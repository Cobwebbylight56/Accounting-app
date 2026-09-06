package com.rhys.financetracker.domain.model

/**
 * The vocabulary of the application.  These enums are persisted **by name**, so
 * existing constants must never be renamed — only added to.  Adding a constant
 * is a non-breaking change and is the normal way to extend the app.
 */

/** What kind of account holds the money. */
enum class AccountType(
    val displayName: String,
    /** Liabilities are held as negative balances and reduce net worth. */
    val isLiability: Boolean,
    /** Whether the account is counted as savings on the dashboard. */
    val isSavings: Boolean,
) {
    CURRENT("Current account", isLiability = false, isSavings = false),
    SAVINGS("Savings account", isLiability = false, isSavings = true),
    CASH("Cash", isLiability = false, isSavings = false),
    CREDIT_CARD("Credit card", isLiability = true, isSavings = false),
    LOAN("Loan", isLiability = true, isSavings = false),
    MORTGAGE("Mortgage", isLiability = true, isSavings = false),
    INVESTMENT("Investment", isLiability = false, isSavings = true),
    PENSION("Pension", isLiability = false, isSavings = true),
    OTHER("Other", isLiability = false, isSavings = false),
}

/** The direction money moves. */
enum class TransactionType(val displayName: String) {
    INCOME("Income"),
    EXPENSE("Expense"),

    /**
     * Moves money between two accounts owned by the household.  A transfer has
     * no effect on net worth and is excluded from income/expense totals.
     */
    TRANSFER("Transfer"),
}

/**
 * Where a stored transaction came from, and so how much it is to be trusted.
 *
 * A bank statement is the account itself talking: it has the day the money
 * actually moved, the amount to the penny, and the payee as the bank recorded
 * it — `VIRGIN MEDIA PAYMENTS 998812` rather than a remembered "Virgin media".
 * A spreadsheet row or a typed entry is somebody's account of the same event,
 * written from memory and often dated the day it was noticed rather than the
 * day it happened.
 *
 * So when both describe one transaction, the statement wins and the other is
 * brought up to it. Nothing is lost by that: what the earlier entry said is
 * kept in its notes. The reverse is never done — a spreadsheet import cannot
 * overwrite what the bank said.
 */
enum class RecordSource(val displayName: String) {
    /** Recorded before the app kept track of this. Treated as the weakest. */
    UNKNOWN("Unknown"),
    MANUAL("Typed in"),
    SPREADSHEET("From a spreadsheet"),
    STATEMENT("From a bank statement"),
    ;

    /** True when [other] should be allowed to overwrite a record from here. */
    fun yieldsTo(other: RecordSource): Boolean = other == STATEMENT && this != STATEMENT
}

/** Which side of the books a category belongs to. */
enum class CategoryKind(val displayName: String) {
    INCOME("Income"),
    EXPENSE("Expense"),

    /**
     * Money moved somewhere it is being kept, rather than spent.
     *
     * Its own kind because the two directions have to be told apart from
     * ordinary income and spending: £200 to a saver is not £200 gone, and
     * £200 back out of one is not £200 earned.
     */
    SAVING("Saving"),

    /**
     * Money that became notes and coins, or notes and coins paid back in.
     *
     * Taking £50 out of a machine spends nothing — the £50 is in a pocket
     * instead of an account. Counted as spending it inflates the month twice
     * over: once at the machine, and again when the cash is actually spent.
     */
    CASH("Cash"),
    TRANSFER("Transfer"),
    ;

    /**
     * True for the kinds that describe money being moved rather than earned
     * or spent, and which therefore belong on every direction's list.
     */
    val isAPot: Boolean get() = this == SAVING || this == CASH
}

/** How often a recurring income or bill repeats. */
enum class Frequency(
    val displayName: String,
    /** Roughly how many times this occurs per year; used to normalise to a monthly figure. */
    val approximateOccurrencesPerYear: Double,
) {
    ONE_OFF("One-off", 0.0),
    DAILY("Daily", 365.0),
    WEEKLY("Weekly", 52.0),
    FORTNIGHTLY("Fortnightly", 26.0),
    FOUR_WEEKLY("Every 4 weeks", 13.0),
    MONTHLY("Monthly", 12.0),
    QUARTERLY("Quarterly", 4.0),
    HALF_YEARLY("Every 6 months", 2.0),
    YEARLY("Yearly", 1.0),

    /** Repeats every N days, where N is the rule's `interval`. */
    CUSTOM_DAYS("Every N days", 0.0),

    /** Repeats every N months, where N is the rule's `interval`. */
    CUSTOM_MONTHS("Every N months", 0.0),
    ;

    val isCustom: Boolean get() = this == CUSTOM_DAYS || this == CUSTOM_MONTHS
}

/**
 * What the app should do when a recurring rule falls due.
 *
 * `AUTO_POST` is the behaviour that removes manual work: the transaction is
 * created automatically.  `CONFIRM` still generates the entry but flags it as
 * unconfirmed so the user can check the amount of a variable bill first.
 */
enum class RecurrenceMode(val displayName: String) {
    AUTO_POST("Post automatically"),
    CONFIRM("Ask me to confirm"),
    REMIND_ONLY("Remind me only"),
}

/** Which slice of the household the user is currently looking at. */
enum class ScopeType(val displayName: String) {
    HOUSEHOLD("Whole household"),
    PERSON("One person"),
    ACCOUNT("One account"),
}

/** Dashboard cards the user can show, hide and re-order. */
enum class DashboardWidget(val key: String, val title: String, val defaultVisible: Boolean) {
    // Declaration order is the default order on screen, so the plain
    // at-a-glance cards come before the charts.
    ACCOUNT_ACTIVITY("account_activity", "Accounts this month", true),
    CATEGORY_TILES("category_tiles", "Where it went", true),
    BALANCE_SUMMARY("balance_summary", "Balances", true),
    MONTH_SUMMARY("month_summary", "This month", true),
    DISPOSABLE_INCOME("disposable_income", "Left to spend", true),
    UPCOMING_BILLS("upcoming_bills", "Upcoming bills", true),
    OVERDUE_BILLS("overdue_bills", "Overdue", true),
    RECENT_TRANSACTIONS("recent_transactions", "This month's transactions", true),
    SAVINGS_AND_CASH("savings_and_cash", "Savings and cash", true),
    SAVINGS_PROGRESS("savings_progress", "Savings goals", true),
    SPENDING_BY_CATEGORY("spending_by_category", "Spending by category", true),
    INSIGHTS("insights", "Advice", true),
    INCOME_VS_EXPENSE("income_vs_expense", "Income vs expenses", true),
    NET_WORTH("net_worth", "Net worth", false),
    ACCOUNTS_LIST("accounts_list", "Accounts", false),
    EXTERNAL_DATA("external_data", "Rates & data", false),
    ;

    companion object {
        fun fromKey(key: String): DashboardWidget? = entries.firstOrNull { it.key == key }
    }
}

/** Formats a report can be exported to. */
enum class ExportFormat(val displayName: String, val extension: String, val mimeType: String) {
    PDF("PDF", "pdf", "application/pdf"),
    CSV("CSV", "csv", "text/csv"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    JSON("JSON backup", "json", "application/json"),
}

/** Page setup for printed reports. */
enum class PageOrientation(val displayName: String) {
    PORTRAIT("A4 portrait"),
    LANDSCAPE("A4 landscape"),
}

/** How the app should be locked when it is not in use. */
enum class LockMethod(val displayName: String) {
    NONE("No lock"),
    PIN("PIN"),
    BIOMETRIC("Fingerprint or face"),
    PIN_AND_BIOMETRIC("Fingerprint with PIN fallback"),
}

/** Light/dark preference. */
enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** The kinds of external figure the app can keep up to date. */
enum class ExternalDataKey(
    val key: String,
    val displayName: String,
    val unit: String,
    /** False when no free, key-free API exists — the value must be entered by hand. */
    val hasAutomaticSource: Boolean,
) {
    EXCHANGE_RATE_EUR("fx_gbp_eur", "GBP to EUR", "EUR", true),
    EXCHANGE_RATE_USD("fx_gbp_usd", "GBP to USD", "USD", true),
    NEXT_BANK_HOLIDAY("bank_holiday_next", "Next bank holiday", "date", true),
    BANK_OF_ENGLAND_BASE_RATE("boe_base_rate", "Bank of England base rate", "%", false),
    CPI_INFLATION("cpi_inflation", "CPI inflation", "%", false),
    FUEL_PRICE_PETROL("fuel_petrol", "Petrol price", "p/litre", false),
    FUEL_PRICE_DIESEL("fuel_diesel", "Diesel price", "p/litre", false),
    ENERGY_PRICE_CAP("energy_cap", "Energy price cap", "£/year", false),
    ;

    companion object {
        fun fromKey(key: String): ExternalDataKey? = entries.firstOrNull { it.key == key }
    }
}
