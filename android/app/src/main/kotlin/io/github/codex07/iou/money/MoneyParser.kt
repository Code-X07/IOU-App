package io.github.codex07.iou.money

import java.math.BigDecimal

sealed interface MoneyError {
    data object InvalidAmount : MoneyError
    data class TooManyDecimals(val maxDecimals: Int) : MoneyError
    data object AmountTooLarge : MoneyError
}

sealed interface MoneyParseResult {
    data class Ok(val money: Money) : MoneyParseResult
    data class Err(val error: MoneyError) : MoneyParseResult
}

/** Text → [Money] through [BigDecimal]; never through floating point (spec §3.1). */
object MoneyParser {

    // ASCII digits only: Java's \d does not match other scripts' digits by default.
    private val plainDecimal = Regex("""^\d+([.,]\d+)?$""")

    fun parse(text: String, currency: CurrencyCode): MoneyParseResult {
        val trimmed = text.trim()
        if (!plainDecimal.matches(trimmed)) return MoneyParseResult.Err(MoneyError.InvalidAmount)

        val parsed = BigDecimal(trimmed.replace(',', '.'))
        // stripTrailingZeros() of a zero keeps its scale on old JDKs, so zero is handled apart.
        val value = if (parsed.signum() == 0) BigDecimal.ZERO else parsed.stripTrailingZeros()

        val digits = currency.fractionDigits
        if (value.scale() > digits) return MoneyParseResult.Err(MoneyError.TooManyDecimals(digits))

        val minor = value.movePointRight(digits)
        if (minor > BigDecimal.valueOf(MAX_MINOR)) return MoneyParseResult.Err(MoneyError.AmountTooLarge)

        return MoneyParseResult.Ok(Money(minor.longValueExact(), currency))
    }
}
