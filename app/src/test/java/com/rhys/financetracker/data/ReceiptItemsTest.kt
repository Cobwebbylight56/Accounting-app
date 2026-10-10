package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.receipts.ItemCategoriser
import com.rhys.financetracker.data.receipts.ReceiptItems
import com.rhys.financetracker.data.receipts.ReceiptItems.Item
import com.rhys.financetracker.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(listOf("Fuel", "Drinks", "Food"), items.map { ItemCategoriser.categoryFor(it.name) })
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
        assertEquals("Food", ItemCategoriser.categoryFor("CADBURY CHOCOLATE"))
        assertEquals("Children", ItemCategoriser.categoryFor("PAMPERS SIZE 4"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("WHOLE MILK 4 PINTS"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("CHICKEN BREASTS"))
        // Nothing to go on: stays with the payment's own category.
        assertNull(ItemCategoriser.categoryFor("RED ALPHABET STOCK PK1"))
    }

    @Test
    fun `words that would put an item in the wrong group`() {
        assertEquals("Drinks", ItemCategoriser.categoryFor("DR PEPPER 2L"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("RED PEPPERS 3PK"))
        assertEquals("Food", ItemCategoriser.categoryFor("DAIRY MILK CHOCOLATE"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("BABY POTATOES 1KG"))
        assertEquals("Children", ItemCategoriser.categoryFor("BABY WIPES 4PK"))
        assertEquals("Food", ItemCategoriser.categoryFor("MONSTER MUNCH 6PK"))
        assertEquals("Drinks", ItemCategoriser.categoryFor("MONSTER ENERGY 500ML"))
        assertEquals("Food", ItemCategoriser.categoryFor("GREGGS SAUSAGE ROLL"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("PORK SAUSAGES 8PK"))
        assertEquals("Household", ItemCategoriser.categoryFor("ANDREX TOILET ROLLS"))
        assertEquals("Drinks", ItemCategoriser.categoryFor("TROPICANA ORANGE JUICE"))
        assertEquals("Groceries", ItemCategoriser.categoryFor("EASY PEELER ORANGES"))
        assertEquals("Food", ItemCategoriser.categoryFor("CREME EGG EASTER EGG"))
        assertEquals("Drinks", ItemCategoriser.categoryFor("STELLA 4X440ML"))
        assertEquals("Personal", ItemCategoriser.categoryFor("SUN CREAM SPF50"))
    }

    @Test
    fun `every group is a category the app makes`() {
        val made = DefaultData.defaultCategories().filter { it.kind == CategoryKind.EXPENSE }.map { it.name }
        ItemCategoriser.GROUPS.forEach { group -> assertTrue(group.category, group.category in made) }
    }

    @Test
    fun `the groups come in a fixed order, anything else after`() {
        val names = listOf("Household", "Shopping", "Drinks", "Groceries", "Food")
        assertEquals(listOf("Groceries", "Food", "Drinks", "Household", "Shopping"), names.sortedBy(ItemCategoriser::orderOf))
    }

    @Test
    fun `what the user chose before wins`() {
        val learned = mapOf(ItemCategoriser.keyOf("TSCO FNST CHKN 500G") to "Groceries")
        assertEquals("Groceries", ItemCategoriser.categoryFor("TSCO FNST CHKN 1KG", learned))
        assertEquals("tsco fnst chkn", ItemCategoriser.keyOf("TSCO FNST CHKN 500G 3.50"))
    }
}
