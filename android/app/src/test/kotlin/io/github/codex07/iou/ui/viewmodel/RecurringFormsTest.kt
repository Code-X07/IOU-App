package io.github.codex07.iou.ui.viewmodel

import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.forms.ScheduleFields
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringFormsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC)
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }

    private fun form(a: Apis) = EntryFormViewModel(a.ledger, a.people, a.groups, a.setup, a.fx, a.backup, a.recurring)

    @Test fun `schedule form creates and edits a template with exact multiple payers`() = runTest(dispatcher) {
        val a = FakeApis.seeded(clock)
        val self = (a.setup.observeSetupState().first() as SetupState.Ready).me.id.value
        val other = a.people.observePeople().first().first().person.id.value
        val vm = form(a)
        vm.openSchedule(null)
        runCurrent()
        vm.change { it.copy(description = "Two payer subscription", amount = "12.50", date = "2027-01-31",
            participants = listOf(self, other), payers = listOf(self, other), payerSplit = PayerSplit.EXACT,
            payerInputs = mapOf(self to "5", other to "7.50")) }
        vm.changeSchedule { it.copy(interval = "100") }
        vm.save()
        runCurrent()
        assertEquals(LedgerError.InvalidField("interval"), vm.status.value.error)
        vm.changeSchedule { it.copy(interval = "1") }
        vm.save()
        runCurrent()
        val id = RecurringId(checkNotNull(vm.status.value.completedId))
        val detail = checkNotNull(a.recurring.observeSchedule(id).first())
        assertEquals(LocalDate.parse("2027-01-31"), detail.summary.next)
        assertEquals(listOf(500L, 750L), detail.editDraft.payers.map { it.value })
        vm.finish()

        val edit = form(a)
        edit.openSchedule(id)
        runCurrent()
        assertEquals(PayerSplit.EXACT, edit.state.value.fields.payerSplit)
        edit.changeSchedule { it.copy(endOn = "2027-01-01") }
        edit.save()
        runCurrent()
        assertEquals(LedgerError.InvalidField("endOn"), edit.status.value.error)
        edit.changeSchedule { it.copy(endOn = "2027-05-31", interval = "2") }
        edit.save()
        runCurrent()
        assertEquals(id.value, edit.status.value.completedId)
        val updated = checkNotNull(a.recurring.observeSchedule(id).first()).editDraft
        assertEquals(2, updated.interval)
        assertEquals(detail.editDraft.payers, updated.payers)
        edit.finish()
    }

    @Test fun `next preview stays anchored to month end and respects the handled date and end`() {
        val handled = LocalDate.parse("2027-02-28")
        val fields = ScheduleFields(handledThrough = handled)
        assertEquals(LedgerResult.Ok(LocalDate.parse("2027-03-31")), fields.next("2027-01-31"))
        assertEquals(LedgerResult.Ok(null), fields.copy(endOn = "2027-03-30").next("2027-01-31"))
    }

    @Test fun `occurrence form posts once with the linked occurrence even when the expense date changes`() = runTest(dispatcher) {
        val a = FakeApis.seeded(clock)
        val due = a.recurring.observeDue().first().first { it.description == "Rent" }
        val vm = form(a)
        vm.openOccurrence(due.schedule, due.date)
        runCurrent()
        assertEquals("Rent", vm.state.value.fields.description)
        vm.change { it.copy(date = due.date.plusDays(1).toString()) }
        vm.save()
        vm.save()
        runCurrent()
        val entry = checkNotNull(a.ledger.observeEntry(EntryId(checkNotNull(vm.status.value.completedId))).first())
        assertEquals(due.schedule, entry.item.recurringId)
        assertEquals(due.date, entry.item.recurringOn)
        assertEquals(due.date.plusDays(1), entry.item.occurredOn)
        assertEquals(1, a.recurring.observeSchedule(due.schedule).first()!!.posted.size)

        val outdated = form(a)
        outdated.openOccurrence(due.schedule, due.date)
        runCurrent()
        assertTrue(outdated.status.value.error is LedgerError.OccurrenceNotDue)
        outdated.save()
        runCurrent()
        assertNull(outdated.status.value.completedId)
        vm.finish()
        outdated.finish()
    }

    @Test fun `an archive after post and skip review prevents using a now held occurrence`() = runTest(dispatcher) {
        val a = FakeApis.seeded(clock)
        val due = a.recurring.observeDue().first().first { it.description == "Rent" }
        val post = form(a)
        val skip = DueViewModel(a.recurring)
        post.openOccurrence(due.schedule, due.date)
        skip.prepareSkip(due)
        runCurrent()
        val reviewed = checkNotNull(skip.action.value.review)
        val group = checkNotNull(a.groups.observeGroup(checkNotNull(due.group).id).first())
        assertTrue(a.groups.setGroupArchived(group.group.id, true, group.token) is LedgerResult.Ok)
        val held = a.recurring.observeSchedule(due.schedule).first()!!
        assertEquals(reviewed.token, held.token)
        assertTrue(held.summary.holds.isNotEmpty())
        post.save()
        skip.confirmSkip(due.date)
        runCurrent()
        assertEquals(LedgerError.OccurrenceNotDue(null), post.status.value.error)
        assertEquals(LedgerError.OccurrenceNotDue(null), skip.action.value.error)
        assertTrue(post.status.value.message!!.contains("on hold"))
        assertTrue(skip.action.value.message!!.contains("on hold"))
        assertEquals(held, a.recurring.observeSchedule(due.schedule).first())
        post.finish()
    }

    @Test fun `skip review uses due dates and the reviewed token then refreshes from the backend`() = runTest(dispatcher) {
        val a = FakeApis.seeded(clock)
        val due = a.recurring.observeDue().first().first { it.description == "Rent" }
        val vm = DueViewModel(a.recurring)
        vm.prepareSkip(due.copy(date = due.date.plusDays(1)))
        runCurrent()
        assertEquals(LedgerError.OccurrenceNotDue(due.date), vm.action.value.error)
        vm.prepareSkip(due)
        runCurrent()
        val review = checkNotNull(vm.action.value.review)
        val schedule = a.recurring.observeSchedule(due.schedule).first()!!
        assertEquals(schedule.due, review.dates)
        assertEquals(schedule.token, review.token)
        a.recurring.saveSchedule(schedule.editDraft.copy(description = "New rent"), ScheduleSaveMode.Update(due.schedule, schedule.token))
        vm.confirmSkip(due.date)
        runCurrent()
        assertTrue(vm.action.value.error is LedgerError.StaleVersion)
        vm.prepareSkip(due)
        runCurrent()
        vm.confirmSkip(due.date)
        vm.confirmSkip(due.date)
        runCurrent()
        assertNotNull(vm.action.value.message)
        val current = a.recurring.observeSchedule(due.schedule).first()!!
        assertEquals(due.date, current.handledThrough)
        assertTrue(current.posted.isEmpty())
        assertTrue(current.due.isEmpty())
    }
}
