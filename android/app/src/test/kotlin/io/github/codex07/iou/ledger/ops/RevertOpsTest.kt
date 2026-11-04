package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class RevertOpsTest {

    @Test
    fun `reverting an amount edit adds a new revision with the old amount`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.transfer(ME, x, eur(4_000))
        val h = OpsHarness(ledger.state)
        val edit = TransferDraft(ME, x, eur(4_500), occurredOn = h.state.transfers.getValue(id).occurredOn)
        h.run { EntryOps.saveTransfer(it, edit, SaveMode.Update(id, h.token(h.state.transfers.getValue(id)))) }

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.TRANSFER, id.value), 1, h.token(h.state.transfers.getValue(id)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)

        val reverted = h.state.transfers.getValue(id)
        reverted.amount shouldBe eur(4_000)
        reverted.meta.version shouldBe 3
        h.revisions.last().action shouldBe RevisionAction.UPDATED
        h.revisions.last().reason shouldBe "Reverted to v1"
    }

    @Test
    fun `a version that breaks today's rules is refused with every violated rule`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val group = ledger.group("Trip", members = listOf(ME, a, b))
        val id = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, a to 500L), group = group,
            splitType = SplitType.EXACT, inputs = mapOf(ME to 500L, a to 500L))
        val h = OpsHarness(ledger.state)

        val toBob = ExpenseDraft(ME, "Expense", total = eur(1_000), groupId = group, splitType = SplitType.EXACT,
            participants = listOf(SplitInput(ME, 500), SplitInput(b, 500)), occurredOn = D0)
        h.run { EntryOps.saveExpense(it, toBob, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) }
        h.run { GroupOps.removeMember(it, group, a, h.generation) } shouldBe LedgerResult.Ok(Unit)

        val result = h.run { RevertOps.revertTo(it, EntityRef(EntityType.EXPENSE, id.value), 1, h.token(h.state.expenses.getValue(id)), h.revisions) }
        val conflict = (result as LedgerResult.Err).error.shouldBeInstanceOf<LedgerError.RestoreConflict>()
        conflict.violations.map { it.rule } shouldContain 5
        h.state.expenses.getValue(id).meta.version shouldBe 2
    }

    @Test
    fun `unknown versions and stale tokens are rejected`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val ref = EntityRef(EntityType.PERSON, x.value)

        h.run { RevertOps.revertTo(it, ref, 9, h.token(h.state.people.getValue(x)), h.revisions) } shouldBe
            LedgerResult.Err(LedgerError.NotFound("${x.value} v9"))
        h.run { RevertOps.revertTo(it, ref, 1, h.token(h.state.people.getValue(x)).copy(version = 5), h.revisions) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(x.value, 5, 1))
    }

    @Test
    fun `reverting a handle to a version that was the default clears the newer default`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        fun add(value: String, label: String) =
            (h.run { PeopleOps.addHandle(it, x, HandleDraft(HandleType.IBAN, value, label = label, isDefault = true), h.generation) } as LedgerResult.Ok).value
        val a = add("GB82WEST12345698765432", "A")
        val b = add("DE89370400440532013000", "B")
        h.state.handles.getValue(a).isDefault shouldBe false

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.PAYMENT_HANDLE, a.value), 1, h.token(h.state.handles.getValue(a)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)
        h.state.handles.getValue(a).isDefault shouldBe true
        h.state.handles.getValue(b).isDefault shouldBe false
    }

    @Test
    fun `reverting a default handle to its old type clears that type's newer default`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        fun add(label: String) =
            (h.run { PeopleOps.addHandle(it, x, HandleDraft(HandleType.OTHER, "pay $label", label = label, isDefault = true), h.generation) } as LedgerResult.Ok).value
        val a = add("A")
        val iban = HandleDraft(HandleType.IBAN, "DE89370400440532013000", label = "A", isDefault = true)
        h.run { PeopleOps.updateHandle(it, a, iban, h.token(h.state.handles.getValue(a))) } shouldBe LedgerResult.Ok(Unit)
        val b = add("B")

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.PAYMENT_HANDLE, a.value), 1, h.token(h.state.handles.getValue(a)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)
        h.state.handles.getValue(a).type shouldBe HandleType.OTHER
        h.state.handles.getValue(a).isDefault shouldBe true
        h.state.handles.getValue(b).isDefault shouldBe false
    }

    @Test
    fun `reverting a deleted handle to a default version leaves the live default alone`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        fun add(value: String, label: String) =
            (h.run { PeopleOps.addHandle(it, x, HandleDraft(HandleType.IBAN, value, label = label, isDefault = true), h.generation) } as LedgerResult.Ok).value
        val a = add("GB82WEST12345698765432", "A")
        val b = add("DE89370400440532013000", "B")
        h.run { PeopleOps.deleteHandle(it, a, h.token(h.state.handles.getValue(a))) } shouldBe LedgerResult.Ok(Unit)
        val revisionsBefore = h.revisions.size

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.PAYMENT_HANDLE, a.value), 1, h.token(h.state.handles.getValue(a)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)
        h.state.handles.getValue(a).isDeleted shouldBe true
        h.state.handles.getValue(b).isDefault shouldBe true
        h.revisions.size shouldBe revisionsBefore + 1
    }

    @Test
    fun `reverting a person to a version from before a merge keeps the merge`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        ledger.transfer(ME, alexS, eur(500))
        val h = OpsHarness(ledger.state)
        h.run { PeopleOps.updatePerson(it, alexS, PersonDraft("Alex S."), h.token(h.state.people.getValue(alexS))) }
        h.run { PeopleOps.merge(it, alexS, alex, h.token(h.state.people.getValue(alexS))) }

        val ref = EntityRef(EntityType.PERSON, alexS.value)
        h.run { RevertOps.revertTo(it, ref, 1, h.token(h.state.people.getValue(alexS)), h.revisions) } shouldBe LedgerResult.Ok(Unit)

        h.state.people.getValue(alexS).displayName shouldBe "AlexS"
        h.state.people.getValue(alexS).mergedIntoId shouldBe alex
        LedgerEngine.compute(h.state).net(alex) shouldBe mapOf(EUR to 500L)
    }
}
