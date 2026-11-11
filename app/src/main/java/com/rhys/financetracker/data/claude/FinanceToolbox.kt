package com.rhys.financetracker.data.claude

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.claude.ClaudeText
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/** A tool Claude can call: its name, what it's for, and its inputs as JSON Schema. */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>> = emptyMap(),
    val required: List<String> = emptyList(),
)

/** What a tool gave back: text for Claude, and whether it was an error. */
data class ToolOutput(val text: String, val isError: Boolean = false)

/**
 * What Claude can see of the household's money, and nothing more: accounts
 * and balances, payments, categories and regular bills. It can only look —
 * there is no tool that changes anything — and account numbers in payment
 * wording are masked before they leave the phone.
 */
@Singleton
class FinanceToolbox @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val peopleRepository: PeopleRepository,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
) : ClaudeTools {

    override suspend fun run(name: String, input: Map<*, *>): ToolOutput = run(name, input, DateUtils.today())

    /** Runs the tool called [name] with [input]; a bad input is an error Claude can correct. */
    suspend fun run(name: String, input: Map<*, *>, today: LocalDate): ToolOutput = try {
        when (name) {
            ACCOUNTS -> ToolOutput(accounts(today))
            MONTHS -> ToolOutput(months(input, today))
            CATEGORIES -> ToolOutput(categories(input, today))
            SEARCH -> ToolOutput(search(input, today))
            BILLS -> ToolOutput(bills())
            else -> ToolOutput("There's no tool called $name.", isError = true)
        }
    } catch (e: BadInput) {
        ToolOutput(e.message ?: "That input wasn't understood.", isError = true)
    }

    /** One line about who's who, for the instructions Claude starts with. */
    override suspend fun household(): String {
        val people = peopleRepository.activePeople()
        val named = people.filterNot { it.isShared }.map { it.name.substringBefore(' ') }
        val shared = people.filter { it.isShared }.map { it.name }
        return buildString {
            append(if (named.isEmpty()) "One person" else named.joinToString(" and "))
            if (shared.isNotEmpty()) append(", and a joint pot called ${shared.joinToString()} for money they share")
        }
    }

    private suspend fun accounts(today: LocalDate): String {
        val people = peopleRepository.activePeople().associateBy { it.id }
        val accounts = accountRepository.getAll().filter { !it.isArchived }
        if (accounts.isEmpty()) return "There are no accounts yet."
        var spend = 0L
        var saved = 0L
        var owed = 0L
        val lines = accounts.sortedBy { it.holding.ordinal }.map { account ->
            val balance = accountRepository.balanceAsOf(account.id, today)
            when (account.holding) {
                Holding.SPEND -> spend += balance
                Holding.SET_ASIDE -> saved += balance
                Holding.OWED -> owed -= balance
            }
            val whose = if (account.isShared) "joint" else account.personId?.let { people[it] }?.name?.substringBefore(' ') ?: "nobody set"
            val kind = when (account.holding) {
                Holding.SPEND -> "to spend"
                Holding.SET_ASIDE -> "savings"
                Holding.OWED -> "borrowing (owed ${pounds(-balance)})"
            }
            "- ${account.name} · $whose · ${account.type.displayName} · $kind · balance ${signed(balance)}"
        }
        return "Accounts, balances on ${day(today)}:\n" + lines.joinToString("\n") +
            "\nTotals: to spend ${signed(spend)} · saved ${signed(saved)} · owed ${pounds(owed)}"
    }

    private suspend fun months(input: Map<*, *>, today: LocalDate): String {
        val count = (input.int("months") ?: DEFAULT_MONTHS).coerceIn(1, MAX_MONTHS)
        val (who, personIds) = person(input)
        val thisMonth = YearMonth.from(today)
        val lines = (count - 1 downTo 0).map { back ->
            val month = thisMonth.minusMonths(back.toLong())
            val start = month.atDay(1)
            val end = if (month == thisMonth) today else month.atEndOfMonth()
            val totals = transactionRepository.observeIncomeExpense(start, end, null, personIds).first()
            val saved = transactionRepository.observePotFlow(CategoryKind.SAVING, start, end, null, personIds).first().netMinor
            val name = month.format(MONTH) + if (month == thisMonth) " (so far, to ${day(today)})" else ""
            "- $name: money in ${pounds(totals.incomeMinor)} · spent ${pounds(totals.expenseMinor)} · " +
                "left over ${signed(totals.incomeMinor - totals.expenseMinor)} · put into savings ${signed(saved)}"
        }
        return "Month by month for $who (spending leaves out moves between their own accounts, savings and paying off cards):\n" +
            lines.joinToString("\n")
    }

    private suspend fun categories(input: Map<*, *>, today: LocalDate): String {
        val (from, to) = dates(input, today, defaultFrom = YearMonth.from(today).atDay(1))
        val (who, personIds) = person(input)
        val income = input.string("type")?.lowercase() == "income"
        val totals = transactionRepository.observeCategoryTotals(
            if (income) TransactionType.INCOME else TransactionType.EXPENSE, from, to, null, personIds,
        ).first().filter { it.totalMinor != 0L }.sortedByDescending { it.totalMinor }
        val what = if (income) "Money in" else "Spending"
        if (totals.isEmpty()) return "$what for $who from ${day(from)} to ${day(to)}: nothing recorded."
        val sum = totals.sumOf { it.totalMinor }
        return "$what by category for $who, ${day(from)} to ${day(to)} — total ${pounds(sum)}:\n" +
            totals.joinToString("\n") { total ->
                val share = if (sum == 0L) 0 else Math.round(total.totalMinor * 100.0 / sum)
                "- ${total.categoryName ?: "No category"}: ${pounds(total.totalMinor)} ($share%, ${total.transactionCount} payments)"
            }
    }

    private suspend fun search(input: Map<*, *>, today: LocalDate): String {
        val (from, to) = dates(input, today, defaultFrom = today.minusMonths(DEFAULT_SEARCH_MONTHS))
        val (who, personIds) = person(input)
        val categoryName = input.string("category")
        val categoryIds = categoryName?.let { wanted ->
            val all = categoryDao.getAll()
            all.filter { it.name.equals(wanted, ignoreCase = true) }.ifEmpty { all.filter { it.name.contains(wanted, ignoreCase = true) } }
                .map { it.id }.toSet()
                .ifEmpty { throw BadInput("No category called \"$wanted\". Categories: ${all.map { it.name }.distinct().sorted().joinToString()}") }
        }.orEmpty()
        val accountName = input.string("account")
        val accountIds = accountName?.let { wanted ->
            val all = accountRepository.getAll().filter { !it.isArchived }
            all.filter { it.name.contains(wanted, ignoreCase = true) }.map { it.id }.toSet()
                .ifEmpty { throw BadInput("No account called \"$wanted\". Accounts: ${all.joinToString { it.name }}") }
        }.orEmpty()
        val types = when (input.string("kind")?.lowercase()) {
            null, "", "any" -> emptySet()
            "spending", "expense", "out" -> setOf(TransactionType.EXPENSE)
            "income", "in" -> setOf(TransactionType.INCOME)
            "transfer", "move" -> setOf(TransactionType.TRANSFER)
            else -> throw BadInput("kind is spending, income, transfer or any.")
        }
        val sort = when (input.string("sort")?.lowercase()) {
            null, "", "newest" -> TransactionSort.DATE_DESC
            "oldest" -> TransactionSort.DATE_ASC
            "biggest", "largest" -> TransactionSort.AMOUNT_DESC
            "smallest" -> TransactionSort.AMOUNT_ASC
            else -> throw BadInput("sort is newest, oldest, biggest or smallest.")
        }
        val limit = (input.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val rows = transactionRepository.searchOnce(
            TransactionFilter(
                text = input.string("text")?.takeIf { it.isNotBlank() },
                dateFrom = from,
                dateTo = to,
                accountIds = accountIds,
                categoryIds = categoryIds,
                personIds = personIds.orEmpty(),
                types = types,
                minAmountMinor = input.pence("min_amount"),
                maxAmountMinor = input.pence("max_amount"),
                sort = sort,
                limit = MAX_MATCHES,
            ),
        )
        if (rows.isEmpty()) return "No payments match, for $who from ${day(from)} to ${day(to)}."
        val out = rows.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amountMinor }
        val inn = rows.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amountMinor }
        val more = if (rows.size >= MAX_MATCHES) " or more" else ""
        val header = "${rows.size}$more payments match, for $who from ${day(from)} to ${day(to)}" +
            " — money out ${pounds(out)}, money in ${pounds(inn)}." +
            (if (rows.size > limit) " Showing ${limit}, ${sort.displayName.lowercase()}:" else "")
        return header + "\n" + rows.take(limit).joinToString("\n") { row ->
            val t = row.transaction
            val sign = when (t.type) {
                TransactionType.INCOME -> "+"
                TransactionType.EXPENSE -> "−"
                TransactionType.TRANSFER -> "move "
            }
            val where = if (t.type == TransactionType.TRANSFER) "${row.accountName.orEmpty()} → ${row.transferAccountName.orEmpty()}" else row.accountName
            listOfNotNull(
                "- ${t.date}",
                ClaudeText.maskNumbers(t.description.trim()),
                sign + pounds(t.amountMinor),
                row.categoryName,
                where,
                row.personName?.substringBefore(' '),
                if (t.isOneOff) "one-off" else null,
            ).joinToString(" · ")
        }
    }

    private suspend fun bills(): String {
        val accounts = accountRepository.getAll().associateBy { it.id }
        val rules = recurringRuleDao.getAllActive().sortedByDescending { it.amountMinor }
        if (rules.isEmpty()) return "No regular payments are set up."
        val monthlyOut = rules.filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amountMinor * it.frequency.approximateOccurrencesPerYear / MONTHS_A_YEAR }
        val monthlyIn = rules.filter { it.type == TransactionType.INCOME }
            .sumOf { it.amountMinor * it.frequency.approximateOccurrencesPerYear / MONTHS_A_YEAR }
        return "Regular payments set up (about ${pounds(Math.round(monthlyOut))} a month out, " +
            "${pounds(Math.round(monthlyIn))} a month in):\n" +
            rules.joinToString("\n") { rule ->
                val way = when (rule.type) {
                    TransactionType.INCOME -> "in"
                    TransactionType.EXPENSE -> "out"
                    TransactionType.TRANSFER -> "move"
                }
                listOfNotNull(
                    "- ${ClaudeText.maskNumbers(rule.name)}",
                    "${pounds(rule.amountMinor)} $way",
                    rule.frequency.displayName.lowercase(),
                    "next ${day(rule.nextDueDate)}",
                    rule.paymentKind?.displayName,
                    accounts[rule.accountId]?.name,
                ).joinToString(" · ")
            }
    }

    /** Whose money [input] asks about: everyone, or one person, or the joint pot. */
    private suspend fun person(input: Map<*, *>): Pair<String, Set<Long>?> {
        val wanted = input.string("person")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("everyone", true) }
            ?: return "the whole household" to null
        val people = peopleRepository.activePeople()
        val match: List<PersonEntity> = when {
            wanted.equals("joint", true) || wanted.equals("shared", true) -> people.filter { it.isShared }
            else -> people.filter { it.name.equals(wanted, true) || it.name.substringBefore(' ').equals(wanted, true) }
        }
        if (match.isEmpty()) throw BadInput("Nobody called \"$wanted\". People: ${people.joinToString { it.name }}, or everyone.")
        return match.joinToString(" and ") { it.name } to match.map { it.id }.toSet()
    }

    private fun dates(input: Map<*, *>, today: LocalDate, defaultFrom: LocalDate): Pair<LocalDate, LocalDate> {
        val from = input.date("from") ?: defaultFrom
        val to = input.date("to") ?: today
        if (to.isBefore(from)) throw BadInput("to (${to}) is before from (${from}).")
        return from to to
    }

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()

    private fun Map<*, *>.int(key: String): Int? = when (val v = this[key]) {
        null -> null
        is Number -> v.toInt()
        else -> v.toString().toIntOrNull() ?: throw BadInput("$key should be a whole number.")
    }

    /** An amount in pounds, as pence. */
    private fun Map<*, *>.pence(key: String): Long? = when (val v = this[key]) {
        null -> null
        is Number -> Math.round(v.toDouble() * 100)
        else -> v.toString().replace("£", "").replace(",", "").toDoubleOrNull()?.let { Math.round(it * 100) }
            ?: throw BadInput("$key should be an amount in pounds, like 12.50.")
    }

    private fun Map<*, *>.date(key: String): LocalDate? = this[key]?.toString()?.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it.take(10)) }.getOrElse { throw BadInput("$key should be a date like 2026-10-01.") }
    }

    private class BadInput(message: String) : Exception(message)

    companion object {
        const val ACCOUNTS = "get_accounts"
        const val MONTHS = "get_monthly_totals"
        const val CATEGORIES = "get_spending_by_category"
        const val SEARCH = "search_payments"
        const val BILLS = "get_regular_payments"

        private const val DEFAULT_MONTHS = 6
        private const val MAX_MONTHS = 36
        private const val DEFAULT_SEARCH_MONTHS = 3L
        private const val DEFAULT_LIMIT = 40
        private const val MAX_LIMIT = 200

        /** Payments added up for a search; more than this and the totals say "or more". */
        private const val MAX_MATCHES = 5_000
        private const val MONTHS_A_YEAR = 12.0

        private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)
        private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

        private fun day(date: LocalDate): String = date.format(DAY)

        fun pounds(minor: Long): String = "£" + String.format(Locale.UK, "%,.2f", abs(minor) / 100.0)

        private fun signed(minor: Long): String = if (minor < 0) "−" + pounds(minor) else pounds(minor)

        private val PERSON = mapOf(
            "type" to "string",
            "description" to "Whose money: a person's first name, \"joint\" for the money they share, or leave out for everyone.",
        )
        private val FROM = mapOf("type" to "string", "description" to "First day, as YYYY-MM-DD.")
        private val TO = mapOf("type" to "string", "description" to "Last day, as YYYY-MM-DD. Leave out for today.")

        /** Every tool, in the order Claude is given them. Never changes within a conversation. */
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                ACCOUNTS,
                "Every account the household has — current accounts, savings, credit cards and loans — with whose it " +
                    "is and its balance today, and the totals to spend, saved and owed.",
            ),
            ToolSpec(
                MONTHS,
                "Money in, spending, what was left over and what went into savings, for each of the last few months " +
                    "(this month so far last). Use it for how things are going and how they've changed.",
                mapOf(
                    "months" to mapOf("type" to "integer", "description" to "How many months, 1 to 36. Default 6."),
                    "person" to PERSON,
                ),
            ),
            ToolSpec(
                CATEGORIES,
                "Spending (or money in) added up by category between two dates, biggest first, with each category's " +
                    "share and number of payments. Default: this month so far.",
                mapOf(
                    "from" to FROM,
                    "to" to TO,
                    "type" to mapOf("type" to "string", "enum" to listOf("spending", "income"), "description" to "Default spending."),
                    "person" to PERSON,
                ),
            ),
            ToolSpec(
                SEARCH,
                "Finds payments and adds them up: by words in the payee, dates, category, account, person, kind and " +
                    "amount. Returns how many match with their totals, and lists them. Default: the last 3 months, " +
                    "newest first, 40 listed.",
                mapOf(
                    "text" to mapOf("type" to "string", "description" to "Words in the payee or notes, like \"tesco\" or \"netflix\"."),
                    "from" to FROM,
                    "to" to TO,
                    "category" to mapOf("type" to "string", "description" to "A category name, like \"Groceries\"."),
                    "account" to mapOf("type" to "string", "description" to "Part of an account's name, like \"Lloyds\"."),
                    "person" to PERSON,
                    "kind" to mapOf("type" to "string", "enum" to listOf("spending", "income", "transfer", "any")),
                    "min_amount" to mapOf("type" to "number", "description" to "Smallest amount in pounds."),
                    "max_amount" to mapOf("type" to "number", "description" to "Largest amount in pounds."),
                    "sort" to mapOf("type" to "string", "enum" to listOf("newest", "oldest", "biggest", "smallest")),
                    "limit" to mapOf("type" to "integer", "description" to "How many to list, 1 to 200. Default 40."),
                ),
            ),
            ToolSpec(
                BILLS,
                "The regular payments set up in the app — bills, subscriptions, standing orders, salary — with " +
                    "amount, how often, the next date and the account, and the monthly total.",
            ),
        )
    }
}
