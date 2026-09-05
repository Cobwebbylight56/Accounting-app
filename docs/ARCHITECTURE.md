# Architecture

MVVM, one module, clear package boundaries. The rule is that dependencies point
**inwards**: UI knows about repositories, repositories know about DAOs, and
nothing in `domain/` or `data/` knows anything about Compose.

```
        ┌─────────────────────────────────────────────┐
        │  ui/          Compose screens + ViewModels  │
        └────────────────────┬────────────────────────┘
                             │ StateFlow / suspend calls
        ┌────────────────────▼────────────────────────┐
        │  data/repository/   validation, AppResult   │
        │  domain/            recurrence, rollover,   │
        │                     reports                 │
        └────────────────────┬────────────────────────┘
                             │ Flow / suspend
        ┌────────────────────▼────────────────────────┐
        │  data/local/        Room DAOs and entities  │
        │  data/prefs/        DataStore               │
        │  data/remote/       two optional GETs       │
        └─────────────────────────────────────────────┘
```

---

## Packages

| Package | What lives there |
|---|---|
| `core/` | Things with no dependencies of their own: `Money`, `DateUtils`, `AppResult`, `Validators` |
| `data/local/` | Room — entities, DAOs, converters, query projections, seed data |
| `data/repository/` | One repository per aggregate; validation and `AppResult` wrapping |
| `data/prefs/` | DataStore preferences |
| `data/importer/` | Spreadsheet reading, column detection, mapping, candidate building |
| `data/export/` | CSV, `.xlsx`, PDF and printing |
| `data/backup/` | JSON backup, restore, serialisation |
| `data/remote/` | HTTP client and the external-figures repository |
| `domain/model/` | The vocabulary: enums, persisted by name |
| `domain/recurrence/` | Recurrence maths and the transaction generator |
| `domain/rollover/` | Monthly archive engine |
| `domain/report/` | Report models and the builder |
| `di/` | Hilt modules |
| `work/` | WorkManager workers, the scheduler, the boot receiver |
| `notify/` | Notification channels and builder |
| `security/` | PIN storage, biometrics, the app lock |
| `ui/` | Theme, shared components, navigation, and one package per screen |

---

## The rules the code follows

### Money is pence in a `Long`

`Money.kt` is the only place that converts between the stored representation and
what a user sees. Nothing else parses or formats an amount.

### Amounts are positive; direction is a type

A transaction's `amount_minor` is never negative; `type` says which way the money
went. This means a mistyped minus sign cannot silently turn an expense into
income, and reports can group by type without worrying about signs.

### Balances are derived in SQL, never stored

See [DATABASE.md](DATABASE.md). A stored balance is a second source of truth
that will eventually disagree with the transactions that explain it.

### Failure is a value, not an exception

Every operation that can fail for a reason a user should see returns
`AppResult<T>`. The UI has to unwrap it, so it cannot forget to render the
failure. `runCatchingApp` converts a thrown exception into a `Failure` while
re-throwing `CancellationException`, so coroutine cancellation still works.

### Validation lives in one place

`core/validation/Validators.kt` plus the `validate` function on each repository.
The same rules therefore apply whether a record arrives from a form, a
spreadsheet import or a restored backup.

### The UI is a function of database state

Every screen's state comes from Room `Flow`s combined in a ViewModel and exposed
as a `StateFlow`. There is no manual refresh anywhere, and no cached copy that
can go stale: saving a transaction on one screen updates the dashboard, the
reports and the savings progress at once, because they are all reading the same
source.

### Nothing blocks the main thread

Every database and file operation is `suspend` and runs on the injected IO
dispatcher.

---

## Key components

### `RecurrenceCalculator`

Pure functions, no dependencies, fully unit tested. Occurrence dates are derived
from the start date and an occurrence index rather than by chaining `plusMonths`
— which is what makes a bill due on the 31st land on 28 February and then
**return to 31 March**, instead of drifting to the 28th forever. The index is
estimated arithmetically and then corrected, so a rule that started in 2000
resolves in microseconds rather than by stepping through 300 iterations.

### `RecurringTransactionGenerator`

Walks each rule's cursor forward, writing transactions. Idempotent: the cursor
only moves forwards, and every candidate is checked against
`existsForRuleOnDate` first, so running it twice — after a restore, say — cannot
duplicate anything.

### `MonthlyRolloverEngine`

Generates what is due, then writes an immutable snapshot per account for each
finished month. Balances "carry over" implicitly because a balance *is* the
opening balance plus every transaction; inventing a carry-over transaction would
double-count and make the ledger disagree with the bank.

### `ReportBuilder`

Builds reports as plain data (`Report` → `ReportSection` → `ReportRow`), so the
same object drives the screen, the PDF, the CSV and the Excel export. They
cannot disagree with each other. Adding a report means adding a `ReportType` and
one `build…` function.

### `TransactionQuery`

Assembles the search SQL at runtime because SQLite rejects an empty `IN ()`, so
a single fixed statement cannot express "filter by these accounts, or by none".
All user input is bound as an argument; only identifiers this file controls are
interpolated.

### `XlsxReader` / `XlsxWriter`

An `.xlsx` file is a ZIP of XML, and both `java.util.zip` and an XML pull parser
are in the platform — so reading and writing real workbooks costs a few hundred
lines and nothing in APK size, where Apache POI would add several megabytes and
a long list of desugaring problems.

### Charts

Drawn directly on a Compose `Canvas`. No charting library means no dependency to
track, a smaller APK, and — the reason that actually matters — a real
`contentDescription` on every chart, so a screen reader reads out the figures.

They are interactive. The donut computes its slice sweeps once and both draws
and hit-tests from that same list, so what you tap is always what you see; the
hit test also checks the radius, since the ring is hollow and the total sits in
the middle. The bar chart treats the whole column as the target rather than the
bar, so a quiet month is as easy to hit as a busy one. Every chart's
`contentDescription` says what tapping does.

### `InsightEngine` and `Forecaster`

Both are pure: values in, advice out, no database and no clock of their own, so
every rule is testable. `InsightRepository` is the only part that knows how to
gather the inputs.

Three constraints shape every rule, because advice that is wrong or nagging is
worse than none:

* **Every message contains the figure.** "£42 more on takeaways than usual",
  not "spending has increased".
* **Each rule states how much history it needs** and stays silent below it. A
  200% rise on £1 is not a finding; nor is a 2% rise on £1,000. A change must
  clear both a percentage and a cash threshold.
* **Comparisons wait until they are fair.** Measuring a full month's average
  against the first three days of a new one always flatters, so category
  comparisons hold off until the month is far enough through.

The forecaster is deliberately boring. Known amounts are counted exactly, using
the same recurrence engine that actually posts them, so an annual premium lands
in the month it is due rather than being smeared across twelve. Only the
discretionary remainder is averaged. Below two complete months it declines to
project at all and the UI says why.

### `HouseholdLayoutDetector`

Recognises the shape of a hand-built budget sheet — a column of figures per
person, blocks separated by blank rows, a derived "both" column — and produces
one `ImportMapping` per person per block. The normal import pipeline then turns
those into candidates, so the one-tap path and the manual path share all of the
same code below the mapping. It reads only; nothing is written until the user
confirms the preview.

---

## Threading and background work

| Job | When | Notes |
|---|---|---|
| `RolloverWorker` | Daily, 00:15 | Generates due entries and archives finished months. Daily rather than monthly so a phone that is off on the 1st catches up on the 2nd. |
| `ReminderWorker` | Daily, at the user's chosen hour | Bills, overdue payments, low balances, savings milestones |
| `BackupWorker` | Daily, 02:00 | Only when enabled and a folder is chosen |
| `ExternalDataWorker` | Daily | Unmetered connections only |

All are idempotent, because WorkManager gives at-least-once delivery and a phone
that has been off for a week runs several in quick succession when it returns.

`FinanceApp` also runs the rollover on launch, so opening the app after a gap
brings everything up to date immediately rather than at the next scheduled run.

---

## Testing

Unit tests cover the parts where a bug would be expensive and silent:

| Test | What it protects |
|---|---|
| `MoneyTest` | Parsing and rounding, including European decimal commas and accountancy brackets |
| `RecurrenceCalculatorTest` | Month-end clamping, leap years, catching up, end dates and limits |
| `SavingsProjectionTest` | "Will I get there in time?" |
| `CsvReaderTest` | Quoted fields, embedded newlines, delimiter detection |
| `XlsxWriterTest` | The archive contains every part Excel needs; text is escaped |
| `TransactionQueryTest` | Filters build correctly, and user input is bound rather than concatenated |
| `HouseholdLayoutDetectorTest` | The example sheet's layout is read correctly: both people found, the "both" column ignored, total rows excluded |
| `InsightEngineTest` | Real changes are reported with their figures; noise, and anything without enough history, stays quiet |
| `ForecasterTest` | Known bills land in the month they are due; no projection is offered without enough history |
| `SampleDataTest` | The sample figures still agree with the original spreadsheet |
| `DateUtilsTest`, `ValidatorsTest` | Date handling and input rules |

```bash
./gradlew test
```
