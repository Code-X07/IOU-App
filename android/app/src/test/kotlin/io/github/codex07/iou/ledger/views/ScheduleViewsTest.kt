package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonRef
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Schedule lists, details, the due list and occurrence drafts (spec §5.7, §6.2). */
class ScheduleViewsTest {

    private fun OpsHarness.views() = LedgerViews(state, revisions, generation, EUR) { if (it == EUR) "1" else null }

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `schedules show their next date, due count and holds, and held ones list apart`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val bea = ledger.person("Bea", archived = true)
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-03-01"))
        val held = ledger.schedule(ME, eur(1_000), listOf(ME, bea), startOn = d("2026-03-01"))
        ledger.schedule(ME, eur(1_000), listOf(ME, x), deleted = true)
        val views = OpsHarness(ledger.state).views()
        val today = d("2026-04-15")

        views.schedules(includeDeleted = false, onHold = null, today = today).map { it.id } shouldBe listOf(rent, held)
        val active = views.schedules(includeDeleted = false, onHold = false, today = today).single()
        active.id shouldBe rent
        active.next shouldBe d("2026-03-01")
        active.dueCount shouldBe 2
        val onHold = views.schedules(includeDeleted = false, onHold = true, today = today).single()
        onHold.id shouldBe held
        onHold.dueCount shouldBe 0
        onHold.holds shouldBe listOf(HoldReason.PersonArchived(PersonRef(bea, "Bea")))
        views.schedules(includeDeleted = true, onHold = null, today = today).size shouldBe 3
    }

    @Test
    fun `the due list shows every due date oldest first and marks a backlog that blocks auto-post`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val auto = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-03-01"), autoPost = true, autoPostFrom = d("2026-04-15"))
        val confirm = ledger.schedule(ME, eur(5_000), listOf(ME, x), startOn = d("2026-04-10"))
        ledger.schedule(ME, eur(5_000), listOf(ME, x), startOn = d("2026-04-01"), deleted = true)

        val due = OpsHarness(ledger.state).views().dueOccurrences(d("2026-04-20"))

        due.map { Triple(it.schedule, it.date, it.confirmToResume) } shouldBe listOf(
            Triple(auto, d("2026-03-01"), true),
            Triple(auto, d("2026-04-01"), true),
            Triple(confirm, d("2026-04-10"), false),
        )
        due.first().amount shouldBe eur(90_000)
        due.first().autoPostError shouldBe null
    }

    @Test
    fun `the detail has the edit draft, posted expenses and history, and the occurrence draft is for the next date`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val trip = ledger.group("Trip", members = listOf(ME, alex))
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = trip,
            participants = listOf(SplitInput(ME), SplitInput(alex)), unit = RecurrenceUnit.MONTH, startOn = d("2026-03-01"),
        )
        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()
        val march = ExpenseDraft(ME, "Rent", total = eur(95_000), groupId = trip, participants = listOf(SplitInput(ME), SplitInput(alex)), occurredOn = d("2026-03-02"))
        val posted = h.run { RecurringOps.post(it, id, d("2026-03-01"), march, h.token(h.state.schedules.getValue(id))) }.ok()

        val detail = h.views().schedule(id, d("2026-04-20"))!!

        detail.editDraft shouldBe draft
        detail.handledThrough shouldBe d("2026-03-01")
        detail.due shouldBe listOf(d("2026-04-01"))
        detail.confirmToResume shouldBe false
        detail.posted.map { Triple(it.entryId, it.recurringId, it.recurringOn) } shouldBe listOf(Triple(posted, id, d("2026-03-01")))
        detail.history.map { it.reason } shouldBe listOf("Posted 2026-03-01", null)
        detail.token shouldBe h.token(h.state.schedules.getValue(id))

        val next = h.views().occurrenceDraft(id).ok()
        next.occurrence shouldBe d("2026-04-01")
        next.draft shouldBe ExpenseDraft(
            listOf(SplitInput(ME)), "Rent", total = eur(90_000), groupId = trip,
            participants = listOf(SplitInput(ME), SplitInput(alex)), occurredOn = d("2026-04-01"),
        )
        next.token shouldBe detail.token
    }

    @Test
    fun `occurrence drafts follow merges, and ended or deleted schedules have none`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alias = ledger.person("Alias", mergedInto = alex)
        val trip = ledger.group("Trip", members = listOf(ME, alex, alias))
        val id = ledger.schedule(alias, eur(1_000), listOf(ME, alias), group = trip)
        val ended = ledger.schedule(ME, eur(1_000), listOf(ME, alex), group = trip, endOn = D0, handledThrough = D0)
        val deleted = ledger.schedule(ME, eur(1_000), listOf(ME, alex), group = trip, deleted = true)
        val views = OpsHarness(ledger.state).views()

        val draft = views.occurrenceDraft(id).ok().draft
        draft.payers shouldBe listOf(SplitInput(alex))
        draft.participants shouldBe listOf(SplitInput(ME), SplitInput(alex))
        draft.fx shouldBe null
        views.occurrenceDraft(ended) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
        views.occurrenceDraft(deleted) shouldBe LedgerResult.Err(LedgerError.NotFound(deleted.value))
    }

    @Test
    fun `an old schedule version opens as a draft`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        val id = h.run { RecurringOps.save(it, draft, ScheduleSaveMode.Create(h.generation)) }.ok()
        h.run {
            RecurringOps.save(it, draft.copy(interval = 2, description = "Shared rent"), ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id))))
        }.ok()

        h.views().draftFromVersion(EntityRef(EntityType.RECURRING_EXPENSE, id.value), 1).ok() shouldBe VersionDraft.OfSchedule(draft)
    }
}
