package io.github.codex07.iou.api

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

interface FxApi {
    /**
     * A rate for [date] through the lookup chain (spec §4.5): cache, Frankfurter, latest cached (stale).
     * `RateRequired` when none is available: ask the user and use [userRate].
     */
    suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote>

    /** Validates a typed rate. Errors: `InvalidRate`. */
    fun userRate(date: LocalDate, from: CurrencyCode, to: CurrencyCode, rate: String): LedgerResult<FxQuote>

    /** The display rate behind "amount actually charged" (source `BANK_ACTUAL`). */
    fun rateFromCharged(date: LocalDate, paid: Money, charged: Money): LedgerResult<FxQuote>
}
