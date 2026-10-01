package com.rhys.financetracker.domain.model

/**
 * The vocabulary of the application.  These enums are persisted **by name**, so
 * existing constants must never be renamed.  Adding a constant is a
 * non-breaking change and is the normal way to extend the app; removing one
 * needs a migration that rewrites every stored row that used it, as
 * `AccountType.CASH` had in version 5 of the database.
 */

/**
 * Where an account's money counts: the one thing that decides whether it is
 * Available, Saved or Owed on every screen.
 *
 * ## Why this exists
 *
 * "Is this savings?" used to be answered in four places — the account type,
 * a nullable per-account override that silently beat the type, a total of
 * everything ever moved into savings that stood in for a balance, and a cash
 * account type that was savings by definition. Each was reasonable alone and
 * together they disagreed: a saver typed as a current account sat in
 * Available while Saved said there was no savings account at all, and the
 * suggestion to fix it was hidden whenever the override had ever been set.
 *
 * Now there is one stored answer per account. The type only suggests it when
 * the account is made; after that the owner's choice is the answer and
 * nothing else is consulted.
 */
enum class Holding(val displayName: String, val explanation: String) {
    /** Day-to-day money: counts towards Available. */
    SPEND("To spend", "Day-to-day money. Counts towards Available."),

    /** Money you are not going to spend from: counts towards Saved. */
    SET_ASIDE("Set aside", "Savings you won't touch. Counts towards Saved."),

    /** Borrowed money — a card, loan or mortgage: counts towards what you owe. */
    OWED("Owed", "A card, loan or mortgage. Counts towards what you owe."),
}

/**
 * What kind of account this is, as the bank would describe it.
 *
 * Descriptive only: where the money counts is [Holding], and the type merely
 * suggests one when an account is made. Cash is deliberately absent — notes
 * in the house are not an account, they are the cash pot.
 */
enum class AccountType(
    val displayName: String,
    /** Where an account of this type counts unless its owner says otherwise. */
    val defaultHolding: Holding,
) {
    CURRENT("Current account", Holding.SPEND),
    SAVINGS("Savings account", Holding.SET_ASIDE),
    CREDIT_CARD("Credit card", Holding.OWED),

    /** Buy now, pay later: PayPal Pay in 3, Klarna, Clearpay, Laybuy. */
    PAY_LATER("Pay later (Pay in 3, Klarna…)", Holding.OWED),
    LOAN("Loan", Holding.OWED),
    MORTGAGE("Mortgage", Holding.OWED),
    INVESTMENT("Investment", Holding.SET_ASIDE),
    PENSION("Pension", Holding.SET_ASIDE),
    OTHER("Other", Holding.SPEND),
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
     * Money that became notes and coins.
     *
     * Its own kind so the month can say how much of its spending left as
     * cash, which is the part no statement can break down any further. It is
     * still spending: £50 out of a machine is £50 gone from the account, and
     * that is how it counts everywhere a total is taken.
     *
     * What is then physically held is a separate question, answered by the
     * cash pot rather than by this.
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
    ACCOUNT_ACTIVITY("account_activity", "Accounts this month", false),
    CATEGORY_TILES("category_tiles", "Where it went", true),
    BALANCE_SUMMARY("balance_summary", "Balances", true),
    MONTH_SUMMARY("month_summary", "This month", true),
    DISPOSABLE_INCOME("disposable_income", "Left to spend", false),
    UPCOMING_BILLS("upcoming_bills", "Coming up", true),
    OVERDUE_BILLS("overdue_bills", "Overdue", true),
    RECENT_TRANSACTIONS("recent_transactions", "This month's payments", true),
    SAVINGS_AND_CASH("savings_and_cash", "Savings", false),
    CASH_IN_HAND("cash_in_hand", "Cash pot", true),
    SAVINGS_PROGRESS("savings_progress", "Savings goals", true),
    SPENDING_BY_CATEGORY("spending_by_category", "Spending by category", false),
    INSIGHTS("insights", "Advice", true),
    INCOME_VS_EXPENSE("income_vs_expense", "Income against spending", false),
    NET_WORTH("net_worth", "Net worth", false),
    ACCOUNTS_LIST("accounts_list", "Accounts", false),
    EXTERNAL_DATA("external_data", "Rates and figures", false),
    ;

    /**
     * Off unless switched on, because Home already shows the same thing: the
     * month's figures repeat "Left to spend", the Saved tile repeats
     * Savings, and "Where it went" repeats the spending chart.
     */
    val repeatsHome: Boolean
        get() = this in setOf(ACCOUNT_ACTIVITY, DISPOSABLE_INCOME, SAVINGS_AND_CASH, SPENDING_BY_CATEGORY, INCOME_VS_EXPENSE)

    /**
     * False for the cards Home's tiles and month list replaced: they are no
     * longer drawn, so a switch for them would do nothing.
     */
    val isSwitchable: Boolean get() = this != BALANCE_SUMMARY && this != MONTH_SUMMARY

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
