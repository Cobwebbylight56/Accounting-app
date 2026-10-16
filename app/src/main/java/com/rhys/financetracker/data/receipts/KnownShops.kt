package com.rhys.financetracker.data.receipts

/**
 * Shops the app knows by name, so a receipt is put down to "B&M" or "Home
 * Bargains" even when the name on it is only the logo and the first words
 * read are its slogan.
 *
 * Matched on whole words, with "B & M" and "B&M" alike and apostrophes
 * dropped, so "Sainsbury's" and "SAINSBURYS" are one shop.
 */
object KnownShops {

    data class Shop(val name: String, val words: List<String>)

    /** The shop [line] names, if any. */
    fun find(line: String): Shop? {
        val text = normalise(line)
        return SHOPS.firstOrNull { shop -> shop.words.any { " $it " in text } }
    }

    /** True when [line] begins with [shop]'s name: "ASDA PETROL 4275", "Costa Coffee". */
    fun startsWith(line: String, shop: Shop): Boolean {
        val text = normalise(line)
        return shop.words.any { text.startsWith(" $it ") }
    }

    private fun normalise(line: String): String =
        " " + line.lowercase()
            .replace(Regex("""['’]"""), "")
            .replace(Regex("""\s*&\s*"""), "&")
            .replace(Regex("""[^a-z0-9&]+"""), " ")
            .trim() + " "

    private fun shop(name: String, vararg words: String) = Shop(name, words.toList())

    private val SHOPS = listOf(
        shop("B&M", "b&m", "b and m", "bandm"),
        shop("Home Bargains", "home bargains", "homebargains"),
        shop("Tesco", "tesco"),
        shop("Sainsbury's", "sainsburys", "sainsbury"),
        shop("Asda", "asda"),
        shop("Morrisons", "morrisons", "morrison"),
        shop("Aldi", "aldi"),
        shop("Lidl", "lidl"),
        shop("Iceland", "iceland foods"),
        shop("The Food Warehouse", "the food warehouse", "food warehouse"),
        shop("Co-op", "co op", "coop", "co operative"),
        shop("Waitrose", "waitrose"),
        shop("M&S", "m&s", "marks&spencer", "marks and spencer"),
        shop("Farmfoods", "farmfoods"),
        shop("Heron Foods", "heron foods"),
        shop("Costco", "costco"),
        shop("Spar", "spar"),
        shop("Poundland", "poundland"),
        shop("Poundstretcher", "poundstretcher"),
        shop("The Range", "the range"),
        shop("Wilko", "wilko", "wilkinson"),
        shop("Boots", "boots the chemist", "boots uk", "boots pharmacy"),
        shop("Superdrug", "superdrug"),
        shop("Savers", "savers health"),
        shop("Primark", "primark"),
        shop("TK Maxx", "tk maxx", "tkmaxx"),
        shop("Matalan", "matalan"),
        shop("Argos", "argos"),
        shop("Currys", "currys"),
        shop("B&Q", "b&q"),
        shop("Screwfix", "screwfix"),
        shop("Toolstation", "toolstation"),
        shop("Wickes", "wickes"),
        shop("Homebase", "homebase"),
        shop("Dunelm", "dunelm"),
        shop("IKEA", "ikea"),
        shop("Pets at Home", "pets at home"),
        shop("Jollyes", "jollyes"),
        shop("Halfords", "halfords"),
        shop("Card Factory", "card factory"),
        shop("Smyths", "smyths"),
        shop("The Entertainer", "the entertainer"),
        shop("Hobbycraft", "hobbycraft"),
        shop("JD Sports", "jd sports"),
        shop("Sports Direct", "sports direct"),
        shop("Greggs", "greggs"),
        shop("Costa", "costa coffee", "costa"),
        shop("Starbucks", "starbucks"),
        shop("McDonald's", "mcdonalds"),
        shop("KFC", "kfc"),
        shop("Burger King", "burger king"),
        shop("Subway", "subway"),
        shop("Domino's", "dominos"),
        shop("Amazon", "amazon", "amazon co uk"),
        // Online: the names their emails and order pages use.
        shop("eBay", "ebay"),
        shop("Etsy", "etsy"),
        shop("Vinted", "vinted"),
        shop("ASOS", "asos"),
        shop("Shein", "shein"),
        shop("Temu", "temu"),
        shop("Very", "very co uk"),
        shop("Next", "next co uk", "next retail", "next direct"),
        shop("John Lewis", "john lewis"),
        shop("AO", "ao com"),
        shop("Ocado", "ocado"),
        shop("Deliveroo", "deliveroo"),
        shop("Just Eat", "just eat", "justeat"),
        shop("Uber Eats", "uber eats", "ubereats"),
        shop("Uber", "uber"),
        shop("Apple", "apple com", "apple services", "itunes", "app store"),
        shop("Google", "google play", "google one", "google store"),
        shop("Netflix", "netflix"),
        shop("Spotify", "spotify"),
        shop("Audible", "audible"),
        shop("Disney+", "disney plus", "disneyplus"),
        shop("Trainline", "trainline"),
        shop("Ticketmaster", "ticketmaster"),
        shop("Booking.com", "booking com"),
        shop("Airbnb", "airbnb"),
        shop("easyJet", "easyjet"),
        shop("Ryanair", "ryanair"),
        shop("Steam", "steampowered", "steam purchase", "steam store"),
        shop("PlayStation", "playstation"),
        shop("Xbox", "xbox"),
        shop("Nintendo", "nintendo"),
        shop("Boohoo", "boohoo"),
        shop("New Look", "new look"),
        shop("River Island", "river island"),
        shop("H&M", "h&m"),
        shop("Zara", "zara"),
        shop("Waterstones", "waterstones"),
        shop("WHSmith", "whsmith", "wh smith"),
        shop("The Works", "the works"),
    )
}
