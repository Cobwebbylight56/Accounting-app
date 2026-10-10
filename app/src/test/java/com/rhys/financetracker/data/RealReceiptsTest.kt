package com.rhys.financetracker.data

import com.rhys.financetracker.data.receipts.ItemCategoriser
import com.rhys.financetracker.data.receipts.ReceiptItems
import com.rhys.financetracker.data.receipts.ReceiptItems.Item
import com.rhys.financetracker.data.receipts.ReceiptParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two real receipts, as the camera reads them. The B&M one is crumpled, so
 * its bottom lines come back in more than one way; each is here.
 */
class RealReceiptsTest {

    private val today = LocalDate.of(2026, 10, 10)

    private val bmItems = """
        Big Brands Big Savings
        B&M Retail Ltd
        WalesNP20 2NN
        SALES VOUCHER
        S.E GREYDPAN BRUSH  £1.50
        RED ALPHABET STOCK PK1  £5.00
        RED ALPHABET STOCK PK2  £5.00
        RED BULL 473MLORIGINAL  £4.38
        2 @ £2.19
        RED BULL 6X250MORIGINAL  £6.49
        WMC 2X75GCOTTON  £1.29
        VAMOOSH PET 3X100G  £3.75
        Garfield Milk 200ml  £3.16
        4 @ £0.79
        OASIS 1.5LTRSUM/FRUIT  £3.30
        2 @ £1.65
        2 for £3  -£0.30
        KOKA 85G NOODLE CHICKEN  £1.56
        4 @ £0.39
        No. items 18
    """.trimIndent()

    @Test
    fun `B&M - the amount by Visa sits on the row above it`() {
        val text = bmItems + "\n" + """
            Sub Total:  £35.13
            £35.13
            Visa
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("B&M", reading.shop)
        assertEquals(3_513L, reading.totalMinor)
        assertNull(reading.date)
    }

    @Test
    fun `B&M - the big printed total read with a gap and Visa cut off`() {
        val text = bmItems + "\n" + """
            Sub Total:
            £35. 13
        """.trimIndent()
        assertEquals(3_513L, ReceiptParser.parse(text, today).totalMinor)
    }

    @Test
    fun `B&M - only the subtotal line says it`() {
        val text = bmItems + "\nSub Total:  £35.13"
        assertEquals(3_513L, ReceiptParser.parse(text, today).totalMinor)
    }

    @Test
    fun `B&M - the total unreadable, the items still add up to it`() {
        val text = bmItems + "\nSub Total:  £3S.l3"
        assertEquals(3_513L, ReceiptParser.parse(text, today).totalMinor)
    }

    @Test
    fun `B&M items, with the 2 for 3 offer taken off the Oasis`() {
        val items = ReceiptItems.itemsIn(bmItems + "\nSub Total:  £35.13")
        assertEquals(
            listOf(
                Item("S.E GREYDPAN BRUSH", 150),
                Item("RED ALPHABET STOCK PK1", 500),
                Item("RED ALPHABET STOCK PK2", 500),
                Item("RED BULL 473MLORIGINAL", 438),
                Item("RED BULL 6X250MORIGINAL", 649),
                Item("WMC 2X75GCOTTON", 129),
                Item("VAMOOSH PET 3X100G", 375),
                Item("Garfield Milk 200ml", 316),
                Item("OASIS 1.5LTRSUM/FRUIT", 300),
                Item("KOKA 85G NOODLE CHICKEN", 156),
            ),
            items,
        )
        assertEquals(3_513L, items.sumOf { it.amountMinor })
        assertEquals(
            listOf("Household", null, null, "Drinks", "Drinks", "Personal", "Pets", "Pets", "Drinks", "Groceries"),
            items.map { ItemCategoriser.categoryFor(it.name) },
        )
    }

    private val homeBargains = """
        home bargains
        Top Brands Bottom Prices
        Unit 4 Newport Leisure Park,
        Seven Stiles Avenue, Newport NP19 4QR.
        HOME BARGAINS RE USEABLE BAG  0.20
        30M KITCHEN FOIL EXTRA LONG  1.99
        RED BULL 10PK ENERGY DRINK  9.99
        RED BULL 473ML  2.19
        DR PEPPER 8PK  3.49
        ICED LATTE MACCHIATO CARAMEL  0.59
        SHAR TIKKA MASALA KIT 280G  0.99
        SHAR BUTTER CHICKEN KIT 280G  0.99
        RED BULL 473ML  2.19
        MINI P&W BAKING MALLOWS  0.69
        PEPERAMI ORIGINAL 5PK  1.99
        SHAR KORMA CURRY KIT 280G  0.99
        GINGERBREAD OREOS 154G  0.79
        PAW PATROL SAUSAGE SLICES  0.99
        XMAS CHARACTER MILK BOTTLE  0.79
        XMAS CHARACTER MILK BOTTLE  0.79
        RIBENA BLACKCURRANT 1.5LTR  2.95
        MARTHAS DRY CHICKEN 2KG  2.69
        SQUARES TOTALLY CHOC 8PK  3.19
        SQUARES MARSHMALLOW 8PK  3.19
        TOPLIFE MILK FOR KITTENS 200ML  0.79
        TOPLIFE MILK FOR KITTENS 200ML  0.79
        MARTHAS POUCH 40S JELLY MEAT  7.85
        TOTAL TO PAY  51.10
        CARD TENDER  51.10
        CHANGE DUE  0.00
        VAT @ 20.00%  Net  35.90  VAT  7.18
        VAT @ 0.00%  Net  8.02  VAT  0.00
        PLEASE RETAIN YOUR RECEIPT.
    """.trimIndent()

    @Test
    fun `Home Bargains`() {
        val reading = ReceiptParser.parse(homeBargains, today)
        assertEquals("Home Bargains", reading.shop)
        assertEquals(5_110L, reading.totalMinor)
        assertNull(reading.date)
    }

    @Test
    fun `Home Bargains items come to the total and are sorted`() {
        val items = ReceiptItems.itemsIn(homeBargains)
        assertEquals(23, items.size)
        assertEquals(5_110L, items.sumOf { it.amountMinor })
        val byCategory = items.groupBy { ItemCategoriser.categoryFor(it.name) }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }
        assertEquals(
            mapOf(
                // Red Bull, Dr Pepper, the iced latte, Ribena.
                "Drinks" to 2_140L,
                // Cat food and kitten milk.
                "Pets" to 1_212L,
                // Oreos, Peperami, Squares, the mallows.
                "Food" to 985L,
                // The curry kits, sausage slices, milk bottles.
                "Groceries" to 554L,
                // The bag and the foil.
                "Household" to 219L,
            ),
            byCategory,
        )
    }
}
