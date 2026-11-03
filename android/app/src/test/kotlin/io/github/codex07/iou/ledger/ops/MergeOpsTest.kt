package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MergeProblem
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MergeOpsTest {

    @Test
    fun `handles are normalized and a new default clears the old one across aliases`() {
        val ledger = TestLedger().apply { me() }
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val old = ledger.handle(target, HandleType.IBAN, isDefault = true)
        val h = OpsHarness(ledger.state)

        val draft = HandleDraft(HandleType.IBAN, "gb82 west 1234 5698 7654 32", label = "UK", isDefault = true)
        val id = (h.run { PeopleOps.addHandle(it, alias, draft, h.generation) } as LedgerResult.Ok).value

        h.state.handles.getValue(id).value shouldBe "GB82WEST12345698765432"
        h.state.handles.getValue(id).isDefault shouldBe true
        h.state.handles.getValue(old).isDefault shouldBe false
        IdentityResolver(h.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe id
        h.run { PeopleOps.addHandle(it, alias, draft.copy(value = "GB00WEST12345698765432"), h.generation) } shouldBe
            LedgerResult.Err(LedgerError.InvalidIban("GB00WEST12345698765432"))
    }

    @Test
    fun `editing a handle as shown keeps every stored default flag`() {
        val ledger = TestLedger().apply { me() }
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val own = ledger.handle(target, HandleType.IBAN, isDefault = true)
        val hidden = ledger.handle(alias, HandleType.IBAN, value = "GB82WEST12345698765432", isDefault = true)
        val h = OpsHarness(ledger.state)
        fun shown(id: HandleId) = h.state.handles.getValue(id).let {
            // As the UI fills it from PaymentHandleView: isDefault is the canonical default.
            HandleDraft(it.type, it.value, "${it.label}!", it.holderName, it.bic, IdentityResolver(h.state).defaultHandle(target, it.type)?.id == id)
        }

        h.run { PeopleOps.updateHandle(it, hidden, shown(hidden), h.token(h.state.handles.getValue(hidden))) } shouldBe LedgerResult.Ok(Unit)
        h.run { PeopleOps.updateHandle(it, own, shown(own), h.token(h.state.handles.getValue(own))) } shouldBe LedgerResult.Ok(Unit)
        h.lastPlan!!.changes.single().entity.entityId shouldBe own.value
        h.state.handles.getValue(hidden).isDefault shouldBe true
        h.state.handles.getValue(own).isDefault shouldBe true

        h.run { PeopleOps.unmerge(it, alias, h.token(h.state.people.getValue(alias))) } shouldBe LedgerResult.Ok(Unit)
        IdentityResolver(h.state).defaultHandle(alias, HandleType.IBAN)?.id shouldBe hidden

        h.run { PeopleOps.merge(it, alias, target, h.token(h.state.people.getValue(alias))) } shouldBe LedgerResult.Ok(Unit)
        h.run { PeopleOps.updateHandle(it, hidden, shown(hidden).copy(isDefault = true), h.token(h.state.handles.getValue(hidden))) }
        IdentityResolver(h.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe hidden
        h.state.handles.getValue(own).isDefault shouldBe false
    }

    @Test
    fun `merge writes only the source row and combines balances, unmerge splits them again`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        ledger.transfer(ME, alex, eur(1_000))
        ledger.transfer(ME, alexS, eur(500))
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.merge(it, alexS, alex, h.token(h.state.people.getValue(alexS))) } shouldBe LedgerResult.Ok(Unit)
        h.lastPlan!!.changes.single().entity.entityId shouldBe alexS.value
        h.revisions.last().reason shouldBe "Merged into Alex"
        LedgerEngine.compute(h.state).net(alex) shouldBe mapOf(EUR to 1_500L)

        h.run { PeopleOps.unmerge(it, alexS, h.token(h.state.people.getValue(alexS))) } shouldBe LedgerResult.Ok(Unit)
        h.revisions.last().reason shouldBe "Unmerged from Alex"
        LedgerEngine.compute(h.state).net(alex) shouldBe mapOf(EUR to 1_000L)
        LedgerEngine.compute(h.state).net(alexS) shouldBe mapOf(EUR to 500L)
    }

    @Test
    fun `unmerging a middle record takes its own aliases along`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val smith = ledger.person("Smith")
        ledger.transfer(ME, alex, eur(1_000))
        ledger.transfer(ME, alexS, eur(500))
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.merge(it, alex, alexS, h.token(h.state.people.getValue(alex))) }
        val beforeSecondMerge = LedgerEngine.compute(h.state)
        h.run { PeopleOps.merge(it, alexS, smith, h.token(h.state.people.getValue(alexS))) }
        LedgerEngine.compute(h.state).net(smith) shouldBe mapOf(EUR to 1_500L)

        h.run { PeopleOps.unmerge(it, alexS, h.token(h.state.people.getValue(alexS))) }
        LedgerEngine.compute(h.state) shouldBe beforeSecondMerge
        IdentityResolver(h.state).canonical(alex) shouldBe alexS
    }

    @Test
    fun `merge preconditions`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val c = ledger.person("C", mergedInto = b)
        val gone = ledger.person("Gone", deleted = true)
        val h = OpsHarness(ledger.state)
        val tokenA = h.token(h.state.people.getValue(a))

        h.run { PeopleOps.merge(it, a, a, tokenA) } shouldBe LedgerResult.Err(LedgerError.InvalidMerge(MergeProblem.SAME_PERSON))
        h.run { PeopleOps.merge(it, a, ME, tokenA) } shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
        h.run { PeopleOps.merge(it, ME, a, h.token(h.state.me)) } shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
        h.run { PeopleOps.merge(it, a, c, tokenA) } shouldBe LedgerResult.Err(LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL))
        h.run { PeopleOps.merge(it, a, gone, tokenA) } shouldBe LedgerResult.Err(LedgerError.InvalidMerge(MergeProblem.DELETED))
        h.run { PeopleOps.unmerge(it, a, tokenA) } shouldBe LedgerResult.Err(LedgerError.InvalidMerge(MergeProblem.NOT_MERGED))
    }

    @Test
    fun `merge then unmerge restores identical balances and canonical ids`() = runTest {
        checkAll(Arb.list(Arb.pair(Arb.int(1..4), Arb.long(1L..MAX_MINOR / 100)), 0..20)) { moves ->
            val ledger = TestLedger().apply { me() }
            val p2 = ledger.person("P2")
            val p1 = ledger.person("P1", mergedInto = p2)
            val p3 = ledger.person("P3")
            val p4 = ledger.person("P4", mergedInto = p3)
            val people = listOf(ME, p1, p2, p3, p4)
            moves.forEachIndexed { i, (who, amount) ->
                if (i % 2 == 0) ledger.transfer(ME, people[who], eur(amount)) else ledger.transfer(people[who], ME, eur(amount))
            }
            val h = OpsHarness(ledger.state)
            val before = LedgerEngine.compute(h.state)
            val canonicalBefore = people.associateWith { IdentityResolver(h.state).canonical(it) }

            h.run { PeopleOps.merge(it, p2, p3, h.token(h.state.people.getValue(p2))) } shouldBe LedgerResult.Ok(Unit)
            h.run { PeopleOps.unmerge(it, p2, h.token(h.state.people.getValue(p2))) } shouldBe LedgerResult.Ok(Unit)

            LedgerEngine.compute(h.state) shouldBe before
            people.associateWith { IdentityResolver(h.state).canonical(it) } shouldBe canonicalBefore
        }
    }
}
