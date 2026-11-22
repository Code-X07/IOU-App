package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSummary
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.ops.EntryOps
import java.time.LocalDate

/** Schedules by description; [onHold] keeps only held (true) or only active (false) ones, null both (spec §5.7, §6.2). */
fun LedgerViews.schedules(includeDeleted: Boolean, onHold: Boolean?, today: LocalDate): List<ScheduleSummary> =
    state.schedules.values
        .filter { includeDeleted || !it.isDeleted }
        .map { scheduleSummary(it, today) }
        .filter { onHold == null || it.holds.isNotEmpty() == onHold }
        .sortedWith(compareBy<ScheduleSummary>({ it.description.lowercase() }, { it.id.value }))

fun LedgerViews.scheduleSummary(schedule: RecurringExpense, today: LocalDate): ScheduleSummary = ScheduleSummary(
    id = schedule.id,
    description = schedule.description,
    group = schedule.groupId?.let(::groupRef),
    total = schedule.total,
    unit = schedule.unit,
    interval = schedule.interval,
    autoPost = schedule.autoPost,
    next = Recurrence.next(schedule, state),
    dueCount = dueDates(schedule, today).size,
    holds = holdReasons(schedule),
    deleted = schedule.isDeleted,
)

fun LedgerViews.schedule(id: RecurringId, today: LocalDate): ScheduleDetail? {
    val schedule = state.schedules[id] ?: return null
    val summary = scheduleSummary(schedule, today)
    val due = dueDates(schedule, today)
    return ScheduleDetail(
        generation = generation,
        summary = summary,
        token = token(schedule),
        editDraft = scheduleDraftOf(schedule),
        handledThrough = Recurrence.handledDate(schedule, state),
        due = due,
        // From the due dates, as in the due list: a held or deleted schedule has none, so it never asks to confirm.
        confirmToResume = confirmToResume(schedule, due),
        posted = state.expenses.values
            .filter { it.recurringId == id && !it.isDeleted }
            .sortedWith(compareByDescending<Expense> { it.occurredOn }.thenByDescending { it.meta.createdAt }.thenByDescending { it.id.value })
            .map { activityItem(it) },
        history = history(EntityType.RECURRING_EXPENSE, id.value),
    )
}

/** Every due occurrence of every live schedule not on hold, oldest first (spec §5.7). */
fun LedgerViews.dueOccurrences(today: LocalDate): List<DueOccurrence> =
    state.schedules.values
        .flatMap { schedule ->
            val dates = dueDates(schedule, today)
            val waits = confirmToResume(schedule, dates)
            dates.map { date ->
                DueOccurrence(
                    schedule = schedule.id, description = schedule.description, date = date, amount = schedule.total,
                    group = schedule.groupId?.let(::groupRef), autoPost = schedule.autoPost, confirmToResume = waits,
                )
            }
        }
        .sortedWith(compareBy<DueOccurrence>({ it.date }, { it.description.lowercase() }, { it.schedule.value }))

/**
 * The draft for the schedule's next occurrence (spec §5.7): the template with the occurrence date, payers and
 * participants on canonical people with records combined, and no FX choice. Ended → `OccurrenceNotDue(null)`.
 */
fun LedgerViews.occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft> {
    val schedule = state.schedules[id]?.takeIf { !it.isDeleted } ?: return LedgerResult.Err(LedgerError.NotFound(id.value))
    val next = Recurrence.next(schedule, state) ?: return LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
    val draft = ExpenseDraft(
        payers = inputsOf(schedule.payerRows, schedule.payerSplit.splitType),
        description = schedule.description,
        category = schedule.category,
        total = schedule.total,
        groupId = schedule.groupId,
        splitType = schedule.splitType,
        participants = inputsOf(schedule.shareRows, schedule.splitType),
        occurredOn = next,
        note = schedule.note,
        fx = null,
        payerSplit = schedule.payerSplit,
    )
    return LedgerResult.Ok(OccurrenceDraft(id, next, draft, token(schedule)))
}

/** A schedule's template and pattern as its edit form takes them (also "Open as draft" of an old version). */
internal fun LedgerViews.scheduleDraftOf(schedule: RecurringExpense): RecurringDraft = RecurringDraft(
    payers = inputsOf(schedule.payerRows, schedule.payerSplit.splitType),
    description = schedule.description,
    category = schedule.category,
    total = schedule.total,
    groupId = schedule.groupId,
    splitType = schedule.splitType,
    participants = inputsOf(schedule.shareRows, schedule.splitType),
    note = schedule.note,
    payerSplit = schedule.payerSplit,
    unit = schedule.unit,
    interval = schedule.interval,
    startOn = schedule.startOn,
    endOn = schedule.endOn,
    autoPost = schedule.autoPost,
)

// ---- internals ----

/** Due dates, none for a deleted or held schedule. */
private fun LedgerViews.dueDates(schedule: RecurringExpense, today: LocalDate): List<LocalDate> =
    if (schedule.isDeleted || Recurrence.holds(schedule, state, resolver).isNotEmpty()) emptyList()
    else Recurrence.due(schedule, state, today)

/**
 * Spec §5.7: the runner posts nothing for an auto-post schedule while its next occurrence is before the cut-off, or
 * while more than [Recurrence.MAX_AUTO_CATCH_UP] occurrences are due.
 */
private fun confirmToResume(schedule: RecurringExpense, due: List<LocalDate>): Boolean =
    schedule.autoPost && due.isNotEmpty() && (due.first().isBefore(schedule.autoPostFrom) || due.size > Recurrence.MAX_AUTO_CATCH_UP)

private fun LedgerViews.holdReasons(schedule: RecurringExpense): List<HoldReason> =
    Recurrence.holds(schedule, state, resolver).map { hold ->
        when (hold) {
            is Recurrence.Hold.GroupArchived -> HoldReason.GroupArchived(groupRef(hold.groupId))
            is Recurrence.Hold.PersonArchived -> HoldReason.PersonArchived(personRef(hold.personId))
        }
    }

private fun LedgerViews.inputsOf(rows: List<RecurringParticipant>, type: SplitType): List<SplitInput> =
    EntryOps.combineInputs(rows.map { SplitInput(it.personId, it.inputValue) }, type, resolver)
