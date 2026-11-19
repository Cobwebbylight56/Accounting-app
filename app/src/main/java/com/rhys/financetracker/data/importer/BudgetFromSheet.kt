package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.domain.budget.LineGoal
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import kotlin.math.abs

/**
 * The household spreadsheet the app started from, read as a budget.
 *
 * Its outgoings block is what the household planned to spend each month:
 *
 * ```
 *  OUTGOINGS     Rhys    Hannah   both
 *  car          60.75     80.59   141.34
 *  road tax     37.62     24.06    61.68
 *  fuel           120        80      200
 * ```
 *
 * Each row becomes a budget line for the household — the people's figures
 * added together, never the "both" column, which already is that — and is
 * matched to the category its spending lands in, or to the loan or saver
 * it's paid into.
 */
object BudgetFromSheet {

    /** One outgoing on the sheet. */
    data class Outgoing(
        val name: String,
        /** A month's worth, everyone's added together, in pence. */
        val amountMinor: Long,
        /** Each person's share, by the name heading their column. */
        val byPerson: Map<String, Long>,
    )

    /** Every outgoing in [layout]'s outgoings blocks; the same name twice is added together. */
    fun rows(sheet: SheetData, layout: DetectedLayout): List<Outgoing> =
        layout.blocks
            .filter { it.target == ImportTarget.RECURRING_EXPENSE }
            .flatMap { block ->
                (block.firstRow..block.lastRow).mapNotNull { row ->
                    val label = sheet.cell(row, block.nameColumn).trim()
                    if (label.isEmpty() || HouseholdLayoutDetector.isTotalRow(label)) return@mapNotNull null
                    val byPerson = block.people.associate { person ->
                        person.name to (Money.parseOrNull(sheet.cell(row, person.amountColumn))?.let(::abs) ?: 0L)
                    }
                    val total = byPerson.values.sum()
                    if (total <= 0L) null else Outgoing(tidy(label), total, byPerson)
                }
            }
            .groupBy { it.name.lowercase() }
            .map { (_, same) ->
                Outgoing(
                    name = same.first().name,
                    amountMinor = same.sumOf { it.amountMinor },
                    byPerson = same.flatMap { it.byPerson.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
                )
            }

    /** "road tax" → "Road tax". */
    fun tidy(label: String): String =
        label.trim().replace(Regex("""\s+"""), " ").lowercase().replaceFirstChar { it.uppercase() }

    /** A category, as matching needs it. */
    data class Category(val id: Long, val name: String, val kind: CategoryKind, val parentId: Long? = null)

    /** An account, as matching needs it. */
    data class Account(val id: Long, val name: String, val holding: Holding)

    /** What a line tracks, and how. */
    data class Match(
        val categoryId: Long? = null,
        val accountId: Long? = null,
        val aim: Boolean = false,
        val goal: LineGoal = LineGoal.SPEND_AT_MOST,
    )

    /**
     * Where [label]'s money goes: a loan or saver whose name it shares a
     * word with ("car loan" → "Car finance"), else a category of the same
     * name, a word that means one ("petrol" → Fuel, "food shop" →
     * Groceries), or the category the app files a payee of that name under.
     * Nothing, when nothing fits: the line is offered with "choose what
     * counts".
     */
    fun match(label: String, categories: List<Category>, accounts: List<Account>): Match {
        val words = wordsOf(label)
        if (words.isEmpty()) return Match()
        val lower = label.trim().lowercase()

        // Paid into a loan or put into a saver: "car loan", "LISA", "savings pot".
        val distinctive = words - GENERIC
        val intoAccount = accounts.filter { it.holding != Holding.SPEND }
            .filter { account -> (wordsOf(account.name) - GENERIC).any { it in distinctive } }
        if (intoAccount.size == 1 && SPENDING_WORDS.none { it in words }) {
            return Match(accountId = intoAccount.single().id, goal = LineGoal.PUT_ASIDE_AT_LEAST)
        }

        val spending = categories.filter { it.kind == CategoryKind.EXPENSE }
        val savings = categories.filter { it.kind == CategoryKind.SAVING }
        fun named(name: String) = spending.firstOrNull { it.name.equals(name, ignoreCase = true) }
        fun result(category: Category, goal: LineGoal = LineGoal.SPEND_AT_MOST) =
            Match(categoryId = category.id, aim = isAim(lower, category.name), goal = goal)

        // A line for savings with no saver to point at: the Savings category.
        if (words.any { it in SAVING_WORDS }) savings.firstOrNull()?.let { return result(it, LineGoal.PUT_ASIDE_AT_LEAST) }

        named(lower)?.let { return result(it) }
        named(lower.removeSuffix("s"))?.let { return result(it) }
        named(lower + "s")?.let { return result(it) }
        named(lower.replace(" and ", " & "))?.let { return result(it) }

        // Several words are looked for as a phrase, one as a whole word: "aa" isn't in "bazaar".
        SYNONYMS.entries.firstOrNull { (key, _) -> if (' ' in key) lower.contains(key) else key in words }
            ?.let { (_, name) -> named(name) }
            ?.let { return result(it) }

        MerchantCategoriser.categoryFor(label)?.let(::named)?.let { return result(it) }

        // A category sharing a distinctive word: "home insurance" → Home insurance.
        val sharing = spending.filter { category -> (wordsOf(category.name) - GENERIC).any { it in distinctive } }
        if (sharing.size == 1) return result(sharing.single())

        return Match(aim = isAim(lower, null))
    }

    /** Spending that changes month to month, where going over isn't a big problem. */
    fun isAim(label: String, categoryName: String?): Boolean {
        val words = wordsOf(label) + wordsOf(categoryName.orEmpty())
        return words.any { it in AIM_WORDS }
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    private val STOP_WORDS = setOf("the", "and", "my", "our", "for", "of", "to", "per", "month", "monthly", "bill", "bills", "payment", "payments")

    /** Words in a name that say nothing about which one it is. */
    private val GENERIC = setOf(
        "account", "accounts", "bank", "card", "credit", "current", "loan", "loans", "finance", "saver", "savings",
        "saving", "isa", "pot", "joint", "personal", "plan", "insurance", "cover", "money",
    )

    /** Words that make a line spending even when an account shares a word: "car fuel" isn't the car loan. */
    private val SPENDING_WORDS = setOf(
        "fuel", "petrol", "diesel", "insurance", "tax", "mot", "service", "servicing", "repairs", "parking", "tyres",
    )

    private val SAVING_WORDS = setOf("savings", "saving", "save", "saver")

    /** Words meaning one of the app's categories. */
    private val SYNONYMS = linkedMapOf(
        "petrol" to "Fuel", "diesel" to "Fuel", "fuel" to "Fuel",
        "food shop" to "Groceries", "food" to "Groceries", "shopping" to "Groceries", "grocery" to "Groceries",
        "groceries" to "Groceries", "supermarket" to "Groceries",
        "takeaway" to "Takeaways", "eating out" to "Eating out", "meals out" to "Eating out",
        "phone" to "Mobile", "phones" to "Mobile", "mobile" to "Mobile", "sim" to "Mobile",
        "internet" to "Broadband", "wifi" to "Broadband", "broadband" to "Broadband", "sky" to "Broadband",
        "virgin" to "Broadband",
        "electricity" to "Electric", "elec" to "Electric", "electric" to "Electric", "gas" to "Gas",
        "energy" to "Energy",
        "water" to "Water", "council" to "Council tax", "tv licence" to "TV licence", "tv license" to "TV licence",
        "licence" to "TV licence",
        "road tax" to "Road tax", "car tax" to "Road tax", "vehicle tax" to "Road tax",
        "car insurance" to "Car insurance", "house insurance" to "Home insurance", "home insurance" to "Home insurance",
        "life insurance" to "Life insurance", "pet insurance" to "Pet insurance",
        "breakdown" to "Breakdown cover", "rac" to "Breakdown cover", "aa" to "Breakdown cover",
        "mot" to "MOT & servicing", "service" to "MOT & servicing",
        "gym" to "Fitness", "netflix" to "Subscriptions", "spotify" to "Subscriptions", "subscriptions" to "Subscriptions",
        "disney" to "Subscriptions", "prime" to "Subscriptions",
        "rent" to "Rent", "mortgage" to "Mortgage", "childcare" to "Childcare", "nursery" to "Childcare",
        "clothes" to "Clothes", "clothing" to "Clothes", "presents" to "Gifts", "gifts" to "Gifts",
        "hair" to "Hair & beauty", "haircut" to "Hair & beauty", "dog" to "Pets", "cat" to "Pets", "pets" to "Pets",
        "vet" to "Vets", "holiday" to "Holidays", "holidays" to "Holidays", "days out" to "Days out",
        "car" to "Car finance",
    )

    /** What makes a line an aim rather than a limit. */
    private val AIM_WORDS = setOf(
        "fuel", "petrol", "diesel", "food", "groceries", "grocery", "shopping", "supermarket", "eating", "takeaway",
        "takeaways", "drinks", "coffee", "snacks", "entertainment", "days", "fun", "clothes", "clothing", "gifts",
        "presents", "hobbies", "household", "spending", "pocket", "taxis", "parking", "holidays", "beauty", "hair",
        "pharmacy",
    )
}
