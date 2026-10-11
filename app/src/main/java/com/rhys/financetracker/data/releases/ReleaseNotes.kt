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
            "1.58.0", LocalDate.of(2026, 10, 11), "Subscriptions that end, and cards on the charts",
            listOf(
                "Subscriptions that have stopped or been cancelled are hidden, like Uber One. A line at the bottom shows them if you want.",
                "Tap a subscription to say when it started and how long it's for. The app works out when it ends and shows how many payments are left. \"I've cancelled it\" hides it straight away.",
                "Audible on the Zopa card shows as still being paid. A payment read a year ahead had made the card's statements look as if they ran to December.",
                "Card spending an older version read as money in, like Audible in May and June, is turned back into spending.",
                "Spending → Month by month splits money out into the bank (red), credit cards (yellow) and subscriptions (purple). Categories over time always has a line for subscriptions.",
                "Bills & subscriptions shows each subscription as its own colour in the monthly bars.",
                "A card's page has a Spent and paid off chart: spending in yellow, paying it off in green.",
            ),
        ),
        Release(
            "1.57.0", LocalDate.of(2026, 10, 11), "Credit in yellow, paid off in green",
            listOf(
                "Anything spent on a credit card is yellow, so it stands apart from spending from your current account, which stays red. That's on the card's page, in Money, on Home and on the payment itself.",
                "Paying a card off is green and marked \"Paid off\" — on the card, and on the account it was paid from.",
            ),
        ),
        Release(
            "1.56.0", LocalDate.of(2026, 10, 11), "Credit cards that work like credit cards",
            listOf(
                "On a credit card's page, paying it off shows in yellow, marked \"Paid off\". Spending on it stays red, because it adds to what you owe.",
                "The card's page shows what you owe — \"£130.89 owed\" and what's left to spend of your limit — and the month-end graph is what's owed, in yellow, instead of a balance falling below zero.",
                "\"Direct Debit Payment – Thank You\" on a card statement is money in, paying the card off. It used to be read as money out.",
                "A card statement's summary and small print — \"Payments received\", \"New transactions\", your name and address — are no longer read as payments. The any-layout reader now only steps in when the usual one reads nothing.",
                "A card payment that's on both your bank and card statements counts once, as money moved from your bank to the card: not spending, and not income.",
                "Paying a card off is filed under Transfers & payments, so it isn't counted as money in.",
            ),
        ),
        Release(
            "1.55.0", LocalDate.of(2026, 10, 10), "Statements in any layout",
            listOf(
                "A new statement reader for any layout: dates at the start, the end or the middle of a line, in any common form (27/01/2026, 27 Jan, Jan 27, 2026, 2026-01-27), amounts with £, minus, brackets, CR or DR, a date heading several payments, and payees or amounts on the line below. It takes over whenever the usual reader can't make sense of a PDF.",
                "Capital One's PDF statements are read. They only give the year in two figures (\"26 February 26\"), which stopped every row being dated.",
                "On a card statement, the balance going up is spending — the balance check now reads it that way, so card payments aren't flagged to check.",
                "Lines adding up a column (\"STATEMENT TOTALS\") are no longer taken for a payment, and the starting balance is the previous one, not the new one.",
            ),
        ),
        Release(
            "1.54.0", LocalDate.of(2026, 10, 10), "What's left over each month",
            listOf(
                "The tile beside Owed now shows what's left over in a month on average — what came in, less what went out, was put aside and paid off loans — over your last three full months.",
                "Until there's a full month to go on, it shows take-home a month instead.",
            ),
        ),
        Release(
            "1.53.0", LocalDate.of(2026, 10, 10), "Your pay and your money, worked out for you",
            listOf(
                "Your wage is found in your statements — the same employer paying in each month — and set up by itself. It's added on payday, and the statement puts in the exact figure.",
                "Take-home is worked out from what's really paid in. A yearly figure that was really one month's pay is put right.",
                "When your bank's alert says what your balance is, that's what your account shows — nothing to type in.",
                "Money in says when your wage is due, instead of £0.00 before payday.",
                "Left to spend is now Left until payday: what's in your account, less the bills that go out before payday.",
            ),
        ),
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
