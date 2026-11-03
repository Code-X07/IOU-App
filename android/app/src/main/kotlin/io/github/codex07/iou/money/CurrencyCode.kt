package io.github.codex07.iou.money

import java.util.Currency

/**
 * An ISO 4217 currency code that `java.util.Currency` knows and that has minor units.
 * Codes without minor units (precious metals, `XXX`, …) are not money in this app.
 */
@JvmInline
value class CurrencyCode(val code: String) {

    init {
        require(isSupported(code)) { "Unsupported currency code: $code" }
    }

    val fractionDigits: Int
        get() = Currency.getInstance(code).defaultFractionDigits

    override fun toString(): String = code

    companion object {
        /** Lenient parse for user and file input: trims and uppercases; null when unsupported. */
        fun parse(text: String): CurrencyCode? {
            val code = text.trim().uppercase()
            return if (isSupported(code)) CurrencyCode(code) else null
        }

        private fun isSupported(code: String): Boolean {
            if (code.length != 3 || !code.all { it in 'A'..'Z' }) return false
            val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return false
            return currency.defaultFractionDigits >= 0
        }
    }
}
