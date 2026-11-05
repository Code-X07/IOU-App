package io.github.codex07.iou.fx

import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.FakeFrankfurter
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FxServiceTest {

    private class InMemoryRateCache(initial: List<CachedRate> = emptyList()) : RateCache {
        val rows = initial.toMutableList()
        override suspend fun load() = rows.toList()
        override suspend fun save(rates: List<CachedRate>) {
            rows += rates
        }
    }

    private val friday = LocalDate.parse("2026-10-30")
    private val sunday = LocalDate.parse("2026-11-01")
    private val frankfurter = FakeFrankfurter()

    private fun eurRates(date: LocalDate, vararg rates: Pair<CurrencyCode, String>) =
        rates.map { (currency, rate) -> CachedRate(EUR, currency, date, rate, T0) }

    private fun service(cache: RateCache) = FxService(cache, frankfurter.client(), Clock.fixed(T0, ZoneOffset.UTC))

    private fun ecb(from: CurrencyCode, to: CurrencyCode, rate: String, date: LocalDate, stale: Boolean = false) =
        LedgerResult.Ok(FxQuote(from, to, rate, date, FxSource.ECB_FRANKFURTER, isStale = stale))

    @Test
    fun `a rate cached for the date needs no network`() = runTest {
        val fx = service(InMemoryRateCache(eurRates(friday, SEK to "10", USD to "1.25")))
        fx.load()

        fx.quote(friday, SEK, USD) shouldBe ecb(SEK, USD, "0.125", friday)
        frankfurter.requests.shouldBeEmpty()
    }

    @Test
    fun `a fetched rate is cached and carries the date it was published for`() = runTest {
        frankfurter.publish(friday, "SEK" to "10", "USD" to "1.25")
        val cache = InMemoryRateCache()
        val fx = service(cache).apply { load() }

        fx.quote(sunday, SEK, EUR) shouldBe ecb(SEK, EUR, "0.1", friday)
        cache.rows shouldBe eurRates(friday, SEK to "10")

        frankfurter.online = false
        fx.quote(friday, EUR, SEK) shouldBe ecb(EUR, SEK, "10", friday)
        frankfurter.requests.size shouldBe 1
    }

    @Test
    fun `without a rate for the date the most recent cached one is used and flagged stale`() = runTest {
        frankfurter.online = false
        val fx = service(InMemoryRateCache(eurRates(friday.minusDays(1), SEK to "11") + eurRates(friday, SEK to "10")))
        fx.load()

        fx.quote(sunday, SEK, EUR) shouldBe ecb(SEK, EUR, "0.1", friday, stale = true)
    }

    @Test
    fun `with no rate anywhere the user is asked for one`() = runTest {
        frankfurter.publish(friday, "SEK" to "10")
        val fx = service(InMemoryRateCache()).apply { load() }

        fx.quote(friday, CurrencyCode("ISK"), EUR) shouldBe LedgerResult.Err(LedgerError.RateRequired(CurrencyCode("ISK"), EUR))
        frankfurter.online = false
        fx.quote(friday, USD, EUR) shouldBe LedgerResult.Err(LedgerError.RateRequired(USD, EUR))
    }

    @Test
    fun `quotes wait for the cache to load`() = runTest {
        frankfurter.online = false
        val fx = service(InMemoryRateCache(eurRates(friday, SEK to "10")))

        val quote = async { fx.quote(friday, EUR, SEK) }
        runCurrent()
        quote.isActive shouldBe true
        fx.load()
        quote.await() shouldBe ecb(EUR, SEK, "10", friday)
    }

    @Test
    fun `the refresh caches the latest rates and updates the lookup`() = runTest {
        frankfurter.publish(friday, "SEK" to "10", "USD" to "1.25")
        val cache = InMemoryRateCache()
        val fx = service(cache).apply { load() }
        fx.lookup.value.rate(SEK, USD) shouldBe null

        fx.refresh(setOf(EUR, SEK, USD)) shouldBe true
        fx.lookup.value.rate(SEK, USD) shouldBe "0.125"
        fx.lookup.value.rate(SEK, SEK) shouldBe "1"
        cache.rows shouldContainExactlyInAnyOrder eurRates(friday, SEK to "10", USD to "1.25")

        fx.refresh(setOf(EUR)) shouldBe true
        frankfurter.requests.size shouldBe 1
        frankfurter.online = false
        fx.refresh(setOf(EUR, SEK)) shouldBe false
    }
}
