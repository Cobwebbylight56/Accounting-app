package com.rhys.financetracker.domain.tips

import com.rhys.financetracker.data.releases.ReleaseNotes

/** The pages a tip can show on. */
enum class TipPlace { HOME, PAYMENTS, PAYMENT_EDIT, SPENDING, SUBSCRIPTIONS, CATEGORIES, IMPORT, ACCOUNTS, LIVE_PAYMENTS, SETTINGS }

/**
 * A short tip on a page. One with [newIn] points out something added in
 * that version, where it is; one without is an everyday tip.
 */
data class Tip(
    val id: String,
    val place: TipPlace,
    val title: String,
    val text: String,
    val newIn: String? = null,
) {
    val isNew: Boolean get() = newIn != null
}

/**
 * Every tip, and which to show. A page shows one tip at a time — what's new
 * first, so a new thing is found — and "Got it" brings the next.
 *
 * Everyday tips can be turned off. Pointers to what's new are on unless
 * turned off, and even then anything added in a later version is still
 * pointed out once: something new is always shown where it is.
 */
object TipBook {

    val ALL: List<Tip> = listOf(
        // ------------------------------------------------------------- Home
        Tip(
            "home-tips", TipPlace.HOME, "Tips like this one",
            "Each page has a tip or two, and new things are marked New. Choose which tips and pop-ups you see in " +
                "More → Settings → Tips & reminders.",
            newIn = "2.5.0",
        ),
        Tip(
            "home-left-over", TipPlace.HOME, "Tap \"Left over a month\"",
            "See how it's worked out, month by month. Something bought once, like a car, can be marked One-off so it " +
                "doesn't count.",
            newIn = "2.4.0",
        ),
        Tip(
            "home-add", TipPlace.HOME, "Add anything with +",
            "The + at the bottom adds a payment, scans a receipt, imports a statement or moves money between accounts.",
        ),
        Tip(
            "home-tabs", TipPlace.HOME, "Everyone, each of you, and Joint",
            "The tabs along the top show the whole household, one person, or your joint money. Every figure follows " +
                "the tab.",
        ),
        Tip(
            "home-needs-look", TipPlace.HOME, "Needs a look",
            "Anything worth checking — payments with no category, a statement missing — is listed under Needs a " +
                "look, a tap away.",
        ),

        // ------------------------------------------------- a payment, edited
        Tip(
            "edit-account", TipPlace.PAYMENT_EDIT, "Whose money, then which account",
            "Tap a person, then one of their accounts. Two accounts with the same name can't be mixed up.",
            newIn = "2.4.0",
        ),
        Tip(
            "edit-one-off", TipPlace.PAYMENT_EDIT, "One-off",
            "Bought a car, or paid for a holiday in full? Turn on One-off and it's left out of spending and left " +
                "over. Your balance still counts it.",
            newIn = "2.4.0",
        ),
        Tip(
            "edit-category", TipPlace.PAYMENT_EDIT, "Categories in groups",
            "Tap Category to see the groups — Food, Transport, Shopping — open one and pick. Or search, say " +
                "\"takeaway\".",
            newIn = "2.3.0",
        ),
        Tip(
            "edit-amount", TipPlace.PAYMENT_EDIT, "Typing an amount",
            "Just type the digits: 2, 4, 9, 9 is 24.99. The point and the comma go in by themselves.",
            newIn = "2.3.0",
        ),
        Tip(
            "edit-payee", TipPlace.PAYMENT_EDIT, "One shop, one category",
            "Change a payment's category and every payment to the same shop moves with it. New statements follow.",
        ),

        // ------------------------------------------------------- Payments
        Tip(
            "payments-find", TipPlace.PAYMENTS, "Finding a payment",
            "Search by shop or note, or use the filter to pick an account, a person, a category or an amount.",
        ),
        Tip(
            "payments-tap", TipPlace.PAYMENTS, "Tap a payment",
            "See it, keep a receipt with it, split it across categories, or put it right.",
        ),

        // ------------------------------------------------------- Spending
        Tip(
            "spending-tap", TipPlace.SPENDING, "Tap the charts",
            "Tap a bar or a slice to see exactly which payments are in it.",
        ),

        // ----------------------------------------------- Bills & subscriptions
        Tip(
            "subs-sort-out", TipPlace.SUBSCRIPTIONS, "Sort them out",
            "Go through them one at a time: you pay it, you've cancelled it, it isn't yours, or it's the same as " +
                "another under a different name.",
            newIn = "2.3.0",
        ),
        Tip(
            "subs-tap", TipPlace.SUBSCRIPTIONS, "Tap one for more",
            "Say when it started and how long it runs, mark it cancelled, or see every payment for it.",
        ),

        // ------------------------------------------------------ Categories
        Tip(
            "categories-groups", TipPlace.CATEGORIES, "Groups that open",
            "Tap the arrow beside a group to open it. Edit a category to move it into another group.",
            newIn = "2.3.0",
        ),

        // ---------------------------------------------------------- Import
        Tip(
            "import-share", TipPlace.IMPORT, "Share straight from your bank",
            "In your bank's app, open a statement and share it to Finance Tracker. Several at once is fine, and " +
                "overlapping ones never double up.",
        ),

        // -------------------------------------------------------- Accounts
        Tip(
            "accounts-tap", TipPlace.ACCOUNTS, "An account's own page",
            "Tap an account to see its payments, statements and balance, or to bring in its statement.",
        ),

        // -------------------------------------------------- Live payments
        Tip(
            "live-once", TipPlace.LIVE_PAYMENTS, "Told twice, added once",
            "When Google Wallet and your bank or card app both tell of a payment, it's added once. Money moved " +
                "between your own banks is one move.",
            newIn = "2.4.1",
        ),

        // -------------------------------------------------------- Settings
        Tip(
            "settings-tips", TipPlace.SETTINGS, "Tips & reminders",
            "Choose which tips and pop-ups you see, and turn the New marks off — anything added later is still " +
                "pointed out.",
            newIn = "2.5.0",
        ),
    )

    /** The tip to show on [place] now, or null for none. */
    fun next(place: TipPlace, seen: Set<String>, tipsOn: Boolean, newOffAt: String?): Tip? =
        ALL.filter { it.place == place && it.id !in seen && shows(it, tipsOn, newOffAt) }
            .sortedBy { if (it.isNew) 0 else 1 }
            .firstOrNull()

    /** Whether [id] is a new thing still to point out with a New mark. */
    fun pointsOut(id: String, seen: Set<String>, newOffAt: String?): Boolean {
        val tip = ALL.firstOrNull { it.id == id } ?: return false
        return tip.isNew && id !in seen && shows(tip, tipsOn = false, newOffAt = newOffAt)
    }

    /**
     * New pointers show unless turned off — and then still for anything
     * newer than the version they were turned off in. Everyday tips follow
     * their switch.
     */
    private fun shows(tip: Tip, tipsOn: Boolean, newOffAt: String?): Boolean {
        val newIn = tip.newIn ?: return tipsOn
        return newOffAt == null || ReleaseNotes.compare(newIn, newOffAt) > 0
    }
}
