package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyError
import io.github.codex07.iou.money.MoneyParser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerResultTest {

    private val eur = CurrencyCode("EUR")

    @Test
    fun `map and flatMap transform only Ok`() {
        val failed: LedgerResult<Int> = LedgerResult.Err(LedgerError.SelfTransfer)
        LedgerResult.Ok(2).map { it * 3 } shouldBe LedgerResult.Ok(6)
        failed.map { it + 1 } shouldBe LedgerResult.Err(LedgerError.SelfTransfer)
        LedgerResult.Ok(2).flatMap { LedgerResult.Err(LedgerError.CannotModifyMe) } shouldBe
            LedgerResult.Err(LedgerError.CannotModifyMe)
    }

    @Test
    fun `getOrNull and errorOrNull pick the matching side`() {
        LedgerResult.Ok("x").getOrNull() shouldBe "x"
        LedgerResult.Ok("x").errorOrNull() shouldBe null
        LedgerResult.Err(LedgerError.LedgerReplaced).getOrNull() shouldBe null
        LedgerResult.Err(LedgerError.LedgerReplaced).errorOrNull() shouldBe LedgerError.LedgerReplaced
    }

    @Test
    fun `the builder returns the first failure and skips the rest`() {
        var reachedEnd = false
        val result = ledgerResult {
            val a = LedgerResult.Ok(1).bind()
            ensure(a == 2) { LedgerError.ZeroWeights }
            reachedEnd = true
            a
        }
        result shouldBe LedgerResult.Err(LedgerError.ZeroWeights)
        reachedEnd shouldBe false

        ledgerResult { LedgerResult.Ok(1).bind() + LedgerResult.Ok(2).bind() } shouldBe LedgerResult.Ok(3)
        ledgerResult<Int> { fail(LedgerError.NoParticipants) } shouldBe LedgerResult.Err(LedgerError.NoParticipants)
    }

    @Test
    fun `nested builders keep their own failures`() {
        val outer = ledgerResult {
            val inner = ledgerResult<Int> { fail(LedgerError.SelfTransfer) }
            inner.errorOrNull()
        }
        outer shouldBe LedgerResult.Ok(LedgerError.SelfTransfer)
    }

    @Test
    fun `money errors map one to one`() {
        MoneyError.InvalidAmount.toLedgerError() shouldBe LedgerError.InvalidAmount
        MoneyError.TooManyDecimals(2).toLedgerError() shouldBe LedgerError.TooManyDecimals(2)
        MoneyError.AmountTooLarge.toLedgerError() shouldBe LedgerError.AmountTooLarge
        MoneyParser.parse("1.5", eur).toLedgerResult() shouldBe LedgerResult.Ok(Money(150, eur))
        MoneyParser.parse("x", eur).toLedgerResult() shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
    }
}
