package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.testutil.RealHarness
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

/**
 * Writes the seeded ledger's exports to `app/build/export-samples` for the manual checklist (spec §11), which
 * opens them in Excel and LibreOffice. A person named like a formula shows whether text stays text.
 */
class ExportSamplesTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("samples"))

    private val samples = mapOf(
        "normalized.zip" to ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV),
        "normalized-excel.zip" to ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV, excelFriendly = true),
        "report.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV),
        "report-excel.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV, excelFriendly = true),
    )

    @AfterEach
    fun stop() = harness.stop()

    @Test
    fun `sample exports are written for the manual checklist`() = runTest {
        val backend = harness.start()
        SeedData.populate(backend.store)
        val basis = backend.apis.backup.observeLedgerGeneration().first()
        backend.apis.people.createPerson(PersonDraft(FORMULA), basis).shouldBeInstanceOf<LedgerResult.Ok<*>>()

        val out = File("build/export-samples").apply { deleteRecursively(); mkdirs() }
        for ((name, request) in samples) {
            val ref = DocumentRef(name)
            backend.apis.importExport.export(request, ref).shouldBeInstanceOf<LedgerResult.Ok<*>>()
            harness.documents.file(ref).copyTo(File(out, name))
        }
        out.list()!!.sorted() shouldBe samples.keys.sorted()
    }

    private companion object {
        const val FORMULA = """=HYPERLINK("http://evil","x")"""
    }
}
