package com.rhys.financetracker.data

import com.rhys.financetracker.data.live.BankAlertParser
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Banking app alerts in the shapes banks send them. The exact wording varies
 * by bank and changes over time, so these cover the shapes rather than one
 * bank's sentence.
 */
class BankAlertParserTest {

    private fun read(title: String?, text: String?) = BankAlertParser.parse(title, text)

    @Test
    fun `card spending at a shop`() {
        val alert = read("Nationwide", "You've spent £12.50 at TESCO STORES 3012 on your card ending 1234.")!!
        assertEquals(1_250L, alert.amountMinor)
        assertEquals(TransactionType.EXPENSE, alert.type)
        assertEquals("TESCO STORES 3012", alert.payee)
        assertEquals("1234", alert.ending)
    }

    @Test
    fun `amount first`() {
        val alert = read("Card payment", "£4.20 at Costa Coffee")!!
        assertEquals(420L, alert.amountMinor)
        assertEquals("Costa Coffee", alert.payee)
    }

    @Test
    fun `the balance after is not the payment`() {
        val alert = read("Lloyds", "£12.50 has gone out of your account ending 5678 to SHELL WAKEFIELD. Your balance is now £840.10")!!
        assertEquals(1_250L, alert.amountMinor)
        assertEquals(TransactionType.EXPENSE, alert.type)
        assertEquals("SHELL WAKEFIELD", alert.payee)
    }

    @Test
    fun `thousands and pence`() {
        assertEquals(125_000L, read(null, "You paid £1,250.00 to Halifax Mortgage")!!.amountMinor)
        assertEquals(125_000L, read(null, "You paid £1250 to Halifax Mortgage")!!.amountMinor)
        assertEquals(1_250L, read(null, "You paid £12.5 to Ann")!!.amountMinor)
    }

    @Test
    fun `money in from a person`() {
        val alert = read("Money in", "You've received £250.00 from H PAYNE.")!!
        assertEquals(TransactionType.INCOME, alert.type)
        assertEquals(25_000L, alert.amountMinor)
        assertEquals("H PAYNE", alert.payee)
    }

    @Test
    fun `someone paid you`() {
        val alert = read("Monzo", "Hannah Payne paid you £20.00")!!
        assertEquals(TransactionType.INCOME, alert.type)
        assertEquals("Hannah Payne", alert.payee)
    }

    @Test
    fun `payment received is money in, not out`() {
        val alert = read(null, "Payment received: £45.00 from EMPLOYER LTD")!!
        assertEquals(TransactionType.INCOME, alert.type)
        assertEquals("EMPLOYER LTD", alert.payee)
    }

    @Test
    fun `a refund is money in`() {
        assertEquals(TransactionType.INCOME, read(null, "Refund of £9.99 from Amazon")!!.type)
    }

    @Test
    fun `sending money to a person`() {
        val alert = read(null, "You sent £30.00 to Ross Evans from your account")!!
        assertEquals(TransactionType.EXPENSE, alert.type)
        assertEquals("Ross Evans", alert.payee)
    }

    @Test
    fun `a time is not the shop`() {
        val alert = read(null, "You spent £3.10 at 10:23 at Greggs")!!
        assertEquals("Greggs", alert.payee)
    }

    @Test
    fun `wallet style puts the shop in the title`() {
        val alert = read("Tesco Express", "£8.40 with Visa •••• 4321")
        // No direction word: from a bank, read nothing rather than guess.
        assertNull(alert)
        // From Google Wallet, a tap is a card payment.
        val tap = BankAlertParser.parse("Tesco Express", "£8.40 with Visa •••• 4321", assumeSpending = true)!!
        assertEquals(840L, tap.amountMinor)
        assertEquals(TransactionType.EXPENSE, tap.type)
        assertEquals("Tesco Express", tap.payee)
        assertEquals("4321", tap.ending)
        val short = BankAlertParser.parse("Costa Coffee", "£3.10 with Mastercard ••1234", assumeSpending = true)!!
        assertEquals("Costa Coffee", short.payee)
        assertEquals("1234", short.ending)
        // Even from Wallet, a refund is money back, and a card being added is nothing.
        assertEquals(
            TransactionType.INCOME,
            BankAlertParser.parse("ASOS", "Refund of £20.00 to Visa •••• 4321", assumeSpending = true)!!.type,
        )
        assertNull(BankAlertParser.parse("Google Wallet", "Your Visa •••• 4321 is ready to use", assumeSpending = true))
        // Wallet's own name is not the shop.
        assertEquals(
            "Card payment",
            BankAlertParser.parse("Google Wallet", "£5.00 with Visa •••• 4321", assumeSpending = true)!!.payee,
        )
        val paid = read("Tesco Express", "Paid £8.40 with Visa •••• 4321")!!
        assertEquals("Tesco Express", paid.payee)
        assertEquals("4321", paid.ending)
    }

    @Test
    fun `no name falls back to a plain description`() {
        assertEquals("Card payment", read("Nationwide", "Card payment of £5.00")!!.payee)
    }

    @Test
    fun `things that are not payments are ignored`() {
        assertNull(read("Nationwide", "Your one-time passcode is 123456. Never share it."))
        assertNull(read(null, "Your card payment of £50.00 at ASOS was declined"))
        assertNull(read(null, "A Direct Debit of £45.00 to British Gas will be taken tomorrow"))
        assertNull(read(null, "Your balance is £840.10"))
        assertNull(read(null, "Hannah requested £10.00 from you"))
        assertNull(read(null, "Your statement is ready to view"))
        assertNull(read(null, "Approve your payment of £20.00 to Amazon in the app"))
        assertNull(read("Lloyds", "Log in to see your latest offers"))
    }

    @Test
    fun `an overpayment goes to the borrowing`() {
        val alert = read("Nationwide", "You've made a £100.00 overpayment to your mortgage.")!!
        assertEquals(10_000L, alert.amountMinor)
        assertEquals(TransactionType.EXPENSE, alert.type)
        assertEquals("mortgage", alert.toBorrowing)
        assertEquals("Overpayment to mortgage", alert.payee)
        assertEquals("loan", read(null, "Overpayment of £50.00 made towards your car loan")!!.toBorrowing)
        assertEquals("credit card", read(null, "You paid £200.00 to your credit card")!!.toBorrowing)
        assertNull(read(null, "You spent £4.20 at Costa")!!.toBorrowing)
    }

    @Test
    fun `confirmations from payment apps and shops`() {
        val klarna = read("Klarna", "You've paid £25.00 to ASOS with Klarna")!!
        assertEquals("ASOS", klarna.payee)
        assertEquals(TransactionType.EXPENSE, klarna.type)
        val paypal = read("PayPal", "You sent £10.00 to Ross Evans")!!
        assertEquals("Ross Evans", paypal.payee)
        val order = read("Order confirmed", "Your order of £18.40 from Deliveroo is confirmed")!!
        assertEquals(1_840L, order.amountMinor)
        assertEquals("Deliveroo", order.payee)
    }

    @Test
    fun `statement ready alerts`() {
        assertEquals(true, BankAlertParser.isStatementReady("Nationwide", "Your statement is ready to view"))
        assertEquals(true, BankAlertParser.isStatementReady("Lloyds", "A new statement is available in the app"))
        assertEquals(false, BankAlertParser.isStatementReady("Lloyds", "You spent £4.20 at Costa"))
        assertNull(read("Nationwide", "Your statement is ready to view"))
    }
}
