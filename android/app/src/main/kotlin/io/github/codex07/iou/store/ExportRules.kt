package io.github.codex07.iou.store

import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.LedgerError

/** Export request rules both flavors share (spec §7.2, §7.3). */
object ExportRules {

    /**
     * - `InvalidField("format")`: XLSX arrives in M2 with FastExcel.
     * - `InvalidField("scope")`: the normalized shape is always the full ledger.
     * - `InvalidField("to")`: a date range that ends before it starts.
     */
    fun check(request: ExportRequest): LedgerError? = when {
        request.format == ExportFormat.XLSX -> LedgerError.InvalidField("format")
        request.shape == ExportShape.NORMALIZED && request.scope != ExportScope.FullLedger -> LedgerError.InvalidField("scope")
        request.from != null && request.to != null && request.from > request.to -> LedgerError.InvalidField("to")
        else -> null
    }
}
