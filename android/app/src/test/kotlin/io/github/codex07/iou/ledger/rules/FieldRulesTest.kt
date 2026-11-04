package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FieldRulesTest {

    @Test
    fun `IBANs are normalized and mod-97 checked`() {
        FieldRules.normalizeHandleValue(HandleType.IBAN, " de89 3704 0044 0532 0130 00 ") shouldBe
            LedgerResult.Ok("DE89370400440532013000")
        FieldRules.normalizeHandleValue(HandleType.IBAN, "DE89370400440532013001") shouldBe
            LedgerResult.Err(LedgerError.InvalidIban("DE89370400440532013001"))
        FieldRules.normalizeHandleValue(HandleType.IBAN, "not an iban") shouldBe
            LedgerResult.Err(LedgerError.InvalidIban("not an iban"))
        FieldRules.isValidIban("GB82WEST12345698765432") shouldBe true
    }

    @Test
    fun `revtags lose the at sign, phones become E164`() {
        FieldRules.normalizeHandleValue(HandleType.REVTAG, "@alex.s") shouldBe LedgerResult.Ok("alex.s")
        FieldRules.normalizeHandleValue(HandleType.REVTAG, "alex s") shouldBe LedgerResult.Err(LedgerError.InvalidField("value"))
        FieldRules.normalizeHandleValue(HandleType.PHONE, "+46 70-123 45 67") shouldBe LedgerResult.Ok("+46701234567")
        FieldRules.normalizeHandleValue(HandleType.PHONE, "0701234567") shouldBe LedgerResult.Err(LedgerError.InvalidField("value"))
        FieldRules.normalizeHandleValue(HandleType.OTHER, "  ") shouldBe LedgerResult.Err(LedgerError.InvalidField("value"))
    }

    @Test
    fun `valid rows pass`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        ledger.handle(alex)
        ledger.transfer(ME, alex, eur(100))
        ledger.expense(ME, eur(100), mapOf(ME to 50L, alex to 50L))
        val state = ledger.state

        FieldRules.check(state.people.values + state.handles.values + state.entries).shouldBeEmpty()
    }

    @Test
    fun `blank names, bad amounts and broken shares are reported`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val t = ledger.transfer(ME, alex, eur(100))
        val e = ledger.expense(ME, eur(100), mapOf(ME to 50L, alex to 50L))
        val state = ledger.state

        FieldRules.check(listOf(state.people.getValue(alex).copy(displayName = " "))) shouldBe
            listOf(RuleViolation(0, LedgerError.InvalidField("displayName")))
        FieldRules.check(listOf(state.transfers.getValue(t).copy(amount = eur(0)))) shouldBe
            listOf(RuleViolation(0, LedgerError.InvalidAmount))
        FieldRules.check(listOf(state.transfers.getValue(t).copy(effectAmount = eur(MAX_MINOR + 1)))) shouldBe
            listOf(RuleViolation(0, LedgerError.AmountTooLarge))

        val expense = state.expenses.getValue(e)
        FieldRules.check(listOf(expense.copy(shares = listOf(ExpenseShare(ME, 50), ExpenseShare(alex, 40))))) shouldBe
            listOf(RuleViolation(0, LedgerError.CorruptData("shares of ${expense.id} sum to 90, not 100")))
        FieldRules.check(listOf(expense.copy(shares = emptyList()))) shouldBe
            listOf(RuleViolation(0, LedgerError.NoParticipants))
        FieldRules.check(listOf(expense.copy(shares = listOf(ExpenseShare(ME, 150), ExpenseShare(alex, -50))))) shouldBe
            listOf(RuleViolation(0, LedgerError.NegativeInput(alex)))
        FieldRules.check(listOf(expense.copy(description = ""))) shouldBe
            listOf(RuleViolation(0, LedgerError.InvalidField("description")))
    }

    @Test
    fun `share sums that overflow are corrupt data, not a crash`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val e = ledger.expense(ME, eur(100), mapOf(ME to 50L, alex to 50L))
        val expense = ledger.state.expenses.getValue(e)

        FieldRules.check(listOf(expense.copy(shares = listOf(ExpenseShare(ME, Long.MAX_VALUE), ExpenseShare(alex, Long.MAX_VALUE))))) shouldBe
            listOf(RuleViolation(0, LedgerError.CorruptData("shares of ${expense.id} sum to more than $MAX_MINOR")))
    }

    @Test
    fun `stored inputs must suit the split type`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val e = ledger.expense(ME, eur(1000), mapOf(ME to 500L, alex to 500L))
        val expense = ledger.state.expenses.getValue(e)
        fun with(type: SplitType, me: Long?, other: Long?) =
            FieldRules.check(listOf(expense.copy(splitType = type, shares = listOf(ExpenseShare(ME, 500, me), ExpenseShare(alex, 500, other)))))

        with(SplitType.PERCENT, 5_000, 5_000).shouldBeEmpty()
        with(SplitType.PERCENT, 10_000, 10_000) shouldBe listOf(RuleViolation(0, LedgerError.PercentNotHundred(20_000)))
        with(SplitType.SHARES, 1, null) shouldBe listOf(RuleViolation(0, LedgerError.MissingInput(alex)))
        with(SplitType.SHARES, 1, -1) shouldBe listOf(RuleViolation(0, LedgerError.NegativeInput(alex)))
        with(SplitType.SHARES, 0, 0) shouldBe listOf(RuleViolation(0, LedgerError.ZeroWeights))
        with(SplitType.EXACT, 500, 400) shouldBe listOf(RuleViolation(0, LedgerError.ExactRemaining(100)))
        with(SplitType.EQUAL, null, 1) shouldBe
            listOf(RuleViolation(0, LedgerError.CorruptData("EQUAL shares of ${expense.id} carry an input")))
        // Zero weights are accepted per person (Part 2), and the stored allocation is never recomputed.
        FieldRules.check(listOf(expense.copy(splitType = SplitType.SHARES, shares = listOf(ExpenseShare(ME, 999, 0), ExpenseShare(alex, 1, 3))))).shouldBeEmpty()
    }

    @Test
    fun `stored rows of merged people keep their own input limits`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val alias = ledger.person("Alex 2", mergedInto = alex)
        val e = ledger.expense(
            ME, eur(1000), mapOf(ME to 334L, alex to 333L, alias to 333L), splitType = SplitType.SHARES,
            inputs = mapOf(ME to SplitResolver.MAX_WEIGHT, alex to SplitResolver.MAX_WEIGHT, alias to SplitResolver.MAX_WEIGHT),
        )

        FieldRules.check(listOf(ledger.state.expenses.getValue(e))).shouldBeEmpty()
    }

    @Test
    fun `stored handle values must already be normalized`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val handle = ledger.handle(alex, HandleType.IBAN, value = "de89 3704 0044 0532 0130 00")

        FieldRules.check(listOf(ledger.state.handles.getValue(handle))) shouldBe
            listOf(RuleViolation(0, LedgerError.InvalidIban("de89 3704 0044 0532 0130 00")))
    }
}
