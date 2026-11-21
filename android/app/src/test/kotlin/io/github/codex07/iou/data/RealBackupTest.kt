package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.USD
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RealBackupTest {

    private val clock = Clock.fixed(T0, ZoneOffset.UTC)
    private val harness = RealHarness(clock, SequentialIds("a"))
    private val passphrase = "pass-12345".toCharArray()
    private val file = DocumentRef("ledger.iou")

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    /** A started backend with the seeded demo ledger and a cached rate. */
    private suspend fun seeded(): RealBackend = harness.start().also {
        SeedData.populate(it.store)
        it.apis.fx.quote(D0, SEK, EUR).orFail()
    }

    @Test
    fun `backup, wipe and restore give back the same ledger, and the device ID stays the installation's own`() = runTest {
        val first = seeded()
        val content = first.store.loaded().content
        val rates = first.fx.cachedRates()
        first.apis.backup.createBackup(file, passphrase).orFail()

        val wiped = RealHarness(clock, SequentialIds("b"))
        try {
            harness.documents.file(file).copyTo(wiped.documents.file(file))
            val second = wiped.start()
            val deviceId = second.store.deviceId
            deviceId shouldNotBe first.store.deviceId
            second.apis.backup.restoreBackup(file, passphrase).orFail()

            second.store.loaded().content shouldBe content
            second.fx.cachedRates().toSet() shouldBe rates.toSet()
            second.store.deviceId shouldBe deviceId
            wiped.stop()

            val restarted = wiped.start()
            restarted.store.loaded().content shouldBe content
            restarted.store.deviceId shouldBe deviceId
        } finally {
            wiped.stop()
        }
    }

    @Test
    fun `a wrong passphrase or an invalid ledger writes nothing`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, passphrase).orFail()
        val plain = DocumentRef("plain.json")
        backend.apis.backup.createBackup(plain, null).orFail()
        val twoMes = harness.documents.file(plain).readText().replace("\"isMe\":false", "\"isMe\":true")
        harness.documents.file(plain).writeText(twoMes)
        val files = harness.ledgerFileNames()
        val generation = backend.store.loaded().generation

        backend.apis.backup.restoreBackup(file, "wrong-pass".toCharArray()) shouldBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        ((backend.apis.backup.restoreBackup(plain, null) as LedgerResult.Err).error as LedgerError.CorruptFile).detail shouldContain "rule 15"

        harness.ledgerFileNames() shouldBe files
        backend.store.loaded().generation shouldBe generation
    }

    @ParameterizedTest
    @EnumSource(names = ["MARKED", "BUILT", "LIVE_CLOSED"])
    fun `a failure before the commit point keeps the database and the ledger settings`(step: RestoreStep) = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, passphrase).orFail()
        backend.apis.settings.setHomeCurrency(SEK).orFail()
        val content = backend.store.loaded().content
        val files = harness.ledgerFileNames()
        harness.onStep = { if (it == step) throw IllegalStateException("injected at $it") }

        backend.apis.backup.restoreBackup(file, passphrase) shouldBe LedgerResult.Err(LedgerError.RestoreFailed("injected at $step"))
        harness.onStep = {}

        backend.store.loaded().content shouldBe content
        harness.ledgerFileNames() shouldBe files
        val basis = backend.apis.backup.observeLedgerGeneration().first()
        backend.apis.people.createPerson(PersonDraft("After"), basis).orFail()
        harness.stop()

        val restarted = harness.start()
        restarted.apis.settings.observeSettings().first().homeCurrency shouldBe SEK
        restarted.recovery shouldBe RestoreRecovery.Result(interrupted = false)
        restarted.store.loaded().state.people.values.map { it.displayName }.toSet() shouldBe
            content.state.people.values.map { it.displayName }.toSet() + "After"
    }

    @Test
    fun `undo brings back the state before the restore and can itself be undone`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        val backedUp = backend.store.loaded().content
        backend.apis.people.createPerson(PersonDraft("Later"), backend.apis.backup.observeLedgerGeneration().first()).orFail()
        val later = backend.store.loaded().content

        backend.apis.backup.restoreBackup(file, null).orFail()
        backend.store.loaded().content shouldBe backedUp
        backend.apis.backup.undoLastRestore().orFail()
        backend.store.loaded().content shouldBe later
        backend.apis.backup.undoLastRestore().orFail()
        backend.store.loaded().content shouldBe backedUp

        val pointer = backend.files.readPointer()!!
        harness.ledgerFileNames() shouldBe setOf(DbFiles.POINTER, pointer.current, pointer.previous)
    }

    @Test
    fun `an undo that fails before the commit point changes nothing`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        backend.apis.backup.restoreBackup(file, null).orFail()
        val pointer = backend.files.readPointer()
        val content = backend.store.loaded().content
        harness.onStep = { if (it == RestoreStep.LIVE_CLOSED) throw IllegalStateException("injected") }

        backend.apis.backup.undoLastRestore() shouldBe LedgerResult.Err(LedgerError.RestoreFailed("injected"))
        backend.files.readPointer() shouldBe pointer
        backend.files.readMarker() shouldBe null
        backend.store.loaded().content shouldBe content
        backend.apis.people.createPerson(PersonDraft("Still writable"), backend.apis.backup.observeLedgerGeneration().first()).orFail()
    }

    /**
     * Fails the switch to the committed database: the pointer keeps the commit with its fresh generation, no
     * write lands, and a restart opens the committed ledger and reports nothing (spec §7.1: after the commit
     * point the operation is complete). Until that restart, a retried restore or undo is refused before it
     * touches anything, even one that would fail before its own commit point and reopen the database.
     */
    private suspend fun failAfterCommit(backend: RealBackend, expected: LedgerContent, operation: suspend () -> LedgerResult<Unit>) {
        val before = backend.files.readPointer()!!
        harness.onStep = { if (it == RestoreStep.COMMITTED) throw IllegalStateException("injected") }
        shouldThrow<IllegalStateException> { operation() }.message shouldBe "injected"

        val committed = backend.files.readPointer()!!
        committed.current shouldNotBe before.current
        committed.generation shouldNotBe before.generation
        val files = harness.ledgerFileNames()
        harness.onStep = { if (it == RestoreStep.MARKED) throw IllegalStateException("injected again") }
        shouldThrow<IllegalStateException> { backend.apis.backup.restoreBackup(file, null) }.message shouldBe RestoreProcedure.RESTART_NEEDED
        shouldThrow<IllegalStateException> { backend.apis.backup.undoLastRestore() }.message shouldBe RestoreProcedure.RESTART_NEEDED
        harness.onStep = {}
        backend.files.readPointer() shouldBe committed
        harness.ledgerFileNames() shouldBe files
        shouldThrow<IllegalStateException> {
            backend.apis.people.createPerson(PersonDraft("Lost"), backend.apis.backup.observeLedgerGeneration().first())
        }
        harness.stop()

        val restarted = harness.start()
        restarted.store.loaded().content shouldBe expected
        restarted.recovery shouldBe RestoreRecovery.Result(interrupted = false)
        restarted.files.readPointer() shouldBe committed
        restarted.store.loaded().generation shouldBe LedgerGeneration(committed.generation)
    }

    @Test
    fun `a restore that fails after the commit point is complete and is not rolled back`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        val backedUp = backend.store.loaded().content
        backend.apis.people.createPerson(PersonDraft("Later"), backend.apis.backup.observeLedgerGeneration().first()).orFail()

        failAfterCommit(backend, backedUp) { backend.apis.backup.restoreBackup(file, null) }
    }

    @Test
    fun `an undo that fails after the commit point is complete and is not rolled back`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        backend.apis.people.createPerson(PersonDraft("Later"), backend.apis.backup.observeLedgerGeneration().first()).orFail()
        val later = backend.store.loaded().content
        backend.apis.backup.restoreBackup(file, null).orFail()

        failAfterCommit(backend, later) { backend.apis.backup.undoLastRestore() }
    }

    @Test
    fun `a pointer that can't be written abandons the restore`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        val pointer = backend.files.readPointer()
        val content = backend.store.loaded().content
        val files = harness.ledgerFileNames()
        // A directory where the temp pointer goes makes writing it fail, as full storage would.
        File(harness.databaseDir, "${DbFiles.POINTER}.tmp").mkdir()

        (backend.apis.backup.restoreBackup(file, null) as LedgerResult.Err).error.shouldBeInstanceOf<LedgerError.RestoreFailed>()
        backend.files.readPointer() shouldBe pointer
        backend.store.loaded().content shouldBe content
        harness.ledgerFileNames() shouldBe files
        backend.apis.people.createPerson(PersonDraft("Still writable"), backend.apis.backup.observeLedgerGeneration().first()).orFail()
    }

    @Test
    fun `restore and undo wait for startup before touching the pointer`() = runTest {
        val initial = seeded()
        initial.apis.backup.createBackup(file, null).orFail()
        val backup = BackupFormat(RealHarness.FAST_KDF).read(harness.documents.file(file).readBytes(), null).orFail()
        val backedUp = initial.store.loaded().content
        initial.apis.people.createPerson(PersonDraft("Later"), initial.store.loaded().generation).orFail()
        val later = initial.store.loaded().content
        harness.stop()

        suspend fun beforeStartup(operation: suspend (RealBackend) -> LedgerResult<Unit>, expected: LedgerContent) {
            val dispatcher = object : CoroutineDispatcher() {
                private var queued: Runnable? = null
                private var released = false
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    synchronized(this) {
                        if (!released) {
                            check(queued == null)
                            queued = block
                        } else {
                            Dispatchers.IO.dispatch(context, block)
                        }
                    }
                }
                fun release() = synchronized(this) {
                    released = true
                    queued?.let { Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, it) }
                    queued = null
                }
            }
            val backend = harness.start(CoroutineScope(SupervisorJob() + dispatcher))
            val pointer = backend.files.readPointer()
            val marker = backend.files.readMarker()
            val files = harness.ledgerFileNames()
            val pending = async(SupervisorJob(), start = CoroutineStart.UNDISPATCHED) { operation(backend) }
            try {
                pending.isCompleted shouldBe false
                backend.recovery shouldBe null
                backend.files.readPointer() shouldBe pointer
                backend.files.readMarker() shouldBe marker
                harness.ledgerFileNames() shouldBe files
                dispatcher.release()
                pending.await().orFail()
                backend.store.loaded().content shouldBe expected
                backend.recovery shouldBe RestoreRecovery.Result(interrupted = false)
            } finally {
                dispatcher.release()
                pending.join()
                harness.stop()
            }
        }

        beforeStartup({ it.restores.restore(backup) }, backedUp)
        beforeStartup({ it.restores.undo() }, later)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `backup captures a coherent ledger and rates while restore replaces them`() = runTest {
        val backend = seeded()
        backend.apis.backup.createBackup(file, null).orFail()
        val format = BackupFormat(RealHarness.FAST_KDF)
        val beforeRestore = format.read(harness.documents.file(file).readBytes(), null).orFail()
        backend.apis.people.createPerson(PersonDraft("Later"), backend.store.loaded().generation).orFail()
        backend.apis.fx.quote(D0, USD, EUR).orFail()
        val laterContent = backend.store.loaded().content
        val laterRates = LedgerRows.of(laterContent, backend.fx.cachedRates()).rates.toSet()
        beforeRestore.content shouldNotBe laterContent
        beforeRestore.rows.rates.toSet() shouldNotBe laterRates

        val resultFile = DocumentRef("during-restore.json")
        val started = CompletableDeferred<Deferred<LedgerResult<io.github.codex07.iou.api.BackupInfo>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            backend.fx.lookup.drop(1).first()
            started.complete(async(start = CoroutineStart.UNDISPATCHED) {
                backend.apis.backup.createBackup(resultFile, null)
            })
        }
        backend.apis.backup.restoreBackup(file, null).orFail()
        started.await().await().orFail()
        val captured = format.read(harness.documents.file(resultFile).readBytes(), null).orFail()
        val actual = captured.content to captured.rows.rates.toSet()
        val valid = setOf(beforeRestore.content to beforeRestore.rows.rates.toSet(), laterContent to laterRates)
        (actual in valid) shouldBe true
    }
    @Test
    fun `the reminder is due after 30 days without a manual backup`() = runTest {
        val backend = harness.start()
        backend.apis.setup.completeSetup("Me", EUR).orFail()
        backend.device.update { it.copy(lastManualBackupAt = T0 - Duration.ofDays(29)) }
        backend.apis.backup.observeBackupReminder().first().due shouldBe false
        backend.device.update { it.copy(lastManualBackupAt = T0 - Duration.ofDays(31)) }
        backend.apis.backup.observeBackupReminder().first().due shouldBe true

        backend.apis.backup.createBackup(file, passphrase).orFail()
        val reminder = backend.apis.backup.observeBackupReminder().first()
        reminder.due shouldBe false
        reminder.lastBackupAt shouldBe T0
    }

    @Test
    fun `a backup the restore limits would refuse is not written and keeps the reminder`() = runTest {
        val size = harness.start().let { backend ->
            backend.apis.setup.completeSetup("Me", EUR).orFail()
            backend.apis.backup.createBackup(file, null).orFail()
            harness.documents.file(file).length().also { harness.documents.file(file).delete(); harness.stop() }
        }
        for ((limits, secret) in listOf(
            BackupFormat(RealHarness.FAST_KDF, maxFileBytes = size - 1) to null,
            BackupFormat(RealHarness.FAST_KDF, maxJsonBytes = size - 1) to passphrase,
        )) {
            val backend = harness.start(backupFormat = limits)
            val before = T0 - Duration.ofDays(31)
            backend.device.update { it.copy(lastManualBackupAt = before) }
            backend.apis.backup.createBackup(file, secret).shouldBeInstanceOf<LedgerResult.Err>()
                .error.shouldBeInstanceOf<LedgerError.LimitsExceeded>()
            harness.documents.file(file).exists() shouldBe false
            backend.apis.backup.observeBackupReminder().first().lastBackupAt shouldBe before
            harness.stop()
        }
    }
}
