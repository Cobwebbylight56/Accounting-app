package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.TransactionType

/**
 * Turns a bank's description into one of the app's categories.
 *
 * A statement says `TESCO STORES 3294`, not "Groceries". Without this every
 * imported row would arrive uncategorised, and a thousand rows to file by hand
 * is a spending history nobody ever builds.
 *
 * Two sources, in order of authority:
 *
 *  1. **What you have already decided.** If `SAINSBURYS PETROL` was filed under
 *     Fuel once, it belongs in Fuel again — even though the built-in rules
 *     would call it Groceries. Your correction outranks the guess, and one
 *     correction fixes every future import of that merchant.
 *  2. **Built-in rules** for the shops and services a UK household meets.
 *
 * When neither matches, the row is left uncategorised rather than guessed at.
 * An empty category is obvious and quick to fix; a wrong one is neither.
 */
object MerchantCategoriser {

    /**
     * The category for [description], or null when nothing matches.
     *
     * @param learned merchant to category, from transactions already filed.
     *   Keys must be normalised with [TransactionFingerprint.normaliseDescription].
     */
    fun categoryFor(
        description: String,
        type: TransactionType = TransactionType.EXPENSE,
        learned: Map<String, String> = emptyMap(),
    ): String? {
        val text = TransactionFingerprint.normaliseDescription(description)
        if (text.isBlank()) return null

        learned[text]?.let { return it }

        // A payee remembered under a longer reference still counts: banks add
        // and drop trailing numbers between exports.
        learned.entries.firstOrNull { (merchant, _) ->
            merchant.length >= MIN_LEARNED_PREFIX &&
                (text.startsWith(merchant) || merchant.startsWith(text))
        }?.let { return it.value }

        val rules = if (type == TransactionType.INCOME) INCOME_RULES else EXPENSE_RULES
        return rules.firstOrNull { rule -> rule.keywords.any { matches(text, it) } }?.category
    }

    /**
     * Whether [keyword] appears in [text] at the start of a word.
     *
     * A plain substring test is not good enough: "tfl" sits inside "neTFLix",
     * which filed every Netflix payment under public transport. Anchoring to a
     * word start fixes that while still letting "sainsbury" match
     * "sainsburys", which is the whole reason for matching on fragments.
     */
    private fun matches(text: String, keyword: String): Boolean =
        " $text ".contains(" $keyword")

    /** One category and the words that mean it. */
    private data class Rule(val category: String, val keywords: List<String>)

    private fun rule(category: String, vararg keywords: String) =
        Rule(category, keywords.toList())

    /**
     * Ordered, and the order carries meaning: the first match wins, so
     * anything that would otherwise be swallowed by a broader rule is listed
     * above it. "Tesco Mobile" is a phone bill, "Uber Eats" is a takeaway, and
     * "Shell Energy" is a gas bill — each sits above Tesco, Uber and Shell.
     */
    private val EXPENSE_RULES: List<Rule> = listOf(
        // -- savings and cash, which are not spending -----------------------
        //
        // First, because a saver is usually named after the bank it is with
        // and the general rules below would file "TRANSFER TO NATIONWIDE" as
        // shopping. Without this the payment that starts the saving is the one
        // thing the app never counts as saving, and a household putting money
        // aside every month is shown as having saved nothing at all.
        //
        // Neither of these is spending: one is money moved to where it is
        // being kept, the other is the same money in a pocket instead of an
        // account. The words for both live in [PotWords], with the reasoning
        // behind them, and are matched in both directions — the transaction's
        // own direction says whether it went into the pot or came back out.
        Rule("Savings", PotWords.SAVINGS),
        Rule("Cash", PotWords.CASH),

        // -- specific cases that must beat the general ones below ----------
        rule("Mobile", "tesco mobile", "sky mobile", "asda mobile"),
        rule("Eating out", "uber eats", "ubereats"),
        rule("Energy", "shell energy", "sainsbury energy"),
        rule("Fuel", "sainsburys petrol", "tesco petrol", "morrisons petrol", "asda petrol"),

        // -- groceries ------------------------------------------------------
        rule(
            "Groceries",
            "tesco", "sainsbury", "asda", "aldi", "lidl", "morrisons", "waitrose",
            "co op", "coop", "iceland", "ocado", "farmfoods", "spar", "budgens",
            "marks and spencer", "m and s", "booths", "costcutter", "nisa",
        ),

        // -- eating out and takeaways --------------------------------------
        rule(
            "Eating out",
            "costa", "starbucks", "caffe nero", "greggs", "pret", "nando", "mcdonald",
            "kfc", "burger king", "subway", "pizza", "domino", "wagamama", "prezzo",
            "harvester", "toby carvery", "wetherspoon", "deliveroo", "just eat",
            "restaurant", "bistro", "cafe", "coffee", "takeaway", "chippy", "bakery",
        ),

        // -- motoring -------------------------------------------------------
        rule(
            "Fuel",
            "shell", "bp ", "esso", "texaco", "gulf", "murco", "applegreen", "jet ",
            "petrol", "fuel", "filling station", "service station",
        ),
        rule("Car insurance", "admiral", "hastings direct", "churchill", "direct line", "esure"),
        rule("MOT & servicing", "kwik fit", "halfords", "national tyres", "mot ", "garage"),
        rule("Road tax", "dvla", "road tax", "vehicle tax"),
        rule("Car finance", "car finance", "motability", "vehicle finance"),
        rule(
            "Public transport",
            "trainline", "national rail", "tfl", "oyster", "stagecoach", "arriva",
            "first bus", "lner", "avanti", "northern rail", "uber", "bolt ", "taxi",
        ),

        // -- home and bills -------------------------------------------------
        rule("Council tax", "council tax", "council"),
        rule(
            "Energy",
            "octopus energy", "british gas", "e on", "eon ", "edf", "ovo energy",
            "scottish power", "bulb", "utilita", "sse ", "npower",
        ),
        rule(
            "Water",
            "severn trent", "thames water", "united utilities", "anglian water",
            "yorkshire water", "welsh water", "dwr cymru", "wessex water",
            "southern water", "northumbrian", "water plc", "hafren",
        ),
        rule(
            "Broadband",
            "virgin media", "plusnet", "talktalk", "hyperoptic", "community fibre",
            "broadband", "bt group", "bt plc", "openreach",
        ),
        rule(
            "Mobile",
            "vodafone", "giffgaff", "lebara", "lycamobile", "id mobile", "o2 ",
            "three uk", "ee limited", "ee ltd", "mobile",
        ),
        rule("Mortgage", "mortgage"),
        rule("Rent", "rent "),
        rule("Repairs", "screwfix", "toolstation", "plumber", "electrician"),

        // -- insurance ------------------------------------------------------
        rule("Home insurance", "home insurance", "buildings insurance", "contents insurance"),
        rule("Life insurance", "life insurance", "life cover"),
        rule("Insurance", "aviva", "axa", "legal and general", "lv ", "insurance", "insure"),

        // -- subscriptions and entertainment --------------------------------
        rule(
            "Subscriptions",
            "netflix", "spotify", "disney", "amazon prime", "prime video", "apple com bill",
            "itunes", "google play", "youtube", "audible", "xbox", "playstation",
            "nintendo", "patreon", "dropbox", "adobe", "microsoft", "now tv", "tv licence",
        ),
        rule(
            "Days out",
            "odeon", "vue cinema", "cineworld", "cinema", "national trust", "english heritage",
            "alton towers", "zoo", "theme park", "theatre",
        ),

        // -- shopping -------------------------------------------------------
        rule(
            "Shopping",
            "amazon", "argos", "ikea", "b and q", "wickes", "homebase", "dunelm",
            "primark", "asos", "tk maxx", "sports direct", "john lewis", "currys",
            "very co uk", "shein", "temu", "ebay", "etsy", "wilko", "poundland",
            "home bargains", "b and m", "the range", "next retail", "matalan",
        ),

        // -- health, pets, children ------------------------------------------
        rule("Health", "boots", "superdrug", "pharmacy", "dentist", "dental", "specsavers",
            "vision express", "optician", "bupa", "nuffield"),
        rule("Pets", "pets at home", "veterinary", "vets", "jollyes"),
        rule("Childcare", "nursery", "childcare", "playgroup"),
        rule("School", "school", "college fees"),

        // -- money owed -------------------------------------------------------
        rule("Credit & loans", "klarna", "clearpay", "paypal credit", "credit card",
            "loan", "finance ltd"),
    )

    /** Income is far less varied: a wage, a refund, or interest. */
    private val INCOME_RULES: List<Rule> = listOf(
        // Money coming back out of a saver is not income the household earned,
        // and cash paid in at a counter is not income either. Both are the
        // same words as on the way out; only the direction differs.
        Rule("Savings", PotWords.SAVINGS),
        Rule("Cash", PotWords.CASH),
        rule("Salary", "salary", "wages", "payroll", "pay ref"),
        rule("Child benefit", "child benefit", "hmrc chb"),
        rule("Benefits", "dwp", "universal credit", "hmrc", "pension credit"),
        rule("Interest", "interest", "gross int"),
        rule("Refunds", "refund", "reversal", "chargeback"),
    )

    /**
     * How much of a remembered merchant must match before a prefix counts.
     * Short prefixes would let "bp" claim "bpost" and similar.
     */
    private const val MIN_LEARNED_PREFIX = 6
}
