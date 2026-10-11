package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.CardStatementJunk
import com.rhys.financetracker.data.importer.PdfStatementParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card statement's own writing, read as payments by 1.55, is told from real ones. */
class CardStatementJunkTest {

    private fun lines(name: String): List<String> =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("statements/$name")) { "missing $name" }
            .bufferedReader().readLines()

    @Test
    fun `what was read from Capital One's summary and small print`() {
        listOf(
            "Protect Your Identity .",
            "Minimum Payments will be taken on or just after",
            "is",
            "New transactions",
            "AB1 2CD Payments received",
            "Mr A Customer at",
            "Dear Mr A Customer, Your Direct Debit payment of",
        ).forEach { assertTrue(it, CardStatementJunk.isJunk(it)) }
    }

    @Test
    fun `real payments on a card are left alone`() {
        val real = listOf("capital-one.txt", "capital-one-repaid.txt", "zopa.txt")
            .flatMap { PdfStatementParser.parse(lines(it)) }
            .map { it.description } +
            listOf(
                "TESCO STORES 3294", "BP", "Audible UK", "Direct Debit Payment - Thank You", "Total purchase interest",
                "MARKS AND SPENCER", "SHELL ON THE GO", "PAYPAL *SPOTIFY", "Interest charged", "Late payment fee",
                "Non sterling transaction fee", "Payment received - thank you", "Uber One Membership",
            )
        real.forEach { assertFalse(it, CardStatementJunk.isJunk(it)) }
    }
}
