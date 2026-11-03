package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InputsTest {

    private val eur = CurrencyCode("EUR")

    @Test
    fun `amount parses through MoneyParser and maps its errors`() {
        Inputs.amount("12,50", eur) shouldBe LedgerResult.Ok(Money(1250, eur))
        Inputs.amount("1.005", eur) shouldBe LedgerResult.Err(LedgerError.TooManyDecimals(2))
        Inputs.amount("ten", eur) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
    }

    @Test
    fun `currency accepts lenient input and names unknown codes`() {
        Inputs.currency(" sek") shouldBe LedgerResult.Ok(CurrencyCode("SEK"))
        Inputs.currency("ZZZ ") shouldBe LedgerResult.Err(LedgerError.UnknownCurrency("ZZZ"))
    }
}
