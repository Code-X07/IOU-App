package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
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
    fun `several payers are listed with their amounts in the paid currency`() {
        val ledger = TestLedger()
        ledger.me()
        val alice = ledger.person("Alice")
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alice, bob))
        ledger.expense(
            alice, eur(10_000), mapOf(ME to 5_000L, bob to 5_000L), group = trip, payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(alice, 6_000, 6_000), ExpensePayer(bob, 4_000, 4_000)),
        )
        ledger.expense(
            alice, sek(1_001), mapOf(ME to 1_001L), group = trip,
            payers = listOf(ExpensePayer(alice, 501), ExpensePayer(bob, 500)),
        )
        // EQUAL payers as an import from another installation stores them: the extra cent with Bob, though Alice's ID
        // sorts first. The report keeps the stored amounts, unconverted and converted alike.
        ledger.expense(
            alice, eur(10_001), mapOf(ME to 10_001L), group = trip,
            payers = listOf(ExpensePayer(alice, 5_000), ExpensePayer(bob, 5_001)),
        )
        ledger.expense(
            alice, sek(1_001), mapOf(ME to 91L), group = trip, effectTotal = eur(91),
            payers = listOf(ExpensePayer(alice, 45), ExpensePayer(bob, 46)),
        )
        val out = ByteArrayOutputStream()
        LedgerExport("0.1.0").report(ledger.state, ExportScope.FullLedger, null, null, excelFriendly = false, out)
        val paidBy = out.toString(Charsets.UTF_8).lines().drop(1).filter { it.isNotEmpty() }.map { it.split(',')[4] }

        paidBy shouldBe listOf("Alice 60.00; Bob 40.00", "Alice 5.01; Bob 5.00", "Alice 50.00; Bob 50.01", "Alice 5.00; Bob 5.01")
    }

    @Test
    fun `excel-friendly mode writes a BOM and semicolons`() {
        report(excel = true).second shouldStartWith "\uFEFFdate;type;description;group;"
    }
}
