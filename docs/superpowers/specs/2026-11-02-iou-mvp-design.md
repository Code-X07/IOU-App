# IOU App — MVP Design (Phases 1–2)

- **Date:** 2026-11-02
- **Status:** Approved; revised 2026-11-02 after an external review (merge model, import of deleted state, rule kinds, numeric contract, export scope, restore commit point, milestone criteria), and again for the ledger generation in the database pointer, draft bases, cross-currency settlement edits, FX snapshot conflicts on import, and cascades written by import and import undo
- **Sources:** `01-core-spec.md` (suggestions), `02-future-features.md` (kept in mind for extension points)
- **Contributors:** project owner (core: domain, data, engine, I/O, security); friend (the whole UI layer, ViewModels included, see §6)

---

## 1. Summary

An Android app that replaces WhatsApp-based tracking of shared expenses and IOUs in a friend group. It keeps running balances with each person, independent of which bank account money moved through, and supports groups with equal / percent / shares / exact splits, multiple currencies, a full audit history of every edit, and import/export.

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
- Optimal (minimum-transfer) debt simplification, FIFO "settled bills" view, receipts, recurring expenses, analytics.
- Multiple payers per expense; user-defined categories; scheduled automatic backups.
- Encrypted local database (SQLCipher).
- Play Store distribution; iOS.

---

## 2. Architecture

### 2.1 Module and packages

The Gradle project lives in `android/` at the repository root (only `android/` and `docs/` are tracked). It has a single module `:app` with two product flavors (§2.4). Packages marked "No" contain **no Android imports**. They are testable on the plain JVM, shared by the fake and real implementations, and portable to KMP later.

| Package | Responsibility | Android imports | Owner |
|---|---|---|---|
| `money` | `Money` value class (Long minor units + ISO 4217 code), minor-unit digits, text→money parsing via `BigDecimal`, formatting | No | Core |
| `ledger.model` | Domain types: Person, PaymentHandle, Group, GroupMember, Transfer, Expense, ExpenseShare, FxSnapshot, EntryRevision | No | Core |
| `ledger.rules` | `LedgerValidator`: the integrity rules of §3.4 as pure functions over a read-only `LedgerState` | No | Core |
| `ledger.engine` | `SplitResolver`, `FxConverter`, `IdentityResolver` (canonical IDs), `LedgerEngine` (balances), `SettlePlanner`, `RevisionDiff` | No | Core |
| `api` | **The core ↔ UI contract:** `*Api` interfaces, input and output models, `LedgerError`, `LedgerResult<T>` (§6) | No | Core (changes by PR, §6.4) |
| `fake` | In-memory implementation of `api`, using `ledger.rules` and `ledger.engine`; seeded demo data | No | Core |
| `data` | Room entities and DAOs, **`LedgerWriter`** (sole write path), `LedgerRepository`, migrations, snapshot upcasters, real `api` implementation | Yes | Core |
| `fx` | Frankfurter client, rate cache, `FxService`, daily refresh worker | Yes | Core |
| `io` | JSON backup, CSV, XLSX exporters; own-CSV importer; import pipeline | Mostly no | Core |
| `security` | App-lock policy, backup encryption | Yes | Core |
| `ui` | Compose screens, **ViewModels**, navigation, theming, `LedgerError` → string mapping, `BiometricPrompt` calls | Yes | UI contributor |

### 2.2 Data flow

```
ui (ViewModels)  ──calls──▶  api interfaces
                                 │
            ┌────────────────────┴─────────────────────┐
     fake flavor                                  real flavor
  in-memory state                       data: LedgerWriter ──(one Room txn)──▶ row + EntryRevision
        │                                        LedgerRepository ◀── Room
        └──────────────┬─────────────────────────────┘
                       ▼
     ledger.rules (validate writes) + ledger.engine (compute balances, settle plans)
```

- **Reads:** `api` exposes `Flow`s of ready-to-render domain models (overview, person detail, group detail, activity, entry detail). In the real flavor they come from Room `Flow`s mapped through `IdentityResolver` and `LedgerEngine` on `Dispatchers.Default`. Any write re-emits.
- **Writes:** `suspend` functions returning `LedgerResult<T>`. In the real flavor only `LedgerWriter` may write ledger tables; DAO write methods are `internal` to `data`, and a test asserts no other class calls them.
- **Same rules everywhere:** the fake and the real implementation call the same `LedgerValidator` and `LedgerEngine`, so the fake produces realistic errors and balances.

### 2.3 Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3, Navigation Compose (UI choices beyond this are the UI contributor's) |
| DB | Room (SQLite) |
| Device-local settings | Jetpack DataStore (Preferences) |
| DI | Hilt (incl. WorkManager integration) |
| Async | Coroutines + Flow |
| Networking | Ktor client (OkHttp engine) + kotlinx.serialization |
| Background | WorkManager |
| CSV | Apache Commons CSV |
| XLSX (export only) | FastExcel writer |
| Crypto | BouncyCastle (Argon2id), JCE AES-256-GCM |
| Tests | JUnit 5, Kotest (property tests), Room in-memory tests |
| SDK | **min 30** (Android 11), target latest stable |

### 2.4 Build, flavors and release

- **applicationId:** `io.github.codex07.iou`; display name "IOU" (working title; the display name can change, the ID cannot).
- **Flavors:** `fake` (Hilt binds the in-memory `api` implementation with seeded demo data; applicationId suffix `.fake`, so it installs side by side with the real one) and `real` (Room-backed).
- **CI** (GitHub Actions, on push): lint, JVM tests, Room tests, `api` contract tests against both implementations (§11), and the `Double`/`Float` check.
- **Release:** pushing a `v*` tag builds `realRelease`, signs it with the release keystore stored as encrypted repository secrets, and attaches the APK to a GitHub Release. The project owner holds the keystore and keeps an offline backup; the UI contributor never needs it. Updates can be tracked with Obtainium. Debug builds use the default debug key.

---

## 3. Data model

### 3.1 Conventions

- **Money:** `amountMinor: Long` + `currency: String` (ISO 4217). `Double`/`Float` never appear in money paths (CI-enforced). Minor-unit digits come from `java.util.Currency.defaultFractionDigits`.
- **Money limits and overflow:** every stored amount (original, effect, share, EXACT input) lies between 0 and `MAX_MINOR = 10^13` minor units (> 0 where the entity requires it); larger input → `AmountTooLarge`. All `Long` money arithmetic uses `Math.addExact` / `subtractExact` / `multiplyExact`, and `BigDecimal` → `Long` uses `longValueExact()`. An overflow never wraps: if computing the balances of the state a write would produce overflows, the write is rejected with `AmountTooLarge`. (With the cap, that takes about 900 000 maximal entries in one currency.)
- **IDs:** UUID strings everywhere.
- **Identity:** the "me" person's UUID is generated once at first launch and is the user's **permanent global identity**. Backups carry it and restores keep it; it is never regenerated. In phase 5 the user's account is this ID.
- **Device ID:** a UUID generated per installation, stored in `noBackupFilesDir` (never backed up, restored, or exported). Every revision records it.
- **Sync columns** on every syncable entity (Person, PaymentHandle, Group, GroupMember, Transfer, Expense): `id`, `createdAt`, `updatedAt`, `authorId` (always the me identity until sync), `deletedAt` (tombstone), `version` (Int, starts at 1, +1 on every write), `importBatchId` (nullable).
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

**Expense**: something exactly one person paid on behalf of one or more people.

| Field | Type | Notes |
|---|---|---|
| groupId | UUID? | Null = ad-hoc (only me and one other person, §3.4) |
| payerPersonId | UUID | Need not be a participant |
| description | String | |
| category | Enum? | Fixed list: `GROCERIES`, `DINING`, `TRANSPORT`, `ACCOMMODATION`, `ENTERTAINMENT`, `UTILITIES`, `RENT`, `GENERAL`, `OTHER` |
| totalMinor, currency | Long, String | As paid; total > 0 |
| effectTotalMinor, effectCurrency | Long, String | Converted to the group currency in a CONVERT group with a foreign currency; otherwise equal to the original |
| fxSnapshotId | UUID? | Set when converted |
| splitType | Enum | `EQUAL`, `PERCENT`, `SHARES`, `EXACT` |
| occurredOn | LocalDate | |
| note | String? | |
| + sync columns | | |

**ExpenseShare**: owned by its Expense. No sync columns or revisions of its own; it is versioned as part of the Expense snapshot. On edit, all shares of the expense are replaced.

| Field | Type | Notes |
|---|---|---|
| expenseId, personId | UUID | Composite key |
| shareMinor | Long | In the expense's `effectCurrency`; ≥ 0 |
| inputValue | Long? | Basis points (PERCENT), weight (SHARES), or minor units in the original currency (EXACT) |

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
| entityType | Enum | `PERSON`, `PAYMENT_HANDLE`, `GROUP`, `GROUP_MEMBER`, `TRANSFER`, `EXPENSE` |
| entityId | UUID | |
| version | Int | Equals the entity's `version` after the write |
| action | Enum | `CREATED`, `UPDATED`, `DELETED`, `RESTORED` |
| authorId | UUID | The me identity until sync |
| deviceId | UUID | Installation that made the write |
| at | Instant | |
| reason | String? | e.g. "Reverted to v2", "Merged into Alex", "Imported from export.zip (source v3)" |
| importBatchId | UUID? | |
| snapshotJson | String | Full entity state after the write (an Expense includes its shares), plus a `schema` number |

The DAO exposes insert and query only. A full JSON restore is the only operation that removes revisions, because it replaces the whole database.

### 3.3 Settings

- **Ledger settings: Room table `AppSetting(key, value)`.** Home currency (default `EUR`), backup encryption enabled (default **on**). Stored in Room so that a backup restore swaps them atomically together with the ledger (§7.1).
- **Device-local settings: DataStore.** App lock enabled, lock timeout, screen protection enabled, `lastManualBackupAt`. Never leave the device: excluded from **manual backups** (the JSON files of §7.1) **and** from **Android Auto Backup** (the system cloud backup of §9). Otherwise a restore onto a phone without a screen lock could enable an app lock the user cannot satisfy.
- **Device ID:** `noBackupFilesDir` (§3.1).
- **Rate cache:** Room table `FxRateCache(base, quote, rateDate, rate, fetchedAt)`.

### 3.4 Integrity rules

`LedgerValidator` (`ledger.rules`) checks these rules over canonical IDs. There are two kinds:

- **State invariants [S]** describe every committed state. They are checked against the **complete state a write would produce**, on every write path: create, update, delete, restore, archive, member add/remove, merge, unmerge, revert to version (§5.3), import (§7.4) and undo import (§5.4).
- **Edit rules [E]** guard changes made through the `api`. They apply only to the entities a write creates or changes, plus the operation's own preconditions, never to untouched historical entries. They run on create, update, delete, restore, archive, member add/remove, merge, unmerge and revert. **Import and undo import are checked against the state invariants only:** they reproduce or remove whole records rather than edit them, so an exported group with removed members and their historical entries imports as is.

**Business invariants constrain non-deleted rows only; structural invariants cover every row.** Rule 15 is structural: it holds for deleted rows too, so history and restore can always resolve them. Every other state invariant ignores soft-deleted rows until they are restored, and the restore is checked like any other write: the restored rows must satisfy the invariants again. So restoring an entry whose person or group is deleted fails with `DeletedReference` until that person or group is restored first.

`LedgerWriter` (and the fake) runs the validator on every write path. A write that violates any applicable rule is rejected in full with a typed `LedgerError` (§8).

**Scope of entries**

1. [S] **Outside groups, exactly me and one other person.** An ad-hoc expense (no group) has exactly two distinct people across payer and share rows: me and one other person. An untagged transfer has me as sender or receiver. Anything involving three or more people, or two people who are not me, must be in a group. → `NeedsGroup` (the UI offers to create a group from the selected people)

**Group membership**

2. [S] **Me is a member of every non-deleted group.** [E] Me is added automatically when a group is created and cannot be removed. → `CannotModifyMe`
3. [S] **Members only.** Every person a non-deleted group entry references (payer, share rows, transfer parties) has a non-deleted membership in that group, active or removed. → `NotGroupMember`
4. [S] **Removed members have a zero net.** A canonical person who is not an active member of a group has a group net of 0 there in every currency. So removing a member with a non-zero net fails, and so does any import or unmerge that would leave a removed member with one. → `MemberHasBalance`
5. [E] **Removed members freeze their entries.** Any create, update, delete or restore of a group entry that references a removed member is rejected. Their existing entries stay as they are and keep counting (their net is 0 by rule 4). Re-adding the member (clearing `removedAt`, recorded as a revision) unlocks those entries. → `MemberRemoved`

**Group currency settings**

6. [E] **`fxMode` is locked** once the group has any entry, including soft-deleted ones (they can be restored). → `GroupSettingsLocked`
7. [E] **In a CONVERT group, `defaultCurrency` is locked** under the same condition. In a KEEP group, `defaultCurrency` is only the default for new entries and stays editable.
8. [S] **CONVERT invariant:** every entry in a CONVERT group (expenses and group-tagged transfers) has `effectCurrency == group.defaultCurrency`, converted through an FxSnapshot when the paid currency differs.

**People**

9. [S] Exactly one person has `isMe`; it is never deleted or merged. [E] **Me** cannot be deleted, archived, merged, or used as a merge target. → `CannotModifyMe`
10. [S] No non-deleted entry, membership or payment handle references a deleted person, and no non-deleted entry or membership references a deleted group. → `DeletedReference`. [E] Through the `api`, a person or group can be deleted only when no entry references it, soft-deleted entries included (those could be restored), and a person also only when no membership references them; otherwise archive. → `StillReferenced`. A group's own memberships and a person's own payment handles don't block deletion: they are deleted and restored together with their owner (§5.2). Import undo is not bound by this edit rule: it deletes the entries it created first, then the people and groups (§5.4).
11. [S] Every non-deleted person's `mergedIntoId` chain ends at a non-deleted canonical person (rule 15 rules out cycles). [E] **Merge** (§5.6): source and target must be distinct, non-deleted, non-me, and both canonical (neither merged into anyone). → `InvalidMerge`
12. [E] **Unmerge:** the person must be merged into someone. The resulting state must satisfy the state invariants (e.g. rule 4 for a removed membership of the split-off person). → the violated rule's error
13. [E] **Distinct transfer parties:** a created or updated transfer has different canonical sender and receiver. A transfer that became a self-transfer through a merge stays valid and has no effect (§4.1). → `SelfTransfer`

**FX and structure**

14. [S] **FX snapshot consistency:** an entry has an `fxSnapshotId` exactly when its effect currency differs from its paid currency, and that snapshot has `baseCurrency` = the paid currency and `quoteCurrency` = the effect currency. → `FxSnapshotMismatch`
15. [S, all rows] **Structure:** every ID reference (person, group, expense, FX snapshot, `mergedIntoId`) points to an existing row, deleted or not; the `mergedIntoId` graph over all people, deleted ones included, has no cycles; exactly one person has `isMe`. → `CorruptData` (only reachable through import, where it shows as invalid rows)

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
- **Expense** paid by P: for each share of person X ≠ P with `shareMinor > 0`: `Effect(P, X, effectCurrency, shareMinor, groupId)`.

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
- **Approximate home-currency totals** use the latest cached rates, are always labelled approximate, and are never stored.

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
| SHARES | Integer weight 1–1 000 000 per participant | `total × w / Σw` |
| EXACT | Minor units per participant, in the original currency | Must sum exactly to `totalMinor` |

- **Largest-remainder rounding** (EQUAL, PERCENT, SHARES), in exact integer arithmetic (`BigInteger`; no division with a precision or rounding mode). Each participant has a weight `w` (1 for EQUAL, basis points for PERCENT, the weight for SHARES), and `W = Σw`. The floor share is `total × w div W`, the remainder `total × w mod W`. The `total − Σfloor` leftover minor units (always fewer than the number of participants) go +1 each to the participants with the largest remainders. All remainders share the denominator `W`, so comparing them is exact, and non-terminating fractions such as 100/3 need no precision setting. Ties go to the lowest person UUID (string order).
- **EXACT in a converted expense:** the per-person exact amounts in the original currency become the weights, and the converted total is distributed by the same procedure.
- **Errors:** `NoParticipants`, `PercentNotHundred(sumBp)`, `ExactRemaining(diffMinor)`, `ZeroWeights`, `NegativeInput`.
- Splitting happens over `effectTotalMinor`, so `Σ shareMinor == effectTotalMinor` always holds.

### 4.5 FX (`FxService`, `FxConverter`)

Rate lookup `rateFor(occurredOn, from, to)`:

1. Cached historical rate for that date.
2. Frankfurter historical endpoint (`https://api.frankfurter.dev/v1/{date}`).
3. The most recent cached rate, with the snapshot flagged `isStale = true`.
4. No rate available, or a currency outside the ECB set: the user enters a rate manually (`USER_ENTERED`).

- **Rate semantics:** `rate` is the number of major units of `quoteCurrency` per one major unit of `baseCurrency`. A snapshot used by an entry always has base = the paid currency and quote = the effect currency, so conversion is always a multiplication. An inverted or cross rate (e.g. from cached rates against the home currency) is computed once with `MathContext.DECIMAL128` and stored as the snapshot's rate string; conversion always uses the stored string, so it is reproducible.
- **Valid rates:** a rate parses as `BigDecimal`, is > 0 and has at most 34 significant digits; otherwise `InvalidRate`. This applies to fetched, cached and user-entered rates alike.
- **Conversion:** `effectMinor = HALF_EVEN(amountMinor × rate × 10^(fd(quote) − fd(base)))`, where `fd` is the currency's minor-unit digits. Everything before that single rounding is exact `BigDecimal` arithmetic (a multiplication and a power-of-ten scale shift). Example: 100 JPY (fd 0) at 0.006 EUR/JPY → 100 × 0.006 × 10² = 60 EUR minor units (0.60 EUR). A result above `MAX_MINOR` → `AmountTooLarge`. In expenses the converted total is split afterwards (§4.4).
- **Zero results:** a positive amount that converts to 0 minor units (e.g. 1 IDR at 0.000055 EUR/IDR) is rejected with `ConvertedToZero`; the user can enter the amount actually charged instead.
- **Bank actual override:** the user enters the amount actually charged (e.g. "87.65 EUR"). That amount **is** the effect amount; nothing is recomputed from the rate. The snapshot stores the derived rate (`charged / paid` in major units, `DECIMAL128`) with `source = BANK_ACTUAL`, for display.
- **Required effect currency**, computed from the draft on every save (create or edit), in this order:
  1. In a CONVERT group: the group's `defaultCurrency` (rule 8).
  2. Otherwise, for a transfer whose draft sets `settlesCurrency` (an explicit cross-currency settlement, §4.6): that currency.
  3. Otherwise: the paid currency.

  An edit draft of a transfer outside a CONVERT group loads `settlesCurrency` from the stored row (its effect currency, when that differs from the paid currency). So editing only the note of a €9 repayment that settled 100 SEK keeps its SEK effect and its snapshot. Transfer drafts in a CONVERT group load it empty, and it is ignored there.
- **Editing:** on every save, the required effect currency is recomputed from the (possibly changed) group, the group's `fxMode` and `defaultCurrency`, the draft's `settlesCurrency` and the paid currency. Then:
  - **No conversion needed any more** (effect currency = paid currency; e.g. moved to a KEEP group or out of a group, or `settlesCurrency` cleared or set to the paid currency): the snapshot is dropped and the effect equals the original.
  - **Same pair** (the existing snapshot's base and quote still match the paid and effect currency): the snapshot is kept unless the date or amount changed. If they changed, a fresh rate is proposed and the user may keep the old snapshot.
  - **Different pair** (paid currency changed, `settlesCurrency` changed, or the entry moved to a group with another effect currency): the old snapshot can't be kept; a new rate is required (lookup chain above, or bank actual).
  - Rule 14 enforces this on every write, so an entry can never keep a snapshot for the wrong pair.
- **Refresh:** a daily WorkManager job (network required) caches the latest rates for the home currency and the currencies in use. Historical rates are cached forever.
- **Where conversion applies:** in CONVERT groups, and for transfers whose draft sets `settlesCurrency` (explicit cross-currency settlements outside CONVERT groups, §4.6). Everything else in KEEP groups and outside groups keeps `effectCurrency = currency`, so balances stay per currency.

### 4.6 Settle up (`SettlePlanner`)

**Group settle plan** (per group, per currency; computed on demand, never stored):

1. Take the members with non-zero `groupNet`.
2. Greedy: repeatedly match the largest creditor with the largest debtor (ties go to the lowest person UUID) and emit a transfer of `min(credit, |debt|)` from debtor to creditor.
3. The result settles the group with at most n−1 debtor–creditor pairs per currency.
4. **Amount cap:** a pair whose amount exceeds `MAX_MINOR` (possible, since nets can sum many capped entries) is emitted as several transfers of at most `MAX_MINOR` each, in the same direction. When every net is within `MAX_MINOR`, this never happens and the plan has at most n−1 transfers.

- From a plan item the UI gets a pre-filled `REPAYMENT` draft tagged with the group. The amount is editable (partial settlement), and the paid currency is editable (cross-currency settlement through an FxSnapshot). In CONVERT groups the effect stays in the group currency (rule 8); in KEEP groups the draft sets `settlesCurrency` to the plan item's currency, so the effect lands in the currency of the net being settled (§4.5).
- Plan items that don't involve me can be recorded too: I am the group's bookkeeper until sync exists.

**Person settle-up** (outside groups): a pre-filled untagged `REPAYMENT` draft for `net(me, X)` per non-zero currency, in the direction that reduces the debt (split into several drafts of at most `MAX_MINOR` if the balance is larger). Cross-currency is allowed through an FxSnapshot: the draft sets `settlesCurrency` to the debt's currency B, the transfer moves currency A, and its effect is in B (§4.5). A transfer recorded by hand can set `settlesCurrency` the same way.

### 4.7 Invariants (property-tested)

- `Σ shares == effectTotalMinor` for every expense.
- `net(A, B, c) == −net(B, A, c)` for non-group pairs.
- Group member nets sum to 0 per group and currency.
- Recording every transfer of a settle plan brings all nets of that group and currency to 0, and every plan transfer is a valid amount (0 < amount ≤ `MAX_MINOR`). A plan has at most n−1 debtor–creditor pairs, and at most n−1 transfers when every net is within `MAX_MINOR`.
- Merge followed by unmerge of the same person, with no other writes in between, restores identical balances, memberships and canonical IDs, also when either person already had aliases of its own.
- Replaying `EntryRevision` (latest snapshot per entity) reproduces the current entity tables exactly.

---

## 5. Write path, revisions and undo

### 5.1 `LedgerWriter`

Each operation runs in one `db.withTransaction { }`, which writes the entity row(s) and appends one `EntryRevision` per changed entity.

| Operation | Row | Revision action |
|---|---|---|
| `create(entity)` | Insert, `version = 1` | `CREATED` |
| `update(entity, token)` | `version + 1`, `updatedAt = now` | `UPDATED` |
| `delete(id, token)` | Set `deletedAt`, `version + 1` | `DELETED` |
| `restore(id, token)` | Clear `deletedAt`, `version + 1` | `RESTORED` |

- **Ledger generation.** A UUID stored in the database pointer `db-state.json` (§7.1), not in any database. First launch assigns it, and every pointer commit (each restore and each undo of a restore) replaces it with a fresh one. So every switch of the live database gives a new generation, including an undo back to an earlier database and a second restore of the same backup.
- **Edit tokens and draft basis.** Every read model of an editable entity carries an opaque `EditToken` = (ledger generation, entity version), and every update, delete, restore, archive, merge and revert takes one. Every other `PeopleApi`, `GroupsApi` and `LedgerApi` write (creates, saves of settle-up drafts, member add, remove and re-add) carries a `basis`: the generation its draft was prepared against (§6.2). A token or basis from another generation → `LedgerReplaced`; a version mismatch → `StaleVersion`. Either way nothing is written.
- Every operation runs `LedgerValidator` (§3.4): the state invariants against the complete state the transaction would produce, and (except for import and undo import) the edit rules against the entities it changes.
- Writes for one logical action (an expense with its shares, a merge, a whole import) happen in a single transaction.
- Archive, merge and unmerge are `update`s with a descriptive reason.
- Imports use the same operations with `importBatchId` and an import reason. Incoming version numbers are never written (§7.4).

### 5.2 Deletion and archiving

- **Transfers and expenses:** soft delete with Undo (the UI shows it). Deleted entries remain viewable and restorable, subject to §3.4.
- **People and groups:** deletion only when unreferenced (rule 10); archiving is the normal path.
- **Owned rows cascade.** Deleting a group also deletes its non-deleted memberships (me's included), and deleting a person deletes their non-deleted payment handles, in the same transaction and with the **same `deletedAt`** as the owner (one revision per row). Restoring the owner restores, in one transaction, exactly the owned rows whose `deletedAt` equals the owner's, so the group and its memberships (or the person and their handles) come back together and the state invariants hold at once. Memberships and handles are not restored on their own through the `api`; `GroupsApi` and `PeopleApi` expose delete and restore for groups and people. **Every** deletion or restore of a group or person goes through this cascade, including those written by import (§7.4) and import undo (§5.4), so the shared `deletedAt` always identifies what was deleted together.
- **Archiving with an open balance is allowed.** The `api` returns the open balance so the UI can warn first. Archived people and groups keep counting in totals and stay in the overview with an "archived, unsettled" badge until their balance with me is 0 (§4.3).
- **Leaving a group = archiving it.** Me is never removed (rule 2).

### 5.3 History and revert to version

- Each entity's revisions, newest first: author, device, time, action, reason, and a field-level diff to the previous snapshot (`RevisionDiff`; shares diffed per person).
- **Revert to version N** = `update()` with the business fields of snapshot N (identity and sync columns stay current), reason "Reverted to vN". History is only ever added to.
- **A historical snapshot must satisfy current rules.** The revert runs the full validator and field validation against today's state. If it fails (e.g. the old version references a since-removed member, or an old Group revision would change a locked currency or FX mode), nothing is written and `RestoreConflict(violations)` is returned, listing each violated rule. The UI then offers **Open as draft**: an edit form pre-filled with snapshot N, saved as a normal update.
- An old snapshot's `fxSnapshotId` stays valid, because FX snapshots are immutable and never deleted.

### 5.4 Import undo

Undoing a batch, in one transaction:

- Rows **created** by the batch are deleted (rows the import created already deleted stay as they are).
- Rows **updated** by the batch (conflicts resolved as "take incoming", §7.4) are reverted to their pre-import snapshot, including their deleted state, but only if their current version is still the one the import wrote.
- **Order:** entries first; then memberships and payment handles the import created under a group or person it did **not** create, each deleted on its own; then groups and people. A group or person the import created is deleted with the §5.2 cascade, which takes its non-deleted memberships or handles along with the same `deletedAt`. Those owned rows are therefore never deleted on their own before their owner, and the owner can later be restored together with them. Reverting an updated group or person's deleted state also goes through the cascade: a delete cascades, and a restore brings back the owned rows deleted with it. FX snapshots the import created stay (they are immutable and harmless when unreferenced). The result is checked against the state invariants (§3.4). A row that was changed since the import, or whose undo would break an invariant (e.g. deleting an imported person that a kept entry still references), is skipped. The rest of the undo proceeds.
- **Undo is best-effort.** The result lists every skipped row with its reason. Balances return exactly to their pre-import values only when nothing was skipped; the UI says so when something was.
- Everything is recorded with reason "Undo import <file>"; `ImportBatch.undoneAt` is set.

### 5.5 Snapshot schema evolution

Each `snapshotJson` carries `schema: Int`. When a Room migration changes an entity, a matching upcaster in `data` converts old snapshots when they are read. Stored revisions are never rewritten.

### 5.6 Person merge and unmerge

Used now for duplicate contacts ("I created Alex twice"), and in phase 5 for linking a local contact to a real account or claiming a placeholder (§13).

- **`previewMerge(source, target)`:** the combined non-group balance, the combined group nets, the entries between the two that will stop having an effect (non-group debts, and group entries whose parties become one person), and the payment-handle defaults that will apply. Nothing is written.
- **`merge(source, target)`:** both must be canonical (rule 11). Sets `source.mergedIntoId = target` (revision reason "Merged into <target>"). **That is the only row written.** The source's own aliases keep pointing at the source and reach the target through resolution (§4.1).
- **History is never rewritten.** Entries and snapshots keep referencing the source ID; `IdentityResolver` attributes them to the target.
- **Collisions resolve at read time, with no extra writes:** memberships in the same group combine (canonical membership, §3.4); shares of both records in one expense add up; entries between the two records, including group transfers that become self-transfers, stay stored and have no effect (§4.1); payment-handle defaults follow §3.2. Merge cannot break a state invariant, so it never fails on collisions.
- **`unmerge(person)`:** clears that person's `mergedIntoId` (revision reason "Unmerged from <target>"), after validation (rule 12). The person splits off together with its own aliases. Because merge wrote nothing else, unmerging right after a merge restores the previous state exactly (§4.7). Entries created after the merge reference the target directly, so they stay with the target; a default handle set on the canonical person after the merge stays as set.
- In the MVP, me is never part of a merge (rule 9).

---

## 6. Core ↔ UI contract

### 6.1 Ownership

- **UI contributor:** everything in `ui`: screens, ViewModels, navigation, theming, string resources, mapping `LedgerError` to messages, `BiometricPrompt` and other Activity-bound APIs, UI tests. **Screen layouts and flows are theirs to design;** this spec deliberately contains none.
- **Project owner:** everything behind `api`, plus the fake.

### 6.2 API shape

- Pure Kotlin interfaces in `api`, grouped by area. Reads are `Flow`s of ready-to-render models; writes are `suspend` functions returning `LedgerResult<T>` (`Ok(value)` / `Err(LedgerError)`).
- Pure, synchronous helpers for live form feedback (e.g. split preview) need no I/O.
- Files are passed as `DocumentRef` (a value class wrapping a SAF URI string), so `api` stays Android-free.

| Interface | Covers |
|---|---|
| `SetupApi` | First-launch setup (create me with display name, home currency); setup state |
| `PeopleApi` | Observe people and person detail (non-group balance, "In groups" suggestions, history, payment handles); create / update / archive / delete / restore person; payment handle CRUD with IBAN validation; `previewMerge` / `merge` / `unmerge`; plain-text balance summary for sharing |
| `GroupsApi` | Observe groups and group detail (members with nets, settle plan, expenses, flows); create (me auto-added) / update / archive / delete / restore; add, remove, re-add member |
| `LedgerApi` | Observe overview (totals, mixed people/group list) and activity feed with filters; save expense and transfer drafts; `previewSplit(draft)`; settle-up drafts from a plan item or a person; delete / restore; observe entry detail with history and diffs; `revertTo(entityId, version)` |
| `FxApi` | Rate quote for a date and currency pair (value, source, date, stale flag); derive a rate from "amount actually charged" |
| `BackupApi` | Create a backup (encrypted or not); restore; undo last restore; observe the backup reminder (no manual backup for 30 days) |
| `ImportExportApi` | Export (shape, format, Excel-friendly flag, and a scope for reports); parse an import into a preview; commit with per-row conflict choices; observe import batches; undo an import |
| `SettingsApi` | Home currency; app-lock enabled and timeout; screen protection; backup encryption default; `observeLockRequired()` and `markUnlocked()` for the app-lock policy |

**Ledger replacement.** `BackupApi.observeLedgerGeneration()` emits the current ledger generation (§5.1) and emits again after every restore or undo-restore commits. Work prepared against the old ledger is invalid after that, create drafts included: a settle-up draft for €100 prepared before a restore must not be saved into a ledger where only €20 is outstanding. So every write input carries the generation it was prepared against (§5.1): edits inside their `EditToken`, everything else as a required `basis` field. Drafts the `api` builds (settle-up drafts from a plan item or a person) come with `basis` already set; for a blank create form, the UI sets `basis` from `observeLedgerGeneration()` when it opens the form. Writes from another generation and commits of import previews parsed before the restore return `LedgerReplaced`, and all read `Flow`s re-emit from the new database. The UI should drop open drafts and previews and return to the overview when the generation changes (how it tells the user is the UI contributor's call).

`LedgerError` is a sealed hierarchy covering every error in §4.4 and §8, each carrying the data a good message needs (e.g. `ExactRemaining(diff)`, `MemberHasBalance(nets)`, `RestoreConflict(violations)`, `NeedsGroup(people)`).

### 6.3 Fake implementation

- In-memory state with seeded demo data: me, five people, two groups (one CONVERT EUR with a SEK expense, one KEEP), a merged duplicate, a few revisions, an archived unsettled person.
- Uses the real `ledger.rules` and `ledger.engine`, so errors, balances and settle plans behave like the real thing.
- Backup, restore and import/export are simulated with canned success, failure and preview results.
- Delivered in Milestone 0, before any real persistence exists.

### 6.4 Contract changes

`api` is shared ground. Either side proposes changes as small PRs that touch `api` and the fake together; both contributors review them. The contract tests (§11) run against both implementations, so a change that the real implementation can't satisfy fails CI.

---

## 7. Import and export

### 7.1 JSON backup and restore

- **Content:** all Room tables, including `EntryRevision`, `FxSnapshot`, `FxRateCache`, `ImportBatch` and `AppSetting` (§3.3); plus `schemaVersion`, app version and timestamp. Device-local settings and the device ID are not included.
- **Encrypted format** (default; toggle in Settings): header = magic `IOUB`, format version, Argon2id parameters (memory 64 MiB, iterations 3, parallelism 1), 16-byte salt, 12-byte nonce; body = AES-256-GCM over gzipped JSON. The passphrase is entered for every backup and restore and never stored.
- **Unencrypted format:** plain `.json`, with a warning when chosen.
- **Backup reminder:** when `lastManualBackupAt` is older than 30 days (or no backup has ever been made), `BackupApi` reports a non-blocking reminder. There are no scheduled automatic backups (they would need a stored passphrase).

**Database files.** Database files have generation names, `iou-<uuid>.db`, in the app's database directory. A small pointer file, `db-state.json` (`{ "current": "iou-<a>.db", "previous": "iou-<b>.db" | null, "generation": "<uuid>" }`), names the live database and the pre-restore copy and holds the ledger generation (§5.1); Room opens the one named `current`. The pointer is only ever replaced atomically: write `db-state.json.tmp`, fsync, rename it over `db-state.json`, fsync the directory. Every replacement writes a fresh `generation`, so the switch of database and the change of generation are one atomic step. First launch creates the first database and the pointer.

**Restore procedure** (replaces the whole ledger, after confirmation; all or nothing):

1. **Decrypt, parse and validate** the entire file before touching the live database. Newer `schemaVersion` → rejected; older → migrated in step 3.
2. **Mark:** write `restore-marker.json` (`{ "op": "restore", "target": "iou-<new>.db" }`) and fsync it. The marker only decides which message to show after a crash; correctness never depends on it.
3. **Build** `iou-<new>.db`, run migrations if needed, run the §4.7 invariant checks, then close it with a full WAL checkpoint so it is a single file (no `-wal`/`-shm`).
4. **Commit:** take the write lock (every `LedgerWriter` transaction holds it, so in-flight writes finish first and new ones wait), close the live database with a full WAL checkpoint, then replace the pointer with `{ current: new, previous: old current, generation: fresh UUID }`. **This atomic rename is the commit point.** Reopen, release the lock, and emit the new generation (§6.2). Writes that waited are then checked against the new generation, so their old tokens and bases fail with `LedgerReplaced`.
5. **Clean up:** delete the database file that was `previous` before the commit (only the latest pre-restore copy is kept), then delete the marker.
6. **On any failure before the commit point,** delete `iou-<new>.db` and the marker, and reopen the live database. The user sees "Restore failed, nothing changed".

**Undo last restore** writes a marker with `op = "undo"` and `target` = the current `previous`, then commits a pointer with `current` and `previous` swapped and a fresh `generation` (same close-and-commit as step 4). The restored database becomes the pre-restore copy, so an undo can itself be undone. Because the generation lives in the pointer, a token issued before the restore stays invalid after the undo, even though the entry it names is back at the same version.

**Crash recovery:** on every app start, *before* Room opens a database, `RestoreRecovery`:

1. Reads the pointer and deletes every `iou-*.db` (with its `-wal`/`-shm`) and any `db-state.json.tmp` that the pointer does not name. Process death before the commit point leaves the old database live plus an unreferenced new file, which is deleted. After the commit point the new database is live, and at most a stale old copy is left to delete. No other state can exist, so nothing is rolled back or inferred from which files are present.
2. If a marker exists: when the pointer's `current` equals the marker's `target`, the operation had committed and nothing is shown; otherwise the app shows "The last restore was interrupted, nothing changed" once. Then the marker is deleted.

- Ledger settings live in Room (§3.3), so the commit restores them atomically; no second store can partially fail.
- The me identity comes from the backup (a restore on a new phone keeps the same identity). The device ID stays the installation's own.
- **Pre-restore copy:** the database named `previous` is an ordinary database file with the same protection as the live one: app-private storage with file-based encryption. It is not passphrase-encrypted (no passphrase is available). Only the latest copy is kept.

### 7.2 CSV export

- **Scope:** the **normalized shape is always the full ledger**: every table, every row including soft-deleted ones and the me person, so a re-import never meets a dangling reference (merge targets, members, FX snapshots). The **report shape** can be scoped to the full ledger, one group, or one person, each with an optional `occurredOn` date range. A person scope contains the entries in which that person (canonically) is the payer, has a share, or is a transfer party, shown in full with the other participants and the entry totals; it is a report for my own use.
- **Normalized shape:** `.zip` with one CSV per table (`people` including `merged_into_id`, `payment_handles`, `groups`, `group_members`, `transfers`, `expenses`, `expense_shares`, `fx_snapshots`) plus `manifest.json` (app version, schema version, timestamp, scope, text escaping). Amounts are exported as `amount_minor` integers and decimal strings.
- **Report shape:** one row per entry: `date, type, description, group, paid_by, from, to, amount, currency, my_share, balance_effect_for_me, account, note`. Names are canonical. Not re-importable.
- **Format:** default UTF-8 without BOM, `,` delimiter, RFC 4180 quoting; "Excel-friendly" mode = UTF-8 with BOM and `;`. Dates as ISO 8601 (`occurredOn` as `2026-11-02`, instants in UTC with `Z`), `.` decimal separator.
- **Formula-injection protection (reversible):** a text value starting with `=`, `+`, `-`, `@`, tab, CR **or `'`** gets one `'` prefixed. Escaping a leading `'` too makes the rule lossless: on import of own exports, a value starting with `'` has exactly one leading `'` removed. So `=SUM(1)` → `'=SUM(1)` → `=SUM(1)`, and `'quoted` → `''quoted` → `'quoted`. `manifest.json` records `"textEscaping": "quote-prefix-v1"`. The report shape uses the same rule.

### 7.3 XLSX export (FastExcel)

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
- **Deleted owners keep their cascade.** Local timestamps are never copied from the file, but the file's `deletedAt` values decide which rows were deleted together. When the import deletes a group or person (a new deleted row, or "take incoming" for "active → deleted"), it first deletes on their own the owned rows that the file shows deleted at a *different* time than the owner. Then it deletes the owner with the §5.2 cascade, which gives its remaining owned rows, including those the file shows deleted at the owner's own time, the owner's local `deletedAt`. Restoring the owner later brings back exactly the memberships or handles that were deleted with it in the source. When the import restores an owner ("deleted → active"), the §5.2 restore cascade runs.
- **Expenses are classified with their shares.** Before classification, each incoming `expenses` row is assembled with its complete set of `expense_shares` rows into one unit, the same shape as an Expense revision snapshot. The unit is compared as a whole: a changed share (e.g. EXACT 50/50 → 25/75 with an unchanged total) makes the expense a conflict, and the diff shows shares per person (as `RevisionDiff` does). "Take incoming" replaces the expense and all its shares in one `update` with one revision. A share row whose expense is not in the file is invalid.
- **FX snapshots are immutable and have no versions.** They are classified by UUID on their own: unknown → inserted as is (no revision; snapshots have none); same UUID and identical fields → duplicate, skipped; same UUID with different fields → **invalid row** (the copies are corrupt, since snapshots never change), with no "take incoming". The local snapshot is kept. Every incoming entry that references a rejected snapshot and would be written (new, or a conflict) is **invalid too**, with the reason "references an FX snapshot that differs from the local copy": its stored effect was computed from the incoming rate, which the local snapshot no longer explains (local SEK→EUR 0.09 against an incoming expense of 1 000 SEK with a €100 effect), and a matching currency pair (rule 14) does not catch that. Like other invalid rows, they are skipped or fixed in the file; an incoming entry that is a duplicate of its local row is skipped as usual. Import undo never removes snapshots (§5.4).
- **Audit history stays complete locally.** Every imported entity's local history starts with a revision that records the file and source version it came from. Source-device history is not imported; only JSON restore (§7.1) carries full history.
- The import is checked against the **state invariants** of §3.4, not the edit rules (§3.4): it reproduces records rather than editing them. A group with removed members and their historical entries therefore imports as is, while anything that would leave an inconsistent state (e.g. a removed member with a non-zero net) shows up as invalid rows in the preview.
- **Identity mapping.** Own-export import is for *your own* data, so the source's me must become the local me:
  - **Same identity** (the source's `isMe` UUID equals the local me UUID, e.g. an install restored from your backup): imported as is.
  - **Different identity** (e.g. a second install that was set up fresh): the preview asks the user to confirm that this export is their own data from another install. On confirmation, every reference to the source me in incoming rows (payer, share rows, transfer parties, group members, `authorId`) is mapped to the local me while the rows are imported. The source me's Person row itself is not imported. Each affected entity's origin revision records the mapping ("Imported from <file> (source v<N>), source identity <uuid> mapped to me"). Existing local rows are never rewritten; mapping applies only to incoming data, before classification and validation.
  - **Declined:** the import is rejected with `ForeignLedger`. Importing another person's ledger is a sharing problem and belongs to phase 5.
  - After mapping, every non-group entry involves the local me and every imported group contains me, so rules 1–2 hold. Other people who exist under different UUIDs locally arrive as new people and can be merged afterwards (§5.6).
- Text values are unescaped per §7.2 when `manifest.json` declares `quote-prefix-v1`.
- Unknown `schemaVersion` in the manifest → rejected with a clear message.

### 7.5 Import pipeline

1. **Parse**, streaming, in a background coroutine.
2. **Map** columns using `manifest.json` and the CSV headers, then apply identity mapping (§7.4).
3. **Validate** each row: required fields, amounts (`BigDecimal`, never `Double`; too many decimals or above `MAX_MINOR` → invalid), FX rates, dates, known currency codes, shares sum, IBAN checksum. Then check the §3.4 state invariants over the complete state the import would produce, and attribute each violation to the rows that cause it.
4. **Classify** by UUID as new / duplicate / conflict (§7.4).
5. **Preview:** counts of new / conflicting / duplicate / invalid rows, conflicts with field diffs and a per-row choice, invalid rows with row number and reason, the resulting balance changes. Invalid rows must be fixed in the file or explicitly skipped.
6. **Commit** in one transaction through `LedgerWriter`, applying creates and updates in dependency order (people, payment handles, groups, memberships, FX snapshots, entries), then the deletions: entries first, then owned rows deleted on their own, then groups and people with their cascade (§7.4); create an `ImportBatch`, tag the rows. The state invariants are checked once, on the final state.

**Limits:** 20 MB per file, 100 000 rows; for zips, at most 50 entries, at most 200 MB total uncompressed, compression ratio at most 100.

Files are read and written through the Storage Access Framework or the share sheet. No storage permission is needed.

---

## 8. Error handling

All failures come back as typed `LedgerError`s that the UI can display. No partial writes.

| Area | Errors | Behaviour |
|---|---|---|
| Money / validation | `InvalidAmount`, `TooManyDecimals`, `AmountTooLarge`, `UnknownCurrency`, `InvalidRate`, `ConvertedToZero`, `InvalidIban`, split errors (§4.4) | Returned as results; save is not possible |
| Integrity (§3.4) | `NeedsGroup`, `NotGroupMember`, `MemberHasBalance`, `MemberRemoved`, `GroupSettingsLocked`, `CannotModifyMe`, `StillReferenced`, `DeletedReference`, `InvalidMerge`, `SelfTransfer` | Transaction rolled back; error carries the data for a suggested action (create group, settle, re-add member, archive) |
| Concurrency | `StaleVersion`, `LedgerReplaced` | Nothing written; reload (after `LedgerReplaced`, drop the draft or preview, §6.2) |
| FX consistency / data structure | `FxSnapshotMismatch`, `CorruptData` | Nothing written; the first comes from an edit that keeps a snapshot for the wrong pair, the second only from import (shown as invalid rows) |
| Revert to version | `RestoreConflict(violations)` | Nothing written; list of violated rules; "Open as draft" |
| FX | Network failure, unsupported currency | Fallback chain §4.5; worst case the user enters a rate manually. Never blocks entry |
| Rate refresh worker | Any | WorkManager exponential backoff; silent |
| Restore / import (fatal) | Corrupt file, limits exceeded, schema too new, GCM tag failure ("Wrong passphrase or damaged file"), `ForeignLedger` (identity mapping declined), failure or process death during a restore or undo | Before the commit point: nothing changes and the new file is discarded. After it: the operation is complete. Never a mix (§7.1) |
| Import (per row) | Validation failures | Shown in the preview |

No third-party crash reporting.

---

## 9. Security

- **App lock:** `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (available as one API from minSdk 30). The core's lock policy (`SettingsApi.observeLockRequired()`) requires unlock on cold start and after a configurable time in the background (immediately, 1, 5, 15 min; default 1 min), tracked with `ProcessLifecycleOwner`. The UI shows a lock screen covering all content until `markUnlocked()`. App lock can only be enabled if the device has a secure lock screen.
- **Screen protection:** a toggle (default **off**) that sets `FLAG_SECURE` on the single Activity.
- **Backup encryption:** §7.1; default on. Passphrase at least 8 characters, entered twice, with a clear "cannot be recovered" warning.
- **Android Auto Backup** (system cloud backup, separate from manual JSON backups): enabled **only for the ledger databases** (the `iou-*.db` files, which includes the pre-restore copy) and their pointer `db-state.json`, through `dataExtractionRules` and `fullBackupContent`. It is end-to-end encrypted by Android on devices with a screen lock. Excluded: DataStore (device-local settings, §3.3), `noBackupFilesDir` (device ID), `db-state.json.tmp` and `restore-marker.json`. Any unreferenced database file that arrives with a system restore is deleted by `RestoreRecovery` at the next start. When Auto Backup restores the database onto a new phone, the me identity comes with it, the device gets a new device ID, and device-local settings start at their defaults.
- **Permissions:** no runtime (dangerous) permissions. Install-time normal permissions only: `INTERNET` (declared by the app), `USE_BIOMETRIC` (merged from `androidx.biometric`), and the permissions WorkManager merges for scheduling (e.g. `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`). The merged manifest is reviewed before each release (§14), and unexpected entries are removed with `tools:node="remove"` where safe.
- **Network:** cleartext traffic disabled. Release builds never log amounts, names or payment handles.

---

## 10. Distribution

Signed APKs on GitHub Releases (§2.4); friends install by sideloading, optionally tracking updates with Obtainium. Play Store distribution is revisited when phase 3+ brings in users beyond the friend group.

---

## 11. Testing

- **JVM unit tests:** `Money` (parse/format/arithmetic, currencies with 0 and 3 decimals, `MAX_MINOR` and overflow → `AmountTooLarge`), `SplitResolver` (including non-terminating fractions and maximal weights), `FxConverter` (differing minor digits, e.g. 100 JPY at 0.006 → 60 EUR minor units; `ConvertedToZero`; `InvalidRate`; inverted rates), `IdentityResolver` (alias trees, handle defaults), `LedgerEngine`, `SettlePlanner`, `LedgerValidator` (every rule, positive and negative), IBAN mod-97, `RevisionDiff`, snapshot upcasters, CSV escaping and unescaping, import classification, backup encrypt/decrypt and wrong-passphrase handling.
- **Kotest property tests:** the invariants of §4.7; normalized CSV export → wipe → import yields identical balances, IDs, business fields and deleted states (including arbitrary text with leading `=`, `+`, `-`, `@`, `'`, soft-deleted entries, merged people, and removed members with historical entries); JSON backup → restore yields an identical database.
- **`api` contract tests:** one abstract test suite per `*Api` interface, run against both the fake and the real implementation (the real one on an in-memory Room database). It keeps the fake honest and protects the UI contributor from drift.
- **Room tests (in-memory):** `LedgerWriter` atomicity (a failure mid-write leaves no row and no revision), `StaleVersion` and `LedgerReplaced`, group and person delete/restore cascades (also when written by import of deleted owners and by import undo), import undo (complete and with skipped rows), merge and unmerge with nested aliases, migrations with `MigrationTestHelper`.
- **Restore tests (file system):** process death injected at every step of a restore and of an undo, followed by `RestoreRecovery`.
- **UI tests:** defined by the UI contributor.
- **CI:** §2.4, including a check that fails if `Double` or `Float` appears in `money`, `ledger`, `api`, `data` or `io` sources.
- **Manual checklist** (`docs/manual-test-checklist.md`): app lock on a real device, `FLAG_SECURE`, exported CSV (both modes) and XLSX opened in Excel and LibreOffice, signed release APK installs and upgrades over the previous release.

---

## 12. Milestones and acceptance criteria

### Milestone 0: Skeleton and UI contract

**Goal:** the UI contributor can start building real screens within about the first week.

**Scope:** project skeleton (`:app`, packages, Hilt, `fake`/`real` flavors, applicationId); CI workflow including tag-based signed releases; `money`; `ledger.model`; `ledger.rules` and `ledger.engine` (enough for the fake: splits, identity resolution, balances, settle plans, validator); the complete `api` package with `LedgerError`; the in-memory fake with seed data; contract test suites running against the fake.

**Acceptance criteria**

1. `fakeDebug` builds and runs; every `*Api` returns seeded data, and writes update all observers.
2. The fake rejects invalid writes with the same `LedgerError`s the spec defines (e.g. a 3-person ad-hoc expense → `NeedsGroup`).
3. CI is green on push; a `v0.0.1` tag produces a signed APK on a GitHub Release.
4. The `api` package has no Android imports and no `Double`/`Float` in money paths (CI-checked).

### Milestone 1: Personal ledger

**Scope:** Room schema and real `api` implementation for setup, people, payment handles, transfers and ad-hoc two-person expenses; `LedgerWriter` with revisions, history and revert; person merge/unmerge; FX service and rate cache; JSON backup/restore (encrypted by default) with reminder; CSV export (normalized and report) and own-export import for people, payment handles, transfers, expenses and FX snapshots; app-lock policy; screen protection.

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
14. The M0 contract tests for setup, people, the non-group ledger, FX, backup, import/export and settings pass against the real implementation. Contract tests that need groups are tagged `groups` and join the real run in M2; until then the real flavor's `GroupsApi` emits no groups and rejects writes.
15. After a restore commits, `observeLedgerGeneration()` emits a new value. Saving an edit draft loaded before the restore returns `LedgerReplaced` and writes nothing, even when the restored entry has the same version number; saving a settle-up or other create draft prepared before the restore, and committing an import preview parsed before it, do the same. After "Undo last restore", the generation changes again, and a token from before the restore is still rejected with `LedgerReplaced`, although the entry is back at that version.
16. An import file containing two deleted people whose `mergedIntoId`s point at each other is rejected with those rows invalid (`CorruptData`).
17. Changing a converted transfer's paid currency while trying to keep its old snapshot is rejected (`FxSnapshotMismatch`); the user must take a new rate.
18. X owes me 100 SEK. A 9.00 EUR repayment from X, recorded from the person settle-up draft with `settlesCurrency` SEK and a rate giving a 100 SEK effect, brings the SEK balance to 0 and leaves the EUR balance unchanged. Editing only its note keeps the snapshot and the SEK effect; clearing `settlesCurrency` drops the snapshot and turns it into a 9.00 EUR effect.

### Milestone 2: Groups and splits

**Scope:** groups and members (me auto-added); group expenses with all four split types; CONVERT and KEEP FX modes; member nets, flows and settle plans; group-tagged settle-up; archiving with open balances; CSV export and import extended to groups; XLSX export; import undo.

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
14. An import file with one invalid row shows that row and the reason in the preview and commits nothing until it is fixed or skipped. Undoing an import in which no row was changed afterwards removes exactly the rows it created, reverts the rows it updated (including their deleted state), and restores the previous balances; this includes an import that created a person together with transfers involving them. If an imported row was changed after the import, undo skips it (and any row that still depends on it), lists each skipped row with its reason, and undoes the rest.
15. XLSX report and normalized workbooks open in Excel and LibreOffice with correct numbers and dates.
16. A full export of a ledger containing a group with a removed member and that member's historical expenses imports into an empty install without errors and reproduces every net.
17. Merging two people who both belong to a group, each with a default IBAN and with a group transfer between them, succeeds: the transfer has no effect, the canonical person has one default IBAN (§3.2), and unmerging restores both people's nets.
18. All `api` contract tests, including those tagged `groups`, pass against the real implementation.
19. Deleting an empty group deletes it with its memberships (me's included); restoring it brings back the group and those memberships together. After undoing an import that created a group, restoring that group works the same way. Importing an export that contains a deleted group with its memberships (deleted together) into an empty install creates them with one shared local `deletedAt`, so restoring the group brings its memberships back with it.
20. Importing an export in which an EXACT expense's shares changed from 50.00/50.00 to 25.00/75.00 (same total) shows a conflict with a per-person share diff; "take incoming" writes one `UPDATED` revision with the new shares. An FX snapshot whose UUID exists locally with a different rate is shown as invalid and is never overwritten, and every new or conflicting incoming entry that references it is shown as invalid too.
21. Moving a SEK expense from a CONVERT EUR group to a CONVERT USD group requires a new SEK→USD rate; moving it to a KEEP group drops the snapshot and keeps SEK effects.

---

## 13. Extension points (not built now)

| Future item | Prepared by |
|---|---|
| Phase 3 pay/request helpers (EPC QR, Revolut links, bank launch) | `PaymentHandle` with validated IBAN, holder name, default flag |
| Phase 4 auto-detected repayments | Confirmed suggestions become `LedgerWriter.create` calls; add `Transfer.bankTxnRef` then |
| Phase 5 sync: identity | The me UUID is the account ID. **Linking** a local contact to a real account = merging it into a person record carrying that account's identity (§5.6). **Claiming** a placeholder in a shared group = the same merge, done by its real owner. No ID translation layer |
| Phase 5 sync: data | UUIDs, versions, tombstones, `authorId`, `deviceId`, `StaleVersion` conflicts; "leave a shared group" becomes unsubscribing, separate from ledger membership |
| C1 event-sourced ledger | `EntryRevision` already holds full snapshots per write and can become the source of truth |
| C3 signed / hash-chained entries | Add signature and previous-hash columns to `EntryRevision`; device keys tied to `deviceId` |
| C2 counterparty confirmation | Add an entry `status`; the engine filters by it |
| B1 optimal debt simplification | Replace or extend the greedy `SettlePlanner` (zero-sum subgroups) |
| Multiple payers per expense | Add `ExpensePayer` rows; effects generalize to payer shares |
| Custom categories, analytics | Category enum → table when D2/D3 arrive |
| Splitwise import/export, generic CSV/XLSX import wizard | `Importer` interface and shared pipeline (§7.5) |
| Play Store | applicationId fixed from day one; signing key under owner control |
| E1 iOS via KMP | `money`, `ledger.*`, `api` and `fake` have no Android imports |
| F encrypted local DB | Room access is isolated in `data` |

---

## 14. Open items to verify while building

1. Confirm the FastExcel writer runs on Android (core library desugaring if needed).
2. Confirm Frankfurter query parameter names (`from`/`to` vs `base`/`symbols`) against the current docs.
3. Confirm BouncyCastle Argon2id performance at 64 MiB on a low-end device; lower the memory parameter if a backup takes more than about 2 s.
4. Inspect the merged release manifest: confirm the exact permissions that `androidx.biometric` and WorkManager contribute, and remove any the app doesn't need.
5. Confirm that `dataExtractionRules` (API 31+) and `fullBackupContent` (API 30) both exclude everything listed in §9, since minSdk 30 needs both.
