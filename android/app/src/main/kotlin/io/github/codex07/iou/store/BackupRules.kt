package io.github.codex07.iou.store

import io.github.codex07.iou.api.BackupReminder
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Documents the user picks (spec §7): the Storage Access Framework on the device, plain files in tests. */
interface DocumentStore {
    /** Throws `IOException` when [ref] can't be opened. */
    fun openRead(ref: DocumentRef): InputStream

    /** Replaces [ref]'s content. Throws `IOException` when it can't be opened. */
    fun openWrite(ref: DocumentRef): OutputStream
}

/** Reads the whole stream, or returns null once it holds more than [max] bytes. */
fun InputStream.readAtMost(max: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > max) return null
        out.write(chunk, 0, read)
    }
}

/** Backup rules both flavors share (spec §7.1, §9). */
object BackupRules {
    const val MIN_PASSPHRASE = 8
    val REMINDER_AFTER: Duration = Duration.ofDays(30)

    /** `InvalidField("passphrase")` for a passphrase shorter than [MIN_PASSPHRASE]; null means none (unencrypted). */
    fun checkPassphrase(passphrase: CharArray?): LedgerError? =
        if (passphrase != null && passphrase.size < MIN_PASSPHRASE) LedgerError.InvalidField("passphrase") else null

    /** Due when no manual backup was ever made or the last one is older than [REMINDER_AFTER]. */
    fun reminder(device: DeviceSettingsStore, now: () -> Instant): Flow<BackupReminder> = device.settings.map { settings ->
        val last = settings.lastManualBackupAt
        BackupReminder(due = last == null || Duration.between(last, now()) > REMINDER_AFTER, lastBackupAt = last)
    }.distinctUntilChanged()
}
