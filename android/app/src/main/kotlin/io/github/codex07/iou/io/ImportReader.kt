package io.github.codex07.iou.io

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.store.readAtMost
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat

/** One data row: its row number in the file (the header is row 1) and its cells by column name. */
data class CsvRow(val number: Int, val cells: Map<String, String>)

/** A normalized export as read: its manifest and the rows of each table it contains. */
data class ImportFile(val manifest: ExportManifest, val tables: Map<String, List<CsvRow>>) {
    fun rows(table: CsvTable<*>): List<CsvRow> = tables[table.name].orEmpty()
}

/** Limits on an import file (spec §7.5). */
data class ImportLimits(
    val maxFileBytes: Long = 20L * 1024 * 1024,
    val maxRows: Int = 100_000,
    val maxEntries: Int = 50,
    val maxUncompressedBytes: Long = 200L * 1024 * 1024,
    val maxCompressionRatio: Int = 100,
)

/**
 * Reads an own normalized export (spec §7.4, §7.5 step 1): the zip within [limits], `manifest.json`, and
 * every known table's CSV by its header. Free-text cells are unescaped when the manifest declares
 * [TextEscaping.NAME]. Entries that aren't tables of the normalized shape are ignored.
 */
class ImportReader(private val limits: ImportLimits = ImportLimits()) {

    private val tablesByFile = NormalizedTables.all.associateBy { it.fileName }

    /** Errors: `CorruptFile` (not a zip or not an IOU export), `SchemaTooNew`, `LimitsExceeded`. */
    fun read(file: ByteArray): LedgerResult<ImportFile> {
        if (file.size > limits.maxFileBytes) return limit("File larger than ${limits.maxFileBytes} bytes")
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        var entryCount = 0
        try {
            ZipInputStream(ByteArrayInputStream(file)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entryCount > limits.maxEntries) return limit("More than ${limits.maxEntries} entries")
                    if (entry.name in entries) return corrupt("Duplicate zip entry ${entry.name}")
                    val budget = minOf(limits.maxUncompressedBytes, file.size.toLong() * limits.maxCompressionRatio) - total
                    val bytes = zip.readBytesUpTo(budget) ?: return limit("Uncompressed size or compression ratio too large")
                    total += bytes.size
                    entries[entry.name] = bytes
                }
            }
        } catch (_: ZipException) {
            return corrupt("Not a zip file")
        } catch (_: IOException) {
            return corrupt("Damaged zip file")
        }
        val manifestBytes = entries[LedgerExport.MANIFEST] ?: return corrupt("Not an IOU export: no manifest.json")
        val manifest = try {
            json.decodeFromString(ExportManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return corrupt("Unreadable manifest.json")
        } catch (_: IllegalArgumentException) {
            return corrupt("Unreadable manifest.json")
        }
        if (manifest.format != LedgerExport.FORMAT) return corrupt("Not an IOU normalized export")
        if (manifest.schemaVersion > NormalizedTables.SCHEMA_VERSION) {
            return LedgerResult.Err(LedgerError.SchemaTooNew(manifest.schemaVersion, NormalizedTables.SCHEMA_VERSION))
        }
        if (manifest.textEscaping != TextEscaping.NAME) return corrupt("Unknown text escaping ${manifest.textEscaping}")
        val delimiter = manifest.delimiter.singleOrNull() ?: return corrupt("Bad delimiter ${manifest.delimiter}")

        val tables = linkedMapOf<String, List<CsvRow>>()
        var rowCount = 0
        for ((name, bytes) in entries) {
            val table = tablesByFile[name] ?: continue
            val rows = try {
                parse(table, bytes, delimiter, limits.maxRows - rowCount)
                    ?: return limit("More than ${limits.maxRows} rows")
            } catch (e: UncheckedIOException) {
                return corrupt("$name: ${e.message}")
            } catch (e: IOException) {
                return corrupt("$name: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return corrupt("$name: ${e.message}")
            } catch (e: IllegalStateException) {
                return corrupt("$name: ${e.message}")
            }
            rowCount += rows.size
            tables[table.name] = rows
        }
        // The manifest's row counts make the file checkably complete (spec §7.2): every known table must be
        // present, even an empty one (exports always write all of them), with exactly the declared data rows.
        for (table in NormalizedTables.all) {
            val rows = tables[table.name] ?: return corrupt("Missing ${table.fileName}")
            val declared = manifest.tables[table.name] ?: return corrupt("manifest.json has no row count for ${table.name}")
            if (rows.size != declared) return corrupt("${table.fileName} has ${rows.size} data rows, manifest.json says $declared")
        }
        return LedgerResult.Ok(ImportFile(manifest, tables))
    }

    private fun parse(table: CsvTable<*>, bytes: ByteArray, delimiter: Char, budget: Int): List<CsvRow>? {
        val text = bytes.toString(Charsets.UTF_8).removePrefix(LedgerExport.BOM)
        val textColumns = table.columns.filter { it.text }.map { it.name }.toSet()
        val format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).get()
        return format.parse(text.reader()).use { parser ->
            require(parser.headerNames.size == parser.headerNames.toSet().size) { "Duplicate CSV header" }
            val rows = mutableListOf<CsvRow>()
            for (record in parser) {
                if (rows.size >= budget) return null
                val cells = record.toMap().mapValues { (column, value) ->
                    if (column in textColumns) TextEscaping.unescape(value) else value
                }
                rows.add(CsvRow(rows.size + 2, cells))
            }
            rows
        }
    }

    private fun ZipInputStream.readBytesUpTo(budget: Long): ByteArray? = if (budget < 0) null else readAtMost(budget)

    private fun corrupt(detail: String) = LedgerResult.Err(LedgerError.CorruptFile(detail))

    private fun limit(detail: String) = LedgerResult.Err(LedgerError.LimitsExceeded(detail))

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
