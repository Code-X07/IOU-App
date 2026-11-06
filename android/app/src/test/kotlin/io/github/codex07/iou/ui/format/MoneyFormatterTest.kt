package io.github.codex07.iou.ui.format

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.util.Locale
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoneyFormatterTest {
    @Test fun `hiding masks the complete value regardless of currency or sign`() {
        listOf("EUR", "USD", "JPY", "KWD").forEach { currency ->
            assertEquals("••••", MoneyFormatter.format(Money(-1250, CurrencyCode(currency)), hidden = true))
        }
    }
    @Test fun `money keeps currency fraction digits and explicit codes`() {
        assertEquals("€12.50 EUR", MoneyFormatter.format(Money(1250, CurrencyCode("EUR")), locale = Locale.US))
        assertEquals("¥1,250 JPY", MoneyFormatter.format(Money(1250, CurrencyCode("JPY")), locale = Locale.US))
        val dinar = MoneyFormatter.format(Money(1250, CurrencyCode("KWD")), locale = Locale.US)
        assertTrue(dinar.contains("1.250")); assertTrue(dinar.contains("KWD"))
    }
    @Test fun `magnitude and signed styles do not change the stored money`() {
        val money = Money(-1250, CurrencyCode("EUR"))
        assertEquals("€12.50 EUR", MoneyFormatter.format(money, magnitude = true, locale = Locale.US))
        assertEquals(-1250L, money.amountMinor)
        assertTrue(MoneyFormatter.format(money.copy(amountMinor = 1250), signed = true, locale = Locale.US).startsWith("+"))
    }
    @Test fun `large read-model values are formatted without binary rounding`() {
        val formatted = MoneyFormatter.format(Money(9007199254740991L, CurrencyCode("EUR")), locale = Locale.US)
        assertEquals("€90,071,992,547,409.91 EUR", formatted)
    }
}
