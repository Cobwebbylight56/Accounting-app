# Importing your spreadsheet

The app can read an existing `.xlsx` or `.csv` budget and turn it into real
accounts, bills and transactions. This walks through doing that with the
original "Book r and h" workbook, which is a good example because it is laid out
the way household spreadsheets usually are rather than the way importers usually
expect.

**Nothing is written to the app until the last step.** A wrong guess costs you
nothing but a tap on Back.

---

## Before you start

Open the spreadsheet on a computer and check two things:

1. **The file is `.xlsx` or `.csv`.** Older `.xls` files cannot be read
   directly — open it in Excel or Google Sheets and *Save as* `.xlsx`.
2. **You know which rows hold data.** The importer asks you for the heading row
   and the first and last data rows, counting from 0.

---

## Bank statements — building up a spending history

This is the route that needs no bank connection at all, and it is how you build
a spending history going back as far as your bank keeps records.

Export a statement from online banking — **PDF or CSV** — and open it here.
The app recognises the layout, so there is nothing to map. PDF is what most
banking apps offer; CSV, where available, reads more reliably.

### PDF statements

Most banking apps only offer PDF, so PDFs are read directly.

A PDF has no columns — it is lines of text, and nothing in it marks which
figure is the amount, which is the balance, or whether money came in or went
out. The **running balance** settles it: between two rows the balance moves by
exactly the amount of the transaction, and the direction of that move says
which way the money went.

That makes the reading self-checking. When no figure on a line matches the
change in balance, something was misread — a wrapped description, a fee sharing
a line — and the row is **flagged on the review screen** rather than guessed at.
Look over anything marked before importing.

Two things to know:

* **A CSV export reads more reliably** where your bank offers one. It is
  usually on the website rather than the app: look for "Download", "Export" or
  "Statements" on the account page, and choose CSV or Excel over PDF.
* **Scanned or photographed statements will not work.** There is no text in
  the file to read, only a picture of one. The app says so rather than
  importing nothing quietly.

Password-protected PDFs need saving as an unprotected copy first.

### What it handles

Bank exports are all slightly different, and the awkward parts are dealt with:

| What banks do | What happens |
|---|---|
| Put the account name and sort code above the headings | The headings are searched for, not assumed to be on the first line |
| Use "Paid out" and "Paid in" (Lloyds, Nationwide) | Read as two columns; the side with a figure decides the direction |
| Use one signed "Amount" (Monzo, Starling) | A minus means money out |
| Include a running **Balance** column | Recognised and deliberately ignored |
| Word things differently — Debit, Credit, Withdrawn, Money out | All recognised |

The balance column matters more than it looks. It is a column of entirely
plausible amounts sitting next to the real one, and importing it would add the
account's whole history a second time.

### Importing old statements

Import them in any order, oldest or newest first. Each row carries its own
date, so the ledger sorts itself out. Downloading a year at a time and
importing each file is the quickest way to fill in the past.

### Nothing is ever counted twice

Statements overlap. Downloading "the last three months" every month means two
thirds of each file has been seen before.

Every imported row is fingerprinted from its account, date, amount, direction
and description. On the next import, rows already held are shown but not
selected, and the summary says how many were skipped — seeing *58 already had*
is how you know it worked.

It counts rather than merely matching, so two identical coffees on the same day
both survive: if the file holds three of a row and two are stored, the third is
imported.

Because the fingerprint includes the account, choose the right one when you
import. The same £40 at the same shop on the same day can honestly appear on
two different cards.

### Spending is sorted for you

Descriptions like `TESCO STORES 3294` are matched against the shops and
services a UK household meets, so rows arrive already filed under Groceries,
Fuel, Energy, Subscriptions and so on.

Two things make it better over time:

* **Your corrections win.** Re-file one `SAINSBURYS SPRUCE HILL` from Groceries
  to Fuel and every later import of that payee follows, even though the
  built-in rule says otherwise.
* **Unknown payees are left blank** rather than guessed at. An empty category
  is obvious and quick to fix; a wrong one is neither.

## The quick way: let the app read the layout

When you pick the file, the app looks at its shape. If it sees a **column of
figures for each person** — which is how the "Book r and h" sheet is built — it
says so and offers to import the whole thing in one tap:

> **This looks like a household budget**
> Rhys and Hannah found, across 3 blocks: income (1 row), savings & (4 rows),
> outgoings (7 rows).
>
> [ Import the whole sheet ]

Press it and you go straight to the preview, with every row from every block
listed and ready to check. Nothing is saved until you press Import.

What it works out for itself:

- **Who the people are** — from the row reading `Rhys | Hannah | both`.
- **Which column belongs to whom**, reading each person's column separately.
- **That the "both" column is a total**, and ignoring it. This matters: importing
  it as well would count every amount twice.
- **Where the blocks start and stop**, from the headings (`savings &`,
  `OUTGOINGS`) and the blank rows between them.
- **What each block is** — balances, income or bills.
- **Which rows are totals** (`spent`, `left over`, `ALL SAVINGS`) and leaving
  them out, so you do not end up with a bill called "spent" for £1,941.63.

If the guess is wrong anywhere, **Change** on the preview screen takes you to
the manual mapping below, and nothing has been written.

---

## The manual way

Use this when your sheet is laid out differently, or when the detection gets
something wrong.

### The layout problem, by hand

A spreadsheet like this one:

```
        C          D        E         F        G       H        I     J
 2   income  befor tax  27,455.76          16,692.48
 3                        Rhys                Hannah              both
 4   income                1862.23              1447            3309.23
 ...
19   car                     60.75             80.59             141.34
20   road tax                37.62             24.06              61.68
21   fuel                       80                70                150
```

has **one column of figures per person** and one derived "both" column. Most
importers assume one row per record with a person column — this one has a person
per *column*.

The way through it by hand is to **import the sheet once per person**:

1. Import it with the *Amount* column set to Rhys's column (F) and the person
   set to "Rhys".
2. Go back into the importer and do it again with the amount column set to
   Hannah's column (H) and the person set to "Hannah".

Leave the "both" column (J) alone — it is a total the app recalculates for you,
and importing it would double-count everything.

## Step by step

### 1. Choose the file

**More → Import a spreadsheet → Choose a file.**

Pick the workbook. The app reads it and shows you a preview of the first eight
rows with their row numbers down the left — those numbers are what you type in
the "which rows to read" boxes.

### 2. Say what the rows should become

Four choices:

| Choice | Use it for |
|---|---|
| **Regular bills** | The OUTGOINGS block — each row becomes a monthly bill the app posts for you |
| **Regular income** | The income rows |
| **Account balances** | The "savings &" block — each row becomes an account with a starting balance |
| **One-off transactions** | A list of individual payments with dates |

For the outgoings block, choose **Regular bills**.

### 3. Check the column meanings

The app has already had a guess, based on the headings and on what the columns
actually contain — a column of parseable amounts is treated as an amount column
even with no heading, which hand-built sheets often have.

Set each column to one of:

- **Ignore** — spacer columns and working notes. This is the right answer for
  most columns in a sheet like this one.
- **Name** — column D, the payment names ("car", "road tax", "fuel").
- **Amount** — column F for Rhys, column H for Hannah.
- **Date**, **Category**, **Person**, **Account**, **Notes**, **Type**,
  **Frequency**, **Day of month** — if your sheet has them.

The label above each dropdown shows the heading and a sample value from the
column, so you can tell them apart without going back to the spreadsheet.

### 4. Apply a person and account to every row

This is the part that makes a per-person-column sheet work.

Under **Apply to every row**, type:

- **Person**: `Rhys`
- **Account**: `Rhys bank`
- **Category**: leave empty unless every row shares one

Neither the person nor the account has to exist yet — they are created if they
do not.

### 5. Set the row range

For the outgoings block in the example sheet:

- **Heading row**: the row containing "Rhys" / "Hannah" (row 2, counting from 0)
- **First data row**: the first bill ("car")
- **Last data row**: the last bill ("life insur"), *not* the totals row

Leaving the totals row in would create a bill called "spent" for £1,941.63,
which the preview would show you — but it is easier not to.

### 6. Check the preview

Press **Check N rows**. Every row is listed with what it would create:

- Rows the app can read are ticked.
- Rows it cannot are shown with the reason — "The amount is zero", "No name in
  this row", "\"n/a\" is not an amount" — and are left unticked.

Untick anything you do not want. **All** and **None** are at the top.

### 7. Import

Press **Import**. The app tells you exactly what it created: people, accounts,
categories, regular payments and transactions, plus any rows it had to skip and
why.

### 8. Do it again for the second person

**Import another block**, then repeat from step 2 with column H and "Hannah".

---

## What the importer does for you

- **Matches by name.** An account, person or category that already exists is
  reused, not duplicated. That is what makes it safe to import the same workbook
  again next month to top things up.
- **Creates what is missing.** A category named in the sheet that the app does
  not have is created and given a colour.
- **Guesses account types.** "Saver (Mum)" becomes a savings account, "£1 coins"
  becomes cash, anything with "credit" or "card" in the name becomes a credit
  card.
- **Understands frequency words.** "monthly", "every 4 weeks", "fortnightly",
  "quarterly", "annual" and similar all map to the right frequency.
- **Reads UK dates.** `31/03/2026`, `31-03-26` and `2026-03-31` all work.
  Excel's internal date numbers are converted too.
- **Tidies floating-point noise.** Excel storing `1862.2299999999998` becomes
  £1,862.23.

## What it deliberately does not do

- **It does not import formulas or totals.** Only the values you see. Totals are
  recalculated by the app, which is the point of moving off the spreadsheet.
- **It does not guess which person a column belongs to.** You say so explicitly,
  because getting that wrong silently would be worse than asking.
- **It does not merge into existing transactions.** Importing the same
  transactions twice creates them twice. Import balances and rules from a
  spreadsheet; record transactions in the app.

---

## After the import

1. **More → Accounts** — check the starting balances and set each account's
   type.
2. **More → Regular payments** — check the due dates. The importer sets them all
   to the 1st unless the sheet gave a day, and the real dates matter for
   "Left to spend".
3. Turn on **"The amount changes each time"** for energy, fuel and food, so the
   app asks you to check them rather than assuming last month's figure.
4. **Savings → +** — the spreadsheet's "put away" rows become transfers into
   savings; add a goal so you can see them adding up to something.

---

## If something goes wrong

**"That is an older .xls workbook"** — save it as `.xlsx` or `.csv` first.

**"That file did not contain any data"** — the sheet is empty, or the data is on
a different tab. If the workbook has several tabs, chips appear at the top of
the mapping screen to switch between them.

**Every row says "is not an amount"** — the amount column is pointing at the
wrong column, or at a column of text.

**Amounts are a hundred times too big or small** — a cell was formatted as text
with a stray character. The preview shows this before anything is written.

**You imported the wrong thing** — everything can be deleted. If you have a
backup from before the import, restoring it is the quickest way back.
Taking a backup before a large import is a good habit.
