package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.settings.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltViewModel
class DataManagementViewModel @Inject constructor(
    private val backup: BackupApi, private val transfer: ImportExportApi, private val settings: SettingsApi,
    people: PeopleApi, groups: GroupsApi, @param:FileIo private val io: CoroutineDispatcher, val services: DataServices,
    private val replacements: LedgerReplacements,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DataState())
    val state = mutableState.asStateFlow()
    val catalog = combine(settings.observeSettings(), backup.observeBackupReminder(), backup.observeUndoRestoreAvailable(),
        transfer.observeImportBatches(), combine(people.observePeople(true), groups.observeGroups(true)) { p, g -> p to g }) { config, reminder, undo, batches, records ->
        DataCatalog(config, reminder, undo, batches, records.first, records.second)
    }.catch { mutableState.update { it.copy(message = "Data-management status could not be loaded.") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private var generation: LedgerGeneration? = null
    private var epoch = 0L
    private var sequence = 0L
    private var initialized = false
    private var job: Job? = null
    private var mutating = false
    private var replacing = false
    private var secret: CharArray? = null
    private var restoreSource: DocumentRef? = null
    private var rawPreview: ImportPreview? = null
    private var exportRequest: ExportRequest? = null
    private var undoBatch: ImportBatchId? = null
    private val watcher = viewModelScope.launch {
        backup.observeLedgerGeneration().catch {
            epoch++; job?.cancel(); clearPrepared(); mutableState.value = DataState(loading = false, mode = state.value.mode, error = LedgerError.LedgerReplaced)
        }.collect { current ->
            if (generation != null && current != generation) {
                epoch++; if (!replacing) job?.cancel()
                // A restore this ViewModel started stays busy until its own call returns; its outcome is delivered then.
                clearPrepared(); mutableState.value = DataState(loading = false, mode = state.value.mode, busy = replacing,
                    message = if (replacing) null else "The ledger changed. Old selections, confirmations and import previews were discarded.")
            }
            generation = current
        }
    }
    fun open(mode: String) {
        if (initialized) return
        initialized = true; mutableState.update { it.copy(mode = mode) }
        viewModelScope.launch {
            try { val config = settings.observeSettings().first(); mutableState.update { it.copy(loading = false, encrypted = config.backupEncryptionDefault) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, message = "Backup preferences are unavailable. Close and reopen this page.") } }
        }
    }
    private suspend fun <T> file(block: suspend () -> LedgerResult<T>): LedgerResult<T> = withContext(io) { block() }
    private fun clearSecret() { secret?.fill('\u0000'); secret = null }
    private fun dropPreview() { rawPreview?.let(transfer::discardImport); rawPreview = null }
    private fun clearPrepared() { clearSecret(); restoreSource = null; dropPreview(); exportRequest = null; undoBatch = null }
    private fun fail(error: LedgerError) { mutableState.update { it.copy(error = error) } }
    private fun available() = !state.value.loading && !state.value.busy && state.value.picker == null && !state.value.exit && !replacements.running.value
    /** Calls [call] and hands its preview to [accept]; a preview that arrives after cancellation is discarded instead of lost. */
    private suspend fun <T> producing(call: suspend () -> LedgerResult<ImportPreview>, accept: (ImportPreview) -> T): LedgerResult<T> {
        var pending: ImportPreview? = null
        try { return file { call().also { if (it is LedgerResult.Ok) pending = it.value } }.map { pending = null; accept(it) } }
        finally { pending?.let(transfer::discardImport) }
    }
    private fun replace(success: String, block: suspend () -> LedgerResult<Unit>, wipe: CharArray? = null) {
        if (!available()) { wipe?.fill('\u0000'); return }
        mutating = true; replacing = true
        mutableState.update { it.copy(busy = true, error = null, message = null, confirmation = null) }
        val started = replacements.run(block, success) { result ->
            replacing = false; mutating = false
            if (!state.value.exit) when (result) {
                is LedgerResult.Ok -> { clearPrepared(); mutableState.update { it.copy(busy = false, message = success, restoreSelected = false) } }
                is LedgerResult.Err -> mutableState.update { it.copy(busy = false, error = result.error) }
                null -> mutableState.update { it.copy(busy = false, message = "The restore could not be confirmed. Review your records and document access before retrying.") }
            }
        }
        if (!started) { replacing = false; mutating = false; wipe?.fill('\u0000'); mutableState.update { it.copy(busy = false, message = "Another restore is still running.") } }
    }
    private fun runOperation(write: Boolean = false, block: suspend () -> LedgerResult<Unit>) {
        if (!available()) return
        val version = epoch; mutating = write
        mutableState.update { it.copy(busy = true, error = null, message = null, confirmation = null) }
        job = viewModelScope.launch {
            try {
                val result = block(); currentCoroutineContext().ensureActive()
                if (version == epoch) when (result) {
                    is LedgerResult.Ok -> mutableState.update { it.copy(busy = false) }
                    is LedgerResult.Err -> mutableState.update { it.copy(busy = false, error = result.error) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (version == epoch) mutableState.update { it.copy(busy = false, message = "The file operation could not be confirmed. Review your records and document access before retrying.") } }
            finally { mutating = false }
        }
    }
    fun encryption(value: Boolean) { if (available()) { clearSecret(); mutableState.update { it.copy(encrypted = value, error = null) } } }
    fun exportChange(update: (ExportFields) -> ExportFields) { if (available()) mutableState.update { it.copy(export = update(it.export), error = null) } }
    private fun picker(operation: DocumentOperation, create: Boolean, mime: String, filename: String) {
        mutableState.update { it.copy(error = null, message = null, picker = DocumentPickerRequest(++sequence, operation, create,
            if (create && services.simulated) "text/plain" else mime,
            if (create && services.simulated) "IOU-SIMULATION-EMPTY-${operation.name.lowercase()}.txt" else filename)) }
    }
    fun beginBackup(phrase: CharArray, confirmation: CharArray, allowPlain: Boolean) {
        try {
            if (!available()) return
            clearSecret()
            if (state.value.encrypted) {
                if (phrase.size < 8) { fail(LedgerError.InvalidField("passphrase")); return }
                if (!phrase.contentEquals(confirmation)) { fail(LedgerError.InvalidField("passphraseConfirmation")); return }
                secret = phrase.copyOf()
            } else if (!allowPlain) { fail(LedgerError.InvalidField("unencryptedWarning")); return }
            picker(DocumentOperation.BACKUP, true, if (state.value.encrypted) "application/octet-stream" else "application/json", "iou-backup.${if (state.value.encrypted) "ioub" else "json"}")
        } finally { phrase.fill('\u0000'); confirmation.fill('\u0000') }
    }
    fun pickRestore() { if (available()) { clearSecret(); picker(DocumentOperation.RESTORE, false, "*/*", "") } }
    fun pickImport() { if (available()) picker(DocumentOperation.IMPORT, false, "application/zip", "") }
    fun pickExport() {
        if (!available()) return
        when (val request = state.value.export.request()) {
            is LedgerResult.Err -> fail(request.error)
            is LedgerResult.Ok -> { exportRequest = request.value; picker(DocumentOperation.EXPORT, true, state.value.export.mime(), "iou-export.${state.value.export.extension()}") }
        }
    }
    fun pickerLaunched(id: Long) { mutableState.update { if (it.picker?.id == id) it.copy(picker = it.picker?.copy(launched = true)) else it } }
    fun documentFailure() { clearSecret(); mutableState.update { it.copy(picker = null, message = "Document access failed. Choose an accessible document and try again.") } }
    fun documentResult(uri: String?) {
        if (state.value.exit) { clearSecret(); return }
        val request = state.value.picker ?: return
        mutableState.update { it.copy(picker = null) }
        if (uri == null) { clearSecret(); mutableState.update { it.copy(message = "Document selection cancelled.") }; return }
        val document = DocumentRef(uri)
        when (request.operation) {
            DocumentOperation.BACKUP -> {
                val password = secret; secret = null
                runOperation(write = true) {
                    try { file { backup.createBackup(document, password) }.map {
                        mutableState.update { s -> s.copy(message = if (services.simulated) "Backup simulation completed. No financial data was written; the selected empty document is not a usable backup." else "Backup saved to your chosen document.") }
                    } } finally { password?.fill('\u0000') }
                }
            }
            DocumentOperation.RESTORE -> { restoreSource = document; mutableState.update { it.copy(restoreSelected = true, message = "Backup selected. Enter a passphrase if needed, then review the replacement confirmation.") } }
            DocumentOperation.IMPORT -> parse(document)
            DocumentOperation.EXPORT -> {
                val export = exportRequest ?: return
                runOperation(write = true) { file { transfer.export(export, document) }.map { info -> mutableState.update { it.copy(message = if (services.simulated)
                    "Export simulation reports ${info.rowCount} rows. No financial data was written to the selected document." else "Exported ${info.rowCount} rows.") } } }
            }
        }
    }
    fun requestRestore(phrase: CharArray) {
        try { if (!available() || restoreSource == null) return; clearSecret(); secret = phrase.takeIf { it.isNotEmpty() }?.copyOf(); mutableState.update { it.copy(confirmation = DataConfirmation.RESTORE, error = null) } }
        finally { phrase.fill('\u0000') }
    }
    fun requestUndoRestore() { if (available()) mutableState.update { it.copy(confirmation = DataConfirmation.UNDO_RESTORE, error = null) } }
    fun requestUndoImport(batch: ImportBatchId) { if (available()) { undoBatch = batch; mutableState.update { it.copy(confirmation = DataConfirmation.UNDO_IMPORT, error = null) } } }
    fun dismissConfirmation() { if (!state.value.busy) { clearSecret(); undoBatch = null; mutableState.update { it.copy(confirmation = null) } } }
    fun confirm() {
        if (!available()) return
        when (state.value.confirmation) {
            DataConfirmation.RESTORE -> {
                val source = restoreSource ?: return; val password = secret; secret = null
                replace(if (services.simulated) "Restore simulation completed. The backend changed ledger generation; no on-disk backup was read." else "Restore complete. Your ledger was replaced with the backup.",
                    { try { file { backup.restoreBackup(source, password) } } finally { password?.fill('\u0000') } }, wipe = password)
            }
            DataConfirmation.UNDO_RESTORE -> replace(if (services.simulated) "Undo-restore simulation completed. This is another ledger replacement." else "Undo complete. Your ledger was replaced with its state before the last restore.",
                { file { backup.undoLastRestore() } })
            DataConfirmation.COMMIT_IMPORT -> commit()
            DataConfirmation.UNDO_IMPORT -> {
                val id = undoBatch ?: return
                runOperation(write = true) { file { transfer.undoImport(id) }.map { result -> mutableState.update { it.copy(undoResult = result,
                    message = if (services.simulated) "Import undo simulated. Review the backend result below." else if (result.complete) "Import undo complete." else "Import undo was partial. Some rows were skipped.") } } }
            }
            null -> Unit
        }
    }
    private fun publish(preview: ImportPreview) {
        rawPreview?.takeIf { it.id != preview.id }?.let(transfer::discardImport); rawPreview = preview
        val visible = if (preview.identity is ImportIdentity.NeedsConfirmation) preview.copy(counts = ImportCounts(0, 0, 0, 0), rows = emptyList(), invalidRows = emptyList(), balanceChanges = emptyList()) else preview
        mutableState.update { it.copy(preview = visible, choices = ImportChoices(), undoResult = null) }
    }
    private fun parse(source: DocumentRef) = runOperation { producing({ transfer.parseImport(source) }) { publish(it) } }
    fun simulateImport(scenario: String) {
        if (!services.simulated) return
        if (!available()) return
        parse(DocumentRef("simulation/${scenario.filter { it.isLetterOrDigit() }}.zip"))
    }
    fun confirmIdentity(accept: Boolean) {
        val preview = rawPreview?.takeIf { it.identity is ImportIdentity.NeedsConfirmation } ?: return
        runOperation {
            when (val result = producing({ transfer.confirmIdentity(preview, accept) }) { publish(it) }) {
                is LedgerResult.Err -> if (!accept && result.error == LedgerError.ForeignLedger) {
                    rawPreview = null; mutableState.update { it.copy(preview = null, message = "Identity declined. Nothing was imported.") }; LedgerResult.Ok(Unit)
                } else result
                is LedgerResult.Ok -> LedgerResult.Ok(Unit)
            }
        }
    }
    fun choices(update: (ImportChoices) -> ImportChoices) {
        if (!available() || rawPreview?.identity is ImportIdentity.NeedsConfirmation) return
        val raw = rawPreview ?: return
        val candidate = update(state.value.choices)
        val effective = transfer.applyChoices(raw, candidate)
        val allowed = effective.rows.filter { it.choice == ImportRowChoice.KeepOrTake }.map { it.key }.toSet()
        val clean = candidate.copy(takeIncoming = candidate.takeIncoming.intersect(allowed))
        mutableState.update { it.copy(preview = transfer.applyChoices(raw, clean), choices = clean,
            error = if (it.error == LedgerError.ImportPreviewStale) it.error else null) }
    }
    fun refresh() {
        val old = rawPreview ?: return; val choices = state.value.choices
        runOperation { producing({ transfer.refreshPreview(old) }) { fresh ->
            val unchanged = fresh.rows.filter { row -> row.choice == ImportRowChoice.KeepOrTake && !row.changedSinceLastPreview && old.rows.firstOrNull { it.key == row.key }?.changes == row.changes }.map { it.key }.toSet()
            publish(fresh)
            if (fresh.identity is ImportIdentity.NeedsConfirmation) return@producing
            val retained = choices.copy(takeIncoming = choices.takeIncoming.intersect(unchanged), skipInvalidRows = false)
            mutableState.update { it.copy(choices = retained, preview = transfer.applyChoices(fresh, retained), message = "Preview refreshed. Review highlighted rows and invalid-row decisions before committing.") }
        } }
    }
    fun requestCommit() {
        if (!available() || state.value.error == LedgerError.ImportPreviewStale) return
        val preview = state.value.preview ?: return
        if (preview.identity is ImportIdentity.NeedsConfirmation) return
        if (preview.invalidRows.isNotEmpty() && !state.value.choices.skipInvalidRows) { fail(LedgerError.InvalidField("invalidRows")); return }
        mutableState.update { it.copy(confirmation = DataConfirmation.COMMIT_IMPORT) }
    }
    private fun commit() {
        val preview = state.value.preview ?: return; val choices = state.value.choices
        runOperation(write = true) { file { transfer.commitImport(preview, choices) }.map {
            rawPreview = null; mutableState.update { it.copy(preview = null, choices = ImportChoices(), message = if (services.simulated)
                "Import simulation committed a batch record. The canned preview did not change financial records." else "Import committed. The imported changes are now in your ledger.") }
        } }
    }
    fun requestLeave() {
        if (state.value.busy && mutating) return
        if (state.value.busy) { job?.cancel(); epoch++; clearPrepared(); mutableState.update { it.copy(busy = false, exit = true) }; return }
        val dirty = rawPreview != null || restoreSource != null || state.value.picker != null || state.value.confirmation != null
        mutableState.update { it.copy(discard = dirty, exit = !dirty) }
    }
    fun keepEditing() { mutableState.update { it.copy(discard = false) } }
    fun discard() { job?.cancel(); epoch++; clearPrepared(); mutableState.value = DataState(loading = false, mode = state.value.mode, exit = true) }
    fun end() { clearPrepared(); watcher.cancel(); job?.cancel(); mutableState.value = DataState(loading = false, mode = state.value.mode, exit = true) }
    override fun onCleared() { clearPrepared(); super.onCleared() }
}
