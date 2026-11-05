package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.ImportBatchId
import kotlinx.coroutines.flow.Flow

interface ImportExportApi {
    /** Errors: `InvalidField("scope")` for a scoped normalized export. */
    suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo>

    /**
     * Parses and validates; writes nothing. Errors: `CorruptFile`, `LimitsExceeded`, `SchemaTooNew`. One import is
     * open at a time: in the real flavor, parsing a file ends any earlier preview, whose later use returns
     * `NotFound("preview")`.
     */
    suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview>

    /**
     * Answers `ImportIdentity.NeedsConfirmation`: accept → the full preview, built against the current ledger;
     * decline → `ForeignLedger`. A preview from an earlier generation → `LedgerReplaced`, whatever the answer.
     */
    suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview>

    /**
     * The preview with forced owner/owned outcomes recomputed for [choices]. No I/O, but not a disposable
     * calculation: the real flavor stores [choices] in the preview's import session, and [refreshPreview] rebuilds
     * with the last choices stored. Pass the choices the user made, not trial alternatives (spec §6.2).
     */
    fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview

    /**
     * Rebuilds a stale preview against the current ledger: conflicts, diffs and counts are recomputed, and rows
     * that are new or whose diff changed get `changedSinceLastPreview`. Earlier generation → `LedgerReplaced`.
     */
    suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview>

    /** Errors: `LedgerReplaced`, `ImportPreviewStale` (any write since the preview), invalid rows not skipped. */
    suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId>

    /**
     * Ends [preview]'s import session and frees what it holds; call it when the user leaves a preview without
     * committing. Later use of the preview returns `NotFound("preview")`. Unknown or ended previews are ignored.
     */
    fun discardImport(preview: ImportPreview)

    fun observeImportBatches(): Flow<List<ImportBatchView>>

    suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult>
}
