package io.github.codex07.iou.testutil

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.LedgerResult
import java.io.File
import java.io.StringWriter
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

/** Writes a normalized export of [this] backend's ledger to [name] in [documents] and returns the file. */
suspend fun Apis.exportTo(documents: FileDocuments, name: String): File {
    val ref = DocumentRef(name)
    val result = importExport.export(ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV), ref)
    check(result is LedgerResult.Ok) { "Export failed: $result" }
    return documents.file(ref)
}

/** [zip] with [table]'s rows rewritten by [transform] (cells by column name); the header is kept. */
fun editCsv(zip: ByteArray, table: String, transform: (List<MutableMap<String, String>>) -> List<Map<String, String>>): ByteArray =
    Zips.edit(zip, "$table.csv") { text ->
        val parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(text.reader())
        val header = parser.headerNames
        val rows = transform(parser.records.map { record -> record.toMap().toMutableMap() })
        val out = StringWriter()
        CSVPrinter(out, CSVFormat.RFC4180).use { printer ->
            printer.printRecord(header)
            rows.forEach { row -> printer.printRecord(header.map { row[it].orEmpty() }) }
        }
        out.toString()
    }

/** [zip] with the first transfer's note set to [note], as the contract's own-export fixture needs. */
fun withFirstTransferNote(zip: ByteArray, note: String): ByteArray = editCsv(zip, "transfers") { rows ->
    rows.also { it.firstOrNull()?.set("note", note) }
}
