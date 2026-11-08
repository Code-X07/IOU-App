package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Recurring expenses (spec §5.7, §6.2). A schedule either posts by itself (`autoPost`) or lists its due dates for the
 * user to post or skip, oldest first; out of order → `OccurrenceNotDue(next)`. Revert and "Open as draft" of a schedule
 * go through `LedgerApi.revertTo` / `draftFromVersion` (entity type `RECURRING_EXPENSE`), as for other entities.
 */
interface RecurringApi {

    /** Schedules by description. [onHold]: true lists only held schedules (their own screen), false only active ones, null both. */
    fun observeSchedules(includeDeleted: Boolean = false, onHold: Boolean? = null): Flow<List<ScheduleSummary>>

    fun observeSchedule(id: RecurringId): Flow<ScheduleDetail?>

    /** Every due occurrence of every live schedule not on hold, oldest first, with `autoPostError` where a pass stopped. */
    fun observeDue(): Flow<List<DueOccurrence>>

    /** Errors: field and split errors as for expenses, `InvalidField("interval")`, `InvalidField("endOn")`, and any rule. */
    suspend fun saveSchedule(draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<RecurringId>

    /** Keeps the posted expenses. */
    suspend fun deleteSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit>

    /** Brings its occurrences after the handled date back; an auto-post schedule waits for them to be confirmed. */
    suspend fun restoreSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit>

    /** The draft for the next occurrence: `NotFound` for a deleted schedule, `OccurrenceNotDue(null)` once it ended. */
    suspend fun occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft>

    /** Posts [occurrence], which must be the next one, as [draft] (edited freely); [token] is the schedule's. */
    suspend fun postOccurrence(id: RecurringId, occurrence: LocalDate, draft: ExpenseDraft, token: EditToken): LedgerResult<EntryId>

    /** Skips every occurrence from the next one through [through], which must be an occurrence date. */
    suspend fun skipOccurrences(id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Unit>
}
