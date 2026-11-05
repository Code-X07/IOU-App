package io.github.codex07.iou.data

import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.BackupInfo
import io.github.codex07.iou.api.BackupReminder
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fx.FxService
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.store.BackupRules
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.LedgerStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * JSON backups of the whole database (spec §7.1). A backup is written from the store's current snapshot,
 * which is exactly what the database holds, plus the rate cache. A restore reads and validates the whole
 * file before [RestoreProcedure] touches any database file.
 */
class RealBackupApi(
    private val store: LedgerStore,
    private val device: DeviceSettingsStore,
    private val documents: DocumentStore,
    private val format: BackupFormat,
    private val fx: FxService,
    private val procedure: RestoreProcedure,
    private val interrupted: MutableStateFlow<Boolean>,
    private val appVersion: String,
) : BackupApi {

    override fun observeLedgerGeneration(): Flow<LedgerGeneration> = store.snapshot.map { it.generation }.distinctUntilChanged()

    override fun observeBackupReminder(): Flow<BackupReminder> = BackupRules.reminder(device, store::now)

    override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
        BackupRules.checkPassphrase(passphrase)?.let { return LedgerResult.Err(it) }
        val now = store.now()
        val rows = LedgerRows.of(store.loaded().content, fx.cachedRates())
        val written = withContext(Dispatchers.IO) {
            val file = format.write(rows, appVersion, now, passphrase)
            access(target) { documents.openWrite(target).use { it.write(file) } }
        }
        if (written is LedgerResult.Err) return written
        device.update { it.copy(lastManualBackupAt = now) }
        return LedgerResult.Ok(BackupInfo(encrypted = passphrase != null, createdAt = now))
    }

    override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> = withContext(Dispatchers.IO) {
        val file = when (val read = access(source) { documents.openRead(source).use { it.readAtMost(format.maxFileBytes) } }) {
            is LedgerResult.Err -> return@withContext read
            is LedgerResult.Ok -> read.value
                ?: return@withContext LedgerResult.Err(LedgerError.LimitsExceeded("Backup larger than ${format.maxFileBytes} bytes"))
        }
        when (val backup = format.read(file, passphrase)) {
            is LedgerResult.Err -> backup
            is LedgerResult.Ok -> procedure.restore(backup.value)
        }
    }

    override fun observeUndoRestoreAvailable(): Flow<Boolean> = procedure.undoAvailable

    override suspend fun undoLastRestore(): LedgerResult<Unit> = withContext(Dispatchers.IO) { procedure.undo() }

    override fun observeRestoreInterrupted(): Flow<Boolean> = interrupted

    override suspend fun dismissRestoreInterrupted() {
        interrupted.value = false
    }

    /** Runs a document read or write; a missing document or a revoked permission becomes `FileAccessFailed`. */
    private inline fun <T> access(ref: DocumentRef, block: () -> T): LedgerResult<T> = try {
        LedgerResult.Ok(block())
    } catch (e: IOException) {
        LedgerResult.Err(LedgerError.FileAccessFailed("${ref.uri}: ${e.message}"))
    } catch (e: SecurityException) {
        LedgerResult.Err(LedgerError.FileAccessFailed("${ref.uri}: ${e.message}"))
    }
}
