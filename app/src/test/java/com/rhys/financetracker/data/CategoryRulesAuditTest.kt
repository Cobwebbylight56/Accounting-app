package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.MerchantCategoriser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real payment names, as statements print them, and where each belongs.
 * Each one here was, or could easily have been, filed wrongly by a broader
 * rule matching first.
 */
class CategoryRulesAuditTest {

    private val expected = listOf(
        // Fuel at supermarket forecourts: Tesco PFS is a petrol filling station.
        "Contactless Payment TESCO PFS 3839 NEWPORT" to "Fuel",
        "TESCO PAY AT PUMP 3839 NEWPORT" to "Fuel",
        "TESCO PAY AT PUMP 3952 NEWPORT G" to "Fuel",
        "SAINSBURYS PFS 1234" to "Fuel",
        "ASDA PFS NEWPORT" to "Fuel",
        "MORRISONS PETROL" to "Fuel",
        "ESSO MFG NEWPORT" to "Fuel",
        "BP CONNECT CARDIFF" to "Fuel",
        "SHELL CARDIFF RD" to "Fuel",
        "GULF SERVICE STATION" to "Fuel",
        "EG ON THE MOVE" to "Fuel",
        // The weekly shop.
        "Contactless Payment TESCO STORES 6231 LONDON" to "Groceries",
        "Contactless Payment TESCO-STORES 6459 NEWPORT" to "Groceries",
        "Contactless Payment ICELAND GWENT" to "Groceries",
        "SAINSBURYS S/MKTS" to "Groceries",
        "M&S SIMPLY FOOD" to "Groceries",
        "MARKS & SPENCER" to "Groceries",
        "CO-OP GROUP FOOD" to "Groceries",
        "LIDL GB NEWPORT" to "Groceries",
        "ALDI STORES" to "Groceries",
        // Shops that share a supermarket's name but are not food.
        "Contactless Payment ASDA LIVING STORE 4346 NEWPORT" to "Shopping",
        "GEORGE AT ASDA" to "Shopping",
        "TESCO F&F CLOTHING" to "Shopping",
        "B&Q NEWPORT" to "Shopping",
        "B&M BARGAINS" to "Shopping",
        "H&M NEWPORT" to "Shopping",
        "HOME BARGAINS NEWPORT WES NEWP" to "Shopping",
        "AMZNMktplace*MW2HH9I15 amazon.co." to "Shopping",
        "ARGOS LTD" to "Shopping",
        "SAINSBURYS ARGOS" to "Shopping",
        "PRIMARK NEWPORT" to "Shopping",
        "TK MAXX" to "Shopping",
        "COOPERS DIY" to "Repairs",
        // Hobbies.
        "HOBBYCRAFT NEWPORT" to "Hobbies",
        "GAMES WORKSHOP" to "Hobbies",
        "ANGLING DIRECT" to "Hobbies",
        // Other everyday places.
        "Contactless Payment PETS AT HOME LTD NEWPORT" to "Pets",
        "Contactless Payment GREGGS PLC" to "Eating out",
        "COSTA COFFEE" to "Eating out",
        "FRANKIE & BENNYS" to "Eating out",
        "Direct debit EE LIMITED" to "Mobile",
        "Direct debit VIRGIN MEDIA PYMTS" to "Broadband",
        "Direct debit PC/GOSKIPPY INS" to "Car insurance",
        "Direct debit DVLA-CV03BND" to "Road tax",
        "Direct debit UTILITY WAREHOUSE" to "Energy",
        "Direct debit DWR CYMRU WELSH WA" to "Water",
        "Direct debit SAMSUNGFINANCEGLOW" to "Credit & loans",
        "LEGAL & GENERAL" to "Insurance",
        "BOOTS NEWPORT" to "Health",
    )

    /** Payments that are not shops, which must not be filed as one. */
    private val notThese = listOf(
        // A bank's way of saying how a transfer was sent, not a phone bill.
        "MOBILE PAYMENT TO J SMITH" to "Mobile",
        "SPARE ROOM LTD" to "Groceries",
        "SPARKS BAR" to "Groceries",
    )

    @Test
    fun `every payment goes where it belongs`() {
        val wrong = expected.mapNotNull { (description, category) ->
            val got = MerchantCategoriser.categoryFor(description)
            if (got == category) null else "$description → $got (should be $category)"
        }
        assertEquals("Filed wrongly:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test
    fun `bank wording and look-alike names are not filed as a shop`() {
        val wrong = notThese.mapNotNull { (description, notCategory) ->
            val got = MerchantCategoriser.categoryFor(description)
            if (got == notCategory) "$description → $got" else null
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a bare card payment learned once does not claim every shop after it`() {
        // Somebody filed the old, shop-less "Contactless Payment" rows as
        // Groceries. That must not make every card payment Groceries.
        val learned = mapOf("contactless payment" to "Groceries")
        assertEquals(
            "Fuel",
            MerchantCategoriser.categoryFor("Contactless Payment TESCO PFS 3839 NEWPORT", learned = learned),
        )
        assertEquals("Groceries", MerchantCategoriser.categoryFor("Contactless Payment", learned = learned))
    }

    @Test
    fun `what the user chose for a payee outranks the list, under any reference`() {
        // Filed one Tesco PFS under Groceries by hand: every Tesco PFS goes
        // there, whatever its branch number, even though the list says Fuel.
        val learned = mapOf("card payment tesco pfs 3012" to "Groceries")
        assertEquals("Groceries", MerchantCategoriser.categoryFor("CARD PAYMENT TESCO PFS 4471", learned = learned))
        assertEquals("Groceries", MerchantCategoriser.categoryFor("TESCO PFS 9", learned = learned))
        // A different payee is not claimed.
        assertEquals("Fuel", MerchantCategoriser.categoryFor("SHELL NEWPORT", learned = learned))
    }

    @Test
    fun `money in from a person is a transfer, not uncategorised`() {
        val income = com.rhys.financetracker.domain.model.TransactionType.INCOME
        assertEquals("Transfers & payments", MerchantCategoriser.categoryFor("Bank credit H Payne", income))
        assertEquals("Transfers & payments", MerchantCategoriser.categoryFor("FASTER PAYMENT FROM HANNAH PAYNE", income))
        assertEquals("Transfers & payments", MerchantCategoriser.categoryFor("H PAYNE", income))
        // Wages, benefits and refunds still come first.
        assertEquals("Salary", MerchantCategoriser.categoryFor("ACME LTD SALARY", income))
        assertEquals("Refunds", MerchantCategoriser.categoryFor("AMAZON REFUND", income))
    }

    @Test
    fun `money to a person is a transfer, but PayPal shopping is still spending`() {
        assertEquals("Transfers & payments", MerchantCategoriser.categoryFor("FASTER PAYMENT TO HANNAH PAYNE"))
        assertEquals("Transfers & payments", MerchantCategoriser.categoryFor("STANDING ORDER A EVANS"))
        assertEquals("Payment apps", MerchantCategoriser.categoryFor("PAYPAL PAYMENT 88213"))
        assertEquals("Shopping", MerchantCategoriser.categoryFor("PAYPAL *EBAY"))
        // A shop is never a person, whatever the wording.
        assertEquals("Groceries", MerchantCategoriser.categoryFor("PAYMENT TO TESCO STORES"))
    }
}
