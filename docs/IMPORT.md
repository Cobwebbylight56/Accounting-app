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

## The layout problem, and how to work with it

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

The way through it is to **import the sheet once per person**:

1. Import it with the *Amount* column set to Rhys's column (F) and the person
   set to "Rhys".
2. Go back into the importer and do it again with the amount column set to
   Hannah's column (H) and the person set to "Hannah".

Leave the "both" column (J) alone — it is a total the app recalculates for you,
and importing it would double-count everything.

---

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
