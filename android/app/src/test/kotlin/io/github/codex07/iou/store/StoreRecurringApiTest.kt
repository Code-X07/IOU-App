package io.github.codex07.iou.store

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Date-relative reads follow the runner's passes (spec §5.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecurringApiTest {

    /** A clock the test moves (UTC). */
    private class MovingClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }

    @Test
    fun `an open due list shows a new day's occurrence after a pass that writes nothing`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = LocalDate.parse("2026-12-01"))
        val clock = MovingClock(Instant.parse("2026-11-30T10:00:00Z"))
        val store = LedgerStore(clock, SequentialIds("api"), DeviceId("device-test"), initial = LedgerContent(ledger.state))
        val runner = RecurringRunner(store, QuoteSource { _, from, to -> LedgerResult.Err(LedgerError.RateRequired(from, to)) })
        val seen = mutableListOf<List<LocalDate>>()
        backgroundScope.launch { StoreRecurringApi(store, runner).observeDue().collect { due -> seen += due.map { it.date } } }
        runCurrent()
        seen.last() shouldBe emptyList()

        clock.at = Instant.parse("2026-12-01T10:00:00Z")
        runner.run() shouldBe emptyList()
        runCurrent()
        seen.last() shouldBe listOf(LocalDate.parse("2026-12-01"))
    }
}
