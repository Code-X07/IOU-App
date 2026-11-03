package io.github.codex07.iou.money

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MoneyParserTest {

    private val eur = CurrencyCode("EUR")
    private val jpy = CurrencyCode("JPY")
    private val bhd = CurrencyCode("BHD")

    private fun ok(minor: Long, currency: CurrencyCode) = MoneyParseResult.Ok(Money(minor, currency))
    private fun err(error: MoneyError) = MoneyParseResult.Err(error)

    @Test
    fun `parses decimals written with a dot or a comma`() {
        MoneyParser.parse("12.34", eur) shouldBe ok(1234, eur)
        MoneyParser.parse("12,34", eur) shouldBe ok(1234, eur)
        MoneyParser.parse(" 12 ", eur) shouldBe ok(1200, eur)
        MoneyParser.parse("0.5", eur) shouldBe ok(50, eur)
        MoneyParser.parse("0", eur) shouldBe ok(0, eur)
        MoneyParser.parse("0.000", eur) shouldBe ok(0, eur)
        MoneyParser.parse("1.234", bhd) shouldBe ok(1234, bhd)
    }

    @Test
    fun `trailing zeros beyond the minor digits are accepted`() {
        MoneyParser.parse("12.340", eur) shouldBe ok(1234, eur)
        MoneyParser.parse("100.00", jpy) shouldBe ok(100, jpy)
    }

    @Test
    fun `more significant decimals than the currency has are rejected`() {
        MoneyParser.parse("1.5", jpy) shouldBe err(MoneyError.TooManyDecimals(0))
        MoneyParser.parse("0.001", eur) shouldBe err(MoneyError.TooManyDecimals(2))
        MoneyParser.parse("1.2345", bhd) shouldBe err(MoneyError.TooManyDecimals(3))
    }

    @Test
    fun `anything but a plain non-negative decimal is invalid`() {
        listOf("", " ", "-5", "+5", "abc", "1e3", "1.2.3", "1 000", ".5", "5.", "1,000.00", "١٢")
            .forEach { text -> MoneyParser.parse(text, eur) shouldBe err(MoneyError.InvalidAmount) }
    }

    @Test
    fun `MAX_MINOR is the largest accepted amount`() {
        MoneyParser.parse("100000000000.00", eur) shouldBe ok(MAX_MINOR, eur)
        MoneyParser.parse("100000000000.01", eur) shouldBe err(MoneyError.AmountTooLarge)
        MoneyParser.parse("10000000000000", jpy) shouldBe ok(MAX_MINOR, jpy)
        MoneyParser.parse("10000000000001", jpy) shouldBe err(MoneyError.AmountTooLarge)
        MoneyParser.parse("99999999999999999999999999", eur) shouldBe err(MoneyError.AmountTooLarge)
    }

    @Test
    fun `parsing a formatted amount gives the same money back`() = runTest {
        checkAll(Arb.long(0L..MAX_MINOR), Arb.element(eur, jpy, bhd)) { minor, currency ->
            val money = Money(minor, currency)
            MoneyParser.parse(money.toPlainString(), currency) shouldBe MoneyParseResult.Ok(money)
        }
    }
}
