# Extending the app

The project is arranged so that the common kinds of change are additive: you add
a file or a constant, and the rest of the app picks it up. This is a set of
worked recipes for the changes most likely to be wanted.

Read [ARCHITECTURE.md](ARCHITECTURE.md) first if you have not.

---

## The golden rules

1. **Never rename an enum constant.** They are persisted by name, in the
   database and in backups. Adding is free; renaming silently breaks every
   existing row that used it.
2. **Never store money as a `Double`.** Use `Long` pence and `Money.kt`.
3. **Never add `fallbackToDestructiveMigration()`.** Write the migration.
4. **Never store a balance.** Derive it.
5. **Put validation in the repository**, not in the ViewModel, so imports and
   restores get the same rules as typed input.
6. **Return `AppResult`** from anything that can fail in a way the user should
   see.

---

## Add a field to an existing record

Say you want a sort code on an account.

1. **Entity** — add the property with a default, so existing code still compiles:

   ```kotlin
   @ColumnInfo(name = "sort_code") val sortCode: String? = null,
   ```

2. **Version** — bump `AppDatabase.DATABASE_VERSION` to 2.

3. **Migration** — in `data/local/migration/Migrations.kt`:

   ```kotlin
   val MIGRATION_1_2 = Migration(1, 2) { db ->
       db.execSQL("ALTER TABLE accounts ADD COLUMN sort_code TEXT")
   }

   val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
   ```

4. **Backup** — add it to `BackupSerializer.accountToJson` and
   `accountFromJson`. Use `optStringOrNull`, so an older backup still restores.

5. **UI** — add the field to `AccountForm`, a `LabelledTextField` in
   `AccountEditScreen`, and the mapping in `AccountEditViewModel.save()`.

6. Build once so the schema JSON is exported, and commit it.

---

## Add a new dashboard card

Two edits and it appears everywhere, including the layout settings.

1. **Constant** — add to `DashboardWidget` in `domain/model/Enums.kt`:

   ```kotlin
   BUDGET_PROGRESS("budget_progress", "Budget", true),
   ```

2. **Composable** — write it in `ui/dashboard/DashboardCards.kt` and add a
   branch to the `when` in `DashboardScreen.kt`:

   ```kotlin
   DashboardWidget.BUDGET_PROGRESS -> BudgetProgressCard(state)
   ```

New installs get it in the position of its enum constant; existing installs pick
it up the next time `DefaultData.defaultDashboardWidgets()` runs, so add a small
migration if you want it to appear for them immediately.

---

## Make a chart do something when it is tapped

Every chart in `ui/components/Charts.kt` takes an optional click callback and an
optional `selectedIndex`; passing null for both leaves it a plain picture.

```kotlin
DonutChart(
    entries = entries,
    selectedIndex = selected,
    onSliceClick = { index -> selected = index },   // null when the hole is tapped
)
ChartLegend(entries, selectedIndex = selected, onEntryClick = { selected = it })
```

Two things to keep right:

1. **Index against the same filtered list the chart drew.** The charts drop
   zero-value entries, so `entries[index]` is not `totals[index]` unless you
   filter first — see `SpendingByCategoryCard`.
2. **Wire the legend to the same action.** A two-degree slice is not a usable
   target; the legend row is.

---

## Add a new report

1. **Constant** — add to `ReportType` in `domain/report/ReportModels.kt`.
2. **Builder** — add a `build…` function to `ReportBuilder` and a branch to its
   `when`.

That is all. The reports screen, the PDF, the CSV and the Excel export are all
driven by `Report`, so they pick it up with no further change.

```kotlin
private suspend fun weeklySpending(period: ReportPeriod, scope: ReportScope): Report {
    val totals = transactionDao.getCategoryTotals(
        TransactionType.EXPENSE.name, period.start, period.end,
        scope.accountId, scope.personId,
    )
    return Report(
        type = ReportType.WEEKLY_SPENDING,
        title = "Weekly spending",
        period = period,
        scope = scope,
        generatedOn = DateUtils.today(),
        sections = listOf(
            ReportSection(
                title = "By category",
                rows = totals.map {
                    ReportRow(
                        label = it.categoryName ?: "Uncategorised",
                        value = Money.format(it.totalMinor),
                        colorHex = it.categoryColor,
                    )
                },
            ),
        ),
        charts = ReportCharts(categoryTotals = totals),
    )
}
```

---

## Add a new frequency

1. Add to `Frequency` with a sensible `approximateOccurrencesPerYear` — that is
   what normalises it to a monthly figure for budgeting.
2. Add a branch to `RecurrenceCalculator.occurrenceDate` and to `indexOf`.
3. Add a test to `RecurrenceCalculatorTest`. This is the one place where a
   subtle bug produces wrong money quietly, so do not skip it.

Nothing else changes: the pickers list `Frequency.entries`.

---

## Add a new account type

Add to `AccountType`, saying whether it is a liability and whether it counts as
savings:

```kotlin
CRYPTO("Cryptocurrency", isLiability = false, isSavings = true),
```

Net worth, the savings totals and the type breakdown all read those flags, so
they are correct immediately.

---

## Add a new screen

1. **Route** — add a constant to `Routes`, and a pattern if it takes an
   argument.
2. **ViewModel** — `@HiltViewModel`, inject repositories, expose a
   `StateFlow<YourState>` built with `combine(...).stateIn(...)`.
3. **Screen** — a `@Composable` taking callbacks for navigation, never a
   `NavController`. That keeps it previewable and testable.
4. **Register** — add a `composable(...)` in the right function in
   `FinanceNavHost.kt`.

The convention for editors is `savedStateHandle.get<String>(Routes.ARG_ID)`,
with `0` meaning "create a new one".

---

## Add a new external figure

See [EXTERNAL_DATA.md](EXTERNAL_DATA.md). Add a constant to `ExternalDataKey`
with `hasAutomaticSource = false` and it appears in the "entered by hand" list
straight away. Add a `refresh…` function and flip the flag when a real API
exists, and it moves to the automatic list by itself.

---

## Add a new export format

1. Add to `ExportFormat` with its extension and MIME type.
2. Add a branch to `ExportManager.exportReportToCache` and `exportReportToUri`.
3. Write the encoder next to `CsvExporter` and `XlsxWriter`.

---

## Add a new background job

1. Write an `@HiltWorker` in `work/Workers.kt`. Make it idempotent — WorkManager
   gives at-least-once delivery.
2. Add a `schedule…` function to `WorkScheduler` and call it from
   `scheduleAll()`.

Use `ExistingPeriodicWorkPolicy.KEEP` for a fixed schedule so re-scheduling on
launch does not reset its clock, and `UPDATE` when the user can change its
timing.

---

## Splitting into Gradle modules

Single-module is right for a project this size — it keeps the build simple and
the navigation between files short. If it grows past that, the package
boundaries already match the module boundaries you would want:

```
:core       core/
:data       data/, domain/
:feature-*  one per ui/ package
:app        MainActivity, navigation, DI wiring
```

The one thing to fix first is that ViewModels currently depend on repositories
directly. Introducing use-case classes in `domain/` would let feature modules
depend on `:domain` alone. That is worth doing when a second developer joins,
and not before.

---

## Things deliberately left out, and how to add them

| Feature | Where to start |
|---|---|
| **Budgets per category** | `categories.monthly_budget_minor` already exists and is editable. Add a `BudgetProgressCard` and a report comparing it with `getCategoryTotals`. |
| **Receipt photos** | New `attachments` table with the transaction id and a `content://` URI; copy the file into app storage so the URI stays valid. |
| **Bank import (OFX/QIF)** | New reader beside `CsvReader`; reuse the whole mapping and preview flow. |
| **Another spreadsheet layout** | Add a detector beside `HouseholdLayoutDetector` returning `List<ImportMapping>`; everything below the mapping is already shared. |
| **Multi-currency** | `accounts.currency_code` already exists. Add a stored rate per date and convert at the report layer, never at the entity layer. |
| **Sync between phones** | The backup format is already a complete, versioned document. The honest version needs per-record timestamps (they exist: `updated_at`) and a conflict rule. |
| **Widgets** | Glance, reading the same repositories. |

---

## Style

- Kotlin official style; the project builds with `kotlin.code.style=official`.
- Comments explain **why**, not what. If a comment restates the code, delete it.
- No hard-coded values: dimensions in the theme, strings in `strings.xml` or
  next to their use, versions in `gradle/libs.versions.toml`.
- Every user-facing string is written as though a non-technical person will read
  it — because they will. "Choose the account the money is going to", not
  "Invalid transfer target".
