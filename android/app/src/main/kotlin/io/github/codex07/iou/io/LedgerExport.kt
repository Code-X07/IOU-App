package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.Money
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

/** `manifest.json` of the normalized zip (spec §7.2); the import reads the delimiter and escaping from it. */
@Serializable
data class ExportManifest(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val scope: String,
    val textEscaping: String,
    val delimiter: String,
    val bom: Boolean,
    /** Data rows per table. */
    val tables: Map<String, Int>,
)

/**
 * CSV export (spec §7.2). UTF-8, RFC 4180 quoting, `,` and no BOM by default. Excel-friendly mode is UTF-8 with
 * a BOM and `;`. Text is escaped with [TextEscaping] in both shapes.
 */
class LedgerExport(private val appVersion: String) {

    /** The normalized zip: every table, every row, plus the manifest. Returns the number of data rows. */
    fun normalized(state: LedgerState, exportedAt: Instant, excelFriendly: Boolean, out: OutputStream): Int {
        val zip = ZipOutputStream(out)
        val counts = linkedMapOf<String, Int>()
        for (table in NormalizedTables.all) {
            zip.putNextEntry(ZipEntry(table.fileName))
            counts[table.name] = table.write(state, excelFriendly, zip)
            zip.closeEntry()
        }
        val manifest = ExportManifest(
            format = FORMAT, schemaVersion = NormalizedTables.SCHEMA_VERSION, appVersion = appVersion,
            exportedAt = exportedAt.toString(), scope = "full-ledger", textEscaping = TextEscaping.NAME,
            delimiter = delimiter(excelFriendly).toString(), bom = excelFriendly, tables = counts,
        )
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(ExportManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        return counts.values.sum()
    }

    /**
     * The report: one row per active entry in [scope] and the inclusive date range, by date. Names are canonical;
     * `my_share` and `balance_effect_for_me` (positive: owed to me) are in `effect_currency`. Not re-importable.
     * Errors: `NotFound` for a group or person that doesn't exist.
     */
    fun report(
        state: LedgerState,
        scope: ExportScope,
        from: LocalDate?,
        to: LocalDate?,
        excelFriendly: Boolean,
        out: OutputStream,
    ): LedgerResult<Int> {
        scopeError(state, scope)?.let { return LedgerResult.Err(it) }
        val resolver = IdentityResolver(state)
        val me = state.meOrNull?.id
        val entries = if (me == null) emptyList() else state.entries
            .filter { !it.isDeleted }
            .filter { (from == null || it.occurredOn >= from) && (to == null || it.occurredOn <= to) }
            .filter { it.inScope(scope, resolver) }
            .sortedWith(compareBy({ it.occurredOn }, { it.id.value }))
        csv(out, excelFriendly) { printer ->
            printer.printRecord(REPORT_HEADER)
            for (entry in entries) printer.printRecord(reportRow(entry, checkNotNull(me), state, resolver))
        }
        return LedgerResult.Ok(entries.size)
    }

    /** `NotFound` for a scope naming a group or person that doesn't exist; check before opening the target. */
    fun scopeError(state: LedgerState, scope: ExportScope): LedgerError? = when (scope) {
        ExportScope.FullLedger -> null
        is ExportScope.Group -> if (scope.id in state.groups) null else LedgerError.NotFound(scope.id.value)
        is ExportScope.Person -> if (scope.id in state.people) null else LedgerError.NotFound(scope.id.value)
    }

    private fun Entry.inScope(scope: ExportScope, resolver: IdentityResolver): Boolean = when (scope) {
        ExportScope.FullLedger -> true
        is ExportScope.Group -> groupId == scope.id
        is ExportScope.Person -> resolver.canonical(scope.id) in personIds.map(resolver::canonical)
    }

    private fun reportRow(entry: Entry, me: PersonId, state: LedgerState, resolver: IdentityResolver): List<String?> {
        fun name(id: PersonId) = state.people[resolver.canonical(id)]?.displayName
        val effectCurrency = entry.effect.currency
        val effectForMe = LedgerEngine.entryEffects(entry, resolver).sumOf { effect ->
            when (me) {
                effect.creditor -> effect.amount.amountMinor
                effect.debtor -> -effect.amount.amountMinor
                else -> 0L
            }
        }
        val transfer = entry as? Transfer
        val expense = entry as? Expense
        val myShare = expense?.shares?.filter { resolver.canonical(it.personId) == me }?.sumOf { it.shareMinor }
        return listOf(
            entry.occurredOn.toString(),
            transfer?.kind?.name?.lowercase() ?: "expense",
            expense?.description?.let(TextEscaping::escape),
            entry.groupId?.let { state.groups[it]?.name }?.let(TextEscaping::escape),
            expense?.let { name(it.payerPersonId) }?.let(TextEscaping::escape),
            transfer?.let { name(it.fromPersonId) }?.let(TextEscaping::escape),
            transfer?.let { name(it.toPersonId) }?.let(TextEscaping::escape),
            entry.paid.toPlainString(),
            entry.paid.currency.code,
            myShare?.let { Money(it, effectCurrency).toPlainString() },
            Money(effectForMe, effectCurrency).toPlainString(),
            effectCurrency.code,
            transfer?.accountLabel?.let(TextEscaping::escape),
            entry.note?.let(TextEscaping::escape),
        )
    }

    private fun <T> CsvTable<T>.write(state: LedgerState, excelFriendly: Boolean, out: OutputStream): Int {
        val records = rows(state)
        csv(out, excelFriendly) { printer ->
            printer.printRecord(header)
            for (record in records) printer.printRecord(cells(record))
        }
        return records.size
    }

    /** Writes one CSV document to [out] without closing it (a zip entry stays open for the next one). */
    private fun csv(out: OutputStream, excelFriendly: Boolean, block: (CSVPrinter) -> Unit) {
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        if (excelFriendly) writer.write(BOM)
        val printer = CSVPrinter(writer, CSVFormat.RFC4180.builder().setDelimiter(delimiter(excelFriendly)).get())
        block(printer)
        printer.flush()
    }

    private fun delimiter(excelFriendly: Boolean) = if (excelFriendly) ';' else ','

    companion object {
        const val FORMAT = "iou-normalized-csv"
        const val MANIFEST = "manifest.json"
        const val BOM = "\uFEFF"
        val REPORT_HEADER = listOf(
            "date", "type", "description", "group", "paid_by", "from", "to", "amount", "currency",
            "my_share", "balance_effect_for_me", "effect_currency", "account", "note",
        )
        private val json = Json { encodeDefaults = true }
    }
}
