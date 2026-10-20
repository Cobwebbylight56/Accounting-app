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
            "2.4.0", LocalDate.of(2026, 10, 20), "One-offs, and accounts under your names",
            listOf(
                "Mark a payment as a one-off — buying a car, a holiday paid in full, a windfall — and it's left out of spending, money in, left over, the averages and the progress reports, so one big payment doesn't make the month look broken. Your balances still count it.",
                "Every payment has a One-off switch. Tap \"Left over a month\" and each of the biggest payments has a One-off button beside it; one-offs are listed apart there, with Count it to put one back. They're tagged One-off in Payments.",
                "Choosing an account is whose first, then which: the payment editor (and From and To on a move), regular payments, importing a statement and a savings goal's linked account all show a tab for each of you and Joint, then only that person's accounts. The one already chosen is never hidden.",
                "In Settings → bank alerts, two accounts with one name say whose each is: \"Lloyds Current Account · Rhys\".",
            ),
        ),
        Release(
            "2.3.0", LocalDate.of(2026, 10, 19), "Categories in groups, and left over put right",
            listOf(
                "Choosing a category opens a list of groups — Food, Transport, Shopping, Health, Money and the rest — and each opens up to what's inside, with a search across the lot. \"Just Food\" is there when nothing more specific fits. More → Categories opens and closes the same way.",
                "New categories where things didn't fit: Takeaways and Coffee & snacks under Food, Taxis, Parking and Breakdown cover under Transport, TV licence, Pet insurance, Vets, Pharmacy, Dentist & opticians, Hair & beauty, Electronics and Bank charges. Clothes and Household now sit under Shopping, Hobbies and Holidays under Entertainment, and card payments, payment apps and fees under Money. Payments the app filed itself move into the new ones — Undo on Home puts them back.",
                "\"Left over a month\" counted paying off a card from the bank as spending, on top of what was spent on the card. A bank payment that names one of your cards or loans is now a move between your own accounts, with a note saying why. Tap the tile to see how it's worked out month by month, and the biggest payments counted as spending — tap one to put it right.",
                "Cards and loans set up as an ordinary account — a Capital One card, car finance — are set up as what's owed, with a note.",
                "Money boxes fill in like a till: type 2, 4, 9, 9 and it reads 24.99, with the comma put in from a thousand. No hunting for the point.",
                "Subscriptions has Sort them out: one at a time, say you pay it, you've cancelled it, it isn't yours, or it's the same as another — two names for one subscription become one, and a payment in both is counted once. Sort the list by cost, name or next payment.",
                "Bills found in a statement that aren't bills can be turned down with \"None of these are bills\", and any left unticked aren't offered again.",
            ),
        ),
        Release(
            "2.2.1", LocalDate.of(2026, 10, 16), "Whose money, then which account",
            listOf(
                "Adding a receipt now asks whose money it was first — a tab for each of you, then Joint — and then offers only that person's accounts. Two accounts both called \"Lloyds Current Account\" can no longer be mixed up.",
                "Only accounts a payment comes out of are offered — current accounts and cards — with savings, ISAs and loans behind \"Show savings and loans too\". Choosing a person picks their account straight away.",
                "The same for a screenshot of a list of payments, and payments offered as a match say whose account they're in when two share a name.",
            ),
        ),
        Release(
            "2.2.0", LocalDate.of(2026, 10, 16), "Receipts from emails and websites",
            listOf(
                "Select a receipt's words in an email or on a website, tap Share and choose Add as a receipt: the shop, total and date are read exactly, then matched to the bank's payment or added as a new one.",
                "PDF receipts and invoices — an Amazon invoice, an emailed receipt, an order saved as a PDF — can be shared or opened with the app, or chosen on the receipt screen. One that reads like a receipt goes straight there; one that isn't a statement offers Keep it as a receipt.",
                "Online receipts are read properly: the total including VAT, \"Order Total (2 items)\", dates like \"October 14, 2026\", a greeting at the top, and who a PayPal payment went to. Dozens of online shops are known by name.",
                "A receipt matched to a payment the bank named vaguely, like \"PAYPAL *4029357733\", files the payment by the receipt's shop.",
            ),
        ),
        Release(
            "2.1.1", LocalDate.of(2026, 10, 16), "Lloyds statements from the app",
            listOf(
                "Statements downloaded from the Lloyds app are read properly: they print the headings again above every payment, and every payment is now read, with which way it went taken from its heading and checked against the balance.",
                "The summary at the top of one (\"Money In… Balance on…\") is no longer taken for payments. Where an earlier version did, those are put away by themselves, and the payment it read with jumbled words in its name is tidied — import the statement again and the wage and anything else it missed are added, without doubling the rest.",
                "A statement with no payments in it says so, instead of saying the layout can't be read.",
            ),
        ),
        Release(
            "2.1.0", LocalDate.of(2026, 10, 16), "How you're doing",
            listOf(
                "At the start of every month, and again halfway through, a pop-up says how it's going against the month before — for the two of you together and for each of you: doing better, much the same, or needs improving.",
                "Each report compares spending, what was put aside, money in, what was left over and what's owed on cards and loans, names the categories that rose or fell most, and says what would help. Halfway through, it says where the month is heading.",
                "Every report is kept under More → Progress reports.",
                "A + after the tabs on Home adds someone straight away. With one person set up, it says \"Add someone\".",
                "Payments has tabs along the top: Everyone, each person, and Joint.",
                "A new icon and opening animation in Vision 2's colours: a glowing bubble whose line climbs into a little bubble of its own.",
                "Charts and bars draw themselves in: lines from the left, bars growing to their length, and again whenever the figures change. The tabs spring between choices, and the bubble in the bar along the bottom pops up out of the tab you tap.",
            ),
        ),
        Release(
            "2.0.0", LocalDate.of(2026, 10, 15), "Vision 2",
            listOf(
                "A whole new look. Midnight ink and lavender in dark mode, porcelain and lavender in light, with bolder figures and rounder everything.",
                "Home's tiles are glowing gradient bubbles — ocean for what you have, sunset for what you owe, mint for what's saved, violet for what's left over — with bubbles drifting off their edges.",
                "The bar along the bottom floats, and the tab you're on is lifted into a glowing bubble. An account's balance sits in a bubble too.",
                "Charts redrawn: smooth lines that glow, and never pass a month's real figure; slim rounded bars with a gap between the parts of a stacked bar; a ring of rounded pills for where it went.",
                "Chart colours checked so they can be told apart by colour-blind eyes, in both light and dark.",
            ),
        ),
        Release(
            "1.59.0", LocalDate.of(2026, 10, 11), "Regular payments right on every card",
            listOf(
                "A regular payment from a credit card is a Card payment, never a Direct debit or Standing order. Ones already set up are put right by themselves.",
                "Subscriptions you set up yourself count as subscriptions on the Spending charts too.",
                "Set up by you and Home's coming-up bills show card payments in yellow and paying a card off in green, like everywhere else.",
                "A standing order to a person, like rent to a landlord, is listed under Bills & services. A fixed sum to someone every month counts; sending money now and then doesn't.",
                "Each subscription and bill says how it's paid: Direct debit, Standing order or Card payment.",
                "Junk lines an older version read from a Capital One statement (\"New transactions\", \"Dear Mr …\") are put away, and \"Payment – Thank You\" read as money out is turned into paying the card off. No need to undo and import again.",
            ),
        ),
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
