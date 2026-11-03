package io.github.codex07.iou.api.contract

import app.cash.turbine.test
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.TransferKind
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

abstract class LedgerApiContract : ApiContract() {

    @Test
    fun `a loan and a repayment leave the rest owed, regardless of account`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, eur(5_000))
        apis.ledger.saveTransfer(
            TransferDraft(x, me, eur(2_000), TransferKind.REPAYMENT, accountLabel = "Other bank", occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(3_000))
        apis.ledger.observeOverview().first().owedToMe shouldBe listOf(eur(3_000))
    }

    @Test
    fun `writes update observers`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        apis.ledger.observeOverview().test {
            awaitItem().owedToMe shouldBe emptyList()
            transfer(me, x, eur(1_000))
            awaitItem().owedToMe shouldBe listOf(eur(1_000))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `entries outside groups involve me and exactly one other person`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val y = newPerson("Y")
        val pizza = ExpenseDraft(me, "Pizza", total = eur(3_000), participants = listOf(SplitInput(me), SplitInput(x), SplitInput(y)), occurredOn = TODAY)

        apis.ledger.saveExpense(pizza, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.NeedsGroup(setOf(me, x, y)))
        apis.ledger.saveTransfer(TransferDraft(x, y, eur(100), occurredOn = TODAY), SaveMode.Create(basis())) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(x, y)))
    }

    @Test
    fun `an edit is diffed in history and can be reverted`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(4_000))
        val detail = apis.ledger.observeEntry(id).first()!!
        apis.ledger.saveTransfer((detail.editDraft as TransferDraft).copy(amount = eur(4_500)), SaveMode.Update(id, detail.token)).orFail()

        val edited = apis.ledger.observeEntry(id).first()!!
        edited.history.first().changes shouldContain FieldChange("amount", "40.00 EUR", "45.00 EUR")
        apis.ledger.revertTo(EntityRef(EntityType.TRANSFER, id.value), 1, edited.token).orFail()

        val reverted = apis.ledger.observeEntry(id).first()!!
        reverted.item.paid shouldBe eur(4_000)
        reverted.history.size shouldBe 3
        reverted.history.first().reason shouldBe "Reverted to v1"
    }

    @Test
    fun `delete removes the effect at once and restore brings it back`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(700))
        apis.ledger.deleteEntry(id, apis.ledger.observeEntry(id).first()!!.token).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
        apis.ledger.restoreEntry(id, apis.ledger.observeEntry(id).first()!!.token).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(700))
    }

    @Test
    fun `settling up with a person zeroes the balance`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, eur(1_500))
        val draft = apis.ledger.settleUpWithPerson(x, basis()).orFail().single()
        draft.draft.from shouldBe x
        apis.ledger.saveTransfer(draft.draft, SaveMode.Create(draft.basis)).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
    }

    @Test
    fun `a cross-currency settlement asks for a rate, then settles the other currency`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, sek(10_000))
        val repayment = TransferDraft(x, me, eur(900), TransferKind.REPAYMENT, occurredOn = TODAY, settlesCurrency = SEK)

        apis.ledger.saveTransfer(repayment, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.RateRequired(EUR, SEK))
        val quote = apis.fx.userRate(TODAY, EUR, SEK, "11.1111111111").orFail()
        apis.ledger.saveTransfer(repayment.copy(fx = FxChoice.Rate(quote)), SaveMode.Create(basis())).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
    }

    @Tag("groups")
    @Test
    fun `an equal split of 100 among three sums exactly`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val trip = apis.groups.createGroup(GroupDraft("Trip", EUR, FxMode.KEEP), listOf(a, b), basis()).orFail()
        val id = apis.ledger.saveExpense(
            ExpenseDraft(me, "Taxi", total = eur(10_000), groupId = trip, participants = listOf(SplitInput(me), SplitInput(a), SplitInput(b)), occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        val shares = apis.ledger.observeEntry(id).first()!!.shares.map { it.share.amountMinor }
        shares.sorted() shouldBe listOf(3_333L, 3_333L, 3_334L)
    }

    @Tag("groups")
    @Test
    fun `the dinner example gives the plan, the overview and the In groups suggestion, and the plan settles`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val dinner = apis.groups.createGroup(GroupDraft("Dinner", EUR, FxMode.KEEP), listOf(a, b), basis()).orFail()
        apis.ledger.saveExpense(
            ExpenseDraft(a, "Dinner", total = eur(6_000), groupId = dinner, participants = listOf(SplitInput(a), SplitInput(b), SplitInput(me)), occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        val plan = apis.groups.observeGroup(dinner).first()!!.settlePlan
        plan.map { Triple(it.from.id, it.to.id, it.amount) }.toSet() shouldBe setOf(Triple(b, a, eur(2_000)), Triple(me, a, eur(2_000)))
        apis.ledger.observeOverview().first().iOwe shouldBe listOf(eur(2_000))
        apis.people.observePerson(a).first()!!.inGroups.single().items.single().amount shouldBe eur(2_000)

        plan.forEach { item ->
            val draft = apis.ledger.settleUpFromPlan(item).orFail()
            apis.ledger.saveTransfer(draft.draft, SaveMode.Create(draft.basis)).orFail()
        }
        apis.groups.observeGroup(dinner).first()!!.members.all { it.nets.isEmpty() } shouldBe true
    }
}
