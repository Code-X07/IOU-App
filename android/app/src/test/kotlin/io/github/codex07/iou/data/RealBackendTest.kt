package io.github.codex07.iou.data

import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.fake.FakeRates
import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.fx.RateCache
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RealBackendTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("real"))

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    @Test
    fun `the first start creates the pointer and an empty ledger`() = runTest {
        val backend = harness.start()
        backend.apis.setup.observeSetupState().first() shouldBe SetupState.NeedsSetup

        val pointer = backend.files.readPointer()!!
        pointer.previous shouldBe null
        backend.apis.backup.observeLedgerGeneration().first().value shouldBe pointer.generation
        backend.recovery shouldBe RestoreRecovery.Result(interrupted = false)
    }

    @Test
    fun `the ledger, its settings, the device settings and the generation survive a restart`() = runTest {
        val first = harness.start()
        val me = first.apis.setup.completeSetup("Me", SEK).orFail()
        val basis = first.apis.backup.observeLedgerGeneration().first()
        val x = first.apis.people.createPerson(PersonDraft("X"), basis).orFail()
        first.apis.settings.setLockTimeout(LockTimeout.FIVE_MINUTES).orFail()
        val people = first.apis.people.observePeople().first()
        val deviceId = first.store.deviceId
        harness.stop()

        val second = harness.start()
        (second.apis.setup.observeSetupState().first() as SetupState.Ready).me.id shouldBe me
        second.apis.people.observePeople().first() shouldBe people
        people.single().person.id shouldBe x
        second.apis.backup.observeLedgerGeneration().first() shouldBe basis
        val settings = second.apis.settings.observeSettings().first()
        settings.homeCurrency shouldBe SEK
        settings.lockTimeout shouldBe LockTimeout.FIVE_MINUTES
        second.store.deviceId shouldBe deviceId
    }

    @Test
    fun `with app lock on, the next start is locked until unlocked`() = runTest {
        val first = harness.start()
        first.apis.settings.setAppLockEnabled(true).orFail()
        first.apis.settings.observeLockRequired().first() shouldBe false
        harness.stop()

        val second = harness.start()
        second.apis.settings.observeLockRequired().first() shouldBe true
        second.apis.settings.markUnlocked()
        second.apis.settings.observeLockRequired().first() shouldBe false
    }

    @Test
    fun `a restore cut off before its commit point is reported once and its file is gone`() = runTest {
        harness.start().apis.setup.completeSetup("Me", SEK).orFail()
        harness.stop()
        val files = DbFiles(harness.databaseDir)
        File(harness.databaseDir, "iou-half-built.db").writeText("partial")
        files.writeMarker(RestoreMarker("restore", "iou-half-built.db"))

        val backend = harness.start()
        backend.apis.setup.observeSetupState().first().shouldBeInstanceOf<SetupState.Ready>()
        backend.recovery shouldBe RestoreRecovery.Result(interrupted = true)
        File(harness.databaseDir, "iou-half-built.db").exists() shouldBe false
        files.readMarker() shouldBe null
    }

    @Test
    fun `fetched rates are kept in the database and serve quotes after a restart`() = runTest {
        val quote = harness.start().apis.fx.quote(D0, SEK, EUR).orFail()
        quote shouldBe FxQuote(SEK, EUR, FakeRates.rate(SEK, EUR)!!, RealHarness.RATES_DATE, FxSource.ECB_FRANKFURTER, isStale = false)
        harness.stop()

        harness.frankfurter.online = false
        val restarted = harness.start()
        restarted.apis.fx.quote(RealHarness.RATES_DATE, SEK, EUR).orFail() shouldBe quote
        restarted.apis.fx.quote(D0, SEK, EUR).orFail() shouldBe quote.copy(isStale = true)
    }

    @Test
    fun `a refresh that throws asks for a retry instead of failing the worker`() = runTest {
        var failure: Exception? = null
        val backend = harness.start(rateCache = { databases ->
            val room = RoomRateCache(databases)
            object : RateCache {
                override suspend fun load() = room.load()
                override suspend fun save(rates: List<CachedRate>) = failure?.let { throw it } ?: room.save(rates)
            }
        })
        backend.apis.setup.completeSetup("Me", SEK).orFail()
        failure = IOException("disk full")
        backend.refreshRates() shouldBe false
        failure = null
        backend.refreshRates() shouldBe true
    }

    @Test
    fun `the rate refresh fetches the home and in-use currencies and fills the approximate totals`() = runTest {
        val backend = harness.start()
        backend.refreshRates() shouldBe true
        harness.frankfurter.requests.shouldBeEmpty()

        val me = backend.apis.setup.completeSetup("Me", EUR).orFail()
        val basis = backend.apis.backup.observeLedgerGeneration().first()
        val x = backend.apis.people.createPerson(PersonDraft("X"), basis).orFail()
        backend.apis.ledger.saveTransfer(TransferDraft(me, x, sek(11_500), occurredOn = D0), SaveMode.Create(basis)).orFail()
        backend.apis.ledger.observeOverview().first().approxOwedToMe shouldBe null

        backend.refreshRates() shouldBe true
        harness.frankfurter.requests.single().parameters["symbols"] shouldBe "SEK"
        backend.apis.ledger.observeOverview().first { it.approxOwedToMe != null }.approxOwedToMe shouldBe eur(1_000)
    }
}
