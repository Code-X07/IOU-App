package io.github.codex07.iou.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MoneyTest {

    private val eur = CurrencyCode("EUR")
    private val sek = CurrencyCode("SEK")

    @Test
    fun `adds, subtracts and negates within one currency`() {
        Money(1050, eur) + Money(250, eur) shouldBe Money(1300, eur)
        Money(1050, eur) - Money(2000, eur) shouldBe Money(-950, eur)
        -Money(5, eur) shouldBe Money(-5, eur)
    }

    @Test
    fun `mixing currencies is a programming error`() {
        shouldThrow<IllegalArgumentException> { Money(1, eur) + Money(1, sek) }
        shouldThrow<IllegalArgumentException> { Money(1, eur) - Money(1, sek) }
    }

    @Test
    fun `overflow throws instead of wrapping`() {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, eur) + Money(1, eur) }
        shouldThrow<ArithmeticException> { Money(Long.MIN_VALUE, eur) - Money(1, eur) }
        shouldThrow<ArithmeticException> { -Money(Long.MIN_VALUE, eur) }
    }

    @Test
    fun `formats with the currency's minor-unit digits`() {
        Money(1234, eur).toPlainString() shouldBe "12.34"
        Money(-5, eur).toPlainString() shouldBe "-0.05"
        Money(0, eur).toPlainString() shouldBe "0.00"
        Money(100, CurrencyCode("JPY")).toPlainString() shouldBe "100"
        Money(1234, CurrencyCode("BHD")).toPlainString() shouldBe "1.234"
        Money(5000, eur).toString() shouldBe "50.00 EUR"
    }

    @Test
    fun `MAX_MINOR is ten to the thirteenth`() {
        MAX_MINOR shouldBe 10_000_000_000_000L
    }

    @Test
    fun `zero has no minor units`() {
        Money.zero(eur) shouldBe Money(0, eur)
    }
}
