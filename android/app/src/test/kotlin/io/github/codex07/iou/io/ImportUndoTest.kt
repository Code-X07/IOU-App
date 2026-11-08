package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.SkippedRow
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Undo of imports that wrote groups, memberships and schedules (spec §5.4, AC M2-16, 19, 35). */
class ImportUndoTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun empty(): LedgerState = TestLedger().apply { me() }.state

    private val trip = GroupId("group-trip")
    private val al = PersonId("person-al")

    /**
     * Trip (KEEP EUR): me and Bea active, Al removed. I paid €100 for Al and Al paid it back, so Al's net is 0;
     * Bea paid €60 split with me.
     */
    private fun trip(): LedgerState = TestLedger().run {
        me()
        val al = person("Al")
        val bea = person("Bea")
        val trip = group("Trip", members = listOf(ME, bea))
        member(trip, al, removed = true)
        expense(ME, eur(10_000), mapOf(al to 10_000L), group = trip, id = "e-al")
        transfer(al, ME, eur(10_000), group = trip, kind = TransferKind.REPAYMENT, id = "t-al")
        expense(bea, eur(6_000), mapOf(ME to 3_000L, bea to 3_000L), group = trip, id = "e-bea")
        state
    }

    private fun LedgerState.allNetsZero(): Boolean {
        val balances = LedgerEngine.compute(this)
        return balances.pairwise.values.all { it.isEmpty() } && balances.groupNets.values.all { nets -> nets.values.all { it.isEmpty() } }
    }

    @Test
    fun `undoing an unchanged import of a group with a removed member removes everything and reports nothing`() = runTest {
        val bench = ImportBench(empty())
        val batch = bench.import(trip())

        val result = bench.undo(batch)

        result.skipped shouldBe emptyList()
        val state = bench.state
        state.groups.values.all { it.isDeleted } shouldBe true
        state.members.values.all { it.isDeleted } shouldBe true
        state.entries.all { it.isDeleted } shouldBe true
        state.people.values.filter { !it.isMe }.all { it.isDeleted } shouldBe true
        state.allNetsZero() shouldBe true
    }

    @Test
    fun `a group the undone import created comes back with its memberships when restored`() = runTest {
        val local = TestLedger().run {
            me()
            person("X")
            state
        }
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x))
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source)

        bench.undo(batch).skipped shouldBe emptyList()

        val flat = bench.state.groups.getValue(GroupId("group-flat"))
        val members = bench.state.members.values.filter { it.groupId == flat.id }
        flat.isDeleted shouldBe true
        members.map { it.meta.deletedAt }.toSet() shouldBe setOf(flat.meta.deletedAt!!)
        with(bench) { groups.restoreGroup(flat.id, token(flat)).ok() }
        bench.state.members.values.filter { it.groupId == flat.id }.all { !it.isDeleted } shouldBe true
    }

    @Test
    fun `undoing an import that brought November's expense and progress makes November due again`() = runTest {
        val rent = RecurringId("rent")
        fun ledger(handled: LocalDate, november: Boolean) = TestLedger().run {
            me()
            val x = person("X")
            schedule(ME, eur(90_000), listOf(ME, x), id = "rent", handledThrough = handled)
            if (november) {
                expense(
                    ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), id = "e-nov", occurredOn = d("2026-11-01"),
                    recurring = rent, recurringOn = d("2026-11-01"),
                )
            }
            state
        }
        val bench = ImportBench(ledger(d("2026-10-01"), november = false))
        val batch = bench.import(ledger(d("2026-11-01"), november = true))
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-11-01")

        val result = bench.undo(batch)

        result.skipped shouldBe emptyList()
        bench.state.expenses.getValue(EntryId("e-nov")).isDeleted shouldBe true
        val schedule = bench.state.schedules.getValue(rent)
        schedule.handledThrough shouldBe d("2026-10-01")
        Recurrence.next(schedule, bench.state) shouldBe d("2026-11-01")
    }

    @Test
    fun `when the whole undo breaks a rule, entries tied by a removed member still go back together`() = runTest {
        val bench = ImportBench(empty())
        val batch = bench.import(trip())
        // A transfer added after the import keeps the group and Bea alive: deleting either would break rule 10.
        with(bench) {
            ledger.saveTransfer(TransferDraft(ME, PersonId("person-bea"), eur(500), groupId = trip, occurredOn = D0), SaveMode.Create(basis())).ok()
        }

        val result = bench.undo(batch)

        val state = bench.state
        listOf("e-al", "t-al", "e-bea").all { state.entry(EntryId(it))!!.isDeleted } shouldBe true
        result.skipped shouldContain SkippedRow(ImportRowKey(EntityType.GROUP, trip.value), "would break rule 3")
        result.skipped shouldContain SkippedRow(ImportRowKey(EntityType.PERSON, "person-bea"), "would break rule 10")
        state.groups.getValue(trip).isDeleted shouldBe false
        LedgerEngine.compute(state).groupNet(trip, al) shouldBe emptyMap()
    }

    @Test
    fun `undoing an import that restored a person and a handle deleted apart deletes them apart again`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val earlier = T0.minusSeconds(3_600)
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = earlier)))
        }
        val source = TestLedger().run {
            me()
            handle(person("X"), id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value), ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)))
        val batch = bench.import(source, take)
        bench.state.handles.getValue(handleId).isDeleted shouldBe false

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.people.getValue(x).meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.people.getValue(x).isDeleted shouldBe false
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `undoing an import that moved an earlier deleted handle into its person's cascade gives the handle its own deletedAt back`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val earlier = T0.minusSeconds(3_600)
        val local = TestLedger().run {
            me()
            handle(person("X"), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = earlier)))
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source, ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value))))
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.people.getValue(x).isDeleted shouldBe false
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
    }

    @Test
    fun `undoing an import that created a handle under a person kept deleted moves it out of the person's cascade`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            person("X", deleted = true)
            state
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source)
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }
}
