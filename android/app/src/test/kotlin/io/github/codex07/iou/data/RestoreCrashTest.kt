package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * AC M1-12: the process is killed after each step of a restore and of an undo. After a restart the app has
 * the complete old database or the complete new one. A kill before the commit point is reported once; a kill
 * after it is not reported. The restore hook stands in for the kill by throwing an `Error`, which skips every
 * clean-up; then the backend is stopped and started on the same files.
 */
class RestoreCrashTest {

    private class Killed : Error("killed")

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("crash"))
    private val file = DocumentRef("ledger.iou")

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    /** A ledger with one person, backed up, and then a second person; returns (backed up, live). */
    private suspend fun RealBackend.backupThenChange(): Pair<LedgerContent, LedgerContent> {
        apis.setup.completeSetup("Me", EUR).orFail()
        apis.people.createPerson(PersonDraft("In the backup"), apis.backup.observeLedgerGeneration().first()).orFail()
        apis.backup.createBackup(file, "pass-12345".toCharArray()).orFail()
        val backedUp = store.loaded().content
        apis.people.createPerson(PersonDraft("Only live"), apis.backup.observeLedgerGeneration().first()).orFail()
        return backedUp to store.loaded().content
    }

    private fun killAt(step: RestoreStep) {
        harness.onStep = { if (it == step) throw Killed() }
    }

    /** Restarts, checks the ledger and the report, and that the report doesn't come back. */
    private suspend fun restartAndCheck(expected: LedgerContent, reported: Boolean) {
        harness.onStep = {}
        harness.stop()
        val restarted = harness.start()
        restarted.store.loaded().content shouldBe expected
        restarted.recovery shouldBe RestoreRecovery.Result(interrupted = reported)
        restarted.apis.backup.observeRestoreInterrupted().first() shouldBe reported
        restarted.apis.backup.dismissRestoreInterrupted()
        restarted.apis.backup.observeRestoreInterrupted().first() shouldBe false

        val pointer = restarted.files.readPointer()!!
        harness.ledgerFileNames() shouldBe setOfNotNull(DbFiles.POINTER, pointer.current, pointer.previous)
        harness.stop()
        val again = harness.start()
        again.store.loaded().content shouldBe expected
        again.recovery shouldBe RestoreRecovery.Result(interrupted = false)
    }

    @ParameterizedTest
    @EnumSource(RestoreStep::class)
    fun `a restore killed after any step leaves the old or the new ledger`(step: RestoreStep) = runTest {
        val backend = harness.start()
        val (backedUp, live) = backend.backupThenChange()

        killAt(step)
        shouldThrow<Killed> { backend.apis.backup.restoreBackup(file, "pass-12345".toCharArray()) }

        val committed = step >= RestoreStep.COMMITTED
        restartAndCheck(expected = if (committed) backedUp else live, reported = !committed)
    }

    @ParameterizedTest
    @EnumSource(names = ["MARKED", "LIVE_CLOSED", "COMMITTED", "CLEANED"])
    fun `an undo killed after any step leaves the restored or the earlier ledger`(step: RestoreStep) = runTest {
        val backend = harness.start()
        val (backedUp, live) = backend.backupThenChange()
        backend.apis.backup.restoreBackup(file, "pass-12345".toCharArray()).orFail()

        killAt(step)
        shouldThrow<Killed> { backend.apis.backup.undoLastRestore() }

        val committed = step >= RestoreStep.COMMITTED
        restartAndCheck(expected = if (committed) live else backedUp, reported = !committed)
    }
}
