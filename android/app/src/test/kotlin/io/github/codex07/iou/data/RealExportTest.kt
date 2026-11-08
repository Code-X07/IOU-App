package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportInfo
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RealExportTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("export"))

    @AfterEach
    fun stop() = harness.stop()

    @Test
    fun `exports write the live ledger to the picked document`() = runTest {
        val backend = harness.start()
        SeedData.populate(backend.store)
        val state = backend.store.loaded().state
        val zip = DocumentRef("ledger.zip")

        val normalized = backend.apis.importExport.export(ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV), zip)
        val tableRows = state.people.size + state.handles.size + state.groups.size + state.members.size +
            state.fxSnapshots.size + state.transfers.size + state.expenses.size + state.expenses.values.sumOf { it.shares.size } +
            state.expenses.values.sumOf { it.payers.size } + state.schedules.size + state.schedules.values.sumOf { it.participants.size }
        normalized shouldBe LedgerResult.Ok(ExportInfo(tableRows))
        ZipFile(harness.documents.file(zip)).use { file ->
            file.getInputStream(file.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8) shouldStartWith
                """{"format":"iou-normalized-csv","schemaVersion":2,"appVersion":"test","exportedAt":"2026-01-01T00:00:00Z""""
        }

        val report = DocumentRef("report.csv")
        val result = backend.apis.importExport.export(ExportRequest(ExportShape.REPORT, ExportFormat.CSV, excelFriendly = true), report)
        result shouldBe LedgerResult.Ok(ExportInfo(state.entries.count { !it.isDeleted }))
        harness.documents.file(report).readText() shouldStartWith "\uFEFFdate;type;description;"
    }

    @Test
    fun `bad requests are refused before anything is written`() = runTest {
        val backend = harness.start()
        SeedData.populate(backend.store)
        val target = DocumentRef("nothing.csv")
        val export = backend.apis.importExport

        export.export(ExportRequest(ExportShape.REPORT, ExportFormat.XLSX), target) shouldBe LedgerResult.Err(LedgerError.InvalidField("format"))
        export.export(ExportRequest(ExportShape.REPORT, ExportFormat.CSV, from = D0, to = D0.minusDays(1)), target) shouldBe
            LedgerResult.Err(LedgerError.InvalidField("to"))
        export.export(ExportRequest(ExportShape.REPORT, ExportFormat.CSV, scope = ExportScope.Group(GroupId("nope"))), target) shouldBe
            LedgerResult.Err(LedgerError.NotFound("nope"))
        harness.documents.file(target).exists() shouldBe false

        File(harness.documents.dir, "a-folder").mkdirs()
        val folder = export.export(ExportRequest(ExportShape.REPORT, ExportFormat.CSV), DocumentRef("a-folder"))
        (folder as LedgerResult.Err).error::class shouldBe LedgerError.FileAccessFailed::class
    }
}
