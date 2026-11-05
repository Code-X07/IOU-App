package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportInfo
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.ExportRules
import io.github.codex07.iou.store.LedgerStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real flavor's import/export. Export writes the live ledger to the picked document (spec §7.2). Import
 * still comes from [simulation] until Part 7.
 */
class RealImportExportApi(
    private val store: LedgerStore,
    private val documents: DocumentStore,
    private val exporter: LedgerExport,
    simulation: ImportExportApi,
) : ImportExportApi by simulation {

    /** Errors: those of `ExportRules.check`, `NotFound` for the scope, `FileAccessFailed`. */
    override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> {
        ExportRules.check(request)?.let { return LedgerResult.Err(it) }
        val state = store.loaded().state
        exporter.scopeError(state, request.scope)?.let { return LedgerResult.Err(it) }
        val now = store.now()
        return withContext(Dispatchers.IO) {
            try {
                documents.openWrite(target).use { out ->
                    when (request.shape) {
                        ExportShape.NORMALIZED -> LedgerResult.Ok(exporter.normalized(state, now, request.excelFriendly, out))
                        ExportShape.REPORT -> exporter.report(state, request.scope, request.from, request.to, request.excelFriendly, out)
                    }
                }.map(::ExportInfo)
            } catch (e: IOException) {
                LedgerResult.Err(LedgerError.FileAccessFailed("${target.uri}: ${e.message}"))
            } catch (e: SecurityException) {
                LedgerResult.Err(LedgerError.FileAccessFailed("${target.uri}: ${e.message}"))
            }
        }
    }
}
