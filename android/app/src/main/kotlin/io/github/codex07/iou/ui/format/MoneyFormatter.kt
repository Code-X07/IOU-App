package io.github.codex07.iou.ui.format

import io.github.codex07.iou.money.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Presentation only: never converts currencies, totals a ledger, or passes money through floating point. */
object MoneyFormatter {
    fun format(money: Money, hidden: Boolean = false, magnitude: Boolean = false,
               signed: Boolean = false, locale: Locale = Locale.getDefault()): String {
        if (hidden) return "••••"
        val decimal = BigDecimal.valueOf(money.amountMinor, money.currency.fractionDigits)
        val value = if (magnitude) decimal.abs() else decimal
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(money.currency.code)
            minimumFractionDigits = money.currency.fractionDigits
            maximumFractionDigits = money.currency.fractionDigits
        }
        val sign = if (signed && !magnitude && money.amountMinor > 0) "+" else ""
        val formatted = sign + formatter.format(value)
        return if (formatted.contains(money.currency.code)) formatted else "$formatted ${money.currency.code}"
    }
}
