package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.GroupRef
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `RecurringApi` in both flavors (spec §5.7, AC 27, 28, 31, 33). Today is [TODAY], 1 March 2026. */
abstract class RecurringApiContract : ApiContract() {

    private fun d(text: String) = LocalDate.parse(text)

    /** Monthly confirm-first rent from 1 February: February and March are due today. */
    private fun rent(me: PersonId, other: PersonId) = RecurringDraft(
        payers = listOf(SplitInput(me)), description = "Rent", total = eur(90_000),
        participants = listOf(SplitInput(me), SplitInput(other)), unit = RecurrenceUnit.MONTH, startOn = d("2026-02-01"),
    )

    private suspend fun save(draft: RecurringDraft): RecurringId = apis.recurring.saveSchedule(draft, ScheduleSaveMode.Create(basis())).orFail()

    private suspend fun detail(id: RecurringId): ScheduleDetail = apis.recurring.observeSchedule(id).first()!!

    private suspend fun dueDates() = apis.recurring.observeDue().first().map { it.date }

    @Test
    fun `a monthly schedule from 31 January clamps to month ends and stops at endOn`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x).copy(startOn = d("2026-01-31"), endOn = d("2026-04-30")))
        suspend fun skip(through: LocalDate) = apis.recurring.skipOccurrences(id, through, detail(id).token).orFail()

        dueDates() shouldBe listOf(d("2026-01-31"), d("2026-02-28"))
        skip(d("2026-02-28"))
        detail(id).summary.next shouldBe d("2026-03-31")
        skip(d("2026-03-31"))
        detail(id).summary.next shouldBe d("2026-04-30")
        skip(d("2026-04-30"))
        detail(id).summary.next shouldBe null
        apis.recurring.occurrenceDraft(id) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
    }

    @Test
    fun `due dates are posted or skipped oldest first, and a posted expense keeps its link`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x))
        dueDates() shouldBe listOf(d("2026-02-01"), d("2026-03-01"))

        val february = apis.recurring.occurrenceDraft(id).orFail()
        february.occurrence shouldBe d("2026-02-01")
        apis.recurring.postOccurrence(id, d("2026-03-01"), february.draft, february.token) shouldBe
            LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-02-01")))
        val posted = apis.recurring.postOccurrence(id, february.occurrence, february.draft.copy(total = eur(95_000)), february.token).orFail()

        val item = apis.ledger.observeEntry(posted).first()!!.item
        item.paid shouldBe eur(95_000)
        (item.recurringId to item.recurringOn) shouldBe (id to d("2026-02-01"))
        detail(id).editDraft.total shouldBe eur(90_000)
        apis.recurring.skipOccurrences(id, d("2026-03-01"), detail(id).token).orFail()
        dueDates() shouldBe emptyList()
    }

    @Tag("groups")
    @Test
    fun `archiving a schedule's group holds it, and what a schedule references can't be deleted`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val y = newPerson("Y")
        val flat = apis.groups.createGroup(GroupDraft("Flat", EUR, FxMode.KEEP), listOf(a), basis()).orFail()
        val id = save(rent(me, a).copy(groupId = flat))
        save(rent(me, y))
        suspend fun groupToken() = apis.groups.observeGroup(flat).first()!!.token

        apis.groups.setGroupArchived(flat, true, groupToken()).orFail()
        apis.recurring.observeSchedules(onHold = true).first().map { it.id } shouldBe listOf(id)
        apis.recurring.observeSchedules(onHold = false).first().map { it.id }.contains(id) shouldBe false
        detail(id).summary.holds shouldBe listOf(HoldReason.GroupArchived(GroupRef(flat, "Flat")))
        apis.recurring.observeDue().first().none { it.schedule == id } shouldBe true

        apis.groups.setGroupArchived(flat, false, groupToken()).orFail()
        apis.recurring.observeDue().first().filter { it.schedule == id }.map { it.date } shouldBe listOf(d("2026-02-01"), d("2026-03-01"))
        detail(id).history.first().reason shouldBe "Resumed: Flat unarchived"

        apis.people.deletePerson(y, apis.people.observePerson(y).first()!!.token) shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(y.value))
        apis.groups.deleteGroup(flat, groupToken()) shouldBe LedgerResult.Err(LedgerError.StillReferenced(flat.value))
    }

    @Test
    fun `an edit changes later occurrences only, and a deleted schedule keeps its posted expenses`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x).copy(startOn = d("2026-01-01")))
        val january = apis.recurring.occurrenceDraft(id).orFail()
        val posted = apis.recurring.postOccurrence(id, january.occurrence, january.draft, january.token).orFail()
        apis.recurring.skipOccurrences(id, d("2026-02-01"), detail(id).token).orFail()

        apis.recurring.saveSchedule(
            rent(me, x).copy(startOn = d("2026-01-05"), total = eur(99_000)), ScheduleSaveMode.Update(id, detail(id).token),
        ).orFail()
        detail(id).summary.next shouldBe d("2026-02-05")
        apis.ledger.observeEntry(posted).first()!!.item.paid shouldBe eur(90_000)

        apis.recurring.deleteSchedule(id, detail(id).token).orFail()
        apis.ledger.observeEntry(posted).first()!!.item.deleted shouldBe false
        dueDates() shouldBe emptyList()
        apis.recurring.restoreSchedule(id, detail(id).token).orFail()
        dueDates() shouldBe listOf(d("2026-02-05"))
    }
}
