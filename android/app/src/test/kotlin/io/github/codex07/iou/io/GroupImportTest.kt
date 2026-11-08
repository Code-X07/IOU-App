package io.github.codex07.iou.io

import io.github.codex07.iou.api.BalanceChange
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Group rows through the import (spec §7.4, AC M2-14, 16, 19). */
class GroupImportTest {

    private fun empty(): LedgerState = TestLedger().apply { me() }.state

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

    @Test
    fun `a group with a removed member and their historical entries imports as is and reproduces every net`() = runTest {
        val source = trip()
        val bench = ImportBench(empty())

        val preview = bench.preview(source)

        preview.invalidRows shouldBe emptyList()
        preview.balanceChanges shouldContain BalanceChange("Trip (group)", emptyList(), listOf(eur(-3_000)))
        bench.commit(preview)
        LedgerEngine.compute(bench.state).groupNets shouldBe LedgerEngine.compute(source).groupNets
        LedgerEngine.compute(bench.state).pairwise shouldBe LedgerEngine.compute(source).pairwise
        bench.state.members.values.single { it.personId.value == "person-al" }.removedAt shouldBe
            source.members.values.single { it.personId.value == "person-al" }.removedAt
    }

    @Test
    fun `a deleted group comes with its memberships under one local deletedAt, and restoring it brings them back`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Old", members = listOf(ME, x), deleted = true)
            state
        }
        val bench = ImportBench(empty())

        bench.import(source)

        val old = bench.state.groups.getValue(GroupId("group-old"))
        val members = bench.state.members.values.filter { it.groupId == old.id }
        members.size shouldBe 2
        members.map { it.meta.deletedAt }.toSet() shouldBe setOf(old.meta.deletedAt!!)
        with(bench) { groups.restoreGroup(old.id, token(old)).ok() }
        bench.state.members.values.filter { it.groupId == old.id }.all { !it.isDeleted } shouldBe true
    }

    @Test
    fun `taking a group deleted in the export shows its memberships as deleted with it and deletes them together`() = runTest {
        val local = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x))
            state
        }
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x), deleted = true)
            state
        }
        val bench = ImportBench(local)
        val flat = ImportRowKey(EntityType.GROUP, "group-flat")
        val preview = bench.preview(source)
        val take = ImportChoices(takeIncoming = setOf(flat))

        preview.rows.single { it.key == flat }.choice shouldBe ImportRowChoice.KeepOrTake
        val taken = bench.io.applyChoices(preview, take)
        taken.rows.filter { it.key.type == EntityType.GROUP_MEMBER }.map { it.choice }.toSet() shouldBe
            setOf(ImportRowChoice.DeletedWith("Flat"))
        taken.rows.count { it.key.type == EntityType.GROUP_MEMBER } shouldBe 2

        bench.commit(preview, take)

        val group = bench.state.groups.getValue(GroupId("group-flat"))
        group.isDeleted shouldBe true
        bench.state.members.values.filter { it.groupId == group.id }.map { it.meta.deletedAt }.toSet() shouldBe setOf(group.meta.deletedAt!!)
        bench.store.loaded().revisions.filter { it.entityType == EntityType.GROUP_MEMBER && it.action == RevisionAction.DELETED }
            .map { it.reason } shouldBe List(2) { "Import conflict resolved from export.zip (source v1) with Flat" }
    }

    @Test
    fun `taking a person's deletion moves a handle deleted here earlier into their cascade when the export deleted them together`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val handleKey = ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)
        val local = TestLedger().run {
            me()
            handle(person("X"), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value)))
        val preview = bench.preview(source)

        bench.io.applyChoices(preview, take).rows.single { it.key == handleKey }.choice shouldBe ImportRowChoice.DeletedWith("X")
        bench.commit(preview, take)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe bench.now
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a handle the export shows active joins the cascade of a person kept deleted here`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val handleKey = ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val source = TestLedger().run {
            me()
            handle(person("X"), id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.rows.single { it.key == handleKey }.choice shouldBe ImportRowChoice.DeletedWith("X")
        bench.commit(preview)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a duplicate handle the export shows deleted apart leaves the cascade of a person kept deleted here`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val source = local.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val bench = ImportBench(local)

        bench.import(source)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0.minusNanos(1)
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `a skipped invalid handle keeps its own deletedAt under a person kept deleted`() = runTest {
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
            // Right shape, wrong mod-97 checksum.
            handle(person("X"), value = "DE00000000000000000000", id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.invalidRows.size shouldBe 1
        bench.commit(preview, ImportChoices(skipInvalidRows = true))

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `a skipped invalid person row leaves its handle in the local cascade`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val source = TestLedger().run {
            me()
            // Active with a blank name: rejected, so its missing deletedAt says nothing about the handle's grouping.
            handle(person(" ", id = x.value), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.invalidRows.size shouldBe 1
        bench.commit(preview, ImportChoices(skipInvalidRows = true))

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a group entry naming someone without a membership is an invalid row`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            val stranger = person("Stranger")
            val trip = group("Trip", members = listOf(ME, x))
            expense(ME, eur(1_000), mapOf(stranger to 1_000L), group = trip, id = "e-1")
            state
        }
        val bench = ImportBench(empty())

        val invalid = bench.preview(source).invalidRows

        invalid.map { it.table to it.reason.substringBefore(':') } shouldBe listOf("expenses" to "breaks rule 3")
    }
}
