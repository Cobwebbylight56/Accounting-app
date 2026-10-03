package com.rhys.financetracker.data.importer

/**
 * Whether a regular payment is a subscription — something signed up to and
 * easily cancelled, like Netflix, Audible or Claude — or a bill for a
 * service the household needs: insurance, the phone, broadband, TV, the
 * car, council tax, energy.
 *
 * Known subscription brands decide it first; otherwise a payment filed under
 * Subscriptions or Entertainment is one, unless it is to a person (a window
 * cleaner, say) or reads like a bill. Everything else is a bill or service.
 */
object SubscriptionKinds {

    fun isSubscription(name: String, categoryName: String?): Boolean {
        val text = " " + name.lowercase().replace(Regex("""[^a-z0-9+]+"""), " ") + " "
        if (BRANDS.any { " $it " in text || text.contains(" $it") && it.length >= 6 }) return true
        if (BILL_WORDS.any { " $it " in text }) return false
        if (categoryName !in SUBSCRIPTION_CATEGORIES) return false
        return !PayeeNames.isPersonName(name.trim())
    }

    /** Streaming, apps, software, memberships: signed up to, cancelled with a tap. */
    private val BRANDS = listOf(
        "netflix", "spotify", "audible", "amazon prime", "prime video", "amznprime", "disney", "disney+", "apple",
        "itunes", "apple com bill", "youtube", "google one", "google play", "xbox", "playstation", "nintendo",
        "uber one", "uber", "now tv", "nowtv", "paramount", "britbox", "crunchyroll", "patreon", "adobe",
        "microsoft", "dropbox", "icloud", "anthropic", "claude", "openai", "chatgpt", "duolingo", "deliveroo plus",
        "just eat+", "puregym", "pure gym", "the gym", "david lloyd", "nuffield", "peloton", "strava", "headspace",
        "calm", "kindle", "deezer", "tidal", "discord", "twitch", "canva", "linkedin", "tinder", "hinge",
        "bumble", "match com", "ancestry", "medium", "substack", "onlyfans", "hellofresh", "gousto", "graze",
    )

    /** Words that make a payment a bill for a service, whatever it is filed under. */
    private val BILL_WORDS = listOf(
        "insurance", "ins", "assurance", "council", "water", "energy", "gas", "electric", "octopus", "edf",
        "eon", "e on", "ovo", "british gas", "utility", "sky", "virgin media", "bt", "talktalk", "plusnet",
        "ee", "vodafone", "o2", "three", "giffgaff", "tesco mobile", "id mobile", "smarty", "tv licence",
        "tv licensing", "dvla", "mortgage", "loan", "finance", "rent", "nursery", "school", "rac", "aa",
    )

    private val SUBSCRIPTION_CATEGORIES = setOf("Subscriptions", "Entertainment", "Hobbies")
}
