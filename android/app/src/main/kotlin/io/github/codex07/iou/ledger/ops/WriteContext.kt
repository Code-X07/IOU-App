package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Everything an op may read: the current state, the live generation, the time, an ID source, and [today], the
 * user's calendar date (the store passes `LedgerStore.today()`, in the device zone; index locked decision 6).
 */
data class WriteContext(
    val state: LedgerState,
    val generation: LedgerGeneration,
    val now: Instant,
    val ids: IdGenerator,
    val today: LocalDate = now.atZone(ZoneOffset.UTC).toLocalDate(),
) {
    val meId: PersonId get() = state.me.id
}

/** Rows to write (one revision each) and new FX snapshots; already validated against the resulting state. */
data class WritePlan(
    val changes: List<Change>,
    val snapshots: List<FxSnapshot> = emptyList(),
) {
    val isEmpty: Boolean get() = changes.isEmpty() && snapshots.isEmpty()

    companion object {
        val Empty = WritePlan(emptyList())
    }
}

/** An op's result: the value for the caller (new ID or Unit) and the plan to apply. */
data class Planned<out T>(val value: T, val plan: WritePlan)
