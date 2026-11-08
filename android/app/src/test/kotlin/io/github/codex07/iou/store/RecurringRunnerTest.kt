package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Auto-posting and its triggers (spec §5.7, AC 29–32). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringRunnerTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun store(state: LedgerState, today: String) = LedgerStore(
        Clock.fixed(Instant.parse("${today}T10:00:00Z"), ZoneOffset.UTC), SequentialIds("run"), DeviceId("device-test"),
        initial = LedgerContent(state),
    )

    private val noRates = QuoteSource { _, from, to -> LedgerResult.Err(LedgerError.RateRequired(from, to)) }

    @Test
    fun `due occurrences of an auto-post schedule are posted oldest first, and only once`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), unit = RecurrenceUnit.WEEK, autoPost = true)
        val store = store(ledger.state, "2026-01-15")
        val runner = RecurringRunner(store, noRates)

        runner.run().map { it.occurrence } shouldBe listOf(d("2026-01-01"), d("2026-01-08"), d("2026-01-15"))
        store.loaded().state.expenses.values.mapNotNull { it.recurringOn }.sorted() shouldBe
            listOf(d("2026-01-01"), d("2026-01-08"), d("2026-01-15"))
        runner.run() shouldBe emptyList()
        store.loaded().state.schedules.getValue(id).handledThrough shouldBe d("2026-01-15")
    }

    @Test
    fun `a manual post racing the runner leaves exactly one expense for the occurrence`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val prepared = (store.currentViews().occurrenceDraft(id) as LedgerResult.Ok).value

        RecurringRunner(store, noRates).run().size shouldBe 1

        store.write { RecurringOps.post(it, id, prepared.occurrence, prepared.draft, prepared.token) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(id.value, 1, 2))
        store.loaded().state.expenses.values.count { it.recurringOn == D0 } shouldBe 1
    }

    @Test
    fun `a converted occurrence posts at its date's rate, and without one it stays due while others post`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val sekRent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val eurRent = ledger.schedule(ME, eur(1_000), listOf(ME, x), autoPost = true)
        val store = store(ledger.state, "2026-01-01")

        val offline = RecurringRunner(store, noRates)
        offline.run().map { it.schedule } shouldBe listOf(eurRent)
        offline.errors.value shouldBe mapOf(sekRent to RecurringRunner.Failure(D0, LedgerError.RateRequired(SEK, EUR)))
        RecurringRunner.withErrors(store.currentViews().dueOccurrences(D0), offline.errors.value).single().autoPostError shouldBe
            LedgerError.RateRequired(SEK, EUR)

        val asked = mutableListOf<LocalDate>()
        val online = RecurringRunner(
            store,
            QuoteSource { date, from, to ->
                asked += date
                LedgerResult.Ok(FxQuote(from, to, "0.08765", date, FxSource.ECB_FRANKFURTER, isStale = true))
            },
        )
        online.run().map { it.schedule } shouldBe listOf(sekRent)
        asked shouldBe listOf(D0)
        store.loaded().state.expenses.values.single { it.recurringId == sekRent }.effectTotal shouldBe eur(8_765)
        online.errors.value shouldBe emptyMap()
    }

    @Test
    fun `a schedule whose participant left the group stays due with MemberRemoved, and held schedules are skipped`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME))
        ledger.member(flat, x, removed = true)
        val left = ledger.schedule(ME, eur(1_000), listOf(ME, x), group = flat, autoPost = true)
        val trip = ledger.group("Trip", members = listOf(ME, x), archived = true)
        ledger.schedule(ME, eur(1_000), listOf(ME, x), group = trip, autoPost = true)
        val runner = RecurringRunner(store(ledger.state, "2026-01-01"), noRates)

        runner.run() shouldBe emptyList()
        runner.errors.value.keys shouldBe setOf(left)
        runner.errors.value.getValue(left).error shouldBe LedgerError.MemberRemoved(flat, setOf(x))
    }

    @Test
    fun `a backlog blocks auto-post until it is handled, then the runner posts today's date`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val store = store(ledger.state, "2026-03-01")
        val runner = RecurringRunner(store, noRates)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = d("2025-12-01"), autoPost = true,
        )
        val id = (store.write { RecurringOps.save(it, draft, ScheduleSaveMode.Create(it.generation)) } as LedgerResult.Ok).value

        runner.run() shouldBe emptyList()
        store.currentViews().dueOccurrences(d("2026-03-01")).map { it.date to it.confirmToResume } shouldBe listOf(
            d("2025-12-01") to true, d("2026-01-01") to true, d("2026-02-01") to true, d("2026-03-01") to true,
        )

        store.write { RecurringOps.skip(it, id, d("2026-02-01"), EditToken(it.generation, 1)) } shouldBe LedgerResult.Ok(Unit)
        runner.run().map { it.occurrence } shouldBe listOf(d("2026-03-01"))
    }

    @Test
    fun `a write outside RecurringApi that changes the due list asks for a pass, which reports the new list`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat)
        val store = store(ledger.state, "2026-01-01")
        val reported = mutableListOf<List<RecurringId>>()
        val runner = RecurringRunner(store, noRates, RecurringNotices { due, _ -> reported += due.map { it.schedule } })
        runner.start(backgroundScope)
        runCurrent()
        reported.last() shouldBe listOf(rent)

        StoreGroupsApi(store).setGroupArchived(flat, true, EditToken(store.loaded().generation, 1)) shouldBe LedgerResult.Ok(Unit)
        runCurrent()
        reported.last() shouldBe emptyList()
    }

    @Test
    fun `a schedule put on hold while its rate is fetched is not posted`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val rent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val runner = RecurringRunner(
            store,
            QuoteSource { date, from, to ->
                // The user archives the group while the request is out.
                StoreGroupsApi(store).setGroupArchived(flat, true, EditToken(store.loaded().generation, 1)) shouldBe LedgerResult.Ok(Unit)
                LedgerResult.Ok(FxQuote(from, to, "0.08765", date, FxSource.ECB_FRANKFURTER, isStale = true))
            },
        )

        runner.run() shouldBe emptyList()
        store.loaded().state.expenses.values.none { it.recurringId == rent } shouldBe true
        runner.errors.value shouldBe emptyMap()
    }

    @Test
    fun `a redraw signal reports the due list at once, while a pass still waits for a rate`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val rent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val network = CompletableDeferred<Unit>()
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reports = mutableListOf<List<RecurringId>>()
        val notices = object : RecurringNotices {
            override suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>) {
                reports += due.map { it.schedule }
            }

            override val redraw: Flow<Unit> = signals
        }
        val runner = RecurringRunner(
            store,
            QuoteSource { _, from, to ->
                network.await()
                LedgerResult.Err(LedgerError.RateRequired(from, to))
            },
            notices,
        )
        runner.start(backgroundScope)
        runCurrent()
        reports shouldBe emptyList()

        signals.tryEmit(Unit)
        runCurrent()
        reports shouldBe listOf(listOf(rent))

        network.complete(Unit)
        runCurrent()
        runner.errors.value.keys shouldBe setOf(rent)
    }

    @Test
    fun `a ledger without me, as after undoing a restore onto a fresh install, reports an empty due list`() = runTest {
        val reported = mutableListOf<Pair<List<DueOccurrence>, List<PostedOccurrence>>>()
        val runner = RecurringRunner(store(LedgerState(), "2026-01-01"), noRates, RecurringNotices { due, posted -> reported += due to posted })

        runner.run() shouldBe emptyList()
        runner.redraw()

        reported shouldBe List(2) { emptyList<DueOccurrence>() to emptyList<PostedOccurrence>() }
    }

    @Test
    fun `every pass moves today`() = runTest {
        val clock = MovingClock(Instant.parse("2026-11-30T10:00:00Z"))
        val store = LedgerStore(clock, SequentialIds("run"), DeviceId("device-test"), initial = LedgerContent(TestLedger().apply { me() }.state))
        val runner = RecurringRunner(store, noRates)
        runner.today.value shouldBe d("2026-11-30")

        clock.at = Instant.parse("2026-12-01T10:00:00Z")
        runner.run()
        runner.today.value shouldBe d("2026-12-01")
    }

    /** A clock the test moves (UTC). */
    private class MovingClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }
}
