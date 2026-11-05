package io.github.codex07.iou.fake

import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.BackupInfo
import io.github.codex07.iou.api.BackupReminder
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportInfo
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.ImportBatchView
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportCounts
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRow
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.store.BackupRules
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreFxApi
import io.github.codex07.iou.store.StoreSnapshot
import io.github.codex07.iou.store.StoreWrite
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** FX on the fixed demo rates ([FakeRates.quote]). */
class FakeFxApi : FxApi by StoreFxApi({ date, from, to -> FakeRates.quote(date, from, to) })

/** Simulated backups (spec §6.3): restoring brings back the current data under a fresh generation. */
class FakeBackupApi(private val store: LedgerStore, private val device: DeviceSettingsStore) : BackupApi {
    private val passphrases = ConcurrentHashMap<String, String>()
    private val preRestore = MutableStateFlow<LedgerContent?>(null)

    override fun observeLedgerGeneration(): Flow<LedgerGeneration> = store.snapshot.map { it.generation }.distinctUntilChanged()

    override fun observeBackupReminder(): Flow<BackupReminder> = BackupRules.reminder(device, store::now)

    override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
        BackupRules.checkPassphrase(passphrase)?.let { return LedgerResult.Err(it) }
        val now = store.now()
        passphrases[target.uri] = passphrase?.concatToString().orEmpty()
        device.update { it.copy(lastManualBackupAt = now) }
        return LedgerResult.Ok(BackupInfo(encrypted = passphrase != null, createdAt = now))
    }

    override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> {
        val stored = passphrases[source.uri] ?: return LedgerResult.Err(LedgerError.FileAccessFailed("No document at ${source.uri}"))
        if (stored != passphrase?.concatToString().orEmpty()) return LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        store.exclusive { live, publish ->
            val current = checkNotNull(live).content
            preRestore.value = current
            publish(current, store.newGeneration())
        }
        return LedgerResult.Ok(Unit)
    }

    override fun observeUndoRestoreAvailable(): Flow<Boolean> = preRestore.map { it != null }.distinctUntilChanged()

    override suspend fun undoLastRestore(): LedgerResult<Unit> = store.exclusive { live, publish ->
        val previous = preRestore.value ?: return@exclusive LedgerResult.Err(LedgerError.NotFound("restore"))
        preRestore.value = checkNotNull(live).content
        publish(previous, store.newGeneration())
        LedgerResult.Ok(Unit)
    }

    /** The fake's restores can't be interrupted. */
    override fun observeRestoreInterrupted(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun dismissRestoreInterrupted() = Unit
}

/** Simulated import/export (M0 Part 7 "Decisions"): canned previews, real staleness checks. */
class FakeImportExportApi(private val store: LedgerStore) : ImportExportApi {

    override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> {
        if (request.shape == ExportShape.NORMALIZED && request.scope != ExportScope.FullLedger) {
            return LedgerResult.Err(LedgerError.InvalidField("scope"))
        }
        return LedgerResult.Ok(ExportInfo(rowCount = store.loaded().state.entries.size))
    }

    override suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview> {
        val name = source.uri.substringAfterLast('/')
        if ("corrupt" in name) return LedgerResult.Err(LedgerError.CorruptFile("Not an IOU export: $name"))
        val identity = if ("foreign" in name) ImportIdentity.NeedsConfirmation(PersonId("foreign-me")) else ImportIdentity.Same
        return LedgerResult.Ok(cannedPreview(store.loaded(), name, identity))
    }

    override suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview> {
        val snapshot = store.loaded()
        // A preview parsed before a restore belongs to the replaced ledger, whatever the answer.
        if (preview.generation != snapshot.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val pending = preview.identity as? ImportIdentity.NeedsConfirmation ?: return LedgerResult.Ok(preview)
        if (!accept) return LedgerResult.Err(LedgerError.ForeignLedger)
        return LedgerResult.Ok(cannedPreview(snapshot, preview.fileName, ImportIdentity.Mapped(pending.sourceMe)))
    }

    override fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview = preview

    /** Rebuilds the preview against the current ledger; rows that are new or whose diff changed are flagged. */
    override suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview> {
        val snapshot = store.loaded()
        if (preview.generation != snapshot.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val previous = preview.rows.associateBy { it.key }
        val fresh = cannedPreview(snapshot, preview.fileName, preview.identity)
        return LedgerResult.Ok(
            fresh.copy(id = preview.id, rows = fresh.rows.map { it.copy(changedSinceLastPreview = previous[it.key]?.changes != it.changes) }),
        )
    }

    override suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId> =
        store.writeWith { snapshot, context ->
            when {
                preview.generation != snapshot.generation -> LedgerResult.Err(LedgerError.LedgerReplaced)
                preview.ledgerSeq != snapshot.ledgerSeq -> LedgerResult.Err(LedgerError.ImportPreviewStale)
                preview.identity is ImportIdentity.NeedsConfirmation -> LedgerResult.Err(LedgerError.ForeignLedger)
                preview.invalidRows.isNotEmpty() && !choices.skipInvalidRows -> LedgerResult.Err(LedgerError.InvalidField("invalidRows"))
                else -> {
                    val id = ImportBatchId(context.ids.next())
                    val rowCount = preview.counts.new + choices.takeIncoming.size
                    val batch = ImportBatch(id, ImportSource.OWN_NORMALIZED_CSV, preview.fileName, context.now, rowCount)
                    LedgerResult.Ok(StoreWrite(id, WritePlan.Empty, importBatches = listOf(batch)))
                }
            }
        }

    override fun observeImportBatches(): Flow<List<ImportBatchView>> = store.readSnapshot { snapshot ->
        snapshot.importBatches.sortedByDescending { it.importedAt }
            .map { ImportBatchView(it.id, it.fileName, it.importedAt, it.rowCount, it.undoneAt) }
    }

    override suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult> =
        store.writeWith { snapshot, context ->
            val batch = snapshot.importBatches.firstOrNull { it.id == id }
                ?: return@writeWith LedgerResult.Err(LedgerError.NotFound(id.value))
            val undone = if (batch.undoneAt == null) listOf(batch.copy(undoneAt = context.now)) else emptyList()
            LedgerResult.Ok(StoreWrite(UndoImportResult(emptyList()), WritePlan.Empty, importBatches = undone))
        }

    /** The canned preview of [fileName] against [snapshot]; nothing but the identity question until confirmed. */
    private fun cannedPreview(snapshot: StoreSnapshot, fileName: String, identity: ImportIdentity): ImportPreview {
        val withRows = identity !is ImportIdentity.NeedsConfirmation
        val invalid = withRows && "invalid" in fileName
        val conflict = if (withRows) snapshot.state.transfers.values.firstOrNull { !it.isDeleted } else null
        val rows = listOfNotNull(conflict).map {
            ImportRow(
                key = ImportRowKey(EntityType.TRANSFER, it.id.value),
                label = "Transfer of ${it.amount} on ${it.occurredOn}",
                changes = listOf(FieldChange("note", it.note, "Imported note")),
                choice = ImportRowChoice.KeepOrTake,
                changedSinceLastPreview = false,
            )
        }
        val invalidRows = if (invalid) listOf(InvalidRow("transfers", 3, "amount has too many decimals", LedgerError.TooManyDecimals(2))) else emptyList()
        return ImportPreview(
            id = store.ids.next(),
            fileName = fileName,
            generation = snapshot.generation,
            ledgerSeq = snapshot.ledgerSeq,
            identity = identity,
            counts = ImportCounts(new = if (withRows) 2 else 0, conflicts = rows.size, duplicates = if (withRows) 5 else 0, invalid = invalidRows.size),
            rows = rows,
            invalidRows = invalidRows,
            balanceChanges = emptyList(),
        )
    }
}
