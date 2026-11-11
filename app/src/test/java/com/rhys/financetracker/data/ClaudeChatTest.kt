package com.rhys.financetracker.data

import com.rhys.financetracker.data.claude.ClaudeAccess
import com.rhys.financetracker.data.claude.ClaudeChat
import com.rhys.financetracker.data.claude.ClaudeTools
import com.rhys.financetracker.data.claude.FinanceToolbox
import com.rhys.financetracker.data.claude.ToolOutput
import com.rhys.financetracker.domain.claude.ClaudeModel
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A whole conversation against a stand-in for the Claude API: Claude looks
 * things up, answers, takes a follow-up, and a refused key is explained —
 * with the conversation sent back exactly as it came each time.
 */
class ClaudeChatTest {

    private val requests = mutableListOf<String>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private lateinit var server: HttpServer
    private val calls = mutableListOf<Pair<String, Map<*, *>>>()

    private val access = object : ClaudeAccess {
        override suspend fun apiKey(): String? = "sk-ant-test-key"
        override fun model() = ClaudeModel.OPUS
    }

    private val tools = object : ClaudeTools {
        override suspend fun run(name: String, input: Map<*, *>): ToolOutput {
            calls += name to input
            return if (input["kind"] == "bogus") ToolOutput("kind is spending, income, transfer or any.", isError = true) else ToolOutput("Current account: £1,234.56")
        }

        override suspend fun household() = "Sam and Alex"
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestBody.readBytes().decodeToString()
            val (status, body) = replies.removeFirst()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun reply(content: String, stop: String) = 200 to
        """{"id":"msg_x","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$content],"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":80,"cache_creation_input_tokens":900,"cache_read_input_tokens":0}}"""

    @Test
    fun `claude looks things up, answers, and the conversation is replayed unchanged`() = runBlocking {
        replies += reply(
            """{"type":"thinking","thinking":"","signature":"sig-one"},{"type":"tool_use","id":"toolu_1","name":"get_accounts","input":{}},{"type":"tool_use","id":"toolu_2","name":"search_payments","input":{"text":"tesco","kind":"bogus","limit":5,"min_amount":12.5}}""",
            "tool_use",
        )
        replies += reply("""{"type":"thinking","thinking":"","signature":"sig-two"},{"type":"text","text":"You have **£1,234.56** to spend."}""", "end_turn")
        replies += reply("""{"type":"text","text":"Yes."}""", "end_turn")
        replies += 401 to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        replies += reply("""{"type":"text","text":"About the same."}""", "end_turn")

        val chat = ClaudeChat(access, tools, "http://127.0.0.1:${server.address.port}")
        chat.ask("How much can we spend?")
        chat.ask("Is that enough?")
        chat.ask("And next month?")
        chat.ask("And the month after?")

        val turns = chat.turns.value
        assertEquals(
            listOf("How much can we spend?", "You have **£1,234.56** to spend.", "Is that enough?", "Yes.", "And next month?"),
            turns.take(5).map { it.text },
        )
        assertTrue(turns[1].costDollars!! > 0.0)
        assertTrue(turns[5].isError)
        assertTrue(turns[5].text, turns[5].text.contains("API key"))
        assertEquals("About the same.", turns[7].text)

        // Both look-ups ran, with what Claude asked for.
        assertEquals(listOf("get_accounts", "search_payments"), calls.map { it.first })
        assertEquals("tesco", calls[1].second["text"])
        assertEquals(5, (calls[1].second["limit"] as Number).toInt())

        // The first request carries the tools and asks for caching.
        assertTrue(requests[0].contains(""""cache_control":{"type":"ephemeral"}"""))
        FinanceToolbox.SPECS.forEach { spec -> assertTrue(spec.name, requests[0].contains(""""name":"${spec.name}"""")) }
        assertTrue(requests[0].contains("Sam and Alex"))

        // The second sends Claude's own turn back as it came — reasoning and
        // all — with both results, the failed one marked.
        assertTrue(requests[1].contains(""""signature":"sig-one""""))
        assertTrue(requests[1].contains(""""tool_use_id":"toolu_1""""))
        assertTrue(requests[1].contains(""""is_error":true"""))

        // A refused request leaves nothing behind: the next one has the
        // earlier turns and the new question, not the one that failed.
        assertTrue(requests[4].contains("And the month after?"))
        assertFalse(requests[4].contains("And next month?"))
        assertTrue(requests[4].contains(""""signature":"sig-two""""))
    }

    @Test
    fun `a new conversation starts with nothing carried over`() = runBlocking {
        replies += reply("""{"type":"text","text":"One."}""", "end_turn")
        replies += reply("""{"type":"text","text":"Two."}""", "end_turn")
        val chat = ClaudeChat(access, tools, "http://127.0.0.1:${server.address.port}")
        chat.ask("First question")
        chat.newChat()
        assertTrue(chat.turns.value.isEmpty())
        chat.ask("Second question")
        assertFalse(requests[1].contains("First question"))
    }
}
