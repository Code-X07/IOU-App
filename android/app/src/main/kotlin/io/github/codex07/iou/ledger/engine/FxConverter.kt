package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Exact currency conversion (spec §4.5). A rate is quote major units per one base major unit. */
object FxConverter {

    const val MAX_RATE_DIGITS = 34

    fun validateRate(rate: String): LedgerResult<BigDecimal> {
        val parsed = runCatching { BigDecimal(rate.trim()) }.getOrNull()
        val valid = parsed != null && parsed.signum() > 0 && parsed.stripTrailingZeros().precision() <= MAX_RATE_DIGITS
        return if (valid) LedgerResult.Ok(parsed!!) else LedgerResult.Err(LedgerError.InvalidRate(rate))
    }

    /** `HALF_EVEN(amountMinor × rate × 10^(fd(to) − fd(from)))`, exact up to that one rounding. */
    fun convert(amount: Money, to: CurrencyCode, rate: String): LedgerResult<Money> = ledgerResult {
        val parsedRate = validateRate(rate).bind()
        val shift = to.fractionDigits - amount.currency.fractionDigits
        val rounded = BigDecimal.valueOf(amount.amountMinor)
            .multiply(parsedRate)
            .scaleByPowerOfTen(shift)
            .setScale(0, RoundingMode.HALF_EVEN)
        ensure(rounded <= BigDecimal.valueOf(MAX_MINOR)) { LedgerError.AmountTooLarge }
        val minor = rounded.longValueExact()
        ensure(!(amount.amountMinor > 0 && minor == 0L)) { LedgerError.ConvertedToZero(amount, to) }
        Money(minor, to)
    }

    /** Display rate of a bank-actual snapshot: charged ÷ paid in major units (spec §4.5). */
    fun deriveRate(paid: Money, charged: Money): String {
        require(paid.amountMinor > 0) { "Paid amount must be positive" }
        val shift = paid.currency.fractionDigits - charged.currency.fractionDigits
        return BigDecimal.valueOf(charged.amountMinor)
            .scaleByPowerOfTen(shift)
            .divide(BigDecimal.valueOf(paid.amountMinor), MathContext.DECIMAL128)
            .canonical()
    }

    fun invert(rate: String): String =
        BigDecimal.ONE.divide(BigDecimal(rate), MathContext.DECIMAL128).canonical()

    /** Rate base→quote from `1 home = homeToBase base` and `1 home = homeToQuote quote`. */
    fun cross(homeToBase: String, homeToQuote: String): String =
        BigDecimal(homeToQuote).divide(BigDecimal(homeToBase), MathContext.DECIMAL128).canonical()

    private fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()
}
