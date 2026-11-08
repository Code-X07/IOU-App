package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
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

/** One row of the report shape (spec §7.2), typed: the CSV writes it as text, the workbook as typed cells. */
data class ReportLine(
    val date: LocalDate,
    val type: String,
    val description: String?,
    val group: String?,
    val paidBy: String?,
    val from: String?,
    val to: String?,
    val amount: Money,
    /** In the effect currency. */
    val myShare: Money?,
    /** In the effect currency; positive means owed to me. */
    val effectForMe: Money,
    val account: String?,
    val note: String?,
) {
    /** The CSV cells in [LedgerExport.REPORT_HEADER] order, text escaped (`quote-prefix-v1`). */
    fun cells(): List<String?> = listOf(
        date.toString(), type, description?.let(TextEscaping::escape), group?.let(TextEscaping::escape),
        paidBy?.let(TextEscaping::escape), from?.let(TextEscaping::escape), to?.let(TextEscaping::escape),
        amount.toPlainString(), amount.currency.code, myShare?.toPlainString(), effectForMe.toPlainString(),
        effectForMe.currency.code, account?.let(TextEscaping::escape), note?.let(TextEscaping::escape),
    )
}

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

    /** The report as CSV: [reportLines] under [REPORT_HEADER]. Not re-importable. Errors: `NotFound` for the scope. */
    fun report(
        state: LedgerState,
        scope: ExportScope,
        from: LocalDate?,
        to: LocalDate?,
        excelFriendly: Boolean,
        out: OutputStream,
    ): LedgerResult<Int> {
        val lines = when (val result = reportLines(state, scope, from, to)) {
            is LedgerResult.Err -> return result
            is LedgerResult.Ok -> result.value
        }
        csv(out, excelFriendly) { printer ->
            printer.printRecord(REPORT_HEADER)
            for (line in lines) printer.printRecord(line.cells())
        }
        return LedgerResult.Ok(lines.size)
    }

    /**
     * The report's rows (spec §7.2): one per active entry in [scope] and the inclusive date range, by date, then ID.
     * Names are canonical. Errors: `NotFound` for a group or person that doesn't exist.
     */
    fun reportLines(state: LedgerState, scope: ExportScope, from: LocalDate?, to: LocalDate?): LedgerResult<List<ReportLine>> {
        scopeError(state, scope)?.let { return LedgerResult.Err(it) }
        val me = state.meOrNull?.id ?: return LedgerResult.Ok(emptyList())
        val resolver = IdentityResolver(state)
        val entries = state.entries
            .filter { !it.isDeleted }
            .filter { (from == null || it.occurredOn >= from) && (to == null || it.occurredOn <= to) }
            .filter { it.inScope(scope, resolver) }
            .sortedWith(compareBy({ it.occurredOn }, { it.id.value }))
        return LedgerResult.Ok(entries.map { reportLine(it, me, state, resolver) })
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

    private fun reportLine(entry: Entry, me: PersonId, state: LedgerState, resolver: IdentityResolver): ReportLine {
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
        return ReportLine(
            date = entry.occurredOn,
            type = transfer?.kind?.name?.lowercase() ?: "expense",
            description = expense?.description,
            group = entry.groupId?.let { state.groups[it]?.name },
            paidBy = expense?.let { paidBy(it) { id -> name(id) } },
            from = transfer?.let { name(it.fromPersonId) },
            to = transfer?.let { name(it.toPersonId) },
            amount = entry.paid,
            myShare = myShare?.let { Money(it, effectCurrency) },
            effectForMe = Money(effectForMe, effectCurrency),
            account = transfer?.accountLabel,
            note = entry.note,
        )
    }

    /**
     * `paid_by` (spec §7.2): the payer's name, or each payer's name with what they paid in the paid currency, always
     * from the stored payer rows (spec §4.4: they are authoritative), never re-divided by person ID, which an import
     * from another installation can change: an EXACT payer's input; an EQUAL payer's stored `paidMinor` when nothing
     * was converted; for a converted EQUAL expense the paid total divided equally, the extra minor units going to the
     * payers with the larger stored amounts (stored order on ties), so the report agrees with the ledger.
     */
    private fun paidBy(expense: Expense, name: (PersonId) -> String?): String? {
        if (expense.payers.size == 1) return name(expense.payers.single().personId)
        val amounts = when {
            expense.payerSplit == PayerSplit.EXACT -> expense.payers.map { it.inputValue ?: 0L }
            expense.total == expense.effectTotal -> expense.payers.map { it.paidMinor }
            else -> equalPaid(expense.total.amountMinor, expense.payers)
        }
        return expense.payers.zip(amounts).joinToString("; ") { (payer, amount) ->
            "${name(payer.personId)} ${Money(amount, expense.total.currency).toPlainString()}"
        }
    }

    /** [total] in equal parts; the remainder goes one unit each to the payers with the largest stored `paidMinor`. */
    private fun equalPaid(total: Long, payers: List<ExpensePayer>): List<Long> {
        val base = total / payers.size
        val extra = (total - base * payers.size).toInt()
        val first = payers.indices.sortedByDescending { payers[it].paidMinor }.take(extra).toSet()
        return payers.indices.map { if (it in first) base + 1 else base }
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
