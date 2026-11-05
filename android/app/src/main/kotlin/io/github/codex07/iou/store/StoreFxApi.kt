package io.github.codex07.iou.store

import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

/** Where [StoreFxApi] gets looked-up rates: the fake's demo table or the real lookup chain (spec §4.5). */
fun interface QuoteSource {
    suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote>
}

/** [FxApi] for both flavors: lookups come from [source]; typed and bank-actual rates need no source. */
class StoreFxApi(private val source: QuoteSource) : FxApi {

    override suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote> =
        source.quote(date, from, to)

    override fun userRate(date: LocalDate, from: CurrencyCode, to: CurrencyCode, rate: String): LedgerResult<FxQuote> =
        FxConverter.validateRate(rate).map { FxQuote(from, to, rate.trim(), date, FxSource.USER_ENTERED, isStale = false) }

    override fun rateFromCharged(date: LocalDate, paid: Money, charged: Money): LedgerResult<FxQuote> {
        if (paid.amountMinor <= 0 || charged.amountMinor <= 0) return LedgerResult.Err(LedgerError.InvalidAmount)
        val rate = FxConverter.deriveRate(paid, charged)
        return LedgerResult.Ok(FxQuote(paid.currency, charged.currency, rate, date, FxSource.BANK_ACTUAL, isStale = false))
    }
}
