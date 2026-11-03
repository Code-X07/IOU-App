package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.ledger.model.Change

/** What kind of write is being validated (spec §3.4). */
sealed interface WriteOp {
    /** A write through the api (create, update, delete, restore, archive, member add/remove, merge, unmerge, revert). */
    data class Edit(val changes: List<Change>) : WriteOp

    /** Import of own export: state invariants only. */
    data object Import : WriteOp

    /** Undo of an import: state invariants only. */
    data object UndoImport : WriteOp
}
