package com.rhys.financetracker.data.releases

import java.time.LocalDate

/**
 * What changed in each version, in plain words, newest first: shown once
 * after an update, and all of it under Settings → What's new.
 *
 * Every release adds its entry at the top. A test checks the top one is the
 * version being built, so an update never arrives without saying what's in it.
 */
object ReleaseNotes {

    data class Release(
        val version: String,
        val date: LocalDate,
        /** The one line that sums it up. */
        val headline: String,
        val changes: List<String>,
    )

    /** Every release, newest first. */
    val ALL: List<Release> = listOf(
        Release(
            "1.52.0", LocalDate.of(2026, 10, 10), "What's new, and no more doubled bills",
            listOf(
                "After each update, this note shows the new version and what changed. Every version's notes are in Settings → What's new.",
                "A regular payment the bank renamed — \"VIRGIN MEDIA\", then \"VIRGIN MEDIA PYMTS\" — shows once, under the name it goes by now. A price rise doesn't split it either.",
                "Two payments to one company, like two insurance policies, stay two.",
                "A bill you've set up isn't suggested again under a slightly different name.",
                "When a bill's price changes, the statement replaces the copy the app added on its day, so the month doesn't count it twice.",
                "Live payments: Test it checks the app can read your alerts, and What it heard lately shows each banking alert and what was done with it.",
            ),
        ),
        Release(
            "1.51.0", LocalDate.of(2026, 10, 10), "Receipts broken down into groups",
            listOf(
                "Each item on a receipt goes into Groceries, Food, Drinks, Clothes, Household, Personal, Health, Children, Pets or Fuel.",
                "The breakdown shows a bar of what went on each group, then each group with its items. Tap ⇄ to move an item; it's remembered next time.",
                "A new Drinks category, and Food is orange so it stands apart from Groceries.",
            ),
        ),
        Release(
            "1.50.0", LocalDate.of(2026, 10, 10), "Receipts read better",
            listOf(
                "Shops are found by name — B&M, Home Bargains, Tesco, Aldi and many more — never the slogan under the logo.",
                "The total is found even when a creased receipt puts it a line away from \"Visa\", then from the subtotal, then from what the items add up to.",
                "Pet food, kitten milk, Oreos, Ribena, brushes and carrier bags are sorted into the right groups.",
            ),
        ),
        Release(
            "1.49.0", LocalDate.of(2026, 10, 3), "Make any payment regular",
            listOf(
                "Open a payment and tap Set up on Happens regularly? to make it a direct debit, standing order or savings move, on its day.",
                "It's added by itself each time, no statement needed — and never twice: the statement or a bank alert is matched to it.",
                "Payment lists show the type: Direct debit, Standing order, To savings.",
            ),
        ),
        Release(
            "1.48.0", LocalDate.of(2026, 10, 3), "Needs a look pops up",
            listOf(
                "Things that need a look pop up when you open the app. Hide them for a week if you like.",
                "Payments added from a bank alert are marked Live until the statement replaces them.",
                "Subscriptions (Netflix, Audible, Claude) are kept apart from bills and services (insurance, phone, TV).",
                "When payments are re-sorted, a note says how many, with Undo.",
            ),
        ),
        Release(
            "1.47.0", LocalDate.of(2026, 10, 3), "A simpler layout",
            listOf(
                "Five tabs: Home, Spending, Payments, Accounts and More.",
                "One + button for everything you add: a payment, a receipt, a statement or a move.",
                "Needs a look, Bills & subscriptions, and a page for each payment with its receipt.",
            ),
        ),
        Release(
            "1.46.0", LocalDate.of(2026, 10, 3), "Split a payment across categories",
            listOf("A shop can count as food, clothes and household at once, filled in from its receipt."),
        ),
        Release(
            "1.45.0", LocalDate.of(2026, 10, 3), "Screenshots of payment lists",
            listOf("A screenshot of Google Wallet's or a banking app's list is read row by row, each row its own payment."),
        ),
        Release(
            "1.44.0", LocalDate.of(2026, 10, 3), "Receipts",
            listOf("Scan a receipt or a screenshot to add a payment, and keep photos with any payment."),
        ),
        Release(
            "1.43.0", LocalDate.of(2026, 10, 3), "Google Wallet backs up your bank's alerts",
            listOf("A tap to pay is added even if the bank's alert is slow, and only once if both arrive."),
        ),
        Release(
            "1.42.0", LocalDate.of(2026, 10, 3), "Live payments",
            listOf(
                "Payments added the moment your banking app's alert says money moved — switch on in Settings → Live payments.",
                "Overpayments come off what you owe; PayPal and Klarna confirmations are read too; a reminder when a statement is ready.",
            ),
        ),
        Release(
            "1.41.0", LocalDate.of(2026, 10, 2), "Change month from the bottom",
            listOf("The month bar sits at the bottom with arrows either side, and you can swipe to change month."),
        ),
        Release(
            "1.40.0", LocalDate.of(2026, 10, 2), "Easier loans and mortgages",
            listOf("Set a loan or mortgage up with its rate, time left and monthly payment, and see when it'll be paid off."),
        ),
    )

    /**
     * What's new since [seen] up to [current], newest first. Without a
     * version seen — the first update since this was added — just [current].
     */
    fun since(seen: String?, current: String): List<Release> {
        if (seen == null) return ALL.filter { it.version == current }
        return ALL.filter { compare(it.version, current) <= 0 && compare(it.version, seen) > 0 }
    }

    /**
     * The version a build's name is, as these notes name it: "1.52.0" from
     * "1.52.0 (build 123)" off the build server, or "1.52.0-debug".
     */
    fun versionOf(buildName: String): String =
        Regex("""^\d+(\.\d+)*""").find(buildName.trim())?.value ?: buildName.trim()

    /** "1.52.0" against "1.9.3", part by part as numbers. */
    fun compare(one: String, other: String): Int {
        val a = one.split('.').map { it.toIntOrNull() ?: 0 }
        val b = other.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
