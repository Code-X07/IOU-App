package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Auto-posting (spec §5.7), the same in both flavors (index, locked decision 8). A pass posts the due occurrences of
 * every schedule that auto-posts, by schedule ID and oldest first, through `RecurringOps.post` under the store's write
 * lock, but only while the next occurrence is on or after the schedule's `autoPostFrom`: a backlog waits for the
 * user, and so do more than [Recurrence.MAX_AUTO_CATCH_UP] due occurrences. A failure stops that schedule at that occurrence and is kept in [errors] (memory only) until a later pass
 * gets past it; the other schedules still run. Every pass ends by reporting to [notices].
 */
class RecurringRunner(
    private val store: LedgerStore,
    private val quotes: QuoteSource,
    private val notices: RecurringNotices = RecurringNotices.None,
) {
    /** Where a schedule's last pass stopped, and why: the due list's `autoPostError` for that occurrence. */
    data class Failure(val occurrence: LocalDate, val error: LedgerError)

    private val pass = Mutex()
    private val report = Mutex()
    private val failures = MutableStateFlow<Map<RecurringId, Failure>>(emptyMap())
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val day = MutableStateFlow(store.today())

    val errors: StateFlow<Map<RecurringId, Failure>> = failures.asStateFlow()

    /**
     * The day the last pass ran for (the store's today until the first pass). Date-relative reads follow it, so a
     * pass on a new day refreshes the due list and schedule read models even when it writes nothing.
     */
    val today: StateFlow<LocalDate> = day.asStateFlow()

    /**
     * One pass; waits for the start-up load. Returns what it posted. Passes never overlap. A ledger without me (before
     * setup, or after undoing a restore onto a fresh install) posts nothing but still reports an empty due list, so
     * notifications of a discarded ledger go away (spec §5.7).
     */
    suspend fun run(): List<PostedOccurrence> = pass.withLock {
        val start = store.currentViews()
        if (start.state.meOrNull == null) {
            failures.value = emptyMap()
            report.withLock { notices.afterRun(emptyList(), emptyList()) }
            return@withLock emptyList()
        }
        val today = store.today()
        day.value = today
        val posted = mutableListOf<PostedOccurrence>()
        val failed = LinkedHashMap<RecurringId, Failure>()
        val ids = start.state.schedules.values
            .filter { Recurrence.autoPosts(it, start.state, start.resolver) }
            .map { it.id }
            .sortedBy { it.value }
        for (id in ids) runSchedule(id, today, posted)?.let { failed[id] = it }
        failures.value = failed
        report.withLock { notices.afterRun(withErrors(store.currentViews().dueOccurrences(today), failed), posted) }
        posted
    }

    /**
     * Reports the current due list, with the last failures, at once: no posting, and no waiting for a pass, which can
     * sit on rate requests. Used for [RecurringNotices.redraw], so an app-lock switch redacts within moments (spec
     * §5.7). Reports never overlap; a pass already running reports again when it ends.
     */
    suspend fun redraw() {
        report.withLock {
            val views = store.currentViews()
            val due = if (views.state.meOrNull == null) emptyList() else withErrors(views.dueOccurrences(store.today()), failures.value)
            notices.afterRun(due, emptyList())
        }
    }

    /** Asks for a pass soon; requests made while one is waiting fold into it. Needs [start] to have run. */
    fun request() {
        requests.trySend(Unit)
    }

    /**
     * Runs requested passes in [scope], and requests one for every new ledger generation (the start-up load, a restore,
     * an undo of a restore), every change of the import batches (an import, an import undo), every change of the due
     * list, whatever wrote it (an archive or unarchive through `PeopleApi` / `GroupsApi`, a merge, a revert, a post or
     * skip). Every [RecurringNotices.redraw] runs [redraw] instead of a pass. A pass that posts changes the due list
     * and so is followed by one more, which finds nothing left to post.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            for (signal in requests) {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed write (e.g. a full disk) leaves the ledger as it was; the next trigger tries again.
                }
            }
        }
        launch { store.ready.map { it.dueOccurrences(store.today()) }.distinctUntilChanged().collect { request() } }
        launch {
            notices.redraw.collect {
                try {
                    redraw()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Drawing failed (e.g. the notified file can't be written); the next pass reports again.
                }
            }
        }
        store.snapshot.map { it.generation to it.importBatches }.distinctUntilChanged().collect { request() }
    }

    /** Posts one schedule's due occurrences; returns where it stopped on a failure, or null. */
    private suspend fun runSchedule(id: RecurringId, today: LocalDate, posted: MutableList<PostedOccurrence>): Failure? {
        // Counted once, before posting: a catch-up larger than the limit (a clock jump ahead) waits for the user whole.
        store.currentViews().let { views ->
            val schedule = views.state.schedules[id] ?: return null
            if (Recurrence.due(schedule, views.state, today).size > Recurrence.MAX_AUTO_CATCH_UP) return null
        }
        while (true) {
            val views = store.currentViews()
            val schedule = views.state.schedules[id] ?: return null
            if (!Recurrence.autoPosts(schedule, views.state, views.resolver)) return null
            val next = Recurrence.next(schedule, views.state) ?: return null
            if (next.isAfter(today) || next.isBefore(schedule.autoPostFrom)) return null
            val prepared = when (val result = views.occurrenceDraft(id)) {
                is LedgerResult.Ok -> result.value
                is LedgerResult.Err -> return Failure(next, result.error)
            }
            val draft = when (val result = withRate(views, prepared)) {
                is LedgerResult.Ok -> result.value
                is LedgerResult.Err -> return Failure(next, result.error)
            }
            val result = store.write<EntryId?> { ctx ->
                // A hold can start while the rate is fetched and leaves the schedule's version alone, so the token
                // still matches: check again under the write lock, and post nothing when it no longer auto-posts.
                val live = ctx.state.schedules[id]
                if (live == null || !Recurrence.autoPosts(live, ctx.state)) LedgerResult.Ok(Planned(null, WritePlan.Empty))
                else RecurringOps.post(ctx, id, prepared.occurrence, draft, prepared.token)
            }
            when (result) {
                is LedgerResult.Ok -> if (result.value == null) return null else {
                    posted += PostedOccurrence(id, schedule.description, prepared.occurrence, draft.total)
                }
                is LedgerResult.Err -> return Failure(next, result.error)
            }
        }
    }

    /** Spec §5.7 FX: a conversion takes the rate for the occurrence date through the lookup chain; a stale one is fine. */
    private suspend fun withRate(views: LedgerViews, prepared: OccurrenceDraft): LedgerResult<ExpenseDraft> {
        val draft = prepared.draft
        val required = EntryOps.requiredEffectCurrency(views.state, draft.groupId, draft.total.currency, settlesCurrency = null)
        if (required == draft.total.currency) return LedgerResult.Ok(draft)
        return quotes.quote(prepared.occurrence, draft.total.currency, required).map { draft.copy(fx = FxChoice.Rate(it)) }
    }

    companion object {
        /** [due] with each schedule's failure attached to the occurrence its last pass stopped at. */
        fun withErrors(due: List<DueOccurrence>, failures: Map<RecurringId, Failure>): List<DueOccurrence> = due.map { item ->
            val failure = failures[item.schedule]
            if (failure != null && failure.occurrence == item.date) item.copy(autoPostError = failure.error) else item
        }
    }
}
