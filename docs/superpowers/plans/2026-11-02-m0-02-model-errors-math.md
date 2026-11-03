# M0 Part 2: Domain Model, Errors and Exact Math Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-02-m0-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** The domain types every later part builds on (`ledger.model`), the shared error and result types (`api.LedgerError`, `api.LedgerResult`), and the three pieces of exact math: canonical identity (`IdentityResolver`), split resolution (`SplitResolver`) and currency conversion (`FxConverter`).

**Architecture:** Plain immutable Kotlin data classes; a `LedgerState` value holds every table as a map keyed by ID. Pure functions over that state. No Android imports, no floating point. Errors are values (`LedgerResult`), with a small `ledgerResult { }` builder so multi-step validation reads top to bottom.

**Tech Stack:** Kotlin, `java.time`, `java.math.BigDecimal`/`BigInteger`, JUnit 5, Kotest assertions and property tests.

## Global Constraints

- Money is `money.Money` (Long minor units); `Double`/`Float` never appear (spec §3.1, checked by `DomainPurityTest`).
- Every stored amount lies between 0 and `MAX_MINOR = 10^13` minor units; larger → `AmountTooLarge` (spec §3.1).
- IDs are UUID strings; sync columns on every syncable entity: `createdAt`, `updatedAt`, `authorId`, `deletedAt`, `version` (starts at 1), `importBatchId` (spec §3.1).
- Sign convention: `net(A, B, currency) > 0` means B owes A; `groupNet(M) > 0` means the group owes M (spec §3.1).
- Splits: largest-remainder rounding in exact integer arithmetic (`BigInteger`), ties to the lowest canonical person UUID in string order; PERCENT inputs are basis points summing to 10 000; SHARES weights are integers up to 1 000 000; EXACT inputs are minor units in the original currency and must sum exactly to `totalMinor`; `Σ shareMinor == effectTotalMinor` (spec §4.4).
- FX: `rate` = major units of quote per one major unit of base; a valid rate parses as `BigDecimal`, is > 0 and has at most 34 significant digits and a scale within ±64, else `InvalidRate`; `effectMinor = HALF_EVEN(amountMinor × rate × 10^(fd(quote) − fd(base)))`; result above `MAX_MINOR` → `AmountTooLarge`; positive amount converting to 0 → `ConvertedToZero`; inverted rates use `MathContext.DECIMAL128` (spec §4.5).
- Canonical identity: `canonical(id)` follows `mergedIntoId` to a person that is not merged; a canonical person's default handle of a type is its own record's default if it has one, otherwise the default of the alias with the lowest UUID (spec §3.2, §4.1).

## Deviations from the spec text (deliberate, small)

- SHARES weights may be **0** for an individual participant (listed but pays nothing this time); all-zero weights → `ZeroWeights`; a weight above 1 000 000 → `WeightOutOfRange`. The spec says "1–1 000 000"; allowing 0 is the only way `ZeroWeights` can occur and is useful in the UI.
- `LedgerError` adds a few errors the spec implies but doesn't name: `NotFound`, `InvalidField`, `RateRequired` (no rate given or none available, the user must enter one), `MissingInput`, `WeightOutOfRange`, `DuplicateParticipant`, and file-level errors for M1 (`WrongPassphraseOrDamagedFile`, `CorruptFile`, `LimitsExceeded`, `SchemaTooNew`).
- `FieldChange`, `SplitInput` and `Change` live in `ledger.model` (plain data shared by engine, rules, ops and api). The index lists them there.
- A rate's scale must lie within ±64 (`MAX_RATE_SCALE`); otherwise `InvalidRate`. Without it an exponent-notation rate like `1E999999999` makes conversion build a number with a billion digits.

## File map

| File | Responsibility |
|---|---|
| `ledger/model/Ids.kt` | ID value classes, `IdGenerator` |
| `ledger/model/Enums.kt` | All domain enums |
| `ledger/model/Entities.kt` | `SyncMeta`, `LedgerEntity`, `Entry`, the six syncable entities, `ExpenseShare` |
| `ledger/model/Records.kt` | `FxSnapshot`, `EntryRevision`, `ImportBatch`, `Change`, `FieldChange`, `SplitInput` |
| `ledger/model/LedgerState.kt` | The immutable ledger value and its update helpers |
| `api/LedgerResult.kt` | `LedgerResult`, helpers, `ledgerResult { }` builder |
| `api/LedgerError.kt` | The error hierarchy, `MergeProblem`, `RuleViolation`, `MoneyError` mapping |
| `ledger/engine/IdentityResolver.kt` | Canonical IDs, alias trees, handle defaults, canonical membership |
| `ledger/engine/SplitResolver.kt` | Split validation and largest-remainder distribution |
| `ledger/engine/FxConverter.kt` | Rate validation, conversion, derived and inverted rates |
| `test/.../testutil/TestLedger.kt` | Test fixture: builds `LedgerState`s tersely (used by Parts 2, 4, 5, 6, 7) |

All main paths are under `android/app/src/main/kotlin/io/github/codex07/iou/`; tests under `android/app/src/test/kotlin/io/github/codex07/iou/`. Commands run from `android/`.

---

### Task 1: `ledger.model` and the test fixture

**Files:**
- Create: `main/.../ledger/model/Ids.kt`, `Enums.kt`, `Entities.kt`, `Records.kt`, `LedgerState.kt`
- Create: `test/.../testutil/TestLedger.kt`
- Test: `test/.../ledger/model/LedgerStateTest.kt`

**Interfaces:**
- Consumes: `money.CurrencyCode`, `money.Money` (Part 1).
- Produces (exact names later parts use):
  - IDs `PersonId`, `GroupId`, `MembershipId`, `HandleId`, `EntryId`, `FxSnapshotId`, `ImportBatchId`, `RevisionId`, `DeviceId`, `LedgerGeneration`, each `@JvmInline value class X(val value: String)` with `toString() = value`.
  - `fun interface IdGenerator { fun next(): String }` with `IdGenerator.Random`.
  - `SyncMeta(createdAt, updatedAt, authorId, deletedAt = null, version = 1, importBatchId = null)` with `isDeleted`.
  - `sealed interface LedgerEntity { meta; entityType; entityId; isDeleted; fun withMeta(meta): LedgerEntity }`.
  - `Person`, `PaymentHandle`, `Group`, `GroupMember`, `Transfer`, `Expense` (fields below), `sealed interface Entry : LedgerEntity { id; groupId; paid; effect; fxSnapshotId; occurredOn; note; personIds }`.
  - `ExpenseShare(personId, shareMinor, inputValue)`, `FxSnapshot`, `EntryRevision`, `ImportBatch`, `Change(entity, action, reason)`, `FieldChange(field, before, after)`, `SplitInput(personId, value)`.
  - `LedgerState(people, handles, groups, members, transfers, expenses, fxSnapshots)` with `me`, `meOrNull`, `entries`, `entry(id)`, `entity(type, id)`, `with(entity)`, `with(snapshot)`, `applying(changes, snapshots)`.
  - Test fixture `testutil.TestLedger` and constants `EUR`, `SEK`, `USD`, `JPY`, `BHD`, `IDR`, `T0`, `D0`, `ME`, helpers `eur(minor)`, `sek(minor)`, `usd(minor)`.

- [ ] **Step 1: Write the model files**

`ledger/model/Ids.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

import java.util.UUID

@JvmInline value class PersonId(val value: String) { override fun toString(): String = value }
@JvmInline value class GroupId(val value: String) { override fun toString(): String = value }
@JvmInline value class MembershipId(val value: String) { override fun toString(): String = value }
@JvmInline value class HandleId(val value: String) { override fun toString(): String = value }
@JvmInline value class EntryId(val value: String) { override fun toString(): String = value }
@JvmInline value class FxSnapshotId(val value: String) { override fun toString(): String = value }
@JvmInline value class ImportBatchId(val value: String) { override fun toString(): String = value }
@JvmInline value class RevisionId(val value: String) { override fun toString(): String = value }
@JvmInline value class DeviceId(val value: String) { override fun toString(): String = value }

/** Identifies the live database; changes on every restore and undo of a restore (spec §5.1). */
@JvmInline value class LedgerGeneration(val value: String) { override fun toString(): String = value }

/** Source of new UUID strings. Production uses [Random]; tests use deterministic generators. */
fun interface IdGenerator {
    fun next(): String

    companion object {
        val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
```

`ledger/model/Enums.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

enum class HandleType { IBAN, REVTAG, PHONE, OTHER }

enum class FxMode { CONVERT, KEEP }

enum class TransferKind { LOAN, REPAYMENT }

enum class TransferMethod { BANK, CASH, CARD, OTHER }

enum class Category { GROCERIES, DINING, TRANSPORT, ACCOMMODATION, ENTERTAINMENT, UTILITIES, RENT, GENERAL, OTHER }

enum class SplitType { EQUAL, PERCENT, SHARES, EXACT }

enum class FxSource { ECB_FRANKFURTER, USER_ENTERED, BANK_ACTUAL }

enum class EntityType { PERSON, PAYMENT_HANDLE, GROUP, GROUP_MEMBER, TRANSFER, EXPENSE }

enum class RevisionAction { CREATED, UPDATED, DELETED, RESTORED }

enum class ImportSource { OWN_NORMALIZED_CSV }
```

`ledger/model/Entities.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

/** Sync columns shared by every syncable entity (spec §3.1). */
data class SyncMeta(
    val createdAt: Instant,
    val updatedAt: Instant,
    val authorId: PersonId,
    val deletedAt: Instant? = null,
    val version: Int = 1,
    val importBatchId: ImportBatchId? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

/** A row that has sync columns and revisions. */
sealed interface LedgerEntity {
    val meta: SyncMeta
    val entityType: EntityType
    val entityId: String
    val isDeleted: Boolean get() = meta.isDeleted

    fun withMeta(meta: SyncMeta): LedgerEntity
}

data class Person(
    val id: PersonId,
    val displayName: String,
    val isMe: Boolean = false,
    val note: String? = null,
    val defaultCurrency: CurrencyCode? = null,
    val archivedAt: Instant? = null,
    val mergedIntoId: PersonId? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.PERSON
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): Person = copy(meta = meta)
}

data class PaymentHandle(
    val id: HandleId,
    val personId: PersonId,
    val type: HandleType,
    val value: String,
    val holderName: String? = null,
    val bic: String? = null,
    val label: String,
    val isDefault: Boolean = false,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.PAYMENT_HANDLE
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): PaymentHandle = copy(meta = meta)
}

data class Group(
    val id: GroupId,
    val name: String,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode = FxMode.CONVERT,
    val archivedAt: Instant? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.GROUP
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): Group = copy(meta = meta)
}

data class GroupMember(
    val id: MembershipId,
    val groupId: GroupId,
    val personId: PersonId,
    val joinedAt: Instant,
    val removedAt: Instant? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.GROUP_MEMBER
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): GroupMember = copy(meta = meta)
}

/** A transfer or an expense: something with a paid amount and an effect on balances. */
sealed interface Entry : LedgerEntity {
    val id: EntryId
    val groupId: GroupId?
    val paid: Money
    val effect: Money
    val fxSnapshotId: FxSnapshotId?
    val occurredOn: LocalDate
    val note: String?

    /** Every person this entry references, as stored (not resolved through merges). */
    val personIds: Set<PersonId>

    override val entityId: String get() = id.value
}

data class Transfer(
    override val id: EntryId,
    val fromPersonId: PersonId,
    val toPersonId: PersonId,
    val amount: Money,
    val effectAmount: Money,
    override val fxSnapshotId: FxSnapshotId? = null,
    val kind: TransferKind = TransferKind.LOAN,
    val method: TransferMethod = TransferMethod.BANK,
    val accountLabel: String? = null,
    override val groupId: GroupId? = null,
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
) : Entry {
    override val entityType: EntityType get() = EntityType.TRANSFER
    override val paid: Money get() = amount
    override val effect: Money get() = effectAmount
    override val personIds: Set<PersonId> get() = setOf(fromPersonId, toPersonId)
    override fun withMeta(meta: SyncMeta): Transfer = copy(meta = meta)
}

/** Owned by its expense; versioned as part of the expense (spec §3.2). */
data class ExpenseShare(
    val personId: PersonId,
    val shareMinor: Long,
    val inputValue: Long? = null,
)

data class Expense(
    override val id: EntryId,
    override val groupId: GroupId? = null,
    val payerPersonId: PersonId,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val effectTotal: Money,
    override val fxSnapshotId: FxSnapshotId? = null,
    val splitType: SplitType,
    val shares: List<ExpenseShare>,
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
) : Entry {
    override val entityType: EntityType get() = EntityType.EXPENSE
    override val paid: Money get() = total
    override val effect: Money get() = effectTotal
    override val personIds: Set<PersonId> get() = setOf(payerPersonId) + shares.map { it.personId }
    override fun withMeta(meta: SyncMeta): Expense = copy(meta = meta)
}
```

`ledger/model/Records.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.money.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** Immutable once written, never deleted (spec §3.2). */
data class FxSnapshot(
    val id: FxSnapshotId,
    val baseCurrency: CurrencyCode,
    val quoteCurrency: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean = false,
    val fetchedAt: Instant,
    val paidMinor: Long? = null,
    val chargedMinor: Long? = null,
)

/** One append-only audit record. [snapshot] is the full entity after the write (spec §3.2). */
data class EntryRevision(
    val id: RevisionId,
    val entityType: EntityType,
    val entityId: String,
    val version: Int,
    val action: RevisionAction,
    val authorId: PersonId,
    val deviceId: DeviceId,
    val at: Instant,
    val reason: String? = null,
    val importBatchId: ImportBatchId? = null,
    val snapshot: LedgerEntity,
)

data class ImportBatch(
    val id: ImportBatchId,
    val source: ImportSource,
    val fileName: String,
    val importedAt: Instant,
    val rowCount: Int,
    val undoneAt: Instant? = null,
)

/** One row a write creates or changes, already carrying its new sync columns. One revision each. */
data class Change(
    val entity: LedgerEntity,
    val action: RevisionAction,
    val reason: String? = null,
)

/** One field of a revision diff or an import conflict; values are display strings. */
data class FieldChange(
    val field: String,
    val before: String?,
    val after: String?,
)

/**
 * One participant of a split. [value] is basis points (PERCENT), a weight (SHARES), minor units in the
 * paid currency (EXACT), or ignored (EQUAL).
 */
data class SplitInput(
    val personId: PersonId,
    val value: Long? = null,
)
```

`ledger/model/LedgerState.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

/** The whole ledger as one immutable value. Soft-deleted rows are included. */
data class LedgerState(
    val people: Map<PersonId, Person> = emptyMap(),
    val handles: Map<HandleId, PaymentHandle> = emptyMap(),
    val groups: Map<GroupId, Group> = emptyMap(),
    val members: Map<MembershipId, GroupMember> = emptyMap(),
    val transfers: Map<EntryId, Transfer> = emptyMap(),
    val expenses: Map<EntryId, Expense> = emptyMap(),
    val fxSnapshots: Map<FxSnapshotId, FxSnapshot> = emptyMap(),
) {
    /** The single me person. Throws if the state is corrupt; validators check that first (rule 15). */
    val me: Person get() = people.values.single { it.isMe }

    val meOrNull: Person? get() = people.values.singleOrNull { it.isMe }

    val entries: List<Entry> get() = transfers.values + expenses.values

    fun entry(id: EntryId): Entry? = transfers[id] ?: expenses[id]

    fun entity(type: EntityType, id: String): LedgerEntity? = when (type) {
        EntityType.PERSON -> people[PersonId(id)]
        EntityType.PAYMENT_HANDLE -> handles[HandleId(id)]
        EntityType.GROUP -> groups[GroupId(id)]
        EntityType.GROUP_MEMBER -> members[MembershipId(id)]
        EntityType.TRANSFER -> transfers[EntryId(id)]
        EntityType.EXPENSE -> expenses[EntryId(id)]
    }

    /** Inserts or replaces [entity] in its table. */
    fun with(entity: LedgerEntity): LedgerState = when (entity) {
        is Person -> copy(people = people + (entity.id to entity))
        is PaymentHandle -> copy(handles = handles + (entity.id to entity))
        is Group -> copy(groups = groups + (entity.id to entity))
        is GroupMember -> copy(members = members + (entity.id to entity))
        is Transfer -> copy(transfers = transfers + (entity.id to entity))
        is Expense -> copy(expenses = expenses + (entity.id to entity))
    }

    fun with(snapshot: FxSnapshot): LedgerState = copy(fxSnapshots = fxSnapshots + (snapshot.id to snapshot))

    /** The state a write would produce. */
    fun applying(changes: List<Change>, snapshots: List<FxSnapshot> = emptyList()): LedgerState {
        val withSnapshots = snapshots.fold(this) { state, snapshot -> state.with(snapshot) }
        return changes.fold(withSnapshots) { state, change -> state.with(change.entity) }
    }
}
```

- [ ] **Step 2: Write the test fixture**

`test/.../testutil/TestLedger.kt`. Deterministic IDs that sort predictably (`person-alex` < `person-bob`), every row created at `T0` by `ME`. Tests that need a tie order choose names accordingly.

```kotlin
package io.github.codex07.iou.testutil

import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

val EUR = CurrencyCode("EUR")
val SEK = CurrencyCode("SEK")
val USD = CurrencyCode("USD")
val JPY = CurrencyCode("JPY")
val BHD = CurrencyCode("BHD")
val IDR = CurrencyCode("IDR")

val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")
val D0: LocalDate = LocalDate.parse("2026-01-01")
val ME = PersonId("person-0-me")

fun eur(minor: Long) = Money(minor, EUR)
fun sek(minor: Long) = Money(minor, SEK)
fun usd(minor: Long) = Money(minor, USD)

/** Deterministic IDs: "<prefix>-1", "<prefix>-2", … */
class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix-${++counter}"
}

fun testMeta(deleted: Boolean = false, version: Int = 1): SyncMeta =
    SyncMeta(createdAt = T0, updatedAt = T0, authorId = ME, deletedAt = if (deleted) T0 else null, version = version)

/** Builds [LedgerState]s for tests. Every builder call returns the new row's ID. */
class TestLedger {
    var state = LedgerState()
        private set

    private var counter = 0
    private fun nextId(prefix: String) = "$prefix-${++counter}"

    fun me(name: String = "Me"): PersonId {
        state = state.with(Person(ME, name, isMe = true, meta = testMeta()))
        return ME
    }

    fun person(
        name: String,
        id: String = "person-${name.lowercase()}",
        mergedInto: PersonId? = null,
        deleted: Boolean = false,
        archived: Boolean = false,
    ): PersonId {
        val personId = PersonId(id)
        state = state.with(
            Person(
                id = personId,
                displayName = name,
                mergedIntoId = mergedInto,
                archivedAt = if (archived) T0 else null,
                meta = testMeta(deleted),
            ),
        )
        return personId
    }

    fun handle(
        person: PersonId,
        type: HandleType = HandleType.IBAN,
        value: String = "DE89370400440532013000",
        isDefault: Boolean = false,
        deleted: Boolean = false,
        id: String = nextId("handle"),
    ): HandleId {
        val handleId = HandleId(id)
        state = state.with(
            PaymentHandle(handleId, person, type, value, label = "$type", isDefault = isDefault, meta = testMeta(deleted)),
        )
        return handleId
    }

    /** A group with active memberships for [members] (me first by default). */
    fun group(
        name: String,
        currency: CurrencyCode = EUR,
        fxMode: FxMode = FxMode.KEEP,
        members: List<PersonId> = listOf(ME),
        deleted: Boolean = false,
        id: String = "group-${name.lowercase()}",
    ): GroupId {
        val groupId = GroupId(id)
        state = state.with(Group(groupId, name, currency, fxMode, meta = testMeta(deleted)))
        members.forEach { member(groupId, it, deleted = deleted) }
        return groupId
    }

    fun member(group: GroupId, person: PersonId, removed: Boolean = false, deleted: Boolean = false): MembershipId {
        val membershipId = MembershipId(nextId("member"))
        state = state.with(
            GroupMember(membershipId, group, person, joinedAt = T0, removedAt = if (removed) T0 else null, meta = testMeta(deleted)),
        )
        return membershipId
    }

    fun fxSnapshot(
        base: CurrencyCode,
        quote: CurrencyCode,
        rate: String,
        source: FxSource = FxSource.USER_ENTERED,
        paidMinor: Long? = null,
        chargedMinor: Long? = null,
    ): FxSnapshotId {
        val snapshotId = FxSnapshotId(nextId("fx"))
        state = state.with(
            FxSnapshot(snapshotId, base, quote, rate, D0, source, fetchedAt = T0, paidMinor = paidMinor, chargedMinor = chargedMinor),
        )
        return snapshotId
    }

    fun transfer(
        from: PersonId,
        to: PersonId,
        amount: Money,
        group: GroupId? = null,
        effect: Money = amount,
        fx: FxSnapshotId? = null,
        kind: TransferKind = TransferKind.LOAN,
        deleted: Boolean = false,
        id: String = nextId("transfer"),
    ): EntryId {
        val entryId = EntryId(id)
        state = state.with(
            Transfer(
                id = entryId, fromPersonId = from, toPersonId = to, amount = amount, effectAmount = effect,
                fxSnapshotId = fx, kind = kind, groupId = group, occurredOn = D0, meta = testMeta(deleted),
            ),
        )
        return entryId
    }

    /** [shares] are final share amounts in the effect currency; inputs are stored as given. */
    fun expense(
        payer: PersonId,
        total: Money,
        shares: Map<PersonId, Long>,
        group: GroupId? = null,
        effectTotal: Money = total,
        fx: FxSnapshotId? = null,
        splitType: SplitType = SplitType.EXACT,
        inputs: Map<PersonId, Long?> = emptyMap(),
        deleted: Boolean = false,
        id: String = nextId("expense"),
    ): EntryId {
        val entryId = EntryId(id)
        state = state.with(
            Expense(
                id = entryId, groupId = group, payerPersonId = payer, description = "Expense $id",
                total = total, effectTotal = effectTotal, fxSnapshotId = fx, splitType = splitType,
                shares = shares.map { (person, share) -> ExpenseShare(person, share, inputs[person]) },
                occurredOn = D0, meta = testMeta(deleted),
            ),
        )
        return entryId
    }
}
```

- [ ] **Step 3: Write the failing state test**

`test/.../ledger/model/LedgerStateTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerStateTest {

    @Test
    fun `me is the single isMe person`() {
        val ledger = TestLedger().apply { me(); person("Alex") }
        ledger.state.me.id shouldBe ME
        ledger.state.meOrNull?.id shouldBe ME
    }

    @Test
    fun `a state without me has no me`() {
        val state = LedgerState()
        state.meOrNull shouldBe null
        shouldThrow<NoSuchElementException> { state.me }
    }

    @Test
    fun `entries contains transfers and expenses, and lookups find each table`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val transfer = ledger.transfer(ME, alex, eur(5000))
        val expense = ledger.expense(ME, eur(1000), mapOf(ME to 500L, alex to 500L))
        val state = ledger.state

        state.entries.map { it.id } shouldContainExactlyInAnyOrder listOf(transfer, expense)
        state.entry(transfer) shouldBe state.transfers[transfer]
        state.entry(expense) shouldBe state.expenses[expense]
        state.entity(EntityType.PERSON, alex.value) shouldBe state.people[alex]
        state.entity(EntityType.EXPENSE, expense.value) shouldBe state.expenses[expense]
    }

    @Test
    fun `applying changes replaces rows by id and adds snapshots`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val renamed = ledger.state.people.getValue(alex).copy(displayName = "Alex S.", meta = testMeta(version = 2))

        val after = ledger.state.applying(listOf(Change(renamed, RevisionAction.UPDATED)))

        after.people.getValue(alex).displayName shouldBe "Alex S."
        after.people.size shouldBe 2
        ledger.state.people.getValue(alex).displayName shouldBe "Alex"
    }

    @Test
    fun `entry person ids include payer and every share`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val bob = ledger.person("Bob")
        val expense = ledger.expense(alex, eur(900), mapOf(ME to 300L, bob to 600L))

        ledger.state.entry(expense)!!.personIds shouldBe setOf(alex, ME, bob)
    }
}
```

- [ ] **Step 4: Run, expect a compile failure, then pass**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.model.*"
```

Expected before Step 1 is in place: compilation fails on unresolved model types. With Steps 1–2 written: `BUILD SUCCESSFUL`, 5 tests pass. (If you wrote the test first as TDD intends, run it once before Step 1 to see the failure.)

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/model android/app/src/test/kotlin/io/github/codex07/iou/testutil android/app/src/test/kotlin/io/github/codex07/iou/ledger/model
git commit -m "Add the ledger domain model and a test fixture for building ledger states"
```

---

### Task 2: `LedgerResult` and `LedgerError`

**Files:**
- Create: `main/.../api/LedgerResult.kt`, `main/.../api/LedgerError.kt`
- Test: `test/.../api/LedgerResultTest.kt`

**Interfaces:**
- Consumes: `money.MoneyError`, `money.MoneyParseResult`, model IDs.
- Produces:
  - `sealed interface LedgerResult<out T>` with `Ok(value)` and `Err(error)`; extension functions `map`, `flatMap`, `getOrNull`, `errorOrNull`.
  - `inline fun <T> ledgerResult(block: ResultScope.() -> T): LedgerResult<T>` where `ResultScope` offers `LedgerResult<T>.bind(): T`, `fail(error): Nothing`, `ensure(condition) { error }`.
  - `sealed interface LedgerError` (members listed in the code), `enum class MergeProblem`, `data class RuleViolation(rule: Int, error: LedgerError)`.
  - `fun MoneyError.toLedgerError(): LedgerError`, `fun MoneyParseResult.toLedgerResult(): LedgerResult<Money>`.

- [ ] **Step 1: Write the failing test**

`test/.../api/LedgerResultTest.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyError
import io.github.codex07.iou.money.MoneyParser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerResultTest {

    private val eur = CurrencyCode("EUR")

    @Test
    fun `map and flatMap transform only Ok`() {
        val failed: LedgerResult<Int> = LedgerResult.Err(LedgerError.SelfTransfer)
        LedgerResult.Ok(2).map { it * 3 } shouldBe LedgerResult.Ok(6)
        failed.map { it + 1 } shouldBe LedgerResult.Err(LedgerError.SelfTransfer)
        LedgerResult.Ok(2).flatMap { LedgerResult.Err(LedgerError.CannotModifyMe) } shouldBe
            LedgerResult.Err(LedgerError.CannotModifyMe)
    }

    @Test
    fun `getOrNull and errorOrNull pick the matching side`() {
        LedgerResult.Ok("x").getOrNull() shouldBe "x"
        LedgerResult.Ok("x").errorOrNull() shouldBe null
        LedgerResult.Err(LedgerError.LedgerReplaced).getOrNull() shouldBe null
        LedgerResult.Err(LedgerError.LedgerReplaced).errorOrNull() shouldBe LedgerError.LedgerReplaced
    }

    @Test
    fun `the builder returns the first failure and skips the rest`() {
        var reachedEnd = false
        val result = ledgerResult {
            val a = LedgerResult.Ok(1).bind()
            ensure(a == 2) { LedgerError.ZeroWeights }
            reachedEnd = true
            a
        }
        result shouldBe LedgerResult.Err(LedgerError.ZeroWeights)
        reachedEnd shouldBe false

        ledgerResult { LedgerResult.Ok(1).bind() + LedgerResult.Ok(2).bind() } shouldBe LedgerResult.Ok(3)
        ledgerResult<Int> { fail(LedgerError.NoParticipants) } shouldBe LedgerResult.Err(LedgerError.NoParticipants)
    }

    @Test
    fun `nested builders keep their own failures`() {
        val outer = ledgerResult {
            val inner = ledgerResult<Int> { fail(LedgerError.SelfTransfer) }
            inner.errorOrNull()
        }
        outer shouldBe LedgerResult.Ok(LedgerError.SelfTransfer)
    }

    @Test
    fun `money errors map one to one`() {
        MoneyError.InvalidAmount.toLedgerError() shouldBe LedgerError.InvalidAmount
        MoneyError.TooManyDecimals(2).toLedgerError() shouldBe LedgerError.TooManyDecimals(2)
        MoneyError.AmountTooLarge.toLedgerError() shouldBe LedgerError.AmountTooLarge
        MoneyParser.parse("1.5", eur).toLedgerResult() shouldBe LedgerResult.Ok(Money(150, eur))
        MoneyParser.parse("x", eur).toLedgerResult() shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.api.LedgerResultTest"
```

Expected: compilation fails, `Unresolved reference 'LedgerResult'`.

- [ ] **Step 3: Implement**

`main/.../api/LedgerResult.kt`:

```kotlin
package io.github.codex07.iou.api

/** Result of every write and of fallible helpers: a value or a typed [LedgerError] (spec §6.2). */
sealed interface LedgerResult<out T> {
    data class Ok<out T>(val value: T) : LedgerResult<T>
    data class Err(val error: LedgerError) : LedgerResult<Nothing>
}

inline fun <T, R> LedgerResult<T>.map(transform: (T) -> R): LedgerResult<R> = when (this) {
    is LedgerResult.Ok -> LedgerResult.Ok(transform(value))
    is LedgerResult.Err -> this
}

inline fun <T, R> LedgerResult<T>.flatMap(transform: (T) -> LedgerResult<R>): LedgerResult<R> = when (this) {
    is LedgerResult.Ok -> transform(value)
    is LedgerResult.Err -> this
}

fun <T> LedgerResult<T>.getOrNull(): T? = (this as? LedgerResult.Ok)?.value

fun <T> LedgerResult<T>.errorOrNull(): LedgerError? = (this as? LedgerResult.Err)?.error

/** Scope of [ledgerResult]: `bind()` unwraps or stops the block with the error. */
class ResultScope @PublishedApi internal constructor() {

    @PublishedApi
    internal class Failure(val error: LedgerError, val scope: ResultScope) :
        RuntimeException(null, null, false, false)

    fun <T> LedgerResult<T>.bind(): T = when (this) {
        is LedgerResult.Ok -> value
        is LedgerResult.Err -> throw Failure(error, this@ResultScope)
    }

    fun fail(error: LedgerError): Nothing = throw Failure(error, this)

    inline fun ensure(condition: Boolean, error: () -> LedgerError) {
        if (!condition) fail(error())
    }
}

/** Runs [block]; the first `bind()` of an error, or `fail`, becomes the result. */
inline fun <T> ledgerResult(block: ResultScope.() -> T): LedgerResult<T> {
    val scope = ResultScope()
    return try {
        LedgerResult.Ok(scope.block())
    } catch (failure: ResultScope.Failure) {
        if (failure.scope !== scope) throw failure
        LedgerResult.Err(failure.error)
    }
}
```

`main/.../api/LedgerError.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyError
import io.github.codex07.iou.money.MoneyParseResult

/**
 * Every failure the core reports (spec §4.4, §8). Each carries the data a good message or a
 * suggested action needs; the UI maps them to strings.
 */
sealed interface LedgerError {
    // Money and field validation
    data object InvalidAmount : LedgerError
    data class TooManyDecimals(val maxDecimals: Int) : LedgerError
    data object AmountTooLarge : LedgerError
    data class UnknownCurrency(val code: String) : LedgerError
    data class InvalidRate(val rate: String) : LedgerError
    data class ConvertedToZero(val amount: Money, val to: CurrencyCode) : LedgerError
    /** A conversion is needed but no rate was given or none is available: ask the user for one. */
    data class RateRequired(val from: CurrencyCode, val to: CurrencyCode) : LedgerError
    data class InvalidIban(val value: String) : LedgerError
    /** A required text field is blank or a value has the wrong shape (e.g. a Revtag with spaces). */
    data class InvalidField(val field: String) : LedgerError

    // Splits (spec §4.4)
    data object NoParticipants : LedgerError
    data class PercentNotHundred(val sumBp: Long) : LedgerError
    data class ExactRemaining(val diffMinor: Long) : LedgerError
    data object ZeroWeights : LedgerError
    data class NegativeInput(val personId: PersonId) : LedgerError
    data class MissingInput(val personId: PersonId) : LedgerError
    data class WeightOutOfRange(val personId: PersonId, val value: Long) : LedgerError
    data class DuplicateParticipant(val personId: PersonId) : LedgerError

    // Integrity (spec §3.4)
    data class NeedsGroup(val people: Set<PersonId>) : LedgerError
    data class NotGroupMember(val groupId: GroupId, val people: Set<PersonId>) : LedgerError
    data class MemberHasBalance(
        val groupId: GroupId,
        val personId: PersonId,
        val nets: Map<CurrencyCode, Long>,
    ) : LedgerError
    data class MemberRemoved(val groupId: GroupId, val people: Set<PersonId>) : LedgerError
    data class GroupSettingsLocked(val groupId: GroupId) : LedgerError
    data object CannotModifyMe : LedgerError
    data class StillReferenced(val id: String) : LedgerError
    data class DeletedReference(val ids: Set<String>) : LedgerError
    data class InvalidMerge(val problem: MergeProblem) : LedgerError
    data object SelfTransfer : LedgerError

    // Concurrency (spec §5.1)
    data class StaleVersion(val id: String, val expected: Int, val actual: Int) : LedgerError
    data object LedgerReplaced : LedgerError
    data object ImportPreviewStale : LedgerError

    // FX consistency and structure
    data class FxSnapshotMismatch(val entryId: EntryId?) : LedgerError
    data class CorruptData(val detail: String) : LedgerError

    // Revert to version (spec §5.3)
    data class RestoreConflict(val violations: List<RuleViolation>) : LedgerError

    // Backup, restore and import files (M1)
    data object WrongPassphraseOrDamagedFile : LedgerError
    data class CorruptFile(val detail: String) : LedgerError
    data class LimitsExceeded(val detail: String) : LedgerError
    data class SchemaTooNew(val found: Int, val supported: Int) : LedgerError
    data object ForeignLedger : LedgerError

    // Lookups
    data class NotFound(val id: String) : LedgerError
}

enum class MergeProblem { SAME_PERSON, DELETED, IS_ME, NOT_CANONICAL, NOT_MERGED }

/** A violated rule of spec §3.4 (0 = field validity) with the error it produces. */
data class RuleViolation(val rule: Int, val error: LedgerError)

fun MoneyError.toLedgerError(): LedgerError = when (this) {
    MoneyError.InvalidAmount -> LedgerError.InvalidAmount
    is MoneyError.TooManyDecimals -> LedgerError.TooManyDecimals(maxDecimals)
    MoneyError.AmountTooLarge -> LedgerError.AmountTooLarge
}

fun MoneyParseResult.toLedgerResult(): LedgerResult<Money> = when (this) {
    is MoneyParseResult.Ok -> LedgerResult.Ok(money)
    is MoneyParseResult.Err -> LedgerResult.Err(error.toLedgerError())
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.api.LedgerResultTest" --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: `BUILD SUCCESSFUL`; 5 result tests and the purity check pass (it now scans `ledger` and `api` too).

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/api android/app/src/test/kotlin/io/github/codex07/iou/api
git commit -m "Add LedgerResult with a result builder and the LedgerError hierarchy"
```

---

### Task 3: `IdentityResolver`

**Files:**
- Create: `main/.../ledger/engine/IdentityResolver.kt`
- Test: `test/.../ledger/engine/IdentityResolverTest.kt`

**Interfaces:**
- Consumes: `LedgerState`, `Person`, `PaymentHandle`, `GroupMember`, `HandleType` (Task 1).
- Produces: `class IdentityResolver(state: LedgerState)` with
  - `fun canonical(id: PersonId): PersonId` (unknown IDs resolve to themselves),
  - `val brokenChains: Set<PersonId>` (people whose `mergedIntoId` chain loops or points at a missing row; rule 15 reports them; their canonical is themselves for a loop, or the last existing person for a dangling pointer),
  - `fun recordsOf(canonical: PersonId): Set<PersonId>`,
  - `fun handlesOf(canonical: PersonId): List<PaymentHandle>` (non-deleted, sorted by ID),
  - `fun defaultHandle(canonical: PersonId, type: HandleType): PaymentHandle?`,
  - `fun memberships(groupId: GroupId, canonical: PersonId): List<GroupMember>` (non-deleted),
  - `fun hasMembership(groupId, canonical)`, `fun isActiveMember(groupId, canonical)`,
  - `fun canonicalMembers(groupId: GroupId): Set<PersonId>` (canonical IDs with any non-deleted membership).

- [ ] **Step 1: Write the failing test**

`test/.../ledger/engine/IdentityResolverTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IdentityResolverTest {

    @Test
    fun `canonical follows the merge chain to the end`() {
        val ledger = TestLedger()
        ledger.me()
        val smith = ledger.person("Smith")
        val alexS = ledger.person("AlexS", mergedInto = smith)
        val alex = ledger.person("Alex", mergedInto = alexS)
        val resolver = IdentityResolver(ledger.state)

        resolver.canonical(alex) shouldBe smith
        resolver.canonical(alexS) shouldBe smith
        resolver.canonical(smith) shouldBe smith
        resolver.canonical(ME) shouldBe ME
        resolver.recordsOf(smith) shouldBe setOf(smith, alexS, alex)
        resolver.recordsOf(ME) shouldBe setOf(ME)
        resolver.brokenChains.shouldBeEmpty()
    }

    @Test
    fun `unknown ids resolve to themselves`() {
        val resolver = IdentityResolver(TestLedger().state)
        resolver.canonical(PersonId("nobody")) shouldBe PersonId("nobody")
        resolver.recordsOf(PersonId("nobody")) shouldBe setOf(PersonId("nobody"))
    }

    @Test
    fun `merge cycles and dangling pointers are reported, not looped over`() {
        val ledger = TestLedger()
        ledger.me()
        ledger.person("A", mergedInto = PersonId("person-b"), deleted = true)
        ledger.person("B", mergedInto = PersonId("person-a"), deleted = true)
        val c = ledger.person("C", mergedInto = PersonId("missing"))
        val resolver = IdentityResolver(ledger.state)

        resolver.brokenChains shouldBe setOf(PersonId("person-a"), PersonId("person-b"), c)
        resolver.canonical(PersonId("person-a")) shouldBe PersonId("person-a")
        resolver.canonical(c) shouldBe c
    }

    @Test
    fun `handles are the union over all records, deleted ones excluded`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val own = ledger.handle(target, HandleType.REVTAG, "target", id = "handle-b")
        val aliased = ledger.handle(alias, HandleType.IBAN, id = "handle-a")
        ledger.handle(alias, HandleType.PHONE, "+46700000000", deleted = true, id = "handle-c")
        val resolver = IdentityResolver(ledger.state)

        resolver.handlesOf(target).map { it.id } shouldBe listOf(aliased, own)
    }

    @Test
    fun `the canonical record's own default wins, otherwise the lowest alias UUID`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target", id = "person-t")
        val aliasB = ledger.person("B", id = "person-b", mergedInto = target)
        val aliasA = ledger.person("A", id = "person-a", mergedInto = target)
        val fromB = ledger.handle(aliasB, HandleType.IBAN, isDefault = true, id = "handle-1")
        val fromA = ledger.handle(aliasA, HandleType.IBAN, isDefault = true, id = "handle-2")

        IdentityResolver(ledger.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe fromA

        val own = ledger.handle(target, HandleType.IBAN, isDefault = true, id = "handle-3")
        IdentityResolver(ledger.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe own
        IdentityResolver(ledger.state).defaultHandle(target, HandleType.REVTAG) shouldBe null
        IdentityResolver(ledger.state).handlesOf(target).map { it.id } shouldBe listOf(fromB, fromA, own)
    }

    @Test
    fun `a canonical person is active if any of their records has an active membership`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.member(group, alias, removed = true)
        val resolver1 = IdentityResolver(ledger.state)

        resolver1.hasMembership(group, target) shouldBe true
        resolver1.isActiveMember(group, target) shouldBe false
        resolver1.canonicalMembers(group) shouldBe setOf(ME, target)

        ledger.member(group, target)
        val resolver2 = IdentityResolver(ledger.state)
        resolver2.isActiveMember(group, target) shouldBe true
        resolver2.memberships(group, target).size shouldBe 2
    }

    @Test
    fun `deleted memberships do not count`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.member(group, alex, deleted = true)

        IdentityResolver(ledger.state).hasMembership(group, alex) shouldBe false
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.IdentityResolverTest"
```

Expected: compilation fails, `Unresolved reference 'IdentityResolver'`.

- [ ] **Step 3: Implement**

`main/.../ledger/engine/IdentityResolver.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.PersonId

/**
 * Canonical person IDs for one ledger state (spec §4.1). Built once per state; every read and
 * every validation resolves person IDs through it.
 */
class IdentityResolver(private val state: LedgerState) {

    private val canonicalOf: Map<PersonId, PersonId>

    /** People whose merge chain loops or points at a missing row (reported by rule 15). */
    val brokenChains: Set<PersonId>

    init {
        val resolved = HashMap<PersonId, PersonId>()
        val broken = HashSet<PersonId>()
        for (start in state.people.keys) {
            val seen = HashSet<PersonId>()
            var current = start
            while (true) {
                if (!seen.add(current)) {
                    broken += start
                    current = start
                    break
                }
                val next = state.people[current]?.mergedIntoId ?: break
                if (next !in state.people) {
                    broken += start
                    break
                }
                current = next
            }
            resolved[start] = current
        }
        canonicalOf = resolved
        brokenChains = broken
    }

    private val recordsByCanonical: Map<PersonId, Set<PersonId>> =
        canonicalOf.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }

    fun canonical(id: PersonId): PersonId = canonicalOf[id] ?: id

    /** Every person record that resolves to [canonical], itself included. */
    fun recordsOf(canonical: PersonId): Set<PersonId> = recordsByCanonical[canonical] ?: setOf(canonical)

    fun handlesOf(canonical: PersonId): List<PaymentHandle> =
        state.handles.values
            .filter { !it.isDeleted && canonical(it.personId) == canonical }
            .sortedBy { it.id.value }

    /** Own record's default first, otherwise the default of the alias with the lowest UUID (spec §3.2). */
    fun defaultHandle(canonical: PersonId, type: HandleType): PaymentHandle? {
        val defaults = handlesOf(canonical).filter { it.type == type && it.isDefault }
        return defaults.firstOrNull { it.personId == canonical }
            ?: defaults.minWithOrNull(compareBy({ it.personId.value }, { it.id.value }))
    }

    fun memberships(groupId: GroupId, canonical: PersonId): List<GroupMember> =
        state.members.values.filter { it.groupId == groupId && !it.isDeleted && canonical(it.personId) == canonical }

    fun hasMembership(groupId: GroupId, canonical: PersonId): Boolean = memberships(groupId, canonical).isNotEmpty()

    /** Canonical membership (spec §3.4): active if any record has an active membership. */
    fun isActiveMember(groupId: GroupId, canonical: PersonId): Boolean =
        memberships(groupId, canonical).any { it.removedAt == null }

    fun canonicalMembers(groupId: GroupId): Set<PersonId> =
        state.members.values
            .filter { it.groupId == groupId && !it.isDeleted }
            .map { canonical(it.personId) }
            .toSet()
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.IdentityResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/engine/IdentityResolver.kt android/app/src/test/kotlin/io/github/codex07/iou/ledger/engine/IdentityResolverTest.kt
git commit -m "Add IdentityResolver for canonical people, handle defaults and canonical membership"
```

---

### Task 4: `SplitResolver`

**Files:**
- Create: `main/.../ledger/engine/SplitResolver.kt`
- Test: `test/.../ledger/engine/SplitResolverTest.kt`

**Interfaces:**
- Consumes: `SplitType`, `SplitInput`, `ExpenseShare`, `PersonId` (Task 1), `LedgerResult`, `ledgerResult`, `LedgerError` (Task 2), `MAX_MINOR` (Part 1).
- Produces: `object SplitResolver` with
  - `const val FULL_PERCENT_BP = 10_000L`, `const val MAX_WEIGHT = 1_000_000L`,
  - `fun resolve(type: SplitType, effectTotalMinor: Long, originalTotalMinor: Long, inputs: List<SplitInput>): LedgerResult<List<ExpenseShare>>`: shares in input order, `inputValue` = the input (null for EQUAL), `Σ shareMinor == effectTotalMinor`. EXACT inputs must sum to `originalTotalMinor` and then act as weights over `effectTotalMinor` (for an unconverted expense the two totals are equal and the shares equal the inputs).
  - `fun largestRemainder(total: Long, weights: List<Pair<PersonId, Long>>): List<Long>`.
- Callers pass canonical, distinct participants (combining merged records first, spec §4.4); duplicates → `DuplicateParticipant`.

Error precedence, checked in this order: `NoParticipants`, `DuplicateParticipant`, `NegativeInput`, `MissingInput` (not for EQUAL), then the type-specific check.

- [ ] **Step 1: Write the failing test**

`test/.../ledger/engine/SplitResolverTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.MAX_MINOR
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigInteger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SplitResolverTest {

    private val a = PersonId("person-a")
    private val b = PersonId("person-b")
    private val c = PersonId("person-c")

    private fun shares(result: LedgerResult<List<ExpenseShare>>): Map<PersonId, Long> =
        (result as LedgerResult.Ok).value.associate { it.personId to it.shareMinor }

    @Test
    fun `equal split of 100,00 among three gives the extra cent to the lowest id`() {
        val inputs = listOf(SplitInput(c), SplitInput(a), SplitInput(b))
        val result = SplitResolver.resolve(SplitType.EQUAL, 10_000, 10_000, inputs)

        shares(result) shouldBe mapOf(a to 3334L, b to 3333L, c to 3333L)
        (result as LedgerResult.Ok).value.map { it.personId } shouldBe listOf(c, a, b)
        result.value.map { it.inputValue } shouldBe listOf(null, null, null)
    }

    @Test
    fun `percent uses basis points`() {
        val inputs = listOf(SplitInput(a, 5_000), SplitInput(b, 2_500), SplitInput(c, 2_500))
        shares(SplitResolver.resolve(SplitType.PERCENT, 1_001, 1_001, inputs)) shouldBe
            mapOf(a to 501L, b to 250L, c to 250L)
    }

    @Test
    fun `percent must sum to ten thousand basis points`() {
        val inputs = listOf(SplitInput(a, 5_000), SplitInput(b, 4_999))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, inputs) shouldBe
            LedgerResult.Err(LedgerError.PercentNotHundred(9_999))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, listOf(SplitInput(a, 10_001))) shouldBe
            LedgerResult.Err(LedgerError.WeightOutOfRange(a, 10_001))
    }

    @Test
    fun `shares split non-terminating fractions exactly`() {
        val inputs = listOf(SplitInput(a, 1), SplitInput(b, 1), SplitInput(c, 1))
        shares(SplitResolver.resolve(SplitType.SHARES, 100, 100, inputs)) shouldBe mapOf(a to 34L, b to 33L, c to 33L)
    }

    @Test
    fun `shares weights are bounded and may not all be zero`() {
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 1_000_001))) shouldBe
            LedgerResult.Err(LedgerError.WeightOutOfRange(a, 1_000_001))
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 0), SplitInput(b, 0))) shouldBe
            LedgerResult.Err(LedgerError.ZeroWeights)
        shares(SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 0), SplitInput(b, 3)))) shouldBe
            mapOf(a to 0L, b to 100L)
    }

    @Test
    fun `exact inputs must sum to the paid total`() {
        SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 5_000), SplitInput(b, 4_000))) shouldBe
            LedgerResult.Err(LedgerError.ExactRemaining(1_000))
        SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 6_000), SplitInput(b, 5_000))) shouldBe
            LedgerResult.Err(LedgerError.ExactRemaining(-1_000))
        shares(SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 2_500), SplitInput(b, 7_500)))) shouldBe
            mapOf(a to 2_500L, b to 7_500L)
    }

    @Test
    fun `exact amounts in a converted expense become weights over the converted total`() {
        // 1 000.00 SEK split 500/250/250, converted to 87.65 EUR.
        val inputs = listOf(SplitInput(a, 50_000), SplitInput(b, 25_000), SplitInput(c, 25_000))
        val result = SplitResolver.resolve(SplitType.EXACT, 8_765, 100_000, inputs)

        shares(result) shouldBe mapOf(a to 4_383L, b to 2_191L, c to 2_191L)
        (result as LedgerResult.Ok).value.map { it.inputValue } shouldBe listOf(50_000L, 25_000L, 25_000L)
    }

    @Test
    fun `input errors come in a fixed order`() {
        SplitResolver.resolve(SplitType.EQUAL, 100, 100, emptyList()) shouldBe LedgerResult.Err(LedgerError.NoParticipants)
        SplitResolver.resolve(SplitType.EQUAL, 100, 100, listOf(SplitInput(a), SplitInput(a))) shouldBe
            LedgerResult.Err(LedgerError.DuplicateParticipant(a))
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, -1), SplitInput(b))) shouldBe
            LedgerResult.Err(LedgerError.NegativeInput(a))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, listOf(SplitInput(a, 10_000), SplitInput(b))) shouldBe
            LedgerResult.Err(LedgerError.MissingInput(b))
        SplitResolver.resolve(SplitType.EXACT, 100, 100, listOf(SplitInput(a, MAX_MINOR + 1))) shouldBe
            LedgerResult.Err(LedgerError.AmountTooLarge)
    }

    @Test
    fun `largest remainder always sums exactly and stays within one unit of the exact share`() = runTest {
        checkAll(
            Arb.long(0L..MAX_MINOR),
            Arb.list(Arb.long(0L..SplitResolver.MAX_WEIGHT), 1..12),
        ) { total, rawWeights ->
            val weights = if (rawWeights.all { it == 0L }) listOf(1L) + rawWeights.drop(1) else rawWeights
            val people = weights.indices.map { PersonId("person-%03d".format(it)) }
            val amounts = SplitResolver.largestRemainder(total, people.zip(weights))

            amounts.sum() shouldBe total
            val weightSum = BigInteger.valueOf(weights.sum())
            amounts.zip(weights).forEach { (amount, weight) ->
                amount shouldBeGreaterThanOrEqual 0L
                val exactNumerator = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weight))
                val deviation = BigInteger.valueOf(amount).multiply(weightSum).subtract(exactNumerator).abs()
                (deviation < weightSum) shouldBe true
            }
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.SplitResolverTest"
```

Expected: compilation fails, `Unresolved reference 'SplitResolver'`.

- [ ] **Step 3: Implement**

`main/.../ledger/engine/SplitResolver.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.MAX_MINOR
import java.math.BigInteger

/** Exact split resolution (spec §4.4). */
object SplitResolver {

    const val FULL_PERCENT_BP = 10_000L
    const val MAX_WEIGHT = 1_000_000L

    fun resolve(
        type: SplitType,
        effectTotalMinor: Long,
        originalTotalMinor: Long,
        inputs: List<SplitInput>,
    ): LedgerResult<List<ExpenseShare>> = ledgerResult {
        ensure(inputs.isNotEmpty()) { LedgerError.NoParticipants }
        inputs.groupBy { it.personId }.entries.firstOrNull { it.value.size > 1 }
            ?.let { fail(LedgerError.DuplicateParticipant(it.key)) }
        inputs.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
        if (type != SplitType.EQUAL) {
            inputs.firstOrNull { it.value == null }?.let { fail(LedgerError.MissingInput(it.personId)) }
        }

        val weights: List<Long> = when (type) {
            SplitType.EQUAL -> inputs.map { 1L }
            SplitType.PERCENT -> {
                inputs.firstOrNull { it.value!! > FULL_PERCENT_BP }
                    ?.let { fail(LedgerError.WeightOutOfRange(it.personId, it.value!!)) }
                val sum = inputs.sumOf { it.value!! }
                ensure(sum == FULL_PERCENT_BP) { LedgerError.PercentNotHundred(sum) }
                inputs.map { it.value!! }
            }
            SplitType.SHARES -> {
                inputs.firstOrNull { it.value!! > MAX_WEIGHT }
                    ?.let { fail(LedgerError.WeightOutOfRange(it.personId, it.value!!)) }
                ensure(inputs.sumOf { it.value!! } > 0L) { LedgerError.ZeroWeights }
                inputs.map { it.value!! }
            }
            SplitType.EXACT -> {
                ensure(inputs.all { it.value!! <= MAX_MINOR }) { LedgerError.AmountTooLarge }
                val sum = inputs.sumOf { it.value!! }
                ensure(sum == originalTotalMinor) { LedgerError.ExactRemaining(originalTotalMinor - sum) }
                ensure(sum > 0L) { LedgerError.ZeroWeights }
                inputs.map { it.value!! }
            }
        }

        val amounts = largestRemainder(effectTotalMinor, inputs.map { it.personId }.zip(weights))
        inputs.mapIndexed { index, input ->
            ExpenseShare(input.personId, amounts[index], if (type == SplitType.EQUAL) null else input.value)
        }
    }

    /**
     * Distributes [total] in proportion to the weights: floors first, then +1 to the largest
     * remainders, ties to the lowest person ID (string order). Exact integer arithmetic only.
     * Results are in input order.
     */
    fun largestRemainder(total: Long, weights: List<Pair<PersonId, Long>>): List<Long> {
        val totalBig = BigInteger.valueOf(total)
        val weightSum = weights.fold(BigInteger.ZERO) { acc, (_, weight) -> acc + BigInteger.valueOf(weight) }
        require(weights.all { it.second >= 0L }) { "Weights must not be negative" }
        require(weightSum.signum() > 0) { "Weights must not all be zero" }

        val parts = weights.map { (_, weight) -> totalBig.multiply(BigInteger.valueOf(weight)).divideAndRemainder(weightSum) }
        // Each floor is at most |total|, so it fits a Long. Not longValueExact(): BigInteger's needs API 31 (minSdk is 30).
        val result = parts.map { it[0].toLong() }.toMutableList()
        var leftover = total - result.sum()

        val byRemainder = weights.indices.sortedWith(
            compareByDescending<Int> { parts[it][1] }.thenBy { weights[it].first.value },
        )
        for (index in byRemainder) {
            if (leftover == 0L) break
            result[index] += 1
            leftover--
        }
        return result
    }
}
```

`total − Σfloor` is `Σremainder / W`, which is smaller than the number of non-zero remainders, so a zero-weight participant never receives a leftover unit.

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.SplitResolverTest"
```

Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/engine/SplitResolver.kt android/app/src/test/kotlin/io/github/codex07/iou/ledger/engine/SplitResolverTest.kt
git commit -m "Add SplitResolver with exact largest-remainder rounding"
```

---

### Task 5: `FxConverter`

**Files:**
- Create: `main/.../ledger/engine/FxConverter.kt`
- Test: `test/.../ledger/engine/FxConverterTest.kt`

**Interfaces:**
- Consumes: `Money`, `CurrencyCode`, `MAX_MINOR` (Part 1), `LedgerResult`, `LedgerError` (Task 2).
- Produces: `object FxConverter` with
  - `const val MAX_RATE_DIGITS = 34`, `const val MAX_RATE_SCALE = 64`,
  - `fun validateRate(rate: String): LedgerResult<BigDecimal>` (`InvalidRate(rate)` on failure),
  - `fun convert(amount: Money, to: CurrencyCode, rate: String): LedgerResult<Money>`,
  - `fun deriveRate(paid: Money, charged: Money): String` (charged ÷ paid in major units, `DECIMAL128`; requires `paid.amountMinor > 0`),
  - `fun invert(rate: String): String` (1 ÷ rate, `DECIMAL128`),
  - `fun cross(homeToBase: String, homeToQuote: String): String` (rate base→quote from two rates quoted against the home currency, `DECIMAL128`).
  Rate strings it produces are `stripTrailingZeros().toPlainString()`.

- [ ] **Step 1: Write the failing test**

`test/.../ledger/engine/FxConverterTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.BHD
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.IDR
import io.github.codex07.iou.testutil.JPY
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class FxConverterTest {

    @Test
    fun `converts across differing minor-unit digits`() {
        FxConverter.convert(Money(100, JPY), EUR, "0.006") shouldBe LedgerResult.Ok(Money(60, EUR))
        FxConverter.convert(Money(100_000, SEK), EUR, "0.09") shouldBe LedgerResult.Ok(Money(9_000, EUR))
        FxConverter.convert(Money(1_000, BHD), JPY, "400") shouldBe LedgerResult.Ok(Money(400, JPY))
    }

    @Test
    fun `rounds half to even once, at the end`() {
        FxConverter.convert(Money(25, EUR), USD, "0.1") shouldBe LedgerResult.Ok(Money(2, USD))
        FxConverter.convert(Money(35, EUR), USD, "0.1") shouldBe LedgerResult.Ok(Money(4, USD))
    }

    @Test
    fun `a positive amount that converts to zero is rejected`() {
        FxConverter.convert(Money(100, IDR), EUR, "0.000055") shouldBe
            LedgerResult.Err(LedgerError.ConvertedToZero(Money(100, IDR), EUR))
    }

    @Test
    fun `results above MAX_MINOR are rejected`() {
        FxConverter.convert(Money(MAX_MINOR, EUR), USD, "1.0000001") shouldBe LedgerResult.Err(LedgerError.AmountTooLarge)
        FxConverter.convert(Money(MAX_MINOR, EUR), USD, "1") shouldBe LedgerResult.Ok(Money(MAX_MINOR, USD))
    }

    @Test
    fun `invalid rates are rejected`() {
        val thirtyFiveDigits = "1." + "0".repeat(33) + "1"
        val thirtyFourDigits = "1." + "0".repeat(32) + "1"
        listOf("0", "-1", "abc", "", thirtyFiveDigits, "1E65", "1E-65", "1E999999999", "1E-999999999").forEach { rate ->
            FxConverter.validateRate(rate) shouldBe LedgerResult.Err(LedgerError.InvalidRate(rate))
            FxConverter.convert(Money(100, EUR), USD, rate) shouldBe LedgerResult.Err(LedgerError.InvalidRate(rate))
        }
        FxConverter.validateRate("0.09") shouldBe LedgerResult.Ok(BigDecimal("0.09"))
        FxConverter.validateRate(thirtyFourDigits) shouldBe LedgerResult.Ok(BigDecimal(thirtyFourDigits))
        FxConverter.validateRate("1E64") shouldBe LedgerResult.Ok(BigDecimal("1E64"))
        FxConverter.validateRate("1E-64") shouldBe LedgerResult.Ok(BigDecimal("1E-64"))
    }

    @Test
    fun `derives the bank-actual rate from paid and charged amounts`() {
        FxConverter.deriveRate(Money(100_000, SEK), Money(8_765, EUR)) shouldBe "0.08765"
        FxConverter.deriveRate(Money(100, JPY), Money(60, EUR)) shouldBe "0.006"
    }

    @Test
    fun `inverts and crosses rates with DECIMAL128`() {
        FxConverter.invert("0.08") shouldBe "12.5"
        FxConverter.invert("3") shouldBe "0." + "3".repeat(34)
        // 1 EUR = 11.5 SEK and 1 EUR = 1.15 USD, so 1 SEK = 0.1 USD.
        FxConverter.cross(homeToBase = "11.5", homeToQuote = "1.15") shouldBe "0.1"
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.FxConverterTest"
```

Expected: compilation fails, `Unresolved reference 'FxConverter'`.

- [ ] **Step 3: Implement**

`main/.../ledger/engine/FxConverter.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** Exact currency conversion (spec §4.5). A rate is quote major units per one base major unit. */
object FxConverter {

    const val MAX_RATE_DIGITS = 34

    /** Bounds the rate's exponent so conversion never builds huge numbers (e.g. "1E999999999"). */
    const val MAX_RATE_SCALE = 64

    fun validateRate(rate: String): LedgerResult<BigDecimal> {
        val parsed = runCatching { BigDecimal(rate.trim()) }.getOrNull()
        val stripped = parsed?.stripTrailingZeros()
        val valid = parsed != null && parsed.signum() > 0 &&
            stripped!!.precision() <= MAX_RATE_DIGITS && abs(stripped.scale()) <= MAX_RATE_SCALE
        return if (valid) LedgerResult.Ok(parsed!!) else LedgerResult.Err(LedgerError.InvalidRate(rate))
    }

    /** `HALF_EVEN(amountMinor × rate × 10^(fd(to) − fd(from)))`, exact up to that one rounding. */
    fun convert(amount: Money, to: CurrencyCode, rate: String): LedgerResult<Money> = ledgerResult {
        val parsedRate = validateRate(rate).bind()
        val shift = to.fractionDigits - amount.currency.fractionDigits
        val rounded = BigDecimal.valueOf(amount.amountMinor)
            .multiply(parsedRate)
            .scaleByPowerOfTen(shift)
            .setScale(0, RoundingMode.HALF_EVEN)
        ensure(rounded <= BigDecimal.valueOf(MAX_MINOR)) { LedgerError.AmountTooLarge }
        val minor = rounded.longValueExact()
        ensure(!(amount.amountMinor > 0 && minor == 0L)) { LedgerError.ConvertedToZero(amount, to) }
        Money(minor, to)
    }

    /** Display rate of a bank-actual snapshot: charged ÷ paid in major units (spec §4.5). */
    fun deriveRate(paid: Money, charged: Money): String {
        require(paid.amountMinor > 0) { "Paid amount must be positive" }
        val shift = paid.currency.fractionDigits - charged.currency.fractionDigits
        return BigDecimal.valueOf(charged.amountMinor)
            .scaleByPowerOfTen(shift)
            .divide(BigDecimal.valueOf(paid.amountMinor), MathContext.DECIMAL128)
            .canonical()
    }

    fun invert(rate: String): String =
        BigDecimal.ONE.divide(BigDecimal(rate), MathContext.DECIMAL128).canonical()

    /** Rate base→quote from `1 home = homeToBase base` and `1 home = homeToQuote quote`. */
    fun cross(homeToBase: String, homeToQuote: String): String =
        BigDecimal(homeToQuote).divide(BigDecimal(homeToBase), MathContext.DECIMAL128).canonical()

    private fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.FxConverterTest"
```

Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 5: Run everything, commit, push**

```bash
./gradlew testFakeDebugUnitTest
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/engine/FxConverter.kt android/app/src/test/kotlin/io/github/codex07/iou/ledger/engine/FxConverterTest.kt
git commit -m "Add FxConverter for exact conversion and derived, inverted and cross rates"
git push origin main
```

Expected: all unit tests pass; CI goes green on the push.

---

## Part 2 done when

- `ledger.model`, `api.LedgerResult`/`LedgerError`, `IdentityResolver`, `SplitResolver`, `FxConverter` exist with the signatures above.
- `./gradlew testFakeDebugUnitTest` passes, including the purity check over `ledger` and `api`.

Next: Part 3 (`2026-11-02-m0-03-api-contract.md`).
