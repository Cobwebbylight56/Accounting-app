package com.rhys.financetracker.data

import com.rhys.financetracker.data.receipts.EReceipts
import com.rhys.financetracker.data.receipts.ReceiptParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receipts that arrive as words: an order email's text selected and shared,
 * a website's receipt page, an invoice PDF. Made-up orders in the shapes
 * these shops use.
 */
class EReceiptTest {

    private val today = LocalDate.of(2026, 10, 16)

    private val amazonEmail = """
        Hello A Customer,
        Thank you for your order. We'll send a confirmation when your item ships.
        Order #204-0000000-0000000
        Placed on Tuesday, 14 October 2026
        Arriving Saturday 18 October
        USB-C charging cable, 2m
        Quantity: 1
        £8.99
        Item(s) Subtotal: £20.98
        Postage & Packing: £0.00
        Order Total (2 items): £20.98
    """.trimIndent()

    @Test
    fun `an Amazon order email is read for the shop, the total and the day it was placed`() {
        val text = EReceipts.compose("Your Amazon.co.uk order #204-0000000-0000000", amazonEmail)
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Amazon", reading.shop)
        assertEquals(2_098L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 14), reading.date)
    }

    @Test
    fun `an invoice's total including VAT is the total, not the VAT`() {
        val text = """
            Currys
            VAT invoice
            Invoice date 12/10/2026
            Order number 0000000
            Kettle 1.7L               £29.99
            Delivery                  £0.00
            VAT @ 20%                 £5.00
            Total (inc. VAT)          £29.99
            Paid with Visa ending 0000
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Currys", reading.shop)
        assertEquals(2_999L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 12), reading.date)
        assertEquals("0000", reading.cardEnding)
    }

    @Test
    fun `a takeaway email with a greeting at the top names the takeaway, not the greeting`() {
        val text = """
            Hi A,
            Thanks for your order from Deliveroo
            Pizza Place – Wakefield
            Margherita              £9.50
            Garlic bread            £3.50
            Subtotal                £13.00
            Delivery fee            £2.49
            Service fee             £0.99
            Rider tip               £1.00
            Total                   £17.48
            Oct 15, 2026
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Deliveroo", reading.shop)
        assertEquals(1_748L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 15), reading.date)
    }

    @Test
    fun `a PayPal receipt names who was paid`() {
        val text = """
            PayPal
            You sent a payment of £23.47 GBP to Acme Trading Ltd
            Transaction date 13 Oct 2026
            Paid with Visa Debit
            Total £23.47 GBP
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Acme Trading", reading.shop)
        assertEquals(2_347L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 13), reading.date)
    }

    @Test
    fun `a card machine's merchant copy is not taken for the shop`() {
        val text = """
            MERCHANT COPY
            MERCHANT ID 00000000
            SALE
            TOTAL £6.40
            12/10/26
        """.trimIndent()
        assertEquals(null, ReceiptParser.parse(text, today).shop?.takeIf { it.contains("Copy", ignoreCase = true) || it.startsWith("Id") })
    }

    @Test
    fun `a receipt PDF is told from a bank statement`() {
        val invoice = """
            Currys
            VAT invoice
            Order number 0000000
            Kettle 1.7L £29.99
            Delivery £0.00
            Total (inc. VAT) £29.99
        """.trimIndent()
        assertTrue(EReceipts.looksLikeReceipt(invoice))
        assertTrue(EReceipts.looksLikeReceipt(amazonEmail))

        val statement = """
            Your statement
            Sort code 00-00-00 Account number 00000000
            Date Description Money out Money in Balance
            01 Mar 2026 Balance brought forward 2,000.00
            01 Mar 2026 TESCO STORES 3294 42.15 1,957.85
            02 Mar 2026 AMAZON ORDER 12.99 1,944.86
        """.trimIndent()
        assertFalse(EReceipts.looksLikeReceipt(statement))
        // A letter with an amount in it isn't a receipt either.
        assertFalse(EReceipts.looksLikeReceipt("Dear customer, your new card limit is £1,500.00."))
    }

    @Test
    fun `a shared link is told apart from a receipt's words`() {
        assertTrue(EReceipts.isJustALink("https://www.amazon.co.uk/gp/your-account/order-details?orderID=204"))
        assertTrue(EReceipts.isJustALink("Your order https://example.com/o/1"))
        assertFalse(EReceipts.isJustALink(amazonEmail))
        assertFalse(EReceipts.isJustALink(""))
    }

    @Test
    fun `the subject goes first, once`() {
        assertEquals("Your receipt from Apple\nTOTAL £4.99", EReceipts.compose("Your receipt from Apple", "TOTAL £4.99"))
        assertEquals("TOTAL £4.99", EReceipts.compose(null, "TOTAL £4.99"))
        assertEquals("Apple TOTAL £4.99", EReceipts.compose("Apple", "Apple TOTAL £4.99"))
    }
}
