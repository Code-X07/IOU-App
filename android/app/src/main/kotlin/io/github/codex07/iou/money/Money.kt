package io.github.codex07.iou.money

import java.math.BigDecimal

/** Largest amount any stored money value may have, in minor units (spec §3.1). */
const val MAX_MINOR: Long = 10_000_000_000_000L

/**
 * An amount in minor units of one currency. Arithmetic is exact: an overflow throws
 * [ArithmeticException], which the write path reports as `AmountTooLarge`.
 */
data class Money(val amountMinor: Long, val currency: CurrencyCode) {

    operator fun plus(other: Money): Money =
        Money(Math.addExact(amountMinor, sameCurrency(other).amountMinor), currency)

    operator fun minus(other: Money): Money =
        Money(Math.subtractExact(amountMinor, sameCurrency(other).amountMinor), currency)

    operator fun unaryMinus(): Money = Money(Math.negateExact(amountMinor), currency)

    /** Major units with exactly the currency's minor digits and a `.` separator, e.g. "12.34". */
    fun toPlainString(): String =
        BigDecimal.valueOf(amountMinor, currency.fractionDigits).toPlainString()

    override fun toString(): String = "${toPlainString()} ${currency.code}"

    private fun sameCurrency(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other
    }

    companion object {
        fun zero(currency: CurrencyCode): Money = Money(0, currency)
    }
}
