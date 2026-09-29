package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.OwnAccountMatcher
import com.rhys.financetracker.data.importer.OwnAccountMatcher.Target
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of a person's own accounts a statement row moves money to. */
class OwnAccountMatcherTest {

    private val startToSave = Target(2, "Start to Save", AccountType.SAVINGS, Holding.SET_ASIDE)
    private val rainyDay = Target(3, "Rainy Day Saver", AccountType.SAVINGS, Holding.SET_ASIDE)
    private val carLoan = Target(
        4, "Car loan", AccountType.LOAN, Holding.OWED,
        monthlyPaymentMinor = 18_500L, paymentDay = 1,
    )
    private val barclaycard = Target(5, "Barclaycard", AccountType.CREDIT_CARD, Holding.OWED)
    private val all = listOf(startToSave, rainyDay, carLoan, barclaycard)
    private val sept = LocalDate.of(2026, 9, 2)

    private fun match(
        description: String,
        amount: Long = 20_000L,
        out: Boolean = true,
        isSavings: Boolean = false,
        targets: List<Target> = all,
        learned: Map<String, Long> = emptyMap(),
    ) = OwnAccountMatcher.match(description, amount, sept, out, isSavings, targets, learned)

    @Test
    fun `savers with names on the statement go to the right one`() {
        assertEquals(startToSave, match("TRANSFER TO START TO SAVE 12345678"))
        assertEquals(rainyDay, match("TO RAINY DAY SAVER"))
        assertEquals(startToSave, match("FROM START TO SAVE", out = false))
    }

    @Test
    fun `a loan is found by name or by its own payment`() {
        assertEquals(carLoan, match("CAR LOAN PAYMENT"))
        // The lender's name, but exactly the loan's monthly payment near its day.
        assertEquals(carLoan, match("BLACK HORSE LTD", amount = 18_500L))
        // The same amount a fortnight off its day is not it.
        assertNull(
            OwnAccountMatcher.match(
                "BLACK HORSE LTD", 18_500L, LocalDate.of(2026, 9, 16), true, false, all, emptyMap(),
            ),
        )
        // "Car" alone is not the car loan.
        assertNull(match("NCP CAR PARK", amount = 450L))
    }

    @Test
    fun `a card payment goes to the card`() {
        assertEquals(barclaycard, match("BARCLAYCARD PAYMENT"))
    }

    @Test
    fun `ordinary spending stays ordinary`() {
        assertNull(match("TESCO STORES 2231", amount = 4_512L))
        assertNull(match("CONTACTLESS PAYMENT PETER", amount = 6_000L))
    }

    @Test
    fun `savings with only one saver to go to go there`() {
        assertEquals(
            startToSave,
            match("STANDING ORDER 88", isSavings = true, targets = listOf(startToSave, carLoan)),
        )
        // With two savers and no name, it cannot say which.
        assertNull(match("STANDING ORDER 88", isSavings = true))
    }

    @Test
    fun `a payee filed before goes the same way again`() {
        assertEquals(
            rainyDay,
            match("SO 88 R EVANS", learned = mapOf("so 88 r evans" to 3L)),
        )
    }
}
