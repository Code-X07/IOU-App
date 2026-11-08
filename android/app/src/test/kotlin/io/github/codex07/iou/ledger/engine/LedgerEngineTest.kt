package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerEngineTest {

    @Test
    fun `a loan and a partial repayment give the remaining balance`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.transfer(ME, x, eur(5_000))
        ledger.transfer(x, ME, eur(2_000), kind = TransferKind.REPAYMENT)

        LedgerEngine.compute(ledger.state).net(x) shouldBe mapOf(EUR to 3_000L)
    }

    @Test
    fun `currencies stay separate and deleted entries do not count`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.transfer(ME, x, eur(1_000))
        ledger.transfer(x, ME, sek(50_000))
        ledger.transfer(ME, x, eur(9_999), deleted = true)

        LedgerEngine.compute(ledger.state).net(x) shouldBe mapOf(EUR to 1_000L, SEK to -50_000L)
    }

    @Test
    fun `an ad-hoc expense makes the other person owe their share`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.expense(ME, eur(3_000), mapOf(ME to 1_500L, x to 1_500L))
        ledger.expense(x, eur(1_000), mapOf(ME to 1_000L))

        LedgerEngine.compute(ledger.state).net(x) shouldBe mapOf(EUR to 500L)
    }

    @Test
    fun `group example from the spec gives nets, totals and flows`() {
        val ledger = TestLedger()
        ledger.me()
        val a = ledger.person("A")
        val b = ledger.person("B")
        val group = ledger.group("Dinner", members = listOf(ME, a, b))
        ledger.expense(a, eur(6_000), mapOf(a to 2_000L, b to 2_000L, ME to 2_000L), group = group)

        val balances = LedgerEngine.compute(ledger.state)

        balances.groupNets[group] shouldBe mapOf(
            a to mapOf(EUR to 4_000L),
            b to mapOf(EUR to -2_000L),
            ME to mapOf(EUR to -2_000L),
        )
        balances.myGroupNet(group) shouldBe mapOf(EUR to -2_000L)
        balances.iOwe shouldBe mapOf(EUR to 2_000L)
        balances.owedToMe.shouldBeEmpty()
        balances.net(a).shouldBeEmpty()
        balances.flows[group] shouldBe mapOf(
            (a to b) to mapOf(EUR to 2_000L),
            (a to ME) to mapOf(EUR to 2_000L),
        )
    }

    @Test
    fun `an untagged repayment changes only the pairwise balance`() {
        val ledger = TestLedger()
        ledger.me()
        val a = ledger.person("A")
        val group = ledger.group("Dinner", members = listOf(ME, a))
        ledger.expense(a, eur(4_000), mapOf(a to 2_000L, ME to 2_000L), group = group)
        ledger.transfer(ME, a, eur(2_000), kind = TransferKind.REPAYMENT)

        val balances = LedgerEngine.compute(ledger.state)
        balances.myGroupNet(group) shouldBe mapOf(EUR to -2_000L)
        balances.net(a) shouldBe mapOf(EUR to 2_000L)
        balances.owedToMe shouldBe mapOf(EUR to 2_000L)
        balances.iOwe shouldBe mapOf(EUR to 2_000L)
    }

    @Test
    fun `merged records count as one canonical person`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS", mergedInto = alex)
        ledger.transfer(ME, alex, eur(1_000))
        ledger.transfer(ME, alexS, eur(500))

        val balances = LedgerEngine.compute(ledger.state)
        balances.net(alex) shouldBe mapOf(EUR to 1_500L)
        balances.pairwise.keys shouldBe setOf(alex)
    }

    @Test
    fun `entries between two records of one person have no effect, shares of both add up`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS", mergedInto = alex)
        val group = ledger.group("Trip", members = listOf(ME, alex, alexS))
        ledger.transfer(alex, alexS, eur(700), group = group)
        ledger.expense(ME, eur(900), mapOf(alex to 300L, alexS to 300L, ME to 300L), group = group)

        val balances = LedgerEngine.compute(ledger.state)
        balances.groupNets[group] shouldBe mapOf(ME to mapOf(EUR to 600L), alex to mapOf(EUR to -600L))
        // ADR-0012: effects come from per-person contributions, so alex's two share rows net into one effect.
        LedgerEngine.effects(ledger.state).map { it.amount } shouldBe listOf(eur(600))
    }

    @Test
    fun `balances that overflow throw instead of wrapping`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.transfer(ME, x, Money(Long.MAX_VALUE, EUR))
        ledger.transfer(ME, x, eur(1))

        shouldThrow<ArithmeticException> { LedgerEngine.compute(ledger.state) }
    }

    @Test
    fun `totals that overflow throw from compute`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val y = ledger.person("Y")
        ledger.transfer(ME, x, Money(Long.MAX_VALUE, EUR))
        ledger.transfer(ME, y, eur(1))

        shouldThrow<ArithmeticException> { LedgerEngine.compute(ledger.state) }
    }
}
