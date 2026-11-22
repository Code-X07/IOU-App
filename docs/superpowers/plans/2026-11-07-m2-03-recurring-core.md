# M2 Part 3: Recurring Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-07-m2-00-index.md` first: its locked decisions and type inventory apply here. This part starts from the code as Parts 1 and 2 leave it.

**Goal:** Schedules exist in the pure core. `RecurringExpense` with its `RecurringParticipant` rows is a ledger entity; expenses carry `recurringId` / `recurringOn`; occurrence dates, the handled date, due dates and holds are computed (spec §5.7); the rules cover schedules (0, 1, 3, 10, 15, 18 and the posting order of 19); `RecurringOps` saves, deletes, restores, posts and skips; deleting a posted expense raises `handledThrough`; the cut-off rule and hold resumes run on every write the store commits; and `LedgerViews` serves schedule lists, details, the due list and occurrence drafts. `RecurringApi`, persistence and the runner come in Part 4.

**Architecture:** Model types in `ledger.model`, date logic in a pure `ledger.engine.Recurrence` object, rules in the existing rule objects, writes in a new `ledger.ops.RecurringOps`. `RecurringOps.finalize(ctx, plan)` is called by `LedgerStore.writeWith` on every successful op (and by the test `OpsHarness`), after the op built and validated its plan: it adds a resume for each schedule the write takes off hold and applies the cut-off rule. It changes only `autoPostFrom` and sync columns, which no rule reads, so the finalized plan needs no second validation (this settles the open point of index locked decision 7). Posting and skipping mark their schedule change with `Change.keepsCutOff`. Read models live in `api/Recurring.kt`; the views in a new `ledger/views/ScheduleViews.kt` take `today` as a parameter.

**Tech Stack:** Kotlin, `java.time`, JUnit 5, Kotest, coroutines test.

## Global Constraints

- **Occurrences (spec §5.7):** occurrence n = `startOn` plus n × `interval` units (`plusWeeks` / `plusMonths` / `plusYears`), always counted from `startOn`; none after `endOn`. Handled date = the latest of `handledThrough` and the `recurringOn` of every non-deleted expense posted from the schedule. Next = the first occurrence after the handled date (the first one when nothing was handled), null once past `endOn`. Due = from the next one through today, oldest first.
- **On hold:** the schedule's group is archived, or the canonical person of any participant (payer or share) is archived. Held and deleted schedules have no due occurrences in read models.
- **Posting (rule 19):** only the next occurrence (also before its date), only while the schedule is not deleted (`NotFound` otherwise), never past `endOn`; one write creates the expense (reason "Posted from <schedule description>", `recurringId`, `recurringOn`) and moves `handledThrough` to the occurrence (reason "Posted <date>"). Skipping moves `handledThrough` to an occurrence date from the next one on (reason "Skipped through <date>"). Out of order → `OccurrenceNotDue(nextDue)`.
- **Links are permanent:** an expense keeps `recurringId` / `recurringOn` through every edit and revert; a schedule's revert keeps `handledThrough` and `autoPostFrom`.
- **Deleting a posted expense** through the `api` raises its schedule's `handledThrough` to the expense's `recurringOn` when lower, in the same write (reason "Handled through <date>: posted expense deleted").
- **Cut-off rule:** every schedule change in a committed plan except posting, skipping and the raise above sets `autoPostFrom` to today when the schedule auto-posts afterwards (not deleted, `autoPost` on, not on hold). A new confirm-first schedule gets today too; an edit keeps the stored value unless the cut-off applies.
- **Resumes:** each non-deleted schedule that a write takes off hold without changing it is written in the same plan (`UPDATED`, reason "Resumed: <cause>", causes like "Flat unarchived", "Old merged into New", "Patsy unmerged from Pat", joined with "; ", or just "Resumed"); the cut-off rule then applies to it. A write that starts a hold writes no schedule.
- **Deleting references:** a person or group referenced by any schedule, deleted ones included, can't be deleted through the `api` (`StillReferenced`).
- **`today`** comes from `WriteContext.today` (the store's `today()`, device zone; index locked decision 6), never from a clock.
- No `ui/` file except `ui/forms/LedgerErrorMessage.kt` (one line for `OccurrenceNotDue`).

## File map

| File | Change |
|---|---|
| `main/.../ledger/model/Ids.kt`, `Enums.kt`, `Entities.kt`, `LedgerState.kt`, `Records.kt` | `RecurringId`, `RecurrenceUnit`, `ParticipantRole`, `EntityType.RECURRING_EXPENSE`, `RecurringParticipant`, `RecurringExpense`, `Expense.recurringId` / `recurringOn`, `LedgerState.schedules`, `Change.keepsCutOff` |
| `main/.../ledger/ops/WriteContext.kt` | `WriteContext.today` |
| `main/.../ledger/engine/Recurrence.kt` (new) | Occurrences, handled date, next, due, holds |
| `main/.../ledger/engine/RevisionDiff.kt` | Schedule fields and expense links |
| `main/.../ledger/rules/FieldRules.kt`, `StateInvariants.kt`, `EditRules.kt`, `LedgerValidator.kt` | Rules 0, 1, 3, 10, 15, 18 for schedules |
| `main/.../api/Recurring.kt` (new), `api/LedgerError.kt`, `api/Drafts.kt`, `api/ReadModels.kt` | `RecurringDraft`, `ScheduleSaveMode`, read models, `OccurrenceNotDue`, `VersionDraft.OfSchedule`, `ActivityItem.recurringId` / `recurringOn` |
| `main/.../ui/forms/LedgerErrorMessage.kt` | One line for `OccurrenceNotDue` |
| `main/.../ledger/ops/RecurringOps.kt` (new), `EntryOps.kt`, `RevertOps.kt` | Save, delete, restore, post, skip, finalize; expense links kept; delete raises `handledThrough` |
| `main/.../store/LedgerStore.kt` | `today` in the context; `finalize` on every write |
| `main/.../data/SnapshotCodec.kt` | Temporary: schedule snapshots refused until Part 4 |
| `main/.../ledger/views/ScheduleViews.kt` (new), `EntryViews.kt` | Schedule list, detail, due list, occurrence draft, old versions as drafts, subjects |
| `test/...` | `RecurrenceTest`, `ScheduleRulesTest`, `RecurringOpsTest`, `PostingTest`, `CutOffTest`, `ScheduleViewsTest` (new); `TestLedger`, `OpsHarness` |
| `docs/superpowers/specs/2026-11-02-iou-mvp-design.md` §5.7 | The `handledThrough` raise keeps the cut-off |

Paths abbreviate `android/app/src/main/kotlin/io/github/codex07/iou/` as `main/...` and the test root likewise. Commands run from `android/` with `JAVA_HOME` set to the MS JDK 21. The real flavor stores no schedule rows until Part 4; nothing can create one through the `api` before then.

---

### Task 1: Schedules in the model, and occurrence dates

**Files:**
- Modify: `main/.../ledger/model/Ids.kt`, `main/.../ledger/model/Enums.kt`, `main/.../ledger/model/Entities.kt`, `main/.../ledger/model/LedgerState.kt`, `main/.../ledger/model/Records.kt`, `main/.../ledger/ops/WriteContext.kt`, `main/.../ledger/engine/RevisionDiff.kt`, `main/.../ledger/rules/FieldRules.kt`, `main/.../ledger/rules/EditRules.kt`, `main/.../ledger/views/EntryViews.kt`, `main/.../data/SnapshotCodec.kt`
- Create: `main/.../ledger/engine/Recurrence.kt`, `test/.../ledger/engine/RecurrenceTest.kt`
- Modify (tests): `test/.../testutil/TestLedger.kt`, `test/.../ledger/ops/OpsHarness.kt`

**Interfaces:**
- Consumes (Part 1): `PayerSplit`, `ExpensePayer`, `Expense.payers`, `TestLedger.expense(..., payers, payerSplit)`.
- Produces: `RecurringId`; `enum class RecurrenceUnit { WEEK, MONTH, YEAR }`; `enum class ParticipantRole { PAYER, SHARE }`; `EntityType.RECURRING_EXPENSE`; `RecurringParticipant(role, personId, inputValue = null)`; `RecurringExpense(id, groupId, description, category, note, total, payerSplit, splitType, participants, unit, interval, startOn, endOn, handledThrough, autoPostFrom, autoPost, meta)` with `payerRows`, `shareRows`, `personIds`; `Expense.recurringId: RecurringId?`, `Expense.recurringOn: LocalDate?` (after `meta`, default null); `LedgerState.schedules`; `Change.keepsCutOff: Boolean = false`; `WriteContext.today: LocalDate`; `Recurrence.occurrence(schedule, n: Long)`, `firstAfter(schedule, after: LocalDate?)`, `isOccurrence(schedule, date)`, `handledDate(schedule, state)`, `next(schedule, state)`, `due(schedule, state, today)`, `holds(schedule, state, resolver)`, `autoPosts(schedule, state, resolver)`, `Recurrence.Hold.GroupArchived(groupId)` / `PersonArchived(personId)`; `TestLedger.schedule(...)`, `TestLedger.group(..., archived)`, `TestLedger.expense(..., occurredOn, recurring, recurringOn)`; `OpsHarness.today`.
- Temporary until Task 2: `FieldRules` checks nothing on a schedule. Temporary until Part 4: `SnapshotCodec.encode` refuses a schedule.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/engine/RecurrenceTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Occurrence dates, the handled date, due dates and holds (spec §5.7). */
class RecurrenceTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `monthly occurrences from 31 January clamp to month ends without drifting and stop at endOn`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), startOn = d("2026-01-31"), endOn = d("2026-04-30"))
        val rent = ledger.state.schedules.getValue(id)

        (0L..3L).map { Recurrence.occurrence(rent, it) } shouldBe
            listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30"))
        Recurrence.firstAfter(rent, null) shouldBe d("2026-01-31")
        Recurrence.firstAfter(rent, d("2026-02-28")) shouldBe d("2026-03-31")
        Recurrence.firstAfter(rent, d("2026-04-30")) shouldBe null
        Recurrence.isOccurrence(rent, d("2026-02-28")) shouldBe true
        Recurrence.isOccurrence(rent, d("2026-02-27")) shouldBe false
        Recurrence.isOccurrence(rent, d("2026-05-31")) shouldBe false
        Recurrence.occurrence(rent.copy(startOn = d("2028-01-31")), 1) shouldBe d("2028-02-29")
        val fortnightly = rent.copy(unit = RecurrenceUnit.WEEK, interval = 2, startOn = D0, endOn = null)
        Recurrence.firstAfter(fortnightly, d("2027-06-01")) shouldBe d("2027-06-03")
    }

    @Test
    fun `the next occurrence follows the later of handledThrough and the live posted expenses`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), handledThrough = d("2026-02-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-03-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-04-01"), deleted = true)
        val schedule = ledger.state.schedules.getValue(id)

        Recurrence.handledDate(schedule, ledger.state) shouldBe d("2026-03-01")
        Recurrence.next(schedule, ledger.state) shouldBe d("2026-04-01")
        Recurrence.due(schedule, ledger.state, d("2026-05-15")) shouldBe listOf(d("2026-04-01"), d("2026-05-01"))
        Recurrence.due(schedule, ledger.state, d("2026-03-31")) shouldBe emptyList()
        Recurrence.next(schedule.copy(handledThrough = null), LedgerState()) shouldBe D0
    }

    @Test
    fun `a changed pattern skips nothing - the next date is the new pattern's first after the handled date`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-01-01"), handledThrough = d("2026-12-01"))
        val rent = ledger.state.schedules.getValue(id)

        Recurrence.next(rent, ledger.state) shouldBe d("2027-01-01")
        Recurrence.next(rent.copy(startOn = d("2026-01-05")), ledger.state) shouldBe d("2026-12-05")
        Recurrence.next(rent.copy(endOn = d("2026-11-30")), ledger.state) shouldBe null
    }

    @Test
    fun `a schedule is on hold while its group or the canonical person of one of its people is archived`() {
        val ledger = TestLedger().apply { me() }
        val bea = ledger.person("Bea", archived = true)
        val alias = ledger.person("Bea2", mergedInto = bea)
        val chen = ledger.person("Chen")
        val flat = ledger.group("Flat", members = listOf(ME, bea, alias, chen))
        val trip = ledger.group("Trip", members = listOf(ME, chen), archived = true)
        val viaAlias = ledger.schedule(alias, eur(1_000), listOf(ME, chen), group = flat)
        val inTrip = ledger.schedule(ME, eur(1_000), listOf(ME, chen), group = trip)
        val free = ledger.schedule(ME, eur(1_000), listOf(ME, chen), group = flat)
        val state = ledger.state
        fun holds(id: RecurringId) = Recurrence.holds(state.schedules.getValue(id), state)

        holds(viaAlias) shouldBe listOf(Recurrence.Hold.PersonArchived(bea))
        holds(inTrip) shouldBe listOf(Recurrence.Hold.GroupArchived(trip))
        holds(free) shouldBe emptyList()
        Recurrence.autoPosts(state.schedules.getValue(free).copy(autoPost = true), state) shouldBe true
        Recurrence.autoPosts(state.schedules.getValue(free), state) shouldBe false
        Recurrence.autoPosts(state.schedules.getValue(viaAlias).copy(autoPost = true), state) shouldBe false
    }

    @Test
    fun `schedules are diffed per field and per participant, without autoPostFrom`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val beforeId = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val before = ledger.state.schedules.getValue(beforeId)
        val after = before.copy(
            interval = 2,
            handledThrough = d("2026-03-01"),
            autoPostFrom = d("2026-09-01"),
            participants = before.participants + RecurringParticipant(ParticipantRole.SHARE, y),
        )

        val changes = RevisionDiff.diff(before, after)

        changes shouldContain FieldChange("interval", "1", "2")
        changes shouldContain FieldChange("handledThrough", null, "2026-03-01")
        changes shouldContain FieldChange("participant[${y.value}]", null, "yes")
        changes.none { it.field == "autoPostFrom" } shouldBe true
    }
}
```

In `test/.../testutil/TestLedger.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole`, `io.github.codex07.iou.ledger.model.RecurrenceUnit`, `io.github.codex07.iou.ledger.model.RecurringExpense`, `io.github.codex07.iou.ledger.model.RecurringId` and `io.github.codex07.iou.ledger.model.RecurringParticipant`.

Let `group` build archived groups. Replace:

```kotlin
        deleted: Boolean = false,
        id: String = "group-${name.lowercase()}",
    ): GroupId {
        val groupId = GroupId(id)
        state = state.with(Group(groupId, name, currency, fxMode, meta = testMeta(deleted)))
```

with:

```kotlin
        deleted: Boolean = false,
        id: String = "group-${name.lowercase()}",
        archived: Boolean = false,
    ): GroupId {
        val groupId = GroupId(id)
        state = state.with(Group(groupId, name, currency, fxMode, archivedAt = if (archived) T0 else null, meta = testMeta(deleted)))
```

Let `expense` take a date and a schedule link. Replace (Part 1's parameters):

```kotlin
        payers: List<ExpensePayer>? = null,
        payerSplit: PayerSplit = PayerSplit.EQUAL,
    ): EntryId {
```

with:

```kotlin
        payers: List<ExpensePayer>? = null,
        payerSplit: PayerSplit = PayerSplit.EQUAL,
        occurredOn: LocalDate = D0,
        recurring: RecurringId? = null,
        recurringOn: LocalDate? = null,
    ): EntryId {
```

and, inside that function's `Expense(...)`, replace:

```kotlin
                occurredOn = D0, meta = testMeta(deleted),
```

with:

```kotlin
                occurredOn = occurredOn, meta = testMeta(deleted), recurringId = recurring, recurringOn = recurringOn,
```

Add this builder before the last closing brace of `TestLedger`:

```kotlin

    /** A schedule: [payer] pays (EQUAL), [participants] share (EQUAL); monthly from [startOn] by default. */
    fun schedule(
        payer: PersonId,
        total: Money,
        participants: List<PersonId>,
        group: GroupId? = null,
        unit: RecurrenceUnit = RecurrenceUnit.MONTH,
        interval: Int = 1,
        startOn: LocalDate = D0,
        endOn: LocalDate? = null,
        handledThrough: LocalDate? = null,
        autoPost: Boolean = false,
        autoPostFrom: LocalDate = D0,
        deleted: Boolean = false,
        id: String = nextId("schedule"),
    ): RecurringId {
        val scheduleId = RecurringId(id)
        state = state.with(
            RecurringExpense(
                id = scheduleId, groupId = group, description = "Schedule $id", total = total, splitType = SplitType.EQUAL,
                participants = listOf(RecurringParticipant(ParticipantRole.PAYER, payer)) +
                    participants.map { RecurringParticipant(ParticipantRole.SHARE, it) },
                unit = unit, interval = interval, startOn = startOn, endOn = endOn, handledThrough = handledThrough,
                autoPostFrom = autoPostFrom, autoPost = autoPost, meta = testMeta(deleted),
            ),
        )
        return scheduleId
    }
```

In `test/.../ledger/ops/OpsHarness.kt`, add the imports `io.github.codex07.iou.testutil.D0` and `java.time.LocalDate`; replace:

```kotlin
    var now: Instant = T0.plusSeconds(60)
```

with:

```kotlin
    var now: Instant = T0.plusSeconds(60)
    var today: LocalDate = D0
```

replace:

```kotlin
        val entities: List<LedgerEntity> = initial.people.values + initial.handles.values + initial.groups.values +
            initial.members.values + initial.entries
```

with:

```kotlin
        val entities: List<LedgerEntity> = initial.people.values + initial.handles.values + initial.groups.values +
            initial.members.values + initial.entries + initial.schedules.values
```

and replace:

```kotlin
    fun ctx(): WriteContext = WriteContext(state, generation, now, ids)
```

with:

```kotlin
    fun ctx(): WriteContext = WriteContext(state, generation, now, ids, today)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.RecurrenceTest"`
Expected: FAIL, compilation errors `Unresolved reference 'RecurringId'`, `'RecurrenceUnit'`, `'Recurrence'`, `'schedules'`.

- [ ] **Step 3: Add the model types**

In `main/.../ledger/model/Ids.kt`, after the `ImportBatchId` line, add:

```kotlin
@JvmInline value class RecurringId(val value: String) { override fun toString(): String = value }
```

In `main/.../ledger/model/Enums.kt`, replace:

```kotlin
enum class EntityType { PERSON, PAYMENT_HANDLE, GROUP, GROUP_MEMBER, TRANSFER, EXPENSE }
```

with:

```kotlin
enum class EntityType { PERSON, PAYMENT_HANDLE, GROUP, GROUP_MEMBER, TRANSFER, EXPENSE, RECURRING_EXPENSE }
```

and append at the end of the file:

```kotlin

/** A schedule's repeat unit (spec §3.2): occurrences are `startOn` plus n × interval of these. */
enum class RecurrenceUnit { WEEK, MONTH, YEAR }

/** Whether a schedule's template row is a payer or a participant (spec §3.2). */
enum class ParticipantRole { PAYER, SHARE }
```

In `main/.../ledger/model/Entities.kt`, give expenses their schedule link. Replace:

```kotlin
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
) : Entry {
    override val entityType: EntityType get() = EntityType.EXPENSE
```

with:

```kotlin
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
    /** The schedule this expense was posted from (spec §5.7); set exactly when [recurringOn] is. */
    val recurringId: RecurringId? = null,
    /** The occurrence date it fills; never changed by an edit or a revert. */
    val recurringOn: LocalDate? = null,
) : Entry {
    override val entityType: EntityType get() = EntityType.EXPENSE
```

They follow `meta` so that every existing positional `Expense(...)` call stays valid. Append at the end of the file:

```kotlin

/** One template payer or participant of a schedule (spec §3.2): owned and versioned with it, like a share. */
data class RecurringParticipant(
    val role: ParticipantRole,
    val personId: PersonId,
    /** As for an [ExpensePayer] (PAYER) or an [ExpenseShare] (SHARE); null for EQUAL. */
    val inputValue: Long? = null,
)

/**
 * A schedule (spec §3.2, §5.7, ADR-0013): an expense template and a repeat pattern. It stores no rate and no
 * effect amounts. [handledThrough] is the last occurrence posted or skipped; auto-post runs only while the next
 * occurrence is on or after [autoPostFrom] (the cut-off rule).
 */
data class RecurringExpense(
    val id: RecurringId,
    val groupId: GroupId? = null,
    val description: String,
    val category: Category? = null,
    val note: String? = null,
    val total: Money,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
    val splitType: SplitType,
    /** PAYER rows, then SHARE rows, each in their stored order. */
    val participants: List<RecurringParticipant>,
    val unit: RecurrenceUnit,
    val interval: Int,
    val startOn: LocalDate,
    val endOn: LocalDate? = null,
    val handledThrough: LocalDate? = null,
    val autoPostFrom: LocalDate,
    val autoPost: Boolean,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.RECURRING_EXPENSE
    override val entityId: String get() = id.value
    val payerRows: List<RecurringParticipant> get() = participants.filter { it.role == ParticipantRole.PAYER }
    val shareRows: List<RecurringParticipant> get() = participants.filter { it.role == ParticipantRole.SHARE }

    /** Every person the template references, as stored (not resolved through merges). */
    val personIds: Set<PersonId> get() = participants.map { it.personId }.toSet()

    override fun withMeta(meta: SyncMeta): RecurringExpense = copy(meta = meta)
}
```

Replace the whole of `main/.../ledger/model/LedgerState.kt` with:

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
    val schedules: Map<RecurringId, RecurringExpense> = emptyMap(),
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
        EntityType.RECURRING_EXPENSE -> schedules[RecurringId(id)]
    }

    /** Inserts or replaces [entity] in its table. */
    fun with(entity: LedgerEntity): LedgerState = when (entity) {
        is Person -> copy(people = people + (entity.id to entity))
        is PaymentHandle -> copy(handles = handles + (entity.id to entity))
        is Group -> copy(groups = groups + (entity.id to entity))
        is GroupMember -> copy(members = members + (entity.id to entity))
        is Transfer -> copy(transfers = transfers + (entity.id to entity))
        is Expense -> copy(expenses = expenses + (entity.id to entity))
        is RecurringExpense -> copy(schedules = schedules + (entity.id to entity))
    }

    fun with(snapshot: FxSnapshot): LedgerState = copy(fxSnapshots = fxSnapshots + (snapshot.id to snapshot))

    /** The state a write would produce. */
    fun applying(changes: List<Change>, snapshots: List<FxSnapshot> = emptyList()): LedgerState {
        val withSnapshots = snapshots.fold(this) { state, snapshot -> state.with(snapshot) }
        return changes.fold(withSnapshots) { state, change -> state.with(change.entity) }
    }
}
```

In `main/.../ledger/model/Records.kt`, replace:

```kotlin
/** One row a write creates or changes, already carrying its new sync columns. One revision each. */
data class Change(
    val entity: LedgerEntity,
    val action: RevisionAction,
    val reason: String? = null,
)
```

with:

```kotlin
/**
 * One row a write creates or changes, already carrying its new sync columns. One revision each.
 * [keepsCutOff] marks a schedule change that only moves `handledThrough` forward (posting, skipping, the raise
 * when a posted expense is deleted): the cut-off rule leaves its `autoPostFrom` as it is (spec §5.7).
 */
data class Change(
    val entity: LedgerEntity,
    val action: RevisionAction,
    val reason: String? = null,
    val keepsCutOff: Boolean = false,
)
```

In `main/.../ledger/ops/WriteContext.kt`, add the imports `java.time.LocalDate` and `java.time.ZoneOffset`, and replace:

```kotlin
/** Everything an op may read: the current state, the live generation, the time and an ID source. */
data class WriteContext(
    val state: LedgerState,
    val generation: LedgerGeneration,
    val now: Instant,
    val ids: IdGenerator,
) {
```

with:

```kotlin
/**
 * Everything an op may read: the current state, the live generation, the time, an ID source, and [today], the
 * user's calendar date (the store passes `LedgerStore.today()`, in the device zone; index locked decision 6).
 */
data class WriteContext(
    val state: LedgerState,
    val generation: LedgerGeneration,
    val now: Instant,
    val ids: IdGenerator,
    val today: LocalDate = now.atZone(ZoneOffset.UTC).toLocalDate(),
) {
```

- [ ] **Step 4: Compute occurrences, due dates and holds**

Create `main/.../ledger/engine/Recurrence.kt`:

```kotlin
package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringExpense
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A schedule's occurrence dates, handled date, due dates and holds (spec §5.7). Pure. */
object Recurrence {

    /** Why a schedule is on hold: its group, or the canonical person of one of its people, is archived. */
    sealed interface Hold {
        data class GroupArchived(val groupId: GroupId) : Hold
        data class PersonArchived(val personId: PersonId) : Hold
    }

    /**
     * Occurrence [n] (n ≥ 0): `startOn` plus n × `interval` units, always counted from `startOn`, so a month end
     * clamps without drifting (31 Jan, 28 Feb, 31 Mar). Ignores `endOn`.
     */
    fun occurrence(schedule: RecurringExpense, n: Long): LocalDate {
        require(schedule.interval >= 1) { "Schedule ${schedule.id} has interval ${schedule.interval}" }
        val units = Math.multiplyExact(n, schedule.interval.toLong())
        return when (schedule.unit) {
            RecurrenceUnit.WEEK -> schedule.startOn.plusWeeks(units)
            RecurrenceUnit.MONTH -> schedule.startOn.plusMonths(units)
            RecurrenceUnit.YEAR -> schedule.startOn.plusYears(units)
        }
    }

    /** The first occurrence after [after] (the first one when [after] is null); null when it lies after `endOn`. */
    fun firstAfter(schedule: RecurringExpense, after: LocalDate?): LocalDate? {
        var n = if (after == null) 0L else lowerBound(schedule, after)
        var date = occurrence(schedule, n)
        while (after != null && !date.isAfter(after)) {
            n += 1
            date = occurrence(schedule, n)
        }
        val end = schedule.endOn
        return if (end != null && date.isAfter(end)) null else date
    }

    fun isOccurrence(schedule: RecurringExpense, date: LocalDate): Boolean = firstAfter(schedule, date.minusDays(1)) == date

    /** The latest of `handledThrough` and the `recurringOn` of every non-deleted expense posted from [schedule]. */
    fun handledDate(schedule: RecurringExpense, state: LedgerState): LocalDate? {
        val posted = state.expenses.values
            .filter { !it.isDeleted && it.recurringId == schedule.id }
            .mapNotNull { it.recurringOn }
        return (posted + listOfNotNull(schedule.handledThrough)).maxOrNull()
    }

    /** The first occurrence of the current pattern after the handled date; null once the schedule has ended. */
    fun next(schedule: RecurringExpense, state: LedgerState): LocalDate? = firstAfter(schedule, handledDate(schedule, state))

    /** The occurrences from the next one through [today], oldest first. Holds are not considered here. */
    fun due(schedule: RecurringExpense, state: LedgerState, today: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var date = next(schedule, state)
        while (date != null && !date.isAfter(today)) {
            out += date
            date = firstAfter(schedule, date)
        }
        return out
    }

    /** Empty unless [schedule] is on hold: its group first, then archived canonical people by ID. */
    fun holds(schedule: RecurringExpense, state: LedgerState, resolver: IdentityResolver = IdentityResolver(state)): List<Hold> {
        val group = schedule.groupId?.let { state.groups[it] }?.takeIf { it.archivedAt != null }?.let { Hold.GroupArchived(it.id) }
        val people = schedule.personIds.map(resolver::canonical).distinct().sortedBy { it.value }
            .filter { state.people[it]?.archivedAt != null }
            .map { Hold.PersonArchived(it) }
        return listOfNotNull(group) + people
    }

    /** Spec §5.7: a schedule auto-posts when it is not deleted, has `autoPost` on and is not on hold. */
    fun autoPosts(schedule: RecurringExpense, state: LedgerState, resolver: IdentityResolver = IdentityResolver(state)): Boolean =
        !schedule.isDeleted && schedule.autoPost && holds(schedule, state, resolver).isEmpty()

    /** An n whose occurrence is on or before [date], so a long history is not walked one step at a time. */
    private fun lowerBound(schedule: RecurringExpense, date: LocalDate): Long {
        if (!date.isAfter(schedule.startOn)) return 0L
        val unit = when (schedule.unit) {
            RecurrenceUnit.WEEK -> ChronoUnit.WEEKS
            RecurrenceUnit.MONTH -> ChronoUnit.MONTHS
            RecurrenceUnit.YEAR -> ChronoUnit.YEARS
        }
        return maxOf(0L, unit.between(schedule.startOn, date) / schedule.interval - 1)
    }
}
```

`plusMonths` never passes the date `ChronoUnit.MONTHS.between` measured, and later occurrences are never earlier, so the loop in `firstAfter` starts at or before [after] and walks forward a step or two.

- [ ] **Step 5: Give schedules a branch wherever entities are matched**

In `main/.../ledger/engine/RevisionDiff.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole` and `io.github.codex07.iou.ledger.model.RecurringExpense`. Show an expense's link: replace:

```kotlin
            is Expense -> {
                plain("description", entity.description)
```

with:

```kotlin
            is Expense -> {
                plain("schedule", entity.recurringId?.value)
                plain("recurringOn", entity.recurringOn?.toString())
                plain("description", entity.description)
```

and add the schedule branch: replace:

```kotlin
        }
        plain("state", if (entity.isDeleted) "deleted" else "active")
```

with:

```kotlin
            // autoPostFrom is local state (spec §7.4): never diffed or compared.
            is RecurringExpense -> {
                plain("description", entity.description)
                plain("category", entity.category?.name)
                plain("total", entity.total.toString())
                plain("payerSplit", entity.payerSplit.name)
                plain("splitType", entity.splitType.name)
                group("group", entity.groupId)
                plain("note", entity.note)
                plain("unit", entity.unit.name)
                plain("interval", entity.interval.toString())
                plain("startOn", entity.startOn.toString())
                plain("endOn", entity.endOn?.toString())
                plain("handledThrough", entity.handledThrough?.toString())
                plain("autoPost", yesNo(entity.autoPost))
                for (row in entity.participants) {
                    val key = if (row.role == ParticipantRole.PAYER) "payer" else "participant"
                    val value = row.inputValue?.toString() ?: "yes"
                    fields["$key[${row.personId.value}]"] = DiffField("$key[${names.person(row.personId)}]", value, value)
                }
            }
        }
        plain("state", if (entity.isDeleted) "deleted" else "active")
```

In `main/.../ledger/rules/FieldRules.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringExpense` and replace:

```kotlin
            is GroupMember -> emptyList()
```

with:

```kotlin
            is GroupMember -> emptyList()
            is RecurringExpense -> emptyList() // Task 2
```

In `main/.../ledger/rules/EditRules.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringExpense` and replace:

```kotlin
                is PaymentHandle -> Unit
```

with:

```kotlin
                is PaymentHandle -> Unit

                // Template edits break no edit rule; posting checks its expense like any other create (spec §5.7).
                is RecurringExpense -> Unit
```

In `main/.../ledger/views/EntryViews.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringExpense` and replace:

```kotlin
    is Expense -> entity.description
    null -> ref.id
```

with:

```kotlin
    is Expense -> entity.description
    is RecurringExpense -> entity.description
    null -> ref.id
```

In `main/.../data/SnapshotCodec.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringExpense`, and in `encode`, after the `is Expense ->` branch, add:

```kotlin
            // Persisted from M2 Part 4; until then no schedule can reach the real flavor's journal.
            is RecurringExpense -> throw UnsupportedOperationException("Schedule snapshots are stored from M2 Part 4")
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.engine.RecurrenceTest"`
Expected: PASS, 5 tests.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS; nothing else changed behaviour.

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "Add schedules to the ledger model and compute their occurrence dates and holds"
```

---

### Task 2: Rules for schedules and posted expenses

**Files:**
- Modify: `main/.../ledger/rules/FieldRules.kt`, `main/.../ledger/rules/StateInvariants.kt`, `main/.../ledger/rules/EditRules.kt`, `main/.../ledger/rules/LedgerValidator.kt`
- Create: `test/.../ledger/rules/ScheduleRulesTest.kt`

**Interfaces:**
- Consumes: Task 1's model and `TestLedger.schedule`; `PeopleOps.deletePerson`, `GroupOps.deleteGroup`, `OpsHarness`.
- Produces: rule 0 for schedules (`InvalidField("interval")`, `InvalidField("endOn")`, `InvalidField("description")`, amount errors, the split and payer errors of the template rows, `CorruptData` for EQUAL rows with inputs) and for an expense's link (`CorruptData` when only one of `recurringId` / `recurringOn` is set); rule 1 and rule 3 for schedules; rule 10 (`DeletedReference` from live schedules; `StillReferenced` for a person or group any schedule references); rule 15 for schedule references; rule 18 (`CorruptData`). Import validation checks field rules on live schedules too.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/rules/ScheduleRulesTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Rules 0, 1, 3, 10, 15 and 18 for schedules and the expenses posted from them (spec §3.4). */
class ScheduleRulesTest {

    private fun d(text: String) = LocalDate.parse(text)

    /** Every violation of [state], as import validation finds them. */
    private fun violations(state: LedgerState) = LedgerValidator.validate(state, state, WriteOp.Import)

    @Test
    fun `a schedule's fields and template rows are checked as stored`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(10_000), listOf(ME, x))
        fun errors(change: (RecurringExpense) -> RecurringExpense) =
            FieldRules.check(listOf(change(ledger.state.schedules.getValue(id)))).map { it.error }

        errors { it } shouldBe emptyList()
        errors { it.copy(interval = 0) } shouldBe listOf(LedgerError.InvalidField("interval"))
        errors { it.copy(interval = 100) } shouldBe listOf(LedgerError.InvalidField("interval"))
        errors { it.copy(endOn = it.startOn.minusDays(1)) } shouldBe listOf(LedgerError.InvalidField("endOn"))
        errors { it.copy(description = " ") } shouldBe listOf(LedgerError.InvalidField("description"))
        errors { it.copy(total = eur(0)) } shouldBe listOf(LedgerError.InvalidAmount)
        errors { it.copy(participants = it.payerRows) } shouldBe listOf(LedgerError.NoParticipants)
        errors { it.copy(participants = it.shareRows) } shouldBe listOf(LedgerError.NoPayers)
        errors { it.copy(splitType = SplitType.EXACT) } shouldBe listOf(LedgerError.MissingInput(ME))
        errors { it.copy(participants = it.participants.map { row -> row.copy(inputValue = 1) }) }
            .map { it::class } shouldBe listOf(LedgerError.CorruptData::class, LedgerError.CorruptData::class)
    }

    @Test
    fun `an expense names both its schedule and its occurrence, or neither`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val rent = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val id = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        val expense = ledger.state.expenses.getValue(id)

        FieldRules.check(listOf(expense)) shouldBe emptyList()
        FieldRules.check(listOf(expense.copy(recurringOn = null))).single().error::class shouldBe LedgerError.CorruptData::class
        FieldRules.check(listOf(expense.copy(recurringId = null))).single().error::class shouldBe LedgerError.CorruptData::class
    }

    @Test
    fun `schedules follow the people and membership rules`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val z = ledger.person("Z", deleted = true)
        val flat = ledger.group("Flat", members = listOf(ME, x))
        ledger.schedule(ME, eur(1_000), listOf(ME, x, y))
        ledger.schedule(ME, eur(1_000), listOf(ME, y), group = flat)
        val withZ = ledger.schedule(ME, eur(1_000), listOf(ME, z))
        ledger.schedule(ME, eur(1_000), listOf(ME, x, y), deleted = true)

        val found = violations(ledger.state).map { it.rule to it.error }

        found shouldContain (1 to LedgerError.NeedsGroup(setOf(ME, x, y)))
        found shouldContain (3 to LedgerError.NotGroupMember(flat, setOf(y)))
        found shouldContain (10 to LedgerError.DeletedReference(setOf(withZ.value, z.value)))
        found.count { it.first == 1 } shouldBe 1
    }

    @Test
    fun `references to schedules must exist, and an occurrence has at most one live expense`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val rent = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-02-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-02-01"), deleted = true)

        violations(ledger.state) shouldBe emptyList()

        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        violations(ledger.state).map { it.rule } shouldBe listOf(18)

        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = RecurringId("gone"), recurringOn = d("2026-01-01"))
        violations(ledger.state).map { it.rule } shouldBe listOf(15)
    }

    @Test
    fun `import validation checks live schedules' fields`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.schedule(ME, eur(1_000), listOf(ME, x), interval = 0)

        violations(ledger.state).map { it.rule to it.error } shouldBe listOf(0 to LedgerError.InvalidField("interval"))
    }

    @Test
    fun `a person or group that any schedule references can't be deleted`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val flat = ledger.group("Flat", members = listOf(ME, y))
        ledger.schedule(ME, eur(1_000), listOf(ME, x))
        ledger.schedule(ME, eur(1_000), listOf(ME, y), group = flat, deleted = true)
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.deletePerson(it, x, h.token(h.state.people.getValue(x))) } shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(x.value))
        h.run { GroupOps.deleteGroup(it, flat, h.token(h.state.groups.getValue(flat))) } shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(flat.value))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.rules.ScheduleRulesTest"`
Expected: FAIL: field checks return nothing for schedules, no rule 1/3/10/15/18 violations are found for them, and the deletes succeed.

- [ ] **Step 3: Check schedule fields and expense links (rule 0)**

In `main/.../ledger/rules/FieldRules.kt`, replace Task 1's line:

```kotlin
            is RecurringExpense -> emptyList() // Task 2
```

with:

```kotlin
            is RecurringExpense -> listOfNotNull(
                blank(entity.description, "description"),
                amount(entity.total),
                LedgerError.InvalidField("interval").takeIf { entity.interval !in 1..99 },
                entity.endOn?.takeIf { it.isBefore(entity.startOn) }?.let { LedgerError.InvalidField("endOn") },
            ) + template(entity)
```

replace (Part 1's expense branch end):

```kotlin
                amount(entity.effectTotal),
            ) + shares(entity) + payers(entity)
```

with:

```kotlin
                amount(entity.effectTotal),
                recurringLink(entity),
            ) + shares(entity) + payers(entity)
```

and add before the KDoc of `private fun inputs(expense: Expense)`:

```kotlin
    /** Spec §3.4 rule 0: an expense names both its schedule and its occurrence date, or neither. */
    private fun recurringLink(expense: Expense): LedgerError? =
        if ((expense.recurringId == null) != (expense.recurringOn == null)) {
            LedgerError.CorruptData("${expense.id} names only one of recurringId and recurringOn")
        } else {
            null
        }

    /**
     * A schedule's template rows as stored (spec §3.4 rule 0): payers and participants resolve over the template
     * total, and EQUAL rows carry no input. Without a valid total there is nothing to resolve over; the amount
     * error is reported instead.
     */
    private fun template(schedule: RecurringExpense): List<LedgerError> {
        val total = schedule.total.amountMinor
        if (total <= 0 || total > MAX_MINOR) return emptyList()
        val payers = schedule.payerRows.map { SplitInput(it.personId, it.inputValue) }
        val shares = schedule.shareRows.map { SplitInput(it.personId, it.inputValue) }
        val equalWithInputs = listOfNotNull(
            LedgerError.CorruptData("EQUAL payers of ${schedule.id} carry an input")
                .takeIf { schedule.payerSplit == PayerSplit.EQUAL && payers.any { it.value != null } },
            LedgerError.CorruptData("EQUAL participants of ${schedule.id} carry an input")
                .takeIf { schedule.splitType == SplitType.EQUAL && shares.any { it.value != null } },
        )
        return listOfNotNull(
            (SplitResolver.resolve(schedule.splitType, total, total, shares) as? LedgerResult.Err)?.error,
            (SplitResolver.resolvePayers(schedule.payerSplit, total, total, payers) as? LedgerResult.Err)?.error,
        ) + equalWithInputs
    }

```

- [ ] **Step 4: Apply the state invariants to schedules**

In `main/.../ledger/rules/StateInvariants.kt`, in `structure`, replace (Part 1's version):

```kotlin
        state.expenses.values.forEach { e ->
            e.payers.forEach { person(it.personId, e.id.value) }
            e.shares.forEach { person(it.personId, e.id.value) }
            group(e.groupId, e.id.value)
            snapshot(e.fxSnapshotId, e.id.value)
        }
```

with:

```kotlin
        state.expenses.values.forEach { e ->
            e.payers.forEach { person(it.personId, e.id.value) }
            e.shares.forEach { person(it.personId, e.id.value) }
            group(e.groupId, e.id.value)
            snapshot(e.fxSnapshotId, e.id.value)
            e.recurringId?.let { if (it !in state.schedules) problems += "${e.id.value} references missing schedule $it" }
        }
        state.schedules.values.forEach { s ->
            s.participants.forEach { person(it.personId, s.id.value) }
            group(s.groupId, s.id.value)
        }
```

In `check`, replace:

```kotlin
        val me = state.me
        val liveEntries = state.entries.filter { !it.isDeleted }
        val liveGroups = state.groups.values.filter { !it.isDeleted }
```

with:

```kotlin
        val me = state.me
        val liveEntries = state.entries.filter { !it.isDeleted }
        val liveGroups = state.groups.values.filter { !it.isDeleted }
        val liveSchedules = state.schedules.values.filter { !it.isDeleted }.sortedBy { it.id.value }
```

replace:

```kotlin
            if (!ok) add(1, LedgerError.NeedsGroup(people))
        }
```

with:

```kotlin
            if (!ok) add(1, LedgerError.NeedsGroup(people))
        }
        for (schedule in liveSchedules.filter { it.groupId == null }) {
            val people = schedule.personIds.map(resolver::canonical).toSet()
            if (people.size != 2 || me.id !in people) add(1, LedgerError.NeedsGroup(people))
        }
```

replace:

```kotlin
            if (missing.isNotEmpty()) add(3, LedgerError.NotGroupMember(groupId, missing))
        }
```

with:

```kotlin
            if (missing.isNotEmpty()) add(3, LedgerError.NotGroupMember(groupId, missing))
        }
        for (schedule in liveSchedules) {
            val groupId = schedule.groupId ?: continue
            val missing = schedule.personIds.map(resolver::canonical).filterNot { resolver.hasMembership(groupId, it) }.toSet()
            if (missing.isNotEmpty()) add(3, LedgerError.NotGroupMember(groupId, missing))
        }
```

replace:

```kotlin
            if (dead.isNotEmpty()) add(10, LedgerError.DeletedReference(setOf(entry.id.value) + dead))
        }
```

with:

```kotlin
            if (dead.isNotEmpty()) add(10, LedgerError.DeletedReference(setOf(entry.id.value) + dead))
        }
        for (schedule in liveSchedules) {
            val dead = schedule.personIds.filter { state.people.getValue(it).isDeleted }.map { it.value } +
                listOfNotNull(schedule.groupId?.takeIf { state.groups.getValue(it).isDeleted }?.value)
            if (dead.isNotEmpty()) add(10, LedgerError.DeletedReference(setOf(schedule.id.value) + dead))
        }
```

and replace:

```kotlin
        // Rules 14, 16, 17
        for (entry in liveEntries) fxRule(state, entry)?.let { (rule, error) -> add(rule, error) }
```

with:

```kotlin
        // Rules 14, 16, 17
        for (entry in liveEntries) fxRule(state, entry)?.let { (rule, error) -> add(rule, error) }

        // Rule 18: one live expense per occurrence
        state.expenses.values
            .filter { !it.isDeleted && it.recurringId != null && it.recurringOn != null }
            .groupBy { it.recurringId!! to it.recurringOn!! }
            .filterValues { it.size > 1 }
            .entries.sortedWith(compareBy({ it.key.first.value }, { it.key.second }))
            .forEach { (key, expenses) ->
                val ids = expenses.map { it.id.value }.sorted().joinToString()
                add(18, LedgerError.CorruptData("${key.first} has ${expenses.size} expenses for ${key.second}: $ids"))
            }
```

- [ ] **Step 5: Block deleting what a schedule references, and validate schedules on import**

In `main/.../ledger/rules/EditRules.kt`, replace:

```kotlin
                    if (previous != null && !previous.isDeleted && new.isDeleted && after.entries.any { it.groupId == new.id }) {
```

with:

```kotlin
                    val groupReferenced = after.entries.any { it.groupId == new.id } || after.schedules.values.any { it.groupId == new.id }
                    if (previous != null && !previous.isDeleted && new.isDeleted && groupReferenced) {
```

and replace:

```kotlin
            val referenced = after.entries.any { new.id in it.personIds } ||
```

with:

```kotlin
            val referenced = after.entries.any { new.id in it.personIds } ||
                after.schedules.values.any { new.id in it.personIds } ||
```

Schedules count whether deleted or not, since a deleted schedule can be restored (spec §3.4 rule 10).

In `main/.../ledger/rules/LedgerValidator.kt`, replace:

```kotlin
            (after.people.values + after.handles.values + after.groups.values + after.members.values + after.entries)
```

with:

```kotlin
            (after.people.values + after.handles.values + after.groups.values + after.members.values + after.entries +
                after.schedules.values)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.rules.ScheduleRulesTest"`
Expected: PASS, 6 tests.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "Check schedules and posted expenses in the ledger rules"
```

---

### Task 3: Saving, deleting and restoring schedules

**Files:**
- Create: `main/.../api/Recurring.kt`, `main/.../ledger/ops/RecurringOps.kt`, `test/.../ledger/ops/RecurringOpsTest.kt`
- Modify: `main/.../api/LedgerError.kt`, `main/.../ui/forms/LedgerErrorMessage.kt`

**Interfaces:**
- Consumes: Task 1's model, `EntryOps.combineInputs`, `SplitResolver.resolve` / `resolvePayers`, `checkBasis`, `edited`, `checkAmount`, `validated`, `touch`, `newMeta`, `cleaned` (`OpsSupport.kt`).
- Produces: `data class RecurringDraft(payers, description, category = null, total, groupId = null, splitType = EQUAL, participants, note = null, payerSplit = EQUAL, unit, interval = 1, startOn, endOn = null, autoPost = false)`; `sealed interface ScheduleSaveMode { Create(basis: LedgerGeneration); Update(id: RecurringId, token: EditToken) }`; `LedgerError.OccurrenceNotDue(nextDue: LocalDate?)`; `RecurringOps.save(ctx, draft, mode): LedgerResult<Planned<RecurringId>>`, `RecurringOps.delete(ctx, id, token)`, `RecurringOps.restore(ctx, id, token)`: `LedgerResult<Planned<Unit>>`.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/ops/RecurringOpsTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Saving, deleting and restoring schedules (spec §5.7). */
class RecurringOpsTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    @Test
    fun `a new schedule stores its template rows on canonical people, records of one person combined`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB", mergedInto = alexA)
        val bob = ledger.person("Bob")
        val flat = ledger.group("Flat", members = listOf(ME, alexA, alexB, bob))
        val h = OpsHarness(ledger.state)
        h.today = d("2026-02-10")
        val draft = RecurringDraft(
            payers = listOf(SplitInput(alexB)), description = " Rent ", total = eur(90_000), groupId = flat,
            splitType = SplitType.SHARES,
            participants = listOf(SplitInput(ME, 1), SplitInput(alexA, 1), SplitInput(alexB, 1), SplitInput(bob, 1)),
            unit = RecurrenceUnit.MONTH, startOn = d("2026-03-01"),
        )

        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()

        val schedule = h.state.schedules.getValue(id)
        schedule.description shouldBe "Rent"
        schedule.participants shouldBe listOf(
            RecurringParticipant(ParticipantRole.PAYER, alexA),
            RecurringParticipant(ParticipantRole.SHARE, ME, 1),
            RecurringParticipant(ParticipantRole.SHARE, alexA, 2),
            RecurringParticipant(ParticipantRole.SHARE, bob, 1),
        )
        schedule.handledThrough shouldBe null
        schedule.autoPostFrom shouldBe d("2026-02-10")
        h.revisions.last().action shouldBe RevisionAction.CREATED
    }

    @Test
    fun `an update keeps handledThrough, and keeps stored rows that still combine to the draft's`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB", mergedInto = alexA)
        val bob = ledger.person("Bob")
        val flat = ledger.group("Flat", members = listOf(ME, alexA, alexB, bob))
        val id = ledger.schedule(alexB, eur(90_000), listOf(ME, alexB), group = flat, handledThrough = d("2026-03-01"))
        val h = OpsHarness(ledger.state)
        val stored = h.state.schedules.getValue(id)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(alexA)), description = "Rent", total = eur(95_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(alexA)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        fun save(changed: RecurringDraft) =
            h.run { RecurringOps.save(it, changed, ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id)))) }

        save(draft) shouldBe LedgerResult.Ok(id)
        val saved = h.state.schedules.getValue(id)
        saved.participants shouldBe stored.participants
        saved.total shouldBe eur(95_000)
        saved.handledThrough shouldBe d("2026-03-01")
        saved.meta.version shouldBe 2

        save(draft.copy(payers = listOf(SplitInput(bob)))) shouldBe LedgerResult.Ok(id)
        h.state.schedules.getValue(id).payerRows shouldBe listOf(RecurringParticipant(ParticipantRole.PAYER, bob))
    }

    @Test
    fun `template errors name their side, and the rules apply to schedules`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        fun create(changed: RecurringDraft) = h.run { RecurringOps.save(it, changed, ScheduleSaveMode.Create(h.generation)) }

        create(draft.copy(total = eur(0))) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
        create(draft.copy(interval = 0)) shouldBe LedgerResult.Err(LedgerError.InvalidField("interval"))
        create(draft.copy(endOn = D0.minusDays(1))) shouldBe LedgerResult.Err(LedgerError.InvalidField("endOn"))
        create(draft.copy(participants = listOf(SplitInput(ME), SplitInput(ME)))) shouldBe
            LedgerResult.Err(LedgerError.DuplicateParticipant(ME))
        create(draft.copy(payers = listOf(SplitInput(x), SplitInput(x)))) shouldBe LedgerResult.Err(LedgerError.DuplicatePayer(x))
        create(draft.copy(payerSplit = PayerSplit.EXACT, payers = listOf(SplitInput(ME, 50_000), SplitInput(x, 30_000)))) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(10_000))
        create(draft.copy(splitType = SplitType.EXACT, participants = listOf(SplitInput(ME, -1), SplitInput(x, 90_001)))) shouldBe
            LedgerResult.Err(LedgerError.NegativeInput(ME))
        create(draft.copy(participants = listOf(SplitInput(ME), SplitInput(y)))) shouldBe
            LedgerResult.Err(LedgerError.NotGroupMember(flat, setOf(y)))
        create(draft.copy(groupId = null, participants = listOf(SplitInput(ME), SplitInput(x), SplitInput(y)))) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(ME, x, y)))
        h.state.schedules shouldBe emptyMap()
    }

    @Test
    fun `a schedule is deleted and restored with one revision each, and repeats change nothing`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val h = OpsHarness(ledger.state)
        fun token() = h.token(h.state.schedules.getValue(id))

        h.run { RecurringOps.delete(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(id).isDeleted shouldBe true
        h.revisions.last().action shouldBe RevisionAction.DELETED
        val count = h.revisions.size
        h.run { RecurringOps.delete(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.revisions.size shouldBe count

        h.run { RecurringOps.restore(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(id).isDeleted shouldBe false
        h.revisions.last().action shouldBe RevisionAction.RESTORED
        h.run { RecurringOps.delete(it, id, h.token(h.state.schedules.getValue(id)).copy(version = 1)) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(id.value, 1, 3))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.RecurringOpsTest"`
Expected: FAIL, compilation errors `Unresolved reference 'RecurringDraft'`, `'ScheduleSaveMode'`, `'RecurringOps'`.

- [ ] **Step 3: Add the draft, the save mode and the error**

Create `main/.../api/Recurring.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.Money
import java.time.LocalDate

/**
 * A schedule to create or save (spec §5.7, §6.2): an expense template without date or FX choice, the same
 * payer and participant inputs as [ExpenseDraft], and a pattern: every [interval] (1–99) [unit]s from [startOn],
 * none after [endOn]. [autoPost] posts due occurrences by itself; otherwise they wait in the due list.
 */
data class RecurringDraft(
    val payers: List<SplitInput>,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val note: String? = null,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
    val unit: RecurrenceUnit,
    val interval: Int = 1,
    val startOn: LocalDate,
    val endOn: LocalDate? = null,
    val autoPost: Boolean = false,
)

/** How a [RecurringDraft] is saved: as a new schedule prepared against [Create.basis], or as an edit. */
sealed interface ScheduleSaveMode {
    data class Create(val basis: LedgerGeneration) : ScheduleSaveMode
    data class Update(val id: RecurringId, val token: EditToken) : ScheduleSaveMode
}
```

In `main/.../api/LedgerError.kt`, add the import `java.time.LocalDate` (if not already imported) and, after `data object SelfTransfer : LedgerError`, add:

```kotlin

    // Recurring expenses (spec §5.7)
    /** Posting or skipping out of order or past `endOn`; [nextDue] is the occurrence to handle first, null when none is left. */
    data class OccurrenceNotDue(val nextDue: LocalDate?) : LedgerError
```

In `main/.../ui/forms/LedgerErrorMessage.kt`, replace (Part 1's line):

```kotlin
    is LedgerError.DuplicatePayer -> "Choose each payer only once."
```

with:

```kotlin
    is LedgerError.DuplicatePayer -> "Choose each payer only once."
    is LedgerError.OccurrenceNotDue -> nextDue?.let { "Post or skip the occurrence on $it first." } ?: "This schedule has no more occurrences."
```

- [ ] **Step 4: Write the save, delete and restore ops**

Create `main/.../ledger/ops/RecurringOps.kt`:

```kotlin
package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ResultScope
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType

/** Schedules (spec §5.7): save, delete, restore, post and skip, and the cut-off rule with hold resumes on every write. */
object RecurringOps {

    fun save(ctx: WriteContext, draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<Planned<RecurringId>> = ledgerResult {
        val existing: RecurringExpense? = when (mode) {
            is ScheduleSaveMode.Create -> {
                checkBasis(ctx, mode.basis)
                null
            }
            is ScheduleSaveMode.Update -> edited(ctx, mode.token, mode.id.value) { it.schedules[mode.id] }
        }
        checkAmount(draft.total)
        val resolver = IdentityResolver(ctx.state)
        val total = draft.total.amountMinor
        val shares = templateRows(
            ParticipantRole.SHARE, draft.participants, draft.splitType,
            existing?.takeIf { it.splitType == draft.splitType }?.shareRows, resolver,
        ) { SplitResolver.resolve(draft.splitType, total, total, it) }
        val payers = templateRows(
            ParticipantRole.PAYER, draft.payers, draft.payerSplit.splitType,
            existing?.takeIf { it.payerSplit == draft.payerSplit }?.payerRows, resolver,
        ) { SplitResolver.resolvePayers(draft.payerSplit, total, total, it) }

        val schedule = RecurringExpense(
            id = existing?.id ?: RecurringId(ctx.ids.next()),
            groupId = draft.groupId,
            description = draft.description.trim(),
            category = draft.category,
            note = draft.note.cleaned(),
            total = draft.total,
            payerSplit = draft.payerSplit,
            splitType = draft.splitType,
            participants = payers + shares,
            unit = draft.unit,
            interval = draft.interval,
            startOn = draft.startOn,
            endOn = draft.endOn,
            // An edit changes only later occurrences: what was handled stays handled (spec §5.7).
            handledThrough = existing?.handledThrough,
            // The cut-off rule in finalize sets today when the schedule auto-posts after this write.
            autoPostFrom = existing?.autoPostFrom ?: ctx.today,
            autoPost = draft.autoPost,
            meta = existing?.meta ?: ctx.newMeta(),
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(schedule.id, validated(ctx, listOf(Change(schedule, action))))
    }

    fun delete(ctx: WriteContext, id: RecurringId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        if (schedule.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(schedule, deletedAt = ctx.now), RevisionAction.DELETED))))
    }

    fun restore(ctx: WriteContext, id: RecurringId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        if (!schedule.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(schedule, deletedAt = null), RevisionAction.RESTORED))))
    }

    // ---- internals ----

    /**
     * One role's template rows (spec §4.4, §5.7): checked per draft record (negatives, the same record twice),
     * combined per canonical person, resolved over the template total so split errors come back now, and stored as
     * the combined inputs. When the combined inputs equal the stored rows' ([stored] is given only when the split
     * type is unchanged), the stored rows are kept, so an edit never rewrites a template's IDs by itself (spec §5.6).
     */
    private fun ResultScope.templateRows(
        role: ParticipantRole,
        inputs: List<SplitInput>,
        type: SplitType,
        stored: List<RecurringParticipant>?,
        resolver: IdentityResolver,
        resolve: (List<SplitInput>) -> LedgerResult<*>,
    ): List<RecurringParticipant> {
        val duplicate: (PersonId) -> LedgerError =
            if (role == ParticipantRole.PAYER) LedgerError::DuplicatePayer else LedgerError::DuplicateParticipant
        val combined = with(EntryOps) { checkedInputs(inputs, type, resolver, duplicate) }
        resolve(combined).bind()
        if (stored != null) {
            val storedInputs = EntryOps.combineInputs(stored.map { SplitInput(it.personId, it.inputValue) }, type, resolver)
            if (storedInputs.toSet() == combined.toSet()) return stored
        }
        return combined.map { RecurringParticipant(role, it.personId, it.value) }
    }
}
```

`combineInputs` already gives EQUAL rows no value, so EQUAL template rows store no input, as rule 0 requires.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.RecurringOpsTest"`
Expected: PASS, 4 tests.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src
git commit -m "Save, delete and restore schedules"
```

---

### Task 4: Posting and skipping occurrences, and keeping expenses linked

**Files:**
- Modify: `main/.../ledger/ops/RecurringOps.kt`, `main/.../ledger/ops/EntryOps.kt`, `main/.../ledger/ops/RevertOps.kt`
- Create: `test/.../ledger/ops/PostingTest.kt`

**Interfaces:**
- Consumes: Task 1's `Recurrence`, Task 3's `RecurringOps.save` / `delete`, `RecurringDraft`, `OccurrenceNotDue`; `EntryOps.saveExpense` / `deleteEntry`.
- Produces: `RecurringOps.post(ctx, id: RecurringId, occurrence: LocalDate, draft: ExpenseDraft, token: EditToken): LedgerResult<Planned<EntryId>>`, `RecurringOps.skip(ctx, id, through: LocalDate, token): LedgerResult<Planned<Unit>>`, `internal RecurringOps.raisedByDelete(ctx, entry: Entry): List<Change>`. Expense edits and reverts keep `recurringId` / `recurringOn`; schedule reverts keep `handledThrough` / `autoPostFrom`.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/ops/PostingTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Posting and skipping occurrences (spec §5.7, rules 18–19, AC 28 and 33 at op level). */
class PostingTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    /** A monthly confirm-first rent in Flat (me and X), from 1 March to 1 June 2026. */
    private class Fixture {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(
            ME, eur(90_000), listOf(ME, x), group = flat,
            startOn = LocalDate.parse("2026-03-01"), endOn = LocalDate.parse("2026-06-01"),
        )
        val h = OpsHarness(ledger.state)

        fun schedule() = h.state.schedules.getValue(rent)

        fun draft(on: LocalDate, total: Long = 90_000) =
            ExpenseDraft(ME, "Rent", total = eur(total), groupId = flat, participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = on)

        fun post(on: LocalDate, total: Long = 90_000) = h.run { RecurringOps.post(it, rent, on, draft(on, total), h.token(schedule())) }

        fun skip(through: LocalDate) = h.run { RecurringOps.skip(it, rent, through, h.token(schedule())) }
    }

    @Test
    fun `occurrences are posted strictly in order, as edited, without changing the template`() {
        val f = Fixture()
        Recurrence.due(f.schedule(), f.h.state, d("2026-04-15")) shouldBe listOf(d("2026-03-01"), d("2026-04-01"))
        f.post(d("2026-04-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))

        val id = f.post(d("2026-03-01"), total = 95_000).ok()

        val expense = f.h.state.expenses.getValue(id)
        expense.total shouldBe eur(95_000)
        expense.recurringId shouldBe f.rent
        expense.recurringOn shouldBe d("2026-03-01")
        f.schedule().total shouldBe eur(90_000)
        f.schedule().handledThrough shouldBe d("2026-03-01")
        f.h.revisions.takeLast(2).map { it.entityType to it.reason } shouldBe listOf(
            EntityType.EXPENSE to "Posted from ${f.schedule().description}",
            EntityType.RECURRING_EXPENSE to "Posted 2026-03-01",
        )

        f.skip(d("2026-04-01")) shouldBe LedgerResult.Ok(Unit)
        f.schedule().handledThrough shouldBe d("2026-04-01")
        f.h.revisions.last().reason shouldBe "Skipped through 2026-04-01"
        Recurrence.due(f.schedule(), f.h.state, d("2026-04-15")) shouldBe emptyList()
    }

    @Test
    fun `skips land on occurrences, nothing goes past endOn, and a deleted schedule posts nothing`() {
        val f = Fixture()
        f.skip(d("2026-03-15")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))
        f.skip(d("2026-07-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))
        f.skip(d("2026-06-01")) shouldBe LedgerResult.Ok(Unit)
        f.post(d("2026-07-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))

        val g = Fixture()
        g.h.run { RecurringOps.delete(it, g.rent, g.h.token(g.schedule())) } shouldBe LedgerResult.Ok(Unit)
        g.post(d("2026-03-01")) shouldBe LedgerResult.Err(LedgerError.NotFound(g.rent.value))
        g.skip(d("2026-03-01")) shouldBe LedgerResult.Err(LedgerError.NotFound(g.rent.value))
    }

    @Test
    fun `a post with an old token is refused, so an occurrence is never posted twice`() {
        val f = Fixture()
        val token = f.h.token(f.schedule())
        f.post(d("2026-03-01")).ok()

        f.h.run { RecurringOps.post(it, f.rent, d("2026-03-01"), f.draft(d("2026-03-01")), token) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(f.rent.value, 1, 2))
        f.h.state.expenses.values.count { it.recurringId == f.rent } shouldBe 1
    }

    @Test
    fun `a posted expense keeps its link through edits and reverts`() {
        val f = Fixture()
        val march = f.post(d("2026-03-01")).ok()
        fun expense() = f.h.state.expenses.getValue(march)

        f.h.run { EntryOps.saveExpense(it, f.draft(d("2026-03-03"), total = 91_000), SaveMode.Update(march, f.h.token(expense()))) } shouldBe
            LedgerResult.Ok(march)
        (expense().recurringId to expense().recurringOn) shouldBe (f.rent to d("2026-03-01"))

        f.h.run { RevertOps.revertTo(it, EntityRef(EntityType.EXPENSE, march.value), 1, f.h.token(expense()), f.h.revisions) } shouldBe
            LedgerResult.Ok(Unit)
        Triple(expense().total, expense().recurringId, expense().recurringOn) shouldBe Triple(eur(90_000), f.rent, d("2026-03-01"))

        // The schedule already counts March as handled, so the delete writes the expense only.
        f.h.run { EntryOps.deleteEntry(it, march, f.h.token(expense())) } shouldBe LedgerResult.Ok(Unit)
        f.h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.EXPENSE)
        Recurrence.next(f.schedule(), f.h.state) shouldBe d("2026-04-01")
    }

    @Test
    fun `deleting a posted expense the schedule hasn't caught up with raises handledThrough`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, handledThrough = d("2026-02-01"))
        val march = ledger.expense(
            ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), group = flat, recurring = rent, recurringOn = d("2026-03-01"),
        )
        val h = OpsHarness(ledger.state)

        h.run { EntryOps.deleteEntry(it, march, h.token(h.state.expenses.getValue(march))) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(rent).handledThrough shouldBe d("2026-03-01")
        h.revisions.last().reason shouldBe "Handled through 2026-03-01: posted expense deleted"
        Recurrence.next(h.state.schedules.getValue(rent), h.state) shouldBe d("2026-04-01")

        h.run { EntryOps.restoreEntry(it, march, h.token(h.state.expenses.getValue(march))) } shouldBe LedgerResult.Ok(Unit)
        h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.EXPENSE)
    }

    @Test
    fun `reverting a schedule changes its template and pattern, never what was handled`() {
        val f = Fixture()
        f.post(d("2026-03-01")).ok()
        val edit = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(99_000), groupId = f.flat,
            participants = listOf(SplitInput(ME), SplitInput(f.x)), unit = RecurrenceUnit.MONTH, startOn = d("2026-03-05"),
        )
        f.h.run { RecurringOps.save(it, edit, ScheduleSaveMode.Update(f.rent, f.h.token(f.schedule()))) }.ok()

        f.h.run { RevertOps.revertTo(it, EntityRef(EntityType.RECURRING_EXPENSE, f.rent.value), 1, f.h.token(f.schedule()), f.h.revisions) } shouldBe
            LedgerResult.Ok(Unit)

        f.schedule().total shouldBe eur(90_000)
        f.schedule().startOn shouldBe d("2026-03-01")
        f.schedule().handledThrough shouldBe d("2026-03-01")
        f.schedule().meta.version shouldBe 4
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.PostingTest"`
Expected: FAIL, compilation errors `Unresolved reference 'post'` and `'skip'` on `RecurringOps`.

- [ ] **Step 3: Post and skip**

In `main/.../ledger/ops/RecurringOps.kt`, add the imports `io.github.codex07.iou.api.ExpenseDraft`, `io.github.codex07.iou.api.SaveMode`, `io.github.codex07.iou.ledger.engine.Recurrence`, `io.github.codex07.iou.ledger.model.Entry`, `io.github.codex07.iou.ledger.model.EntryId`, `io.github.codex07.iou.ledger.model.Expense` and `java.time.LocalDate`, and add after `fun restore(...)`:

```kotlin

    /**
     * Posts [occurrence], which must be the schedule's next one (rule 19), as the expense [draft] describes (spec
     * §5.7): the expense is created through the normal expense create, so every rule applies, and the schedule's
     * `handledThrough` moves to [occurrence] in the same write. [token] is the schedule's.
     */
    fun post(
        ctx: WriteContext,
        id: RecurringId,
        occurrence: LocalDate,
        draft: ExpenseDraft,
        token: EditToken,
    ): LedgerResult<Planned<EntryId>> = ledgerResult {
        val schedule = live(ctx, id, token)
        val next = Recurrence.next(schedule, ctx.state)
        ensure(next != null && occurrence == next) { LedgerError.OccurrenceNotDue(next) }
        val created = EntryOps.saveExpense(ctx, draft, SaveMode.Create(ctx.generation)).bind()
        val expense = (created.plan.changes.single().entity as Expense).copy(recurringId = id, recurringOn = occurrence)
        val handled = ctx.touch(schedule.copy(handledThrough = occurrence))
        val changes = listOf(
            Change(expense, RevisionAction.CREATED, "Posted from ${schedule.description}"),
            Change(handled, RevisionAction.UPDATED, "Posted $occurrence", keepsCutOff = true),
        )
        Planned(expense.id, validated(ctx, changes, created.plan.snapshots))
    }

    /** Skips every occurrence from the next one through [through], which must be an occurrence (rule 19). */
    fun skip(ctx: WriteContext, id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = live(ctx, id, token)
        val next = Recurrence.next(schedule, ctx.state)
        ensure(next != null && !through.isBefore(next) && Recurrence.isOccurrence(schedule, through)) {
            LedgerError.OccurrenceNotDue(next)
        }
        val skipped = ctx.touch(schedule.copy(handledThrough = through))
        Planned(Unit, validated(ctx, listOf(Change(skipped, RevisionAction.UPDATED, "Skipped through $through", keepsCutOff = true))))
    }

    /**
     * Spec §5.7: deleting a posted expense through the api raises its schedule's `handledThrough` to the expense's
     * occurrence when it is lower, so the date doesn't become due again. Like a skip it only moves forward.
     */
    internal fun raisedByDelete(ctx: WriteContext, entry: Entry): List<Change> {
        val expense = entry as? Expense ?: return emptyList()
        val on = expense.recurringOn ?: return emptyList()
        val schedule = expense.recurringId?.let { ctx.state.schedules[it] } ?: return emptyList()
        val handled = schedule.handledThrough
        if (handled != null && !handled.isBefore(on)) return emptyList()
        val raised = ctx.touch(schedule.copy(handledThrough = on))
        return listOf(Change(raised, RevisionAction.UPDATED, "Handled through $on: posted expense deleted", keepsCutOff = true))
    }
```

and add to the internals:

```kotlin

    /** The schedule a post or skip acts on: token checked first, and a deleted schedule is `NotFound` (rule 19). */
    private fun ResultScope.live(ctx: WriteContext, id: RecurringId, token: EditToken): RecurringExpense {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        ensure(!schedule.isDeleted) { LedgerError.NotFound(id.value) }
        return schedule
    }
```

The expense's draft is posted as the user edited it (amount, date, payers, even the group); only its link comes from the schedule. The expense create inside `post` validates once on its own and the whole write validates again with both rows, which is where rule 18 and the link fields are checked.

- [ ] **Step 4: Keep links through edits, reverts and deletes**

In `main/.../ledger/ops/EntryOps.kt`, in `saveExpense`, replace:

```kotlin
            meta = existing?.meta ?: ctx.newMeta(),
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(expense.id, validated(ctx, listOf(Change(expense, action)), listOfNotNull(fx.newSnapshot)))
```

with:

```kotlin
            meta = existing?.meta ?: ctx.newMeta(),
            // A posted expense stays linked to its schedule and occurrence through every edit (rule 19).
            recurringId = existing?.recurringId,
            recurringOn = existing?.recurringOn,
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(expense.id, validated(ctx, listOf(Change(expense, action)), listOfNotNull(fx.newSnapshot)))
```

and in `deleteEntry`, replace:

```kotlin
        if (entry.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(entry, deletedAt = ctx.now), RevisionAction.DELETED))))
```

with:

```kotlin
        if (entry.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        val deleted = Change(ctx.touch(entry, deletedAt = ctx.now), RevisionAction.DELETED)
        Planned(Unit, validated(ctx, listOf(deleted) + RecurringOps.raisedByDelete(ctx, entry)))
```

In `main/.../ledger/ops/RevertOps.kt`, add the imports `io.github.codex07.iou.ledger.model.Expense` and `io.github.codex07.iou.ledger.model.RecurringExpense`, and replace:

```kotlin
        is GroupMember -> (current as GroupMember).let { old.copy(groupId = it.groupId, personId = it.personId, joinedAt = it.joinedAt) }
```

with:

```kotlin
        is GroupMember -> (current as GroupMember).let { old.copy(groupId = it.groupId, personId = it.personId, joinedAt = it.joinedAt) }
        is Expense -> (current as Expense).let { old.copy(recurringId = it.recurringId, recurringOn = it.recurringOn) }
        // A revert changes the template and pattern, never which occurrences were handled (spec §5.3).
        is RecurringExpense -> (current as RecurringExpense).let { old.copy(handledThrough = it.handledThrough, autoPostFrom = it.autoPostFrom) }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.PostingTest"`
Expected: PASS, 6 tests.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src
git commit -m "Post and skip occurrences, and keep posted expenses linked to their schedule"
```

---

### Task 5: The cut-off rule and hold resumes on every write

**Files:**
- Modify: `main/.../ledger/ops/RecurringOps.kt`, `main/.../store/LedgerStore.kt`, `docs/superpowers/specs/2026-11-02-iou-mvp-design.md` (§5.7)
- Modify (tests): `test/.../ledger/ops/OpsHarness.kt`
- Create: `test/.../ledger/ops/CutOffTest.kt`

**Interfaces:**
- Consumes: `Recurrence.holds` / `autoPosts`, `Change.keepsCutOff`, `WriteContext.today` (Task 1); `RecurringOps.save` / `post` / `skip` (Tasks 3–4); `GroupOps.setGroupArchived`, `PeopleOps.merge` / `unmerge`; `LedgerStore`, `LedgerContent`.
- Produces: `RecurringOps.finalize(ctx: WriteContext, plan: WritePlan): WritePlan`, called by `LedgerStore.writeWith` for every successful op and by `OpsHarness.run`. Part 4's runner and Part 5's import and undo get the rule through the store.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/ops/CutOffTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The cut-off rule and hold resumes (spec §5.7, AC 31–32 at op level). */
class CutOffTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    private fun rent(x: PersonId, autoPost: Boolean) = RecurringDraft(
        payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
        participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = d("2026-02-01"), autoPost = autoPost,
    )

    @Test
    fun `saving an auto-post schedule sets its cut-off to today, and posting and skipping keep it`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        h.today = d("2026-05-10")
        val id = h.run { RecurringOps.save(it, rent(x, autoPost = true), ScheduleSaveMode.Create(h.generation)) }.ok()
        fun schedule() = h.state.schedules.getValue(id)
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.today = d("2026-05-20")
        val february = ExpenseDraft(ME, "Rent", total = eur(90_000), participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = d("2026-02-01"))
        h.run { RecurringOps.post(it, id, d("2026-02-01"), february, h.token(schedule())) }.ok()
        h.run { RecurringOps.skip(it, id, d("2026-03-01"), h.token(schedule())) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.run { RecurringOps.save(it, rent(x, autoPost = true).copy(description = "Flat rent"), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-20")
    }

    @Test
    fun `a confirm-first schedule keeps its cut-off until auto-post is switched on`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        h.today = d("2026-05-10")
        val id = h.run { RecurringOps.save(it, rent(x, autoPost = false), ScheduleSaveMode.Create(h.generation)) }.ok()
        fun schedule() = h.state.schedules.getValue(id)

        h.today = d("2026-05-20")
        h.run { RecurringOps.save(it, rent(x, autoPost = false).copy(note = "Due on the 1st"), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.run { RecurringOps.save(it, rent(x, autoPost = true), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-20")
    }

    @Test
    fun `the handledThrough raise of a deleted posted expense keeps the cut-off`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), handledThrough = d("2026-02-01"), autoPost = true)
        val march = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-03-01"))
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")

        h.run { EntryOps.deleteEntry(it, march, h.token(h.state.expenses.getValue(march))) }.ok()

        h.state.schedules.getValue(id).handledThrough shouldBe d("2026-03-01")
        h.state.schedules.getValue(id).autoPostFrom shouldBe D0
    }

    @Test
    fun `unarchiving a group resumes its held schedules with a fresh cut-off, and archiving writes none`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x), archived = true)
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, autoPost = true)
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")
        fun group() = h.state.groups.getValue(flat)

        h.run { GroupOps.setGroupArchived(it, flat, false, h.token(group())) }.ok()

        h.lastPlan!!.changes.map { it.entity.entityType to it.reason } shouldBe listOf(
            EntityType.GROUP to "Unarchived",
            EntityType.RECURRING_EXPENSE to "Resumed: Flat unarchived",
        )
        h.state.schedules.getValue(id).let { it.meta.version to it.autoPostFrom } shouldBe (2 to d("2026-06-01"))

        h.run { GroupOps.setGroupArchived(it, flat, true, h.token(group())) }.ok()
        h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.GROUP)
    }

    @Test
    fun `merging an archived person into an active one, or unmerging an active alias from an archived person, resumes`() {
        val ledger = TestLedger().apply { me() }
        val old = ledger.person("Old", archived = true)
        val new = ledger.person("New")
        val pat = ledger.person("Pat", archived = true)
        val patsy = ledger.person("Patsy", mergedInto = pat)
        val viaOld = ledger.schedule(ME, eur(1_000), listOf(ME, old))
        val viaPatsy = ledger.schedule(ME, eur(1_000), listOf(ME, patsy))
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.merge(it, old, new, h.token(h.state.people.getValue(old))) }.ok()
        h.revisions.last().let { it.entityId to it.reason } shouldBe (viaOld.value to "Resumed: Old merged into New")

        h.run { PeopleOps.unmerge(it, patsy, h.token(h.state.people.getValue(patsy))) }.ok()
        h.revisions.last().let { it.entityId to it.reason } shouldBe (viaPatsy.value to "Resumed: Patsy unmerged from Pat")
        // Confirm-first: the resume is written, but the cut-off stays.
        h.state.schedules.getValue(viaPatsy).autoPostFrom shouldBe D0
    }

    @Test
    fun `editing a held schedule so it no longer references the archived person is its own resume`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val bea = ledger.person("Bea", archived = true)
        val flat = ledger.group("Flat", members = listOf(ME, x, bea))
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, bea), group = flat, autoPost = true)
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")
        val edit = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0, autoPost = true,
        )

        h.run { RecurringOps.save(it, edit, ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id)))) }.ok()

        h.lastPlan!!.changes.map { it.entity.entityType to it.reason } shouldBe listOf(EntityType.RECURRING_EXPENSE to null)
        h.state.schedules.getValue(id).autoPostFrom shouldBe d("2026-06-01")
        Recurrence.holds(h.state.schedules.getValue(id), h.state) shouldBe emptyList()
    }

    @Test
    fun `the store finalizes every write it commits`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x), archived = true)
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, autoPost = true)
        val store = LedgerStore(
            Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-test"),
            initial = LedgerContent(ledger.state),
        )

        store.write { ctx ->
            GroupOps.setGroupArchived(ctx, flat, false, EditToken(ctx.generation, ctx.state.groups.getValue(flat).meta.version))
        } shouldBe LedgerResult.Ok(Unit)

        val snapshot = store.loaded()
        snapshot.state.schedules.getValue(id).autoPostFrom shouldBe d("2026-06-01")
        snapshot.revisions.last().reason shouldBe "Resumed: Flat unarchived"
    }
}
```

In `test/.../ledger/ops/OpsHarness.kt`, apply plans the way the store does. Replace:

```kotlin
    fun <T> run(op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> {
        val result = op(ctx())
        if (result is LedgerResult.Ok) {
            val plan = result.value.plan
```

with:

```kotlin
    /** Runs [op] and applies its plan after `RecurringOps.finalize`, as `LedgerStore` does. */
    fun <T> run(op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> {
        val context = ctx()
        val result = op(context)
        if (result is LedgerResult.Ok) {
            val plan = RecurringOps.finalize(context, result.value.plan)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.CutOffTest"`
Expected: FAIL, compilation error `Unresolved reference 'finalize'`.

- [ ] **Step 3: Write `finalize`**

In `main/.../ledger/ops/RecurringOps.kt`, add the imports `io.github.codex07.iou.ledger.model.Group`, `io.github.codex07.iou.ledger.model.LedgerState`, `io.github.codex07.iou.ledger.model.Person` and `io.github.codex07.iou.ledger.model.PersonId`, and add after `raisedByDelete`:

```kotlin

    /**
     * Runs on every plan the store commits, after the op built and validated it (spec §5.7). First, each live
     * schedule the write takes off hold without changing it gets a resume: an `UPDATED` revision with the reason
     * "Resumed: <cause>". Then the cut-off rule: every schedule change except those marked `keepsCutOff` (posting,
     * skipping, the raise of a deleted posted expense) sets `autoPostFrom` to today when the schedule auto-posts
     * after the write. Only `autoPostFrom` and sync columns change, which no rule reads, so the plan stays valid.
     */
    fun finalize(ctx: WriteContext, plan: WritePlan): WritePlan {
        if (plan.changes.isEmpty()) return plan
        val before = ctx.state
        val after = before.applying(plan.changes, plan.snapshots)
        if (after.schedules.isEmpty()) return plan
        val beforeResolver = IdentityResolver(before)
        val afterResolver = IdentityResolver(after)
        val changed = plan.changes.mapNotNull { (it.entity as? RecurringExpense)?.id }.toSet()

        val resumes = after.schedules.values
            .filter { !it.isDeleted && it.id !in changed }
            .sortedBy { it.id.value }
            .mapNotNull { schedule ->
                val held = Recurrence.holds(schedule, before, beforeResolver)
                if (held.isEmpty() || Recurrence.holds(schedule, after, afterResolver).isNotEmpty()) return@mapNotNull null
                Change(ctx.touch(schedule), RevisionAction.UPDATED, resumeReason(held, plan, before, after, beforeResolver))
            }

        val changes = (plan.changes + resumes).map { change ->
            val schedule = change.entity as? RecurringExpense
            if (schedule == null || change.keepsCutOff || !Recurrence.autoPosts(schedule, after, afterResolver)) {
                change
            } else {
                change.copy(entity = schedule.copy(autoPostFrom = ctx.today))
            }
        }
        return plan.copy(changes = changes)
    }
```

and add to the internals:

```kotlin

    /** "Resumed: <cause>" from the plan's group and person changes that ended one of [held]; "Resumed" if none names it. */
    private fun resumeReason(
        held: List<Recurrence.Hold>,
        plan: WritePlan,
        before: LedgerState,
        after: LedgerState,
        beforeResolver: IdentityResolver,
    ): String {
        fun name(state: LedgerState, id: PersonId) = state.people[id]?.displayName ?: id.value
        val causes = plan.changes.mapNotNull { change ->
            when (val entity = change.entity) {
                is Group ->
                    if (Recurrence.Hold.GroupArchived(entity.id) in held && entity.archivedAt == null) "${entity.name} unarchived" else null
                is Person -> {
                    val old = before.people[entity.id]
                    val holdsThis = Recurrence.Hold.PersonArchived(beforeResolver.canonical(entity.id)) in held
                    val target = entity.mergedIntoId
                    val oldTarget = old?.mergedIntoId
                    when {
                        old == null || !holdsThis -> null
                        old.archivedAt != null && entity.archivedAt == null -> "${entity.displayName} unarchived"
                        oldTarget == null && target != null -> "${entity.displayName} merged into ${name(after, target)}"
                        oldTarget != null && target == null -> "${entity.displayName} unmerged from ${name(before, oldTarget)}"
                        else -> null
                    }
                }
                else -> null
            }
        }
        return if (causes.isEmpty()) "Resumed" else "Resumed: ${causes.joinToString("; ")}"
    }
```

A merge of an archived source names the source: its canonical person before the write is itself, the archived one. An unmerge names the alias: before the write it resolved to the archived target. A write that starts a hold adds nothing, since a schedule only gets a resume when it was held before and is not held after.

- [ ] **Step 4: Finalize every store write**

In `main/.../store/LedgerStore.kt`, add the import `io.github.codex07.iou.ledger.ops.RecurringOps`, and replace:

```kotlin
            val result = op(snapshot, WriteContext(snapshot.state, snapshot.generation, now(), ids))
```

with:

```kotlin
            val context = WriteContext(snapshot.state, snapshot.generation, now(), ids, today())
            // The cut-off rule and hold resumes apply to every write path alike (spec §5.7, index locked decision 7).
            val result = op(snapshot, context).map { it.copy(plan = RecurringOps.finalize(context, it.plan)) }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.CutOffTest"`
Expected: PASS, 7 tests.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS. Without schedules `finalize` returns the plan unchanged, so no existing test sees a difference.

- [ ] **Step 6: Record the raise in the spec and commit**

In `docs/superpowers/specs/2026-11-02-iou-mvp-design.md`, §5.7, in the cut-off bullet, replace:

```markdown
Posting and skipping only move `handledThrough` forward, so they keep the cut-off.
```

with:

```markdown
Posting and skipping only move `handledThrough` forward, so they keep the cut-off; so does the raise of `handledThrough` when a posted expense is deleted (below), which moves it forward the same way.
```

```bash
git add android/app/src docs/superpowers/specs/2026-11-02-iou-mvp-design.md
git commit -m "Apply the cut-off rule and write hold resumes on every committed write"
```

---

### Task 6: Schedule read models

**Files:**
- Modify: `main/.../api/Recurring.kt`, `main/.../api/Drafts.kt`, `main/.../api/ReadModels.kt`, `main/.../ledger/views/EntryViews.kt`
- Create: `main/.../ledger/views/ScheduleViews.kt`, `test/.../ledger/views/ScheduleViewsTest.kt`

**Interfaces:**
- Consumes: `Recurrence` (Task 1), `RecurringOps` (Tasks 3–5), `EntryOps.combineInputs`, `LedgerViews.history`, `activityItem`, `groupRef`, `personRef`, `token`.
- Produces: `sealed interface HoldReason { GroupArchived(group: GroupRef); PersonArchived(person: PersonRef) }`; `ScheduleSummary(id, description, group, total, unit, interval, autoPost, next, dueCount, holds, deleted)`; `ScheduleDetail(generation, summary, token, editDraft, handledThrough, due, confirmToResume, posted, history)`; `DueOccurrence(schedule, description, date, amount, group, autoPost, confirmToResume, autoPostError = null)`; `OccurrenceDraft(schedule, occurrence, draft: ExpenseDraft, token)`; `VersionDraft.OfSchedule(draft: RecurringDraft)`; `ActivityItem.recurringId`, `ActivityItem.recurringOn`; `LedgerViews.schedules(includeDeleted, onHold, today)`, `scheduleSummary(schedule, today)`, `schedule(id, today)`, `dueOccurrences(today)`, `occurrenceDraft(id)`. Part 4 wraps them in `RecurringApi` and fills `autoPostError`.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/views/ScheduleViewsTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonRef
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Schedule lists, details, the due list and occurrence drafts (spec §5.7, §6.2). */
class ScheduleViewsTest {

    private fun OpsHarness.views() = LedgerViews(state, revisions, generation, EUR) { if (it == EUR) "1" else null }

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `schedules show their next date, due count and holds, and held ones list apart`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val bea = ledger.person("Bea", archived = true)
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-03-01"))
        val held = ledger.schedule(ME, eur(1_000), listOf(ME, bea), startOn = d("2026-03-01"))
        ledger.schedule(ME, eur(1_000), listOf(ME, x), deleted = true)
        val views = OpsHarness(ledger.state).views()
        val today = d("2026-04-15")

        views.schedules(includeDeleted = false, onHold = null, today = today).map { it.id } shouldBe listOf(rent, held)
        val active = views.schedules(includeDeleted = false, onHold = false, today = today).single()
        active.id shouldBe rent
        active.next shouldBe d("2026-03-01")
        active.dueCount shouldBe 2
        val onHold = views.schedules(includeDeleted = false, onHold = true, today = today).single()
        onHold.id shouldBe held
        onHold.dueCount shouldBe 0
        onHold.holds shouldBe listOf(HoldReason.PersonArchived(PersonRef(bea, "Bea")))
        views.schedules(includeDeleted = true, onHold = null, today = today).size shouldBe 3
    }

    @Test
    fun `the due list shows every due date oldest first and marks a backlog that blocks auto-post`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val auto = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-03-01"), autoPost = true, autoPostFrom = d("2026-04-15"))
        val confirm = ledger.schedule(ME, eur(5_000), listOf(ME, x), startOn = d("2026-04-10"))
        ledger.schedule(ME, eur(5_000), listOf(ME, x), startOn = d("2026-04-01"), deleted = true)

        val due = OpsHarness(ledger.state).views().dueOccurrences(d("2026-04-20"))

        due.map { Triple(it.schedule, it.date, it.confirmToResume) } shouldBe listOf(
            Triple(auto, d("2026-03-01"), true),
            Triple(auto, d("2026-04-01"), true),
            Triple(confirm, d("2026-04-10"), false),
        )
        due.first().amount shouldBe eur(90_000)
        due.first().autoPostError shouldBe null
    }

    @Test
    fun `the detail has the edit draft, posted expenses and history, and the occurrence draft is for the next date`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val trip = ledger.group("Trip", members = listOf(ME, alex))
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = trip,
            participants = listOf(SplitInput(ME), SplitInput(alex)), unit = RecurrenceUnit.MONTH, startOn = d("2026-03-01"),
        )
        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()
        val march = ExpenseDraft(ME, "Rent", total = eur(95_000), groupId = trip, participants = listOf(SplitInput(ME), SplitInput(alex)), occurredOn = d("2026-03-02"))
        val posted = h.run { RecurringOps.post(it, id, d("2026-03-01"), march, h.token(h.state.schedules.getValue(id))) }.ok()

        val detail = h.views().schedule(id, d("2026-04-20"))!!

        detail.editDraft shouldBe draft
        detail.handledThrough shouldBe d("2026-03-01")
        detail.due shouldBe listOf(d("2026-04-01"))
        detail.confirmToResume shouldBe false
        detail.posted.map { Triple(it.entryId, it.recurringId, it.recurringOn) } shouldBe listOf(Triple(posted, id, d("2026-03-01")))
        detail.history.map { it.reason } shouldBe listOf("Posted 2026-03-01", null)
        detail.token shouldBe h.token(h.state.schedules.getValue(id))

        val next = h.views().occurrenceDraft(id).ok()
        next.occurrence shouldBe d("2026-04-01")
        next.draft shouldBe ExpenseDraft(
            listOf(SplitInput(ME)), "Rent", total = eur(90_000), groupId = trip,
            participants = listOf(SplitInput(ME), SplitInput(alex)), occurredOn = d("2026-04-01"),
        )
        next.token shouldBe detail.token
    }

    @Test
    fun `occurrence drafts follow merges, and ended or deleted schedules have none`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alias = ledger.person("Alias", mergedInto = alex)
        val trip = ledger.group("Trip", members = listOf(ME, alex, alias))
        val id = ledger.schedule(alias, eur(1_000), listOf(ME, alias), group = trip)
        val ended = ledger.schedule(ME, eur(1_000), listOf(ME, alex), group = trip, endOn = D0, handledThrough = D0)
        val deleted = ledger.schedule(ME, eur(1_000), listOf(ME, alex), group = trip, deleted = true)
        val views = OpsHarness(ledger.state).views()

        val draft = views.occurrenceDraft(id).ok().draft
        draft.payers shouldBe listOf(SplitInput(alex))
        draft.participants shouldBe listOf(SplitInput(ME), SplitInput(alex))
        draft.fx shouldBe null
        views.occurrenceDraft(ended) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
        views.occurrenceDraft(deleted) shouldBe LedgerResult.Err(LedgerError.NotFound(deleted.value))
    }

    @Test
    fun `an old schedule version opens as a draft`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()
        h.run {
            RecurringOps.save(it, draft.copy(interval = 2, description = "Shared rent"), ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id))))
        }.ok()

        h.views().draftFromVersion(EntityRef(EntityType.RECURRING_EXPENSE, id.value), 1).ok() shouldBe VersionDraft.OfSchedule(draft)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.ScheduleViewsTest"`
Expected: FAIL, compilation errors `Unresolved reference 'schedules'`, `'HoldReason'`, `'dueOccurrences'`, `'OfSchedule'`, `'recurringId'` on `ActivityItem`.

- [ ] **Step 3: Add the read models**

In `main/.../api/Recurring.kt`, add the import `io.github.codex07.iou.ledger.model.LedgerGeneration` if missing, and append:

```kotlin

/** Why a schedule is on hold (spec §5.7): its group, or the canonical person of one of its people, is archived. */
sealed interface HoldReason {
    data class GroupArchived(val group: GroupRef) : HoldReason
    data class PersonArchived(val person: PersonRef) : HoldReason
}

/** One schedule in a list. Held schedules ([holds] not empty) are meant for their own screen (spec §5.7). */
data class ScheduleSummary(
    val id: RecurringId,
    val description: String,
    val group: GroupRef?,
    /** The template amount, as paid. */
    val total: Money,
    val unit: RecurrenceUnit,
    val interval: Int,
    val autoPost: Boolean,
    /** The next occurrence; null once the schedule has ended. Show it on the edit screen before saving. */
    val next: LocalDate?,
    /** Occurrences due today; 0 while on hold or deleted. */
    val dueCount: Int,
    val holds: List<HoldReason>,
    val deleted: Boolean,
)

data class ScheduleDetail(
    val generation: LedgerGeneration,
    val summary: ScheduleSummary,
    /** For saves, delete, restore, posting and skipping. */
    val token: EditToken,
    /** The template and pattern as the edit form takes them: canonical people, records of one person combined. */
    val editDraft: RecurringDraft,
    /** The last occurrence posted or skipped (posted expenses included); null when none was. */
    val handledThrough: LocalDate?,
    /** Due occurrences, oldest first; empty while on hold or deleted. */
    val due: List<LocalDate>,
    /** Auto-post waits until older dates are posted or skipped ("confirm these to resume auto-posting"). */
    val confirmToResume: Boolean,
    /** The non-deleted expenses posted from this schedule, newest first. */
    val posted: List<ActivityItem>,
    /** Revisions newest first; revert and "Open as draft" go through `LedgerApi` as for other entities. */
    val history: List<RevisionView>,
)

/** One due occurrence in the due list, oldest first (spec §5.7). */
data class DueOccurrence(
    val schedule: RecurringId,
    val description: String,
    val date: LocalDate,
    /** The template amount, as paid; the posted amount may differ. */
    val amount: Money,
    val group: GroupRef?,
    val autoPost: Boolean,
    /** An auto-post schedule that posts nothing more until its older dates are handled. */
    val confirmToResume: Boolean,
    /** Why auto-posting this occurrence failed last time; in memory only, cleared by the next successful run. */
    val autoPostError: LedgerError? = null,
)

/** The draft for posting a schedule's next occurrence; post it with that [occurrence] and [token]. */
data class OccurrenceDraft(
    val schedule: RecurringId,
    val occurrence: LocalDate,
    val draft: ExpenseDraft,
    val token: EditToken,
)
```

In `main/.../api/Drafts.kt`, replace:

```kotlin
    data class OfGroup(val draft: GroupDraft) : VersionDraft
}
```

with:

```kotlin
    data class OfGroup(val draft: GroupDraft) : VersionDraft
    data class OfSchedule(val draft: RecurringDraft) : VersionDraft
}
```

In `main/.../api/ReadModels.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringId` and replace:

```kotlin
    val myBalanceEffect: Money?,
    val deleted: Boolean,
)
```

with:

```kotlin
    val myBalanceEffect: Money?,
    val deleted: Boolean,
    /** The schedule an expense was posted from and the occurrence it fills (spec §5.7); null otherwise. */
    val recurringId: RecurringId?,
    val recurringOn: LocalDate?,
)
```

- [ ] **Step 4: Write the views**

In `main/.../ledger/views/EntryViews.kt`, replace:

```kotlin
        group = entry.groupId?.let(::groupRef), paid = entry.amount, effect = entry.effectAmount,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted,
```

with:

```kotlin
        group = entry.groupId?.let(::groupRef), paid = entry.amount, effect = entry.effectAmount,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted, recurringId = null, recurringOn = null,
```

replace:

```kotlin
        group = entry.groupId?.let(::groupRef), paid = entry.total, effect = entry.effectTotal,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted,
```

with:

```kotlin
        group = entry.groupId?.let(::groupRef), paid = entry.total, effect = entry.effectTotal,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted, recurringId = entry.recurringId, recurringOn = entry.recurringOn,
```

and replace:

```kotlin
        is Group -> VersionDraft.OfGroup(GroupDraft(old.name, old.defaultCurrency, old.fxMode))
```

with:

```kotlin
        is Group -> VersionDraft.OfGroup(GroupDraft(old.name, old.defaultCurrency, old.fxMode))
        is RecurringExpense -> VersionDraft.OfSchedule(scheduleDraftOf(old))
```

Create `main/.../ledger/views/ScheduleViews.kt`:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSummary
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.ops.EntryOps
import java.time.LocalDate

/** Schedules by description; [onHold] keeps only held (true) or only active (false) ones, null both (spec §5.7, §6.2). */
fun LedgerViews.schedules(includeDeleted: Boolean, onHold: Boolean?, today: LocalDate): List<ScheduleSummary> =
    state.schedules.values
        .filter { includeDeleted || !it.isDeleted }
        .map { scheduleSummary(it, today) }
        .filter { onHold == null || it.holds.isNotEmpty() == onHold }
        .sortedWith(compareBy<ScheduleSummary>({ it.description.lowercase() }, { it.id.value }))

fun LedgerViews.scheduleSummary(schedule: RecurringExpense, today: LocalDate): ScheduleSummary = ScheduleSummary(
    id = schedule.id,
    description = schedule.description,
    group = schedule.groupId?.let(::groupRef),
    total = schedule.total,
    unit = schedule.unit,
    interval = schedule.interval,
    autoPost = schedule.autoPost,
    next = Recurrence.next(schedule, state),
    dueCount = dueDates(schedule, today).size,
    holds = holdReasons(schedule),
    deleted = schedule.isDeleted,
)

fun LedgerViews.schedule(id: RecurringId, today: LocalDate): ScheduleDetail? {
    val schedule = state.schedules[id] ?: return null
    val summary = scheduleSummary(schedule, today)
    val due = dueDates(schedule, today)
    return ScheduleDetail(
        generation = generation,
        summary = summary,
        token = token(schedule),
        editDraft = scheduleDraftOf(schedule),
        handledThrough = Recurrence.handledDate(schedule, state),
        due = due,
        // From the due dates, as in the due list: a held or deleted schedule has none, so it never asks to confirm.
        confirmToResume = confirmToResume(schedule, due.firstOrNull()),
        posted = state.expenses.values
            .filter { it.recurringId == id && !it.isDeleted }
            .sortedWith(compareByDescending<Expense> { it.occurredOn }.thenByDescending { it.meta.createdAt }.thenByDescending { it.id.value })
            .map { activityItem(it) },
        history = history(EntityType.RECURRING_EXPENSE, id.value),
    )
}

/** Every due occurrence of every live schedule not on hold, oldest first (spec §5.7). */
fun LedgerViews.dueOccurrences(today: LocalDate): List<DueOccurrence> =
    state.schedules.values
        .flatMap { schedule ->
            val dates = dueDates(schedule, today)
            val waits = confirmToResume(schedule, dates.firstOrNull())
            dates.map { date ->
                DueOccurrence(
                    schedule = schedule.id, description = schedule.description, date = date, amount = schedule.total,
                    group = schedule.groupId?.let(::groupRef), autoPost = schedule.autoPost, confirmToResume = waits,
                )
            }
        }
        .sortedWith(compareBy<DueOccurrence>({ it.date }, { it.description.lowercase() }, { it.schedule.value }))

/**
 * The draft for the schedule's next occurrence (spec §5.7): the template with the occurrence date, payers and
 * participants on canonical people with records combined, and no FX choice. Ended → `OccurrenceNotDue(null)`.
 */
fun LedgerViews.occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft> {
    val schedule = state.schedules[id]?.takeIf { !it.isDeleted } ?: return LedgerResult.Err(LedgerError.NotFound(id.value))
    val next = Recurrence.next(schedule, state) ?: return LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
    val draft = ExpenseDraft(
        payers = inputsOf(schedule.payerRows, schedule.payerSplit.splitType),
        description = schedule.description,
        category = schedule.category,
        total = schedule.total,
        groupId = schedule.groupId,
        splitType = schedule.splitType,
        participants = inputsOf(schedule.shareRows, schedule.splitType),
        occurredOn = next,
        note = schedule.note,
        fx = null,
        payerSplit = schedule.payerSplit,
    )
    return LedgerResult.Ok(OccurrenceDraft(id, next, draft, token(schedule)))
}

/** A schedule's template and pattern as its edit form takes them (also "Open as draft" of an old version). */
internal fun LedgerViews.scheduleDraftOf(schedule: RecurringExpense): RecurringDraft = RecurringDraft(
    payers = inputsOf(schedule.payerRows, schedule.payerSplit.splitType),
    description = schedule.description,
    category = schedule.category,
    total = schedule.total,
    groupId = schedule.groupId,
    splitType = schedule.splitType,
    participants = inputsOf(schedule.shareRows, schedule.splitType),
    note = schedule.note,
    payerSplit = schedule.payerSplit,
    unit = schedule.unit,
    interval = schedule.interval,
    startOn = schedule.startOn,
    endOn = schedule.endOn,
    autoPost = schedule.autoPost,
)

// ---- internals ----

/** Due dates, none for a deleted or held schedule. */
private fun LedgerViews.dueDates(schedule: RecurringExpense, today: LocalDate): List<LocalDate> =
    if (schedule.isDeleted || Recurrence.holds(schedule, state, resolver).isNotEmpty()) emptyList()
    else Recurrence.due(schedule, state, today)

/** Spec §5.7: the runner posts nothing for an auto-post schedule while its next occurrence is before the cut-off. */
private fun confirmToResume(schedule: RecurringExpense, next: LocalDate?): Boolean =
    schedule.autoPost && next != null && next.isBefore(schedule.autoPostFrom)

private fun LedgerViews.holdReasons(schedule: RecurringExpense): List<HoldReason> =
    Recurrence.holds(schedule, state, resolver).map { hold ->
        when (hold) {
            is Recurrence.Hold.GroupArchived -> HoldReason.GroupArchived(groupRef(hold.groupId))
            is Recurrence.Hold.PersonArchived -> HoldReason.PersonArchived(personRef(hold.personId))
        }
    }

private fun LedgerViews.inputsOf(rows: List<RecurringParticipant>, type: SplitType): List<SplitInput> =
    EntryOps.combineInputs(rows.map { SplitInput(it.personId, it.inputValue) }, type, resolver)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.ScheduleViewsTest"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Run the full check**

Run the full check (index, locked decision 9): both JVM suites, the three assembles, both lints. Expected: all tests pass in both flavors (Part 2's total plus 5 in `RecurrenceTest`, 6 in `ScheduleRulesTest`, 4 in `RecurringOpsTest`, 6 in `PostingTest`, 7 in `CutOffTest`, 5 in `ScheduleViewsTest`), lint 0 errors.

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "Serve schedule lists, details, the due list and occurrence drafts"
```
