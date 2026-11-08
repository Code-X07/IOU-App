package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.money.Money
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** One occurrence the runner posted: what a "posted" notification lists (spec §5.7). */
data class PostedOccurrence(
    val schedule: RecurringId,
    val description: String,
    val occurrence: LocalDate,
    /** The posted amount, as paid. */
    val amount: Money,
)

/**
 * Where the runner reports after every pass (spec §5.7 Notifications): [due] is the whole due list with failures
 * attached, [posted] what this pass posted. The real flavor shows Android notifications; the fake shows none.
 */
fun interface RecurringNotices {
    suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>)

    /**
     * Emits when what is drawn must change although the ledger did not (app lock switched on or off); the runner
     * reports again at once on each emission ([RecurringRunner.redraw]), without a pass.
     */
    val redraw: Flow<Unit> get() = emptyFlow()

    companion object {
        val None = RecurringNotices { _, _ -> }
    }
}
