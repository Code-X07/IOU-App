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
import io.github.codex07.iou.api.FxQuote
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
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.Settings
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FakeFxApi : FxApi {
    override suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote> {
        val rate = FakeRates.rate(from, to) ?: return LedgerResult.Err(LedgerError.RateRequired(from, to))
        return LedgerResult.Ok(FxQuote(from, to, rate, date, FxSource.ECB_FRANKFURTER, isStale = false))
    }

    override fun userRate(date: LocalDate, from: CurrencyCode, to: CurrencyCode, rate: String): LedgerResult<FxQuote> =
        FxConverter.validateRate(rate).map { FxQuote(from, to, rate.trim(), date, FxSource.USER_ENTERED, isStale = false) }

    override fun rateFromCharged(date: LocalDate, paid: Money, charged: Money): LedgerResult<FxQuote> {
        if (paid.amountMinor <= 0 || charged.amountMinor <= 0) return LedgerResult.Err(LedgerError.InvalidAmount)
        val rate = FxConverter.deriveRate(paid, charged)
        return LedgerResult.Ok(FxQuote(paid.currency, charged.currency, rate, date, FxSource.BANK_ACTUAL, isStale = false))
    }
}

/** Simulated backups (spec §6.3): restoring brings back the current data under a fresh generation. */
class FakeBackupApi(private val ledger: InMemoryLedger) : BackupApi {
    private val passphrases = ConcurrentHashMap<String, String>()

    override fun observeLedgerGeneration(): Flow<LedgerGeneration> = ledger.snapshot.map { it.generation }.distinctUntilChanged()

    override fun observeBackupReminder(): Flow<BackupReminder> = ledger.snapshot.map { snapshot ->
        val last = snapshot.lastBackupAt
        BackupReminder(due = last == null || Duration.between(last, ledger.now()) > Duration.ofDays(30), lastBackupAt = last)
    }.distinctUntilChanged()

    override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
        val now = ledger.now()
        passphrases[target.uri] = passphrase?.concatToString().orEmpty()
        ledger.update { it.copy(lastBackupAt = now) }
        return LedgerResult.Ok(BackupInfo(encrypted = passphrase != null, createdAt = now))
    }

    override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> {
        val stored = passphrases[source.uri] ?: return LedgerResult.Err(LedgerError.CorruptFile("No IOU backup at ${source.uri}"))
        if (stored != passphrase?.concatToString().orEmpty()) return LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        ledger.update {
            it.copy(generation = ledger.newGeneration(), ledgerSeq = it.ledgerSeq + 1, preRestore = it.state to it.revisions)
        }
        return LedgerResult.Ok(Unit)
    }

    override fun observeUndoRestoreAvailable(): Flow<Boolean> = ledger.snapshot.map { it.preRestore != null }.distinctUntilChanged()

    override suspend fun undoLastRestore(): LedgerResult<Unit> {
        var undone = false
        ledger.update { snapshot ->
            val previous = snapshot.preRestore ?: return@update snapshot
            undone = true
            snapshot.copy(
                state = previous.first, revisions = previous.second, preRestore = snapshot.state to snapshot.revisions,
                generation = ledger.newGeneration(), ledgerSeq = snapshot.ledgerSeq + 1,
            )
        }
        return if (undone) LedgerResult.Ok(Unit) else LedgerResult.Err(LedgerError.NotFound("restore"))
    }
}

/** Simulated import/export (see this plan's "Decisions"): canned previews, real staleness checks. */
class FakeImportExportApi(private val ledger: InMemoryLedger) : ImportExportApi {

    override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> {
        if (request.shape == ExportShape.NORMALIZED && request.scope != ExportScope.FullLedger) {
            return LedgerResult.Err(LedgerError.InvalidField("scope"))
        }
        return LedgerResult.Ok(ExportInfo(rowCount = ledger.snapshot.value.state.entries.size))
    }

    override suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview> {
        val name = source.uri.substringAfterLast('/')
        if ("corrupt" in name) return LedgerResult.Err(LedgerError.CorruptFile("Not an IOU export: $name"))
        val identity = if ("foreign" in name) ImportIdentity.NeedsConfirmation(PersonId("foreign-me")) else ImportIdentity.Same
        return LedgerResult.Ok(cannedPreview(name, identity))
    }

    override suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview> {
        // A preview parsed before a restore belongs to the replaced ledger, whatever the answer.
        if (preview.generation != ledger.snapshot.value.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val pending = preview.identity as? ImportIdentity.NeedsConfirmation ?: return LedgerResult.Ok(preview)
        if (!accept) return LedgerResult.Err(LedgerError.ForeignLedger)
        return LedgerResult.Ok(cannedPreview(preview.fileName, ImportIdentity.Mapped(pending.sourceMe)))
    }

    override fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview = preview

    /** Rebuilds the preview against the current ledger; rows that are new or whose diff changed are flagged. */
    override suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview> {
        if (preview.generation != ledger.snapshot.value.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val previous = preview.rows.associateBy { it.key }
        val fresh = cannedPreview(preview.fileName, preview.identity)
        return LedgerResult.Ok(
            fresh.copy(id = preview.id, rows = fresh.rows.map { it.copy(changedSinceLastPreview = previous[it.key]?.changes != it.changes) }),
        )
    }

    override suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId> {
        var result: LedgerResult<ImportBatchId> = LedgerResult.Err(LedgerError.ImportPreviewStale)
        ledger.update { snapshot ->
            when {
                preview.generation != snapshot.generation -> snapshot.also { result = LedgerResult.Err(LedgerError.LedgerReplaced) }
                preview.ledgerSeq != snapshot.ledgerSeq -> snapshot.also { result = LedgerResult.Err(LedgerError.ImportPreviewStale) }
                preview.identity is ImportIdentity.NeedsConfirmation -> snapshot.also { result = LedgerResult.Err(LedgerError.ForeignLedger) }
                preview.invalidRows.isNotEmpty() && !choices.skipInvalidRows ->
                    snapshot.also { result = LedgerResult.Err(LedgerError.InvalidField("invalidRows")) }
                else -> {
                    val id = ImportBatchId(ledger.ids.next())
                    result = LedgerResult.Ok(id)
                    val batch = ImportBatch(id, ImportSource.OWN_NORMALIZED_CSV, preview.fileName, ledger.now(), preview.counts.new + choices.takeIncoming.size)
                    snapshot.copy(importBatches = snapshot.importBatches + batch, ledgerSeq = snapshot.ledgerSeq + 1)
                }
            }
        }
        return result
    }

    override fun observeImportBatches(): Flow<List<ImportBatchView>> = ledger.snapshot.map { snapshot ->
        snapshot.importBatches.sortedByDescending { it.importedAt }
            .map { ImportBatchView(it.id, it.fileName, it.importedAt, it.rowCount, it.undoneAt) }
    }.distinctUntilChanged()

    override suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult> {
        var found = false
        ledger.update { snapshot ->
            found = snapshot.importBatches.any { it.id == id }
            snapshot.copy(
                importBatches = snapshot.importBatches.map { if (it.id == id && it.undoneAt == null) it.copy(undoneAt = ledger.now()) else it },
                ledgerSeq = if (found) snapshot.ledgerSeq + 1 else snapshot.ledgerSeq,
            )
        }
        return if (found) LedgerResult.Ok(UndoImportResult(emptyList())) else LedgerResult.Err(LedgerError.NotFound(id.value))
    }

    /** The canned preview of [fileName] against the current ledger; nothing but the identity question until confirmed. */
    private fun cannedPreview(fileName: String, identity: ImportIdentity): ImportPreview {
        val snapshot = ledger.snapshot.value
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
            id = ledger.ids.next(),
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

class FakeSettingsApi(private val ledger: InMemoryLedger) : SettingsApi {
    private val lockRequired = MutableStateFlow(ledger.snapshot.value.settings.appLockEnabled)

    override fun observeSettings(): Flow<Settings> = ledger.snapshot.map { it.settings }.distinctUntilChanged()

    override suspend fun setHomeCurrency(currency: CurrencyCode) = change { it.copy(homeCurrency = currency) }

    override suspend fun setAppLockEnabled(enabled: Boolean): LedgerResult<Unit> {
        if (!enabled) lockRequired.value = false
        return change { it.copy(appLockEnabled = enabled) }
    }

    override suspend fun setLockTimeout(timeout: LockTimeout) = change { it.copy(lockTimeout = timeout) }

    override suspend fun setScreenProtection(enabled: Boolean) = change { it.copy(screenProtection = enabled) }

    override suspend fun setBackupEncryptionDefault(enabled: Boolean) = change { it.copy(backupEncryptionDefault = enabled) }

    override fun observeLockRequired(): Flow<Boolean> = lockRequired

    override fun markUnlocked() {
        lockRequired.value = false
    }

    private fun change(transform: (Settings) -> Settings): LedgerResult<Unit> {
        ledger.update { it.copy(settings = transform(it.settings)) }
        return LedgerResult.Ok(Unit)
    }
}
