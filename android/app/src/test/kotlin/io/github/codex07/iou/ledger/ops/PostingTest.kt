package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Posting and skipping occurrences (spec §5.7, rules 18–19, AC 28 and 33 at op level). */
class PostingTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    /** A monthly confirm-first rent in Flat (me and X), from 1 March to 1 June 2026. */
    private class Fixture {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(
            ME, eur(90_000), listOf(ME, x), group = flat,
            startOn = LocalDate.parse("2026-03-01"), endOn = LocalDate.parse("2026-06-01"),
        )
        val h = OpsHarness(ledger.state)

        fun schedule() = h.state.schedules.getValue(rent)

        fun draft(on: LocalDate, total: Long = 90_000) =
            ExpenseDraft(ME, "Rent", total = eur(total), groupId = flat, participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = on)

        fun post(on: LocalDate, total: Long = 90_000) = h.run { RecurringOps.post(it, rent, on, draft(on, total), h.token(schedule())) }

        fun skip(through: LocalDate) = h.run { RecurringOps.skip(it, rent, through, h.token(schedule())) }
    }

    @Test
    fun `occurrences are posted strictly in order, as edited, without changing the template`() {
        val f = Fixture()
        Recurrence.due(f.schedule(), f.h.state, d("2026-04-15")) shouldBe listOf(d("2026-03-01"), d("2026-04-01"))
        f.post(d("2026-04-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))

        val id = f.post(d("2026-03-01"), total = 95_000).ok()

        val expense = f.h.state.expenses.getValue(id)
        expense.total shouldBe eur(95_000)
        expense.recurringId shouldBe f.rent
        expense.recurringOn shouldBe d("2026-03-01")
        f.schedule().total shouldBe eur(90_000)
        f.schedule().handledThrough shouldBe d("2026-03-01")
        f.h.revisions.takeLast(2).map { it.entityType to it.reason } shouldBe listOf(
            EntityType.EXPENSE to "Posted from ${f.schedule().description}",
            EntityType.RECURRING_EXPENSE to "Posted 2026-03-01",
        )

        f.skip(d("2026-04-01")) shouldBe LedgerResult.Ok(Unit)
        f.schedule().handledThrough shouldBe d("2026-04-01")
        f.h.revisions.last().reason shouldBe "Skipped through 2026-04-01"
        Recurrence.due(f.schedule(), f.h.state, d("2026-04-15")) shouldBe emptyList()
    }

    @Test
    fun `skips land on occurrences, nothing goes past endOn, and a deleted schedule posts nothing`() {
        val f = Fixture()
        f.skip(d("2026-03-15")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))
        f.skip(d("2026-07-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-03-01")))
        f.skip(d("2026-06-01")) shouldBe LedgerResult.Ok(Unit)
        f.post(d("2026-07-01")) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))

        val g = Fixture()
        g.h.run { RecurringOps.delete(it, g.rent, g.h.token(g.schedule())) } shouldBe LedgerResult.Ok(Unit)
        g.post(d("2026-03-01")) shouldBe LedgerResult.Err(LedgerError.NotFound(g.rent.value))
        g.skip(d("2026-03-01")) shouldBe LedgerResult.Err(LedgerError.NotFound(g.rent.value))
    }

    @Test
    fun `a post with an old token is refused, so an occurrence is never posted twice`() {
        val f = Fixture()
        val token = f.h.token(f.schedule())
        f.post(d("2026-03-01")).ok()

        f.h.run { RecurringOps.post(it, f.rent, d("2026-03-01"), f.draft(d("2026-03-01")), token) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(f.rent.value, 1, 2))
        f.h.state.expenses.values.count { it.recurringId == f.rent } shouldBe 1
    }

    @Test
    fun `a posted expense keeps its link through edits and reverts`() {
        val f = Fixture()
        val march = f.post(d("2026-03-01")).ok()
        fun expense() = f.h.state.expenses.getValue(march)

        f.h.run { EntryOps.saveExpense(it, f.draft(d("2026-03-03"), total = 91_000), SaveMode.Update(march, f.h.token(expense()))) } shouldBe
            LedgerResult.Ok(march)
        (expense().recurringId to expense().recurringOn) shouldBe (f.rent to d("2026-03-01"))

        f.h.run { RevertOps.revertTo(it, EntityRef(EntityType.EXPENSE, march.value), 1, f.h.token(expense()), f.h.revisions) } shouldBe
            LedgerResult.Ok(Unit)
        Triple(expense().total, expense().recurringId, expense().recurringOn) shouldBe Triple(eur(90_000), f.rent, d("2026-03-01"))

        // The schedule already counts March as handled, so the delete writes the expense only.
        f.h.run { EntryOps.deleteEntry(it, march, f.h.token(expense())) } shouldBe LedgerResult.Ok(Unit)
        f.h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.EXPENSE)
        Recurrence.next(f.schedule(), f.h.state) shouldBe d("2026-04-01")
    }

    @Test
    fun `deleting a posted expense the schedule hasn't caught up with raises handledThrough`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, handledThrough = d("2026-02-01"))
        val march = ledger.expense(
            ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), group = flat, recurring = rent, recurringOn = d("2026-03-01"),
        )
        val h = OpsHarness(ledger.state)

        h.run { EntryOps.deleteEntry(it, march, h.token(h.state.expenses.getValue(march))) } shouldBe LedgerResult.Ok(Unit)
        h.state.schedules.getValue(rent).handledThrough shouldBe d("2026-03-01")
        h.revisions.last().reason shouldBe "Handled through 2026-03-01: posted expense deleted"
        Recurrence.next(h.state.schedules.getValue(rent), h.state) shouldBe d("2026-04-01")

        h.run { EntryOps.restoreEntry(it, march, h.token(h.state.expenses.getValue(march))) } shouldBe LedgerResult.Ok(Unit)
        h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.EXPENSE)
    }

    @Test
    fun `reverting a schedule changes its template and pattern, never what was handled`() {
        val f = Fixture()
        f.post(d("2026-03-01")).ok()
        val edit = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(99_000), groupId = f.flat,
            participants = listOf(SplitInput(ME), SplitInput(f.x)), unit = RecurrenceUnit.MONTH, startOn = d("2026-03-05"),
        )
        f.h.run { RecurringOps.save(it, edit, ScheduleSaveMode.Update(f.rent, f.h.token(f.schedule()))) }.ok()

        f.h.run { RevertOps.revertTo(it, EntityRef(EntityType.RECURRING_EXPENSE, f.rent.value), 1, f.h.token(f.schedule()), f.h.revisions) } shouldBe
            LedgerResult.Ok(Unit)

        f.schedule().total shouldBe eur(90_000)
        f.schedule().startOn shouldBe d("2026-03-01")
        f.schedule().handledThrough shouldBe d("2026-03-01")
        f.schedule().meta.version shouldBe 4
    }
}
