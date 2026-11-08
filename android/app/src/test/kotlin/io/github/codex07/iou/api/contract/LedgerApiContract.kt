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
import io.github.codex07.iou.ledger.model.PayerSplit
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

    @Tag("groups")
    @Test
    fun `two payers in a group give nets of what each paid minus their share`() = runTest {
        setUpMe()
        val alice = newPerson("Alice")
        val bob = newPerson("Bob")
        val carol = newPerson("Carol")
        val group = apis.groups.createGroup(GroupDraft("Dinner", EUR, FxMode.KEEP), listOf(alice, bob, carol), basis()).orFail()
        val draft = ExpenseDraft(
            listOf(SplitInput(alice, 6_000), SplitInput(bob, 4_000)), "Dinner", total = eur(10_000), groupId = group,
            participants = listOf(SplitInput(alice), SplitInput(bob), SplitInput(carol)), occurredOn = TODAY,
            payerSplit = PayerSplit.EXACT,
        )
        val id = apis.ledger.saveExpense(draft, SaveMode.Create(basis())).orFail()

        val detail = apis.ledger.observeEntry(id).first()!!
        detail.item.payers.map { it.person.id to it.paid } shouldBe listOf(alice to eur(6_000), bob to eur(4_000))
        // Who gets the extra cent of 100 / 3 depends on the generated IDs, so the expected nets come from the
        // stored shares: Alice +26.66 or +26.67, Bob +6.67 or +6.66, Carol −33.33 or −33.34 (AC 23).
        val share = detail.shares.associate { it.person.id to it.share.amountMinor }
        val paid = mapOf(alice to 6_000L, bob to 4_000L, carol to 0L)
        val nets = apis.groups.observeGroup(group).first()!!.members.associate { it.person.id to it.nets }
        for (person in listOf(alice, bob, carol)) {
            nets.getValue(person) shouldBe listOf(eur(paid.getValue(person) - share.getValue(person)))
        }
        detail.flows.map { Triple(it.paidBy.id, it.paidFor.id, it.amount) }.toSet() shouldBe setOf(
            Triple(alice, carol, eur(6_000 - share.getValue(alice))),
            Triple(bob, carol, eur(4_000 - share.getValue(bob))),
        )

        apis.ledger.saveExpense(draft.copy(payers = listOf(SplitInput(alice, 6_000), SplitInput(bob, 3_000))), SaveMode.Create(basis())) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(1_000))
        val equal = apis.ledger.previewSplit(draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(alice), SplitInput(bob)))).orFail()
        equal.payers.map { it.paid } shouldBe listOf(eur(5_000), eur(5_000))
    }

    @Test
    fun `outside groups me and the other person can both pay, but a third payer needs a group`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val y = newPerson("Y")
        val lunch = ExpenseDraft(
            listOf(SplitInput(me, 3_000), SplitInput(x, 7_000)), "Lunch", total = eur(10_000),
            participants = listOf(SplitInput(me), SplitInput(x)), occurredOn = TODAY, payerSplit = PayerSplit.EXACT,
        )
        apis.ledger.saveExpense(lunch, SaveMode.Create(basis())).orFail()

        // I paid 30 of my 50: I owe X 20.00 (AC 24).
        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(-2_000))
        apis.ledger.observeOverview().first().iOwe shouldBe listOf(eur(2_000))
        val third = lunch.copy(payers = listOf(SplitInput(me, 3_000), SplitInput(x, 4_000), SplitInput(y, 3_000)))
        apis.ledger.saveExpense(third, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.NeedsGroup(setOf(me, x, y)))
    }

    @Tag("groups")
    @Test
    fun `exact payers of a converted expense add up to the converted total, and a note edit keeps payers and shares`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val flat = apis.groups.createGroup(GroupDraft("Flat", EUR, FxMode.CONVERT), listOf(a, b), basis()).orFail()
        val rate = apis.fx.userRate(TODAY, SEK, EUR, "0.08765").orFail()
        val id = apis.ledger.saveExpense(
            ExpenseDraft(
                listOf(SplitInput(a, 60_000), SplitInput(b, 40_000)), "Furniture", total = sek(100_000), groupId = flat,
                participants = listOf(SplitInput(me), SplitInput(a), SplitInput(b)), occurredOn = TODAY,
                fx = FxChoice.Rate(rate), payerSplit = PayerSplit.EXACT,
            ),
            SaveMode.Create(basis()),
        ).orFail()

        // 1 000 SEK at 0.08765 is €87.65; A's 600 SEK and B's 400 SEK are the weights (AC 26).
        val before = apis.ledger.observeEntry(id).first()!!
        before.item.effect shouldBe eur(8_765)
        before.item.payers.map { Triple(it.person.id, it.paid, it.inputValue) } shouldBe listOf(
            Triple(a, eur(5_259), 60_000L),
            Triple(b, eur(3_506), 40_000L),
        )
        before.shares.sumOf { it.share.amountMinor } shouldBe 8_765L

        apis.ledger.saveExpense((before.editDraft as ExpenseDraft).copy(note = "Sofa"), SaveMode.Update(id, before.token)).orFail()

        val after = apis.ledger.observeEntry(id).first()!!
        after.item.note shouldBe "Sofa"
        after.item.payers shouldBe before.item.payers
        after.shares shouldBe before.shares
        after.history.first().changes.filter { it.field.startsWith("paid") || it.field.startsWith("share") } shouldBe emptyList()
    }
}
