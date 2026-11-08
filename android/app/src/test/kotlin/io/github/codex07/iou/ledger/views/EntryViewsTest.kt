package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryKind
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.RevertOps
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class EntryViewsTest {

    private fun OpsHarness.views() = LedgerViews(state, revisions, generation, EUR) { if (it == EUR) "1" else null }

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    @Test
    fun `activity is newest first, filtered, and shows my balance effect`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val h = OpsHarness(ledger.state)
        val old = h.run { EntryOps.saveTransfer(it, TransferDraft(ME, x, eur(1_000), occurredOn = D0), SaveMode.Create(h.generation)) }.ok()
        val new = h.run { EntryOps.saveTransfer(it, TransferDraft(y, ME, eur(300), occurredOn = D0.plusDays(1)), SaveMode.Create(h.generation)) }.ok()

        h.views().activity(ActivityFilter()).map { it.entryId } shouldBe listOf(new, old)
        h.views().activity(ActivityFilter(personId = x)).map { it.entryId } shouldBe listOf(old)
        h.views().activity(ActivityFilter(kinds = setOf(EntryKind.EXPENSE))) shouldBe emptyList()
        h.views().activity(ActivityFilter()).associate { it.entryId to it.myBalanceEffect } shouldBe
            mapOf(new to eur(-300), old to eur(1_000))
    }

    @Test
    fun `the edit draft of a converted repayment keeps its settlement currency and snapshot`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val quote = FxQuote(EUR, SEK, "11.1111111111", D0, FxSource.USER_ENTERED, false)
        val id = h.run {
            EntryOps.saveTransfer(it, TransferDraft(x, ME, eur(900), TransferKind.REPAYMENT, occurredOn = D0, settlesCurrency = SEK, fx = FxChoice.Rate(quote)), SaveMode.Create(h.generation))
        }.ok()

        val detail = h.views().entry(id)!!
        val draft = detail.editDraft as TransferDraft
        draft.settlesCurrency shouldBe SEK
        draft.fx shouldBe FxChoice.KeepExisting
        detail.fx!!.rate shouldBe "11.1111111111"
        detail.item.effect shouldBe sek(10_000)
        detail.token.version shouldBe 1
    }

    @Test
    fun `history diffs every revision against the previous one, newest first`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.transfer(ME, x, eur(4_000))
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as TransferDraft
        h.run { EntryOps.saveTransfer(it, draft.copy(amount = eur(4_500)), SaveMode.Update(id, h.views().entry(id)!!.token)) }

        val history = h.views().entry(id)!!.history
        history.map { it.version } shouldBe listOf(2, 1)
        history.first().action shouldBe RevisionAction.UPDATED
        history.first().changes shouldBe listOf(
            FieldChange("amount", "40.00 EUR", "45.00 EUR"),
            FieldChange("effect", "40.00 EUR", "45.00 EUR"),
        )
        val ref = EntityRef(EntityType.TRANSFER, id.value)
        h.views().draftFromVersion(ref, 1).ok().let { ((it as VersionDraft.OfEntry).draft as TransferDraft).amount } shouldBe eur(4_000)
        h.views().draftFromVersion(ref, 9) shouldBe LedgerResult.Err(LedgerError.NotFound("${id.value} v9"))
    }

    @Test
    fun `a merge preview lists entries between the two records as neutralized`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alexS))
        val between = ledger.transfer(alex, alexS, eur(700), group = trip)
        ledger.transfer(ME, alex, eur(1_000))
        val h = OpsHarness(ledger.state)

        h.views().mergePreview(alexS, alex).ok().neutralizedEntries.map { it.entryId } shouldBe listOf(between)
    }

    @Test
    fun `person and group history include aliases, handles and memberships`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val trip = ledger.group("Trip", members = listOf(ME, alexS))
        val h = OpsHarness(ledger.state)
        val iban = HandleDraft(HandleType.IBAN, "GB82WEST12345698765432", label = "UK", isDefault = true)
        h.run { PeopleOps.addHandle(it, alexS, iban, h.generation) }
        h.run { PeopleOps.merge(it, alexS, alex, h.token(h.state.people.getValue(alexS))) }

        // Observing the alias shows the canonical person, whose history holds the alias's merge revision.
        val history = h.views().person(alexS)!!.history
        history.first().let { it.entity to it.reason } shouldBe (EntityRef(EntityType.PERSON, alexS.value) to "Merged into Alex")
        history.map { it.entity.type }.toSet() shouldBe setOf(EntityType.PERSON, EntityType.PAYMENT_HANDLE)
        history.single { it.entity.type == EntityType.PAYMENT_HANDLE }.subject shouldBe "IBAN · UK"
        h.views().group(trip)!!.history.map { it.entity.type }.toSet() shouldBe setOf(EntityType.GROUP, EntityType.GROUP_MEMBER)
    }

    @Test
    fun `split preview shows stored and new shares`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), splitType = SplitType.EQUAL)
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as ExpenseDraft

        val same = h.views().splitPreview(draft, id, h.now).ok()
        same.recomputed shouldBe false
        same.storedShares!!.map { it.share } shouldBe listOf(eur(500), eur(500))

        val bigger = h.views().splitPreview(draft.copy(total = eur(1_001)), id, h.now).ok()
        bigger.recomputed shouldBe true
        bigger.shares.map { it.share }.sumOf { it.amountMinor } shouldBe 1_001L
    }

    @Test
    fun `an unchanged split preview after a merge shows each person once, as the stored shares do`() {
        for (type in listOf(SplitType.EQUAL, SplitType.SHARES)) {
            val ledger = TestLedger().apply { me() }
            val alex = ledger.person("Alex")
            val alex2 = ledger.person("Alex2", mergedInto = alex)
            val inputs: Map<PersonId, Long?> = if (type == SplitType.EQUAL) emptyMap() else mapOf(ME to 1L, alex to 1L, alex2 to 1L)
            val id = ledger.expense(ME, eur(3), mapOf(ME to 1L, alex to 1L, alex2 to 1L), splitType = type, inputs = inputs)
            val h = OpsHarness(ledger.state)
            val draft = h.views().entry(id)!!.editDraft as ExpenseDraft

            val preview = h.views().splitPreview(draft.copy(note = "only the note"), id, h.now).ok()
            preview.recomputed shouldBe false
            preview.shares shouldBe preview.storedShares
            preview.shares.map { it.person.displayName to it.share } shouldBe listOf("Me" to eur(1), "Alex" to eur(2))
            h.state.expenses.getValue(id).shares.size shouldBe 3
        }
    }

    @Test
    fun `each history row carries its own row's current token, so memberships and deleted handles can be reverted`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val h = OpsHarness(ledger.state)
        val trip = h.run { GroupOps.createGroup(it, GroupDraft("Trip", EUR, FxMode.CONVERT), listOf(a), h.generation) }.ok()
        h.run { GroupOps.removeMember(it, trip, a, h.generation) }.ok()
        val iban = HandleDraft(HandleType.IBAN, "GB82WEST12345698765432", label = "UK")
        val handle = h.run { PeopleOps.addHandle(it, a, iban, h.generation) }.ok()
        h.run { PeopleOps.deleteHandle(it, handle, h.token(h.state.handles.getValue(handle))) }.ok()

        val group = h.views().group(trip)!!
        val membership = group.history.first { it.entity.type == EntityType.GROUP_MEMBER && it.subject == "A in Trip" }
        val member = h.state.members.values.single { it.groupId == trip && it.personId == a }
        membership.token shouldBe h.token(member)
        membership.token shouldNotBe group.token
        group.history.filter { it.entity.type == EntityType.GROUP }.forEach { it.token shouldBe group.token }
        h.run { RevertOps.revertTo(it, membership.entity, 1, membership.token!!, h.revisions) }.ok()
        h.state.members.getValue(member.id).removedAt shouldBe null

        val deleted = h.views().person(a)!!.history.first { it.entity.type == EntityType.PAYMENT_HANDLE }
        deleted.token shouldBe h.token(h.state.handles.getValue(handle))
        h.run { RevertOps.revertTo(it, deleted.entity, 1, deleted.token!!, h.revisions) }.ok()
    }

    @Test
    fun `settle-up drafts carry the generation and the currency being settled`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val keep = ledger.group("Flat", currency = EUR, fxMode = FxMode.KEEP, members = listOf(ME, a))
        ledger.expense(a, sek(2_000), mapOf(a to 1_000L, ME to 1_000L), group = keep)
        ledger.transfer(ME, a, eur(700))
        val h = OpsHarness(ledger.state)
        val views = h.views()

        val item = views.group(keep)!!.settlePlan.single()
        val fromPlan = views.settleUpFromPlan(item, D0).ok()
        fromPlan.basis shouldBe h.generation
        fromPlan.draft.from shouldBe ME
        fromPlan.draft.amount shouldBe sek(1_000)
        fromPlan.draft.settlesCurrency shouldBe SEK
        fromPlan.draft.kind shouldBe TransferKind.REPAYMENT
        views.settleUpFromPlan(item.copy(generation = LedgerGeneration("old")), D0) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)

        val forPerson = views.settleUpWithPerson(a, h.generation, D0).ok().single()
        forPerson.draft.from shouldBe a
        forPerson.draft.amount shouldBe eur(700)
        forPerson.draft.settlesCurrency shouldBe EUR
        views.balanceSummaryText(a) shouldBe LedgerResult.Ok("A owes you 7.00 EUR")
    }

    @Test
    fun `expense edit drafts list canonical participants once`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alex2 = ledger.person("Alex2", mergedInto = alex)
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alex2, bob))
        val id = ledger.expense(ME, eur(900), mapOf(alex to 300L, alex2 to 300L, bob to 300L), group = trip, splitType = SplitType.EQUAL)
        val h = OpsHarness(ledger.state)

        (h.views().entry(id)!!.editDraft as ExpenseDraft).participants shouldBe listOf(SplitInput(alex), SplitInput(bob))
        h.views().entry(id)!!.shares.map { it.person.displayName to it.share } shouldBe listOf("Alex" to eur(600), "Bob" to eur(300))
    }

    @Test
    fun `expense read models show each canonical payer once with what they paid`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alex2 = ledger.person("Alex2", mergedInto = alex)
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alex2, bob))
        val id = ledger.expense(
            alex, eur(9_000), mapOf(ME to 3_000L, alex to 3_000L, bob to 3_000L), group = trip, splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(alex, 3_000, 3_000), ExpensePayer(bob, 4_000, 4_000), ExpensePayer(alex2, 2_000, 2_000)),
            payerSplit = PayerSplit.EXACT,
        )
        val loan = ledger.transfer(ME, bob, eur(500))
        val h = OpsHarness(ledger.state)

        val detail = h.views().entry(id)!!
        detail.item.paidBy.displayName shouldBe "Alex"
        detail.item.payers.map { Triple(it.person.displayName, it.paid, it.inputValue) } shouldBe listOf(
            Triple("Alex", eur(5_000), 5_000L),
            Triple("Bob", eur(4_000), 4_000L),
        )
        val draft = detail.editDraft as ExpenseDraft
        draft.payerSplit shouldBe PayerSplit.EXACT
        draft.payers shouldBe listOf(SplitInput(alex, 5_000), SplitInput(bob, 4_000))
        h.views().entry(loan)!!.item.payers shouldBe emptyList()
    }

    @Test
    fun `split preview shows stored and new payers`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.expense(
            ME, eur(1_000), mapOf(ME to 500L, x to 500L), splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(ME, 300, 300), ExpensePayer(x, 700, 700)), payerSplit = PayerSplit.EXACT,
        )
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as ExpenseDraft

        val same = h.views().splitPreview(draft, id, h.now).ok()
        same.payersRecomputed shouldBe false
        same.payers shouldBe same.storedPayers
        same.payers.map { it.paid } shouldBe listOf(eur(300), eur(700))

        val equal = h.views().splitPreview(
            draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(ME), SplitInput(x))), id, h.now,
        ).ok()
        equal.payersRecomputed shouldBe true
        equal.recomputed shouldBe false
        equal.payers.map { it.paid to it.inputValue } shouldBe listOf(eur(500) to null, eur(500) to null)
        equal.storedPayers!!.map { it.paid } shouldBe listOf(eur(300), eur(700))

        h.views().splitPreview(draft.copy(payers = listOf(SplitInput(ME, 300), SplitInput(x, 600))), id, h.now) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(100))
    }

    @Test
    fun `old versions open as drafts with the payers they had`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val single = ExpenseDraft(ME, "Lunch", total = eur(1_000), participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = D0)
        val id = h.run { EntryOps.saveExpense(it, single, SaveMode.Create(h.generation)) }.ok()
        val both = single.copy(payers = listOf(SplitInput(ME, 300), SplitInput(x, 700)), payerSplit = PayerSplit.EXACT)
        h.run { EntryOps.saveExpense(it, both, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) }.ok()

        val ref = EntityRef(EntityType.EXPENSE, id.value)
        h.views().draftFromVersion(ref, 1).ok() shouldBe VersionDraft.OfEntry(single)
        h.views().draftFromVersion(ref, 2).ok() shouldBe VersionDraft.OfEntry(both)
    }
}
