package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportInfo
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.ImportBatchView
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportCounts
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.io.DecodedImport
import io.github.codex07.iou.io.ImportAnalysis
import io.github.codex07.iou.io.ImportDecoder
import io.github.codex07.iou.io.ImportReader
import io.github.codex07.iou.io.ImportUndo
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.ExportRules
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreSnapshot
import io.github.codex07.iou.store.StoreWrite
import io.github.codex07.iou.store.readAtMost
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The real flavor's import and export (spec §7.2, §7.4, §7.5, §5.4). A parsed file stays in memory as a
 * session under its preview's ID, so `applyChoices` stays pure and a refresh can rebuild the preview against
 * the current ledger. Sessions don't survive the process; a preview from before a restart gives
 * `NotFound("preview")`.
 */
class RealImportExportApi(
    private val store: LedgerStore,
    private val documents: DocumentStore,
    private val exporter: LedgerExport,
    private val reader: ImportReader = ImportReader(),
    private val maxFileBytes: Long = 20L * 1024 * 1024,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ImportExportApi {

    private class Session(val fileName: String, val decoded: DecodedImport, val sourceMe: PersonId?) {
        @Volatile var mapped = false
        @Volatile var choices = ImportChoices()
        @Volatile var analysis: ImportAnalysis? = null
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Errors: those of `ExportRules.check`, `NotFound` for the scope, `FileAccessFailed`. */
    override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> {
        ExportRules.check(request)?.let { return LedgerResult.Err(it) }
        val state = store.loaded().state
        exporter.scopeError(state, request.scope)?.let { return LedgerResult.Err(it) }
        val now = store.now()
        val written = withContext(Dispatchers.IO) {
            access(target) {
                documents.openWrite(target).use { out ->
                    when (request.shape) {
                        ExportShape.NORMALIZED -> LedgerResult.Ok(exporter.normalized(state, now, request.excelFriendly, out))
                        ExportShape.REPORT -> exporter.report(state, request.scope, request.from, request.to, request.excelFriendly, out)
                    }
                }
            }
        }
        return when (written) {
            is LedgerResult.Err -> written
            is LedgerResult.Ok -> written.value.map(::ExportInfo)
        }
    }

    /** Errors: `FileAccessFailed`, `CorruptFile`, `SchemaTooNew`, `LimitsExceeded`, `NotFound("me")` before setup. */
    override suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview> = withContext(computeDispatcher) {
        val bytes = when (val read = withContext(Dispatchers.IO) { access(source) { documents.openRead(source).use { it.readAtMost(maxFileBytes) } } }) {
            is LedgerResult.Err -> return@withContext read
            is LedgerResult.Ok -> read.value ?: return@withContext LedgerResult.Err(LedgerError.LimitsExceeded("File larger than $maxFileBytes bytes"))
        }
        val file = when (val parsed = reader.read(bytes)) {
            is LedgerResult.Err -> return@withContext parsed
            is LedgerResult.Ok -> parsed.value
        }
        val snapshot = store.loaded()
        val me = snapshot.state.meOrNull?.id ?: return@withContext LedgerResult.Err(LedgerError.NotFound("me"))
        val decoded = ImportDecoder.decode(file)
        val sourceMe = decoded.ledger.people.firstOrNull { it.record.isMe }?.record?.id
        val session = Session(fileName(source), decoded, sourceMe)
        val id = store.ids.next()
        sessions[id] = session
        LedgerResult.Ok(
            if (sourceMe == null || sourceMe == me) {
                build(id, session, snapshot, ImportIdentity.Same, previous = null)
            } else {
                pending(id, session, snapshot, sourceMe)
            },
        )
    }

    override suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview> {
        val snapshot = store.loaded()
        if (preview.generation != snapshot.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val pending = preview.identity as? ImportIdentity.NeedsConfirmation ?: return LedgerResult.Ok(preview)
        if (!accept) {
            sessions.remove(preview.id)
            return LedgerResult.Err(LedgerError.ForeignLedger)
        }
        val session = sessions[preview.id] ?: return LedgerResult.Err(LedgerError.NotFound("preview"))
        session.mapped = true
        return LedgerResult.Ok(build(preview.id, session, snapshot, ImportIdentity.Mapped(pending.sourceMe), previous = null))
    }

    override fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview {
        val session = sessions[preview.id] ?: return preview
        val analysis = session.analysis ?: return preview
        session.choices = choices
        val flagged = preview.rows.associate { it.key to it.changedSinceLastPreview }
        return preview.copy(
            rows = analysis.previewRows(choices.takeIncoming).map { it.copy(changedSinceLastPreview = flagged[it.key] ?: false) },
            balanceChanges = analysis.balanceChanges(choices.takeIncoming),
        )
    }

    override suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview> {
        val snapshot = store.loaded()
        if (preview.generation != snapshot.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val session = sessions[preview.id] ?: return LedgerResult.Err(LedgerError.NotFound("preview"))
        if (preview.identity is ImportIdentity.NeedsConfirmation) return LedgerResult.Ok(preview)
        return LedgerResult.Ok(withContext(computeDispatcher) { build(preview.id, session, snapshot, preview.identity, previous = preview) })
    }

    override suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId> {
        val result = store.writeWith { snapshot, context ->
            val analysis = sessions[preview.id]?.analysis
            when {
                preview.generation != snapshot.generation -> LedgerResult.Err(LedgerError.LedgerReplaced)
                preview.ledgerSeq != snapshot.ledgerSeq -> LedgerResult.Err(LedgerError.ImportPreviewStale)
                preview.identity is ImportIdentity.NeedsConfirmation -> LedgerResult.Err(LedgerError.ForeignLedger)
                analysis == null -> LedgerResult.Err(LedgerError.NotFound("preview"))
                analysis.state !== snapshot.state -> LedgerResult.Err(LedgerError.ImportPreviewStale)
                analysis.invalid.isNotEmpty() && !choices.skipInvalidRows -> LedgerResult.Err(LedgerError.InvalidField("invalidRows"))
                else -> {
                    val id = ImportBatchId(context.ids.next())
                    val plan = analysis.plan(choices.takeIncoming, id, context.now)
                    val violation = LedgerValidator.validate(snapshot.state, plan.state, WriteOp.Import).firstOrNull()
                    if (violation != null) {
                        LedgerResult.Err(violation.error)
                    } else {
                        val batch = ImportBatch(id, ImportSource.OWN_NORMALIZED_CSV, analysis.fileName, context.now, plan.rowCount)
                        LedgerResult.Ok(StoreWrite(id, WritePlan(plan.changes, plan.snapshots), importBatches = listOf(batch)))
                    }
                }
            }
        }
        if (result is LedgerResult.Ok) sessions.remove(preview.id)
        return result
    }

    override fun observeImportBatches(): Flow<List<ImportBatchView>> = store.readSnapshot { snapshot ->
        snapshot.importBatches.sortedByDescending { it.importedAt }
            .map { ImportBatchView(it.id, it.fileName, it.importedAt, it.rowCount, it.undoneAt) }
    }

    /** Best-effort (spec §5.4). An already undone batch does nothing. Errors: `NotFound`. */
    override suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult> = store.writeWith { snapshot, context ->
        val batch = snapshot.importBatches.firstOrNull { it.id == id } ?: return@writeWith LedgerResult.Err(LedgerError.NotFound(id.value))
        val undo = ImportUndo.plan(snapshot.state, snapshot.revisions, batch, context.now)
        val undone = if (batch.undoneAt == null) listOf(batch.copy(undoneAt = context.now)) else emptyList()
        LedgerResult.Ok(StoreWrite(UndoImportResult(undo.skipped), WritePlan(undo.changes), importBatches = undone))
    }

    private fun pending(id: String, session: Session, snapshot: StoreSnapshot, sourceMe: PersonId) = ImportPreview(
        id = id, fileName = session.fileName, generation = snapshot.generation, ledgerSeq = snapshot.ledgerSeq,
        identity = ImportIdentity.NeedsConfirmation(sourceMe), counts = ImportCounts(0, 0, 0, 0),
        rows = emptyList(), invalidRows = emptyList(), balanceChanges = emptyList(),
    )

    /** Analyses the session against [snapshot]; with [previous], rows that are new or whose diff changed are flagged. */
    private fun build(id: String, session: Session, snapshot: StoreSnapshot, identity: ImportIdentity, previous: ImportPreview?): ImportPreview {
        val analysis = ImportAnalysis.build(snapshot.state, session.fileName, session.decoded, if (session.mapped) session.sourceMe else null)
        session.analysis = analysis
        val take = session.choices.takeIncoming
        val before = previous?.rows?.associateBy { it.key }
        val rows = analysis.previewRows(take).map { row: ImportRow ->
            row.copy(changedSinceLastPreview = before != null && before[row.key]?.changes != row.changes)
        }
        return ImportPreview(
            id = id, fileName = session.fileName, generation = snapshot.generation, ledgerSeq = snapshot.ledgerSeq,
            identity = identity, counts = analysis.counts, rows = rows, invalidRows = analysis.invalid,
            balanceChanges = analysis.balanceChanges(take),
        )
    }

    /**
     * The document's file name, from the last path segment of its URI (SAF URIs encode the path). The
     * charset-name overload: the `Charset` one needs API 33 (minSdk is 30).
     */
    private fun fileName(ref: DocumentRef): String =
        URLDecoder.decode(ref.uri.substringAfterLast('/'), "UTF-8").substringAfterLast('/').substringAfterLast(':')

    /** Runs a document read or write; a missing document or a revoked permission becomes `FileAccessFailed`. */
    private inline fun <T> access(ref: DocumentRef, block: () -> T): LedgerResult<T> = try {
        LedgerResult.Ok(block())
    } catch (e: IOException) {
        LedgerResult.Err(LedgerError.FileAccessFailed("${ref.uri}: ${e.message}"))
    } catch (e: SecurityException) {
        LedgerResult.Err(LedgerError.FileAccessFailed("${ref.uri}: ${e.message}"))
    }
}
