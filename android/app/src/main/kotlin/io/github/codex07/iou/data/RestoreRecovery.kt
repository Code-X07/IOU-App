package io.github.codex07.iou.data

/**
 * Runs on every start before Room opens a database (spec §7.1, "Crash recovery"). It deletes every
 * ledger database the pointer does not name, plus a leftover temp pointer, and reads and deletes the restore
 * marker (one it can't read is dropped: it only chooses a message).
 * Nothing is rolled back or inferred from which files exist: the pointer alone says what is live.
 */
object RestoreRecovery {

    /** [interrupted]: a restore or undo was cut off before its commit point; tell the user once that nothing changed. */
    data class Result(val interrupted: Boolean)

    fun run(files: DbFiles): Result {
        val pointer = files.readPointer()
        val keep = setOfNotNull(pointer?.current, pointer?.previous)
        files.deleteTempPointer()
        for ((database, members) in files.ledgerFiles()) {
            if (database !in keep) members.forEach { it.delete() }
        }
        val marker = files.readMarker()
        files.deleteMarker()
        return Result(interrupted = marker != null && pointer?.current != marker.target)
    }
}
