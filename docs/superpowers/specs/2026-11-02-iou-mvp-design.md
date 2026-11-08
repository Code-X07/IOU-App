# IOU App — MVP Design (Phases 1–2)

- **Date:** 2026-11-02
- **Status:** Approved; revised 2026-11-02 after an external review (merge model, import of deleted state, rule kinds, numeric contract, export scope, restore commit point, milestone criteria), and again for the ledger generation in the database pointer, draft bases, cross-currency settlement edits, FX snapshot conflicts on import, cascades written by import and import undo, their precedence over per-row import choices and under undo protection (owner deletion and restore, both directions), bank-actual amount edits (rule 16), effects checked against their rates (rule 17), stored shares after merges, and stale import previews (`ledgerSeq`). Synced on 2026-11-06 with the shipped Milestones 0 and 1 (`v0.1.0`): where this spec and the code disagreed, the spec now describes the code. The M0 and M1 plans (`docs/superpowers/plans/`) hold the implementation detail, and ADRs 0008, 0010 and 0011 record the design changes. Extended on 2026-11-07 with **multiple payers per expense** and **recurring expenses**, both added to Milestone 2 (ADRs 0012 and 0013)
- **Progress:** Milestone 0 done (`v0.0.1`), Milestone 1 done (`v0.1.0`), Milestone 2 next (§12)
- **Sources:** `01-core-spec.md` (suggestions), `02-future-features.md` (kept in mind for extension points)
- **Contributors:** project owner (core: domain, data, engine, I/O, security); friend (the whole UI layer, ViewModels included, see §6)

---

## 1. Summary

An Android app that replaces WhatsApp-based tracking of shared expenses and IOUs in a friend group. It keeps running balances with each person, independent of which bank account money moved through, and supports groups with equal / percent / shares / exact splits, expenses paid by several people, recurring expenses, multiple currencies, a full audit history of every edit, and import/export.

This spec covers **Milestone 0 (skeleton and UI contract)**, **Milestone 1 (personal ledger)** and **Milestone 2 (groups and splits)**. Pay/request helpers, auto-detected repayments and multi-user sync (core spec phases 3–5) each get their own spec later. This design makes sure they fit without rework (§13).

### 1.1 Context and constraints

- Small personal project by an independent developer plus one friend who owns the UI layer. Choose the simplest workable option; add structure only when a concrete milestone needs it.
- Single user, single device until sync exists. Everything works offline; the network is used only for exchange rates.
- **Audit history is a core requirement.** Every change to ledger data is recorded and viewable.
- The app never moves money. It records and assists.
- The UI contributor must be able to start as soon as possible, so a stable contract and a fake implementation come first (§6, Milestone 0).

### 1.2 Goals

1. Accurate balances: pairwise with each person outside groups, and per-member net positions inside groups, per currency.
2. Groups where everyone's spending is tracked (full group ledger), not just mine, with suggested settle-up transfers.
3. Exact split math: shares always sum exactly to the total.
4. Multi-currency with rates snapshotted at entry time; per-group choice between converting and keeping original currencies.
5. Complete, append-only edit history with diffs and revert-to-version.
6. A stable identity for "me" and person merging, so that phase 5 sync can link local contacts to real accounts without a translation layer.
7. JSON backup (encrypted by default), CSV and XLSX export, import of own CSV exports.
8. App lock and optional screen protection.

### 1.3 Non-goals (this spec)

- Payment links, EPC QR, bank app launching (phase 3).
- Bank notification parsing, open banking (phase 4).
- Multi-user sync, counterparty confirmation (phase 5 / C2).
- Splitwise import/export, generic CSV/XLSX import wizard, XLSX import of any kind, bank statement import.
- Optimal (minimum-transfer) debt simplification, FIFO "settled bills" view, receipts, analytics.
- Recurring transfers, pausing a schedule, schedules whose amount changes by itself (§5.7).
- User-defined categories; scheduled automatic backups.
- Encrypted local database (SQLCipher).
- Play Store distribution; iOS.

---

## 2. Architecture

### 2.1 Module and packages

The Gradle project lives in `android/` at the repository root (tracked: `android/`, `docs/`, `.github/` and `README.md`). It has a single module `:app` with two product flavors (§2.4). Packages marked "No" contain **no Android imports**. They are testable on the plain JVM and portable to KMP later; `DomainPurityTest` enforces this and the floating-point ban (§11).

| Package | Responsibility | Android imports | Owner |
|---|---|---|---|
| `money` | `Money` value class (Long minor units + ISO 4217 code), minor-unit digits, text→money parsing via `BigDecimal`, formatting | No | Core |
| `ledger.model` | Domain types: Person, PaymentHandle, Group, GroupMember, Transfer, Expense, ExpenseShare, ExpensePayer, RecurringExpense, RecurringParticipant, FxSnapshot, EntryRevision, ImportBatch, plus the shared plain data `FieldChange`, `SplitInput` and `Change` | No | Core |
| `ledger.rules` | `LedgerValidator`: the integrity rules of §3.4 as pure functions over a read-only `LedgerState` | No | Core |
| `ledger.engine` | `SplitResolver`, `FxConverter`, `IdentityResolver` (canonical IDs), `LedgerEngine` (balances), `SettlePlanner`, `RevisionDiff` | No | Core |
| `ledger.ops` | **The write path** (ADR-0008): each write op turns a draft and the current state into a validated `WritePlan` (changed rows plus one revision each). Shared by both flavors | No | Core |
| `ledger.views` | Read projections (overview, details, activity, history) computed from a `LedgerState`. Shared by both flavors | No | Core |
| `api` | **The core ↔ UI contract:** `*Api` interfaces, input and output models, `LedgerError`, `LedgerResult<T>` (§6) | No | Core (changes by PR, §6.4) |
| `store` | `LedgerStore` (ADR-0010): the whole ledger in memory, writes under one lock, the `LedgerJournal` port that persists them, ledger settings; the `Store*Api` classes for setup, people, groups, ledger, settings and FX; the device-settings port, the app-lock policy (`TimedLockGate`), `RecurringRunner` (auto-posting due occurrences, §5.7), the `DocumentStore` port, and the backup and export request rules both flavors share | No | Core |
| `fake` | The `store` without a journal, seeded demo data, fixed demo rates, simulated backup and import (§6.3) | No | Core |
| `fx` | Frankfurter client (Ktor), `RateTable`, `FxService` (lookup chain and rate cache, §4.5) | No | Core |
| `io` | CSV export (normalized zip and report), formula-injection escaping, own-export import (reader, decoder, analysis, preview, undo plan) | No | Core |
| `data` | The real flavor: Room rows, DAO and database, `RoomJournal` (the only writer of the live database's ledger tables), `LedgerLoader`, snapshot codec and upcasters, database pointer and restore marker files, `RestoreRecovery`, JSON backup format and encryption, restore and undo, the real backup and import/export APIs, SAF documents, the device-settings file, the device ID, the Room rate cache, the recurring-expense notifications, `RealBackend` (wiring). The daily rate worker and the `recurring-post` worker live in the `real` source set | Yes | Core |
| `security` | `AppSecurity`: drives the lock policy from the process lifecycle and sets `FLAG_SECURE` (§9) | Yes | Core |
| `ui` | Compose screens, **ViewModels**, navigation, theming, `LedgerError` → string mapping, `BiometricPrompt` calls | Yes | UI contributor |

Dependency direction: `money` ← `ledger.model` ← `api` ← `ledger.*` ← `store` ← `fx`, `io` ← `data` ← flavor `di` modules, and `store` ← `fake`. `ui` imports only `api`, `money` and `ledger.model`.

### 2.2 Data flow

```
ui (ViewModels)  ──calls──▶  api interfaces (Store*Api, plus per-flavor backup and import/export)
                                 │
                                 ▼
          store.LedgerStore: whole ledger in memory, one write lock
            write: ledger.ops builds a WritePlan ── ledger.rules validates it
                   ── LedgerJournal.append ── publish the new state
            read:  ledger.views over the published state (ledger.engine for balances, settle plans)
                                 │
            ┌────────────────────┴─────────────────────┐
     fake flavor                                  real flavor
  journal = nothing                    journal = RoomJournal ──(one Room transaction)──▶ rows + revisions
  (seeded at start)                    start: RestoreRecovery, then LedgerLoader loads the live database
```

- **One store for both flavors** (ADR-0010). The real flavor keeps the whole ledger, revisions included, in memory and loads it from the database named by the pointer (§7.1) at start. Reads and writes wait for that first load.
- **Reads:** `api` exposes `Flow`s of ready-to-render models (overview, person detail, group detail, activity, entry detail), computed by `ledger.views` from the store's published state. Any write, restore or rate update re-emits.
- **Writes:** `suspend` functions returning `LedgerResult<T>`. The store takes its write lock, the op builds and validates the plan against the current state, and the journal persists it before the new state is published. Persisting and publishing run together under `NonCancellable`, so a cancelled caller can never leave committed rows unpublished. Only `RoomJournal` writes the live database's ledger tables (`DataAccessTest`); a restore builds a separate database file (§7.1).
- **Same rules everywhere:** both flavors run the same ops, validator, engine and views, so the fake produces realistic errors and balances.

### 2.3 Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3, Navigation Compose (UI choices beyond this are the UI contributor's) |
| DB | Room 2.8 (SQLite) on the driver API: `AndroidSQLiteDriver` on the device, `BundledSQLiteDriver` in JVM tests; WAL journal mode; suspend DAOs |
| Serialization | kotlinx.serialization: Room rows double as the JSON of revision snapshots and backups |
| Device-local settings | A JSON file in `noBackupFilesDir`, replaced atomically (ADR-0011; DataStore was dropped) |
| DI | Hilt. The rate worker reaches the backend through a Hilt entry point, so `hilt-work` is not used |
| Async | Coroutines + Flow |
| Networking | Ktor client (OkHttp engine) + kotlinx.serialization |
| Background | WorkManager (`real` flavor only): rate refresh, recurring posting |
| CSV | Apache Commons CSV |
| XLSX (export only, M2) | FastExcel writer |
| Crypto | BouncyCastle lightweight API (Argon2id; no JCE provider registered), JCE AES-256-GCM |
| App lifecycle | `androidx.lifecycle:lifecycle-process` (`ProcessLifecycleOwner`, §9) |
| Tests | JUnit 5, Kotest assertions and property tests, Turbine, Room on the plain JVM through the bundled SQLite driver. No Robolectric, no instrumentation tests |
| SDK | **min 30** (Android 11), target latest stable |

Library versions are pinned in `android/gradle/libs.versions.toml`. Gradle runs on JDK 21; the app targets Java 17 bytecode.

### 2.4 Build, flavors and release

- **applicationId:** `io.github.codex07.iou`; display name "IOU" (working title; the display name can change, the ID cannot).
- **Flavors:** `fake` (Hilt binds the in-memory `api` implementation with seeded demo data; applicationId suffix `.fake`, so it installs side by side with the real one) and `real` (Room-backed). Only `real` declares `INTERNET` and contains WorkManager.
- **CI** (GitHub Actions, on push): `lintFakeDebug`, the JVM unit tests of both flavors (which include the Room tests, the `api` contract suites against both backends and `DomainPurityTest`, §11), and both debug builds.
- **Release:** pushing a `v*` tag builds `realRelease`, signs it with the release keystore stored as encrypted repository secrets, and attaches the APK to a GitHub Release. Hyphenated tags publish as pre-releases. The version code is the workflow's run number, so it always grows; local builds default to the current version name. The project owner holds the keystore and keeps an offline backup; the UI contributor never needs it. Updates can be tracked with Obtainium. Debug builds use the default debug key.

---

## 3. Data model

### 3.1 Conventions

- **Money:** `amountMinor: Long` + `currency: String` (ISO 4217). `Double`/`Float` never appear in money paths (CI-enforced). Minor-unit digits come from `java.util.Currency.defaultFractionDigits`.
- **Money limits and overflow:** every stored amount (original, effect, share, EXACT input) lies between 0 and `MAX_MINOR = 10^13` minor units (> 0 where the entity requires it); larger input → `AmountTooLarge`. All `Long` money arithmetic uses `Math.addExact` / `subtractExact` / `multiplyExact`, and `BigDecimal` → `Long` uses `longValueExact()`. An overflow never wraps: if computing the balances of the state a write would produce overflows, the write is rejected with `AmountTooLarge`. (With the cap, that takes about 900 000 maximal entries in one currency.)
- **IDs:** UUID strings everywhere.
- **Identity:** the "me" person's UUID is generated once at first launch and is the user's **permanent global identity**. Backups carry it and restores keep it; it is never regenerated. In phase 5 the user's account is this ID.
- **Device ID:** a UUID generated per installation, stored in `noBackupFilesDir/device-id` (never backed up, restored, or exported). Every revision records it.
- **Sync columns** on every syncable entity (Person, PaymentHandle, Group, GroupMember, Transfer, Expense, RecurringExpense): `id`, `createdAt`, `updatedAt`, `authorId` (always the me identity until sync; rewritten on every write), `deletedAt` (tombstone), `version` (Int, starts at 1, +1 on every write), `importBatchId` (nullable: the import batch that wrote the row's current version; any later write clears it, so a later edit's revision is not tagged with the import).
- **Persisted values** are text and integers: instants as ISO-8601 (`Instant.toString`, nanoseconds kept), dates as `yyyy-MM-dd`, money as minor units plus an ISO code, rates as decimal strings, enums by name. The Room rows have exactly this shape, and so do revision snapshots and backups.
- **Entry dates:** `occurredOn: LocalDate`, the local date the user enters, used for display, filtering and FX lookup. Entries on the same date are ordered by `createdAt`.
- **Sign convention:** for pairs, `net(A, B, currency) > 0` means B owes A. For group members, `groupNet(M) > 0` means the group owes M.
- **Effect amounts:** every money-bearing entry stores both what was actually paid (original) and its *effect* on balances. FX conversion happens once, at write time. The balance engine never converts.
- **Canonical IDs:** all reads and validation resolve person IDs through `IdentityResolver` (§4.1) before use.

### 3.2 Entities

**Person**

| Field | Type | Notes |
|---|---|---|
| displayName | String | |
| isMe | Boolean | Exactly one row is true; its `id` is the global identity |
| note | String? | |
| defaultCurrency | String? | |
| archivedAt | Instant? | See §5.2 |
| mergedIntoId | UUID? | If set, this person was merged into the referenced person and resolves to that person's canonical person (§4.1, §5.6) |
| + sync columns | | |

**PaymentHandle**

| Field | Type | Notes |
|---|---|---|
| personId | UUID | FK → Person |
| type | Enum | `IBAN`, `REVTAG`, `PHONE`, `OTHER` |
| value | String | IBAN uppercase without spaces (mod-97 validated); Revtag without `@`; phone E.164 |
| holderName | String? | Name as registered at the bank |
| bic | String? | |
| label | String | e.g. "Revolut EUR" |
| isDefault | Boolean | At most one default per person record and type. The canonical person's default of a type is its own record's default if it has one, otherwise the default of the alias with the lowest UUID. Setting a default through the `api` clears the flag on every other handle of that type resolving to the same canonical person |
| + sync columns | | |

**Group**

| Field | Type | Notes |
|---|---|---|
| name | String | |
| defaultCurrency | String | |
| fxMode | Enum | `CONVERT` (default) or `KEEP`. Mutability rules in §3.4 |
| archivedAt | Instant? | Archiving = leaving (§5.2) |
| + sync columns | | |

**GroupMember**

| Field | Type | Notes |
|---|---|---|
| groupId | UUID | Unique together with personId |
| personId | UUID | |
| joinedAt | Instant | |
| removedAt | Instant? | Rules in §3.4 |
| + sync columns | | |

**Transfer**: a direct movement of money (loan or repayment).

| Field | Type | Notes |
|---|---|---|
| fromPersonId | UUID | Sender |
| toPersonId | UUID | Receiver; canonical ID ≠ sender's when the transfer is created or edited (rule 13). A transfer whose parties later resolve to one person through a merge stays valid and has no effect (§4.1) |
| amountMinor, currency | Long, String | What actually moved; amount > 0 |
| effectAmountMinor, effectCurrency | Long, String | Effect on balances; equal to the original unless converted |
| fxSnapshotId | UUID? | Set when effect currency ≠ currency |
| kind | Enum | `LOAN`, `REPAYMENT`; display and filtering only, same math |
| method | Enum | `BANK`, `CASH`, `CARD`, `OTHER` |
| accountLabel | String? | Free text; UI autocompletes from earlier labels |
| groupId | UUID? | If set, the transfer counts toward that group's nets (§4.3) |
| occurredOn | LocalDate | |
| note | String? | |
| + sync columns | | |

**Expense**: something one or more people paid on behalf of one or more people.

| Field | Type | Notes |
|---|---|---|
| groupId | UUID? | Null = ad-hoc (only me and one other person, §3.4) |
| payerSplit | Enum | `EQUAL` or `EXACT`: how the payer rows divide the total (§4.4). A single payer is one payer row with `EQUAL` |
| recurringId | UUID? | The RecurringExpense this expense was posted from (§5.7) |
| recurringOn | LocalDate? | The occurrence date it fills. Set exactly when `recurringId` is set; never changed by an edit |
| description | String | |
| category | Enum? | Fixed list: `GROCERIES`, `DINING`, `TRANSPORT`, `ACCOMMODATION`, `ENTERTAINMENT`, `UTILITIES`, `RENT`, `GENERAL`, `OTHER` |
| totalMinor, currency | Long, String | As paid; total > 0 |
| effectTotalMinor, effectCurrency | Long, String | Converted to the group currency in a CONVERT group with a foreign currency; otherwise equal to the original |
| fxSnapshotId | UUID? | Set when converted |
| splitType | Enum | `EQUAL`, `PERCENT`, `SHARES`, `EXACT` |
| occurredOn | LocalDate | |
| note | String? | |
| + sync columns | | |

**ExpenseShare**: owned by its Expense. No sync columns or revisions of its own; it is versioned as part of the Expense snapshot. On edit, all shares of the expense are replaced. Their order is kept (stored as `position`).

| Field | Type | Notes |
|---|---|---|
| expenseId, personId | UUID | Composite key |
| shareMinor | Long | In the expense's `effectCurrency`; ≥ 0 |
| inputValue | Long? | Basis points (PERCENT), weight (SHARES), or minor units in the original currency (EXACT) |

**ExpensePayer**: who paid how much. Owned by its Expense exactly like its shares: no sync columns or revisions of its own, versioned in the Expense snapshot, replaced as a whole on edit, order kept in `position`. Every expense has at least one. Payers need not be participants.

| Field | Type | Notes |
|---|---|---|
| expenseId, personId | UUID | Composite key |
| paidMinor | Long | In the expense's `effectCurrency`; ≥ 0; the payers sum to the effect total |
| inputValue | Long? | `EXACT`: minor units in the original currency; `EQUAL`: null |

**RecurringExpense**: a schedule that turns into ordinary expenses (§5.7). It holds an expense template and a repeat pattern. It stores no rate and no effect amounts: those are worked out when an occurrence is posted.

| Field | Type | Notes |
|---|---|---|
| groupId | UUID? | As for an expense |
| description, category, note | | Copied into each posted expense |
| totalMinor, currency | Long, String | The template amount, as paid; total > 0 |
| payerSplit, splitType | Enum | As for an expense |
| unit | Enum | `WEEK`, `MONTH`, `YEAR` |
| interval | Int | Repeat every 1–99 units |
| startOn | LocalDate | The first occurrence |
| endOn | LocalDate? | No occurrence after it; ≥ `startOn` |
| handledThrough | LocalDate? | The last occurrence date that was posted or skipped; null when none was. Together with the schedule's non-deleted posted expenses it gives the next occurrence (§5.7). Import only moves it forward (§7.4) |
| autoPostFrom | LocalDate | Auto-post runs only while the next occurrence is on or after this date (§5.7). Every write to the schedule other than posting and skipping sets it to today when the schedule auto-posts afterwards (the cut-off rule, §5.7). Local state: exported, but not compared or taken on import |
| autoPost | Boolean | Post due occurrences by itself, or show them as due |
| + sync columns | | |

**RecurringParticipant**: the template's payers and participants. Owned by its RecurringExpense the way shares are owned by an expense (versioned in its snapshot, replaced as a whole, order in `position`).

| Field | Type | Notes |
|---|---|---|
| recurringId, role, personId | UUID, Enum, UUID | Composite key; `role` is `PAYER` or `SHARE` |
| inputValue | Long? | As for an `ExpensePayer` (`PAYER`) or an `ExpenseShare` (`SHARE`) |

**FxSnapshot**: immutable once written, never deleted.

| Field | Type | Notes |
|---|---|---|
| id | UUID | |
| baseCurrency, quoteCurrency | String | |
| rate | String | Decimal string; parsed with `BigDecimal` |
| rateDate | LocalDate | Date the rate applies to |
| source | Enum | `ECB_FRANKFURTER`, `USER_ENTERED`, `BANK_ACTUAL` |
| isStale | Boolean | True if a cached rate was used because the exact date was unavailable |
| fetchedAt | Instant | |
| paidMinor, chargedMinor | Long? | Set exactly when `source = BANK_ACTUAL`: the paid amount (in `baseCurrency`) and the amount actually charged (in `quoteCurrency`) that the rate was derived from (§4.5, rule 16) |

**ImportBatch**

| Field | Type | Notes |
|---|---|---|
| id | UUID | |
| source | Enum | `OWN_NORMALIZED_CSV` (more sources added with later importers) |
| fileName | String | |
| importedAt | Instant | |
| rowCount | Int | |
| undoneAt | Instant? | |

**EntryRevision**: the append-only audit log.

| Field | Type | Notes |
|---|---|---|
| id | UUID | |
| entityType | Enum | `PERSON`, `PAYMENT_HANDLE`, `GROUP`, `GROUP_MEMBER`, `TRANSFER`, `EXPENSE`, `RECURRING_EXPENSE` |
| entityId | UUID | |
| version | Int | Equals the entity's `version` after the write |
| action | Enum | `CREATED`, `UPDATED`, `DELETED`, `RESTORED` |
| authorId | UUID | The me identity until sync |
| deviceId | UUID | Installation that made the write |
| at | Instant | |
| reason | String? | e.g. "Reverted to v2", "Merged into Alex", "Imported from export.zip (source v3)" |
| importBatchId | UUID? | |
| snapshotJson | String | Full entity state after the write: the entity's Room row as JSON (an Expense includes its share and payer rows, a RecurringExpense its participant rows), plus a `schema` number |

Revisions are only ever inserted, through the journal, and loaded in append order. A full JSON restore is the only operation that removes revisions, because it replaces the whole database.

**Room schema.** Version 1 (shipped with M1) has people, payment handles, groups, memberships, transfers, expenses, shares, FX snapshots, revisions, import batches, `app_setting` and `fx_rate_cache`. **Version 2 (M2)** is the first migration (`Migration(1, 2)`, written by hand): it creates `expense_payer`, `recurring_expense` and `recurring_participant`; gives every existing expense one payer row (its old `payerPersonId`, `paidMinor` = its effect total, no input) and `payerSplit = EQUAL`; adds `recurringId` and `recurringOn` as nulls; and rebuilds `expense` without `payerPersonId`. No balance changes (§4.2). Both features ship together in one release, so there is one migration, not one per feature. Rows are written with `@Insert(onConflict = REPLACE)`, never `@Upsert`. There are no foreign keys (the validator's rule 15 checks references), so a replace has no cascade side effects. Schemas are exported to `app/schemas/` for future migration tests.

### 3.3 Settings

- **Ledger settings: Room table `app_setting(key, value)`.** Home currency (default `EUR`), backup encryption enabled (default **on**), and the write counter `ledgerSeq` (§5.1). Every journal entry rewrites them. Stored in the ledger database so that a backup restore swaps them atomically together with the ledger (§7.1). In memory they are part of the store's content.
- **Device-local settings: `noBackupFilesDir/device-settings.json`** (ADR-0011). App lock enabled, lock timeout, screen protection enabled, `lastManualBackupAt`. Read once at start; each change replaces the file atomically and then updates memory, both under `NonCancellable`, so a cancelled caller can't leave memory behind the file. An unreadable file counts as the defaults (app lock off): it is read at application start, where a crash would loop, and atomic writes mean only outside tampering can cause it; later writes still report their own I/O errors. Never leave the device: excluded from **manual backups** (the JSON files of §7.1) and, by its location, from **Android Auto Backup** (§9). Otherwise a restore onto a phone without a screen lock could enable an app lock the user cannot satisfy.
- **Device ID:** `noBackupFilesDir/device-id` (§3.1).
- **Rate cache:** Room table `fx_rate_cache(base, quote, rateDate, rate, fetchedAt)` in the live database. It is not ledger data: `FxService` writes it directly, never through the journal, and holds it in memory after loading it at start (§4.5).

### 3.4 Integrity rules

`LedgerValidator` (`ledger.rules`) checks these rules over canonical IDs. There are two kinds:

- **State invariants [S]** describe every committed state. They are checked against the **complete state a write would produce**, on every write path: create, update, delete, restore, archive, member add/remove, merge, unmerge, revert to version (§5.3), import (§7.4) and undo import (§5.4).
- **Edit rules [E]** guard changes made through the `api`. They apply only to the entities a write creates or changes, plus the operation's own preconditions, never to untouched historical entries. They run on create, update, delete, restore, archive, member add/remove, merge, unmerge and revert. **Import and undo import are checked against the state invariants only:** they reproduce or remove whole records rather than edit them, so an exported group with removed members and their historical entries imports as is.

**Business invariants constrain non-deleted rows only; structural invariants cover every row.** Rule 15 is structural: it holds for deleted rows too, so history and restore can always resolve them. Every other state invariant ignores soft-deleted rows until they are restored, and the restore is checked like any other write: the restored rows must satisfy the invariants again. So restoring an entry whose person or group is deleted fails with `DeletedReference` until that person or group is restored first.

The write ops (`ledger.ops`, §5.1) run the validator on every write path, in both flavors. A write that violates any applicable rule is rejected in full with a typed `LedgerError` (§8). Field checks run first, then the edit rules, then the state invariants.

**Field validity**

0. **Fields are valid.** Names, labels and descriptions are not blank (`InvalidField`); amounts satisfy `0 < x ≤ MAX_MINOR` (`InvalidAmount`, `AmountTooLarge`); payment-handle values are valid for their type (`InvalidIban`, `InvalidField`); an expense's stored shares are non-empty, name distinct people, are non-negative and sum to the effect total, and their stored inputs are valid for the split type (an `EQUAL` expense stores none); the same holds for its payer rows against `payerSplit` (`EQUAL` or `EXACT`) (`CorruptData`, reachable only through import, since ops compute shares and payers). A schedule has `1 ≤ interval ≤ 99` and `endOn ≥ startOn` (`InvalidField`), a template total within the amount limits, and payer and participant rows that resolve without error over that total (§4.4; the split errors, or `CorruptData` through import). `handledThrough` is any date (or null): it need not be an occurrence of the current pattern, since a pattern edit keeps it (§5.7). An expense has `recurringId` and `recurringOn` both set or both null (`CorruptData`). Checked on the changed rows of an edit, and on every non-deleted row for import, undo import and backup restore.

**Scope of entries**

1. [S] **Outside groups, exactly me and one other person.** An ad-hoc expense (no group) has exactly two distinct people across payer and share rows: me and one other person. So does a schedule without a group, across its participant rows. An untagged transfer has me as sender or receiver. Anything involving three or more people, or two people who are not me, must be in a group. → `NeedsGroup` (the UI offers to create a group from the selected people)

**Group membership**

2. [S] **Me is a member of every non-deleted group.** [E] Me is added automatically when a group is created and cannot be removed. → `CannotModifyMe` (for both kinds)
3. [S] **Members only.** Every person a non-deleted group entry references (payer rows, share rows, transfer parties) has a non-deleted membership in that group, active or removed, and so does every person a non-deleted group schedule references. → `NotGroupMember`
4. [S] **Removed members have a zero net.** A canonical person who is not an active member of a group has a group net of 0 there in every currency. So removing a member with a non-zero net fails, and so does any import or unmerge that would leave a removed member with one. → `MemberHasBalance`
5. [E] **Removed members freeze their entries.** Any create, update, delete or restore of a group entry that references a removed member is rejected. Their existing entries stay as they are and keep counting (their net is 0 by rule 4). Re-adding the member (clearing `removedAt`, recorded as a revision) unlocks those entries. → `MemberRemoved`

**Group currency settings**

6. [E] **`fxMode` is locked** once the group has any entry, including soft-deleted ones (they can be restored). → `GroupSettingsLocked`
7. [E] **In a CONVERT group, `defaultCurrency` is locked** under the same condition. In a KEEP group, `defaultCurrency` is only the default for new entries and stays editable.
8. [S] **CONVERT invariant:** every entry in a CONVERT group (expenses and group-tagged transfers) has `effectCurrency == group.defaultCurrency`, converted through an FxSnapshot when the paid currency differs. → `FxSnapshotMismatch`

**People**

9. [S] Exactly one person has `isMe`; it is never deleted or merged. [E] **Me** cannot be deleted, archived, merged, or used as a merge target. → `CannotModifyMe` (also for a merge involving me, rather than `InvalidMerge`)
10. [S] No non-deleted entry, schedule, membership or payment handle references a deleted person, and no non-deleted entry, schedule or membership references a deleted group. → `DeletedReference`. [E] Through the `api`, a person or group can be deleted only when no entry or schedule references it, soft-deleted ones included (those could be restored), and a person also only when no membership references them and no non-deleted person is merged into them (a live alias); otherwise archive. → `StillReferenced`. A group's own memberships and a person's own payment handles don't block deletion: they are deleted and restored together with their owner (§5.2). Import undo is not bound by this edit rule: it deletes the entries it created first, then the people and groups (§5.4).
11. [S] Every non-deleted person's `mergedIntoId` chain ends at a non-deleted canonical person (rule 15 rules out cycles). [E] **Merge** (§5.6): source and target must be distinct, non-deleted, non-me, and both canonical (neither merged into anyone). → `InvalidMerge`
12. [E] **Unmerge:** the person must be merged into someone. The resulting state must satisfy the state invariants (e.g. rule 4 for a removed membership of the split-off person). → the violated rule's error
13. [E] **Distinct transfer parties:** a created or updated transfer has different canonical sender and receiver. A transfer that became a self-transfer through a merge stays valid and has no effect (§4.1); deleting or restoring it stays allowed. → `SelfTransfer`

**FX and structure**

14. [S] **FX snapshot consistency:** an entry has an `fxSnapshotId` exactly when its effect currency differs from its paid currency, and that snapshot has `baseCurrency` = the paid currency and `quoteCurrency` = the effect currency. → `FxSnapshotMismatch`
15. [S, all rows] **Structure:** every ID reference (person, group, expense, schedule, FX snapshot, `mergedIntoId`) points to an existing row, deleted or not; the `mergedIntoId` graph over all people, deleted ones included, has no cycles; exactly one person has `isMe`. → `CorruptData` (only reachable through import, where it shows as invalid rows)
16. [S] **Bank-actual snapshots fix both amounts:** an entry that references a `BANK_ACTUAL` snapshot has a paid amount equal to the snapshot's `paidMinor` and an effect amount (`effectAmountMinor`, or `effectTotalMinor` for an expense) equal to its `chargedMinor`. → `FxSnapshotMismatch`
17. [S] **Effects match their conversion:** an entry without an `fxSnapshotId` has an effect amount and currency equal to its paid amount and currency. An entry that references a rate-based snapshot (`ECB_FRANKFURTER`, `USER_ENTERED`) has exactly the effect amount that the §4.5 conversion of its paid amount at that snapshot's rate gives. (`BANK_ACTUAL` snapshots are covered by rule 16.) The balance engine trusts stored effects, so this rule is what keeps them honest. For example, an imported 1 000 SEK transfer with a new `USER_ENTERED` snapshot at 0.09 EUR/SEK must have a €90 effect; €100 is invalid. → `FxSnapshotMismatch`

**Recurring expenses**

18. [S] **One expense per occurrence.** No two non-deleted expenses have the same `recurringId` and `recurringOn`. Ops never produce two (posting moves `handledThrough` past the date in the same write, §5.7), so this guards import. → `CorruptData`
19. [E] **Posting follows the schedule.** Occurrences are posted or skipped only while their schedule is not deleted, strictly in order: only the schedule's next occurrence can be posted (also before its date, e.g. rent paid early), and a skip covers the occurrences from the next one up to a given occurrence date. Neither goes past `endOn`. A posted expense keeps its `recurringId` and `recurringOn` through every edit, and a revert never changes them. → `OccurrenceNotDue(nextDue)` (`NotFound` for a deleted schedule)

**Canonical membership:** for rules 2–5, a canonical person is an active member of a group if any of the person records resolving to them has an active membership there. Group operations act on the canonical person: removing them sets `removedAt` on every active membership of their records in that group, and re-adding clears it on all of them (one revision each).

---

## 4. Ledger engine and calculations

### 4.1 Identity resolution (`IdentityResolver`)

- `canonical(id)` follows `mergedIntoId` until it reaches a person that is not merged. Aliases form a tree under each canonical person, and chains cannot loop, because a merge only ever attaches one canonical person to another (rule 11). The resolver builds the full map once per state.
- Every effect, share, membership and payment handle is attributed to the canonical person.
- The canonical person's display name and note are its own. Its payment handles are the union over all records that resolve to it; its default per type follows §3.2.
- Effects between two records of the same canonical person (e.g. a group transfer between two merged duplicates) are dropped. Shares of two such records in one expense add up.

### 4.2 Effects

`LedgerEngine` maps every non-deleted entry, after canonical resolution, to `Effect(creditor, debtor, currency, amountMinor, groupId?)`:

- **Transfer** P→Q: `Effect(P, Q, effectCurrency, effectAmountMinor, groupId)`.
- **Expense** (ADR-0012): each canonical person's **contribution** is what they paid minus their share, `c(X) = Σ paidMinor(X) − Σ shareMinor(X)` over the payer and share rows that resolve to X. Contributions sum to 0. The people with `c > 0` are owed, those with `c < 0` owe, and they are matched like a settle plan (§4.6, without the amount cap): repeatedly the largest remaining creditor with the largest remaining debtor, ties to the lowest person UUID, giving `Effect(creditor, debtor, effectCurrency, min(credit, |debt|), groupId)`. Everything is exact integer arithmetic, so each person's effects add up to exactly their contribution.
  - **One payer gives exactly the old effects.** With a single payer P, P is the only creditor, so every other person X with a share gets `Effect(P, X, shareMinor(X))`: the same balances and flows as before multiple payers existed. The M2 migration therefore moves no balance.
  - Inside a group only the contributions matter (member nets, §4.3); the matching decides only how the informational flows read. Outside groups, with me and X, it gives exactly `net(me, X)` = what I paid − my share.
  - Example: a €100 dinner for Alice, Bob and Carol (EQUAL, 33.34 / 33.33 / 33.33, the extra cent to the lowest UUID, say Alice), Alice paid €60 and Bob €40. Contributions: Alice +26.66, Bob +6.67, Carol −33.33. Effects: Carol → Alice 26.66, Carol → Bob 6.67.

### 4.3 Balances

`LedgerEngine.compute(state): Balances` is a pure function, recomputed on each emission.

**Outside groups (pairwise)**
- `net(me, X, cur)` = Σ non-group effects me→X − Σ non-group effects X→me. This is "X owes me" / "I owe X".

**Inside a group (net positions)**
- `groupNet(M, cur)` = Σ group effects where M is creditor − Σ group effects where M is debtor. Nets sum to 0 per group and currency.
- A group's balance **is** its members' nets. There is no pairwise debt inside a group.
- **Flows** (informational only): gross "who paid for whom" amounts per pair within the group, shown in history and entry detail. Nobody has to clear them.

**Overview totals** (per currency)
- *Owed to me* = Σ positive `net(me, X)` + Σ over groups of `max(groupNet(me), 0)`.
- *I owe* = Σ negative `net(me, X)` + Σ over groups of `min(groupNet(me), 0)`.
- **Approximate home-currency totals** use the latest cached rates (the real flavor's rate cache, §4.5; the fake's fixed demo rates), are always labelled approximate, and are never stored. Without the rates they need (e.g. before the first rate refresh), they are absent (`null`). Views are recomputed when the cached rates change.

**Overview list** contains two kinds of rows:
- **People** with their non-group balance: every non-archived canonical person, plus archived people whose balance is non-zero ("archived, unsettled").
- **Groups** with my net: every non-archived group, plus archived groups where my net is non-zero ("archived, unsettled").
- Ordered by absolute approximate home-currency amount, descending.

**Person detail** = the non-group balance with X per currency, plus an **"In groups"** section listing, per group, the current settle-plan transfers between me and X (§4.6), clearly labelled as suggestions.

### 4.4 Splits (`SplitResolver`)

| Type | Input | Resolution |
|---|---|---|
| EQUAL | Participants | `total / n` each |
| PERCENT | Basis points per participant, must sum to 10 000 | `total × bp / 10 000` |
| SHARES | Integer weight 0–1 000 000 per participant (0 = listed but pays nothing this time; all zero → `ZeroWeights`) | `total × w / Σw` |
| EXACT | Minor units per participant, in the original currency | Must sum exactly to `totalMinor` |

- **Largest-remainder rounding** (EQUAL, PERCENT, SHARES), in exact integer arithmetic (`BigInteger`; no division with a precision or rounding mode). Each participant has a weight `w` (1 for EQUAL, basis points for PERCENT, the weight for SHARES), and `W = Σw`. The floor share is `total × w div W`, the remainder `total × w mod W`. The `total − Σfloor` leftover minor units (always fewer than the number of participants) go +1 each to the participants with the largest remainders. All remainders share the denominator `W`, so comparing them is exact, and non-terminating fractions such as 100/3 need no precision setting. Ties go to the lowest canonical person UUID (string order).
- **EXACT in a converted expense:** the per-person exact amounts in the original currency become the weights, and the converted total is distributed by the same procedure.
- **Stored shares are authoritative.** Once saved, an expense's share rows (person ID as stored, `shareMinor`, `inputValue`) are only replaced when the user changes an **input of the split**: the effect total (paid amount, paid currency or FX choice), the split type, the participants, or their inputs. Any other edit (note, description, category, date, group with the same effect currency) keeps the share rows exactly as stored, so it can never move a balance. Merges (§5.6) and import identity mapping (§7.4) therefore never change a split by themselves.
- **Recomputing after a merge.** A recomputed split is resolved over **canonical** participants. The edit draft shows each canonical person once: records that resolve to the same person are combined, with their inputs added for PERCENT, SHARES and EXACT (still subject to the limits above) and one slot for EQUAL. The new share rows store canonical IDs, so after an unmerge they stay with the target, like entries created after the merge. `previewSplit` shows the new shares next to the stored ones before saving. Example: I paid €90, split EQUAL over Alex-A, Alex-B and Bob, and the two Alex records were then merged. Stored shares give Alex €60 and Bob €30. A note-only edit keeps that. Changing the total to €120 recomputes it as Alex €60 and Bob €60, shown in the preview first.
- **Errors:** `NoParticipants`, `PercentNotHundred(sumBp)`, `ExactRemaining(diffMinor)`, `ZeroWeights`, `NegativeInput`, `MissingInput` (a participant without an input), `WeightOutOfRange` (a SHARES weight above 1 000 000), `DuplicateParticipant` (also when a draft names the same person record twice; distinct records of one merged person are combined, not duplicates). Inputs are checked for negatives per record before records of one canonical person are combined; an overflow while combining is `AmountTooLarge`.
- Splitting happens over `effectTotalMinor`, so `Σ shareMinor == effectTotalMinor` always holds.

**Payers** are resolved by the same `SplitResolver`, over the same effect total, with `payerSplit` as the split type: `EQUAL` (the total divided among the payers, largest remainder, ties to the lowest UUID) or `EXACT` (each payer's amount in the original currency, greater than 0 (`InvalidAmount` otherwise: a payer who paid nothing is not a payer), summing exactly to `totalMinor`; in a converted expense those amounts are the weights for distributing the converted total, as for EXACT shares). So `Σ paidMinor == effectTotalMinor` always holds. An `EQUAL` payer gets 0 only when the total has fewer minor units than there are payers (€0.01 between two), which is allowed. Payers work outside groups too: with me and X, "I paid €30, X paid €70" is one ad-hoc expense (rule 1).

- **Stored payer rows are authoritative** like share rows: they are replaced only when an input of the payers changes (the effect total, the paid currency or FX choice, `payerSplit`, the payers, or their amounts). Merged records are combined in the edit draft the same way (amounts added for `EXACT`, one slot for `EQUAL`).
- **Errors** carry their side, so the UI can tell which list is wrong: `NoPayers`, `PaidRemaining(diffMinor)` (EXACT payer amounts that don't add up to the total), `DuplicatePayer(personId)`; `NegativeInput`, `MissingInput` and `AmountTooLarge` as for shares.
- The draft gives payers as a `payerSplit` plus a list of `SplitInput(personId, value)`, the shape it already uses for participants (§6.2).

### 4.5 FX (`FxService`, `FxConverter`)

Rate lookup (`FxApi.quote(date, from, to)`, `FxService.lookup`):

1. A cached rate for exactly that date.
2. Frankfurter's historical endpoint (`https://api.frankfurter.dev/v1/{date}?base=EUR&symbols=…`).
3. The newest cached date that can answer the pair, with the snapshot flagged `isStale = true`.
4. No rate available, or a currency outside the ECB set: `RateRequired(from, to)`, and the user enters a rate manually (`USER_ENTERED`, through `FxApi.userRate`).

- **Always EUR from Frankfurter.** The app asks only for the EUR rates the ECB publishes and inverts or crosses other pairs locally through EUR (`RateTable`, `DECIMAL128`), so the cache has one format and the precision is ours (Frankfurter rounds non-EUR cross rates to 5 significant digits). Steps 1 and 3 answer a pair directly, inverted or crossed.
- **The answered date.** For a weekend, a holiday or a date before that day's publication, Frankfurter answers with the previous working day. The quote carries the date Frankfurter answered for, and the rate is cached under that date. Step 1 matches exact dates only, so such a date asks Frankfurter again while online.
- **Unavailable means any failure:** no network, a timeout (5 s connect, 10 s request), a status other than 200 (an unknown currency or a future date gives 404), or an unreadable answer. Invalid fetched rates are dropped. The chain then falls through to step 3.
- **Rate semantics:** `rate` is the number of major units of `quoteCurrency` per one major unit of `baseCurrency`. A snapshot used by an entry always has base = the paid currency and quote = the effect currency, so conversion is always a multiplication. An inverted or cross rate (e.g. from cached rates against the home currency) is computed once with `MathContext.DECIMAL128` and stored as the snapshot's rate string; conversion always uses the stored string, so it is reproducible.
- **Valid rates:** a rate parses as `BigDecimal`, is > 0, has at most 34 significant digits and a scale within ±64; otherwise `InvalidRate`. The scale bound stops an exponent-notation rate such as `1E999999999` from making a conversion build a number with a billion digits. This applies to fetched, cached, imported, restored and user-entered rates alike.
- **FX choice on a draft.** Entry drafts carry an explicit `FxChoice`: `Rate(quote)` (a looked-up or user-entered quote from `FxApi`), `BankActual(charged)` or `KeepExisting`. When a conversion is required and the draft has no usable choice, the save returns `RateRequired(from, to)`. A `Rate` choice whose source is `BANK_ACTUAL` is rejected with `InvalidRate`; bank-actual goes through `BankActual`, and its snapshot's `rateDate` is the entry's `occurredOn`.
- **Conversion:** `effectMinor = HALF_EVEN(amountMinor × rate × 10^(fd(quote) − fd(base)))`, where `fd` is the currency's minor-unit digits. Everything before that single rounding is exact `BigDecimal` arithmetic (a multiplication and a power-of-ten scale shift). Example: 100 JPY (fd 0) at 0.006 EUR/JPY → 100 × 0.006 × 10² = 60 EUR minor units (0.60 EUR). A result above `MAX_MINOR` → `AmountTooLarge`. In expenses the converted total is split afterwards (§4.4).
- **Zero results:** a positive amount that converts to 0 minor units (e.g. 1 IDR at 0.000055 EUR/IDR) is rejected with `ConvertedToZero`; the user can enter the amount actually charged instead.
- **Bank actual override:** the user enters the amount actually charged (e.g. "87.65 EUR"). That amount **is** the effect amount; nothing is recomputed from the rate. The snapshot stores the derived rate (`charged / paid` in major units, `DECIMAL128`) with `source = BANK_ACTUAL`, for display only, together with the paid and charged amounts it came from (`paidMinor`, `chargedMinor`; rule 16).
- **Required effect currency**, computed from the draft on every save (create or edit), in this order:
  1. In a CONVERT group: the group's `defaultCurrency` (rule 8).
  2. Otherwise, for a transfer whose draft sets `settlesCurrency` (an explicit cross-currency settlement, §4.6): that currency.
  3. Otherwise: the paid currency.

  An edit draft of a transfer outside a CONVERT group loads `settlesCurrency` from the stored row (its effect currency, when that differs from the paid currency). So editing only the note of a €9 repayment that settled 100 SEK keeps its SEK effect and its snapshot. Transfer drafts in a CONVERT group load it empty, and it is ignored there.
- **Editing:** on every save, the required effect currency is recomputed from the (possibly changed) group, the group's `fxMode` and `defaultCurrency`, the draft's `settlesCurrency` and the paid currency. Then:
  - **No conversion needed any more** (effect currency = paid currency; e.g. moved to a KEEP group or out of a group, or `settlesCurrency` cleared or set to the paid currency): the snapshot is dropped and the effect equals the original.
  - **Same pair** (the existing snapshot's base and quote still match the paid and effect currency): the snapshot is kept unless the date or amount changed. If they changed, a fresh rate is proposed, and the user may keep the old snapshot instead: for a rate-based snapshot (`ECB_FRANKFURTER`, `USER_ENTERED`), the effect is then recomputed from its rate.
  - **Bank actual** (`BANK_ACTUAL`): the charged amount is a fact about this one payment, so it is never recomputed or scaled. Edits that leave the paid amount unchanged (note, date, description, shares, group with the same effect currency) keep the snapshot and the effect. Changing the paid amount needs a new FX choice: a new charged amount (a new `BANK_ACTUAL` snapshot), or a rate from the lookup chain. Keeping the old snapshot is rejected with `FxSnapshotMismatch` (rule 16). Example: 1 000 SEK charged as €87.65, edited to 2 000 SEK. The effect neither stays €87.65 nor becomes €175.30; the user enters what was actually charged or takes a rate.
  - **Different pair** (paid currency changed, `settlesCurrency` changed, or the entry moved to a group with another effect currency): the old snapshot can't be kept; a new rate is required (lookup chain above, or bank actual).
  - Rule 14 enforces this on every write, so an entry can never keep a snapshot for the wrong pair.
- **Rate cache.** Cached rates are EUR-based (`base = EUR`) and live in `fx_rate_cache` (§3.3). `FxService` loads them at start, before the ledger is published, and again after a restore swaps databases. A rate fetched while a restore is swapping databases stays in memory but is not saved. Historical rates are cached forever.
- **Refresh:** unique periodic WorkManager work `fx-rate-refresh` (`real` flavor only): daily, network required, exponential backoff from 30 minutes, policy `KEEP`; its first run is due at once. It asks for the latest rates of the home currency and the currencies in use: the paid and effect currencies of all entries, deleted ones included, and the groups' default currencies. Each non-EUR currency is requested on its own, so an unsupported currency can't block the others. A refresh succeeds when at least one requested rate is usable, or when there is nothing to ask for (only EUR). The worker reaches the backend through a Hilt entry point.
- **Where conversion applies:** in CONVERT groups, and for transfers whose draft sets `settlesCurrency` (explicit cross-currency settlements outside CONVERT groups, §4.6). Everything else in KEEP groups and outside groups keeps `effectCurrency = currency`, so balances stay per currency.

### 4.6 Settle up (`SettlePlanner`)

**Group settle plan** (per group, per currency; computed on demand, never stored):

1. Take the members with non-zero `groupNet`.
2. Greedy: repeatedly match the largest creditor with the largest debtor (ties go to the lowest person UUID) and emit a transfer of `min(credit, |debt|)` from debtor to creditor.
3. The result settles the group with at most n−1 debtor–creditor pairs per currency.
4. **Amount cap:** a pair whose amount exceeds `MAX_MINOR` (possible, since nets can sum many capped entries) is emitted as several transfers of at most `MAX_MINOR` each, in the same direction. When every net is within `MAX_MINOR`, this never happens and the plan has at most n−1 transfers.

- From a plan item the UI gets a pre-filled `REPAYMENT` draft tagged with the group, dated today in the device's current time zone (as are person settle-up drafts; stored instants stay UTC). The amount is editable (partial settlement), and the paid currency is editable (cross-currency settlement through an FxSnapshot). In CONVERT groups the effect stays in the group currency (rule 8); in KEEP groups the draft sets `settlesCurrency` to the plan item's currency, so the effect lands in the currency of the net being settled (§4.5).
- Plan items that don't involve me can be recorded too: I am the group's bookkeeper until sync exists.

**Person settle-up** (outside groups): a pre-filled untagged `REPAYMENT` draft for `net(me, X)` per non-zero currency, in the direction that reduces the debt (split into several drafts of at most `MAX_MINOR` if the balance is larger). Cross-currency is allowed through an FxSnapshot: the draft sets `settlesCurrency` to the debt's currency B, the transfer moves currency A, and its effect is in B (§4.5). A transfer recorded by hand can set `settlesCurrency` the same way.

### 4.7 Invariants (property-tested)

- `Σ shares == effectTotalMinor` and `Σ paid == effectTotalMinor` for every expense.
- An expense's effects give every canonical person a net of exactly `paid − share`, and an expense with one payer gives the same effects as the single-payer rule did before M2.
- `net(A, B, c) == −net(B, A, c)` for non-group pairs.
- Group member nets sum to 0 per group and currency.
- Recording every transfer of a settle plan brings all nets of that group and currency to 0, and every plan transfer is a valid amount (0 < amount ≤ `MAX_MINOR`). A plan has at most n−1 debtor–creditor pairs, and at most n−1 transfers when every net is within `MAX_MINOR`.
- Merge followed by unmerge of the same person, with no other writes in between, restores identical balances, memberships and canonical IDs, also when either person already had aliases of its own.
- Replaying `EntryRevision` (latest snapshot per entity) reproduces the current entity tables exactly.

---

## 5. Write path, revisions and undo

### 5.1 Write path (`ledger.ops` + `LedgerStore`)

Every write is an op in `ledger.ops` run by `LedgerStore` under its write lock (ADR-0008, ADR-0010). The op reads the current state and returns a `WritePlan`: the changed rows with their new sync columns, and one `EntryRevision` per changed entity. The validator checks the plan's resulting state before anything is persisted. The store then appends the plan to its `LedgerJournal` (in the real flavor one Room transaction, `useWriterConnection { immediateTransaction { } }`, writing the rows, revisions, import batches and ledger settings) and publishes the new state. A failed append leaves the store unchanged.

| Operation | Row | Revision action |
|---|---|---|
| `create(entity)` | Insert, `version = 1` | `CREATED` |
| `update(entity, token)` | `version + 1`, `updatedAt = now` | `UPDATED` |
| `delete(id, token)` | Set `deletedAt`, `version + 1` | `DELETED` |
| `restore(id, token)` | Clear `deletedAt`, `version + 1` | `RESTORED` |

- **No-op writes.** Deleting something already deleted, restoring something not deleted, adding an active member and removing a removed one return an empty plan: success, nothing written, no revision.
- **Ledger generation.** A UUID stored in the database pointer `db-state.json` (§7.1), not in any database. First launch assigns it, and every pointer commit (each restore and each undo of a restore) replaces it with a fresh one. So every switch of the live database gives a new generation, including an undo back to an earlier database and a second restore of the same backup.
- **Edit tokens and draft basis.** Every read model of an editable entity carries an opaque `EditToken` = (ledger generation, entity version), and every update, delete, restore, archive, merge and revert takes one. Every other `PeopleApi`, `GroupsApi` and `LedgerApi` write (creates, saves of settle-up drafts, member add, remove and re-add) carries a `basis`: the generation its draft was prepared against (§6.2). A token or basis from another generation → `LedgerReplaced`; a version mismatch → `StaleVersion`. Either way nothing is written.
- **Write sequence.** `app_setting` holds `ledgerSeq`, a counter that every ledger write increments: a commit with rows or import batches. A settings-only change does not. A restore brings the restored database's own counter; the fresh generation, not `ledgerSeq`, invalidates everything prepared before it. Work that depends on more than one row's state, such as an import preview with its cascades (§7.5), records the `ledgerSeq` it was built at, since per-row versions cannot detect a row that was added in the meantime.
- Every operation runs `LedgerValidator` (§3.4): the state invariants against the complete state the write would produce, and (except for import and undo import) the edit rules against the entities it changes.
- Writes for one logical action (an expense with its shares, a merge, a whole import, an import undo, setup with its home currency) are one journal entry, so one transaction.
- **Persisting and publishing can't be cancelled.** Room can commit a transaction and still throw `CancellationException` when it resumes a cancelled caller. So the new state is computed first, and the append and the publish run together under `NonCancellable`; otherwise the store would serve old versions over committed rows.
- Archive, merge and unmerge are `update`s with a descriptive reason. Archiving is `set…Archived(id, archived, token)`, so unarchiving is allowed.
- Imports use the same row and revision shapes with `importBatchId` and an import reason. Incoming version numbers are never written (§7.4).
- **Before setup** (no me yet), ledger writes other than setup return `NotFound("me")` and the ledger-data flows emit nothing. Settings and backup work before setup.

### 5.2 Deletion and archiving

- **Transfers and expenses:** soft delete with Undo (the UI shows it). Deleted entries remain viewable and restorable, subject to §3.4.
- **People and groups:** deletion only when unreferenced (rule 10); archiving is the normal path.
- **Owned rows cascade.** Deleting a group also deletes its non-deleted memberships (me's included), and deleting a person deletes their non-deleted payment handles, in the same transaction and with the **same `deletedAt`** as the owner (one revision per row). Restoring the owner restores, in one transaction, exactly the owned rows whose `deletedAt` equals the owner's, so the group and its memberships (or the person and their handles) come back together and the state invariants hold at once. Memberships and handles are not restored on their own through the `api`; `GroupsApi` and `PeopleApi` expose delete and restore for groups and people. **Every** deletion or restore of a group or person goes through this cascade, including those written by import (§7.4) and import undo (§5.4), so the shared `deletedAt` always identifies what was deleted together.
- **Archiving with an open balance is allowed.** The `api` returns the open balance so the UI can warn first. Archived people and groups keep counting in totals and stay in the overview with an "archived, unsettled" badge until their balance with me is 0 (§4.3).
- **Archiving holds schedules.** An archived group or person puts every schedule referencing it on hold. Every write that ends a hold (an unarchive, but also a merge or unmerge) resumes those schedules in the same write (§5.7).
- **Leaving a group = archiving it.** Me is never removed (rule 2).

### 5.3 History and revert to version

- Each entity's revisions, newest first: author, device, time, action, reason, and a field-level diff to the previous snapshot (`RevisionDiff`; shares, payers and schedule participants diffed per person).
- **Revert to version N** = `update()` with the business fields of snapshot N (identity and sync columns stay current), reason "Reverted to vN". History is only ever added to.
- **A historical snapshot must satisfy current rules.** The revert runs the full validator and field validation against today's state. If it fails (e.g. the old version references a since-removed member, or an old Group revision would change a locked currency or FX mode), nothing is written and `RestoreConflict(violations)` is returned, listing each violated rule. The UI then offers **Open as draft**: `draftFromVersion(entity, version)` returns an edit draft pre-filled with snapshot N (an entry, person, payment handle or group draft), saved with that entity's normal update. Memberships have no edit form (`InvalidField("entity")`). A revision whose stored snapshot is not of the requested row is treated as missing (`NotFound`), so a revert can only ever write the row it names.
- **A revert keeps the current identity and state columns:** `isMe`, `mergedIntoId`, a handle's `personId`, a membership's `groupId`, `personId` and `joinedAt`, an expense's `recurringId` and `recurringOn`, a schedule's `handledThrough` and `autoPostFrom`, and the deleted state. (Reverting a schedule changes its template and pattern, never which occurrences were handled. A schedule that auto-posts after the revert then gets a fresh `autoPostFrom` by the §5.7 cut-off rule.) Reverting a handle clears the other defaults of its type only when the revert makes it that type's default.
- **Where history is shown.** History is reached through the detail that owns it, because some rows have no detail of their own: `PersonDetail.history` also holds the revisions of the person's aliases and payment handles, and `GroupDetail.history` those of the group's memberships, deleted rows included. Each revision view names its row (`entity`, `subject`), so the UI can label it and pick the matching token for a revert.
- An old snapshot's `fxSnapshotId` stays valid, because FX snapshots are immutable and never deleted.

### 5.4 Import undo

Built in M1 for M1's import types (people, payment handles, transfers, ad-hoc expenses, FX snapshots); groups, memberships and schedules join with their import in M2. Undoing a batch is one journal entry (one transaction):

- Undo finds the import's revisions by batch ID. A row counts as unchanged since the import when its current version is still the last one the import wrote.
- Rows **created** by the batch are deleted (rows the import created already deleted stay as they are).
- Rows **updated** by the batch (conflicts resolved as "take incoming", §7.4, and schedules whose `handledThrough` the import moved forward) are reverted to their pre-import business fields, including their deleted state and a schedule's `handledThrough`, but only if they are unchanged since the import (a schedule the runner posted from since then counts as changed). Expenses the batch created are deleted by the undo, not through the `api` delete, so they don't raise `handledThrough` (§5.7): their occurrences are due again as before the import.
- **Order:** entries first; then schedules; then the memberships and payment handles whose deleted state is undone on their own; then groups and people.
- **Owned rows go back with their owner when they changed with it.** An owned row's create, delete or restore by the import is undone through its owner's §5.2 cascade when it happened **with** the owner, and on its own (before the owner) otherwise. Undo works this out from the data: an owned row changed with its owner when the batch created it under an owner the batch created, restored it with an owner the batch restored (same pre-import `deletedAt`), or deleted it with an owner the batch deleted (same `deletedAt`). The import's revision reasons also say so ("… with <owner>"), for the history view. What undo reverts gets its pre-import business fields back, through the owner's cascade too: undoing an import that restored a person and their handle with new values deletes both with the old values.
  - **Owner created by the import:** deleted with the cascade, which takes the memberships or handles the import created under it.
  - **Owner restored by the import:** deleted again with the cascade, with its own pre-import `deletedAt` (not the undo time). The cascade takes the owned rows the import restored with it ("restored with <owner>") and those it created under it, so they share that `deletedAt` again and a later restore brings them back together. Owned rows the import restored by their own choice were deleted at another time before the import, so they are deleted again on their own first, each with its own pre-import `deletedAt`: they stay apart from the owner's cascade exactly as before the import, and restoring the owner later leaves them deleted. The same holds for any row whose deletion an undo puts back: it gets its pre-import `deletedAt`; only rows the import created are deleted with the undo time. Example: a person and a handle were deleted together; the import restored both, and nothing changed afterwards. Undo deletes the person with the cascade, the handle goes with it, and restoring the person later restores both.
  - **Owner deleted by the import:** restored with the cascade. That brings back the owned rows its deletion took, but not the rows the import created under it, which stay deleted. An owned row that was already deleted before the import and that the import moved into the cascade (§7.4) is not restored: it gets its own pre-import `deletedAt` back. A row the import created deleted under an owner it left deleted (so in that owner's cascade) is moved out of it, to the undo time, so a later restore of the owner does not bring back a row whose import was undone.
  - **Owned row under an owner whose deleted state the import did not change:** undone on its own (a created row is deleted; a deleted or restored row is reverted).
- **Undo protection wins over the cascade.** A cascade never changes a row that undo would skip, in either direction. Before deleting or restoring an owner, undo checks every owned row the cascade would touch. Each must be one this import created or changed through the owner, and unchanged since. If any is not (edited after the import, or added later), the owner is skipped too, with the reason "owns rows changed or added since the import", and its owned rows stay as they are. Example: an import created a person with a payment handle, and the handle was edited afterwards. Undo skips both the handle and the person, and lists both. FX snapshots the import created stay (they are immutable and harmless when unreferenced). A row that was changed since the import is skipped.
- **The whole undo is validated first, then single steps.** Undo applies every step that the protection above leaves (all of them when nothing the import wrote has changed) and checks the resulting state once against the state invariants (§3.4). If it holds, that is the undo, in one journal entry. Some steps are only valid together: an import created a group with me and a removed member A, an expense I paid €100 for A and A's €100 repayment to me. Deleting the expense alone leaves A at +€100, deleting the repayment alone at −€100 (rule 4), and neither A's membership nor the group can go while the entries are live; the whole set is valid. When nothing was written since the import, the whole set gives back the pre-import state (plus the kept FX snapshots and the created rows as deleted rows), which was valid, so an unchanged import always undoes completely. Only when the whole set breaks an invariant does undo go step by step, in the order above, each step checked against the state so far:
  - A step that would break an invariant waits and is tried again after the other steps, since a later step can make it valid (a transfer the import moved from X to Y, with X's deletion, undoes fully once X is back).
  - When a whole round applies nothing, the waiting steps that are tied together are tried as one: entry steps in the same group that reference a common member who is removed there (joined transitively), because rule 4 judges a removed member's entries only together. Then the rounds go on.
  - Only when that applies nothing either are the remaining steps skipped with "would break rule N" (e.g. deleting an imported person that a kept entry still references). The rest of the undo proceeds.
- **Undo is best-effort.** The result lists every skipped row with its reason. Balances return exactly to their pre-import values only when nothing was skipped; the UI says so when something was.
- Everything is recorded with reason "Undo import <file>"; undo writes are not tagged with a batch. `ImportBatch.undoneAt` is set. Undoing an undone batch does nothing.

### 5.5 Snapshot schema evolution

Each `snapshotJson` carries `schema: Int` (`SnapshotCodec.CURRENT_SCHEMA`, 1 in M1, 2 from M2). Because rows double as snapshot and backup JSON, a column change bumps the schema and adds an upcaster in `data` in the same commit as the Room migration; the upcaster converts old snapshots when they are read. Stored revisions are never rewritten.

- **Schema 1 → 2 (M2):** an Expense snapshot's `payerPersonId` becomes one payer row (that person, `paidMinor` = the snapshot's effect total, no input), `payerSplit` becomes `EQUAL`, and `recurringId` / `recurringOn` become null. So history, diffs and revert to a pre-M2 version keep working, and such a revert gives the expense one payer again. Other entity snapshots are unchanged.

### 5.6 Person merge and unmerge

Used now for duplicate contacts ("I created Alex twice"), and in phase 5 for linking a local contact to a real account or claiming a placeholder (§13).

- **`previewMerge(source, target)`:** the combined non-group balance, the combined group nets, the entries between the two that will stop having an effect (non-group debts, and group entries whose parties become one person), and the payment-handle defaults that will apply. Nothing is written.
- **`merge(source, target)`:** both must be canonical (rule 11). Sets `source.mergedIntoId = target` (revision reason "Merged into <target>"). **That is the only person row written.** The only other rows a merge can write are resumes: when an archived source is merged into an active target, the schedules that were on hold only through the source come off hold, and §5.7 writes each of them in the same journal entry. The source's own aliases keep pointing at the source and reach the target through resolution (§4.1).
- **History is never rewritten.** Entries and snapshots keep referencing the source ID; `IdentityResolver` attributes them to the target.
- **Collisions resolve at read time, with no extra writes:** memberships in the same group combine (canonical membership, §3.4); shares, and payer amounts, of both records in one expense add up (and stay stored per record until an input of the split or the payers changes, §4.4); a schedule's participant rows keep their stored IDs and resolve when an occurrence is posted (§5.7); entries between the two records, including group transfers that become self-transfers, stay stored and have no effect (§4.1); payment-handle defaults follow §3.2. Merge cannot break a state invariant, so it never fails on collisions.
- **`unmerge(person)`:** clears that person's `mergedIntoId` (revision reason "Unmerged from <target>"), after validation (rule 12), together with the resumes of schedules it takes off hold (an active person split off an archived one, §5.7). The person splits off together with its own aliases. Because merge wrote no other person, membership or entry (a resume changes only a schedule's version and `autoPostFrom`), unmerging right after a merge restores the previous balances, memberships and canonical IDs exactly (§4.7), and a schedule the merge resumed is on hold again. Entries created after the merge reference the target directly, so they stay with the target; a default handle set on the canonical person after the merge stays as set.
- In the MVP, me is never part of a merge (rule 9).

### 5.7 Recurring expenses

A `RecurringExpense` (§3.2, ADR-0013) is an expense template plus a repeat pattern. Its occurrences become **ordinary expenses**: once posted they are edited, deleted, imported and exported like any other, and only `recurringId` / `recurringOn` remember where they came from.

- **Occurrence dates.** Occurrence *n* (n = 0, 1, 2, …) is `startOn` plus `n × interval` units (`plusWeeks`, `plusMonths`, `plusYears`), always counted from `startOn`, never from the previous occurrence. A month end clamps without drifting: a schedule starting on 31 January gives 28 (or 29) February, 31 March, 30 April. Occurrences after `endOn` don't exist.
- **Handled through.** A schedule's **handled date** is the latest of `handledThrough` and the `recurringOn` of every **non-deleted** expense posted from it. Ops keep `handledThrough` at or ahead of its posted expenses (posting sets it; deleting a posted expense through the `api` raises it, below); the live expenses count too so that no import can make the schedule post a date that already has an expense, and an expense that import undo removes stops counting, so its date is due again exactly as before the import.
- **Next and due.** The **next occurrence** is the first occurrence of the **current** pattern after the handled date (or the first one, when nothing was handled yet); none once `endOn` has passed it. **Due occurrences** are the occurrences from the next one up to today, in the device's current time zone. A schedule whose `startOn` lies in the past therefore starts with its past occurrences due (deliberate backfill, e.g. "rent since January").
- **On hold.** A schedule is on hold while its group is archived or the canonical person (§4.1) of any person it references (payer or participant) is archived. A held schedule is not auto-posted (the runner checks again inside the posting write, since a hold can start while it waits for a rate), has no due occurrences in the due list and gets no notifications; its dates keep accumulating silently. Read models carry the hold reason, and `observeSchedules` filters on it, so held schedules are listed apart from the active ones (the intent is a separate screen; layout is the UI contributor's call).
  - **Every hold end is a write to the schedule.** Holds change only through writes, but not only through writes to the schedule: an unarchive (through `set…Archived`, a revert, an import or an import undo), a merge of an archived person into an active one, an unmerge that splits an active person off an archived one (§5.6), or an edit of the schedule so it no longer references anything archived. So the op layer compares holds before and after every write, on every path: each non-deleted schedule that the write takes off hold is written in the same journal entry as a **resume** (one `UPDATED` revision, reason "Resumed: <cause>", e.g. "Resumed: Alex unarchived" or "Resumed: Alex-A merged into Alex-B"), unless the write already changes that schedule. Either way the cut-off rule below applies, so the dates that passed during the hold show as due for confirmation and are never auto-posted.
  - A resume changes no template field, so no edit rule applies to it. An import writes it untagged: it is not something the import changed, so import undo neither reverts nor reports it (when the undo archives the person again, the hold simply returns).
  - A write that starts a hold (an archive, a merge of an active person into an archived one) needs no schedule write: holding back is always safe.
- **Posting** an occurrence is one write (one journal entry): it creates the expense with `recurringId` and `recurringOn` set, and moves the schedule's `handledThrough` to that date (an `UPDATED` revision, reason "Posted <date>"). Because both happen together and the schedule's version is checked, an occurrence can never be posted twice, whoever posts it (rule 18). The expense is created through the normal expense create: every rule applies, its revision says "Posted from <schedule description>", and it is built from the draft as posted, not from the template.
- **The occurrence draft** (`occurrenceDraft(scheduleId)`) is an expense draft for the schedule's next occurrence, built from the template, and `postOccurrence` names the occurrence it posts, so a draft can never land on a later occurrence than the one it was made for: date = the occurrence date, template payers and participants resolved to canonical people and combined as in §4.4 (merges since the schedule was made are respected), and no FX choice. The user may change anything in it (amount, date, payers, split, even the group) before posting; the template stays as it is. The returned draft carries the schedule's `EditToken`.
- **Skipping** moves `handledThrough` to the chosen occurrence date without creating an expense (`UPDATED`, reason "Skipped through <date>"). Skipped occurrences don't come back.
- **Auto-post.** `RecurringRunner` (in `store`, both flavors) posts the due occurrences of every non-deleted schedule with `autoPost`, oldest first, through the same op. It runs off the main thread, one schedule at a time under the store's normal write lock, and never blocks the UI. It runs:
  - **in the foreground:** after the start-up load, when the app comes to the foreground (`ProcessLifecycleOwner` `ON_START`, as for the app lock, §9), after a restore or undo-restore publishes a new generation, after an import or import undo commits, after any write that changes the due list (whatever wrote it: a schedule write, an archive or unarchive through `PeopleApi` / `GroupsApi`, a merge, a revert). The due list and the schedule read models are computed for today on every emission and also start over when a pass runs for a new day, so an open due list catches up with a pass that wrote nothing (e.g. only confirm-first schedules became due);
  - **in the background** (`real` flavor only): unique periodic WorkManager work `recurring-post`, every 6 hours, no network constraint, policy `KEEP`, enqueued at application start next to `fx-rate-refresh` (§4.5). The worker reaches the backend through a Hilt entry point, waits for the start-up load like every other caller, and then runs the runner and the notifier. Android may delay it (Doze, battery saver), so an occurrence is posted within hours of its date, not at midnight; opening the app always catches up. A restore holds the store's write lock, so the worker waits for it and then posts into the restored ledger.
  - **FX:** when the occurrence needs a conversion (a CONVERT group with a foreign template currency), the runner looks up a rate for the occurrence date through the §4.5 chain, steps 1–3 (an exact cached rate, Frankfurter, or the newest cached rate flagged stale). Without one (`RateRequired`), the occurrence stays due.
  - **A backlog stops auto-post.** Posting stays strictly in order (rule 19), so the runner posts a schedule's due occurrences only while its **next** occurrence is on or after `autoPostFrom`. When the next occurrence is older (a backfill, dates that passed during a hold, or older dates when auto-post was switched on), the runner posts nothing for that schedule, including newer dates: the whole backlog waits in the due list, and its due notification says that older dates need confirmation before auto-posting continues. Once the user posts or skips through the backlog, the next occurrence is on or after `autoPostFrom` and auto-post resumes. Example: a schedule created today with three past dates and one due today posts nothing until the three are handled; then the runner posts today's. Held schedules (above) are skipped.
  - **The cut-off rule: any other write resets `autoPostFrom` to today.** A schedule *auto-posts* when it is not deleted, has `autoPost` on and is not on hold. Every write to a schedule except posting and skipping, after which the schedule auto-posts, sets its `autoPostFrom` to today in the same write, whether or not it auto-posted before. The rule lives in the op layer and covers every path alike: create, update (switching `autoPost` on, editing away an archived group or person, changing the pattern or template), revert (§5.3), restore, the resume written for every hold end (an unarchive, a merge, an unmerge; above), import (a new schedule, "take incoming", or a forward `handledThrough`) and import undo. Any write can expose older dates (a hold ending, a pattern moved earlier, an import rewinding nothing but changing the pattern), and this way every date before the write's day waits for confirmation, on every path, with no case analysis. Posting and skipping only move `handledThrough` forward, so they keep the cut-off; so does the raise of `handledThrough` when a posted expense is deleted (below), which moves it forward the same way. The runner normally runs right after start-up, so an edit made later that day rarely holds back anything but dates the user can see in the due list.
  - **Stale rates are accepted:** step 3's newest cached rate (flagged `isStale`) is good enough for an auto-post; the expense shows the stale flag and can be edited.
  - **Any failure** (a rule violation such as `MemberRemoved`, `RateRequired`, a lost race with a manual post) stops that schedule's run at that occurrence. It stays due, and the due list shows the reason (`autoPostError`, kept in memory only, cleared by the next successful run). The remaining schedules still run.
- **Notifications** (`real` flavor; `store.RecurringNotices` port, Android implementation in `data`, no-op in the fake). After every runner pass, foreground or background, the notifier brings the system notifications in line with the ledger:
  - **Due** (channel "Recurring expenses due", default importance): one notification per schedule that has due occurrences, bundled into one notification group when there are several, i.e. confirm-first schedules and auto-post schedules whose run stopped. It names the schedule, the oldest due date and amount, how many are due, and why it waits: the `autoPostError` reason, or, for an auto-post schedule, that older dates need confirmation before auto-posting continues. A schedule is notified again only when its oldest due occurrence changes (a new one became due, or a failure appeared), not on every pass: the last notified occurrence per schedule, with a digest of what its notification shows (so a change of description, amount, count or app-lock redaction, after an edit, an import or a restore, redraws it silently), is kept in `noBackupFilesDir/recurring-notified.json` (device-local, never backed up, replaced atomically; unreadable = empty, which at worst repeats a notification). The group's summary is redrawn only when the group changes (a schedule shown, changed or cancelled) and never alerts by itself, so unchanged schedules make no sound on later passes. When only one schedule is left due, the summary is removed; Android removes the group's notifications with it, so the remaining schedule's notification is drawn again, silently. While the permission is missing or the channel is blocked, nothing is recorded as shown, since Android may remove what was showing; the first pass that can draw again shows everything that is due. Accepted limit: if notifications are switched off and on again between two passes, no pass sees it, and a due notification Android removed meanwhile stays missing until its oldest due occurrence or failure changes (the due list in the app is unaffected).
  - **Posted** (channel "Recurring expenses posted", low importance, no sound): one summary per pass that auto-posted something, listing what was posted ("Rent €900.00, 1 Dec").
  - Posting or skipping in the app, deleting a schedule, ending it, or putting it on hold (an archive) cancels its due notification; a restore re-syncs all of them, and so does undoing it, even onto a fresh install whose ledger has no me yet (the due list is then empty, so every due notification goes).
  - **Switching app lock on or off redraws at once,** without a pass and without waiting for one in progress (which can sit on rate requests): every schedule's due notification is drawn again silently in the new form (redacted or detailed, below), and switching app lock on removes a posted summary that names what was posted. The same file records whether such a detailed posted summary may still be showing.
  - Tapping opens `MainActivity` with the extra `iou.open = "due"` (and the schedule ID for a single schedule); the UI navigates to the due list. Notifications use `VISIBILITY_PRIVATE`. **While app lock is on, their text names no people, amounts or descriptions** ("2 recurring expenses are due", "3 recurring expenses were posted"), so the lock screen and the notification shade reveal nothing the lock hides.
  - **Permission:** `POST_NOTIFICATIONS` is a runtime permission from Android 13 (API 33). The UI asks for it the first time a schedule is saved (and may offer it again from the schedule screen). Without it, nothing is shown, and posting works the same: the due list in the app is always the full picture.
- **Editing a schedule** changes only future occurrences; posted expenses stay as they were. `handledThrough` is kept, and the next occurrence is the first one of the new pattern after the handled date. A changed pattern skips nothing by itself: moving a monthly rent (started 1 January) from the 1st to the 5th, with 1 December handled, makes 5 December next. To continue on 5 January instead, set `startOn` to 5 January next year, or skip 5 December. The schedule's read model shows the next occurrence, so the edit screen can show it before saving. Setting `endOn` before the next occurrence ends the schedule.
- **Deleting a schedule** (soft delete, with restore) stops it and keeps its posted expenses, which keep their `recurringId`. Restoring it brings back its due occurrences after the handled date; for an auto-post schedule the cut-off rule sets `autoPostFrom` to that day, so they wait for confirmation. People and groups a schedule references can't be deleted (rule 10); archiving them puts it on hold (above).
- **Deleting a posted expense** through the `api` doesn't make its occurrence due again: the delete also raises the schedule's `handledThrough` to the expense's `recurringOn` when it is lower, in the same write (normally it already is that high; it can be lower after an import brought the expense without its schedule's progress). Restoring the expense brings it back. **Import undo is different:** it removes the expenses its batch created and reverts the schedule's `handledThrough` to its pre-import value (§5.4), so the occurrences are due again as they were before the import.
- **Merges** never rewrite a schedule's template: its participant rows keep their stored IDs and resolve at posting. A merge or unmerge writes a schedule only to resume it, when it takes the schedule off hold (above). Rule 10 keeps a referenced person from being deleted, so a template never names a deleted person.

---

## 6. Core ↔ UI contract

### 6.1 Ownership

- **UI contributor:** everything in `ui`: screens, ViewModels, navigation, theming, string resources, mapping `LedgerError` to messages, `BiometricPrompt` and other Activity-bound APIs, UI tests. **Screen layouts and flows are theirs to design;** this spec deliberately contains none.
- **Project owner:** everything behind `api`, plus the fake.

### 6.2 API shape

- Pure Kotlin interfaces in `api`, grouped by area. Reads are `Flow`s of ready-to-render models; writes are `suspend` functions returning `LedgerResult<T>` (`Ok(value)` / `Err(LedgerError)`).
- Synchronous helpers do no I/O (e.g. `ImportExportApi.applyChoices`, `FxApi.userRate`). `LedgerApi.previewSplit` is `suspend`: it needs the group's FX mode and, for edits, the stored shares, but does no file or network I/O.
- Files are passed as `DocumentRef` (a value class wrapping a SAF URI string), so `api` stays Android-free. The real flavor reads and writes documents through the `store.DocumentStore` port: SAF on the device (`ContentDocuments`, written with mode `wt`), plain files in tests. A document that can't be opened, read or written gives `FileAccessFailed`.
- **One save call per entry kind** with a `SaveMode`: `Create(basis)` or `Update(id, token)`. Edit forms start from `EntryDetail.editDraft`, which the core pre-fills as §4.4 and §4.5 require (canonical participants combined, `settlesCurrency` loaded, `FxChoice.KeepExisting` when the entry has a snapshot).
- **Aliases carry their own `EditToken`** (`PersonDetail.aliases`), because unmerge writes the alias's row (ADR-0009). Observing a person by an alias ID returns the canonical person's detail.
- **Payers (M2).** `ExpenseDraft.payer` becomes `payerSplit` + `payers: List<SplitInput>` (one entry with no value for a single payer). Entry read models gain `payers: List<PayerView(person, paid: Money, inputValue)>` on `ActivityItem` (per canonical person, `paid` in the effect currency, `inputValue` the EXACT amount in the paid currency); `paidBy` stays as the first payer for single-payer screens. `previewSplit` returns the resolved payers next to the shares, with the stored ones and a `payersRecomputed` flag when editing. Until the UI moves to `payers`, `ExpenseDraft` keeps a single-payer constructor in its old parameter order and a deprecated `payer` accessor. `FlowView` stays as it is. Expense read models carry `recurringId` / `recurringOn`, so the UI can mark posted occurrences and link to their schedule.
- **Notifications (M2).** The core posts and cancels the recurring-expense notifications (§5.7). The UI asks for `POST_NOTIFICATIONS` (Activity-bound, like `BiometricPrompt`) and handles the `iou.open` extra on `MainActivity`'s launch and new intents.
- **Deleted people and groups stay listable:** `observePeople` and `observeGroups` take `includeDeleted`, and the summaries carry `deleted`, so a deleted record can always be found again to view its history or restore it.
- **`HandleDraft.isDefault` means what `PaymentHandleView.isDefault` shows:** the canonical person's default of that type. An edit that leaves it as shown keeps the stored flags, including a merged alias's own default that only shows again after an unmerge; only switching it on moves the default and clears the others of that type (§3.2).

| Interface | Covers |
|---|---|
| `SetupApi` | First-launch setup (create me with display name, home currency); setup state |
| `PeopleApi` | Observe people (with `includeDeleted`) and person detail (non-group balance, "In groups" suggestions, aliases, history, payment handles); create / update / archive / delete / restore person; payment handle CRUD with IBAN validation; `previewMerge` / `merge` / `unmerge`; plain-text balance summary for sharing |
| `GroupsApi` | Observe groups (with `includeDeleted`) and group detail (members with nets, settle plan, expenses, flows, history); create (me auto-added) / update / archive / delete / restore; add, remove, re-add member |
| `LedgerApi` | Observe overview (totals, mixed people/group list) and activity feed with filters; save expense and transfer drafts; `previewSplit(draft)` (shares and payers); settle-up drafts from a plan item or a person; delete / restore; observe entry detail with history, diffs and an edit draft; `revertTo(entityId, version)`; `draftFromVersion(entity, version)` (§5.3) |
| `RecurringApi` (M2) | Observe schedules (with `includeDeleted` and an `onHold` filter; summaries carry the hold reason) and schedule detail (template, pattern, next occurrence, posted expenses, history); save a `RecurringDraft` (`Create(basis)` / `Update(id, token)`): the template fields of an expense draft without date and FX choice, plus `unit`, `interval`, `startOn`, `endOn`, `autoPost`; delete / restore; observe due occurrences (schedule, date, template amount, group, `autoPostError`); `occurrenceDraft(scheduleId)` (for the next occurrence); `postOccurrence(scheduleId, occurrence, draft, token)`; `skipOccurrences(scheduleId, through, token)`. Revert and "Open as draft" go through `LedgerApi.revertTo` / `draftFromVersion` as for other entities |
| `FxApi` | `quote` for a date and currency pair through the lookup chain (value, source, date, stale flag; `RateRequired` when none is available); `userRate` (validate a typed rate); `rateFromCharged` (derive a rate from "amount actually charged") |
| `BackupApi` | `observeLedgerGeneration()`; create a backup (encrypted or not); restore; undo last restore and whether one is available; observe the backup reminder (no manual backup for 30 days); observe and dismiss the "restore interrupted" notice (§7.1) |
| `ImportExportApi` | Export (shape, format, Excel-friendly flag, and a scope for reports); parse an import into a preview; confirm or decline a foreign identity; `applyChoices`; refresh a stale preview; commit with per-row conflict choices; `discardImport` for a preview the user leaves; observe import batches; undo an import |
| `SettingsApi` | Home currency; app-lock enabled and timeout; screen protection; backup encryption default; `observeLockRequired()` and `markUnlocked()` for the app-lock policy (§9) |

**Validation the core does for the UI.** Both flavors check the backup passphrase length (`InvalidField("passphrase")` under 8 characters, §9) and the export request (§7.2). Plan items and details carry their generation, so `settleUpFromPlan(item)` and `settleUpWithPerson(id, basis)` reject stale input with `LedgerReplaced`.

**Import previews** are immutable values. `applyChoices(preview, choices)` returns the preview with the forced cascade outcomes recomputed (§7.4); `commitImport` takes the preview and the choices. `applyChoices` is not a disposable calculation, though: the real flavor also stores the choices in the preview's import session, and `refreshPreview` rebuilds with the last choices stored there. So the UI should call it with the choices the user actually made, not to try out alternatives. (The fake returns the preview unchanged.) A parsed file stays in memory under its preview's ID only while the process lives: after an app restart, a preview gives `NotFound("preview")`. One import is open at a time: in the real flavor parsing a file ends earlier previews, `discardImport(preview)` ends one the user leaves, and a preview answered with `LedgerReplaced` is ended too; an ended preview gives `NotFound("preview")`.

**Ledger replacement.** `BackupApi.observeLedgerGeneration()` emits the current ledger generation (§5.1) and emits again after every restore or undo-restore commits. Work prepared against the old ledger is invalid after that, create drafts included: a settle-up draft for €100 prepared before a restore must not be saved into a ledger where only €20 is outstanding. So every write input carries the generation it was prepared against (§5.1): edits inside their `EditToken`, everything else as a required `basis` field. Drafts the `api` builds (settle-up drafts from a plan item or a person) come with `basis` already set; for a blank create form, the UI sets `basis` from `observeLedgerGeneration()` when it opens the form. Writes from another generation and commits of import previews parsed before the restore return `LedgerReplaced`, and all read `Flow`s re-emit from the new database. The UI should drop open drafts and previews and return to the overview when the generation changes (how it tells the user is the UI contributor's call).

`LedgerError` is a sealed hierarchy covering every error in §4.4 and §8, each carrying the data a good message needs (e.g. `ExactRemaining(diff)`, `MemberHasBalance(nets)`, `RestoreConflict(violations)`, `NeedsGroup(people)`).

### 6.3 Fake implementation

- Runs on the shared `LedgerStore` with no journal (§2.2), seeded with demo data: me, five people, two groups (one CONVERT EUR with a SEK expense, one KEEP), a merged duplicate, a few revisions, an archived unsettled person. From M2 also a group expense with two payers, a monthly confirm-first schedule with one occurrence due, and an auto-post schedule. The fake runs `RecurringRunner` in the foreground like the real flavor; it has no WorkManager and shows no notifications.
- Uses the same ops, rules, engine, views, settings and lock policy as the real flavor, so errors, balances, settle plans and app lock behave like the real thing. The fake locks on a cold start when app lock is on.
- **FX:** the shared `StoreFxApi` over a fixed table of demo rates against EUR (`FakeRates`); unknown currencies give `RateRequired`.
- **Backup and restore are simulated:** a restore brings back a copy of the *current* data under a fresh generation (so `LedgerReplaced` behaves as AC M1-15 describes), and undo swaps back with another fresh generation. Backups remember their passphrase in memory, so a wrong passphrase gives `WrongPassphraseOrDamagedFile`; an unknown backup gives `FileAccessFailed`. Its restores can't be interrupted, so the interrupted notice is always false.
- **Import is simulated with canned previews**, always built from the current ledger: a file name containing `foreign` needs identity confirmation, `corrupt` gives `CorruptFile`, `invalid` adds one invalid row; anything else gives two new rows, one conflict and five duplicates. The commit checks generation, `ledgerSeq`, identity and invalid rows like the real thing and records an `ImportBatch`, but doesn't change ledger data. Exports check the request with the shared `ExportRules` and write nothing.
- Delivered in Milestone 0, before any real persistence exists, and moved onto the shared store in M1.

### 6.4 Contract changes

`api` is shared ground. Either side proposes changes as small PRs that touch `api` and the fake together; both contributors review them. The contract tests (§11) run against both implementations, so a change that the real implementation can't satisfy fails CI. `docs/ui-contributor-guide.md` is the UI contributor's guide to the contract, and the project owner tells them about each change. M1's changes: the restore-interrupted notice in `BackupApi`, the errors `FileAccessFailed` and `RestoreFailed`, the passphrase rule, XLSX rejected until M2, previews lost on restart, the lock policy running in both flavors, and `FLAG_SECURE` set by the core. M2's changes: payers on expense drafts and read models (§6.2), the payer errors (§4.4), `RecurringApi` with `OccurrenceNotDue`, the notification permission and `iou.open` extra, and XLSX accepted. They touch the friend's expense form and every "paid by" display, so they are agreed with the UI contributor before the M2 core work starts.

---

## 7. Import and export

### 7.1 JSON backup and restore

- **Content:** the database's rows, table by table (`LedgerRows`, the same row shape as the tables, revision snapshots and the loader; §3.1): every ledger table including revisions, FX snapshots, import batches and `app_setting` (§3.3), plus the rate cache. The document is `{ "format": "iou-backup", "schemaVersion", "appVersion", "createdAt", "rows" }`; `schemaVersion` is the Room schema version (1 in M1, 2 from M2). Device-local settings and the device ID are not included.
- **Consistent capture:** a backup waits for the start-up load, then captures the store's ledger and cached rates together under the store lock, so a restore or undo can't produce a mixed snapshot. It reads memory, not the database. Serialization, encryption and the document write happen after the lock is released.
- **Encrypted format** (default; toggle in Settings): a 45-byte header = magic `IOUB` | version byte `1` | Argon2id memory in KiB, iterations and parallelism (4-byte big-endian each; default 64 MiB, 3, 1) | 16-byte salt | 12-byte nonce; body = AES-256-GCM over gzipped JSON. The header is GCM's associated data, so a changed header fails like a changed body (`WrongPassphraseOrDamagedFile`). A newer version byte gives `SchemaTooNew`. A truncated file, or Argon2 parameters above 256 MiB, 10 iterations or 4 lanes, give `CorruptFile` before any key is derived, so a crafted file can't make the app allocate gigabytes. Backups record their parameters, so lowering the default later keeps old backups readable. The passphrase (at least 8 characters, checked by the core) is entered for every backup and restore and never stored.
- **Unencrypted format:** plain JSON, with a warning when chosen. The magic decides which format a file is; an encrypted file restored without a passphrase gives `WrongPassphraseOrDamagedFile`.
- **Limits:** a backup file over 64 MiB, or JSON over 256 MiB after decompression, gives `LimitsExceeded`.
- **Backup reminder:** when `lastManualBackupAt` is older than 30 days (or no backup has ever been made), `BackupApi` reports a non-blocking reminder; both flavors share the rule (`BackupRules`). There are no scheduled automatic backups (they would need a stored passphrase).

**Database files.** All ledger files live in the app's database directory: the databases, the pointer, its temp file and the marker. Database files have generation names, `iou-<uuid>.db`; the `iou-` prefix in that directory is reserved for them, since recovery deletes unreferenced ones. A small pointer file, `db-state.json` (`{ "current": "iou-<a>.db", "previous": "iou-<b>.db" | null, "generation": "<uuid>" }`), names the live database and the pre-restore copy and holds the ledger generation (§5.1); Room opens the one named `current`, by absolute path. The pointer is only ever replaced atomically: write `db-state.json.tmp`, fsync, rename it over `db-state.json`, then fsync the directory where the platform allows it. Every replacement writes a fresh `generation`, so the switch of database and the change of generation are one atomic step. First launch creates the first database and the pointer. The guarantees below hold for process death; directory fsync is best effort, so durability of the pointer across a power loss is not guaranteed.

**Restore procedure** (replaces the whole ledger, after confirmation; all or nothing). Restore and undo first wait for the start-up load, then hold the store's write lock from the marker to the clean-up, so in-flight writes finish first and new ones wait for the whole restore. They run under `NonCancellable`, so a cancelled caller can't leave the database closed or the pointer switched.

1. **Decrypt, parse and validate** the entire file before touching the live database. Newer `schemaVersion` → `SchemaTooNew`; older → migrated: a version-1 backup's expense rows get their payer rows exactly as `Migration(1, 2)` gives them (§3.2), and its revision snapshots are read through the upcaster (§5.5). Every row is mapped (bad enums, dates or snapshots give `CorruptFile`); each cached rate needs a date, supported currencies and a valid rate; share rows whose expense is missing are rejected. Primary keys must be unique in every table (duplicates are rejected, never collapsed), and each revision's snapshot must be of the row and version its metadata names. Then the single validator runs over the whole state as for an import (§3.4: field rules on live rows plus every state invariant); a violation gives `CorruptFile("Backup breaks rule N: …")`. An empty, never-set-up ledger is a valid backup.
2. **Mark:** write `restore-marker.json` (`{ "op": "restore", "target": "iou-<new>.db" }`) atomically (through `restore-marker.json.tmp`). The marker only decides which message to show after a crash; correctness never depends on it.
3. **Build** `iou-<new>.db` from the backup's rows, then close it. Closing the last connection checkpoints the WAL, so the closed database is a single file again (a harmless `.lck` file may stay).
4. **Commit:** close the live database, then replace the pointer with `{ current: new, previous: old current, generation: fresh UUID }`. **This atomic rename is the commit point.** A failure while writing the pointer (e.g. full storage) abandons the restore like any earlier step. After the commit, the new database is opened and loaded, the rate cache reloaded, and only then is the new state published with the new generation (§6.2), so memory always equals disk. Writes that waited are then checked against the new generation, so their old tokens and bases fail with `LedgerReplaced`.
5. **Clean up:** delete the database file that was `previous` before the commit (only the latest pre-restore copy is kept), then delete the marker.
6. **On any failure before the commit point,** delete `iou-<new>.db`, the temp pointer and the marker, and reopen the live database. The result is `RestoreFailed`: "Restore failed, nothing changed".

**A failure after the commit point** (opening or loading the new database) does not roll anything back: a rollback would be a second pointer transition after the commit point. The database is closed and the exception propagates, the same end as process death after the commit point. Until the app restarts, the store still shows the earlier ledger but no write lands, backups and exports still read that earlier ledger, and restore and undo fail with `IllegalStateException` (`RESTART_NEEDED`) before touching anything. The next start opens the committed database and reports nothing.

**Undo last restore** writes a marker with `op = "undo"` and `target` = the current `previous`, then commits a pointer with `current` and `previous` swapped and a fresh `generation` (same close, commit and load as step 4). The restored database becomes the pre-restore copy, so an undo can itself be undone. Because the generation lives in the pointer, a token issued before the restore stays invalid after the undo, even though the entry it names is back at the same version. Without a pre-restore copy, undo gives `NotFound("restore")`; `observeUndoRestoreAvailable()` says whether one exists.

**Crash recovery:** on every app start, *before* Room opens a database, `RestoreRecovery`:

1. Reads the pointer and deletes every `iou-*.db` (with its `-wal`/`-shm`) and any `db-state.json.tmp` that the pointer does not name. Process death before the commit point leaves the old database live plus an unreferenced new file, which is deleted. After the commit point the new database is live, and at most a stale old copy is left to delete. No other state can exist, so nothing is rolled back or inferred from which files are present.
2. If a marker exists: when the pointer's `current` equals the marker's `target`, the operation had committed and nothing is reported; otherwise `BackupApi.observeRestoreInterrupted()` reports "The last restore was interrupted, nothing changed" until the UI calls `dismissRestoreInterrupted()`. Then the marker (and any `restore-marker.json.tmp`) is deleted. A malformed marker is dropped without a report, since the pointer alone decides what is live; an I/O error while reading it propagates (it can block start-up during a storage or permission failure).

- The real backend's start-up (recovery, opening and loading the database) runs on `Dispatchers.IO`; small metadata reads (pointer, marker, device ID, device settings) happen synchronously while it is constructed.
- Ledger settings live in the database (§3.3), so the commit restores them atomically; no second store can partially fail.
- The me identity comes from the backup (a restore on a new phone keeps the same identity). The device ID stays the installation's own.
- **Pre-restore copy:** the database named `previous` is an ordinary database file with the same protection as the live one: app-private storage with file-based encryption. It is not passphrase-encrypted (no passphrase is available). Only the latest copy is kept.

### 7.2 CSV export

- **Scope:** the **normalized shape is always the full ledger**: every table, every row including soft-deleted ones and the me person, so a re-import never meets a dangling reference (merge targets, members, FX snapshots). The **report shape** can be scoped to the full ledger, one group, or one person, each with an optional `occurredOn` date range. A person scope contains the entries in which that person (canonically) is a payer, has a share, or is a transfer party, shown in full with the other participants and the entry totals; it is a report for my own use.
- **Normalized shape:** `.zip` with one CSV per table in dependency order: `people` (including `merged_into_id`), `payment_handles`, `groups`, `group_members`, `fx_snapshots`, `transfers`, `recurring_expenses`, `recurring_participants`, `expenses`, `expense_shares`, `expense_payers`. `manifest.json` comes last: `format` (`iou-normalized-csv`), `schemaVersion` (1 in M1, 2 from M2), `appVersion`, `exportedAt`, `scope` (`full-ledger`), `textEscaping`, `delimiter`, `bom`, and the data-row count per table (`tables`), so the import knows how to read the CSVs and can check it read them all.
  - Columns are snake_case versions of the entity fields. Every entity table ends with the sync columns `version, created_at, updated_at, author_id, deleted_at`; `import_batch_id` is not exported (batches are local).
  - Money is `<name>_minor`, `<name>` (decimal string) and the currency column (`currency`, `effect_currency`). `expense_shares` has `expense_id, position, person_id, share_minor, share, input_value`; `share` is in the expense's effect currency. `expense_payers` has `expense_id, position, person_id, paid_minor, paid, input_value`, the same way. `expenses` has `payer_split`, `recurring_id` and `recurring_on` (schema 2) instead of `payer_person_id` (schema 1). `recurring_participants` has `recurring_id, role, position, person_id, input_value`.
  - Booleans are `true`/`false`, enums by name, and null is an empty cell.
- **Report shape:** one row per non-deleted entry, sorted by date, then ID: `date, type, description, group, paid_by, from, to, amount, currency, my_share, balance_effect_for_me, effect_currency, account, note`. `type` is `loan`, `repayment` or `expense`. `paid_by` is the payer's name, or for several payers each name with its amount (`Alice 60.00; Bob 40.00`, in the paid currency), taken from the stored payer rows (§4.4), never re-divided by person ID: an EXACT payer's input, an EQUAL payer's `paidMinor`, or, for a converted EQUAL expense, the paid total in equal parts with the extra minor units on the payers with the larger stored amounts. `my_share` and `balance_effect_for_me` are in the effect currency, which differs from the paid currency after a conversion; `balance_effect_for_me` is signed (positive means owed to me). Names are canonical. Not re-importable.
- **Format:** default UTF-8 without BOM, `,` delimiter, RFC 4180 quoting with CRLF record endings; "Excel-friendly" mode = UTF-8 with BOM and `;`. Dates as ISO 8601 (`occurredOn` as `2026-11-02`, instants in UTC with `Z`), `.` decimal separator. Excel on a comma-locale system still needs an explicit import (Data → From Text/CSV, semicolon) for Excel-friendly files (`docs/manual-test-checklist.md`); the export bytes stay as they are.
- **Formula-injection protection (reversible, `quote-prefix-v1`):** a string value starting with ASCII `=`, `+`, `-`, `@`, tab, CR, LF, full-width `＝`, `＋`, `－`, `＠`, **or `'`** gets one `'` prefixed. Escaping a leading `'` too makes the rule lossless: on import of own exports, a value starting with `'` has exactly one leading `'` removed. So `=SUM(1)` → `'=SUM(1)` → `=SUM(1)`, and `'quoted` → `''quoted` → `'quoted`. Escaped columns: every string column, including opaque IDs and references (primary IDs, merged-into and author IDs), names, notes, labels, payment values, holder names, BICs, descriptions and account labels. Numeric, date, enum and boolean columns stay typed. `manifest.json` records `"textEscaping": "quote-prefix-v1"`. The report shape uses the same rule.
- Schedules are not report rows (they are not entries); their posted expenses are.
- **Request rules** (both flavors, `ExportRules`): XLSX gives `InvalidField("format")` until M2; a scoped normalized export gives `InvalidField("scope")`; a date range that ends before it starts gives `InvalidField("to")`. The real export checks the scope before it opens the target, so a `NotFound` scope writes nothing; a target that can't be opened gives `FileAccessFailed`. Exports read the store's current snapshot.

### 7.3 XLSX export (FastExcel, M2)

- Not built in M1: an XLSX request gives `InvalidField("format")`. FastExcel-on-Android is validated with it in M2 (§14).
- Scopes as in §7.2: the report workbook takes a scope, the normalized workbook is always the full ledger.
- **Report workbook:** `Summary` sheet (non-group balances per person and my net per group, per currency), `Report` sheet (as the §7.2 report), and one sheet per group with member nets and its settle plan. Real date cells; amounts as numeric cells with currency-appropriate decimal formats.
- **Normalized workbook:** one sheet per table with integer `amount_minor` columns.
- The same formula-injection rule applies to string cells.

### 7.4 Import of own normalized CSV zip (merge)

Version numbers from the file are **never** used to decide which side wins. Two copies can diverge from a common ancestor, so a higher version does not mean a valid successor. Each incoming row is classified by UUID:

| Case | Classification | Action |
|---|---|---|
| UUID unknown locally | New | `create` at local version 1; revision `CREATED`, reason "Imported from <file> (source v<N>)". An incoming row that is deleted is created and then deleted in the same transaction (revisions `CREATED` and `DELETED`), so it can be restored later; groups and people are deleted with their cascade (see "Deleted owners" below) |
| UUID exists, business fields and deleted state identical | Duplicate | Skipped |
| UUID exists, business fields or deleted state differ | **Conflict** | Shown in the preview with a field diff; the deleted state shows as a field ("active → deleted", "deleted → active"). Per-row choice: **keep local** (default) or **take incoming**: an `update` on the local version for changed business fields and a `delete` or `restore` for a changed deleted state, each with its own revision, reason "Import conflict resolved from <file> (source v<N>)" |

- **Compared fields:** sync columns are ignored, except that `deletedAt` is compared as a deleted / not-deleted state (its timestamp is ignored), because it changes balances.
- **Deleted owners keep their cascade.** Local timestamps are never copied from the file, but the file's `deletedAt` values decide which rows were deleted together. When the import deletes a group or person (a new deleted row, or "take incoming" for "active → deleted"), it first deletes on their own the owned rows that the file shows deleted at a *different* time than the owner. Then it deletes the owner with the §5.2 cascade, which gives its remaining owned rows, including those the file shows deleted at the owner's own time, the owner's local `deletedAt`. The owned rows deleted on their own get a `deletedAt` 1 ns before the owner's, so the shared `deletedAt` still names exactly what went together. This holds for an owned row the import creates and for an existing one whose owner the import deletes, whether or not the owned row's own conflict is taken. It also holds for an existing owned row that is already deleted here, at another time: when the file shows it deleted together with the owner the import deletes, it gets the owner's new local `deletedAt` (even when the row is otherwise a duplicate, since `deletedAt` is never compared), so it joins the cascade; the preview lists it as "deleted with <owner>". Restoring the owner later brings back exactly the memberships or handles that were deleted with it in the source. When the import restores an owner ("deleted → active"), the §5.2 restore cascade runs.
- **An owned row's deleted state has a choice of its own only while its owner stays active and is not restored by this import.** Otherwise it follows the owner, through the cascade. The preview shows the forced outcome on the owned row instead of offering a choice that the commit would override:
  - **The owner ends deleted.** This covers a new deleted row, "take incoming" for "active → deleted", and a locally deleted owner kept as is. Each of its memberships or handles ends deleted and is shown as "deleted with <owner>", with no choice for its deleted state. When the owner is kept deleted, a new owned row, or one the file shows active, gets the owner's local `deletedAt`, so it comes back when the owner is restored. If the file shows it deleted at a different time than the owner (compared with the owner's row in the file, even when that row duplicates the local one), it gets the owner's local `deletedAt` minus 1 ns instead, so restoring the owner leaves it deleted (see "Deleted owners keep their cascade"). The same applies to an existing owned row that is a duplicate (business fields and deleted state match, so it has no row in the preview): its `deletedAt` is never compared, but the file's still decides whether it is in the owner's cascade.
  - **The import restores the owner** ("take incoming" for "deleted → active"). The §5.2 restore cascade brings back the owned rows deleted together with it locally. They are shown as "restored with <owner>", with no choice for their deleted state, even where the file shows them deleted. Owned rows deleted locally at another time keep their own choice. Example: a person and a handle were deleted together locally, and the export has both active. Taking the person shows the handle as "restored with <person>"; "keep local" for its deleted state is not offered.
  - **A choice on an owned row's business fields always applies.**

  Changing the owner's choice updates the owned rows in the preview at once. So no choice the user sees is overridden at commit, and a cascade never leaves an owned row active under a deleted owner. The preview's rows are therefore the conflicts plus every row whose deleted state a cascade forces, including local owned rows that aren't in the file (so a handle added after the preview shows up in the refreshed cascade, AC M1-21).
- **Schedules are classified with their participants,** the same way expenses are with their shares and payers (below): a `recurring_expenses` row and its `recurring_participants` rows form one unit, a changed participant makes the schedule a conflict, and "take incoming" replaces the whole template in one `update`. Two columns are not compared and have no choice:
  - **`handled_through` only moves forward.** The import writes the later of the local and the incoming value, whatever the row's choice: an incoming later value is applied (shown in the preview as a forced change, like a cascade), an incoming older one is ignored. So an import never makes a skipped or posted date due again, and two copies that handled different dates end at the further one. Import undo reverts it to the pre-import value with the rest of the row (§5.4).
  - **`auto_post_from` is local state:** exported, never compared, never taken. Every import write to a schedule that leaves it auto-posting sets it to today (the cut-off rule, §5.7), so dates that passed since the export wait for confirmation instead of being posted by the next runner pass.
- **Expenses are classified with their shares and payers.** Before classification, each incoming `expenses` row is assembled with its complete set of `expense_shares` and `expense_payers` rows into one unit, the same shape as an Expense revision snapshot. Payer rows follow every rule below for share rows (orphans, per-row validation, stored as given). The unit is compared as a whole: a changed share (e.g. EXACT 50/50 → 25/75 with an unchanged total) makes the expense a conflict, and the diff shows shares per person (as `RevisionDiff` does). "Take incoming" replaces the expense and all its shares in one `update` with one revision. A share row whose expense is not in the file is invalid (`CorruptData("orphan share")`). Each share is validated on its own row before the shares are sorted by `position`; a bad share is reported on its own row, and its expense is left out. Import stores share rows exactly as the file gives them, after identity mapping has replaced the person ID, and never recomputes them. Validation checks that they sum to the effect total and that the inputs are valid for the split type, not which participant got a leftover cent. So mapping the source me to the local me, which can change UUID order, cannot move a cent; later edits follow §4.4.
- **FX snapshots are immutable and have no versions.** They are classified by UUID on their own: unknown → inserted as is (no revision; snapshots have none); same UUID and identical fields → duplicate, skipped; same UUID with different fields → **invalid row** (the copies are corrupt, since snapshots never change), with no "take incoming". The local snapshot is kept. Every incoming entry that references a rejected snapshot and would be written (new, or a conflict) is **invalid too**, with the reason "references an FX snapshot that differs from the local copy": its stored effect was computed from the incoming rate, which the local snapshot no longer explains (local SEK→EUR 0.09 against an incoming expense of 1 000 SEK with a €100 effect), and a matching currency pair (rule 14) does not catch that. Like other invalid rows, they are skipped or fixed in the file; an incoming entry that is a duplicate of its local row is skipped as usual. Import undo never removes snapshots (§5.4).
- **Audit history stays complete locally.** Every imported entity's local history starts with a revision that records the file and source version it came from. Source-device history is not imported; only JSON restore (§7.1) carries full history.
- The import is checked against the **state invariants** of §3.4, not the edit rules (§3.4): it reproduces records rather than editing them. A group with removed members and their historical entries therefore imports as is, while anything that would leave an inconsistent state (e.g. a removed member with a non-zero net) shows up as invalid rows in the preview.
- **Identity mapping.** Own-export import is for *your own* data, so the source's me must become the local me:
  - **Same identity** (the source's `isMe` UUID equals the local me UUID, e.g. an install restored from your backup): imported as is.
  - **Different identity** (e.g. a second install that was set up fresh): the preview asks the user to confirm that this export is their own data from another install. On confirmation, every reference to the source me in incoming rows (payer rows, share rows, schedule participants, transfer parties, group members, `authorId`) is mapped to the local me while the rows are imported. The source me's Person row itself is not imported. Each affected entity's origin revision records the mapping ("Imported from <file> (source v<N>), source identity <uuid> mapped to me"). Existing local rows are never rewritten; mapping applies only to incoming data, before classification and validation.
  - **Declined:** the import is rejected with `ForeignLedger`. Importing another person's ledger is a sharing problem and belongs to phase 5.
  - After mapping, every non-group entry involves the local me and every imported group contains me, so rules 1–2 hold. Other people who exist under different UUIDs locally arrive as new people and can be merged afterwards (§5.6).
- Text values are unescaped per §7.2 when `manifest.json` declares `quote-prefix-v1`.
- A `schemaVersion` above the app's → `SchemaTooNew`. **Schema-1 exports (M1) still import:** they have no `expense_payers`, `recurring_expenses` or `recurring_participants` (the reader requires exactly the tables of the file's own schema), and each expense's `payer_person_id` becomes one `EQUAL` payer row paying the effect total, as in the migration (§3.2).
- **Group rows wait for M2.** In M1, rows of `groups` and `group_members`, and entries with a `group_id`, are invalid rows with the reason "group rows are imported from M2 on". So in M1 the only owner → owned cascade an import writes is a person with their payment handles. From M2 on they import like every other row, and groups cascade to their memberships as people do to their handles.
- **Before setup**, `parseImport` gives `NotFound("me")`. The batch's file name is the last segment of the document URI, URL-decoded.

### 7.5 Import pipeline

1. **Read** the zip within the limits below (`ImportReader`), off the main thread; the analysis runs on `Dispatchers.Default`.
2. **Map** columns using `manifest.json` (delimiter, BOM, row counts) and the CSV headers. Every table of the schema must be present, even an empty one, and hold exactly the data rows its manifest count declares; otherwise the file is `CorruptFile` (an incomplete or edited archive). Unknown extra entries stay ignored. Then apply identity mapping (§7.4). Row numbers are file rows: the header is row 1, the first data row is row 2. An empty cell is null.
3. **Validate** each row: required fields, amounts (`BigDecimal` through `MoneyParser`, never `Double`: `TooManyDecimals`, `AmountTooLarge`, `InvalidAmount`; each decimal column must equal its `*_minor` column, otherwise `CorruptData("<column> mismatch")`), FX rates (`InvalidRate`), effect amounts against their conversion (rules 16–17), dates, enums and booleans (`InvalidField(column)`), known currency codes (`UnknownCurrency`), shares, IBAN checksum, and unique IDs per table (`CorruptData("duplicate id …")`). Then check the §3.4 state invariants and attribute violations to rows:
   1. Each row to be written gets the field rules on its own.
   2. The state invariants run over the complete resulting state: every row, every conflict taken, through the same cascade-aware plan the commit uses. If it holds, no row is invalid. (A person deleted together with the transfers that involve them is valid as a whole, though the person alone would leave active transfers pointing at a deleted person.)
   3. Otherwise rows are blamed: a row is invalid with a violation that goes away when it is left out. A new person is left out together with the rows that name them (dangling references would make the validator report only rule 15 and hide the violation being tested), and is blamed only for a violation that stays while just those rows are left out. People and handles answer first, for the violations that remain when the incoming active entries are left out too (incoming deletions stay in); then entries answer for the rest.
   4. Rounds repeat until nothing is broken or no single row is to blame, and both passes run again while either blames a row, since one broken rule can hide others. A conflict that couldn't be taken is therefore an invalid row. A valid file costs one validator run; an invalid one about one run per row and round, which is fine at personal-ledger size.
4. **Classify** by UUID as new / duplicate / conflict (§7.4).
5. **Preview:** counts of new / conflicting / duplicate / invalid rows, conflicts with field diffs and a per-row choice, invalid rows with row number and reason, the resulting balance changes. Invalid rows must be fixed in the file or explicitly skipped (`ImportChoices.skipInvalidRows`). The preview records the ledger generation and the `ledgerSeq` (§5.1) it was built against, and shows every row that each cascade would touch. The parsed file and the last choices passed to `applyChoices` stay in memory under the preview's ID; `refreshPreview` reuses those choices.
6. **Commit** as one store write (one journal entry, so one transaction). The commit first compares the preview's generation (→ `LedgerReplaced`) and its `ledgerSeq` with the current ones, and binds the cached analysis to the exact snapshot it was built from: an analysis from an out-of-order refresh with an older basis also gives `ImportPreviewStale`. If **any** ledger write happened since the preview was built, it returns `ImportPreviewStale` and writes nothing, because what the user reviewed may no longer be what would be written. Example: the preview shows a person being deleted with their one payment handle, and a second handle is added before the commit; deleting the person now would cascade into a handle that was never shown. `refreshPreview(preview)` rebuilds the preview against the current state. It keeps the user's choices for rows whose diff is unchanged and highlights rows that are new or changed, including rows a cascade now touches. Otherwise the commit applies creates and updates in dependency order (people, payment handles, groups, memberships, FX snapshots, schedules, entries), then the deletions: entries first, then schedules, then owned rows deleted on their own, then groups and people with their cascade (§7.4); create an `ImportBatch`, tag the rows. An `ImportBatch` is written even when every row was a duplicate; its `rowCount` is the number of distinct rows written plus new FX snapshots. The state invariants are checked once, on the final state; its first violation is the error.

**Limits:** 20 MiB per file, 100 000 rows (enforced while parsing); for zips, at most 50 entries (counted from the actual entries, not the directory), at most 200 MiB total uncompressed, compression ratio at most 100. Duplicate zip entries and ambiguous CSV headers are rejected; unknown, unique zip entries are ignored. An I/O failure inside the CSV parser gives `CorruptFile`. The 100 000-row limit is a size guard, not a performance target.

Files are read and written through the Storage Access Framework or the share sheet. No storage permission is needed.

---

## 8. Error handling

Expected failures (bad input, rule violations, stale work, bad or unreadable files) come back as typed `LedgerError`s that the UI can display. Storage failures of the app's own data propagate as exceptions instead, after leaving that data consistent:

- A ledger write or a ledger-settings change whose journal append fails (Room error, full storage) throws; the store keeps its previous state (§2.2).
- A device-settings change whose file write fails throws; the file is replaced atomically, so it keeps the previous settings (§3.3). The `SettingsApi` setters therefore return `Ok` or throw.
- A backup whose document was written completely but whose `lastManualBackupAt` update fails throws: the backup file is good, but the reminder isn't cleared.
- A failure after a restore's or undo's commit point throws and needs an app restart (§7.1).

The ledger is never partly written: each write is one journal entry, and a restore commits through one pointer rename. Documents the user picked are not covered by that guarantee (see Files below).

| Area | Errors | Behaviour |
|---|---|---|
| Money / validation | `InvalidAmount`, `TooManyDecimals`, `AmountTooLarge`, `UnknownCurrency`, `InvalidRate`, `ConvertedToZero`, `RateRequired` (a conversion needs a rate the user must give, §4.5), `InvalidIban`, `InvalidField(field)` (blank or malformed field, short passphrase, bad export request, bad schedule pattern), split and payer errors (§4.4) | Returned as results; save is not possible |
| Integrity (§3.4) | `NeedsGroup`, `NotGroupMember`, `MemberHasBalance`, `MemberRemoved`, `GroupSettingsLocked`, `CannotModifyMe`, `StillReferenced`, `DeletedReference`, `InvalidMerge`, `SelfTransfer` | Transaction rolled back; error carries the data for a suggested action (create group, settle, re-add member, archive) |
| Recurring (§5.7) | `OccurrenceNotDue(nextDue)` (posting or skipping out of order, or past `endOn`) | Nothing written; the due list shows what is next. Auto-post failures are not errors to the caller: the occurrence stays due with its `autoPostError` |
| Concurrency | `StaleVersion`, `LedgerReplaced`, `ImportPreviewStale` | Nothing written; reload (after `LedgerReplaced`, drop the draft or preview, §6.2; after `ImportPreviewStale`, refresh the preview and review it again, §7.5) |
| FX consistency / data structure | `FxSnapshotMismatch`, `CorruptData` | Nothing written; the first comes from an edit that keeps a snapshot for the wrong pair or the wrong effect currency, or keeps a bank-actual snapshot after changing the paid amount (rules 8, 14, 16, 17); the second comes only from import (shown as invalid rows) |
| Revert to version | `RestoreConflict(violations)` | Nothing written; list of violated rules; "Open as draft" |
| FX | Network failure, unsupported currency | Fallback chain §4.5; worst case the user enters a rate manually. Never blocks entry |
| Rate refresh worker | Any | WorkManager exponential backoff; silent |
| Restore / import (fatal) | `CorruptFile`, `LimitsExceeded`, `SchemaTooNew`, `WrongPassphraseOrDamagedFile` (GCM tag failure), `ForeignLedger` (identity mapping declined), `RestoreFailed` (a failure before the commit point), process death during a restore or undo | Before the commit point: nothing changes and the new file is discarded. After it: the operation is complete; a failure while loading the committed database needs an app restart (§7.1). Never a mix |
| Files | `FileAccessFailed` (a picked document can't be opened, read or written: missing, revoked permission, full storage) | The ledger is unchanged. A backup or export target is opened in truncating mode (`wt`) and written directly, so a failure while writing can leave that document empty or partly written; the user should write it again |
| Lookups | `NotFound(id)`: an unknown ID, `"me"` before setup, `"restore"` with nothing to undo, `"preview"` after a restart | Nothing written |
| Import (per row) | Validation failures | Shown in the preview |

No third-party crash reporting.

---

## 9. Security

- **App lock:** `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (available as one API from minSdk 30), shown by the UI. The lock policy is the core's and runs in both flavors (`store.TimedLockGate`, driven by `security.AppSecurity`): `SettingsApi.observeLockRequired()` requires unlock on cold start and after a configurable time in the background (immediately, 1, 5, 15 min; default 1 min). The UI shows a lock screen covering all content while it is true and calls `markUnlocked()` after a successful prompt. App lock can only be enabled if the device has a secure lock screen (the UI checks).
  - Background and foreground are `ProcessLifecycleOwner`'s `ON_STOP` and `ON_START`. `ON_STOP` comes 700 ms after the last activity stops, so rotations and switches between the app's own activities never count. The time away is measured with `SystemClock.elapsedRealtime` (it keeps counting in deep sleep). The switch and the timeout are read on return; "immediately" is a timeout of zero.
  - The cold-start lock reads the device settings' current value (a `StateFlow`), so no frame shows before it. Turning app lock on mid-session doesn't lock the open app. The gate follows the committed setting, also when a settings update fails or its caller is cancelled after the file was written, so a failed disable never unlocks.
  - `AppSecurity` is installed at application start through Hilt field injection in `IouApplication`, so the real backend is created when the process starts. `androidx.biometric` and `USE_BIOMETRIC` arrive with the UI contributor's lock screen.
- **Screen protection:** a toggle (default **off**). The core sets `FLAG_SECURE` itself: an `ActivityLifecycleCallbacks` sets it in `onActivityPreCreated` from the current setting and follows the setting for the activity's lifetime. The UI only calls `setScreenProtection`.
- **Backup encryption:** §7.1; default on. Passphrase at least 8 characters (checked by the core in both flavors), entered twice, with a clear "cannot be recovered" warning (UI).
- **Android Auto Backup** (system backup, separate from manual JSON backups): enabled **only for the ledger files** in the app's database directory, through `dataExtractionRules` (API 31+) and `fullBackupContent` (API 30). Both include `domain="database" path="."`, because generation-named databases can't be listed by name. That covers the `iou-*.db` files (the pre-restore copy included, with SQLite's side files) and their pointer `db-state.json`, and excludes `db-state.json.tmp`, `restore-marker.json` and `restore-marker.json.tmp`. Device-local settings and the device ID live in `noBackupFilesDir`, outside Auto Backup by location; WorkManager keeps its own database in `no_backup` too.
  - Cloud backup runs only end-to-end encrypted: `disableIfNoEncryptionCapabilities="true"` on API 31+ and `requireFlags="clientSideEncryption"` on API 30. A device-to-device transfer always includes the ledger; on API 30 that takes a second include with `requireFlags="deviceToDeviceTransfer"`, because one include needs all of its flags.
  - Any unreferenced database file that arrives with a system restore is deleted by `RestoreRecovery` at the next start. When Auto Backup restores the database onto a new phone, the me identity comes with it, the device gets a new device ID, and device-local settings start at their defaults (checked on the emulator on 2026-11-05).
- **Permissions:** one runtime permission from M2: `POST_NOTIFICATIONS` (API 33+, `real` flavor), for recurring-expense notifications only, asked by the UI when the first schedule is saved; everything works without it (§5.7). Otherwise install-time normal permissions only. The `v0.1.0` real release merges `INTERNET` (declared by the `real` flavor only), WorkManager's `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`, and androidx.core's signature permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `FOREGROUND_SERVICE` (from WorkManager) is removed with `tools:node="remove"`: neither the rate refresh nor recurring posting ever runs as a foreground worker. `USE_BIOMETRIC` arrives with `androidx.biometric`. Exported components: `MainActivity`, WorkManager's `SystemJobService` (`BIND_JOB_SERVICE`), and WorkManager's and profileinstaller's diagnostic receivers (both guarded by `DUMP`). The merged manifest is reviewed before each release, and unexpected entries are removed with `tools:node="remove"` where safe.
- **Debug-only tooling:** the Argon2id timing receiver (`debug.KdfTimingReceiver`) exists only in `realDebug`, guarded by `DUMP` (the shell has it, apps don't).
- **Network:** cleartext traffic disabled. Release builds never log amounts, names or payment handles.

---

## 10. Distribution

Signed APKs on GitHub Releases (§2.4); friends install by sideloading, optionally tracking updates with Obtainium. Play Store distribution is revisited when phase 3+ brings in users beyond the friend group.

---

## 11. Testing

- **JVM unit tests:** `Money` (parse/format/arithmetic, currencies with 0 and 3 decimals, `MAX_MINOR` and overflow → `AmountTooLarge`), `SplitResolver` (including non-terminating fractions and maximal weights), `FxConverter` (differing minor digits, e.g. 100 JPY at 0.006 → 60 EUR minor units; `ConvertedToZero`; `InvalidRate`; inverted rates), `IdentityResolver` (alias trees, handle defaults), `LedgerEngine`, `SettlePlanner`, `LedgerValidator` (every rule, positive and negative), IBAN mod-97, `RevisionDiff`, snapshot upcasters, CSV escaping and unescaping, import classification, backup encrypt/decrypt and wrong-passphrase handling.
- **Kotest property tests:** the invariants of §4.7 (engine, settle plans, splits, merge and unmerge), money parsing, and the reversible text escaping (arbitrary text with leading `=`, `+`, `-`, `@`, `'` and the other prefixes).
- **Round-trip tests:** JSON backup → wipe → restore yields the identical ledger content (IDs, versions, revisions, settings, me identity) and cached rates, also after a restart. A normalized CSV export reads back to the same records, deleted and merged ones included, with escaped text restored exactly. M2 adds the full export → import into an empty install → identical balances test, together with group rows and removed members with historical entries.
- **`api` contract tests:** one abstract test suite per `*Api` interface, run against both the fake and the real implementation (the real one on file databases in a temporary directory, through `RealHarness`, which also simulates app restarts). It keeps the fake honest and protects the UI contributor from drift. All suites, the `groups` ones included, run against both backends.
- **Room tests on the plain JVM** (bundled SQLite driver): journal atomicity (a failure mid-write leaves no row and no revision), lossless row mapping, a seeded ledger written through the journal loads back identically, `StaleVersion` and `LedgerReplaced`, delete/restore cascades (also when written by import and by import undo), import undo (complete and with skipped rows), merge and unmerge with nested aliases. Migration tests with `MigrationTestHelper` start with the first schema change (schemas are exported to `app/schemas/`): `Migration(1, 2)` on a version-1 database seeded with expenses (ad-hoc, group, converted, deleted, merged participants) gives the same balances, flows and revision history afterwards, and a version-1 backup and a schema-1 export restore and import to the same result.
- **Restore tests (file system):** process death injected at every step of a restore and of an undo (the step hook throws an `Error`, which the procedure doesn't clean up after, so the files stay as a killed process leaves them), followed by a new backend on the same files and `RestoreRecovery`.
- **Recurring tests** use a fixed clock and zone: occurrence dates (month-end clamping, leap years, weeks, intervals, `endOn`), due lists across days, the runner on start, foreground, restore and from the worker's entry point, FX lookups at the occurrence date, failure isolation between schedules, a manual post racing an auto-post (exactly one expense), and the notifier against a recording `RecurringNotices` fake: what is shown, when it is shown again, what is cancelled, and the redacted text while app lock is on.
- **Cancellation tests** use a queued dispatcher or a suspending hook, so the caller can be cancelled at the exact point under test (a block that runs synchronously never suspends).
- **UI tests:** defined by the UI contributor.
- **CI:** §2.4. `DomainPurityTest` fails if an Android import appears in `money`, `ledger`, `api`, `store`, `fake`, `fx` or `io`, or `Double`/`Float` in `money`, `ledger`, `api`, `store`, `data`, `io` or `fx`. Lint's `NewApi` check guards JDK methods missing at API 30 (e.g. `BigInteger.longValueExact()` needs API 31).
- **Manual checklist** (`docs/manual-test-checklist.md`): app lock, `FLAG_SECURE`, Auto Backup rules, Argon2id timing, exported CSV (both modes; XLSX in M2) opened in Excel and LibreOffice, signed release APK installs and upgrades over the previous release. It records which checks ran on the emulator and which wait for a real phone or for the UI contributor's screens.

---

## 12. Milestones and acceptance criteria

### Milestone 0: Skeleton and UI contract

**Status:** done, released as `v0.0.1` on 2026-11-03 (plans `2026-11-02-m0-*`).

**Goal:** the UI contributor can start building real screens within about the first week.

**Scope:** project skeleton (`:app`, packages, Hilt, `fake`/`real` flavors, applicationId); CI workflow including tag-based signed releases; `money`; `ledger.model`; `ledger.rules` and `ledger.engine` (enough for the fake: splits, identity resolution, balances, settle plans, validator); the complete `api` package with `LedgerError`; the in-memory fake with seed data; contract test suites running against the fake.

**Acceptance criteria**

1. `fakeDebug` builds and runs; every `*Api` returns seeded data, and writes update all observers.
2. The fake rejects invalid writes with the same `LedgerError`s the spec defines (e.g. a 3-person ad-hoc expense → `NeedsGroup`).
3. CI is green on push; a `v0.0.1` tag produces a signed APK on a GitHub Release.
4. The `api` package has no Android imports and no `Double`/`Float` in money paths (CI-checked).

### Milestone 1: Personal ledger

**Status:** done, released as `v0.1.0` on 2026-11-06 (plans `2026-11-04-m1-*`). The acceptance criteria are proven by JVM tests (each flavor runs 380 tests); the emulator covered what the placeholder screen can show (lock, `FLAG_SECURE`, Auto Backup, permissions, Argon2id timing, the signed upgrade). Still open, on the manual checklist: Argon2id timing on a low-end phone; app lock and `BiometricPrompt` on a real phone; on-device walkthroughs of backup, export and import through SAF, which wait for the UI contributor's screens. Update 2026-11-06: those screens are integrated on branch `integrate/compose-ui` (pending merge); the device checks they enable are tracked in section 8 of the manual checklist.

**Scope:** Room schema and the real backend on the shared store (ADR-0010) for setup, people, payment handles, transfers and ad-hoc two-person expenses; the write path with revisions, history and revert; person merge/unmerge; FX service and rate cache; JSON backup/restore (encrypted by default) with reminder; CSV export (normalized and report) and own-export import for people, payment handles, transfers, expenses and FX snapshots; app-lock policy; screen protection. Two items moved forward from M2: **groups work in the real flavor** (the shared store and ops made them free), and **import undo** is built for M1's import types.

**Acceptance criteria**

1. A 50.00 EUR loan from me to X shows "X owes you 50.00 EUR". A 20.00 EUR repayment from X to me changes it to 30.00 EUR, regardless of account label.
2. Balances in two currencies are shown separately; the combined home-currency total is labelled approximate.
3. An untagged transfer between two people who are not me, and an ad-hoc expense with three people, are rejected with `NeedsGroup`.
4. Editing a transfer from 40.00 to 45.00 adds an `UPDATED` revision whose diff shows "40.00 → 45.00 EUR"; reverting to v1 brings back 40.00 and adds a further revision.
5. Deleting a transfer removes its effect immediately; restoring brings it back; both appear in history.
6. Two duplicates "Alex" (owes me 10.00) and "Alex S." (owes me 5.00) merge into one person who owes me 15.00. Their old entries still show their original revisions, and unmerging restores 10.00 / 5.00. If "Alex" was first merged into "Alex S." and "Alex S." is then merged into "A. Smith", unmerging "Alex S." takes "Alex" along and restores the balances from before the second merge.
7. JSON backup → wipe → restore yields identical balances, IDs, versions, revisions and the same me identity; the device ID is unchanged by the restore.
8. Restoring with a wrong passphrase writes nothing and shows "Wrong passphrase or damaged file". A failure injected before the commit point leaves the previous database and ledger settings intact. "Undo last restore" after a successful restore brings back the previous state.
9. A text field `=HYPERLINK("http://evil","x")` exports as text in CSV, and re-importing the export restores exactly `=HYPERLINK("http://evil","x")`; a note `'quoted` also round-trips unchanged.
10. Importing an export in which a transfer was edited differently from the local copy (even with a higher source version) shows a conflict with a field diff. Nothing is overwritten unless "take incoming" is chosen, and the resulting revision records the source file and version. A transfer deleted in the export but active locally shows as a conflict "active → deleted"; taking incoming deletes it (and the reverse restores it).
11. Importing an export from a fresh second install (different me identity): after confirmation, its transfers with X arrive as transfers between the local me and X, pass all §3.4 rules, and their origin revisions record the identity mapping. Declining rejects the import with `ForeignLedger` and writes nothing.
12. Killing the process at each step of a restore and of an undo (test hook) leaves, after restart, either the complete old database or the complete new one, never a mix. A kill before the commit point keeps the old database and is reported once; a kill after it keeps the new one and reports nothing.
13. With no manual backup for 30 days, `BackupApi` reports the reminder; making a backup clears it.
14. The M0 contract tests for setup, people, the ledger, FX, backup, import/export and settings pass against the real implementation. *(Changed in M1: groups are live in the real flavor, so the contract tests tagged `groups` run against it too. The original criterion kept them out until M2.)*
15. After a restore commits, `observeLedgerGeneration()` emits a new value. Saving an edit draft loaded before the restore returns `LedgerReplaced` and writes nothing, even when the restored entry has the same version number; saving a settle-up or other create draft prepared before the restore, and committing an import preview parsed before it, do the same. After "Undo last restore", the generation changes again, and a token from before the restore is still rejected with `LedgerReplaced`, although the entry is back at that version.
16. An import file containing two deleted people whose `mergedIntoId`s point at each other is rejected with those rows invalid (`CorruptData`).
17. Changing a converted transfer's paid currency while trying to keep its old snapshot is rejected (`FxSnapshotMismatch`); the user must take a new rate.
18. X owes me 100 SEK. A 9.00 EUR repayment from X, recorded from the person settle-up draft with `settlesCurrency` SEK and a rate giving a 100 SEK effect, brings the SEK balance to 0 and leaves the EUR balance unchanged. Editing only its note keeps the snapshot and the SEK effect; clearing `settlesCurrency` drops the snapshot and turns it into a 9.00 EUR effect.
19. A 1 000 SEK transfer with a bank-actual charge of 87.65 EUR (snapshot records 1 000 SEK and 87.65 EUR) keeps that snapshot and effect when only its note or date is edited. Changing the paid amount to 2 000 SEK while keeping the snapshot is rejected with `FxSnapshotMismatch`; saving with a new charged amount or a looked-up rate succeeds.
20. An import file with a 1 000 SEK transfer, a new `USER_ENTERED` snapshot at 0.09 EUR/SEK and an effect of €100 shows that transfer as invalid (`FxSnapshotMismatch`, rule 17), and so does an unconverted transfer whose effect amount differs from its paid amount.
21. Adding a payment handle to a person after an import preview was built makes the commit return `ImportPreviewStale` with nothing written. The refreshed preview keeps the earlier choices and shows the new handle under the person's cascade.

### Milestone 2: Groups, splits, multiple payers and recurring expenses

**Scope:** groups and members (me auto-added); group expenses with all four split types; CONVERT and KEEP FX modes; member nets, flows and settle plans; group-tagged settle-up; archiving with open balances; **multiple payers per expense; recurring expenses;** CSV export and import extended to groups, payers and schedules; XLSX export; import undo.

The group core (ops, rules, views and their persistence) already runs in both flavors since M1, and so does import undo for people, handles and entries. M2's core work comes in this order, because each step builds on the one before:

1. **Multiple payers** (§3.2, §4.2, §4.4): payer rows, contribution-based effects, rules, ops, views, `api` and fake, the schema-2 snapshot upcaster, `Migration(1, 2)`, version-1 backups and schema-1 exports.
2. **Recurring expenses** (§5.7): schedules with their rules, ops, views, `RecurringApi`, fake, runner, and their tables in the same (still unreleased) schema 2.
3. **The original M2 items:** CSV import of group rows (with their cascades and undo), XLSX export with the FastExcel check on Android, and the export → import round trip with groups, now covering payers and schedules too.

M2 ships as **one release** (`v0.2.0`) with the single schema-2 migration; nothing from steps 1–2 is released on its own, and testing builds stay on `v0.1.0` plus fixes until then. The `api` changes (§6.4) are agreed with the UI contributor before step 1 (or the owner says to go ahead without waiting). The screens for payers and schedules are theirs; the core is done when the contract tests pass against both flavors.

**Acceptance criteria**

1. 100.00 EUR split EQUAL among 3 → 33.34 / 33.33 / 33.33, with the extra cent always going to the same person; sum 100.00.
2. PERCENT not summing to 100 % and EXACT with a non-zero remainder cannot be saved and return the corresponding errors.
3. A 1000 SEK expense in a CONVERT EUR group stores an FxSnapshot and EUR effect amounts whose shares sum exactly to the converted total; the same expense in a KEEP group produces SEK nets.
4. "A paid 60.00 EUR for A, B and me (equal)" gives nets A +40.00, B −20.00, me −20.00. The plan is "B pays A 20.00, I pay A 20.00". My overview shows I owe 20.00 in that group, and A's person detail lists my suggested 20.00 under "In groups".
5. Recording all plan transfers (group-tagged) brings every net in the group to 0. An untagged repayment to A changes only my non-group balance with A.
6. For random groups, a settle plan settles all nets, every transfer amount is valid, and it has at most n−1 debtor–creditor pairs per currency (at most n−1 transfers when all nets are within `MAX_MINOR`), including groups whose nets exceed `MAX_MINOR` (property test).
7. In a group where A owes B 10.00 and C owes A 10.00 through the flows, A's net is 0, so removing A succeeds. Afterwards, editing, deleting or restoring any group entry involving A is rejected with `MemberRemoved` until A is re-added. Removing a member with a non-zero net is rejected with `MemberHasBalance`.
8. Removing me from a group is rejected with `CannotModifyMe`; a newly created group contains me.
9. Adding a group expense whose payer or participant is not an active member is rejected with `NotGroupMember`.
10. In a group with at least one entry (including a deleted one), changing `fxMode` is rejected. In a CONVERT group, changing `defaultCurrency` is rejected too. In a KEEP group, changing `defaultCurrency` is allowed and leaves existing nets unchanged.
11. Reverting an expense to a version that includes a since-removed member returns `RestoreConflict` naming that rule and writes nothing; "Open as draft" pre-fills the old values.
12. Archiving a group in which my net is −15.00 is allowed after a warning. The group still counts in "I owe" and stays in the overview as "archived, unsettled" until settled.
13. Deleting an expense removes its effect from all nets immediately.
14. An import file with one invalid row shows that row and the reason in the preview and commits nothing until it is fixed or skipped. Undoing an import in which no row was changed afterwards removes exactly the rows it created, reverts the rows it updated (including their deleted state), and restores the previous balances; this includes an import that created a person together with transfers involving them. If an imported row was changed after the import, undo skips it (and any row that still depends on it), lists each skipped row with its reason, and undoes the rest. This includes owners: if a payment handle the import created was edited afterwards, undo skips both the handle and the person the import created it under. In the import preview, choosing "take incoming" for a person deleted in the export shows that person's active handles as "deleted with <person>", and choosing it for a locally deleted person who is active in the export shows the handles deleted together with them as "restored with <person>"; neither offers "keep local" for the handles' deleted state. Undoing an unchanged import that restored a person together with their handle deletes both again with one shared `deletedAt`, and restoring the person afterwards brings the handle back; a handle deleted before its person and restored by its own choice is deleted again with its own earlier `deletedAt`, so restoring the person afterwards leaves it deleted. Taking the deletion of a person whose handle was deleted here earlier, when the export shows the two deleted together, gives the handle the person's new `deletedAt`, so restoring the person brings it back; undoing that import restores the person and leaves the handle deleted at its earlier `deletedAt`.
15. XLSX report and normalized workbooks open in Excel and LibreOffice with correct numbers and dates.
16. A full export of a ledger containing a group with a removed member and that member's historical expenses imports into an empty install without errors and reproduces every net. Undoing that import at once deletes everything it created, reports nothing skipped and leaves every net at 0, even where single steps would break rule 4 (e.g. the removed member's expense and repayment that cancel out).
17. Merging two people who both belong to a group, each with a default IBAN and with a group transfer between them, succeeds: the transfer has no effect, the canonical person has one default IBAN (§3.2), and unmerging restores both people's nets.
18. All `api` contract tests, including those tagged `groups`, pass against the real implementation.
19. Deleting an empty group deletes it with its memberships (me's included); restoring it brings back the group and those memberships together. After undoing an import that created a group, restoring that group works the same way. Importing an export that contains a deleted group with its memberships (deleted together) into an empty install creates them with one shared local `deletedAt`, so restoring the group brings its memberships back with it.
20. Importing an export in which an EXACT expense's shares changed from 50.00/50.00 to 25.00/75.00 (same total) shows a conflict with a per-person share diff; "take incoming" writes one `UPDATED` revision with the new shares. An FX snapshot whose UUID exists locally with a different rate is shown as invalid and is never overwritten, and every new or conflicting incoming entry that references it is shown as invalid too.
21. Moving a SEK expense from a CONVERT EUR group to a CONVERT USD group requires a new SEK→USD rate; moving it to a KEEP group drops the snapshot and keeps SEK effects.
22. A €90 EQUAL expense over Alex-A, Alex-B and Bob, after the two Alex records are merged, gives Alex €60 and Bob €30. Editing only its note keeps those shares; changing its total to €120 recomputes it over the canonical participants (Alex €60, Bob €60), shown by `previewSplit` before saving. An imported expense whose share rows were remapped from another me identity keeps its cents on the same people.

*Multiple payers*

23. A €100 group expense split EQUAL over Alice, Bob and Carol (Alice has the lowest UUID, so her share is 33.34), paid €60 by Alice and €40 by Bob, gives nets Alice +26.66, Bob +6.67, Carol −33.33; the nets sum to 0, and the flows show Carol paying Alice 26.66 and Bob 6.67. EXACT payer amounts of €60 and €30 return `PaidRemaining` with the €10.00 difference; payers `EQUAL` over Alice and Bob give €50.00 each.
24. Outside groups, a €100 expense split 50/50 that I paid €30 of and X €70 gives "I owe X 20.00". A third person as payer is rejected with `NeedsGroup`.
25. A ledger created by `v0.1.0` (Room schema 1) upgrades to schema 2 with every balance, flow and settle plan unchanged; every expense shows one payer; reverting an expense to a pre-upgrade version works and gives one payer. A `v0.1.0` backup restores and a `v0.1.0` normalized export imports with the same result.
26. A 1 000 SEK expense in a CONVERT EUR group paid 600 SEK by A and 400 SEK by B (EXACT) stores payer amounts that sum exactly to the converted total; editing only its note keeps payers and shares as stored.

*Recurring expenses*

27. A monthly schedule starting 31 January posts occurrences on 31 January, 28 February (29 in a leap year), 31 March and 30 April, and none after `endOn`.
28. A confirm-first schedule whose occurrences for March and April are due shows both, oldest first. Posting April first is rejected with `OccurrenceNotDue(March)`. Posting March with a changed amount creates that expense (linked to the schedule and to March) and leaves the template unchanged; skipping through April leaves nothing due. Neither comes back after a restart.
29. An auto-post schedule with three due occurrences posts all three, oldest first, when the app starts. Opening the app again posts nothing more. A manual post racing the runner for the same occurrence creates exactly one expense.
30. An auto-post SEK schedule in a CONVERT EUR group posts with the rate for each occurrence date; with no rate available (offline, empty cache) the occurrence stays due with `autoPostError = RateRequired`, and other schedules still post.
31. A schedule whose participant was removed from the group stays due with `MemberRemoved`. Archiving its group (or a person in it) puts it on hold: nothing is posted (not even an occurrence whose rate was being fetched when the hold started), due or notified, and it is listed only among held schedules. Unarchiving shows the dates that passed during the hold as due for confirmation; an auto-post schedule posts nothing by itself until they are posted or skipped, then resumes. Editing a held schedule to drop the archived person resumes it the same way. So does merging the archived person it references into an active duplicate: the merge writes the schedule's resume ("Resumed: … merged into …"), and the dates from the hold wait for confirmation; unmerging an active alias from an archived person likewise. Deleting a person or group that a schedule references is rejected with `StillReferenced`.
32. An auto-post schedule created today with `startOn` three months back shows its three past occurrences and today's as due and posts none of them by itself; after the three are skipped, the runner posts today's. Switching a confirm-first schedule with due dates to auto-post, and reverting it to an earlier auto-post version, leave those dates due in the same way.
33. Editing a schedule changes later occurrences only; posted expenses keep their values. Moving a monthly rent (started 1 January, 1 December handled) to the 5th makes 5 December next. Deleting a schedule keeps its posted expenses; restoring it shows its occurrences after the handled date as due.
34. With the app closed, the `recurring-post` worker (run on demand on the emulator) posts a due auto-post occurrence and shows one "posted" notification; a confirm-first schedule that becomes due shows one "due" notification, which is not repeated on the next run and disappears when the occurrence is posted in the app. With two schedules due, later runs make no further sound. With app lock on, both texts name no people or amounts; switching app lock on while detailed notifications show redraws the due one redacted and removes the posted one. With the notification permission denied, posting still happens and nothing is shown.
35. A JSON backup and a normalized export carry schedules with their participants and `handledThrough`; restore and import reproduce them. Import keeps the later `handledThrough`: an incoming copy handled through October never rewinds a local schedule skipped or posted through November, and an incoming copy handled through December moves a local one at November forward. A confirm-first schedule at October, imported together with a November expense and progress through November and then undone at once, shows November due again; undo reports nothing skipped. Any import write to an auto-post schedule makes dates before the import day wait for confirmation. An import file with two non-deleted expenses for the same schedule and date marks them invalid (rule 18).

---

## 13. Extension points (not built now)

| Future item | Prepared by |
|---|---|
| Phase 3 pay/request helpers (EPC QR, Revolut links, bank launch) | `PaymentHandle` with validated IBAN, holder name, default flag |
| Phase 4 auto-detected repayments | Confirmed suggestions become ordinary transfer creates through `ledger.ops` and the store; add `Transfer.bankTxnRef` then |
| Phase 5 sync: identity | The me UUID is the account ID. **Linking** a local contact to a real account = merging it into a person record carrying that account's identity (§5.6). **Claiming** a placeholder in a shared group = the same merge, done by its real owner. No ID translation layer |
| Phase 5 sync: data | UUIDs, versions, tombstones, `authorId`, `deviceId`, `StaleVersion` conflicts; "leave a shared group" becomes unsubscribing, separate from ledger membership |
| C1 event-sourced ledger | `EntryRevision` already holds full snapshots per write and can become the source of truth |
| C3 signed / hash-chained entries | Add signature and previous-hash columns to `EntryRevision`; device keys tied to `deviceId` |
| C2 counterparty confirmation | Add an entry `status`; the engine filters by it |
| B1 optimal debt simplification | Replace or extend the greedy `SettlePlanner` (zero-sum subgroups) |
| Recurring transfers | `RecurringExpense`'s pattern and runner (§5.7) take a transfer template next to the expense template |
| Custom categories, analytics | Category enum → table when D2/D3 arrive |
| Splitwise import/export, generic CSV/XLSX import wizard | `Importer` interface and shared pipeline (§7.5) |
| Play Store | applicationId fixed from day one; signing key under owner control |
| E1 iOS via KMP | `money`, `ledger.*`, `api`, `store`, `fake`, `fx` and `io` have no Android imports |
| F encrypted local DB | Room access is isolated in `data` behind the `LedgerJournal` port |
| Larger ledgers | The whole ledger lives in memory (ADR-0010); paging or Room-driven reads can come later behind the same `api` |

---

## 14. Open items to verify while building

1. **Open (M2):** confirm the FastExcel writer runs on Android (core library desugaring if needed).
2. **Done (M1):** Frankfurter takes `base`/`symbols`. Checked live on 2026-11-04 and 2026-11-05: `latest` and dated requests answer 200 with JSON numbers, a Sunday answers with the previous Friday's date, a future date and an unknown currency answer 404 (or time out), and the client treats every failure as "unavailable" (§4.5).
3. **Partly done:** BouncyCastle Argon2id at 64 MiB, t=3, p=1 took about 0.65 s per encryption or decryption on the x86_64 emulator (2.0 s for the first), so the default stays. Still open: a low-end real phone; lower the memory parameter if a backup takes more than about 2 s there (old backups stay readable, since they record their parameters).
4. **Done for `v0.1.0`:** the merged release manifest was inspected (§9 lists the result; `FOREGROUND_SERVICE` removed). Repeat when `androidx.biometric` arrives with the lock screen, and before each release.
5. **Done (M1):** `dataExtractionRules` and `fullBackupContent` both include only the ledger files and exclude the temp pointer and the marker files (§9). Checked on the emulator on 2026-11-05: its local transport offers no encryption, so cloud backup is refused as intended; with the encryption requirement removed for the test, a backup, `pm clear` and restore brought back only `databases/` without the marker, a new device ID and default device settings.
6. **Open (M2):** on the emulator, check how late the 6-hour `recurring-post` work actually runs under Doze and battery saver, and that it is re-scheduled after a reboot (WorkManager's `RECEIVE_BOOT_COMPLETED`). Shorten the period if occurrences arrive too late in practice.
