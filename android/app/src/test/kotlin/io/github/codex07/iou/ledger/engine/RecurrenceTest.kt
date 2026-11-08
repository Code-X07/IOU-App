package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Occurrence dates, the handled date, due dates and holds (spec §5.7). */
class RecurrenceTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `monthly occurrences from 31 January clamp to month ends without drifting and stop at endOn`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), startOn = d("2026-01-31"), endOn = d("2026-04-30"))
        val rent = ledger.state.schedules.getValue(id)

        (0L..3L).map { Recurrence.occurrence(rent, it) } shouldBe
            listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30"))
        Recurrence.firstAfter(rent, null) shouldBe d("2026-01-31")
        Recurrence.firstAfter(rent, d("2026-02-28")) shouldBe d("2026-03-31")
        Recurrence.firstAfter(rent, d("2026-04-30")) shouldBe null
        Recurrence.isOccurrence(rent, d("2026-02-28")) shouldBe true
        Recurrence.isOccurrence(rent, d("2026-02-27")) shouldBe false
        Recurrence.isOccurrence(rent, d("2026-05-31")) shouldBe false
        Recurrence.occurrence(rent.copy(startOn = d("2028-01-31")), 1) shouldBe d("2028-02-29")
        val fortnightly = rent.copy(unit = RecurrenceUnit.WEEK, interval = 2, startOn = D0, endOn = null)
        Recurrence.firstAfter(fortnightly, d("2027-06-01")) shouldBe d("2027-06-03")
    }

    @Test
    fun `the next occurrence follows the later of handledThrough and the live posted expenses`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), handledThrough = d("2026-02-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-03-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-04-01"), deleted = true)
        val schedule = ledger.state.schedules.getValue(id)

        Recurrence.handledDate(schedule, ledger.state) shouldBe d("2026-03-01")
        Recurrence.next(schedule, ledger.state) shouldBe d("2026-04-01")
        Recurrence.due(schedule, ledger.state, d("2026-05-15")) shouldBe listOf(d("2026-04-01"), d("2026-05-01"))
        Recurrence.due(schedule, ledger.state, d("2026-03-31")) shouldBe emptyList()
        Recurrence.next(schedule.copy(handledThrough = null), LedgerState()) shouldBe D0
    }

    @Test
    fun `a changed pattern skips nothing - the next date is the new pattern's first after the handled date`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = d("2026-01-01"), handledThrough = d("2026-12-01"))
        val rent = ledger.state.schedules.getValue(id)

        Recurrence.next(rent, ledger.state) shouldBe d("2027-01-01")
        Recurrence.next(rent.copy(startOn = d("2026-01-05")), ledger.state) shouldBe d("2026-12-05")
        Recurrence.next(rent.copy(endOn = d("2026-11-30")), ledger.state) shouldBe null
    }

    @Test
    fun `a schedule is on hold while its group or the canonical person of one of its people is archived`() {
        val ledger = TestLedger().apply { me() }
        val bea = ledger.person("Bea", archived = true)
        val alias = ledger.person("Bea2", mergedInto = bea)
        val chen = ledger.person("Chen")
        val flat = ledger.group("Flat", members = listOf(ME, bea, alias, chen))
        val trip = ledger.group("Trip", members = listOf(ME, chen), archived = true)
        val viaAlias = ledger.schedule(alias, eur(1_000), listOf(ME, chen), group = flat)
        val inTrip = ledger.schedule(ME, eur(1_000), listOf(ME, chen), group = trip)
        val free = ledger.schedule(ME, eur(1_000), listOf(ME, chen), group = flat)
        val state = ledger.state
        fun holds(id: RecurringId) = Recurrence.holds(state.schedules.getValue(id), state)

        holds(viaAlias) shouldBe listOf(Recurrence.Hold.PersonArchived(bea))
        holds(inTrip) shouldBe listOf(Recurrence.Hold.GroupArchived(trip))
        holds(free) shouldBe emptyList()
        Recurrence.autoPosts(state.schedules.getValue(free).copy(autoPost = true), state) shouldBe true
        Recurrence.autoPosts(state.schedules.getValue(free), state) shouldBe false
        Recurrence.autoPosts(state.schedules.getValue(viaAlias).copy(autoPost = true), state) shouldBe false
    }

    @Test
    fun `schedules are diffed per field and per participant, without autoPostFrom`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val beforeId = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val before = ledger.state.schedules.getValue(beforeId)
        val after = before.copy(
            interval = 2,
            handledThrough = d("2026-03-01"),
            autoPostFrom = d("2026-09-01"),
            participants = before.participants + RecurringParticipant(ParticipantRole.SHARE, y),
        )

        val changes = RevisionDiff.diff(before, after)

        changes shouldContain FieldChange("interval", "1", "2")
        changes shouldContain FieldChange("handledThrough", null, "2026-03-01")
        changes shouldContain FieldChange("participant[${y.value}]", null, "yes")
        changes.none { it.field == "autoPostFrom" } shouldBe true
    }
}
