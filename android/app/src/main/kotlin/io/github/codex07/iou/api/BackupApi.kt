package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.LedgerGeneration
import kotlinx.coroutines.flow.Flow

interface BackupApi {
    /** Emits the current generation, and again after every restore and undo of a restore (spec §6.2). */
    fun observeLedgerGeneration(): Flow<LedgerGeneration>

    fun observeBackupReminder(): Flow<BackupReminder>

    /**
     * Null [passphrase] writes an unencrypted backup (the UI warns first). Errors: `InvalidField("passphrase")`
     * for a passphrase shorter than 8 characters (spec §9), `FileAccessFailed`.
     */
    suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo>

    /**
     * Replaces the whole ledger, all or nothing. Errors: `WrongPassphraseOrDamagedFile`, `CorruptFile`,
     * `SchemaTooNew`, `LimitsExceeded`, `FileAccessFailed`, `RestoreFailed`.
     */
    suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit>

    fun observeUndoRestoreAvailable(): Flow<Boolean>

    /** Errors: `NotFound("restore")` when there is nothing to undo, `RestoreFailed`. */
    suspend fun undoLastRestore(): LedgerResult<Unit>

    /**
     * True when the last restore or undo was cut off before its commit point (the app was killed): show "The
     * last restore was interrupted, nothing changed" once, then call [dismissRestoreInterrupted] (spec §7.1).
     */
    fun observeRestoreInterrupted(): Flow<Boolean>

    suspend fun dismissRestoreInterrupted()
}
