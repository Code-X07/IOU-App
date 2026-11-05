package io.github.codex07.iou.data

import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestDatabases
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RoomRateCacheTest {

    private val db = TestDatabases.inMemory()
    private val cache = RoomRateCache { db }

    @AfterEach
    fun close() = db.close()

    @Test
    fun `rates round-trip and a refetch of the same pair and date replaces the old one`() = runTest {
        val sek = CachedRate(EUR, SEK, D0, "10.978", T0)
        val usd = CachedRate(EUR, USD, D0, "1.156", T0)
        val nextDay = CachedRate(EUR, SEK, D0.plusDays(1), "10.9515", T0.plusSeconds(86_400))
        cache.save(listOf(sek, usd))
        cache.save(listOf(nextDay, sek.copy(rate = "10.98", fetchedAt = T0.plusNanos(1))))

        cache.load() shouldContainExactlyInAnyOrder listOf(sek.copy(rate = "10.98", fetchedAt = T0.plusNanos(1)), usd, nextDay)
    }
}
