package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [OWN_EXPORT] is an export of this install's ledger in which the first transfer a test creates has the
 * note "Imported note" (so it shows as a note conflict); [FOREIGN_EXPORT] comes from another install.
 */
enum class ImportFixture { OWN_EXPORT, FOREIGN_EXPORT }

abstract class ImportExportApiContract : ApiContract() {

    protected abstract fun importFile(fixture: ImportFixture): DocumentRef
    protected abstract fun exportTarget(): DocumentRef
    protected abstract fun backupTarget(name: String): DocumentRef

    private fun ImportPreview.row(id: EntryId) = rows.single { it.key == ImportRowKey(EntityType.TRANSFER, id.value) }

    @Test
    fun `the normalized shape is always the full ledger`() = runTest {
        val me = setUpMe()
        val request = ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV, scope = ExportScope.Person(me))
        apis.importExport.export(request, exportTarget()) shouldBe LedgerResult.Err(LedgerError.InvalidField("scope"))
    }

    @Test
    fun `a foreign export needs confirmation and declining rejects it`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.FOREIGN_EXPORT)).orFail()
        preview.identity.shouldBeInstanceOf<ImportIdentity.NeedsConfirmation>()
        apis.importExport.confirmIdentity(preview, accept = false) shouldBe LedgerResult.Err(LedgerError.ForeignLedger)
    }

    @Test
    fun `confirming a foreign preview parsed before a restore is refused`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.FOREIGN_EXPORT)).orFail()
        val target = backupTarget("before-confirm.iou")
        apis.backup.createBackup(target, null).orFail()
        apis.backup.restoreBackup(target, null).orFail()

        apis.importExport.confirmIdentity(preview, accept = true) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
    }

    @Test
    fun `refreshing rebuilds conflicts against the current ledger and flags what changed`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = apis.ledger.saveTransfer(TransferDraft(me, x, eur(1_000), occurredOn = TODAY, note = "A"), SaveMode.Create(basis())).orFail()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.OWN_EXPORT)).orFail()
        preview.row(id).changes.single { it.field == "note" }.before shouldBe "A"

        val detail = apis.ledger.observeEntry(id).first()!!
        apis.ledger.saveTransfer((detail.editDraft as TransferDraft).copy(note = "B"), SaveMode.Update(id, detail.token)).orFail()
        val refreshed = apis.importExport.refreshPreview(preview).orFail()

        val row = refreshed.row(id)
        row.changes.single { it.field == "note" }.before shouldBe "B"
        row.changedSinceLastPreview shouldBe true
        apis.importExport.commitImport(refreshed, ImportChoices()).orFail()
    }

    @Test
    fun `any write after parsing makes the preview stale until refreshed`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.OWN_EXPORT)).orFail()
        newPerson("Late")

        apis.importExport.commitImport(preview, ImportChoices()) shouldBe LedgerResult.Err(LedgerError.ImportPreviewStale)
        val refreshed = apis.importExport.refreshPreview(preview).orFail()
        val batch = apis.importExport.commitImport(refreshed, ImportChoices()).orFail()
        apis.importExport.observeImportBatches().first().first().id shouldBe batch
        apis.importExport.undoImport(batch).orFail().complete shouldBe true
    }
}
