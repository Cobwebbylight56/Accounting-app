package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.PdfStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Nationwide FlexDirect statement as the PDF text comes out, with names and
 * account details made up. Its side panel (IBAN, "Average credit balance",
 * sort code) lands on payment lines, a day's later payments carry no date or
 * balance, and a card payment's shop is on the line below.
 */
class NationwideStatementTest {

    private val text = """
Your FlexDirect account

Mr A B C Sample

Statement date: 08 February 2026
transactions Statement no 101   1 of 2 Account no
Start balance £4,439.44
End balance £4,773.29
Date Description £ Out £ In £ Balance Average credit
2026 Balance from statement 100 dated 08/01/2026 4,439.44 balance £3,778.58
Average debit
10 Jan Contactless Payment 36.76 balance £0.00
ASDA LIVING STORE 4346 NEWPORT
Receiving an
TESCO PAY AT PUMP 3839 NEWPORT 56.87 4,345.81 International Payment?
12 Jan Direct debit EE LIMITED 20.50 4,325.31 BIC NAIAGB21
14 Jan Payment to ALEX SAMPLE 1.00 IBAN  GB00 NAIA 0000 0000 0000 00
Payment to ALEX SAMPLE 249.00 4,075.31 Swift
Intermediary Bank MIDLGB22
16 Jan Direct debit SAMSUNGFINANCEGLOW 19.77 4,055.54
19 Jan Contactless Payment 4.10
TESCO STORES 6231 LONDON
Effective Date 18 Jan 2026
AMZNMktplace*MW2HH9I15 amazon.co. 8.73
Effective Date 18 Jan 2026
Direct debit PC/GOSKIPPY INS 60.76 3,981.95
20 Jan Direct debit PAYPAL PAYMENT 325.00
Contactless Payment 30.01 3,626.94
TESCO PFS 3839 NEWPORT
GOOGLE ****4374
21 Jan Direct debit VIRGIN MEDIA PYMTS 39.00
Contactless Payment 5.00
FRIERS STORE NEWPORT
Contactless Payment 6.63
TESCO-STORES 6459 NEWPORT
GOOGLE ****4374
Contactless Payment 26.29 3,550.02
ICELAND GWENT
26 Jan TESCO PAY AT PUMP 3952 NEWPORT G 58.70
Effective Date 25 Jan 2026
HOME BARGAINS NEWPORT WES NEWP 77.28
Effective Date 25 Jan 2026
Nationwide Building Society is authorised by the Prudential Regulation Authority and regulated by the Financial Conduct Authority and the Prudential Regulation Authority under registration number 106078.
Head Office: Nationwide Building Society, Nationwide House, Pipers Way, Swindon, SN38 1NW
DC83 (October 2023)_CSIS
Your FlexDirect account
transactions (continued)
Date Description £ Out £ In £ Balance
2026 3,414.04
26 Jan Contactless Payment 78.01
PETS AT HOME LTD NEWPORT
Effective Date 25 Jan 2026
Payment to JO BLOGGS 80.00 3,256.03
Effective Date 25 Jan 2026 Statement date 08 February 2026
30 Jan Direct debit UTILITY WAREHOUSE 183.95
Statement no 101  2 of 2
Returned direct debit 183.95 3,256.03
02 Feb Standing order PAT JONES 4.50 Sort code 00-00-00
Returned standing order 4.50 Account no 00000000
PAT JONES
Beneficiary Account Stopped
Standing order ALEX SAMPLE 250.00
Returned standing order 250.00
ALEX SAMPLE
Beneficiary Account Stopped
Direct debit DVLA-AB12CDE 37.62
Returned direct debit 37.62
Direct debit DWR CYMRU WELSH WA 71.00
Returned direct debit 71.00 3,256.03
04 Feb Payment to ALEX SAMPLE 250.00
Transfer to 000000 00000000 50.00 2,956.03
06 Feb Direct debit DVLA-AB12CDE 37.62
Contactless Payment 7.35
TESCO-STORES 6459 NEWPORT
Bank credit A Sample 0.01
Bank credit A Sample 1,862.22 4,773.29
work wage
DC86 (October 2023) CSIS
Your FlexDirect account
Please check your statement to make sure everything's correct.
Paying in £1,000 or more per month AER Gross p.a.
Credit Interest 5% 4.89%
arranged overdraft it will cost you: £2.94 for 7 days
Chaps £15 transaction fee each time statement. Entering account numbers incorrectly
visit: nationwide.co.uk/contact-us DC85 (May 2025) CSIS
""".trimIndent().lines()

    private val rows = PdfStatementParser.parse(text)

    @Test
    fun `every payment is read, including those with no date or balance of their own`() {
        val out = rows.sumOf { it.moneyOutMinor ?: 0L }
        val inward = rows.sumOf { it.moneyInMinor ?: 0L }
        // Start £4,439.44, end £4,773.29: nothing missing, nothing extra.
        assertEquals(477_329L - 443_944L, inward - out)
        assertTrue(rows.any { it.moneyOutMinor == 873L && it.description.startsWith("AMZN") })
        assertTrue(rows.any { it.moneyOutMinor == 7_728L && it.description.startsWith("HOME BARGAINS") })
        assertTrue(rows.any { it.moneyOutMinor == 500L })
        assertTrue(rows.any { it.moneyOutMinor == 663L })
        assertTrue(rows.any { it.moneyOutMinor == 100L && it.description.contains("ALEX SAMPLE") })
    }

    @Test
    fun `the side panel never becomes a payment or a balance`() {
        assertTrue(rows.none { it.description.contains("IBAN", ignoreCase = true) })
        assertTrue(rows.none { it.description.contains("Sort code", ignoreCase = true) })
        assertTrue(rows.none { it.moneyOutMinor == 377_858L || it.moneyInMinor == 377_858L })
        assertTrue(rows.none { it.moneyOutMinor == 0L && it.moneyInMinor == null })
    }

    @Test
    fun `a card payment takes the shop's name from the line below`() {
        assertTrue(rows.any { it.description == "Contactless Payment FRIERS STORE NEWPORT" })
        assertTrue(rows.any { it.description == "Contactless Payment ASDA LIVING STORE 4346 NEWPORT" })
        assertTrue(rows.any { it.description == "Contactless Payment PETS AT HOME LTD NEWPORT" })
    }

    @Test
    fun `returned payments come back in`() {
        val returned = rows.filter { it.description.startsWith("Returned") }
        assertEquals(5, returned.size)
        assertTrue(returned.all { it.moneyInMinor != null && it.moneyOutMinor == null })
    }

    @Test
    fun `the wage is money in and the dates are right`() {
        val wage = rows.single { it.moneyInMinor == 186_222L }
        assertEquals("2026-02-06", wage.date.toString())
        assertNull(wage.moneyOutMinor)
        assertEquals("2026-01-10", rows.first().date.toString())
    }
}
