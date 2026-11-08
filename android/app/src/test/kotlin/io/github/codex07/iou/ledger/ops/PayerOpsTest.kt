package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Payers through the write path (spec §4.4): resolved from the draft, kept as stored until an input of the payers changes. */
class PayerOpsTest {

    private fun ok(result: LedgerResult<EntryId>): EntryId = (result as LedgerResult.Ok).value

    /** A €100 dinner in a group of me, A and B: A paid €60 and B €40 (EXACT), shared EQUAL by [participants]. */
    private fun dinner(trip: GroupId, a: PersonId, b: PersonId, participants: List<SplitInput>) =
        ExpenseDraft(
            listOf(SplitInput(a, 6_000), SplitInput(b, 4_000)), "Dinner", total = eur(10_000), groupId = trip,
            participants = participants, occurredOn = D0, payerSplit = PayerSplit.EXACT,
        )

    @Test
    fun `a draft's payers are stored as given for EXACT and divided for EQUAL`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(ME), SplitInput(a), SplitInput(b)))

        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        val saved = h.state.expenses.getValue(id)
        saved.payerSplit shouldBe PayerSplit.EXACT
        saved.payers shouldBe listOf(ExpensePayer(a, 6_000, 6_000), ExpensePayer(b, 4_000, 4_000))
        // ME has the lowest ID, so ME's share takes the extra cent: 33.34, 33.33, 33.33.
        val balances = LedgerEngine.compute(h.state)
        balances.groupNet(trip, ME) shouldBe mapOf(EUR to -3_334L)
        balances.groupNet(trip, a) shouldBe mapOf(EUR to 2_667L)
        balances.groupNet(trip, b) shouldBe mapOf(EUR to 667L)

        val equal = draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(a), SplitInput(b)))
        val second = ok(h.run { EntryOps.saveExpense(it, equal, SaveMode.Create(h.generation)) })
        h.state.expenses.getValue(second).payers shouldBe listOf(ExpensePayer(a, 5_000), ExpensePayer(b, 5_000))
    }

    @Test
    fun `payer errors name the payer side, and the rules see every payer`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val x = ledger.person("X")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(a), SplitInput(b)))
        fun save(
            payers: List<SplitInput>,
            split: PayerSplit = PayerSplit.EXACT,
            group: GroupId? = trip,
            participants: List<SplitInput> = draft.participants,
        ) = h.run {
            EntryOps.saveExpense(
                it, draft.copy(payers = payers, payerSplit = split, groupId = group, participants = participants), SaveMode.Create(h.generation),
            )
        }

        save(listOf(SplitInput(a, 6_000), SplitInput(b, 3_000))) shouldBe LedgerResult.Err(LedgerError.PaidRemaining(1_000))
        save(listOf(SplitInput(a, 10_000), SplitInput(b, 0))) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
        save(listOf(SplitInput(a, 11_000), SplitInput(b, -1_000))) shouldBe LedgerResult.Err(LedgerError.NegativeInput(b))
        save(listOf(SplitInput(a, 6_000), SplitInput(b))) shouldBe LedgerResult.Err(LedgerError.MissingInput(b))
        save(emptyList()) shouldBe LedgerResult.Err(LedgerError.NoPayers)
        save(listOf(SplitInput(a), SplitInput(a)), PayerSplit.EQUAL) shouldBe LedgerResult.Err(LedgerError.DuplicatePayer(a))
        save(listOf(SplitInput(a), SplitInput(x)), PayerSplit.EQUAL) shouldBe
            LedgerResult.Err(LedgerError.NotGroupMember(trip, setOf(x)))
        // Outside groups: me and exactly one other person across payers and shares (rule 1).
        save(listOf(SplitInput(ME), SplitInput(x)), PayerSplit.EQUAL, group = null, participants = listOf(SplitInput(ME), SplitInput(a))) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(ME, a, x)))
    }

    @Test
    fun `stored payers are kept until an input of the payers changes, independently of the shares`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(a), SplitInput(b)))
        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        fun stored() = h.state.expenses.getValue(id)
        fun edit(changed: ExpenseDraft) = h.run { EntryOps.saveExpense(it, changed, SaveMode.Update(id, h.token(stored()))) }
        fun preview(changed: ExpenseDraft) = (EntryOps.previewShares(h.ctx(), changed, stored()) as LedgerResult.Ok).value
        val payers = stored().payers

        preview(draft.copy(note = "ferry")).payersRecomputed shouldBe false
        edit(draft.copy(note = "ferry")) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe payers

        // New participants recompute the shares only.
        val withMe = draft.copy(participants = listOf(SplitInput(ME), SplitInput(a), SplitInput(b)))
        preview(withMe).let { it.recomputed to it.payersRecomputed } shouldBe (true to false)
        edit(withMe) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe payers
        stored().shares.size shouldBe 3

        // New payer amounts recompute the payers only.
        val shares = stored().shares
        val swapped = withMe.copy(payers = listOf(SplitInput(a, 4_000), SplitInput(b, 6_000)))
        preview(swapped).let { it.recomputed to it.payersRecomputed } shouldBe (false to true)
        edit(swapped) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe listOf(ExpensePayer(a, 4_000, 4_000), ExpensePayer(b, 6_000, 6_000))
        stored().shares shouldBe shares

        // A new total recomputes both.
        val bigger = swapped.copy(total = eur(12_000), payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(a), SplitInput(b)))
        edit(bigger) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe listOf(ExpensePayer(a, 6_000), ExpensePayer(b, 6_000))
        stored().shares.sumOf { it.shareMinor } shouldBe 12_000L
        // EXACT amounts that no longer add up to the new total are refused.
        edit(swapped.copy(total = eur(13_000))) shouldBe LedgerResult.Err(LedgerError.PaidRemaining(3_000))
    }

    @Test
    fun `merged payer records stay as stored until the payers change, then combine on the canonical person`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB")
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alexA, alexB, bob))
        val id = ledger.expense(
            alexA, eur(9_000), mapOf(ME to 3_000L, alexA to 3_000L, bob to 3_000L), group = trip, splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(alexA, 4_000, 4_000), ExpensePayer(alexB, 2_000, 2_000), ExpensePayer(bob, 3_000, 3_000)),
            payerSplit = PayerSplit.EXACT,
        )
        val h = OpsHarness(ledger.state.with(ledger.state.people.getValue(alexB).copy(mergedIntoId = alexA)))
        val stored = h.state.expenses.getValue(id)
        val resolver = IdentityResolver(h.state)

        val payerInputs = EntryOps.payerInputsOf(stored, resolver)
        payerInputs shouldBe listOf(SplitInput(alexA, 6_000), SplitInput(bob, 3_000))
        val draft = ExpenseDraft(
            payerInputs, stored.description, total = eur(9_000), groupId = trip, splitType = SplitType.EQUAL,
            participants = EntryOps.splitInputsOf(stored, resolver), occurredOn = D0, payerSplit = PayerSplit.EXACT,
        )

        h.run { EntryOps.saveExpense(it, draft.copy(note = "ferry"), SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe
            LedgerResult.Ok(id)
        h.state.expenses.getValue(id).payers shouldBe stored.payers

        val changed = draft.copy(payers = listOf(SplitInput(alexA, 5_000), SplitInput(bob, 4_000)))
        h.run { EntryOps.saveExpense(it, changed, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe
            LedgerResult.Ok(id)
        h.state.expenses.getValue(id).payers shouldBe listOf(ExpensePayer(alexA, 5_000, 5_000), ExpensePayer(bob, 4_000, 4_000))
    }

    @Test
    fun `reverting a two-payer expense to its single-payer version gives one payer again`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val trip = ledger.group("Trip", members = listOf(ME, a))
        val h = OpsHarness(ledger.state)
        val single = ExpenseDraft(ME, "Dinner", total = eur(1_000), groupId = trip, participants = listOf(SplitInput(ME), SplitInput(a)), occurredOn = D0)
        val id = ok(h.run { EntryOps.saveExpense(it, single, SaveMode.Create(h.generation)) })
        val both = single.copy(payers = listOf(SplitInput(ME, 400), SplitInput(a, 600)), payerSplit = PayerSplit.EXACT)
        h.run { EntryOps.saveExpense(it, both, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe LedgerResult.Ok(id)
        LedgerEngine.compute(h.state).groupNet(trip, a) shouldBe mapOf(EUR to 100L)

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.EXPENSE, id.value), 1, h.token(h.state.expenses.getValue(id)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)

        val reverted = h.state.expenses.getValue(id)
        reverted.payerSplit shouldBe PayerSplit.EQUAL
        reverted.payers shouldBe listOf(ExpensePayer(ME, 1_000))
        LedgerEngine.compute(h.state).groupNet(trip, a) shouldBe mapOf(EUR to -500L)
    }
}
