package io.github.codex07.iou.data

import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.fx.RateCache
import io.github.codex07.iou.money.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/**
 * The rate cache in the live database's `fx_rate_cache` table (spec §3.3). It isn't ledger data: the journal
 * never writes it, and a rate for the same pair and date replaces the old one.
 */
class RoomRateCache(private val databases: DatabaseProvider) : RateCache {

    override suspend fun load(): List<CachedRate> = databases.database().ledgerDao().rates().map { it.toCachedRate() }

    /**
     * Saves outside the store's lock, so a restore may be swapping databases meanwhile; then there is no open
     * database, or a closed one (`IllegalStateException`). Such rates stay in memory only.
     */
    override suspend fun save(rates: List<CachedRate>) {
        try {
            databases.database().ledgerDao().upsertRates(rates.map { it.toRow() })
        } catch (_: IllegalStateException) {
        }
    }
}

fun CachedRate.toRow() = FxRateRow(base.code, quote.code, rateDate.toString(), rate, fetchedAt.toString())

fun FxRateRow.toCachedRate() = CachedRate(
    base = CurrencyCode(base), quote = CurrencyCode(quote), rateDate = LocalDate.parse(rateDate), rate = rate,
    fetchedAt = Instant.parse(fetchedAt),
)
