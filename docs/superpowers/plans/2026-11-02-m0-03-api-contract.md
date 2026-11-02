# M0 Part 3: The `api` Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-02-m0-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** The complete core ↔ UI contract: the eight `*Api` interfaces, every read model, draft and input type they use, and a guide for the UI contributor. After this part the friend can design screens and ViewModels against real types.

**Architecture:** Pure Kotlin in package `api` (no Android imports). Reads are `Flow`s of ready-to-render models; writes are `suspend` functions returning `LedgerResult<T>`. Every edit carries an `EditToken`; every other write carries a `basis` generation (spec §5.1, §6.2). The implementations come in Part 7 (fake) and M1 (real).

**Tech Stack:** Kotlin, `kotlinx.coroutines.flow.Flow`, `java.time`.

## Global Constraints

- `api` imports only `money`, `ledger.model`, `java.*` and `kotlinx.coroutines.flow` (index, locked decision 2). No Android imports, no `Double`/`Float` (spec §2.1, checked by `DomainPurityTest`).
- Reads are `Flow`s of ready-to-render models; writes are `suspend` functions returning `LedgerResult<T>`; files are passed as `DocumentRef` (a value class wrapping a SAF URI string) (spec §6.2).
- Every read model of an editable entity carries an `EditToken` = (ledger generation, entity version); every update, delete, restore, archive, merge and revert takes one. Every other `PeopleApi`, `GroupsApi` and `LedgerApi` write carries a `basis` generation (spec §5.1). Drafts the `api` builds (settle-up) come with `basis` set (spec §6.2).
- `BackupApi.observeLedgerGeneration()` emits the current generation and again after every restore or undo-restore (spec §6.2).
- Interface coverage exactly as spec §6.2's table: `SetupApi`, `PeopleApi`, `GroupsApi`, `LedgerApi`, `FxApi`, `BackupApi`, `ImportExportApi`, `SettingsApi`.
- Sign conventions in read models: a person balance is `net(me, X)`, positive = X owes me; a group net is `groupNet(M)`, positive = the group owes M (spec §3.1).
- Changes to `api` after this part go through small PRs that touch `api` and the fake together, reviewed by both contributors (spec §6.4).

## Design notes (decisions this part makes)

- **One save call per entry kind** with a `SaveMode`: `Create(basis)` or `Update(id, token)`. Edit forms start from `EntryDetail.editDraft`, which the core pre-fills exactly as spec §4.4/§4.5 require (canonical participants combined, `settlesCurrency` loaded, `fx = KeepExisting` when the entry has a snapshot).
- **FX is an explicit choice** on the draft: `FxChoice.Rate(quote)` (a looked-up or user-entered rate from `FxApi`), `FxChoice.BankActual(charged)`, or `FxChoice.KeepExisting`. When a conversion is required and the draft has no usable choice, the save returns `RateRequired(from, to)`; the UI then asks `FxApi` for a quote.
- **Plan items and details carry their generation**, so `settleUpFromPlan(item)` and `settleUpWithPerson(id, basis)` can reject stale input with `LedgerReplaced` (spec §6.2).
- **`previewSplit` is `suspend`** (the spec calls it synchronous): it needs the group's FX mode and, for edits, the stored shares. It does no file or network I/O.
- **Import previews are immutable values.** `applyChoices(preview, choices)` returns the preview with forced cascade outcomes recomputed (spec §7.4 "changing the owner's choice updates the owned rows at once"); `commitImport` takes the preview and the choices.
- **Archiving** is `set…Archived(id, archived, token)`; the UI warns from the balances it already shows (spec §5.2). Unarchiving is allowed.
- **`observePerson(id)` with an alias ID** returns the canonical person's detail (`person.id` is the canonical ID).

## File map

All under `android/app/src/main/kotlin/io/github/codex07/iou/api/`:

| File | Contents |
|---|---|
| `Common.kt` | `EditToken`, `DocumentRef`, `PersonRef`, `GroupRef`, `EntityRef`, `SaveMode`, `FxQuote`, `FxChoice`, `Inputs` |
| `Drafts.kt` | `PersonDraft`, `HandleDraft`, `GroupDraft`, `EntryDraft`, `TransferDraft`, `ExpenseDraft`, `SettleUpDraft` |
| `ReadModels.kt` | Overview, people, groups, activity, entry detail, history, previews |
| `TransferModels.kt` | Backup, import/export and settings models |
| `SetupApi.kt`, `PeopleApi.kt`, `GroupsApi.kt`, `LedgerApi.kt`, `FxApi.kt`, `BackupApi.kt`, `ImportExportApi.kt`, `SettingsApi.kt` | One interface each |
| `Apis.kt` | `Apis`: all eight in one value (contract tests, previews) |

Plus `docs/ui-contributor-guide.md`. Test: `android/app/src/test/kotlin/io/github/codex07/iou/api/InputsTest.kt`.

---

### Task 1: Common types, drafts and input helpers

**Files:**
- Create: `api/Common.kt`, `api/Drafts.kt`
- Test: `test/.../api/InputsTest.kt`

**Interfaces:**
- Consumes: `LedgerResult`, `LedgerError`, `toLedgerResult` (Part 2), `MoneyParser`, `CurrencyCode` (Part 1), model IDs and enums (Part 2).
- Produces: the types in the code below, used by every interface and by `ledger.ops` (Part 6) and `ledger.views` (Part 7). `Inputs.amount(text, currency)` and `Inputs.currency(text)` are the UI's parsing helpers.

- [ ] **Step 1: Write the failing test**

`test/.../api/InputsTest.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InputsTest {

    private val eur = CurrencyCode("EUR")

    @Test
    fun `amount parses through MoneyParser and maps its errors`() {
        Inputs.amount("12,50", eur) shouldBe LedgerResult.Ok(Money(1250, eur))
        Inputs.amount("1.005", eur) shouldBe LedgerResult.Err(LedgerError.TooManyDecimals(2))
        Inputs.amount("ten", eur) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
    }

    @Test
    fun `currency accepts lenient input and names unknown codes`() {
        Inputs.currency(" sek") shouldBe LedgerResult.Ok(CurrencyCode("SEK"))
        Inputs.currency("ZZZ ") shouldBe LedgerResult.Err(LedgerError.UnknownCurrency("ZZZ"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.api.InputsTest"
```

Expected: compilation fails, `Unresolved reference 'Inputs'`.

- [ ] **Step 3: Write `Common.kt` and `Drafts.kt`**

`api/Common.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyParser
import java.time.LocalDate

/**
 * Proof of what an edit was based on: the ledger generation and the entity version it was read at.
 * A token from another generation → `LedgerReplaced`; a version mismatch → `StaleVersion` (spec §5.1).
 */
data class EditToken(val generation: LedgerGeneration, val version: Int)

/** A file chosen through the Storage Access Framework, as a URI string (spec §6.2). */
@JvmInline
value class DocumentRef(val uri: String)

data class PersonRef(val id: PersonId, val displayName: String, val isMe: Boolean = false)

data class GroupRef(val id: GroupId, val name: String)

data class EntityRef(val type: EntityType, val id: String)

/** How an entry draft is saved: as a new entry prepared against [Create.basis], or as an edit. */
sealed interface SaveMode {
    data class Create(val basis: LedgerGeneration) : SaveMode
    data class Update(val id: EntryId, val token: EditToken) : SaveMode
}

/** A rate from [FxApi]: [rate] quote units per one [base] unit, valid for [rateDate] (spec §4.5). */
data class FxQuote(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean,
)

/** How a draft converts its paid amount when the effect currency differs (spec §4.5). */
sealed interface FxChoice {
    /** A looked-up (`ECB_FRANKFURTER`) or user-entered (`USER_ENTERED`) rate. */
    data class Rate(val quote: FxQuote) : FxChoice

    /** The amount actually charged, in the effect currency; it becomes the effect as is. */
    data class BankActual(val charged: Money) : FxChoice

    /** Keep the entry's current snapshot (edits only). */
    data object KeepExisting : FxChoice
}

/** Pure parsing helpers for form fields. */
object Inputs {
    fun amount(text: String, currency: CurrencyCode): LedgerResult<Money> =
        MoneyParser.parse(text, currency).toLedgerResult()

    fun currency(text: String): LedgerResult<CurrencyCode> =
        CurrencyCode.parse(text)?.let { LedgerResult.Ok(it) }
            ?: LedgerResult.Err(LedgerError.UnknownCurrency(text.trim()))
}
```

`api/Drafts.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

data class PersonDraft(
    val displayName: String,
    val note: String? = null,
    val defaultCurrency: CurrencyCode? = null,
)

/**
 * IBAN: any case, spaces allowed (stored uppercase without spaces, mod-97 checked); Revtag: with or
 * without `@` (stored without); phone: E.164 (`+` and 8–15 digits).
 */
data class HandleDraft(
    val type: HandleType,
    val value: String,
    val label: String,
    val holderName: String? = null,
    val bic: String? = null,
    val isDefault: Boolean = false,
)

data class GroupDraft(
    val name: String,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode = FxMode.CONVERT,
)

sealed interface EntryDraft

/**
 * A transfer to create or save. [settlesCurrency] makes an explicit cross-currency settlement outside
 * CONVERT groups: the effect lands in that currency (spec §4.5, §4.6). [fx] is needed whenever the
 * effect currency differs from [amount]'s currency.
 */
data class TransferDraft(
    val from: PersonId,
    val to: PersonId,
    val amount: Money,
    val kind: TransferKind = TransferKind.LOAN,
    val method: TransferMethod = TransferMethod.BANK,
    val accountLabel: String? = null,
    val groupId: GroupId? = null,
    val occurredOn: LocalDate,
    val note: String? = null,
    val settlesCurrency: CurrencyCode? = null,
    val fx: FxChoice? = null,
) : EntryDraft

/**
 * An expense to create or save. [participants] carry the split inputs (see [SplitInput]); EXACT inputs
 * are in [total]'s currency. [fx] is needed in a CONVERT group when [total]'s currency differs from the
 * group currency.
 */
data class ExpenseDraft(
    val payer: PersonId,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val occurredOn: LocalDate,
    val note: String? = null,
    val fx: FxChoice? = null,
) : EntryDraft

/** A pre-filled repayment built by the core, with the generation it was prepared against (spec §4.6). */
data class SettleUpDraft(
    val draft: TransferDraft,
    val basis: LedgerGeneration,
)
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.api.InputsTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/api/Common.kt android/app/src/main/kotlin/io/github/codex07/iou/api/Drafts.kt android/app/src/test/kotlin/io/github/codex07/iou/api/InputsTest.kt
git commit -m "Add the api's common types, drafts and input helpers"
```

---

### Task 2: Read models and transfer/settings models

**Files:**
- Create: `api/ReadModels.kt`, `api/TransferModels.kt`

**Interfaces:**
- Consumes: Task 1 types, model IDs and enums.
- Produces: every type the interfaces of Task 3 return. Field meanings are in the KDoc; Part 7 (`ledger.views`) fills them.

- [ ] **Step 1: Write `ReadModels.kt`**

`api/ReadModels.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

// ---- Overview (spec §4.3) ----

/**
 * Totals are per currency and always positive magnitudes. The approximate home-currency totals use the
 * latest cached rates, are null when a rate is missing, and must be labelled approximate.
 */
data class Overview(
    val generation: LedgerGeneration,
    val homeCurrency: CurrencyCode,
    val owedToMe: List<Money>,
    val iOwe: List<Money>,
    val approxOwedToMe: Money?,
    val approxIOwe: Money?,
    /** Ordered by absolute approximate home-currency amount, descending. */
    val rows: List<OverviewRow>,
)

sealed interface OverviewRow {
    val archived: Boolean

    /** Absolute approximate value in the home currency; null when a rate is missing. */
    val approxHome: Money?

    /** [balances] = `net(me, X)` per currency: positive means X owes me. */
    data class PersonRow(
        val person: PersonRef,
        val balances: List<Money>,
        override val archived: Boolean,
        override val approxHome: Money?,
    ) : OverviewRow

    /** [myNets] = `groupNet(me)` per currency: positive means the group owes me. */
    data class GroupRow(
        val group: GroupRef,
        val myNets: List<Money>,
        override val archived: Boolean,
        override val approxHome: Money?,
    ) : OverviewRow
}

// ---- People ----

data class PersonSummary(
    val person: PersonRef,
    /** `net(me, X)` per currency, non-group only. */
    val balances: List<Money>,
    val archived: Boolean,
    /** Number of other person records merged into this one. */
    val aliasCount: Int,
)

data class PersonDetail(
    val generation: LedgerGeneration,
    val person: PersonRef,
    val note: String?,
    val defaultCurrency: CurrencyCode?,
    val archived: Boolean,
    val deleted: Boolean,
    val token: EditToken,
    /** Records merged into this person (directly or through other aliases). */
    val aliases: List<PersonRef>,
    /** `net(me, X)` per currency, non-group only. */
    val balances: List<Money>,
    /** Per group, the current settle-plan transfers between me and this person: suggestions only. */
    val inGroups: List<GroupSuggestion>,
    val handles: List<PaymentHandleView>,
    /** Entries involving this person, newest first. */
    val activity: List<ActivityItem>,
    /** Revisions of the person record, newest first. */
    val history: List<RevisionView>,
)

data class GroupSuggestion(val group: GroupRef, val items: List<PlanItem>)

data class PaymentHandleView(
    val id: HandleId,
    /** The record that owns the handle (may be an alias of the person shown). */
    val owner: PersonRef,
    val type: HandleType,
    val value: String,
    val label: String,
    val holderName: String?,
    val bic: String?,
    /** True for the canonical person's default of this type (spec §3.2). */
    val isDefault: Boolean,
    val token: EditToken,
)

/** What a merge would do; nothing is written (spec §5.6). */
data class MergePreview(
    val source: PersonRef,
    val target: PersonRef,
    val combinedBalances: List<Money>,
    val combinedGroupNets: List<GroupNetView>,
    /** Entries between the two that stop having an effect. */
    val neutralizedEntries: List<ActivityItem>,
    /** The defaults per type that will apply after the merge. */
    val defaultHandles: List<PaymentHandleView>,
)

data class GroupNetView(val group: GroupRef, val nets: List<Money>)

// ---- Groups ----

data class GroupSummary(
    val group: GroupRef,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode,
    val archived: Boolean,
    /** `groupNet(me)` per currency. */
    val myNets: List<Money>,
    val activeMemberCount: Int,
)

data class GroupDetail(
    val generation: LedgerGeneration,
    val group: GroupRef,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode,
    val archived: Boolean,
    val deleted: Boolean,
    /** True once the group has any entry, deleted ones included (rules 6–7). */
    val settingsLocked: Boolean,
    val token: EditToken,
    val members: List<MemberView>,
    val settlePlan: List<PlanItem>,
    val activity: List<ActivityItem>,
    val flows: List<FlowView>,
    val history: List<RevisionView>,
)

data class MemberView(
    val person: PersonRef,
    val active: Boolean,
    /** `groupNet(member)` per currency: positive means the group owes them. */
    val nets: List<Money>,
)

/** Gross "who paid for whom" within a group; informational only (spec §4.3). */
data class FlowView(val paidBy: PersonRef, val paidFor: PersonRef, val amount: Money)

/** One suggested transfer: [from] pays [to]. Built against [generation] (spec §4.6, §6.2). */
data class PlanItem(
    val generation: LedgerGeneration,
    val from: PersonRef,
    val to: PersonRef,
    val amount: Money,
    /** Null for a person settle-up outside groups. */
    val group: GroupRef?,
)

// ---- Activity and entries ----

enum class EntryKind { TRANSFER, EXPENSE }

data class ActivityItem(
    val entryId: EntryId,
    val kind: EntryKind,
    val occurredOn: LocalDate,
    /** Expense description; null for transfers. */
    val description: String?,
    val note: String?,
    /** Expense payer or transfer sender (canonical). */
    val paidBy: PersonRef,
    /** Transfer receiver (canonical); null for expenses. */
    val receiver: PersonRef?,
    /** Expense share holders (canonical, once each); empty for transfers. */
    val participants: List<PersonRef>,
    val transferKind: TransferKind?,
    val category: Category?,
    val group: GroupRef?,
    val paid: Money,
    val effect: Money,
    /**
     * How this entry moves my position: for a non-group entry, the change of `net(me, other)`; for a
     * group entry, the change of `groupNet(me)`. Null when it doesn't touch me.
     */
    val myBalanceEffect: Money?,
    val deleted: Boolean,
)

/** All fields are optional filters; the list is newest first (date, then creation time). */
data class ActivityFilter(
    val personId: PersonId? = null,
    val groupId: GroupId? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kinds: Set<EntryKind> = EntryKind.entries.toSet(),
    val includeDeleted: Boolean = false,
)

data class EntryDetail(
    val generation: LedgerGeneration,
    val item: ActivityItem,
    val token: EditToken,
    /** Expense shares as stored, per canonical person; empty for transfers. */
    val shares: List<ShareView>,
    val fx: FxSnapshotView?,
    val flows: List<FlowView>,
    /** Pre-filled draft for editing this entry (spec §4.4, §4.5). Save with `SaveMode.Update`. */
    val editDraft: EntryDraft,
    /** Revisions newest first, each with its diff to the previous one. */
    val history: List<RevisionView>,
)

data class ShareView(val person: PersonRef, val share: Money, val inputValue: Long?)

data class FxSnapshotView(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean,
    /** Bank-actual snapshots only: the paid and charged amounts the rate came from. */
    val paid: Money?,
    val charged: Money?,
)

data class RevisionView(
    val version: Int,
    val action: RevisionAction,
    val at: Instant,
    val author: PersonRef,
    val deviceId: DeviceId,
    val reason: String?,
    /** Field-level diff to the previous revision; shares diffed per person. */
    val changes: List<FieldChange>,
)

/** Shares a draft would get; [storedShares] is set when editing, so both can be shown (spec §4.4). */
data class SplitPreview(
    val effectTotal: Money,
    val shares: List<ShareView>,
    val storedShares: List<ShareView>?,
    /** False when saving would keep the stored shares unchanged. */
    val recomputed: Boolean,
)
```

- [ ] **Step 2: Write `TransferModels.kt`**

`api/TransferModels.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

// ---- Backup (spec §7.1) ----

/** [due] when no manual backup exists or the last one is older than 30 days. */
data class BackupReminder(val due: Boolean, val lastBackupAt: Instant?)

data class BackupInfo(val encrypted: Boolean, val createdAt: Instant)

// ---- Export (spec §7.2, §7.3) ----

enum class ExportShape { NORMALIZED, REPORT }

enum class ExportFormat { CSV, XLSX }

/** The normalized shape is always the full ledger; other scopes apply to the report shape only. */
sealed interface ExportScope {
    data object FullLedger : ExportScope
    data class Group(val id: GroupId) : ExportScope
    data class Person(val id: PersonId) : ExportScope
}

data class ExportRequest(
    val shape: ExportShape,
    val format: ExportFormat,
    val excelFriendly: Boolean = false,
    val scope: ExportScope = ExportScope.FullLedger,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

data class ExportInfo(val rowCount: Int)

// ---- Import (spec §7.4, §7.5) ----

data class ImportPreview(
    val id: String,
    val fileName: String,
    val generation: LedgerGeneration,
    val ledgerSeq: Long,
    val identity: ImportIdentity,
    val counts: ImportCounts,
    /** Conflicts and rows whose outcome a cascade forces, with field diffs. */
    val rows: List<ImportRow>,
    val invalidRows: List<InvalidRow>,
    val balanceChanges: List<BalanceChange>,
)

sealed interface ImportIdentity {
    data object Same : ImportIdentity

    /** Confirmed: the source's me is mapped to the local me. */
    data class Mapped(val sourceMe: PersonId) : ImportIdentity

    /** Different identity: call `confirmIdentity` before anything else is shown. */
    data class NeedsConfirmation(val sourceMe: PersonId) : ImportIdentity
}

data class ImportCounts(val new: Int, val conflicts: Int, val duplicates: Int, val invalid: Int)

data class ImportRowKey(val type: EntityType, val id: String)

data class ImportRow(
    val key: ImportRowKey,
    val label: String,
    /** Field diff, with the deleted state as a field ("active → deleted"). */
    val changes: List<FieldChange>,
    val choice: ImportRowChoice,
    /** Set by `refreshPreview` for rows that are new or changed since the previous preview. */
    val changedSinceLastPreview: Boolean,
)

sealed interface ImportRowChoice {
    /** The user picks keep local (default) or take incoming. */
    data object KeepOrTake : ImportRowChoice

    /** The owner ends deleted, so this owned row does too; no choice for its deleted state. */
    data class DeletedWith(val owner: String) : ImportRowChoice

    /** The import restores the owner, which restores this row with it. */
    data class RestoredWith(val owner: String) : ImportRowChoice
}

data class InvalidRow(val table: String, val rowNumber: Int, val reason: String, val error: LedgerError?)

/** Balance with a person (or my net in a group) before and after the import, per currency. */
data class BalanceChange(val counterpart: String, val before: List<Money>, val after: List<Money>)

data class ImportChoices(
    val takeIncoming: Set<ImportRowKey> = emptySet(),
    val skipInvalidRows: Boolean = false,
)

data class ImportBatchView(
    val id: ImportBatchId,
    val fileName: String,
    val importedAt: Instant,
    val rowCount: Int,
    val undoneAt: Instant?,
)

data class SkippedRow(val key: ImportRowKey, val reason: String)

/** Undo is best-effort; balances are exactly restored only when [complete] (spec §5.4). */
data class UndoImportResult(val skipped: List<SkippedRow>) {
    val complete: Boolean get() = skipped.isEmpty()
}

// ---- Settings (spec §3.3, §9) ----

enum class LockTimeout { IMMEDIATELY, ONE_MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES }

data class Settings(
    val homeCurrency: CurrencyCode,
    val appLockEnabled: Boolean,
    val lockTimeout: LockTimeout,
    val screenProtection: Boolean,
    val backupEncryptionDefault: Boolean,
)
```

- [ ] **Step 3: Compile and run the purity check**

```bash
./gradlew :app:compileFakeDebugKotlin :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/api/ReadModels.kt android/app/src/main/kotlin/io/github/codex07/iou/api/TransferModels.kt
git commit -m "Add the api read models and the backup, import/export and settings models"
```

---

### Task 3: The eight interfaces and `Apis`

**Files:**
- Create: `api/SetupApi.kt`, `api/PeopleApi.kt`, `api/GroupsApi.kt`, `api/LedgerApi.kt`, `api/FxApi.kt`, `api/BackupApi.kt`, `api/ImportExportApi.kt`, `api/SettingsApi.kt`, `api/Apis.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: the contract. Part 7 implements every method in the fake; M1 implements them for real. Each method's KDoc lists the errors it can return beyond the validator's integrity errors.

- [ ] **Step 1: Write the interfaces**

`api/SetupApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow

sealed interface SetupState {
    data object NeedsSetup : SetupState
    data class Ready(val me: PersonRef) : SetupState
}

/** First launch: create me (the permanent global identity) and pick the home currency (spec §3.1). */
interface SetupApi {
    fun observeSetupState(): Flow<SetupState>

    /** Errors: `InvalidField("displayName")`; `CannotModifyMe` if setup already happened. */
    suspend fun completeSetup(displayName: String, homeCurrency: CurrencyCode): LedgerResult<PersonId>
}
```

`api/PeopleApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PeopleApi {
    /** Canonical people other than me, by display name. Archived ones only when asked. */
    fun observePeople(includeArchived: Boolean = false): Flow<List<PersonSummary>>

    /** Detail of the canonical person [id] resolves to; null if unknown. */
    fun observePerson(id: PersonId): Flow<PersonDetail?>

    /** Errors: `InvalidField("displayName")`. */
    suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration): LedgerResult<PersonId>

    suspend fun updatePerson(id: PersonId, draft: PersonDraft, token: EditToken): LedgerResult<Unit>

    /** Allowed with an open balance (spec §5.2); me → `CannotModifyMe`. */
    suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken): LedgerResult<Unit>

    /** Deletes the person with their payment handles; referenced → `StillReferenced`; me → `CannotModifyMe`. */
    suspend fun deletePerson(id: PersonId, token: EditToken): LedgerResult<Unit>

    /** Restores the person with the handles deleted together with them. */
    suspend fun restorePerson(id: PersonId, token: EditToken): LedgerResult<Unit>

    /** Errors: `InvalidIban`, `InvalidField("value" | "label")`. A new default clears the old one of that type. */
    suspend fun addHandle(personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<HandleId>

    suspend fun updateHandle(id: HandleId, draft: HandleDraft, token: EditToken): LedgerResult<Unit>

    suspend fun deleteHandle(id: HandleId, token: EditToken): LedgerResult<Unit>

    /** Errors: `InvalidMerge`, `CannotModifyMe`, `NotFound`. Writes nothing. */
    suspend fun previewMerge(source: PersonId, target: PersonId): LedgerResult<MergePreview>

    /** Merges [source] into [target]; [sourceToken] is the source's token (the only row written, spec §5.6). */
    suspend fun merge(source: PersonId, target: PersonId, sourceToken: EditToken): LedgerResult<Unit>

    /** Splits [person] (with its own aliases) off again. Errors: `InvalidMerge(NOT_MERGED)`, rule errors. */
    suspend fun unmerge(person: PersonId, token: EditToken): LedgerResult<Unit>

    /** Plain text of my balance with this person, for sharing (e.g. "Alex owes you 30.00 EUR"). */
    suspend fun balanceSummaryText(id: PersonId): LedgerResult<String>
}
```

`api/GroupsApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface GroupsApi {
    fun observeGroups(includeArchived: Boolean = false): Flow<List<GroupSummary>>

    fun observeGroup(id: GroupId): Flow<GroupDetail?>

    /** Me is added automatically; [members] are the other people. Errors: `InvalidField("name")`. */
    suspend fun createGroup(draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<GroupId>

    /** Errors: `GroupSettingsLocked` when changing a locked `fxMode` or CONVERT currency (rules 6–7). */
    suspend fun updateGroup(id: GroupId, draft: GroupDraft, token: EditToken): LedgerResult<Unit>

    /** Archiving = leaving; allowed with an open balance (spec §5.2). */
    suspend fun setGroupArchived(id: GroupId, archived: Boolean, token: EditToken): LedgerResult<Unit>

    /** Deletes the group with its memberships; any entry → `StillReferenced`. */
    suspend fun deleteGroup(id: GroupId, token: EditToken): LedgerResult<Unit>

    suspend fun restoreGroup(id: GroupId, token: EditToken): LedgerResult<Unit>

    /** Adds the person, or re-adds a removed member (unlocking their entries, rule 5). */
    suspend fun addMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Unit>

    /** Errors: `CannotModifyMe`, `MemberHasBalance` (rule 4). */
    suspend fun removeMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Unit>
}
```

`api/LedgerApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface LedgerApi {
    fun observeOverview(): Flow<Overview>

    fun observeActivity(filter: ActivityFilter = ActivityFilter()): Flow<List<ActivityItem>>

    fun observeEntry(id: EntryId): Flow<EntryDetail?>

    /**
     * Creates or saves a transfer. Errors include `RateRequired` (conversion needed, no usable [TransferDraft.fx]),
     * `FxSnapshotMismatch` (kept snapshot no longer fits, rules 14/16), `SelfTransfer`, `NeedsGroup`,
     * `MemberRemoved`, `ConvertedToZero`, `AmountTooLarge`.
     */
    suspend fun saveTransfer(draft: TransferDraft, mode: SaveMode): LedgerResult<EntryId>

    /** Creates or saves an expense. Errors as [saveTransfer] plus the split errors (spec §4.4). */
    suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId>

    /** The shares saving [draft] would store; [editing] compares with the stored ones (spec §4.4). Writes nothing. */
    suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId? = null): LedgerResult<SplitPreview>

    /** A pre-filled repayment for a plan item. Stale [item] → `LedgerReplaced`. */
    suspend fun settleUpFromPlan(item: PlanItem): LedgerResult<SettleUpDraft>

    /** Pre-filled repayments for every non-zero currency of `net(me, X)` (several if above `MAX_MINOR`). */
    suspend fun settleUpWithPerson(id: PersonId, basis: LedgerGeneration): LedgerResult<List<SettleUpDraft>>

    suspend fun deleteEntry(id: EntryId, token: EditToken): LedgerResult<Unit>

    /** Errors: `DeletedReference`, `MemberRemoved`, and any rule the restored entry breaks. */
    suspend fun restoreEntry(id: EntryId, token: EditToken): LedgerResult<Unit>

    /**
     * Reverts any entity to the business fields of [version]. If today's rules reject it: `RestoreConflict`
     * listing each violated rule, nothing written (spec §5.3).
     */
    suspend fun revertTo(entity: EntityRef, version: Int, token: EditToken): LedgerResult<Unit>

    /** "Open as draft": an edit draft pre-filled from [version] of an entry, saved as a normal update. */
    suspend fun draftFromVersion(id: EntryId, version: Int): LedgerResult<EntryDraft>
}
```

`api/FxApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

interface FxApi {
    /**
     * A rate for [date] through the lookup chain (spec §4.5): cache, Frankfurter, latest cached (stale).
     * `RateRequired` when none is available: ask the user and use [userRate].
     */
    suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote>

    /** Validates a typed rate. Errors: `InvalidRate`. */
    fun userRate(date: LocalDate, from: CurrencyCode, to: CurrencyCode, rate: String): LedgerResult<FxQuote>

    /** The display rate behind "amount actually charged" (source `BANK_ACTUAL`). */
    fun rateFromCharged(date: LocalDate, paid: Money, charged: Money): LedgerResult<FxQuote>
}
```

`api/BackupApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.LedgerGeneration
import kotlinx.coroutines.flow.Flow

interface BackupApi {
    /** Emits the current generation, and again after every restore and undo of a restore (spec §6.2). */
    fun observeLedgerGeneration(): Flow<LedgerGeneration>

    fun observeBackupReminder(): Flow<BackupReminder>

    /** Null [passphrase] writes an unencrypted backup (the UI warns first). */
    suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo>

    /**
     * Replaces the whole ledger, all or nothing. Errors: `WrongPassphraseOrDamagedFile`, `CorruptFile`,
     * `SchemaTooNew`, `LimitsExceeded`.
     */
    suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit>

    fun observeUndoRestoreAvailable(): Flow<Boolean>

    suspend fun undoLastRestore(): LedgerResult<Unit>
}
```

`api/ImportExportApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.ImportBatchId
import kotlinx.coroutines.flow.Flow

interface ImportExportApi {
    /** Errors: `InvalidField("scope")` for a scoped normalized export. */
    suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo>

    /** Parses and validates; writes nothing. Errors: `CorruptFile`, `LimitsExceeded`, `SchemaTooNew`. */
    suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview>

    /** Answers `ImportIdentity.NeedsConfirmation`: accept → the full preview; decline → `ForeignLedger`. */
    suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview>

    /** The preview with forced owner/owned outcomes recomputed for [choices]. Pure. */
    fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview

    /** Rebuilds a stale preview against the current ledger, keeping still-valid choices. */
    suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview>

    /** Errors: `LedgerReplaced`, `ImportPreviewStale` (any write since the preview), invalid rows not skipped. */
    suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId>

    fun observeImportBatches(): Flow<List<ImportBatchView>>

    suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult>
}
```

`api/SettingsApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow

interface SettingsApi {
    fun observeSettings(): Flow<Settings>

    suspend fun setHomeCurrency(currency: CurrencyCode): LedgerResult<Unit>

    /** The UI checks for a secure lock screen before enabling (spec §9). */
    suspend fun setAppLockEnabled(enabled: Boolean): LedgerResult<Unit>

    suspend fun setLockTimeout(timeout: LockTimeout): LedgerResult<Unit>

    suspend fun setScreenProtection(enabled: Boolean): LedgerResult<Unit>

    suspend fun setBackupEncryptionDefault(enabled: Boolean): LedgerResult<Unit>

    /** True while the lock screen must cover all content (cold start, timeout in background). */
    fun observeLockRequired(): Flow<Boolean>

    /** Called by the UI after a successful `BiometricPrompt`. */
    fun markUnlocked()
}
```

`api/Apis.kt`:

```kotlin
package io.github.codex07.iou.api

/** All eight interfaces of one implementation; used by contract tests and Compose previews. */
data class Apis(
    val setup: SetupApi,
    val people: PeopleApi,
    val groups: GroupsApi,
    val ledger: LedgerApi,
    val fx: FxApi,
    val backup: BackupApi,
    val importExport: ImportExportApi,
    val settings: SettingsApi,
)
```

- [ ] **Step 2: Compile and run the purity check**

```bash
./gradlew :app:compileFakeDebugKotlin :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/api
git commit -m "Add the eight api interfaces"
```

---

### Task 4: UI contributor guide, then notify the friend

**Files:**
- Create: `docs/ui-contributor-guide.md`
- Modify: `README.md` (Docs section: add a link)

**Interfaces:**
- Consumes: the whole `api` package.
- Produces: the friend's entry point. Part 7 adds the "Running the fake" section.

- [ ] **Step 1: Write the guide**

`docs/ui-contributor-guide.md`:

````markdown
# UI contributor guide

You own everything in the `ui` package: screens, ViewModels, navigation, theming, strings, mapping
`LedgerError` to messages, `BiometricPrompt` and UI tests. The core owns everything behind the `api`
package. Screen layouts and flows are yours to design; the spec deliberately has none.

Spec: [`superpowers/specs/2026-11-02-iou-mvp-design.md`](superpowers/specs/2026-11-02-iou-mvp-design.md), especially §4 (how balances work), §6 (this contract) and §8 (errors).

## Where things are

- Contract: `android/app/src/main/kotlin/io/github/codex07/iou/api/`. Start with `LedgerApi.kt`, `ReadModels.kt` and `Drafts.kt`.
- Your code: `android/app/src/main/kotlin/io/github/codex07/iou/ui/` (create it). Import only `api`, `money` and `ledger.model`.
- Inject interfaces into ViewModels with Hilt: `@HiltViewModel class OverviewViewModel @Inject constructor(private val ledger: LedgerApi) : ViewModel()`.

## Reads

Every screen reads a `Flow` (`observeOverview()`, `observePerson(id)`, `observeGroup(id)`, `observeEntry(id)`, …). Flows re-emit after any write, so screens never refresh by hand. Read models are ready to render: balances are computed, people are canonical (merged duplicates appear once), amounts are `Money`.

Signs: a person balance is `net(me, X)`. **Positive means they owe me.** A group net is `groupNet(member)`. **Positive means the group owes them.** Overview totals are positive magnitudes.

Format money with `Money.toPlainString()` + the currency code, or your own locale formatting from `amountMinor` and `currency.fractionDigits`. Never convert to `Double`.

## Writes

Writes are `suspend` and return `LedgerResult<T>`: `Ok(value)` or `Err(LedgerError)`. Nothing is ever partially written.

- **Edits** take the `EditToken` from the read model you edited (`PersonDetail.token`, `EntryDetail.token`, …).
- **Everything else** (creates, member add/remove, settle-ups) takes a `basis`: the `LedgerGeneration` from `BackupApi.observeLedgerGeneration()` when the form opened. Settle-up drafts from the core already contain their basis.
- **When the generation changes** (a backup was restored), drop open drafts and previews and go back to the overview. Writes prepared before return `LedgerReplaced`.
- `StaleVersion`: the entity changed since you read it; reload and let the user redo the edit.

## Entries

- New transfer or expense: build a `TransferDraft`/`ExpenseDraft` and call `saveTransfer`/`saveExpense` with `SaveMode.Create(basis)`.
- Edit: start from `EntryDetail.editDraft` (it's already pre-filled correctly, including FX and merged participants), change fields, save with `SaveMode.Update(id, token)`.
- Parse typed amounts with `Inputs.amount(text, currency)` and codes with `Inputs.currency(text)`.
- Live split feedback: `previewSplit(draft, editing)`. When editing, it also returns the stored shares and whether saving would recompute them.
- Outside groups an entry involves exactly me and one other person. Anything else returns `NeedsGroup(people)`: offer to create a group from those people.

### Currencies

- If the save returns `RateRequired(from, to)`, a conversion is needed. Get a rate with `FxApi.quote(date, from, to)`. If that also returns `RateRequired`, ask the user to type a rate (`FxApi.userRate`) or the amount actually charged (`FxChoice.BankActual(charged)`). Put the choice in `draft.fx` and save again.
- In a CONVERT group the effect is in the group currency. Outside CONVERT groups, set `TransferDraft.settlesCurrency` to settle a debt in one currency with money in another.
- `FxSnapshotMismatch` on an edit means the kept rate no longer fits (different currency pair, or a bank-actual charge after the paid amount changed). Ask for a new rate.

## Settle up

- Group detail has `settlePlan`; person detail has `inGroups`. Call `settleUpFromPlan(item)` to get a pre-filled `SettleUpDraft`, let the user adjust amount or currency, and save with `SaveMode.Create(draft.basis)`.
- `settleUpWithPerson(id, basis)` gives one draft per non-zero currency outside groups.

## Errors

`LedgerError` (in `api/LedgerError.kt`) is a sealed interface; map every case to a message. Each carries the data a good message needs, e.g. `ExactRemaining(diffMinor)`, `MemberHasBalance(groupId, personId, nets)`, `NeedsGroup(people)`. `RestoreConflict(violations)` after a revert means "this old version breaks today's rules"; offer **Open as draft** via `draftFromVersion`.

## Changing the contract

The `api` package is shared ground. To change it, open a small PR that touches `api` **and** the fake together; both of us review it. Contract tests run against the fake (and later the real implementation), so a change one side can't satisfy fails CI. Don't add Android types to `api`.

## Running the fake

Available after M0 Part 7 lands (instructions added then).
````

- [ ] **Step 2: Link it from the README**

In `README.md`, under `## Docs`, add the line:

```markdown
- [UI contributor guide](docs/ui-contributor-guide.md)
```

- [ ] **Step 3: Commit and push**

```bash
cd /d/Workspace/IOU-App
git add docs/ui-contributor-guide.md README.md
git commit -m "Add the UI contributor guide for the api contract"
git push origin main
```

Expected: CI green.

- [ ] **Step 4 (owner): Tell the friend**

Send the friend the link to `docs/ui-contributor-guide.md` on GitHub and the `api` folder. They can design screens and ViewModels against the interfaces now; a runnable fake follows with Part 7.

---

## Part 3 done when

- The `api` package compiles with all eight interfaces, the read models, drafts and inputs; the purity check passes.
- `docs/ui-contributor-guide.md` exists and is linked from the README.

Next: Part 4 (`2026-11-02-m0-04-engine.md`).
