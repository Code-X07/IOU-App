package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
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
}
