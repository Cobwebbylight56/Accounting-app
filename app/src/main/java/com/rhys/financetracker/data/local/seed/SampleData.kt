package com.rhys.financetracker.data.local.seed

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType

/**
 * A worked example of a real household budget, taken directly from the
 * spreadsheet this app replaces.
 *
 * Loading it (Settings → Sample data) gives a new user something to explore
 * before typing anything in, and gives the import feature a known-good target
 * to be checked against.  Every figure below is the monthly amount from the
 * original "Book r and h" workbook.
 *
 * Cross-checks that must continue to hold — they are asserted by
 * `SampleDataTest`:
 *  * Rhys's outgoings total £1,941.63 against income of £1,862.23 (−£79.40).
 *  * Hannah's outgoings total £1,558.14 against income of £1,447.00 (−£111.14).
 *  * The opening balances total £11,418.37 ("ALL SAVINGS").
 */
object SampleData {

    const val PERSON_RHYS = "Rhys"
    const val PERSON_HANNAH = "Hannah"

    /** Annual gross pay, recorded as a note on each salary rule. */
    const val GROSS_ANNUAL_RHYS = "27,455.76"
    const val GROSS_ANNUAL_HANNAH = "16,692.48"

    data class SamplePerson(
        val name: String,
        val colorHex: String,
    )

    data class SampleAccount(
        val name: String,
        val type: AccountType,
        val personName: String,
        val openingBalanceMajor: Double,
        val colorHex: String,
        val notes: String? = null,
    )

    /** A recurring income or bill. Amounts are the monthly figures from the sheet. */
    data class SampleRule(
        val name: String,
        val amountMajor: Double,
        val type: TransactionType,
        val personName: String,
        val accountName: String,
        val categoryName: String,
        val dayOfMonth: Int,
        val frequency: Frequency = Frequency.MONTHLY,
        val isVariableAmount: Boolean = false,
        val notes: String? = null,
    )

    data class SampleGoal(
        val name: String,
        val targetMajor: Double,
        val monthlyContributionMajor: Double,
        val accountName: String?,
        val colorHex: String,
        val iconKey: String,
        val notes: String? = null,
    )

    val people: List<SamplePerson> = listOf(
        SamplePerson(PERSON_RHYS, "#1565C0"),
        SamplePerson(PERSON_HANNAH, "#AD1457"),
    )

    /**
     * The "savings &" block of the spreadsheet.  Together these come to
     * £11,418.37, the sheet's ALL SAVINGS figure.
     */
    val accounts: List<SampleAccount> = listOf(
        SampleAccount("Rhys bank", AccountType.CURRENT, PERSON_RHYS, 3_508.37, "#1565C0"),
        SampleAccount("Hannah bank", AccountType.CURRENT, PERSON_HANNAH, 3_000.00, "#AD1457"),
        SampleAccount("Cash", AccountType.CASH, PERSON_RHYS, 1_510.00, "#558B2F"),
        SampleAccount(
            "Overflow bank", AccountType.SAVINGS, PERSON_RHYS, 0.00, "#00796B",
            notes = "The sheet's \"over bank\" row.",
        ),
        SampleAccount(
            "Saver (Mum)", AccountType.SAVINGS, PERSON_RHYS, 1_900.00, "#2E7D32",
            notes = "The sheet's \"saver mum\" row.",
        ),
        SampleAccount(
            "£1 coins", AccountType.CASH, PERSON_RHYS, 500.00, "#F9A825",
            notes = "Coin jar.",
        ),
        SampleAccount(
            "Main account", AccountType.CURRENT, DefaultData.SHARED_PERSON_NAME, 1_000.00, "#455A64",
            notes = "Shared household account.",
        ),
    )

    /** Income. Salaries are paid on the 28th, the usual UK pay date. */
    val incomeRules: List<SampleRule> = listOf(
        SampleRule(
            name = "Rhys salary",
            amountMajor = 1_862.23,
            type = TransactionType.INCOME,
            personName = PERSON_RHYS,
            accountName = "Rhys bank",
            categoryName = "Salary",
            dayOfMonth = 28,
            notes = "Take-home pay. Gross £$GROSS_ANNUAL_RHYS a year before tax.",
        ),
        SampleRule(
            name = "Hannah salary",
            amountMajor = 1_447.00,
            type = TransactionType.INCOME,
            personName = PERSON_HANNAH,
            accountName = "Hannah bank",
            categoryName = "Salary",
            dayOfMonth = 28,
            notes = "Take-home pay. Gross £$GROSS_ANNUAL_HANNAH a year before tax.",
        ),
    )

    /**
     * The OUTGOINGS block.  The spreadsheet's abbreviations are expanded into
     * names that will still make sense in a year's time; the original label is
     * kept in the notes so the two can be reconciled.
     */
    val expenseRules: List<SampleRule> = listOf(
        // ------------------------------------------------------------ Rhys
        rhys("Car finance", 60.75, "Car finance", 5, note = "sheet row \"car\""),
        rhys("Road tax", 37.62, "Road tax", 1, note = "sheet row \"road tax\""),
        rhys("Fuel", 80.00, "Fuel", 15, variable = true),
        rhys("Energy", 200.00, "Energy", 1, variable = true),
        rhys("Water", 45.00, "Water", 1),
        rhys("Council tax", 162.00, "Council tax", 1, note = "sheet row \"tax\""),
        rhys("Broadband", 30.00, "Broadband", 10, note = "sheet row \"wifi\""),
        rhys("Food shopping", 250.00, "Groceries", 1, variable = true),
        rhys("Days out", 80.00, "Days out", 1, variable = true, note = "sheet row \"outings\""),
        rhys("YouTube Premium", 4.60, "Subscriptions", 12, note = "sheet row \"yt\""),
        rhys("Credit card", 250.00, "Credit & loans", 20, note = "sheet row \"credit\""),
        rhys("Phone", 19.77, "Mobile", 8),
        rhys("EE", 20.50, "Mobile", 8, note = "sheet row \"ee\""),
        rhys("Takeaways", 90.00, "Eating out", 1, variable = true, note = "sheet row \"outfood\""),
        rhys("Miscellaneous", 75.00, "Other", 1, variable = true, note = "sheet row \"missol\""),
        rhys("Life insurance", 32.39, "Life insurance", 3),

        // ---------------------------------------------------------- Hannah
        hannah("Car finance", 80.59, "Car finance", 5, note = "sheet row \"car\""),
        hannah("Road tax", 24.06, "Road tax", 1),
        hannah("Fuel", 70.00, "Fuel", 15, variable = true),
        hannah("Food shopping", 250.00, "Groceries", 1, variable = true),
        hannah("Days out", 40.00, "Days out", 1, variable = true, note = "sheet row \"outings\""),
        hannah("YouTube Premium", 12.99, "Subscriptions", 12, note = "sheet row \"yt\""),
        hannah("Credit card", 130.00, "Credit & loans", 20, note = "sheet row \"credit\""),
        hannah("EE", 19.91, "Mobile", 8, note = "sheet row \"ee\""),
        hannah("Takeaways", 30.00, "Eating out", 1, variable = true, note = "sheet row \"outfood\""),
        hannah("Miscellaneous", 165.00, "Other", 1, variable = true, note = "sheet row \"missol\""),
        hannah("Life insurance", 35.59, "Life insurance", 3),
    )

    /**
     * The "put way" rows — £504 from Rhys and £700 from Hannah each month.
     * These are transfers into savings rather than spending, so the app treats
     * them as money kept, not money gone: that is the main correction the app
     * makes to the spreadsheet's arithmetic.
     */
    val savingsTransferRules: List<SampleRule> = listOf(
        SampleRule(
            name = "Put away (Rhys)",
            amountMajor = 504.00,
            type = TransactionType.TRANSFER,
            personName = PERSON_RHYS,
            accountName = "Rhys bank",
            categoryName = "Savings",
            dayOfMonth = 28,
            notes = "sheet row \"put way\"",
        ),
        SampleRule(
            name = "Put away (Hannah)",
            amountMajor = 700.00,
            type = TransactionType.TRANSFER,
            personName = PERSON_HANNAH,
            accountName = "Hannah bank",
            categoryName = "Savings",
            dayOfMonth = 28,
            notes = "sheet row \"put way\"",
        ),
    )

    /** The destination account for both "put away" transfers. */
    const val SAVINGS_DESTINATION_ACCOUNT = "Saver (Mum)"

    val goals: List<SampleGoal> = listOf(
        SampleGoal(
            name = "Emergency fund",
            targetMajor = 6_000.00,
            monthlyContributionMajor = 504.00,
            accountName = "Saver (Mum)",
            colorHex = "#2E7D6B",
            iconKey = "Savings",
            notes = "Three months of household outgoings.",
        ),
        SampleGoal(
            name = "Holiday",
            targetMajor = 2_500.00,
            monthlyContributionMajor = 400.00,
            accountName = null,
            colorHex = "#00838F",
            iconKey = "BeachAccess",
        ),
        SampleGoal(
            name = "Christmas",
            targetMajor = 800.00,
            monthlyContributionMajor = 100.00,
            accountName = "£1 coins",
            colorHex = "#C62828",
            iconKey = "Redeem",
        ),
    )

    /** Every rule, in the order they should be created. */
    val allRules: List<SampleRule>
        get() = incomeRules + expenseRules + savingsTransferRules

    // ------------------------------------------------------------- helpers

    private fun rhys(
        name: String,
        amount: Double,
        category: String,
        day: Int,
        variable: Boolean = false,
        note: String? = null,
    ) = SampleRule(
        name = name,
        amountMajor = amount,
        type = TransactionType.EXPENSE,
        personName = PERSON_RHYS,
        accountName = "Rhys bank",
        categoryName = category,
        dayOfMonth = day,
        isVariableAmount = variable,
        notes = note,
    )

    private fun hannah(
        name: String,
        amount: Double,
        category: String,
        day: Int,
        variable: Boolean = false,
        note: String? = null,
    ) = SampleRule(
        name = name,
        amountMajor = amount,
        type = TransactionType.EXPENSE,
        personName = PERSON_HANNAH,
        accountName = "Hannah bank",
        categoryName = category,
        dayOfMonth = day,
        isVariableAmount = variable,
        notes = note,
    )
}
