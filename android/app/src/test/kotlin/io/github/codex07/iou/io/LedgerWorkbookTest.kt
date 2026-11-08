package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.stream.Collectors
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import org.junit.jupiter.api.Test

/** XLSX workbooks (spec §7.3), read back with FastExcel's reader. */
class LedgerWorkbookTest {

    private val workbook = LedgerWorkbook(LedgerExport("test"))

    /** Me, X and Y in Trip (KEEP EUR); X paid €100 split with me, a €30 loan to Y outside groups, and a schedule. */
    private val state: LedgerState = TestLedger().run {
        me()
        val x = person("X")
        val y = person("Y")
        val trip = group("Trip", members = listOf(ME, x))
        expense(
            x, eur(10_000), mapOf(ME to 5_000L, x to 5_000L), group = trip, id = "e-1", payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(x, 6_000, 6_000), ExpensePayer(ME, 4_000, 4_000)),
        )
        transfer(ME, y, eur(3_000), id = "t-1")
        schedule(ME, eur(90_000), listOf(ME, y), id = "rent", handledThrough = LocalDate.parse("2026-02-01"))
        state
    }.let { s -> s.with(s.expenses.values.single().copy(description = "=SUM(1)")) }

    private fun sheets(bytes: ByteArray): Map<String, List<Row>> =
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { book ->
            book.sheets.collect(Collectors.toList()).associate { it.name to it.read() }
        }

    private fun Row.text(i: Int): String? = if (i < cellCount) getCell(i)?.text?.takeIf { it.isNotEmpty() } else null

    private fun Row.number(i: Int): BigDecimal = getCell(i).asNumber()

    private fun Row.date(i: Int): LocalDate = getCell(i).asDate().toLocalDate()

    @Test
    fun `the normalized workbook has one sheet per table with typed cells`() {
        val out = ByteArrayOutputStream()

        val rows = workbook.normalized(state, out)
        val sheets = sheets(out.toByteArray())

        sheets.keys.toList() shouldBe NormalizedTables.all.map { it.name }
        rows shouldBe sheets.values.sumOf { it.size - 1 }
        val transfers = sheets.getValue("transfers")
        val header = transfers[0].let { row -> (0 until row.cellCount).map { row.text(it) } }
        header shouldBe NormalizedTables.transfers.header
        val loan = transfers[1]
        loan.number(header.indexOf("amount_minor")).compareTo(BigDecimal(3_000)) shouldBe 0
        loan.number(header.indexOf("amount")).compareTo(BigDecimal("30.00")) shouldBe 0
        loan.date(header.indexOf("occurred_on")) shouldBe D0
        val people = sheets.getValue("people")
        val isMe = people[0].let { row -> (0 until row.cellCount).first { row.text(it) == "is_me" } }
        people.drop(1).map { it.getCell(isMe).type }.toSet() shouldBe setOf(CellType.BOOLEAN)
        val schedules = sheets.getValue("recurring_expenses")
        val scheduleHeader = schedules[0].let { row -> (0 until row.cellCount).map { row.text(it) } }
        schedules[1].date(scheduleHeader.indexOf("handled_through")) shouldBe LocalDate.parse("2026-02-01")
    }

    @Test
    fun `the report workbook has the summary, the report rows and a sheet per group`() {
        val out = ByteArrayOutputStream()

        val result = workbook.report(state, ExportScope.FullLedger, null, null, out)
        val sheets = sheets(out.toByteArray())

        result shouldBe LedgerResult.Ok(2)
        sheets.keys.toList() shouldBe listOf("Summary", "Report", "Trip")
        val report = sheets.getValue("Report")
        (0 until report[0].cellCount).map { report[0].text(it) } shouldBe LedgerExport.REPORT_HEADER
        val expense = report.drop(1).single { it.text(1) == "expense" }
        expense.date(0) shouldBe D0
        expense.text(2) shouldBe "'=SUM(1)"
        expense.text(4) shouldBe "X 60.00; Me 40.00"
        expense.number(7).compareTo(BigDecimal("100.00")) shouldBe 0
        expense.number(10).compareTo(BigDecimal("-10.00")) shouldBe 0
        val summary = sheets.getValue("Summary").drop(1).map { listOf(it.text(0), it.text(1), it.text(2)) to it.number(3) }
        summary.map { it.first } shouldBe listOf(listOf("person", "Y", "EUR"), listOf("group", "Trip", "EUR"))
        summary.map { it.second.setScale(2).toPlainString() } shouldBe listOf("30.00", "-10.00")
        val trip = sheets.getValue("Trip")
        trip.drop(1).take(2).map { it.text(0) to it.number(3).setScale(2).toPlainString() } shouldBe
            listOf("Me" to "-10.00", "X" to "10.00")
        trip.last().let { listOf(it.text(0), it.text(1), it.text(2)) } shouldBe listOf("Me", "X", "EUR")
        trip.last().number(3).setScale(2).toPlainString() shouldBe "10.00"
    }

    @Test
    fun `group sheet names are valid and unique`() {
        val used = mutableSetOf("summary", "report", "history") // Excel reserves "History"

        LedgerWorkbook.sheetName("a/b:c", used) shouldBe "a b c"
        LedgerWorkbook.sheetName("Report", used) shouldBe "Report (2)"
        LedgerWorkbook.sheetName("History", used) shouldBe "History (2)"
        LedgerWorkbook.sheetName("'quoted'", used) shouldBe "quoted"
        LedgerWorkbook.sheetName("x".repeat(40), used) shouldBe "x".repeat(31)
        LedgerWorkbook.sheetName("x".repeat(40), used) shouldBe "x".repeat(27) + " (2)"
        LedgerWorkbook.sheetName("???", used) shouldBe "Group"
        LedgerWorkbook.sheetName("y".repeat(30) + " 'z", used) shouldBe "y".repeat(30)
        LedgerWorkbook.sheetName("line\nbreak", used) shouldBe "line break"
    }
}
