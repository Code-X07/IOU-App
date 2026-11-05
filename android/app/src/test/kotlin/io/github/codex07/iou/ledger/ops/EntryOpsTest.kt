package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.USD
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.github.codex07.iou.testutil.usd
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class EntryOpsTest {

    private fun quote(base: CurrencyCode, quote: CurrencyCode, rate: String) =
        FxChoice.Rate(FxQuote(base, quote, rate, D0, FxSource.ECB_FRANKFURTER, isStale = false))

    private fun ok(result: LedgerResult<EntryId>): EntryId = (result as LedgerResult.Ok).value

    @Test
    fun `the same record twice is a duplicate participant, while records of one merged person combine`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alex2 = ledger.person("Alex2", mergedInto = alex)
        val h = OpsHarness(ledger.state)
        fun draft(vararg people: SplitInput, type: SplitType = SplitType.EQUAL) =
            ExpenseDraft(ME, "Dinner", total = eur(900), splitType = type, participants = people.toList(), occurredOn = D0)

        for (twice in listOf(draft(SplitInput(ME), SplitInput(alex), SplitInput(alex)),
            draft(SplitInput(ME, 1), SplitInput(alex, 1), SplitInput(alex, 1), type = SplitType.SHARES))) {
            h.run { EntryOps.saveExpense(it, twice, SaveMode.Create(h.generation)) } shouldBe
                LedgerResult.Err(LedgerError.DuplicateParticipant(alex))
            EntryOps.previewShares(h.ctx(), twice, null) shouldBe LedgerResult.Err(LedgerError.DuplicateParticipant(alex))
        }

        val merged = draft(SplitInput(ME), SplitInput(alex), SplitInput(alex2))
        val id = ok(h.run { EntryOps.saveExpense(it, merged, SaveMode.Create(h.generation)) })
        h.state.expenses.getValue(id).shares.map { it.personId to it.shareMinor } shouldBe listOf(ME to 450L, alex to 450L)
    }

    @Test
    fun `outside groups an entry needs me and exactly one other person`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val h = OpsHarness(ledger.state)

        h.run { EntryOps.saveTransfer(it, TransferDraft(x, y, eur(100), occurredOn = D0), SaveMode.Create(h.generation)) } shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(x, y)))
        val threeWay = ExpenseDraft(ME, "Pizza", total = eur(300), participants = listOf(SplitInput(ME), SplitInput(x), SplitInput(y)), occurredOn = D0)
        h.run { EntryOps.saveExpense(it, threeWay, SaveMode.Create(h.generation)) } shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(ME, x, y)))
        h.run { EntryOps.saveTransfer(it, TransferDraft(ME, ME, eur(100), occurredOn = D0), SaveMode.Create(h.generation)) } shouldBe
            LedgerResult.Err(LedgerError.SelfTransfer)
    }

    @Test
    fun `split errors come back as they are`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val draft = ExpenseDraft(ME, "Dinner", total = eur(10_000), splitType = SplitType.PERCENT,
            participants = listOf(SplitInput(ME, 5_000), SplitInput(x, 4_000)), occurredOn = D0)

        h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) } shouldBe
            LedgerResult.Err(LedgerError.PercentNotHundred(9_000))
        h.run {
            EntryOps.saveExpense(it, draft.copy(splitType = SplitType.EXACT, participants = listOf(SplitInput(ME, 5_000), SplitInput(x, 4_000))), SaveMode.Create(h.generation))
        } shouldBe LedgerResult.Err(LedgerError.ExactRemaining(1_000))
    }

    @Test
    fun `a SEK expense in a CONVERT EUR group stores a snapshot and EUR shares, in a KEEP group SEK nets`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val convert = ledger.group("Convert", fxMode = FxMode.CONVERT, members = listOf(ME, a, b))
        val keep = ledger.group("Keep", currency = EUR, fxMode = FxMode.KEEP, members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = ExpenseDraft(ME, "Hotel", total = sek(100_000), groupId = convert,
            participants = listOf(SplitInput(ME), SplitInput(a), SplitInput(b)), occurredOn = D0)

        h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) } shouldBe
            LedgerResult.Err(LedgerError.RateRequired(SEK, EUR))

        // Save first: `h.state.expenses.getValue(ok(h.run { ... }))` would read the map before the save.
        val convertedId = ok(h.run { EntryOps.saveExpense(it, draft.copy(fx = quote(SEK, EUR, "0.09")), SaveMode.Create(h.generation)) })
        val converted = h.state.expenses.getValue(convertedId)
        converted.effectTotal shouldBe eur(9_000)
        converted.shares.sumOf { it.shareMinor } shouldBe 9_000L
        val snapshot = h.state.fxSnapshots.getValue(converted.fxSnapshotId!!)
        (snapshot.baseCurrency to snapshot.quoteCurrency) shouldBe (SEK to EUR)

        val keptId = ok(h.run { EntryOps.saveExpense(it, draft.copy(groupId = keep), SaveMode.Create(h.generation)) })
        val kept = h.state.expenses.getValue(keptId)
        kept.effectTotal shouldBe sek(100_000)
        kept.fxSnapshotId shouldBe null
        kept.shares.map { it.shareMinor } shouldBe listOf(33_334L, 33_333L, 33_333L)
        LedgerEngine.compute(h.state).myGroupNet(keep) shouldBe mapOf(SEK to 66_666L)
    }

    @Test
    fun `a cross-currency repayment settles SEK with EUR and keeps or drops its snapshot on edit`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.transfer(ME, x, sek(10_000))
        val h = OpsHarness(ledger.state)
        val draft = TransferDraft(x, ME, eur(900), kind = TransferKind.REPAYMENT, occurredOn = D0,
            settlesCurrency = SEK, fx = quote(EUR, SEK, "11.1111111111"))

        val id = ok(h.run { EntryOps.saveTransfer(it, draft, SaveMode.Create(h.generation)) })
        h.state.transfers.getValue(id).effectAmount shouldBe sek(10_000)
        LedgerEngine.compute(h.state).net(x) shouldBe emptyMap()

        val snapshotId = h.state.transfers.getValue(id).fxSnapshotId
        val edited = draft.copy(note = "thanks", fx = FxChoice.KeepExisting)
        h.run { EntryOps.saveTransfer(it, edited, SaveMode.Update(id, h.token(h.state.transfers.getValue(id)))) }
        h.state.transfers.getValue(id).fxSnapshotId shouldBe snapshotId
        h.state.transfers.getValue(id).effectAmount shouldBe sek(10_000)

        h.run { EntryOps.saveTransfer(it, edited.copy(settlesCurrency = null), SaveMode.Update(id, h.token(h.state.transfers.getValue(id)))) }
        h.state.transfers.getValue(id).fxSnapshotId shouldBe null
        h.state.transfers.getValue(id).effectAmount shouldBe eur(900)
        LedgerEngine.compute(h.state).net(x) shouldBe mapOf(SEK to 10_000L, EUR to -900L)
    }

    @Test
    fun `a bank-actual charge is kept on note and date edits and refused when the paid amount changes`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val draft = TransferDraft(ME, x, sek(100_000), occurredOn = D0, settlesCurrency = EUR, fx = FxChoice.BankActual(eur(8_765)))

        val id = ok(h.run { EntryOps.saveTransfer(it, draft, SaveMode.Create(h.generation)) })
        val snapshot = h.state.fxSnapshots.getValue(h.state.transfers.getValue(id).fxSnapshotId!!)
        (snapshot.source to snapshot.paidMinor to snapshot.chargedMinor) shouldBe (FxSource.BANK_ACTUAL to 100_000L to 8_765L)
        snapshot.rate shouldBe "0.08765"

        fun token() = h.token(h.state.transfers.getValue(id))
        h.run { EntryOps.saveTransfer(it, draft.copy(note = "rent", occurredOn = D0.plusDays(1), fx = FxChoice.KeepExisting), SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Ok(id)
        h.state.transfers.getValue(id).effectAmount shouldBe eur(8_765)

        val doubled = draft.copy(amount = sek(200_000), fx = FxChoice.KeepExisting)
        h.run { EntryOps.saveTransfer(it, doubled, SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Err(LedgerError.FxSnapshotMismatch(id))
        h.run { EntryOps.saveTransfer(it, doubled.copy(fx = FxChoice.BankActual(eur(17_000))), SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Ok(id)
        h.run { EntryOps.saveTransfer(it, doubled.copy(fx = quote(SEK, EUR, "0.09")), SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Ok(id)
        h.state.transfers.getValue(id).effectAmount shouldBe eur(18_000)
    }

    @Test
    fun `changing the paid currency while keeping the old snapshot is refused`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val draft = TransferDraft(ME, x, sek(100_000), occurredOn = D0, settlesCurrency = EUR, fx = quote(SEK, EUR, "0.09"))
        val id = ok(h.run { EntryOps.saveTransfer(it, draft, SaveMode.Create(h.generation)) })

        h.run { EntryOps.saveTransfer(it, draft.copy(amount = usd(10_000), fx = FxChoice.KeepExisting), SaveMode.Update(id, h.token(h.state.transfers.getValue(id)))) } shouldBe
            LedgerResult.Err(LedgerError.FxSnapshotMismatch(id))
    }

    @Test
    fun `moving a converted expense needs a new rate for a new pair and drops the snapshot in a KEEP group`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val eurGroup = ledger.group("EurTrip", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, a))
        val usdGroup = ledger.group("UsdTrip", currency = USD, fxMode = FxMode.CONVERT, members = listOf(ME, a))
        val keepGroup = ledger.group("Keep", currency = EUR, fxMode = FxMode.KEEP, members = listOf(ME, a))
        val h = OpsHarness(ledger.state)
        val draft = ExpenseDraft(ME, "Ferry", total = sek(100_000), groupId = eurGroup,
            participants = listOf(SplitInput(ME), SplitInput(a)), occurredOn = D0, fx = quote(SEK, EUR, "0.09"))
        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        fun token() = h.token(h.state.expenses.getValue(id))

        h.run { EntryOps.saveExpense(it, draft.copy(groupId = usdGroup, fx = FxChoice.KeepExisting), SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Err(LedgerError.FxSnapshotMismatch(id))
        h.run { EntryOps.saveExpense(it, draft.copy(groupId = keepGroup, fx = FxChoice.KeepExisting), SaveMode.Update(id, token())) } shouldBe
            LedgerResult.Ok(id)
        val moved = h.state.expenses.getValue(id)
        moved.fxSnapshotId shouldBe null
        moved.effectTotal shouldBe sek(100_000)
        moved.shares.sumOf { it.shareMinor } shouldBe 100_000L
    }

    @Test
    fun `stored shares survive a merge and a note edit, and are recomputed over canonical people when the total changes`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB")
        val bob = ledger.person("Bob")
        val group = ledger.group("Trip", members = listOf(ME, alexA, alexB, bob))
        val id = ledger.expense(ME, eur(9_000), mapOf(alexA to 3_000L, alexB to 3_000L, bob to 3_000L),
            group = group, splitType = SplitType.EQUAL)
        val merged = ledger.state.with(ledger.state.people.getValue(alexB).copy(mergedIntoId = alexA))
        val h = OpsHarness(merged)

        val stored = h.state.expenses.getValue(id)
        val inputs = EntryOps.splitInputsOf(stored, IdentityResolver(h.state))
        inputs shouldBe listOf(SplitInput(alexA, null), SplitInput(bob, null))
        val editDraft = ExpenseDraft(ME, stored.description, total = eur(9_000), groupId = group,
            splitType = SplitType.EQUAL, participants = inputs, occurredOn = D0)

        h.run { EntryOps.saveExpense(it, editDraft.copy(note = "ferry"), SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) }
        h.state.expenses.getValue(id).shares shouldBe stored.shares
        LedgerEngine.compute(h.state).groupNet(group, alexA) shouldBe mapOf(EUR to -6_000L)

        val preview = (EntryOps.previewShares(h.ctx(), editDraft.copy(total = eur(12_000)), h.state.expenses.getValue(id)) as LedgerResult.Ok).value
        preview.recomputed shouldBe true
        preview.shares.map { it.personId to it.shareMinor } shouldBe listOf(alexA to 6_000L, bob to 6_000L)

        h.run { EntryOps.saveExpense(it, editDraft.copy(total = eur(12_000)), SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) }
        h.state.expenses.getValue(id).shares.map { it.personId to it.shareMinor } shouldBe listOf(alexA to 6_000L, bob to 6_000L)
    }

    @Test
    fun `an entry saved after a merge references the target, even from a draft opened before it`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB")
        val h = OpsHarness(ledger.state)
        val transferDraft = TransferDraft(ME, alexA, eur(1_000), occurredOn = D0)
        val expenseDraft = ExpenseDraft(alexA, "Lunch", total = eur(3_000), splitType = SplitType.EQUAL,
            participants = listOf(SplitInput(alexA, null), SplitInput(ME, null)), occurredOn = D0)
        h.run { PeopleOps.merge(it, alexA, alexB, h.token(h.state.people.getValue(alexA))) }

        val transfer = ok(h.run { EntryOps.saveTransfer(it, transferDraft, SaveMode.Create(h.generation)) })
        val expense = ok(h.run { EntryOps.saveExpense(it, expenseDraft, SaveMode.Create(h.generation)) })
        h.state.transfers.getValue(transfer).toPersonId shouldBe alexB
        h.state.expenses.getValue(expense).payerPersonId shouldBe alexB

        // Unmerging leaves both new entries with alexB: +10.00 from the transfer, −15.00 from my share of lunch.
        h.run { PeopleOps.unmerge(it, alexA, h.token(h.state.people.getValue(alexA))) }
        LedgerEngine.compute(h.state).net(alexA) shouldBe emptyMap()
        LedgerEngine.compute(h.state).net(alexB) shouldBe mapOf(EUR to -500L)
    }

    @Test
    fun `an edit keeps the stored record of a merged person`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB")
        val id = ledger.transfer(ME, alexA, eur(1_000))
        val h = OpsHarness(ledger.state)
        h.run { PeopleOps.merge(it, alexA, alexB, h.token(h.state.people.getValue(alexA))) }

        // The edit form shows the canonical person (alexB); saving a note must not move the entry to alexB's record.
        val edit = TransferDraft(ME, alexB, eur(1_000), occurredOn = h.state.transfers.getValue(id).occurredOn, note = "lunch")
        h.run { EntryOps.saveTransfer(it, edit, SaveMode.Update(id, h.token(h.state.transfers.getValue(id)))) }
        h.state.transfers.getValue(id).toPersonId shouldBe alexA
    }

    @Test
    fun `delete removes the effect at once, restore brings it back unless a reference is gone`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val gone = ledger.person("Gone", deleted = true)
        val t = ledger.transfer(ME, x, eur(1_000))
        val orphan = ledger.transfer(ME, gone, eur(1_000), deleted = true)
        val h = OpsHarness(ledger.state)

        h.run { EntryOps.deleteEntry(it, t, h.token(h.state.transfers.getValue(t))) } shouldBe LedgerResult.Ok(Unit)
        LedgerEngine.compute(h.state).net(x) shouldBe emptyMap()
        h.run { EntryOps.restoreEntry(it, t, h.token(h.state.transfers.getValue(t))) } shouldBe LedgerResult.Ok(Unit)
        LedgerEngine.compute(h.state).net(x) shouldBe mapOf(EUR to 1_000L)

        val result = h.run { EntryOps.restoreEntry(it, orphan, h.token(h.state.transfers.getValue(orphan))) }
        result shouldNotBe LedgerResult.Ok(Unit)
        (result as LedgerResult.Err).error shouldBe LedgerError.DeletedReference(setOf(orphan.value, gone.value))
    }

    @Test
    fun `huge split inputs on two records of one person are AmountTooLarge, and a negative cannot hide behind a positive one`() {
        val ledger = TestLedger().apply { me() }
        val alias = ledger.person("Alias")
        val target = ledger.person("Target")
        val h = OpsHarness(ledger.state.with(ledger.state.people.getValue(alias).copy(mergedIntoId = target)))
        val draft = ExpenseDraft(ME, "Dinner", total = eur(10_000), splitType = SplitType.SHARES,
            participants = listOf(SplitInput(alias, Long.MAX_VALUE / 2 + 1), SplitInput(target, Long.MAX_VALUE / 2 + 1)), occurredOn = D0)

        h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) } shouldBe LedgerResult.Err(LedgerError.AmountTooLarge)
        h.run {
            EntryOps.saveExpense(it, draft.copy(participants = listOf(SplitInput(alias, -5), SplitInput(target, 10))), SaveMode.Create(h.generation))
        } shouldBe LedgerResult.Err(LedgerError.NegativeInput(alias))
    }

    @Test
    fun `a bank-actual quote is not a rate`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val group = ledger.group("Convert", fxMode = FxMode.CONVERT, members = listOf(ME, a))
        val h = OpsHarness(ledger.state)
        val draft = ExpenseDraft(ME, "Hotel", total = sek(100_000), groupId = group,
            participants = listOf(SplitInput(ME), SplitInput(a)), occurredOn = D0,
            fx = FxChoice.Rate(FxQuote(SEK, EUR, "0.09", D0, FxSource.BANK_ACTUAL, isStale = false)))

        h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) } shouldBe LedgerResult.Err(LedgerError.InvalidRate("0.09"))
    }

    @Test
    fun `an update that changes only the participants replaces the stored shares`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val group = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = ExpenseDraft(ME, "Dinner", total = eur(9_000), groupId = group, splitType = SplitType.EQUAL,
            participants = listOf(SplitInput(ME), SplitInput(a)), occurredOn = D0)
        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        val before = h.state.expenses.getValue(id).shares

        h.run {
            EntryOps.saveExpense(it, draft.copy(participants = listOf(SplitInput(ME), SplitInput(a), SplitInput(b))),
                SaveMode.Update(id, h.token(h.state.expenses.getValue(id))))
        } shouldBe LedgerResult.Ok(id)
        val after = h.state.expenses.getValue(id).shares
        after shouldNotBe before
        after.map { it.personId }.toSet() shouldBe setOf(ME, a, b)
        after.sumOf { it.shareMinor } shouldBe 9_000L
    }
}
