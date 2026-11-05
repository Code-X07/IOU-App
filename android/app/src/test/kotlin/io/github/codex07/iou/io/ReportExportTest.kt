package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.jupiter.api.Test

class ReportExportTest {

    private val export = LedgerExport("0.1.0")
    private val x = PersonId("person-x")
    private val alias = PersonId("person-alias")
    private val y = PersonId("person-y")
    private val trip = GroupId("group-trip")
    private val loan = EntryId("loan")
    private val repayment = EntryId("repayment")
    private val expense = EntryId("dinner")

    private val state: LedgerState = TestLedger().run {
        me()
        person("X")
        person("X old", id = alias.value, mergedInto = x)
        person("Y")
        group("Trip", members = listOf(ME, x))
        transfer(ME, x, eur(5_000), id = loan.value)
        transfer(alias, ME, eur(2_000), kind = TransferKind.REPAYMENT, id = repayment.value)
        expense(ME, eur(3_000), mapOf(ME to 1_000L, x to 2_000L), group = trip, id = expense.value)
        expense(ME, eur(900), mapOf(ME to 450L, y to 450L), deleted = true)
        state
    }.let { s ->
        s.with(s.transfers.getValue(loan).copy(note = "@lunch"))
            .let { it.with(it.transfers.getValue(repayment).copy(occurredOn = D0.plusDays(1))) }
            .let { it.with(it.expenses.getValue(expense).copy(occurredOn = D0.plusDays(2))) }
    }

    private fun report(scope: ExportScope = ExportScope.FullLedger, from: LocalDate? = null, to: LocalDate? = null, excel: Boolean = false): Pair<LedgerResult<Int>, String> {
        val out = ByteArrayOutputStream()
        val result = export.report(state, scope, from, to, excel, out)
        return result to out.toByteArray().toString(Charsets.UTF_8)
    }

    private val header = "date,type,description,group,paid_by,from,to,amount,currency,my_share,balance_effect_for_me,effect_currency,account,note\r\n"
    private val loanRow = "2026-01-01,loan,,,,Me,X,50.00,EUR,,50.00,EUR,,'@lunch\r\n"
    private val repaymentRow = "2026-01-02,repayment,,,,X,Me,20.00,EUR,,-20.00,EUR,,\r\n"
    private val expenseRow get() = "2026-01-03,expense,Expense $expense,Trip,Me,,,30.00,EUR,10.00,20.00,EUR,,\r\n"

    @Test
    fun `one row per active entry by date, with canonical names and my side`() {
        report() shouldBe (LedgerResult.Ok(3) to header + loanRow + repaymentRow + expenseRow)
    }

    @Test
    fun `scopes and date ranges pick the entries`() {
        report(ExportScope.Group(trip)) shouldBe (LedgerResult.Ok(1) to header + expenseRow)
        report(ExportScope.Person(alias)).first shouldBe LedgerResult.Ok(3)
        report(ExportScope.Person(y)) shouldBe (LedgerResult.Ok(0) to header)
        report(from = D0.plusDays(1), to = D0.plusDays(1)) shouldBe (LedgerResult.Ok(1) to header + repaymentRow)
        report(ExportScope.Group(GroupId("nope"))).first shouldBe LedgerResult.Err(LedgerError.NotFound("nope"))
    }

    @Test
    fun `excel-friendly mode writes a BOM and semicolons`() {
        report(excel = true).second shouldStartWith "\uFEFFdate;type;description;group;"
    }
}
