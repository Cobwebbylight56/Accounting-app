package com.rhys.financetracker.data.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.claude.ClaudeModel
import com.rhys.financetracker.domain.claude.TokenUsage
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One turn of the conversation as it's shown. */
data class ChatTurn(
    val id: Long,
    val fromYou: Boolean,
    val text: String,
    /** Roughly what Claude's reply cost, in dollars; null for your own questions. */
    val costDollars: Double? = null,
    val isError: Boolean = false,
)

/**
 * A conversation with Claude about the household's money, asked through the
 * Claude API with the user's own key.
 *
 * Claude is given read-only tools (see [FinanceToolbox]) and looks up what
 * it needs. Each reply's steps — Claude's calls, the app's answers — are
 * added to the conversation exactly as they came, never edited: the API
 * checks that what came before is unchanged each time it's sent. For the
 * same reason the instructions and tools are fixed when a conversation
 * starts, and changing model starts a new one.
 */
@Singleton
class ClaudeChat @Inject constructor(
    private val settings: ClaudeSettings,
    private val toolbox: FinanceToolbox,
) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    /** What Claude is doing now — "Searching your payments…" — or null when idle. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working.asStateFlow()

    private val lock = Mutex()
    private val history = mutableListOf<MessageParam>()
    private var instructions: String? = null
    private var model: ClaudeModel? = null
    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private var nextId = 0L

    /** Starts afresh. */
    suspend fun newChat() = lock.withLock {
        history.clear()
        instructions = null
        model = null
        _turns.value = emptyList()
    }

    /** Asks [question] and adds Claude's answer, or what went wrong, to [turns]. */
    suspend fun ask(question: String) = lock.withLock {
        val text = question.trim()
        if (text.isEmpty()) return@withLock
        _turns.value = _turns.value + ChatTurn(nextId++, fromYou = true, text = text)
        _working.value = "Thinking…"
        val reply = try {
            withContext(Dispatchers.IO) { answer(text) }
        } catch (e: Exception) {
            ChatTurn(nextId++, fromYou = false, text = explain(e), isError = true)
        } finally {
            _working.value = null
        }
        _turns.value = _turns.value + reply
    }

    private suspend fun answer(question: String): ChatTurn {
        val key = settings.apiKey() ?: return ChatTurn(nextId++, false, "Add your API key first.", isError = true)
        val chosen = settings.setup.value.model
        if (model != chosen) {
            // Another model can't read this one's reasoning: start again rather than carry it over.
            history.clear()
            instructions = null
        }
        model = chosen
        val system = instructions ?: instructions().also { instructions = it }
        val api = clientFor(key)

        // Everything this reply adds, kept apart until it succeeds, so a
        // failed reply leaves the conversation exactly as it was.
        val added = mutableListOf<MessageParam>(MessageParam.ofUser(question))
        var usage = TokenUsage()
        var steps = 0
        while (true) {
            val params = MessageCreateParams.builder()
                .model(chosen.id)
                .maxTokens(MAX_TOKENS)
                .system(system)
                // Caches everything up to the latest turn, so each step and
                // each follow-up only pays in full for what's new.
                .cacheControl(CacheControlEphemeral.builder().build())
                .apply { TOOLS.forEach { addTool(it) } }
                .messages(history + added)
                .build()
            val response = api.messages().create(params)
            usage += usageOf(response)
            added += response.toParam()

            val stop = response.stopReason().orElse(null)
            if (stop == StopReason.TOOL_USE && steps < MAX_STEPS) {
                steps++
                val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                    _working.value = LABELS[call.name()] ?: "Looking…"
                    @Suppress("UNCHECKED_CAST")
                    val input = runCatching { call._input().convert(Map::class.java) as Map<*, *> }.getOrDefault(emptyMap<String, Any>())
                    val output = toolbox.run(call.name(), input)
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(call.id())
                            .content(output.text)
                            .isError(output.isError)
                            .build(),
                    )
                }
                added += MessageParam.ofUserOfContentBlockParams(results)
                _working.value = "Thinking…"
                continue
            }

            val said = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n\n").trim()
            val text = when {
                stop == StopReason.REFUSAL -> "Claude couldn't help with that one. Try asking another way."
                stop == StopReason.TOOL_USE -> (said.ifEmpty { "That took more looking up than one answer allows." }) +
                    "\n\n(Stopped after $MAX_STEPS look-ups — ask a narrower question to go further.)"
                stop == StopReason.MAX_TOKENS -> said + "\n\n(The answer was cut short.)"
                said.isEmpty() -> "Claude didn't say anything. Try asking again."
                else -> said
            }
            // A reply stopped half way through its look-ups can't be carried on from.
            if (stop != StopReason.TOOL_USE) history += added
            return ChatTurn(nextId++, fromYou = false, text = text, costDollars = chosen.costOf(usage))
        }
    }

    private suspend fun instructions(): String {
        val today = DateUtils.today()
        return """
            You're Claude, helping a UK household understand their money inside their budgeting app, Finance Tracker.
            The household: ${toolbox.household()}. Today is ${today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK))}. Amounts are in pounds.

            Your tools show their accounts and balances, every payment, spending by category, month-by-month totals and regular bills. You can only look: you can't move money or change anything, and account numbers are hidden from you.

            - Use the tools for every figure. Never estimate or invent numbers; if the data doesn't say, say so.
            - Say what dates a figure covers. Spending already leaves out moves between their own accounts, savings and paying off cards.
            - Payments come from bank statements and bank alerts, so the latest few days may not all be in yet.
            - Be warm, practical and brief: this is read on a phone. Short paragraphs, "-" bullets, **bold** for the key figures. No tables or headings.
            - Give specific, realistic suggestions from their own payments: subscriptions to check, categories creeping up, what a change would save in a year.
            - You're not a regulated financial adviser. For investments, pensions, mortgages or debt worries, suggest a qualified adviser or the free MoneyHelper service.
        """.trimIndent()
    }

    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        client?.close()
        return AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofMinutes(TIMEOUT_MINUTES))
            .maxRetries(2)
            .build()
            .also {
                client = it
                clientKey = key
            }
    }

    private fun usageOf(response: Message): TokenUsage {
        val u = response.usage()
        return TokenUsage(
            input = u.inputTokens(),
            output = u.outputTokens(),
            cacheRead = u.cacheReadInputTokens().orElse(0L),
            cacheWrite = u.cacheCreationInputTokens().orElse(0L),
        )
    }

    /** What went wrong, in words someone can act on. */
    private fun explain(e: Exception): String = when (e) {
        is UnauthorizedException -> "That API key wasn't accepted. Check it, or make a new one, in Ask Claude's settings."
        is PermissionDeniedException -> "Your Anthropic account isn't allowed to use this model. Try another in Ask Claude's settings."
        is RateLimitException -> "Too many questions at once — wait a minute and ask again."
        is BadRequestException ->
            if (e.message.orEmpty().contains("credit", ignoreCase = true)) {
                "Your Anthropic account has run out of credit. Add some at platform.claude.com, under Billing."
            } else {
                "Claude couldn't take that question: ${e.message}"
            }
        is InternalServerException -> "Claude is busy right now. Try again in a moment."
        is AnthropicServiceException -> "Claude couldn't answer (${e.statusCode()}): ${e.message}"
        is AnthropicIoException -> "Couldn't reach Claude. Check your internet connection and try again."
        else -> "Something went wrong asking Claude: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        /** Room for Claude's thinking as well as its answer. */
        const val MAX_TOKENS = 16_000L

        /** Look-ups one answer may make before it has to stop. */
        const val MAX_STEPS = 12

        const val TIMEOUT_MINUTES = 3L

        val TOOLS: List<Tool> = FinanceToolbox.SPECS.map { spec ->
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder()
                                .apply { spec.properties.forEach { (name, schema) -> putAdditionalProperty(name, JsonValue.from(schema)) } }
                                .build(),
                        )
                        .required(spec.required)
                        .build(),
                )
                .build()
        }

        val LABELS = mapOf(
            FinanceToolbox.ACCOUNTS to "Looking at your accounts…",
            FinanceToolbox.MONTHS to "Adding up the months…",
            FinanceToolbox.CATEGORIES to "Adding up spending by category…",
            FinanceToolbox.SEARCH to "Searching your payments…",
            FinanceToolbox.BILLS to "Checking your regular payments…",
        )
    }
}
