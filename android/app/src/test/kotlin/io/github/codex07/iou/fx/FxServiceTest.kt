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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `a fetched quote survives cache failure while cancellation and refresh errors propagate`() = runTest {
        frankfurter.publish(friday, "SEK" to "10")
        var failure: Exception = IOException("disk full")
        val cache = object : RateCache {
            override suspend fun load() = emptyList<CachedRate>()
            override suspend fun save(rates: List<CachedRate>) { throw failure }
        }
        val fx = service(cache).apply { load() }
        fx.quote(friday, EUR, SEK) shouldBe ecb(EUR, SEK, "10", friday)
        fx.lookup.value.rate(EUR, SEK) shouldBe null
        assertThrows<IOException> { fx.refresh(setOf(EUR, SEK)) }
        failure = CancellationException("cancelled")
        assertThrows<CancellationException> { fx.quote(friday, EUR, SEK) }
    }

    @Test
    fun `a load overlapping a refresh cannot publish an older cache snapshot`() = runTest {
        frankfurter.publish(friday, "SEK" to "10")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = object : RateCache {
            var rows = emptyList<CachedRate>()
            var pause = false
            override suspend fun load(): List<CachedRate> {
                val snapshot = rows
                if (pause) { entered.complete(Unit); release.await() }
                return snapshot
            }
            override suspend fun save(rates: List<CachedRate>) { rows = rows + rates }
        }
        val client = FrankfurterClient(HttpClient(MockEngine) {
            engine {
                dispatcher = Dispatchers.Unconfined
                addHandler { respond("""{"amount":1,"base":"EUR","date":"2026-10-30","rates":{"SEK":10}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            }
        })
        val fx = FxService(cache, client, Clock.fixed(T0, ZoneOffset.UTC)).apply { load() }
        cache.pause = true
        val loading = async(UnconfinedTestDispatcher(testScheduler)) { fx.load() }
        entered.await()
        val refreshing = async(UnconfinedTestDispatcher(testScheduler)) { fx.refresh(setOf(EUR, SEK)) }
        runCurrent()
        release.complete(Unit)
        loading.await()
        refreshing.await() shouldBe true
        fx.lookup.value.rate(EUR, SEK) shouldBe "10"
        fx.quote(friday, EUR, SEK) shouldBe ecb(EUR, SEK, "10", friday)
    }
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
