package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.LedgerGeneration
import kotlinx.coroutines.flow.Flow

interface BackupApi {
    /** Emits the current generation, and again after every restore and undo of a restore (spec §6.2). */
    fun observeLedgerGeneration(): Flow<LedgerGeneration>

    fun observeBackupReminder(): Flow<BackupReminder>

    /** Null [passphrase] writes an unencrypted backup (the UI warns first). */
    suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo>

    /**
     * Replaces the whole ledger, all or nothing. Errors: `WrongPassphraseOrDamagedFile`, `CorruptFile`,
     * `SchemaTooNew`, `LimitsExceeded`.
     */
    suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit>

    fun observeUndoRestoreAvailable(): Flow<Boolean>

    suspend fun undoLastRestore(): LedgerResult<Unit>
}
