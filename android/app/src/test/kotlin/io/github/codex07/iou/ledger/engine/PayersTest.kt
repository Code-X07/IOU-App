package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.rules.FieldRules
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Payer rows (spec §3.2, §4.2, §4.4, ADR-0012): engine contributions, resolution, field rule, diff. */
class PayersTest {

    @Test
    fun `two payers give effects from contributions, largest creditor and debtor first`() {
        val ledger = TestLedger()
        ledger.me()
        val alice = ledger.person("Alice")
        val bob = ledger.person("Bob")
        val carol = ledger.person("Carol")
        val group = ledger.group("Dinner", members = listOf(ME, alice, bob, carol))
        val dinner = ledger.expense(
            alice, eur(10_000), mapOf(alice to 3_334L, bob to 3_333L, carol to 3_333L), group = group,
            splitType = SplitType.EQUAL, payers = listOf(ExpensePayer(alice, 6_000), ExpensePayer(bob, 4_000)),
        )

        val balances = LedgerEngine.compute(ledger.state)

        balances.groupNets[group] shouldBe mapOf(
            alice to mapOf(EUR to 2_666L),
            bob to mapOf(EUR to 667L),
            carol to mapOf(EUR to -3_333L),
        )
        balances.flows[group] shouldBe mapOf(
            (alice to carol) to mapOf(EUR to 2_666L),
            (bob to carol) to mapOf(EUR to 667L),
        )
        LedgerEngine.entryEffects(ledger.state.expenses.getValue(dinner), IdentityResolver(ledger.state))
            .map { it.amount.amountMinor } shouldBe listOf(2_666L, 667L)
    }

    @Test
    fun `outside groups the net is what I paid minus my share`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.expense(
            ME, eur(10_000), mapOf(ME to 5_000L, x to 5_000L),
            payers = listOf(ExpensePayer(ME, 3_000), ExpensePayer(x, 7_000)),
        )

        LedgerEngine.compute(ledger.state).net(x) shouldBe mapOf(EUR to -2_000L)
    }

    @Test
    fun `a payer whose merged alias also holds a share pays for that share first`() {
        val ledger = TestLedger()
        ledger.me()
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB", mergedInto = alexA)
        ledger.expense(
            alexB, eur(9_000), mapOf(ME to 3_000L, alexA to 3_000L, alexB to 3_000L),
            group = ledger.group("Trip", members = listOf(ME, alexA)),
        )

        // Alex paid 9 000 and holds both alias shares (6 000): only my 3 000 is owed.
        LedgerEngine.compute(ledger.state).groupNets.values.single() shouldBe mapOf(
            alexA to mapOf(EUR to 3_000L),
            ME to mapOf(EUR to -3_000L),
        )
    }

    @Test
    fun `equal payers split the effect total with the extra cent to the lowest id`() {
        val alice = ledger().person("Alice")
        val bob = ledger().person("Bob")

        SplitResolver.resolvePayers(PayerSplit.EQUAL, 10_001, 10_001, listOf(SplitInput(bob), SplitInput(alice))) shouldBe
            LedgerResult.Ok(listOf(ExpensePayer(bob, 5_000), ExpensePayer(alice, 5_001)))
        SplitResolver.resolvePayers(PayerSplit.EQUAL, 10_000, 10_000, listOf(SplitInput(alice), SplitInput(bob))) shouldBe
            LedgerResult.Ok(listOf(ExpensePayer(alice, 5_000), ExpensePayer(bob, 5_000)))
    }

    @Test
    fun `exact payers must add up and each must have paid something`() {
        val alice = ledger().person("Alice")
        val bob = ledger().person("Bob")
        fun exact(a: Long?, b: Long?) =
            SplitResolver.resolvePayers(PayerSplit.EXACT, 10_000, 10_000, listOf(SplitInput(alice, a), SplitInput(bob, b)))

        exact(6_000, 4_000) shouldBe LedgerResult.Ok(listOf(ExpensePayer(alice, 6_000, 6_000), ExpensePayer(bob, 4_000, 4_000)))
        exact(6_000, 3_000) shouldBe LedgerResult.Err(LedgerError.PaidRemaining(1_000))
        exact(10_000, 0) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
        exact(10_000, null) shouldBe LedgerResult.Err(LedgerError.MissingInput(bob))
        exact(11_000, -1_000) shouldBe LedgerResult.Err(LedgerError.NegativeInput(bob))
        SplitResolver.resolvePayers(PayerSplit.EXACT, 10_000, 10_000, emptyList()) shouldBe LedgerResult.Err(LedgerError.NoPayers)
        SplitResolver.resolvePayers(PayerSplit.EQUAL, 10_000, 10_000, listOf(SplitInput(alice), SplitInput(alice))) shouldBe
            LedgerResult.Err(LedgerError.DuplicatePayer(alice))
    }

    @Test
    fun `exact payers of a converted expense are weights for the converted total`() {
        val alice = ledger().person("Alice")
        val bob = ledger().person("Bob")
        // 1 000 SEK paid 600 / 400, converted to €87.65.
        val result = SplitResolver.resolvePayers(
            PayerSplit.EXACT, 8_765, 100_000, listOf(SplitInput(alice, 60_000), SplitInput(bob, 40_000)),
        )

        result shouldBe LedgerResult.Ok(listOf(ExpensePayer(alice, 5_259, 60_000), ExpensePayer(bob, 3_506, 40_000)))
    }

    @Test
    fun `stored payer rows are checked as stored`() {
        val ledger = ledger()
        val x = ledger.person("X")
        fun check(payers: List<ExpensePayer>, split: PayerSplit = PayerSplit.EQUAL): List<RuleViolation> {
            val id = ledger.expense(ME, sek(100_000), mapOf(ME to 50_000L, x to 50_000L), payers = payers, payerSplit = split)
            return FieldRules.check(listOf(ledger.state.expenses.getValue(id)))
        }

        check(listOf(ExpensePayer(ME, 30_000), ExpensePayer(x, 70_000))).shouldBeEmpty()
        check(listOf(ExpensePayer(ME, 30_000, 30_000), ExpensePayer(x, 70_000, 70_000)), PayerSplit.EXACT).shouldBeEmpty()
        check(emptyList()) shouldBe listOf(RuleViolation(0, LedgerError.NoPayers))
        check(listOf(ExpensePayer(ME, 30_000), ExpensePayer(ME, 70_000))) shouldBe
            listOf(RuleViolation(0, LedgerError.DuplicatePayer(ME)))
        check(listOf(ExpensePayer(ME, 30_000), ExpensePayer(x, 60_000))).single().error::class shouldBe
            LedgerError.CorruptData::class
        check(listOf(ExpensePayer(ME, 30_000, 30_000), ExpensePayer(x, 70_000))).single().error::class shouldBe
            LedgerError.CorruptData::class
        check(listOf(ExpensePayer(ME, 30_000, 30_000), ExpensePayer(x, 70_000, 60_000)), PayerSplit.EXACT) shouldBe
            listOf(RuleViolation(0, LedgerError.PaidRemaining(10_000)))
    }

    @Test
    fun `payers are diffed per person`() {
        val ledger = ledger()
        val x = ledger.person("X")
        val id = ledger.expense(ME, eur(10_000), mapOf(ME to 5_000L, x to 5_000L))
        val before = ledger.state.expenses.getValue(id)
        val after = before.copy(
            payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(ME, 3_000, 3_000), ExpensePayer(x, 7_000, 7_000)),
        )

        val changes = RevisionDiff.diff(before, after)

        changes shouldContain FieldChange("payerSplit", "EQUAL", "EXACT")
        changes shouldContain FieldChange("paid[${ME.value}]", "100.00 EUR", "30.00 EUR")
        changes shouldContain FieldChange("paid[${x.value}]", null, "70.00 EUR")
        changes shouldContain FieldChange("paidInput[${x.value}]", null, "7000")
    }

    private fun ledger() = TestLedger().also { it.me() }
}
