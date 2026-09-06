# Database structure

SQLite via Room. The schema is exported to `app/schemas/` on every build, so
every change is reviewable and every migration is testable.

Current version: **4**.

| Version | Change |
|---|---|
| 1 | Initial schema |
| 2 | `transactions.import_hash` — the fingerprint that stops a re-imported bank statement being counted twice. Additive and indexed; existing rows are left null, which simply never matches. |
| 3 | `transactions.source` — where a record came from, so a bank statement can correct a remembered entry rather than sit beside it. Existing rows become `UNKNOWN`, the weakest source, because a stored hash says a row was imported but not from what; labelling a hand-built spreadsheet as bank-authoritative would shield it from the very correction this allows. |
| 4 | `accounts.counts_as_savings` — whether an account counts under Saved rather than Available, overriding its type. Nullable on purpose: null means "follow the type", which is true of every account that existed before there was a way to say otherwise. |

---

## Principles

Four decisions shape the whole schema. They are worth understanding before
changing anything.

### 1. Money is stored as whole pence, in a `Long`

Every `*_minor` column is an integer number of the currency's smallest unit.
Binary floating point cannot represent `0.10` exactly, and across thousands of
transactions those errors accumulate into balances that are visibly wrong.
`Money.kt` is the only place that converts between pence and the text a user
sees.

### 2. Amounts are positive; direction is a separate column

`transactions.amount_minor` is never negative. Whether money came in or went out
is carried by `transactions.type`. A mistyped minus sign therefore cannot turn
an expense into income, and every report can group by type without worrying
about signs.

The exception is account balances, which are genuinely signed: an overdrawn
current account and a credit card balance are both negative.

### 3. Balances are computed, never stored

There is no `balance` column. An account's balance is its `opening_balance_minor`
plus every transaction against it, calculated in SQL. A stored balance is a
second source of truth that will eventually disagree with the transactions that
are supposed to explain it, and there is no way to tell which one is right.

### 4. Dates are ISO-8601 text

`2026-03-31`, not an epoch number. Text dates sort correctly with plain string
comparison, are readable when inspecting the database, and cannot shift by a day
when the phone moves time zone. A financial record is a calendar fact, not an
instant.

---

## Tables

### `people`

Everyone whose money is being tracked, plus a shared "Joint" record.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | Auto |
| `name` | TEXT | Unique |
| `color_hex` | TEXT | Tints this person's rows and chart series |
| `is_shared` | INTEGER | True for the household record, which cannot be deleted |
| `sort_order` | INTEGER | Display order |
| `notes` | TEXT? | |
| `is_archived` | INTEGER | Hidden from pickers, kept in history |
| `created_at`, `updated_at` | INTEGER | Epoch milliseconds |

### `accounts`

Where money sits.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | |
| `type` | TEXT | `AccountType` name |
| `person_id` | INTEGER? | FK → `people`, `SET NULL` on delete |
| `opening_balance_minor` | INTEGER | Balance before the first recorded transaction |
| `opening_balance_date` | TEXT | |
| `currency_code` | TEXT | Default `GBP` |
| `overdraft_limit_minor` | INTEGER | Agreed overdraft, a positive number |
| `low_balance_threshold_minor` | INTEGER? | Warn below this |
| `credit_limit_minor` | INTEGER? | Cards; original advance for loans |
| `interest_rate_percent` | REAL? | |
| `color_hex` | TEXT | |
| `include_in_net_worth` | INTEGER | |
| `counts_as_savings` | INTEGER? | Overrides the type for the Saved/Available split. Null follows the type |
| `is_shared` | INTEGER | |
| `sort_order` | INTEGER | |
| `notes` | TEXT? | |
| `is_archived` | INTEGER | |
| `created_at`, `updated_at` | INTEGER | |

Deleting a person leaves their accounts in place, unassigned. Deleting an
account **cascades** to its transactions — which is why the UI recommends
archiving.

### `categories`

Groupings for income and spending, nestable one level deep.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | Unique together with `kind` |
| `kind` | TEXT | `INCOME`, `EXPENSE`, `SAVING`, `CASH` or `TRANSFER`. `SAVING` and `CASH` are money moved rather than earned or spent, and are read in both directions: an expense on one is money into the pot, an income is money back out. No migration — new categories are topped up on launch by name, the same way dashboard cards are. |
| `color_hex` | TEXT | |
| `icon_key` | TEXT? | Material icon name |
| `parent_id` | INTEGER? | FK → `categories`, `SET NULL` |
| `monthly_budget_minor` | INTEGER? | Optional budget |
| `sort_order` | INTEGER | |
| `is_system` | INTEGER | Seeded; renameable but not deletable |
| `is_archived` | INTEGER | |
| `created_at`, `updated_at` | INTEGER | |

### `transactions`

The atom of the application.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `amount_minor` | INTEGER | Always positive |
| `type` | TEXT | `INCOME`, `EXPENSE` or `TRANSFER` |
| `date` | TEXT | ISO-8601 |
| `description` | TEXT | |
| `account_id` | INTEGER | FK → `accounts`, `CASCADE`. The source for a transfer |
| `transfer_account_id` | INTEGER? | FK → `accounts`, `SET NULL`. The destination |
| `category_id` | INTEGER? | FK → `categories`, `SET NULL` |
| `person_id` | INTEGER? | FK → `people`, `SET NULL`. Falls back to the account's owner |
| `recurring_rule_id` | INTEGER? | Set when generated from a rule |
| `savings_goal_id` | INTEGER? | Set when it contributes to a goal |
| `notes` | TEXT? | Searchable |
| `is_confirmed` | INTEGER | False for a generated variable-amount entry awaiting a check |
| `is_cleared` | INTEGER | False for a payment arranged but not yet gone through |
| `tags` | TEXT? | Comma separated, searchable |
| `import_hash` | TEXT? | Fingerprint of account, date, amount, direction and payee. Set on import, null when typed in. Indexed, and matched by **count** so two identical purchases on one day both survive |
| `source` | TEXT | Where the record came from: `UNKNOWN`, `MANUAL`, `SPREADSHEET`, `STATEMENT`. A statement outranks the rest and may correct them; nothing may overwrite a statement row |
| `is_archived` | INTEGER | Excluded from balances and totals |
| `created_at`, `updated_at` | INTEGER | |

Indexed on every foreign key, on `date`, and on `(date, type)` — the combination
the dashboard and every report filter by.

### `recurring_rules`

Templates that generate transactions on a schedule.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | |
| `amount_minor` | INTEGER | |
| `type` | TEXT | |
| `frequency` | TEXT | `Frequency` name |
| `interval` | INTEGER | Multiplier for the custom frequencies |
| `start_date` | TEXT | Also fixes the day of the month |
| `end_date` | TEXT? | |
| `max_occurrences` | INTEGER? | |
| `occurrences_generated` | INTEGER | |
| `next_due_date` | TEXT | **The engine's cursor** — the next occurrence not yet generated |
| `last_generated_date` | TEXT? | |
| `account_id` | INTEGER | FK → `accounts`, `CASCADE` |
| `transfer_account_id` | INTEGER? | |
| `category_id`, `person_id`, `savings_goal_id` | INTEGER? | |
| `mode` | TEXT | `AUTO_POST`, `CONFIRM` or `REMIND_ONLY` |
| `reminder_days_before` | INTEGER? | Null disables the reminder |
| `is_variable_amount` | INTEGER | Posts unconfirmed so the user checks it |
| `notes` | TEXT? | |
| `is_paused`, `is_archived` | INTEGER | |
| `created_at`, `updated_at` | INTEGER | |

Catching up is simply "while `next_due_date` <= today, generate and advance".
Because the cursor only moves forwards and each candidate entry is checked
against what already exists, running the generator twice cannot create
duplicates.

### `savings_goals`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | |
| `target_amount_minor` | INTEGER | |
| `manual_adjustment_minor` | INTEGER | Balance for a hand-tracked goal |
| `monthly_contribution_minor` | INTEGER | |
| `target_date` | TEXT? | |
| `start_date` | TEXT | |
| `account_id` | INTEGER? | When set, the account's balance is the goal's balance |
| `person_id` | INTEGER? | |
| `color_hex`, `icon_key` | TEXT | |
| `notes` | TEXT? | |
| `sort_order` | INTEGER | |
| `is_achieved`, `is_archived` | INTEGER | |
| `created_at`, `updated_at` | INTEGER | |

### `monthly_snapshots`

Immutable per-account records of how a month finished. Written once by the
rollover and never changed.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `year_month` | TEXT | `2026-03`. Unique together with `account_id` |
| `account_id` | INTEGER | FK → `accounts`, `CASCADE` |
| `opening_balance_minor` | INTEGER | |
| `closing_balance_minor` | INTEGER | |
| `total_income_minor` | INTEGER | |
| `total_expense_minor` | INTEGER | |
| `total_transfers_in_minor` | INTEGER | |
| `total_transfers_out_minor` | INTEGER | |
| `transaction_count` | INTEGER | |
| `created_at` | INTEGER | |

Snapshots exist so history stays truthful. Correcting a March transaction in
June changes June's live figures; March's archived figures still show what was
believed at the time.

### `external_data`

| Column | Type | Notes |
|---|---|---|
| `key` | TEXT PK | Matches `ExternalDataKey.key` |
| `value` | TEXT | Text, so a rate, a percentage and a date can share the table |
| `unit` | TEXT | |
| `source` | TEXT | An API host, or "Entered by hand" |
| `is_manual` | INTEGER | |
| `fetched_at` | INTEGER | |
| `last_error` | TEXT? | Populated when a refresh failed |

### `dashboard_widgets`

| Column | Type | Notes |
|---|---|---|
| `widget_key` | TEXT PK | Matches `DashboardWidget.key` |
| `position` | INTEGER | |
| `is_visible` | INTEGER | |

### `import_profiles`

Saved column mappings, so re-importing next month's copy of the same workbook
does not mean re-doing the matching.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | |
| `name` | TEXT | Unique |
| `mapping_json` | TEXT | A serialised `ImportMapping` |
| `source_file_name` | TEXT? | |
| `last_used_at`, `created_at` | INTEGER | |

---

## How a balance is calculated

```sql
SELECT a.opening_balance_minor
     + IFNULL((
         SELECT SUM(CASE WHEN t.type = 'INCOME'
                         THEN t.amount_minor
                         ELSE -t.amount_minor END)
         FROM transactions t
         WHERE t.account_id = a.id AND t.is_archived = 0
       ), 0)
     + IFNULL((
         SELECT SUM(t2.amount_minor)
         FROM transactions t2
         WHERE t2.transfer_account_id = a.id
           AND t2.type = 'TRANSFER'
           AND t2.is_archived = 0
       ), 0)
FROM accounts a
WHERE a.id = :accountId
```

The first subquery covers the account as the source (income adds; expenses and
outgoing transfers subtract). The second adds transfers arriving from elsewhere.
Adding `AND t.date <= :asOf` to both gives a historic balance, which is what the
rollover and the cash flow report use.

---

## Changing the schema

1. Edit the entity in `data/local/entity/`.
2. Increment `AppDatabase.DATABASE_VERSION`.
3. Add a `Migration` to `data/local/migration/Migrations.kt` and put it in
   `Migrations.ALL`:

   ```kotlin
   val MIGRATION_1_2 = Migration(1, 2) { db ->
       db.execSQL("ALTER TABLE accounts ADD COLUMN sort_code TEXT")
   }
   ```

4. If the new column appears in a backup, add it to `BackupSerializer`. Reads
   use `opt…` with a default, so an older backup still restores.
5. Build once so the new schema JSON is exported, and commit it.

`fallbackToDestructiveMigration()` is deliberately absent. A missing migration
should fail loudly in development rather than quietly deleting somebody's
financial history in production.

Enum constants are persisted **by name**. Adding a constant is safe; renaming
one silently breaks every existing row that used it.
