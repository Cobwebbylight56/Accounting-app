package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.AccountFitCheck
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.local.projection.AccountPayee
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Filing a statement against the wrong account is the one import mistake that
 * leaves no trace: every row imports cleanly and both accounts are quietly
 * wrong afterwards. These cover the warning firing when it should, and — the
 * harder half — staying quiet when the evidence is thin.
 */
class AccountFitCheckTest {

    private val rhys = 1L
    private val hannah = 2L

    private fun row(id: Int, payee: String) = ImportCandidate(
        sourceRow = id,
        sourceColumn = 0,
        name = payee,
        amountMinor = 1_000L,
        target = ImportTarget.TRANSACTION,
        personName = null,
        accountName = null,
        categoryName = null,
        notes = null,
        dayOfMonth = null,
        dateIso = "2026-03-0${(id % 9) + 1}",
        frequencyName = "MONTHLY",
        transactionType = TransactionType.EXPENSE,
    )

    private val statement = listOf(
        "TESCO STORES 3294", "SHELL FILLING STN", "GREGGS PLC 1042",
        "NETFLIX.COM", "VIRGIN MEDIA PAYMENTS", "ALDI 812 CARDIFF",
    ).mapIndexed { index, payee -> row(index, payee) }

    private fun seen(accountId: Long, vararg payees: String) =
        payees.map { AccountPayee(accountId = accountId, description = it, occurrences = 3) }

    @Test
    fun `payees that all live on another account are pointed out`() {
        val verdict = AccountFitCheck.check(
            candidates = statement,
            chosenAccountId = rhys,
            payees = seen(
                hannah,
                "Tesco stores 3294", "Shell filling stn", "Greggs plc 1042", "Netflix.com",
            ),
        )
        assertNotNull(verdict)
        assertEquals(hannah, verdict!!.suggestedAccountId)
        assertEquals(4, verdict.recognisedThere)
        assertEquals(0, verdict.recognisedHere)
        assertEquals(6, verdict.payeesConsidered)
    }

    @Test
    fun `the account the payees actually live on is not warned about`() {
        assertNull(
            AccountFitCheck.check(
                candidates = statement,
                chosenAccountId = hannah,
                payees = seen(
                    hannah,
                    "Tesco stores 3294", "Shell filling stn", "Greggs plc 1042", "Netflix.com",
                ),
            ),
        )
    }

    @Test
    fun `a close call is not raised`() {
        // Households shop in the same places. "Four against three" says
        // nothing, and a warning that fires on ordinary imports gets dismissed
        // without being read — including the time it is right.
        assertNull(
            AccountFitCheck.check(
                candidates = statement,
                chosenAccountId = rhys,
                payees = seen(
                    hannah,
                    "Tesco stores 3294", "Shell filling stn", "Greggs plc 1042", "Netflix.com",
                ) + seen(rhys, "Tesco stores 3294", "Shell filling stn", "Greggs plc 1042"),
            ),
        )
    }

    @Test
    fun `too few payees to judge means no judgement`() {
        assertNull(
            AccountFitCheck.check(
                candidates = statement.take(4),
                chosenAccountId = rhys,
                payees = seen(hannah, "Tesco stores 3294", "Shell filling stn", "Greggs plc 1042"),
            ),
        )
    }

    @Test
    fun `payees nobody recognises prove nothing either way`() {
        // A first statement on a new account looks exactly like this, and it
        // is not a mistake.
        assertNull(
            AccountFitCheck.check(
                candidates = statement,
                chosenAccountId = rhys,
                payees = seen(hannah, "Waitrose", "Boots the chemist"),
            ),
        )
    }

    @Test
    fun `an empty ledger is never second-guessed`() {
        assertNull(
            AccountFitCheck.check(
                candidates = statement,
                chosenAccountId = rhys,
                payees = emptyList(),
            ),
        )
    }

    @Test
    fun `wording differences do not hide the match`() {
        // The ledger and the bank rarely agree on capitals or spacing, and a
        // check that only matched exact text would never fire at all.
        val verdict = AccountFitCheck.check(
            candidates = statement,
            chosenAccountId = rhys,
            payees = seen(
                hannah,
                "tesco  stores 3294", "SHELL FILLING STN", "Greggs PLC 1042", "netflix.com",
            ),
        )
        assertEquals(4, verdict?.recognisedThere)
    }
}
