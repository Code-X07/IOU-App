package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.BHD
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.IDR
import io.github.codex07.iou.testutil.JPY
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class FxConverterTest {

    @Test
    fun `converts across differing minor-unit digits`() {
        FxConverter.convert(Money(100, JPY), EUR, "0.006") shouldBe LedgerResult.Ok(Money(60, EUR))
        FxConverter.convert(Money(100_000, SEK), EUR, "0.09") shouldBe LedgerResult.Ok(Money(9_000, EUR))
        FxConverter.convert(Money(1_000, BHD), JPY, "400") shouldBe LedgerResult.Ok(Money(400, JPY))
    }

    @Test
    fun `rounds half to even once, at the end`() {
        FxConverter.convert(Money(25, EUR), USD, "0.1") shouldBe LedgerResult.Ok(Money(2, USD))
        FxConverter.convert(Money(35, EUR), USD, "0.1") shouldBe LedgerResult.Ok(Money(4, USD))
    }

    @Test
    fun `a positive amount that converts to zero is rejected`() {
        FxConverter.convert(Money(100, IDR), EUR, "0.000055") shouldBe
            LedgerResult.Err(LedgerError.ConvertedToZero(Money(100, IDR), EUR))
    }

    @Test
    fun `results above MAX_MINOR are rejected`() {
        FxConverter.convert(Money(MAX_MINOR, EUR), USD, "1.0000001") shouldBe LedgerResult.Err(LedgerError.AmountTooLarge)
        FxConverter.convert(Money(MAX_MINOR, EUR), USD, "1") shouldBe LedgerResult.Ok(Money(MAX_MINOR, USD))
    }

    @Test
    fun `invalid rates are rejected`() {
        val thirtyFiveDigits = "1." + "0".repeat(33) + "1"
        val thirtyFourDigits = "1." + "0".repeat(32) + "1"
        listOf("0", "-1", "abc", "", thirtyFiveDigits, "1E65", "1E-65", "1E999999999", "1E-999999999").forEach { rate ->
            FxConverter.validateRate(rate) shouldBe LedgerResult.Err(LedgerError.InvalidRate(rate))
            FxConverter.convert(Money(100, EUR), USD, rate) shouldBe LedgerResult.Err(LedgerError.InvalidRate(rate))
        }
        FxConverter.validateRate("0.09") shouldBe LedgerResult.Ok(BigDecimal("0.09"))
        FxConverter.validateRate(thirtyFourDigits) shouldBe LedgerResult.Ok(BigDecimal(thirtyFourDigits))
        FxConverter.validateRate("1E64") shouldBe LedgerResult.Ok(BigDecimal("1E64"))
        FxConverter.validateRate("1E-64") shouldBe LedgerResult.Ok(BigDecimal("1E-64"))
    }

    @Test
    fun `derives the bank-actual rate from paid and charged amounts`() {
        FxConverter.deriveRate(Money(100_000, SEK), Money(8_765, EUR)) shouldBe "0.08765"
        FxConverter.deriveRate(Money(100, JPY), Money(60, EUR)) shouldBe "0.006"
    }

    @Test
    fun `inverts and crosses rates with DECIMAL128`() {
        FxConverter.invert("0.08") shouldBe "12.5"
        FxConverter.invert("3") shouldBe "0." + "3".repeat(34)
        // 1 EUR = 11.5 SEK and 1 EUR = 1.15 USD, so 1 SEK = 0.1 USD.
        FxConverter.cross(homeToBase = "11.5", homeToQuote = "1.15") shouldBe "0.1"
    }
}
