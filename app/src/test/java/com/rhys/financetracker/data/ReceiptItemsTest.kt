package com.rhys.financetracker.data

import com.rhys.financetracker.data.receipts.ItemCategoriser
import com.rhys.financetracker.data.receipts.ReceiptItems
import com.rhys.financetracker.data.receipts.ReceiptItems.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptItemsTest {

    @Test
    fun `a supermarket shop with a T-shirt and a saving`() {
        val text = """
            TESCO
            Wakefield Extra
            Tel 0345 677 9001
            WHOLE MILK 4 PINTS  1.65
            WARBURTONS BREAD  1.40
            F&F MENS TSHIRT  8.00
            FAIRY WASHING UP  1.75 A
            Clubcard Price  -0.25
            SUBTOTAL  12.55
            TOTAL  12.55
            VISA  12.55
        """.trimIndent()
        assertEquals(
            listOf(
                Item("WHOLE MILK 4 PINTS", 165),
                Item("WARBURTONS BREAD", 140),
                Item("F&F MENS TSHIRT", 800),
                Item("FAIRY WASHING UP", 150),
            ),
            ReceiptItems.itemsIn(text),
        )
    }

    @Test
    fun `a fuel receipt with a drink and crisps`() {
        val text = """
            TESCO PFS
            PUMP 4 UNLEADED
            32.45L @ 139.9p  45.40
            COCA COLA 500ML  1.85
            WALKERS CRISPS  1.35
            TOTAL  48.60
        """.trimIndent()
        val items = ReceiptItems.itemsIn(text)
        assertEquals(listOf(Item("PUMP 4 UNLEADED", 4_540), Item("COCA COLA 500ML", 185), Item("WALKERS CRISPS", 135)), items)
        assertEquals(listOf("Fuel", "Snacks & drinks", "Snacks & drinks"), items.map { ItemCategoriser.categoryFor(it.name) })
    }

    @Test
    fun `quantities price the name above`() {
        val text = "HEINZ BEANS\n4 @ 1.10  4.40\nTOTAL 4.40"
        assertEquals(listOf(Item("HEINZ BEANS", 440)), ReceiptItems.itemsIn(text))
    }

    @Test
    fun `items are sorted into categories`() {
        assertEquals("Clothes", ItemCategoriser.categoryFor("F&F MENS TSHIRT"))
        assertEquals("Household", ItemCategoriser.categoryFor("FAIRY WASHING UP"))
        assertEquals("Household", ItemCategoriser.categoryFor("ANDREX TOILET ROLL 9PK"))
        assertEquals("Personal", ItemCategoriser.categoryFor("COLGATE TOOTHPASTE"))
        assertEquals("Fuel", ItemCategoriser.categoryFor("UNLD 95"))
        assertEquals("Snacks & drinks", ItemCategoriser.categoryFor("CADBURY CHOCOLATE"))
        assertEquals("Children", ItemCategoriser.categoryFor("PAMPERS SIZE 4"))
        // Food stays with the payment's own category.
        assertNull(ItemCategoriser.categoryFor("WHOLE MILK 4 PINTS"))
        assertNull(ItemCategoriser.categoryFor("CHICKEN BREASTS"))
    }

    @Test
    fun `what the user chose before wins`() {
        val learned = mapOf(ItemCategoriser.keyOf("TSCO FNST CHKN 500G") to "Groceries")
        assertEquals("Groceries", ItemCategoriser.categoryFor("TSCO FNST CHKN 1KG", learned))
        assertEquals("tsco fnst chkn", ItemCategoriser.keyOf("TSCO FNST CHKN 500G 3.50"))
    }
}
