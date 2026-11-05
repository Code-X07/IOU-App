package io.github.codex07.iou.fx

import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.money.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** A cached rate (spec §3.3): [rate] units of [quote] per one [base] on [rateDate]. */
data class CachedRate(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rateDate: LocalDate,
    val rate: String,
    val fetchedAt: Instant,
)

/** A rate and the date it applies to. */
data class DatedRate(val rate: String, val date: LocalDate)

/**
 * Cached rates in memory. A pair that isn't cached directly is answered from its inverse or crossed through a
 * shared base on the same date, computed with `DECIMAL128` (spec §4.5). Rates must be valid
 * ([FxConverter.validateRate]); the cache only ever holds validated ones.
 */
class RateTable(rates: Collection<CachedRate> = emptyList()) {

    private data class Key(val base: CurrencyCode, val quote: CurrencyCode, val date: LocalDate)

    private val byKey: Map<Key, CachedRate> = rates.associateBy { Key(it.base, it.quote, it.rateDate) }
    private val byDate: Map<LocalDate, List<CachedRate>> = byKey.values.groupBy { it.rateDate }
    private val datesNewestFirst: List<LocalDate> = byDate.keys.sortedDescending()

    val rates: Collection<CachedRate> get() = byKey.values

    /** This table with [more] added; a rate for the same pair and date replaces the old one. */
    operator fun plus(more: Collection<CachedRate>): RateTable = RateTable(byKey.values + more)

    /** Units of [to] per one [from] on exactly [date], or null. The same currency is always "1". */
    fun on(date: LocalDate, from: CurrencyCode, to: CurrencyCode): String? {
        if (from == to) return "1"
        val day = byDate[date] ?: return null
        byKey[Key(from, to, date)]?.let { return it.rate }
        byKey[Key(to, from, date)]?.let { return FxConverter.invert(it.rate) }
        for (toRate in day.filter { it.quote == to }) {
            val fromRate = byKey[Key(toRate.base, from, date)] ?: continue
            return FxConverter.cross(homeToBase = fromRate.rate, homeToQuote = toRate.rate)
        }
        return null
    }

    /** The rate from the most recent date that has one, or null. */
    fun latest(from: CurrencyCode, to: CurrencyCode): DatedRate? =
        datesNewestFirst.firstNotNullOfOrNull { date -> on(date, from, to)?.let { DatedRate(it, date) } }
}
