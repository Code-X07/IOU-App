package io.github.codex07.iou.data

import io.github.codex07.iou.store.NotifiedRecord
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the notifier last drew (spec §5.7): `noBackupFilesDir/recurring-notified.json`, device-local, never backed
 * up, replaced atomically. Unreadable reads as empty, which at worst repeats a notification.
 */
class RecurringNotifiedFile(private val file: File) {

    fun read(): NotifiedRecord = if (!file.exists()) NotifiedRecord() else try {
        Json.decodeFromString(Stored.serializer(), file.readText()).let { NotifiedRecord(it.due, it.detailedPosted) }
    } catch (_: IllegalArgumentException) { // includes SerializationException
        NotifiedRecord()
    } catch (_: IOException) {
        NotifiedRecord()
    }

    fun write(record: NotifiedRecord) {
        file.parentFile?.mkdirs()
        file.writeAtomically(Json.encodeToString(Stored.serializer(), Stored(record.due, record.detailedPosted)))
    }

    @Serializable
    private data class Stored(val due: Map<String, String> = emptyMap(), val detailedPosted: Boolean = false)
}
