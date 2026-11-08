package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Saving, deleting and restoring schedules (spec §5.7). */
class RecurringOpsTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    @Test
    fun `a new schedule stores its template rows on canonical people, records of one person combined`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB", mergedInto = alexA)
        val bob = ledger.person("Bob")
        val flat = ledger.group("Flat", members = listOf(ME, alexA, alexB, bob))
        val h = OpsHarness(ledger.state)
        h.today = d("2026-02-10")
        val draft = RecurringDraft(
            payers = listOf(SplitInput(alexB)), description = " Rent ", total = eur(90_000), groupId = flat,
            splitType = SplitType.SHARES,
            participants = listOf(SplitInput(ME, 1), SplitInput(alexA, 1), SplitInput(alexB, 1), SplitInput(bob, 1)),
            unit = RecurrenceUnit.MONTH, startOn = d("2026-03-01"),
        )

        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()

        val schedule = h.state.schedules.getValue(id)
        schedule.description shouldBe "Rent"
        schedule.participants shouldBe listOf(
            RecurringParticipant(ParticipantRole.PAYER, alexA),
            RecurringParticipant(ParticipantRole.SHARE, ME, 1),
            RecurringParticipant(ParticipantRole.SHARE, alexA, 2),
            RecurringParticipant(ParticipantRole.SHARE, bob, 1),
        )
        schedule.handledThrough shouldBe null
        schedule.autoPostFrom shouldBe d("2026-02-10")
        h.revisions.last().action shouldBe RevisionAction.CREATED
    }

    @Test
    fun `an update keeps handledThrough, and keeps stored rows that still combine to the draft's`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB", mergedInto = alexA)
        val bob = ledger.person("Bob")
        val flat = ledger.group("Flat", members = listOf(ME, alexA, alexB, bob))
        val id = ledger.schedule(alexB, eur(90_000), listOf(ME, alexB), group = flat, handledThrough = d("2026-03-01"))
        val h = OpsHarness(ledger.state)
        val stored = h.state.schedules.getValue(id)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(alexA)), description = "Rent", total = eur(95_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(alexA)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        fun save(changed: RecurringDraft) =
            h.run { RecurringOps.save(it, changed, ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id)))) }

        save(draft) shouldBe LedgerResult.Ok(id)
        val saved = h.state.schedules.getValue(id)
        saved.participants shouldBe stored.participants
        saved.total shouldBe eur(95_000)
        saved.handledThrough shouldBe d("2026-03-01")
        saved.meta.version shouldBe 2

        save(draft.copy(payers = listOf(SplitInput(bob)))) shouldBe LedgerResult.Ok(id)
        h.state.schedules.getValue(id).payerRows shouldBe listOf(RecurringParticipant(ParticipantRole.PAYER, bob))
    }

    @Test
    fun `template errors name their side, and the rules apply to schedules`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        fun create(changed: RecurringDraft) = h.run { RecurringOps.save(it, changed, ScheduleSaveMode.Create(h.generation)) }

        create(draft.copy(total = eur(0))) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
        create(draft.copy(interval = 0)) shouldBe LedgerResult.Err(LedgerError.InvalidField("interval"))
        create(draft.copy(endOn = D0.minusDays(1))) shouldBe LedgerResult.Err(LedgerError.InvalidField("endOn"))
        create(draft.copy(participants = listOf(SplitInput(ME), SplitInput(ME)))) shouldBe
            LedgerResult.Err(LedgerError.DuplicateParticipant(ME))
        create(draft.copy(payers = listOf(SplitInput(x), SplitInput(x)))) shouldBe LedgerResult.Err(LedgerError.DuplicatePayer(x))
        create(draft.copy(payerSplit = PayerSplit.EXACT, payers = listOf(SplitInput(ME, 50_000), SplitInput(x, 30_000)))) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(10_000))
        create(draft.copy(splitType = SplitType.EXACT, participants = listOf(SplitInput(ME, -1), SplitInput(x, 90_001)))) shouldBe
            LedgerResult.Err(LedgerError.NegativeInput(ME))
        create(draft.copy(participants = listOf(SplitInput(ME), SplitInput(y)))) shouldBe
            LedgerResult.Err(LedgerError.NotGroupMember(flat, setOf(y)))
        create(draft.copy(groupId = null, participants = listOf(SplitInput(ME), SplitInput(x), SplitInput(y)))) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(ME, x, y)))
        h.state.schedules shouldBe emptyMap()
    }

    @Test
    fun `a schedule is deleted and restored with one revision each, and repeats change nothing`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val h = OpsHarness(ledger.state)
        fun token() = h.token(h.state.schedules.getValue(id))

        h.run { RecurringOps.delete(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(id).isDeleted shouldBe true
        h.revisions.last().action shouldBe RevisionAction.DELETED
        val count = h.revisions.size
        h.run { RecurringOps.delete(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.revisions.size shouldBe count

        h.run { RecurringOps.restore(it, id, token()) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(id).isDeleted shouldBe false
        h.revisions.last().action shouldBe RevisionAction.RESTORED
        h.run { RecurringOps.delete(it, id, h.token(h.state.schedules.getValue(id)).copy(version = 1)) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(id.value, 1, 3))
    }
}
