package com.rhys.financetracker.domain.claude

import java.util.Locale

/**
 * The Claude models Ask Claude can use, and what each costs. Prices are
 * Anthropic's per million tokens in US dollars, which is how the API is
 * billed: only for what's used, from the user's own Anthropic account.
 */
enum class ClaudeModel(
    /** The model id the API takes. */
    val id: String,
    val label: String,
    val blurb: String,
    private val inputPerMillion: Double,
    private val outputPerMillion: Double,
    private val cacheReadPerMillion: Double,
    private val cacheWritePerMillion: Double,
) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "The best answers", 4.0, 20.0, 0.20, 5.0),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Very good, about half the cost", 2.0, 10.0, 0.10, 2.5),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", "Quickest, and a fraction of the cost", 0.10, 0.50, 0.01, 0.125),
    ;

    /** Roughly what a reply cost, in dollars, from the tokens it used. */
    fun costOf(usage: TokenUsage): Double =
        (usage.input * inputPerMillion + usage.output * outputPerMillion +
            usage.cacheRead * cacheReadPerMillion + usage.cacheWrite * cacheWritePerMillion) / MILLION

    companion object {
        val DEFAULT = OPUS

        fun byId(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: DEFAULT

        private const val MILLION = 1_000_000.0

        /** "under 1¢", "about 3¢", "about $0.12". */
        fun describe(dollars: Double): String = when {
            dollars < 0.01 -> "under 1¢"
            dollars < 0.10 -> "about ${Math.round(dollars * 100)}¢"
            else -> "about $" + String.format(Locale.UK, "%.2f", dollars)
        }
    }
}

/** Tokens one reply used, added up over every step of it. */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        input + other.input, output + other.output, cacheRead + other.cacheRead, cacheWrite + other.cacheWrite,
    )
}
