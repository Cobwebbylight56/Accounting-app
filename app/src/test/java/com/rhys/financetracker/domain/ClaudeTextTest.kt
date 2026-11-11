package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.claude.ClaudeModel
import com.rhys.financetracker.domain.claude.ClaudeText
import com.rhys.financetracker.domain.claude.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes to Claude has no account numbers; what comes back is shown as it was meant. */
class ClaudeTextTest {

    @Test
    fun `account numbers and sort codes are masked, shop numbers left alone`() {
        assertEquals("Transfer to •••••• ••6684", ClaudeText.maskNumbers("Transfer to 070806 21646684"))
        assertEquals("FPO ••-••-•• ••6684 REF HOLIDAY", ClaudeText.maskNumbers("FPO 07-08-06 21646684 REF HOLIDAY"))
        assertEquals("Card ••4321", ClaudeText.maskNumbers("Card 4929123412344321"))
        assertEquals("TESCO STORES 2231", ClaudeText.maskNumbers("TESCO STORES 2231"))
        assertEquals("Zopa Credit Card ••1486", ClaudeText.maskNumbers("Zopa Credit Card ••1486"))
    }

    @Test
    fun `bullets, bold and headings are read into lines`() {
        val lines = ClaudeText.lines("## Last month\nYou spent **£2,980** in total.\n- Groceries: **£512**\n* Fuel: £180\n\nThat's all.")
        assertEquals(6, lines.size)
        assertEquals(ClaudeText.Line(false, listOf(ClaudeText.Part("Last month", true))), lines[0])
        assertEquals(
            listOf(ClaudeText.Part("You spent ", false), ClaudeText.Part("£2,980", true), ClaudeText.Part(" in total.", false)),
            lines[1].parts,
        )
        assertTrue(lines[2].bullet)
        assertEquals(listOf(ClaudeText.Part("Groceries: ", false), ClaudeText.Part("£512", true)), lines[2].parts)
        assertTrue(lines[3].bullet)
        assertTrue(lines[4].parts.isEmpty())
    }

    @Test
    fun `an unmatched star pair is shown as written`() {
        assertEquals(listOf(ClaudeText.Part("2 ** 3 is 8", false)), ClaudeText.lines("2 ** 3 is 8")[0].parts)
    }

    @Test
    fun `a reply's cost comes from its tokens`() {
        val usage = TokenUsage(input = 10_000, output = 1_000, cacheRead = 20_000, cacheWrite = 5_000)
        // 10k × $4 + 1k × $20 + 20k × $0.20 + 5k × $5, per million.
        assertEquals(0.089, ClaudeModel.OPUS.costOf(usage), 1e-9)
        assertEquals(0.0445, ClaudeModel.SONNET.costOf(usage), 1e-9)
        assertEquals("under 1¢", ClaudeModel.describe(0.004))
        assertEquals("about 9¢", ClaudeModel.describe(0.089))
        assertEquals("about $0.12", ClaudeModel.describe(0.1234))
        assertEquals(ClaudeModel.OPUS, ClaudeModel.byId("no-such-model"))
        assertEquals(ClaudeModel.HAIKU, ClaudeModel.byId("claude-haiku-5-5"))
    }
}
