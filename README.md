# Finance Tracker

An Android app that replaces the "Book r and h" household budget spreadsheet, and
improves on it: the recurring bills post themselves, the months archive
themselves, the arithmetic cannot drift, and nothing is ever silently
overwritten.

Everything lives on the phone. There is no account to create, no server, and no
telemetry. The only optional network use is fetching exchange rates and the UK
bank holiday calendar, and that is off until you turn it on.

---

## What it does

| Area | What you get |
|---|---|
| **Dashboard** | Balances, this month's income and spending, what is genuinely left to spend, upcoming and overdue bills, recent entries, savings progress, category breakdown, net worth. Cards can be reordered and hidden. |
| **People and accounts** | Any number of people; any number of accounts each (current, savings, cash, credit card, loan, mortgage, investment, pension). View one account, one person, or the whole household. |
| **Income and expenses** | Unlimited sources and bills, each with a name, amount, frequency, dates, category, account, person, notes and an optional reminder. |
| **Recurring payments** | Weekly, fortnightly, every four weeks, monthly, quarterly, six-monthly, yearly, or every N days/months. Posted automatically, with a "check this" flag for bills whose amount varies. |
| **Monthly rollover** | At the start of each month, balances carry over, recurring entries appear, and the finished month is archived. Nothing is overwritten. |
| **Savings** | Multiple goals with targets, contributions, target dates, progress bars, and an honest answer to "will I actually get there in time?". |
| **Categories** | Fully editable, colour-coded, nestable one level deep, with optional monthly budgets. |
| **Reports** | Monthly and yearly spending, category breakdown, income vs expenses, account balances, net worth, cash flow, savings history, and a full printable summary. |
| **Charts** | Donut, grouped bar and line charts, drawn in-app with no third-party library — and each one readable by a screen reader. **Tap a pie slice** to see every entry behind it and how it compares with last month; **tap a bar** to jump to that month; **tap the balance line** to read a single day. |
| **Search and filters** | Search names, notes, tags, categories, accounts and people. Filter by date, amount, type, category, person and account. |
| **Import** | Read an existing `.xlsx` or `.csv` budget. A sheet with a column of figures per person is recognised automatically and imported whole in one tap; anything else is mapped by hand with a sensible first guess. Either way you preview exactly what will be created before anything is written. |
| **Export and print** | PDF, CSV and Excel, A4 portrait or landscape, printed through Android's own print system. |
| **Backup** | Manual and automatic backups to anywhere the system file picker can reach — including Drive, OneDrive or Dropbox — plus restore. |
| **Notifications** | Bills due, overdue payments, low balances and savings milestones, each switchable on its own. |
| **Security** | PIN (PBKDF2-hashed in hardware-backed encrypted storage), fingerprint or face unlock, and automatic locking. |
| **Design** | Material 3, light and dark, large tap targets, and a "larger text" setting. |

## Documentation

| Document | What it covers |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Building and installing the app |
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | How to use it, day to day |
| [docs/DATABASE.md](docs/DATABASE.md) | Every table and column, and why |
| [docs/IMPORT.md](docs/IMPORT.md) | Importing the existing spreadsheet |
| [docs/EXTENDING.md](docs/EXTENDING.md) | How to add features later |
| [docs/EXTERNAL_DATA.md](docs/EXTERNAL_DATA.md) | Which live figures are possible and which are not |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the code is arranged |

## Technology

- **Kotlin** with **Jetpack Compose** and **Material 3**
- **Room** over SQLite, with exported schemas and real migrations
- **MVVM**: Compose screens → ViewModels → repositories → DAOs
- **Hilt** for dependency injection
- **WorkManager** for the daily rollover, reminders and backups
- **DataStore** for preferences
- Minimum **Android 10** (API 29), targets **Android 15** (API 35)

No charting library, no networking library and no Excel library: the charts, the
`.xlsx` reader and writer, and the two HTTP calls are all written against the
platform. That keeps the APK small and the dependency list short enough to
audit.

## Quick start

```bash
git clone <this repository>
cd Accounting-app
./gradlew assembleDebug
./gradlew installDebug     # with a device or emulator attached
```

The app starts with no financial data — only a set of categories to get going
with. There are two ways to fill it:

- **Settings → Load the example household** puts the original spreadsheet's
  figures in as real accounts, bills and goals, to explore before committing to
  anything.
- **More → Import a spreadsheet** reads your actual workbook. If it has a column
  of figures per person, the app spots that and imports the whole sheet in one
  tap.

## Running the tests

```bash
./gradlew test
```

The unit tests cover the parts where a bug would be expensive and silent: money
parsing and rounding, the recurrence engine's month-end and leap-year handling,
the savings projections, the CSV reader, the Excel writer, the search query
builder, and the sample data's agreement with the original spreadsheet.

## Licence

Private project. All rights reserved by the repository owner.
