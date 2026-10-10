package com.rhys.financetracker.data.receipts

/**
 * Which group an item off a receipt belongs in — groceries, food, drinks,
 * clothes, household and so on — so a shop is broken down into what it was
 * really spent on: the Red Bull and Oasis in a B&M shop are Drinks, the cat
 * food Pets, the dustpan brush Household.
 *
 * Receipts shorten everything ("FF MNS TSHIRT", "UNLD 95"), so the words are
 * matched as they tend to be printed. What the user chose for an item before
 * always wins. Anything not recognised has no group of its own: it counts
 * under the payment's own category.
 */
object ItemCategoriser {

    /**
     * The category for [item], by name, or null to leave it with the
     * payment's own. [learned] is item key ([keyOf]) to category name.
     */
    fun categoryFor(item: String, learned: Map<String, String> = emptyMap()): String? {
        learned[keyOf(item)]?.let { return it }
        // "2X75GCOTTON" and "473MLORIGINAL": a size run into the word after it.
        val text = " " + item.lowercase()
            .replace(Regex("""[^a-z0-9&]+"""), " ")
            .replace(Regex("""(\d)(ml|ltr|kg|pk|g|l|m)(?=[a-z])"""), "$1$2 ") + " "
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

    const val GROCERIES = "Groceries"
    const val FOOD = "Food"
    const val DRINKS = "Drinks"
    const val CLOTHES = "Clothes"
    const val HOUSEHOLD = "Household"
    const val PERSONAL = "Personal"
    const val HEALTH = "Health"
    const val CHILDREN = "Children"
    const val PETS = "Pets"
    const val FUEL = "Fuel"

    /** A group a receipt is broken down into, and what goes in it. */
    data class Group(val category: String, val about: String)

    /** The groups, in the order a breakdown shows them. */
    val GROUPS: List<Group> = listOf(
        Group(GROCERIES, "Food for meals: meat, bread, milk, fruit and veg, tins, frozen"),
        Group(FOOD, "Snacks and treats: crisps, chocolate, biscuits, sweets, food on the go"),
        Group(DRINKS, "Pop, energy drinks, juice, water, coffee and alcohol"),
        Group(CLOTHES, "Clothes, shoes and accessories"),
        Group(HOUSEHOLD, "Cleaning, kitchen, bags, bulbs and batteries"),
        Group(PERSONAL, "Toiletries, cotton wool and make-up"),
        Group(HEALTH, "Medicine and first aid"),
        Group(CHILDREN, "Nappies, baby things and toys"),
        Group(PETS, "Pet food, litter and treats"),
        Group(FUEL, "Fuel and things for the car"),
    )

    /** Where [category] comes in the breakdown; groups first, in order, then anything else. */
    fun orderOf(category: String?): Int =
        GROUPS.indexOfFirst { it.category.equals(category, ignoreCase = true) }.let { if (it < 0) GROUPS.size else it }

    /**
     * Words as receipts print them, by group, checked in this order — so
     * "DR PEPPER" is a drink before "pepper" can make it groceries, "MILK
     * CHOCOLATE" food before "milk" can, and "BABY POTATOES" groceries
     * because only baby things, not the word baby, are Children. A word
     * ending in * matches anything starting with it ("choc*" for chocolate).
     */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        FUEL to listOf(
            "unleaded", "unld", "unl", "diesel", "petrol", "super unleaded", "v power", "vpower", "momentum",
            "ultimate", "fuel", "pump", "e10", "e5", "adblue", "screenwash", "engine oil", "de icer",
        ),
        CLOTHES to listOf(
            "f&f", "george", "tu", "tshirt", "t shirt", "tee", "tees", "jeans", "jumper", "hoodie", "dress",
            "socks", "sock", "shirt", "blouse", "leggings", "pyjama*", "pjs", "bra", "briefs", "boxers", "knickers",
            "shoes", "trainers", "coat", "jacket", "skirt", "shorts", "joggers", "cardigan", "vest", "slippers",
            "hat", "gloves", "scarf", "tights", "onesie", "dressing gown",
        ),
        CHILDREN to listOf(
            "nappies", "nappy", "pampers", "huggies", "baby wipes", "baby food", "baby milk", "baby bottle",
            "baby oil", "baby shampoo", "baby lotion", "formula", "aptamil", "cow & gate", "toy", "toys", "lego",
            "crayons", "colouring", "stickers", "dummy", "dummies",
        ),
        PETS to listOf(
            "cat food", "dog food", "whiskas", "felix", "pedigree", "purina", "bakers", "cat litter", "litter",
            "dog treats", "dentastix", "pet", "pets", "cat", "cats", "for cats", "for dogs", "kitten*", "puppy",
            "puppies", "jelly meat", "dreamies", "go cat", "bonio", "harringtons", "webbox", "marthas", "garfield",
            "fish food",
        ),
        HEALTH to listOf(
            "paracetamol", "ibuprofen", "calpol", "lemsip", "plasters", "vitamin*", "medicine", "antihistamine",
            "piriton", "gaviscon", "rennie", "strepsils", "sudafed", "first aid", "cold & flu", "throat",
        ),
        PERSONAL to listOf(
            "shampoo", "conditioner", "toothpaste", "toothbrush", "deodorant", "deo", "shower gel", "body wash",
            "razor*", "soap", "moisturiser", "sanitary", "tampons", "towels", "makeup", "make up", "mascara",
            "lynx", "dove", "colgate", "gillette", "nivea", "sensodyne", "mouthwash", "cotton buds", "cotton wool",
            "cotton", "cotton pads", "hairspray", "hand cream", "face cream", "sun cream", "shaving", "lip balm", "nail*",
        ),
        HOUSEHOLD to listOf(
            "bleach", "washing up", "fairy", "detergent", "laundry", "persil", "ariel", "bold", "fabric",
            "softener", "comfort", "lenor", "toilet roll", "toilet rolls", "toilet tissue", "andrex",
            "kitchen roll", "plenty", "bin bags", "bin liners", "foil", "cling", "sponge*", "cleaner", "dettol",
            "flash", "domestos", "finish", "dishwasher", "batteries", "duracell", "bulb", "light bulb", "candle*",
            "air freshener", "febreze", "tissues", "kleenex", "zoflora", "cif", "mr muscle", "wipes", "brush",
            "dustpan", "mop", "bucket", "useable bag", "reusable bag", "carrier bag", "carrier", "bag for life",
            "baking paper", "freezer bags", "sandwich bags",
        ),
        DRINKS to listOf(
            "coke", "coca", "cola", "pepsi", "fanta", "sprite", "lucozade", "red bull", "redbull", "monster energy",
            "monster ultra", "energy drink", "rockstar", "water", "sparkling", "juice", "smoothie", "oasis", "tango",
            "irn bru", "dr pepper", "7up", "lemonade", "ribena", "vimto", "fruit shoot", "capri sun", "lilt",
            "robinsons", "cordial", "j2o", "latte", "cappuccino", "americano", "iced coffee", "coffee",
            "tea bags", "teabags", "pg tips", "yorkshire tea", "hot chocolate", "milkshake", "frijj", "yazoo",
            "slush", "drink", "drinks", "beer", "lager", "ale", "cider", "wine", "prosecco", "vodka", "gin",
            "whisky", "rum", "stella", "carling", "budweiser", "corona", "fosters", "strongbow", "kopparberg",
            "costa", "starbucks",
        ),
        FOOD to listOf(
            "crisps", "choc*", "sweets", "haribo", "mars", "snickers", "twix", "kitkat", "kit kat", "doritos",
            "walkers", "pringles", "quavers", "wotsits", "hula hoops", "monster munch", "mccoys", "skips",
            "popcorn", "gum", "mints", "polo", "cookie*", "biscuit*", "digestive*", "hobnob*", "jaffa", "cake",
            "cakes", "muffin*", "doughnut*", "donut*", "flapjack*", "brownie*", "croissant*", "pastries",
            "sausage roll*", "pasty", "pasties", "meal deal", "sandwich", "sandwiches", "ice cream", "ice lolly",
            "lolly", "lollies", "magnum", "cornetto", "oreo*", "peperami", "marshmallow*", "mallows", "squares",
            "toffee*", "fudge", "jelly babies", "fruit pastilles", "starburst", "skittles", "maltesers",
            "buttons", "dairy milk", "galaxy", "cadbury", "kinder", "aero", "milkybar", "milky way", "mini eggs",
            "easter egg*", "cereal bar*", "protein bar*", "snack*",
        ),
        GROCERIES to listOf(
            "bread", "loaf", "rolls", "baps", "bagel*", "crumpet*", "wraps", "tortilla*", "milk", "butter",
            "margarine", "flora", "lurpak", "cheese", "cheddar", "mozzarella", "yogurt*", "yoghurt*", "cream",
            "eggs", "egg", "chicken", "beef", "mince", "steak", "pork", "lamb", "bacon", "sausage*", "ham",
            "turkey", "gammon", "fish", "salmon", "tuna", "cod", "haddock", "prawn*", "fish fingers", "nugget*",
            "burger*", "pizza*", "chips", "fries", "potato*", "veg", "vegetable*", "carrot*", "onion*", "tomato*",
            "pepper*", "broccoli", "peas", "sweetcorn", "mushroom*", "lettuce", "salad", "cucumber", "apple*",
            "banana*", "grape*", "orange*", "strawberr*", "berries", "fruit", "lemon*", "avocado*", "pasta",
            "spaghetti", "penne", "fusilli", "lasagne", "rice", "noodle*", "flour", "sugar", "oil", "beans",
            "soup", "sauce", "gravy", "stock cube*", "oxo", "bisto", "curry", "tikka", "korma", "masala",
            "jalfrezi", "bhuna", "balti", "stir fry", "cereal", "cornflakes", "weetabix", "porridge", "oats",
            "granola", "jam", "honey", "marmalade", "peanut butter", "ketchup", "mayo", "mayonnaise", "mustard",
            "vinegar", "salt", "herbs", "spice*", "tinned", "frozen", "ready meal", "pie", "pies", "quiche",
            "custard", "baking", "heinz", "birds eye", "mccain", "warburtons", "hovis", "kingsmill", "quorn",
            "dolmio", "uncle bens", "super noodles",
        ),
    )
}
