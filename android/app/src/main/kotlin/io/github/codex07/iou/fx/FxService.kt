package io.github.codex07.iou.fx

import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.store.QuoteSource
import io.github.codex07.iou.store.RateLookup
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where fetched rates are kept: the `fx_rate_cache` table in the real flavor. */
interface RateCache {
    suspend fun load(): List<CachedRate>
    suspend fun save(rates: List<CachedRate>)
}

/**
 * The rate lookup chain (spec §4.5) and the rates behind approximate totals. The cache is held in memory
 * after [load], which must run before [quote] returns; quotes wait for it.
 */
class FxService(
    private val cache: RateCache,
    private val frankfurter: FrankfurterClient,
    private val clock: Clock,
) : QuoteSource {

    private val mutex = Mutex()
    private val table = MutableStateFlow<RateTable?>(null)
    private val latestRates = MutableStateFlow(RateLookup.None)

    /** The latest cached rate for each pair, for approximate home-currency totals (spec §4.3). */
    val lookup: StateFlow<RateLookup> = latestRates.asStateFlow()

    /** Reads the cache into memory. Also run after the database is replaced (Part 5). */
    suspend fun load() = mutex.withLock { publish(RateTable(cache.load())) }

    /**
     * 1. The cached rate for [date]. 2. Frankfurter for [date]; its answer is cached and may be for the
     * previous working day, which is the date the quote carries. 3. The most recent cached rate, flagged
     * stale. 4. `RateRequired`: the user enters a rate.
     */
    override suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote> {
        val cached = table.filterNotNull().first()
        cached.on(date, from, to)?.let { return ok(from, to, DatedRate(it, date), stale = false) }
        val fetched = frankfurter.eurRates(date, setOf(from, to))?.let { published ->
            val rows = rows(published)
            val updated = try {
                add(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cached + rows
            }
            updated.on(published.date, from, to)?.let { return ok(from, to, DatedRate(it, published.date), stale = false) }
            updated
        }
        (fetched ?: cached).latest(from, to)?.let { return ok(from, to, it, stale = true) }
        return LedgerResult.Err(LedgerError.RateRequired(from, to))
    }

    /**
     * Caches the latest EUR rates for [currencies] (the daily refresh). True when they were fetched or there
     * is nothing to fetch; false when Frankfurter was unavailable, so the caller can retry later.
     */
    suspend fun refresh(currencies: Set<CurrencyCode>): Boolean {
        if (currencies.all { it == FrankfurterClient.EUR }) return true
        val published = frankfurter.eurRates(null, currencies) ?: return false
        add(rows(published))
        return true
    }

    private fun rows(published: PublishedRates): List<CachedRate> {
        val now = clock.instant()
        return published.perEur.map { (currency, rate) -> CachedRate(FrankfurterClient.EUR, currency, published.date, rate, now) }
    }

    private suspend fun add(rows: List<CachedRate>): RateTable = mutex.withLock {
        cache.save(rows)
        (checkNotNull(table.value) + rows).also(::publish)
    }
    private fun publish(rates: RateTable) {
        table.value = rates
        publishLookup(rates)
    }

    private fun publishLookup(rates: RateTable) {
        latestRates.value = RateLookup { from, to -> if (from == to) "1" else rates.latest(from, to)?.rate }
    }

    private fun ok(from: CurrencyCode, to: CurrencyCode, rate: DatedRate, stale: Boolean): LedgerResult<FxQuote> =
        LedgerResult.Ok(FxQuote(from, to, rate.rate, rate.date, FxSource.ECB_FRANKFURTER, isStale = stale))
}
