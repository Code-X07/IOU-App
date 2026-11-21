package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringExpense
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A schedule's occurrence dates, handled date, due dates and holds (spec §5.7). Pure. */
object Recurrence {

    /** Why a schedule is on hold: its group, or the canonical person of one of its people, is archived. */
    sealed interface Hold {
        data class GroupArchived(val groupId: GroupId) : Hold
        data class PersonArchived(val personId: PersonId) : Hold
    }

    /**
     * Occurrence [n] (n ≥ 0): `startOn` plus n × `interval` units, always counted from `startOn`, so a month end
     * clamps without drifting (31 Jan, 28 Feb, 31 Mar). Ignores `endOn`.
     */
    fun occurrence(schedule: RecurringExpense, n: Long): LocalDate =
        occurrence(schedule.startOn, schedule.unit, schedule.interval, n)

    private fun occurrence(startOn: LocalDate, unit: RecurrenceUnit, interval: Int, n: Long): LocalDate {
        require(interval >= 1)
        val units = Math.multiplyExact(n, interval.toLong())
        return when (unit) {
            RecurrenceUnit.WEEK -> startOn.plusWeeks(units)
            RecurrenceUnit.MONTH -> startOn.plusMonths(units)
            RecurrenceUnit.YEAR -> startOn.plusYears(units)
        }
    }

    /** The first occurrence after [after] (the first one when [after] is null); null when it lies after `endOn`. */
    fun firstAfter(schedule: RecurringExpense, after: LocalDate?): LocalDate? =
        firstAfter(schedule.startOn, schedule.unit, schedule.interval, schedule.endOn, after)

    internal fun firstAfter(startOn: LocalDate, unit: RecurrenceUnit, interval: Int, endOn: LocalDate?, after: LocalDate?): LocalDate? {
        require(interval >= 1)
        if (endOn != null && after != null && after >= endOn) return null
        var n = if (after == null) 0L else lowerBound(startOn, unit, interval, after)
        var date = occurrence(startOn, unit, interval, n)
        while (after != null && !date.isAfter(after)) {
            n += 1
            date = occurrence(startOn, unit, interval, n)
        }
        return if (endOn != null && date.isAfter(endOn)) null else date
    }

    fun isOccurrence(schedule: RecurringExpense, date: LocalDate): Boolean = firstAfter(schedule, date.minusDays(1)) == date

    /** The latest of `handledThrough` and the `recurringOn` of every non-deleted expense posted from [schedule]. */
    fun handledDate(schedule: RecurringExpense, state: LedgerState): LocalDate? {
        val posted = state.expenses.values
            .filter { !it.isDeleted && it.recurringId == schedule.id }
            .mapNotNull { it.recurringOn }
        return (posted + listOfNotNull(schedule.handledThrough)).maxOrNull()
    }

    /** The first occurrence of the current pattern after the handled date; null once the schedule has ended. */
    fun next(schedule: RecurringExpense, state: LedgerState): LocalDate? = firstAfter(schedule, handledDate(schedule, state))

    /** The occurrences from the next one through [today], oldest first. Holds are not considered here. */
    fun due(schedule: RecurringExpense, state: LedgerState, today: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var date = next(schedule, state)
        while (date != null && !date.isAfter(today)) {
            out += date
            date = firstAfter(schedule, date)
        }
        return out
    }

    /** Empty unless [schedule] is on hold: its group first, then archived canonical people by ID. */
    fun holds(schedule: RecurringExpense, state: LedgerState, resolver: IdentityResolver = IdentityResolver(state)): List<Hold> {
        val group = schedule.groupId?.let { state.groups[it] }?.takeIf { it.archivedAt != null }?.let { Hold.GroupArchived(it.id) }
        val people = schedule.personIds.map(resolver::canonical).distinct().sortedBy { it.value }
            .filter { state.people[it]?.archivedAt != null }
            .map { Hold.PersonArchived(it) }
        return listOfNotNull(group) + people
    }

    /** Spec §5.7: a schedule auto-posts when it is not deleted, has `autoPost` on and is not on hold. */
    fun autoPosts(schedule: RecurringExpense, state: LedgerState, resolver: IdentityResolver = IdentityResolver(state)): Boolean =
        !schedule.isDeleted && schedule.autoPost && holds(schedule, state, resolver).isEmpty()

    /** An n whose occurrence is on or before [date], so a long history is not walked one step at a time. */
    private fun lowerBound(startOn: LocalDate, unit: RecurrenceUnit, interval: Int, date: LocalDate): Long {
        if (!date.isAfter(startOn)) return 0L
        val chronology = when (unit) {
            RecurrenceUnit.WEEK -> ChronoUnit.WEEKS
            RecurrenceUnit.MONTH -> ChronoUnit.MONTHS
            RecurrenceUnit.YEAR -> ChronoUnit.YEARS
        }
        return maxOf(0L, chronology.between(startOn, date) / interval - 1)
    }
}
