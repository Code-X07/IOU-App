package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SampleLedgers
import io.github.codex07.iou.testutil.SampleLedgers.LOCAL_ME
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A full export into an empty install of another identity, and its undo (spec §7.4, §5.4, AC M2-16, 19, 35). */
class RoundTripTest {

    private fun PersonId.local() = if (this == ME) LOCAL_ME else this

    private fun LedgerState.rowKeys(deleted: Boolean) =
        (people.values.filter { !it.isMe } + handles.values + groups.values + members.values + entries + schedules.values)
            .filter { it.isDeleted == deleted }.map { it.rowKey }.toSet()

    @Test
    fun `a full export imports into an empty install, reproduces every row and net, and undoes to nothing`() = runTest {
        val source = SampleLedgers.full()
        val bench = ImportBench(SampleLedgers.localMe())

        val preview = bench.preview(source)
        val batch = bench.commit(preview)
        val state = bench.state

        preview.identity shouldBe ImportIdentity.Mapped(ME)
        preview.invalidRows shouldBe emptyList()
        state.rowKeys(deleted = false) shouldBe source.rowKeys(deleted = false)
        state.rowKeys(deleted = true) shouldBe source.rowKeys(deleted = true)
        state.fxSnapshots shouldBe source.fxSnapshots
        for (expense in state.expenses.values) {
            val original = source.expenses.getValue(expense.id)
            expense.payers shouldBe original.payers.map { it.copy(personId = it.personId.local()) }
            expense.shares shouldBe original.shares.map { it.copy(personId = it.personId.local()) }
            expense.recurringId shouldBe original.recurringId
            expense.recurringOn shouldBe original.recurringOn
        }
        for (schedule in state.schedules.values) {
            val original = source.schedules.getValue(schedule.id)
            schedule.copy(autoPostFrom = original.autoPostFrom, meta = original.meta) shouldBe
                original.copy(participants = original.participants.map { it.copy(personId = it.personId.local()) })
        }
        val old = state.groups.getValue(GroupId("group-old"))
        state.members.values.filter { it.groupId == old.id }.map { it.meta.deletedAt }.toSet() shouldBe setOf(old.meta.deletedAt!!)
        val before = LedgerEngine.compute(source)
        val after = LedgerEngine.compute(state)
        after.pairwise shouldBe before.pairwise
        after.groupNets shouldBe before.groupNets.mapValues { (_, nets) -> nets.mapKeys { it.key.local() } }

        val undo = bench.undo(batch)

        undo.skipped shouldBe emptyList()
        bench.state.rowKeys(deleted = false) shouldBe emptySet()
        val undone = LedgerEngine.compute(bench.state)
        undone.pairwise.values.all { it.isEmpty() } shouldBe true
        undone.groupNets.values.all { nets -> nets.values.all { it.isEmpty() } } shouldBe true
    }

    @Test
    fun `importing the same export twice writes nothing the second time`() = runTest {
        val source = SampleLedgers.full()
        val bench = ImportBench(SampleLedgers.localMe())
        bench.import(source)
        val once = bench.state

        val again = bench.preview(source, name = "again.zip")
        bench.commit(again)

        again.rows shouldBe emptyList()
        again.counts.new shouldBe 0
        again.counts.conflicts shouldBe 0
        bench.state shouldBe once
    }
}
