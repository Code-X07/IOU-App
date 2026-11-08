package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.RecurringApi
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.ScheduleSummary
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import io.github.codex07.iou.ledger.views.schedule
import io.github.codex07.iou.ledger.views.schedules
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * [RecurringApi] for both flavors. Dates are relative to the store's today (device zone), taken on every emission;
 * the date-relative reads also start over whenever a runner pass runs for a new day ([RecurringRunner.today]), so
 * an open due list catches up with the pass even when that pass writes nothing (spec §5.7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecurringApi(private val store: LedgerStore, private val runner: RecurringRunner) : RecurringApi {

    override fun observeSchedules(includeDeleted: Boolean, onHold: Boolean?): Flow<List<ScheduleSummary>> =
        dated { it.schedules(includeDeleted, onHold, store.today()) }

    override fun observeSchedule(id: RecurringId): Flow<ScheduleDetail?> = dated { it.schedule(id, store.today()) }

    override fun observeDue(): Flow<List<DueOccurrence>> =
        combine(dated { it.dueOccurrences(store.today()) }, runner.errors) { due, errors -> RecurringRunner.withErrors(due, errors) }

    private fun <T> dated(project: (LedgerViews) -> T): Flow<T> = runner.today.flatMapLatest { store.read(project) }

    override suspend fun saveSchedule(draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<RecurringId> =
        written { RecurringOps.save(it, draft, mode) }

    override suspend fun deleteSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.delete(it, id, token) }

    override suspend fun restoreSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.restore(it, id, token) }

    override suspend fun occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft> = store.currentViews().occurrenceDraft(id)

    override suspend fun postOccurrence(id: RecurringId, occurrence: LocalDate, draft: ExpenseDraft, token: EditToken): LedgerResult<EntryId> =
        written { RecurringOps.post(it, id, occurrence, draft, token) }

    override suspend fun skipOccurrences(id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.skip(it, id, through, token) }

    /** A schedule write; a successful one asks for a runner pass, which also brings notifications up to date (spec §5.7). */
    private suspend fun <T> written(op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> =
        store.write(op = op).also { if (it is LedgerResult.Ok) runner.request() }
}
