package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import java.io.File

/**
 * Holds the open database named by the pointer (ADR-0007). [RoomJournal] asks it for the live database
 * on every append, so the restore (Part 5) can swap databases under the store's write lock.
 */
class LedgerDatabases(
    val files: DbFiles,
    private val open: (File) -> IouDatabase,
    private val ids: IdGenerator,
) : DatabaseProvider {
    @Volatile private var live: IouDatabase? = null

    /**
     * Opens the database the pointer names and returns the pointer's generation. On first launch it commits
     * a pointer to a new database first (Room creates the file on first use).
     */
    fun openCurrent(): LedgerGeneration {
        val pointer = files.readPointer()
            ?: DbPointer(current = files.newDatabaseName(ids.next()), generation = ids.next()).also(files::commitPointer)
        live = open(files.database(pointer.current))
        return LedgerGeneration(pointer.generation)
    }

    override fun database(): IouDatabase = checkNotNull(live) { "No ledger database is open" }

    fun close() {
        live?.close()
        live = null
    }
}

/** The installation's device ID (spec §3.1): created once in `noBackupFilesDir`, never backed up or restored. */
object DeviceIdFile {
    fun read(file: File, ids: IdGenerator): DeviceId {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(ids.next())
        }
        return DeviceId(file.readText().trim())
    }
}
