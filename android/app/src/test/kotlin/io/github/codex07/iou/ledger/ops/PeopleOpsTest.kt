package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PeopleOpsTest {

    @Test
    fun `setup creates me once`() {
        val h = OpsHarness(LedgerState())
        val me = (h.run { PeopleOps.setup(it, " Puneet ") } as LedgerResult.Ok).value

        h.state.me.id shouldBe me
        h.state.me.displayName shouldBe "Puneet"
        h.state.me.meta.authorId shouldBe me
        h.run { PeopleOps.setup(it, "Again") } shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
        OpsHarness(LedgerState()).run { PeopleOps.setup(it, " ") } shouldBe
            LedgerResult.Err(LedgerError.InvalidField("displayName"))
    }

    @Test
    fun `creates need the current generation as basis`() {
        val h = OpsHarness(TestLedger().apply { me() }.state)
        h.run { PeopleOps.createPerson(it, PersonDraft("Alex"), LedgerGeneration("old")) } shouldBe
            LedgerResult.Err(LedgerError.LedgerReplaced)

        val id = (h.run { PeopleOps.createPerson(it, PersonDraft(" Alex ", note = " "), h.generation) } as LedgerResult.Ok).value
        val alex = h.state.people.getValue(id)
        alex.displayName shouldBe "Alex"
        alex.note shouldBe null
        alex.meta.version shouldBe 1
        h.revisions.last().action shouldBe RevisionAction.CREATED
    }

    @Test
    fun `updates check generation, then existence, then version`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.updatePerson(it, x, PersonDraft("Y"), EditToken(LedgerGeneration("old"), 1)) } shouldBe
            LedgerResult.Err(LedgerError.LedgerReplaced)
        h.run { PeopleOps.updatePerson(it, x, PersonDraft("Y"), EditToken(h.generation, 7)) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(x.value, 7, 1))
        h.run { PeopleOps.updatePerson(it, x, PersonDraft("Y"), h.token(h.state.people.getValue(x))) } shouldBe
            LedgerResult.Ok(Unit)
        h.state.people.getValue(x).displayName shouldBe "Y"
        h.state.people.getValue(x).meta.version shouldBe 2
        h.state.people.getValue(x).meta.updatedAt shouldBe h.now
        h.run { PeopleOps.updatePerson(it, PersonId("nobody"), PersonDraft("Z"), EditToken(h.generation, 1)) } shouldBe
            LedgerResult.Err(LedgerError.NotFound("nobody"))
        // An edit prepared before a restore whose ledger lacks the row is still LedgerReplaced.
        h.run { PeopleOps.updatePerson(it, PersonId("nobody"), PersonDraft("Z"), EditToken(LedgerGeneration("old"), 1)) } shouldBe
            LedgerResult.Err(LedgerError.LedgerReplaced)
    }

    @Test
    fun `archiving is allowed with a balance, but never for me`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.transfer(ME, x, eur(1_000))
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.setPersonArchived(it, x, true, h.token(h.state.people.getValue(x))) } shouldBe LedgerResult.Ok(Unit)
        h.state.people.getValue(x).archivedAt shouldBe h.now
        h.revisions.last().reason shouldBe "Archived"
        h.run { PeopleOps.setPersonArchived(it, ME, true, h.token(h.state.me)) } shouldBe
            LedgerResult.Err(LedgerError.CannotModifyMe)
    }

    @Test
    fun `delete cascades to live handles with one deletedAt, restore brings back exactly those`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val live = ledger.handle(x)
        val earlier = ledger.handle(x, deleted = true)
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.deletePerson(it, x, h.token(h.state.people.getValue(x))) } shouldBe LedgerResult.Ok(Unit)
        h.lastPlan!!.changes.map { it.entity.entityId } shouldBe listOf(x.value, live.value)
        h.state.people.getValue(x).meta.deletedAt shouldBe h.now
        h.state.handles.getValue(live).meta.deletedAt shouldBe h.now
        h.state.handles.getValue(earlier).meta.deletedAt shouldBe T0

        h.now = h.now.plusSeconds(60)
        h.run { PeopleOps.restorePerson(it, x, h.token(h.state.people.getValue(x))) } shouldBe LedgerResult.Ok(Unit)
        h.state.people.getValue(x).isDeleted shouldBe false
        h.state.handles.getValue(live).isDeleted shouldBe false
        h.state.handles.getValue(earlier).isDeleted shouldBe true
        h.revisions.takeLast(2).map { it.action } shouldBe listOf(RevisionAction.RESTORED, RevisionAction.RESTORED)
    }

    @Test
    fun `referenced people and me cannot be deleted`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.transfer(ME, x, eur(100), deleted = true)
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.deletePerson(it, x, h.token(h.state.people.getValue(x))) } shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(x.value))
        h.run { PeopleOps.deletePerson(it, ME, h.token(h.state.me)) }.shouldBeInstanceOf<LedgerResult.Err>()
    }
}
