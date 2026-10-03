package com.rhys.financetracker.data.receipts

/**
 * Which category an item off a receipt belongs in: the unleaded on a fuel
 * receipt is Fuel, the drink and crisps beside it Snacks & drinks, the
 * T-shirt in a Tesco shop Clothes.
 *
 * Receipts shorten everything ("FF MNS TSHIRT", "UNLD 95"), so the words are
 * matched as they tend to be printed. What the user chose for an item before
 * always wins. Anything not recognised has no category of its own: it counts
 * under the payment's, which for a supermarket is groceries.
 */
object ItemCategoriser {

    /**
     * The category for [item], by name, or null to leave it with the
     * payment's own. [learned] is item key ([keyOf]) to category name.
     */
    fun categoryFor(item: String, learned: Map<String, String> = emptyMap()): String? {
        learned[keyOf(item)]?.let { return it }
        val text = " " + item.lowercase().replace(Regex("""[^a-z0-9&]+"""), " ") + " "
        return RULES.firstOrNull { (_, words) -> words.any { word -> " $word " in text || (word.endsWith("*") && " ${word.dropLast(1)}" in text) } }
            ?.first
    }

    /**
     * An item's words without prices, sizes, codes or numbers, so "COCA COLA
     * 500ML 1.85" and "COCA COLA 500ML" are one item.
     */
    fun keyOf(item: String): String =
        item.lowercase()
            .replace(Regex("""\d+(\.\d+)?\s*(ml|cl|l|ltr|g|kg|pk|pt|pint|pints|x)?\b"""), " ")
            .replace(Regex("""[^a-z&]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    const val FUEL = "Fuel"
    const val SNACKS = "Snacks & drinks"
    const val CLOTHES = "Clothes"
    const val HOUSEHOLD = "Household"

    /**
     * Words as receipts print them, by category, checked in order. A word
     * ending in * matches anything starting with it ("choc*" for chocolate).
     */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        FUEL to listOf(
            "unleaded", "unld", "unl", "diesel", "petrol", "super unleaded", "v power", "vpower", "momentum",
            "ultimate", "fuel", "pump", "e10", "e5", "adblue",
        ),
        CLOTHES to listOf(
            "f&f", "george", "tu", "tshirt", "t shirt", "tee", "tees", "jeans", "jumper", "hoodie", "dress",
            "socks", "sock", "shirt", "blouse", "leggings", "pyjama*", "pjs", "bra", "briefs", "boxers", "knickers",
            "shoes", "trainers", "coat", "jacket", "skirt", "shorts", "joggers", "cardigan", "vest", "slippers",
            "hat", "gloves", "scarf",
        ),
        "Children" to listOf(
            "nappies", "nappy", "pampers", "huggies", "baby", "formula", "aptamil", "cow & gate", "toy", "toys",
            "lego", "crayons",
        ),
        "Pets" to listOf(
            "cat food", "dog food", "whiskas", "felix", "pedigree", "purina", "bakers", "cat litter", "litter",
            "dog treats", "dentastix", "pet", "pets",
        ),
        "Health" to listOf(
            "paracetamol", "ibuprofen", "calpol", "lemsip", "plasters", "vitamin*", "medicine", "antihistamine",
            "piriton", "gaviscon", "rennie", "strepsils", "sudafed",
        ),
        "Personal" to listOf(
            "shampoo", "conditioner", "toothpaste", "toothbrush", "deodorant", "deo", "shower gel", "body wash",
            "razor*", "soap", "moisturiser", "sanitary", "tampons", "towels", "makeup", "mascara", "lynx", "dove",
            "colgate", "gillette", "nivea", "sensodyne", "mouthwash", "cotton buds", "hairspray",
        ),
        HOUSEHOLD to listOf(
            "bleach", "washing up", "fairy", "detergent", "laundry", "persil", "ariel", "bold", "fabric",
            "softener", "comfort", "lenor", "toilet roll", "toilet tissue", "andrex", "kitchen roll", "plenty",
            "bin bags", "bin liners", "foil", "cling", "sponge*", "cleaner", "dettol", "flash", "domestos", "finish",
            "dishwasher", "batteries", "duracell", "bulb", "light bulb", "candle*", "air freshener", "febreze",
            "tissues", "kleenex", "zoflora", "cif", "mr muscle", "wipes",
        ),
        SNACKS to listOf(
            "crisps", "choc*", "sweets", "haribo", "mars", "snickers", "twix", "kitkat", "kit kat", "doritos",
            "walkers", "pringles", "coke", "coca", "pepsi", "fanta", "sprite", "lucozade", "red bull", "redbull",
            "monster", "energy drink", "water", "sparkling", "juice", "oasis", "tango", "irn bru", "dr pepper",
            "7up", "gum", "mints", "polo", "cookie*", "muffin", "doughnut", "donut", "flapjack", "sausage roll",
            "pasty", "meal deal", "sandwich", "latte", "cappuccino", "americano",
            "hot chocolate", "costa", "starbucks", "slush", "ice cream", "magnum", "cornetto", "drink", "drinks",
        ),
    )
}
