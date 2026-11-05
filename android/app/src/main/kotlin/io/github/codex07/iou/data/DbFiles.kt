package io.github.codex07.iou.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `db-state.json` (spec §7.1): the live database, the pre-restore copy, and the ledger generation. */
@Serializable
data class DbPointer(val current: String, val previous: String? = null, val generation: String)

/** `restore-marker.json` (spec §7.1): only chooses the message shown after a crash. [op] is `restore` or `undo`. */
@Serializable
data class RestoreMarker(val op: String, val target: String)

/**
 * The ledger's files in the app's database directory (spec §7.1, ADR-0007): generation-named databases
 * `iou-<uuid>.db`, the pointer, its temp file and the restore marker. The pointer is only ever replaced
 * atomically; that rename is the commit point of a restore or an undo.
 */
class DbFiles(val dir: File) {
    val pointerFile = File(dir, POINTER)
    private val tempPointer = File(dir, "$POINTER.tmp")
    private val markerFile = File(dir, MARKER)
    private val tempMarker = File(dir, "$MARKER.tmp")

    init {
        dir.mkdirs()
    }

    fun database(name: String): File = File(dir, name)

    fun newDatabaseName(uuid: String) = "iou-$uuid.db"

    fun readPointer(): DbPointer? = pointerFile.takeIf { it.exists() }?.let { json.decodeFromString(DbPointer.serializer(), it.readText()) }

    /** Write temp, fsync, rename over the pointer, fsync the directory: one atomic step. */
    fun commitPointer(pointer: DbPointer) {
        pointerFile.writeAtomically(json.encodeToString(DbPointer.serializer(), pointer))
        syncDirectory()
    }

    /** The marker, or null. One that can't be read counts as none: it only chooses a message. */
    fun readMarker(): RestoreMarker? = markerFile.takeIf { it.exists() }?.let {
        try {
            json.decodeFromString(RestoreMarker.serializer(), it.readText())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Written like the pointer (temp file, rename), so a crash never leaves half a marker. */
    fun writeMarker(marker: RestoreMarker) {
        markerFile.writeAtomically(json.encodeToString(RestoreMarker.serializer(), marker))
        syncDirectory()
    }

    /** Deletes the marker and any temp file a crash left while writing it. */
    fun deleteMarker() {
        markerFile.delete()
        tempMarker.delete()
    }

    fun deleteTempPointer() {
        tempPointer.delete()
    }

    /** Every file that belongs to a ledger database (`iou-*.db` and its side files), by the database it belongs to. */
    fun ledgerFiles(): Map<String, List<File>> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("iou-") }
        .groupBy { file -> SIDE_FILES.fold(file.name) { name, suffix -> name.removeSuffix(suffix) } }

    /** Deletes database [name] and its side files (`-journal`, `-wal`, `-shm`, `.lck`). */
    fun deleteDatabase(name: String) {
        File(dir, name).delete()
        for (suffix in SIDE_FILES) File(dir, name + suffix).delete()
    }

    /** Makes a rename durable. Directories can't be opened for sync on every platform (Windows); there it's skipped. */
    private fun syncDirectory() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        }
    }

    companion object {
        const val POINTER = "db-state.json"
        const val MARKER = "restore-marker.json"
        private val SIDE_FILES = listOf("-journal", "-wal", "-shm", ".lck")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Writes [text] to a temp file, fsyncs it and renames it over this file in one atomic step. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.tmp")
    temp.outputStream().use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    Files.move(temp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
