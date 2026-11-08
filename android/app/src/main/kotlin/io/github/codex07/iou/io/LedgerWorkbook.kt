package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet

/**
 * XLSX export with FastExcel (spec §7.3). The report workbook has a `Summary` sheet (my non-group balance with each
 * person and my net per group in scope, per currency), a `Report` sheet (the rows of the CSV report) and one sheet
 * per group in scope with its member nets and settle plan. The normalized workbook has one sheet per normalized
 * table with every row. Dates are date cells, amounts numeric cells with the currency's decimals, and string cells
 * get the `quote-prefix-v1` escape like the CSV. Neither is re-importable.
 */
class LedgerWorkbook(private val export: LedgerExport) {

    /** One sheet per table of the normalized shape (spec §7.2). Returns the number of data rows. */
    fun normalized(state: LedgerState, out: OutputStream): Int {
        val workbook = Workbook(out, APP, VERSION)
        val rows = NormalizedTables.all.sumOf { csv -> tableSheet(workbook.newWorksheet(csv.name), csv, state) }
        workbook.finish()
        return rows
    }

    /**
     * The report workbook for [scope] and the date range (which applies to the `Report` sheet; balances are current).
     * A person scope's group sheets are the groups that person belongs to. Errors: `NotFound` for the scope.
     */
    fun report(state: LedgerState, scope: ExportScope, from: LocalDate?, to: LocalDate?, out: OutputStream): LedgerResult<Int> {
        val lines = when (val result = export.reportLines(state, scope, from, to)) {
            is LedgerResult.Err -> return result
            is LedgerResult.Ok -> result.value
        }
        val balances = state.meOrNull?.let { LedgerEngine.compute(state) }
        val resolver = IdentityResolver(state)
        val groups = state.groups.values
            .filter { !it.isDeleted }
            .filter { group ->
                when (scope) {
                    ExportScope.FullLedger -> true
                    is ExportScope.Group -> group.id == scope.id
                    is ExportScope.Person -> resolver.hasMembership(group.id, resolver.canonical(scope.id))
                }
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        val workbook = Workbook(out, APP, VERSION)
        summarySheet(workbook.newWorksheet("Summary"), state, scope, balances, resolver, groups)
        reportSheet(workbook.newWorksheet("Report"), lines)
        val used = mutableSetOf("summary", "report", "history") // Excel reserves "History"
        for (group in groups) groupSheet(workbook.newWorksheet(sheetName(group.name, used)), state, group, balances, resolver)
        workbook.finish()
        return LedgerResult.Ok(lines.size)
    }

    private fun <T> tableSheet(sheet: Worksheet, table: CsvTable<T>, state: LedgerState): Int {
        val columns = table.header
        val kinds = columns.map { cellKind(columns, it) }
        header(sheet, 0, columns)
        val records = table.rows(state)
        records.forEachIndexed { i, record ->
            table.cells(record).forEachIndexed { c, cell -> if (cell != null) typed(sheet, i + 1, c, kinds[c], cell) }
        }
        return records.size
    }

    /** [cell] as the normalized CSV has it (text already escaped), written as a cell of [kind]. */
    private fun typed(sheet: Worksheet, r: Int, c: Int, kind: CellKind, cell: String) {
        when (kind) {
            CellKind.INTEGER -> sheet.value(r, c, cell.toLong())
            CellKind.DECIMAL -> {
                val number = BigDecimal(cell)
                sheet.value(r, c, number)
                sheet.style(r, c).format(decimals(number.scale())).set()
            }
            CellKind.DATE -> date(sheet, r, c, LocalDate.parse(cell))
            CellKind.BOOLEAN -> sheet.value(r, c, cell == "true")
            CellKind.TEXT -> sheet.value(r, c, cell)
        }
    }

    private fun summarySheet(
        sheet: Worksheet,
        state: LedgerState,
        scope: ExportScope,
        balances: Balances?,
        resolver: IdentityResolver,
        groups: List<Group>,
    ) {
        header(sheet, 0, listOf("kind", "name", "currency", "balance"))
        balances ?: return
        var r = 1
        val people = balances.pairwise.keys
            .filter { scope is ExportScope.FullLedger || (scope is ExportScope.Person && it == resolver.canonical(scope.id)) }
            .sortedWith(compareBy({ name(state, it).lowercase() }, { it.value }))
        for (person in people) {
            for (amount in amounts(balances.net(person))) line(sheet, r++, "person", name(state, person), amount)
        }
        for (group in groups) {
            for (amount in amounts(balances.myGroupNet(group.id))) line(sheet, r++, "group", group.name, amount)
        }
    }

    private fun line(sheet: Worksheet, r: Int, kind: String, name: String, amount: Money) {
        sheet.value(r, 0, kind)
        sheet.value(r, 1, TextEscaping.escape(name))
        sheet.value(r, 2, amount.currency.code)
        money(sheet, r, 3, amount)
    }

    private fun reportSheet(sheet: Worksheet, lines: List<ReportLine>) {
        header(sheet, 0, LedgerExport.REPORT_HEADER)
        lines.forEachIndexed { i, line ->
            val r = i + 1
            date(sheet, r, 0, line.date)
            sheet.value(r, 1, line.type)
            text(sheet, r, 2, line.description)
            text(sheet, r, 3, line.group)
            text(sheet, r, 4, line.paidBy)
            text(sheet, r, 5, line.from)
            text(sheet, r, 6, line.to)
            money(sheet, r, 7, line.amount)
            sheet.value(r, 8, line.amount.currency.code)
            line.myShare?.let { money(sheet, r, 9, it) }
            money(sheet, r, 10, line.effectForMe)
            sheet.value(r, 11, line.effectForMe.currency.code)
            text(sheet, r, 12, line.account)
            text(sheet, r, 13, line.note)
        }
    }

    /** Member nets (positive: the group owes them), then the settle plan. */
    private fun groupSheet(sheet: Worksheet, state: LedgerState, group: Group, balances: Balances?, resolver: IdentityResolver) {
        header(sheet, 0, listOf("member", "active", "currency", "net"))
        var r = 1
        val members = resolver.canonicalMembers(group.id).sortedWith(compareBy({ name(state, it).lowercase() }, { it.value }))
        for (member in members) {
            val nets = balances?.let { amounts(it.groupNet(group.id, member)) }.orEmpty()
            for (net in nets.ifEmpty { listOf(null) }) {
                sheet.value(r, 0, TextEscaping.escape(name(state, member)))
                sheet.value(r, 1, resolver.isActiveMember(group.id, member))
                if (net != null) {
                    sheet.value(r, 2, net.currency.code)
                    money(sheet, r, 3, net)
                }
                r++
            }
        }
        r++
        header(sheet, r++, listOf("from", "to", "currency", "amount"))
        for (item in SettlePlanner.planGroup(balances?.groupNets?.get(group.id).orEmpty())) {
            sheet.value(r, 0, TextEscaping.escape(name(state, item.from)))
            sheet.value(r, 1, TextEscaping.escape(name(state, item.to)))
            sheet.value(r, 2, item.amount.currency.code)
            money(sheet, r, 3, item.amount)
            r++
        }
    }

    private fun header(sheet: Worksheet, r: Int, names: List<String>) = names.forEachIndexed { c, name -> sheet.value(r, c, name) }

    private fun text(sheet: Worksheet, r: Int, c: Int, value: String?) {
        if (value != null) sheet.value(r, c, TextEscaping.escape(value))
    }

    private fun date(sheet: Worksheet, r: Int, c: Int, date: LocalDate) {
        sheet.value(r, c, date)
        sheet.style(r, c).format("yyyy-mm-dd").set()
    }

    private fun money(sheet: Worksheet, r: Int, c: Int, amount: Money) {
        val digits = amount.currency.fractionDigits
        sheet.value(r, c, BigDecimal.valueOf(amount.amountMinor, digits))
        sheet.style(r, c).format(decimals(digits)).set()
    }

    private fun name(state: LedgerState, id: PersonId) = state.people[id]?.displayName ?: id.value

    private fun amounts(map: Map<CurrencyCode, Long>) = map.entries.sortedBy { it.key.code }.map { Money(it.value, it.key) }

    internal enum class CellKind { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN }

    internal companion object {
        private const val APP = "IOU"
        /** FastExcel wants an application version of the form `XX.YYYY`. */
        private const val VERSION = "1.0"
        private val INTEGER_COLUMNS = setOf("position", "version", "interval", "input_value")
        private val DATE_COLUMNS = setOf("rate_date", "handled_through", "auto_post_from")
        private val BOOLEAN_COLUMNS = setOf("is_me", "is_default", "is_stale", "auto_post")

        private fun decimals(digits: Int) = if (digits <= 0) "0" else "0." + "0".repeat(digits)

        /** How a normalized column is written: minor amounts and counters as integers, `x` next to `x_minor` as a decimal. */
        fun cellKind(header: List<String>, column: String): CellKind = when {
            column.endsWith("_minor") || column in INTEGER_COLUMNS -> CellKind.INTEGER
            "${column}_minor" in header -> CellKind.DECIMAL
            column.endsWith("_on") || column in DATE_COLUMNS -> CellKind.DATE
            column in BOOLEAN_COLUMNS -> CellKind.BOOLEAN
            else -> CellKind.TEXT
        }

        /**
         * A valid sheet name for [name] that is not in [used] (compared ignoring case), which it joins: no `[]:*?/\`,
         * no quote at either end, at most 31 characters, "Group" when nothing is left, " (n)" when taken.
         */
        fun sheetName(name: String, used: MutableSet<String>): String {
            val base = name.replace(Regex("""[\[\]:*?/\\\p{Cntrl}]"""), " ").let(::edges).ifEmpty { "Group" }
            var candidate = fit(base, 31)
            var n = 1
            while (!used.add(candidate.lowercase())) {
                n++
                val suffix = " ($n)"
                candidate = fit(base, 31 - suffix.length) + suffix
            }
            return candidate
        }

        /** Excel forbids a sheet name that starts or ends with an apostrophe; spaces at the edges are dropped too. */
        private fun edges(text: String) = text.trim().trim('\'').trim()

        /** [text] cut to [max] chars without splitting a surrogate pair, edges cleaned again; "Group" if nothing is left. */
        private fun fit(text: String, max: Int): String {
            var cut = text.take(max)
            if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
            return edges(cut).ifEmpty { "Group" }
        }
    }
}
