package io.github.codex07.iou.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CurrencyCodeTest {

    @Test
    fun `known codes expose their minor-unit digits`() {
        CurrencyCode("EUR").fractionDigits shouldBe 2
        CurrencyCode("JPY").fractionDigits shouldBe 0
        CurrencyCode("BHD").fractionDigits shouldBe 3
    }

    @Test
    fun `parse trims and uppercases`() {
        CurrencyCode.parse(" sek ") shouldBe CurrencyCode("SEK")
    }

    @Test
    fun `parse rejects unknown codes, malformed codes and codes without minor units`() {
        CurrencyCode.parse("QQQ") shouldBe null
        CurrencyCode.parse("XAU") shouldBe null
        CurrencyCode.parse("EURO") shouldBe null
        CurrencyCode.parse("") shouldBe null
    }

    @Test
    fun `the constructor rejects unsupported codes`() {
        shouldThrow<IllegalArgumentException> { CurrencyCode("QQQ") }
        shouldThrow<IllegalArgumentException> { CurrencyCode("eur") }
    }

    @Test
    fun `toString is the bare code`() {
        CurrencyCode("EUR").toString() shouldBe "EUR"
    }
}
