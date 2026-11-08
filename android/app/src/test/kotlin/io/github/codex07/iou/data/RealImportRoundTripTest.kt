package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SampleLedgers
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The real flavor stores what a full import wrote (Room schema 2, Part 4) and loads it back after a restart. */
class RealImportRoundTripTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("a"))

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    @Test
    fun `imported groups, payers and schedules survive a restart`() = runTest {
        val backend = harness.start()
        backend.apis.setup.completeSetup("Me", EUR).orFail()
        val ref = DocumentRef("full.zip")
        val bytes = ByteArrayOutputStream().also { LedgerExport("test").normalized(SampleLedgers.full(), T0, excelFriendly = false, it) }.toByteArray()
        harness.documents.file(ref).also { requireNotNull(it.parentFile).mkdirs() }.writeBytes(bytes)
        val api = backend.apis.importExport

        val preview = api.confirmIdentity(api.parseImport(ref).orFail(), accept = true).orFail()
        api.commitImport(preview, ImportChoices()).orFail()
        val imported = backend.store.loaded().state
        harness.stop()
        val restarted = harness.start()

        preview.invalidRows shouldBe emptyList()
        restarted.store.loaded().state shouldBe imported
        imported.schedules.keys.map { it.value }.toSet() shouldBe setOf("rent", "gym")
    }
}
