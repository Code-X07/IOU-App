package io.github.codex07.iou.fake

import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.money.CurrencyCode
import java.time.LocalDate

/** Fixed demo rates: units of each currency per 1 EUR. */
object FakeRates {
    val perEur: Map<String, String> = mapOf(
        "EUR" to "1", "SEK" to "11.5", "USD" to "1.08", "GBP" to "0.86", "NOK" to "11.7",
        "DKK" to "7.46", "CHF" to "0.94", "JPY" to "160", "PLN" to "4.3",
    )

    /** Units of [to] per one unit of [from]; null for currencies outside the table. */
    fun rate(from: CurrencyCode, to: CurrencyCode): String? {
        if (from == to) return "1"
        val fromPerEur = perEur[from.code] ?: return null
        val toPerEur = perEur[to.code] ?: return null
        return FxConverter.cross(homeToBase = fromPerEur, homeToQuote = toPerEur)
    }

    /** A fresh ECB quote at the demo rate for any [date]; `RateRequired` outside the table. */
    fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote> {
        val rate = rate(from, to) ?: return LedgerResult.Err(LedgerError.RateRequired(from, to))
        return LedgerResult.Ok(FxQuote(from, to, rate, date, FxSource.ECB_FRANKFURTER, isStale = false))
    }
}
