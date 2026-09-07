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
    private fun matches(text: String, keyword: String): Boolean {
        if (" $text ".contains(" $keyword")) return true
        // Banks truncate to the width of the column: "SAMSUNGFINAN",
        // "NCC COLLECTI", "DWR CYMRU WE". A word long enough to be
        // distinctive, which is where the keyword starts once the spaces come
        // out of both, is that same payee cut short.
        val parts = keyword.split(' ')
        val squashed = parts.joinToString("")
        return text.split(' ').any { word ->
            word.length >= MIN_TRUNCATED &&
                squashed.length > word.length &&
                // A word that merely equals the first word of a phrase is not
                // a truncation of it. Without this "SAINSBURYS" was read as a
                // cut-off "sainsburys petrol" and the weekly shop was filed
                // under fuel. A real truncation runs past the space.
                (parts.size == 1 || word.length > parts.first().length) &&
                squashed.startsWith(word)
        }
    }

    /**
     * How much of a truncated word must survive before it is matched.
     *
     * Long enough that it identifies one payee. Eight was not: "transfer" is
     * eight characters and the start of "transferwise", so every "Transfer
     * from RHYS EVANS" was filed as a payment service.
     */
    private const val MIN_TRUNCATED = 9

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
            "costco", "makro", "bookers", "heron foods", "premier stores",
            "one stop", "mccoll", "londis", "premier store", "food warehouse",
            "grocer", "butcher", "greengrocer", "milk and more", "milkman",
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
        rule(
            "Car insurance",
            "admiral", "hastings direct", "churchill", "direct line", "esure",
            "goskippy", "go skippy", "pc goskippy", "ageas", "swinton", "1st central",
            "one call", "marmalade", "veygo", "by miles", "tempcover", "car insurance",
            "motor insurance", "gladiator", "quotemehappy", "sheilas wheels",
        ),
        rule(
            "MOT & servicing",
            "kwik fit", "halfords", "national tyres", "mot ", "garage", "formula one autocentre",
            "protyre", "tyres", "autocentre", "motor factors", "euro car parts",
            "car parts", "servicing", "bodyshop", "vehicle repair",
        ),
        rule("Road tax", "dvla", "road tax", "vehicle tax", "vehicle licence"),
        rule(
            "Car finance",
            "car finance", "motability", "vehicle finance", "black horse", "moneybarn",
            "advantage finance", "close brothers motor", "blue motor",
        ),
        rule("Parking", "ringgo", "paybyphone", "parkingeye", "ncp ", "car park", "parking"),
        rule("Breakdown cover", "the aa", "aa membership", "rac ", "green flag", "breakdown"),
        rule(
            "Public transport",
            "trainline", "national rail", "tfl", "oyster", "stagecoach", "arriva",
            "first bus", "lner", "avanti", "northern rail", "uber", "bolt ", "taxi",
        ),

        // -- home and bills -------------------------------------------------
        rule("Council tax", "council tax", "council", "ncc collect", "cbc collect"),
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
            "three uk", "ee limited", "ee ltd", "mobile", "smarty", "voxi",
            "talkmobile", "vectone", "phone bill", "airtime",
        ),
        rule("Mortgage", "mortgage", "halifax mtg", "nationwide mtg"),
        rule("Rent", "rent ", "letting", "lettings", "landlord", "housing assoc"),
        rule(
            "Repairs",
            "screwfix", "toolstation", "plumber", "electrician", "builder",
            "joiner", "roofer", "handyman", "locksmith", "boiler", "gas safe",
            "travis perkins", "jewson", "selco", "buildbase",
        ),
        rule(
            "Home",
            "dunelm", "the range home", "furniture", "carpetright", "sofology", "dfs ",
            "oak furniture", "bensons for beds", "dreams ltd", "garden centre",
            "homeware",
        ),

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
        rule("Pets", "pets at home", "veterinary", "vets", "jollyes", "pet shop", "petplan"),
        rule("Childcare", "nursery", "childcare", "playgroup", "childminder", "after school"),
        rule("School", "school", "college fees", "uniform", "parentpay", "school meals"),
        rule(
            "Fitness",
            "puregym", "the gym group", "david lloyd", "nuffield health", "gym",
            "leisure centre", "swimming", "everyone active", "better uk", "myprotein",
        ),
        rule(
            "Charity",
            "save the children", "cancer research", "oxfam", "barnardos", "rspca",
            "british red cross", "macmillan", "air ambulance", "justgiving", "charity",
        ),
        rule(
            "Holidays",
            "ryanair", "easyjet", "jet2", "tui ", "booking com", "airbnb", "expedia",
            "hotels com", "premier inn", "travelodge", "haven holidays", "center parcs",
            "national express", "eurotunnel", "brittany ferries", "airport",
        ),
        rule("Gifts", "moonpig", "card factory", "clintons", "funky pigeon", "interflora"),

        // -- somebody you pay --------------------------------------------
        //
        // Rent, a cleaner, a window cleaner, a babysitter: paid to a person by
        // name, and a name matches nothing. Only the wordings that say it is a
        // person rather than a shop are here — a bare name still comes through
        // uncategorised, because guessing at one would be worse.
        rule(
            "People & services",
            "cleaner", "window clean", "cleaning", "gardener", "gardening",
            "babysitter", "childminder pay", "tutor", "lessons", "music lesson",
            "driving lesson", "hairdresser", "barber", "therapist", "physio",
            "osteopath", "chiropractor", "counselling", "decorator", "handyman",
            // Deliberately not beauty or nails: both are shop names as often
            // as services, and SAVERS HEALTH AND BEAUTY is a chemist.
        ),

        // -- money owed -------------------------------------------------------
        rule(
            "Credit & loans",
            "klarna", "clearpay", "paypal credit", "credit card", "loan", "finance ltd",
            "samsung finance", "samsungfinance", "apple finance", "very finance",
            "zopa", "novuna", "barclaycard", "capital one", "vanquis", "aqua card",
            "tymit", "laybuy", "zilch", "monzo flex", "credit union",
        ),

        // -- money moved to a person, which no rule can name --------------
        rule(
            "Transfers & payments",
            "paypal", "revolut", "wise ", "transferwise", "western union", "moneygram",
            "gocardless", "sumup", "izettle", "square up", "stripe",
        ),

        // Last of all, because it says only that a card was used. It is
        // spending — the bank simply never says on what — and leaving it as
        // nothing at all was worse than saying that much.
        rule(
            "Card spending",
            "contactless payment", "card payment", "chip and pin",
            "debit card payment", "visa purchase", "mastercard purchase", "pos purchase",
        ),
    )

    /** Income is far less varied: a wage, a refund, or interest. */
    private val INCOME_RULES: List<Rule> = listOf(
        // Money coming back out of a saver is not income the household earned,
        // and cash paid in at a counter is not income either. Both are the
        // same words as on the way out; only the direction differs.
        Rule("Savings", PotWords.SAVINGS),
        Rule("Cash", PotWords.CASH),
        rule("Salary", "salary", "wages", "payroll", "pay ref", "bacs credit", "wage"),
        rule("Child benefit", "child benefit", "hmrc chb", "chb "),
        rule(
            "Benefits",
            "dwp", "universal credit", "hmrc", "pension credit", "pip ", "esa ",
            "tax credit", "housing benefit", "carers allowance", "attendance allowance",
        ),
        rule("Pension", "pension", "annuity", "nest pension", "aviva pension"),
        rule("Interest", "interest", "gross int", "credit interest"),
        rule("Refunds", "refund", "reversal", "chargeback", "reimbursement", "rebate"),
        rule("Selling", "vinted", "depop", "gumtree", "facebook mktp", "ebay payout"),
    )

    /**
     * How much of a remembered merchant must match before a prefix counts.
     * Short prefixes would let "bp" claim "bpost" and similar.
     */
    private const val MIN_LEARNED_PREFIX = 6
}
