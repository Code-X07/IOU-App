package io.github.codex07.iou.ui.viewmodel

import app.cash.turbine.test
import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

@OptIn(ExperimentalCoroutinesApi::class)
class DataManagementTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }
    private fun replacements() = LedgerReplacements(CoroutineScope(SupervisorJob() + dispatcher))
    private fun vm(a: Apis, backup: BackupApi = a.backup, io: ImportExportApi = a.importExport, services: DataServices = DataServices.Simulated) =
        DataManagementViewModel(backup, io, a.settings, a.people, a.groups, dispatcher, services, replacements())

    @Test fun `backup encryption follows backend preference and validates length and confirmation`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("backup"); runCurrent(); assertTrue(vm.state.value.encrypted)
        val short = "short".toCharArray(); vm.beginBackup(short, "short".toCharArray(), false)
        assertEquals("passphrase", (vm.state.value.error as LedgerError.InvalidField).field); assertTrue(short.all { it == '\u0000' })
        vm.beginBackup("abcdefgh".toCharArray(), "different".toCharArray(), false)
        assertEquals("passphraseConfirmation", (vm.state.value.error as LedgerError.InvalidField).field); assertNull(vm.state.value.picker)
        a.settings.setBackupEncryptionDefault(false)
        val other = vm(a); other.open("backup"); runCurrent(); assertFalse(other.state.value.encrypted)
        vm.end(); other.end()
    }

    @Test fun `unencrypted backup requires explicit warning acknowledgement`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("backup"); runCurrent(); vm.encryption(false)
        vm.beginBackup(charArrayOf(), charArrayOf(), false)
        assertEquals("unencryptedWarning", (vm.state.value.error as LedgerError.InvalidField).field)
        vm.beginBackup(charArrayOf(), charArrayOf(), true)
        assertNotNull(vm.state.value.picker); assertTrue(vm.state.value.picker!!.name.contains("SIMULATION-EMPTY"))
        vm.end()
    }

    @Test fun `backup passes owned secret only to API and wipes arrays after success`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var captured: CharArray? = null; var calls = 0
        val backup = object : BackupApi by a.backup {
            override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
                calls++; captured = passphrase; assertEquals("testphrase", passphrase!!.concatToString())
                return a.backup.createBackup(target, passphrase)
            }
        }
        val vm = vm(a, backup); vm.open("backup"); runCurrent()
        val secret = "testphrase".toCharArray(); vm.beginBackup(secret, "testphrase".toCharArray(), false)
        assertTrue(secret.all { it == '\u0000' }); assertFalse(vm.state.value.toString().contains("testphrase"))
        vm.documentResult("simulation/backup"); runCurrent()
        assertEquals(1, calls); assertTrue(captured!!.all { it == '\u0000' })
        assertTrue(vm.state.value.message!!.contains("not a usable backup")); assertNotNull(a.backup.observeBackupReminder().first().lastBackupAt)
        vm.end()
    }

    @Test fun `cancelled and closed document workflows cannot submit late results`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var calls = 0
        val backup = object : BackupApi by a.backup {
            override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> { calls++; return a.backup.createBackup(target, passphrase) }
        }
        val vm = vm(a, backup); vm.open("backup"); runCurrent()
        vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false)
        val request = vm.state.value.picker!!; vm.pickerLaunched(request.id); assertTrue(vm.state.value.picker!!.launched)
        vm.documentResult(null); runCurrent(); assertEquals(0, calls)
        vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false)
        vm.requestLeave(); assertTrue(vm.state.value.discard); vm.discard(); vm.documentResult("late-document"); runCurrent()
        assertEquals(0, calls); assertTrue(vm.state.value.exit); assertNull(vm.state.value.picker); vm.end()
    }

    @Test fun `backup errors clear secret arrays and use non-sensitive error copy`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var captured: CharArray? = null
        val backup = object : BackupApi by a.backup {
            override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
                captured = passphrase; return LedgerResult.Err(LedgerError.CorruptFile("private path and 123.45 EUR"))
            }
        }
        val vm = vm(a, backup); vm.open("backup"); runCurrent()
        vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false); vm.documentResult("private-document"); runCurrent()
        assertTrue(captured!!.all { it == '\u0000' })
        assertFalse(vm.state.value.error!!.userMessage(true).contains("123.45")); assertFalse(vm.state.value.error!!.userMessage(true).contains("private path"))
        vm.end()
    }

    @Test fun `file access and restore failures have explicit messages that do not leak detail`() {
        val access = LedgerError.FileAccessFailed("content://private/path").userMessage()
        assertTrue(access.contains("could not be accessed") && access.contains("incomplete") && access.contains("another location"))
        assertTrue(access.contains("usable backup"))
        assertFalse(access.contains("private"))
        val restore = LedgerError.RestoreFailed("secret-detail").userMessage()
        assertTrue(restore.contains("nothing was changed"))
        assertFalse(restore.contains("secret"))
    }

    @Test fun `restore waits for explicit confirmation and handles wrong passphrase without replacing ledger`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.backup.createBackup(DocumentRef("backup"), "testphrase".toCharArray())
        val before = a.backup.observeLedgerGeneration().first()
        val vm = vm(a); vm.open("restore"); runCurrent(); vm.pickRestore(); vm.documentResult("backup")
        assertEquals(before, a.backup.observeLedgerGeneration().first()); assertTrue(vm.state.value.restoreSelected)
        val secret = "wrong".toCharArray(); vm.requestRestore(secret); assertTrue(secret.all { it == '\u0000' })
        assertEquals(DataConfirmation.RESTORE, vm.state.value.confirmation)
        assertEquals(before, a.backup.observeLedgerGeneration().first())
        vm.confirm(); runCurrent(); assertEquals(LedgerError.WrongPassphraseOrDamagedFile, vm.state.value.error)
        assertEquals(before, a.backup.observeLedgerGeneration().first()); vm.end()
    }

    @Test fun `restore and undo change generation and invalidate staged import decisions`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.backup.createBackup(DocumentRef("backup"), "testphrase".toCharArray())
        val importing = vm(a); importing.open("import"); runCurrent(); importing.simulateImport("normal"); runCurrent()
        assertNotNull(importing.state.value.preview)
        val restoring = vm(a); restoring.open("restore"); runCurrent(); restoring.pickRestore(); restoring.documentResult("backup")
        restoring.requestRestore("testphrase".toCharArray()); val before = a.backup.observeLedgerGeneration().first()
        restoring.confirm(); runCurrent()
        val after = a.backup.observeLedgerGeneration().first(); assertNotEquals(before, after)
        assertNull(importing.state.value.preview); assertNull(importing.state.value.confirmation)
        assertTrue(a.backup.observeUndoRestoreAvailable().first())
        restoring.requestUndoRestore(); assertEquals(DataConfirmation.UNDO_RESTORE, restoring.state.value.confirmation)
        restoring.confirm(); runCurrent(); assertNotEquals(after, a.backup.observeLedgerGeneration().first())
        importing.end(); restoring.end()
    }

    @Test fun `export validates normalized scope date format and report combinations`() {
        assertTrue(ExportFields().request() is LedgerResult.Ok)
        assertTrue(ExportFields(scope = "person:id").request().errorOrNull() is LedgerError.InvalidField)
        assertTrue(ExportFields(from = "2026-01-01").request().errorOrNull() is LedgerError.InvalidField)
        assertTrue(ExportFields(format = ExportFormat.XLSX, excelFriendly = true).request().errorOrNull() is LedgerError.InvalidField)
        for (scope in listOf("full", "person:id", "group:id")) {
            assertTrue(ExportFields(ExportShape.REPORT, ExportFormat.CSV, scope, "2026-01-01", "2026-12-31").request() is LedgerResult.Ok)
        }
        assertTrue(ExportFields(ExportShape.REPORT, from = "2026-12-31", to = "2026-01-01").request().errorOrNull() is LedgerError.InvalidField)
        assertEquals("zip", ExportFields().extension()); assertEquals("application/zip", ExportFields().mime())
        assertEquals("csv", ExportFields(shape = ExportShape.REPORT).extension())
    }

    @Test fun `export sends backend request and displays the actual simulated row count`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var exportedRequest: ExportRequest? = null
        val api = object : ImportExportApi by a.importExport {
            override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> { exportedRequest = request; return LedgerResult.Ok(ExportInfo(42)) }
        }
        val vm = vm(a, io = api); vm.open("export"); runCurrent()
        vm.exportChange { ExportFields(ExportShape.REPORT, ExportFormat.CSV, "person:p", "2026-01-01", "2026-11-05", true) }
        vm.pickExport(); assertNotNull(vm.state.value.picker); vm.documentResult("document"); runCurrent()
        assertEquals(ExportScope.Person(PersonId("p")), exportedRequest!!.scope); assertTrue(exportedRequest!!.excelFriendly)
        assertTrue(vm.state.value.message!!.contains("42 rows")); assertTrue(vm.state.value.message!!.contains("No financial data")); vm.end()
    }

    @Test fun `foreign identity exposes no rows counts or balances until explicit acceptance`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("import"); runCurrent(); vm.simulateImport("foreign"); runCurrent()
        val preview = vm.state.value.preview!!
        assertTrue(preview.identity is ImportIdentity.NeedsConfirmation); assertTrue(preview.rows.isEmpty())
        assertTrue(preview.balanceChanges.isEmpty()); assertEquals(ImportCounts(0, 0, 0, 0), preview.counts)
        vm.requestCommit(); assertNull(vm.state.value.confirmation)
        vm.confirmIdentity(true); runCurrent(); assertTrue(vm.state.value.preview!!.identity is ImportIdentity.Mapped)
        assertTrue(vm.state.value.preview!!.rows.isNotEmpty()); vm.end()
    }

    @Test fun `declining identity imports nothing and removes the preview`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val before = a.importExport.observeImportBatches().first()
        val vm = vm(a); vm.open("import"); runCurrent(); vm.simulateImport("foreign"); runCurrent()
        vm.confirmIdentity(false); runCurrent(); assertNull(vm.state.value.preview)
        assertEquals(before, a.importExport.observeImportBatches().first()); assertTrue(vm.state.value.message!!.contains("Nothing was imported")); vm.end()
    }

    @Test fun `forced owner deletion restoration choices remain backend controlled`() = runTest(dispatcher) {
        val a = FakeApis.seeded()
        val deleted = ImportRowKey(EntityType.PAYMENT_HANDLE, "owned-delete")
        val restored = ImportRowKey(EntityType.GROUP_MEMBER, "owned-restore")
        val api = object : ImportExportApi by a.importExport {
            override suspend fun parseImport(source: DocumentRef) = a.importExport.parseImport(source).map { it.copy(rows = it.rows + listOf(
                ImportRow(deleted, "Owned handle", emptyList(), ImportRowChoice.DeletedWith("Owner"), false),
                ImportRow(restored, "Owned membership", emptyList(), ImportRowChoice.RestoredWith("Group"), false))) }
        }
        val vm = vm(a, io = api); vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
        val selectable = vm.state.value.preview!!.rows.first { it.choice == ImportRowChoice.KeepOrTake }.key
        vm.choices { it.copy(takeIncoming = setOf(deleted, restored, selectable)) }
        assertEquals(setOf(selectable), vm.state.value.choices.takeIncoming)
        assertTrue(vm.state.value.preview!!.rows.first { it.key == deleted }.choice is ImportRowChoice.DeletedWith)
        assertTrue(vm.state.value.preview!!.rows.first { it.key == restored }.choice is ImportRowChoice.RestoredWith); vm.end()
    }

    @Test fun `invalid rows require deliberate skipping before import confirmation`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("import"); runCurrent(); vm.simulateImport("invalid"); runCurrent()
        assertTrue(vm.state.value.preview!!.invalidRows.isNotEmpty())
        vm.requestCommit(); assertEquals("invalidRows", (vm.state.value.error as LedgerError.InvalidField).field)
        assertNull(vm.state.value.confirmation)
        vm.choices { it.copy(skipInvalidRows = true) }; vm.requestCommit(); assertEquals(DataConfirmation.COMMIT_IMPORT, vm.state.value.confirmation)
        vm.confirm(); runCurrent(); assertNull(vm.state.value.error); assertNull(vm.state.value.preview); vm.end()
    }

    @Test fun `stale import cannot commit until refreshed and changed decisions are cleared`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var changeDiff = false
        val api = object : ImportExportApi by a.importExport {
            override suspend fun refreshPreview(preview: ImportPreview) = a.importExport.refreshPreview(preview).map { fresh ->
                if (!changeDiff) fresh else fresh.copy(rows = fresh.rows.map { it.copy(changedSinceLastPreview = true, changes = listOf(FieldChange("note", "old", "changed"))) })
            }
        }
        val vm = vm(a, io = api); vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
        val row = vm.state.value.preview!!.rows.first().key
        vm.choices { it.copy(takeIncoming = setOf(row)) }
        a.people.createPerson(PersonDraft("Write after preview"), a.backup.observeLedgerGeneration().first())
        vm.requestCommit(); vm.confirm(); runCurrent(); assertEquals(LedgerError.ImportPreviewStale, vm.state.value.error)
        vm.requestCommit(); assertNull(vm.state.value.confirmation)
        vm.refresh(); runCurrent(); assertTrue(row in vm.state.value.choices.takeIncoming)
        changeDiff = true; vm.refresh(); runCurrent()
        assertTrue(vm.state.value.choices.takeIncoming.isEmpty()); assertTrue(vm.state.value.preview!!.rows.all { it.changedSinceLastPreview })
        vm.end()
    }

    @Test fun `import confirmation deduplicates commits and completed preview cannot be resubmitted`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); var commits = 0
        val api = object : ImportExportApi by a.importExport {
            override suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId> { commits++; gate.await(); return a.importExport.commitImport(preview, choices) }
        }
        val vm = vm(a, io = api); vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
        vm.requestCommit(); assertNotNull(vm.state.value.confirmation); vm.confirm(); vm.confirm(); runCurrent()
        assertEquals(1, commits); assertTrue(vm.state.value.busy)
        vm.requestLeave(); assertFalse(vm.state.value.exit)
        gate.complete(Unit); runCurrent(); assertNull(vm.state.value.preview)
        vm.requestCommit(); vm.confirm(); runCurrent(); assertEquals(1, commits); vm.end()
    }

    @Test fun `partial undo reports actual skipped rows and does not claim exact balance restoration`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val skipped = SkippedRow(ImportRowKey(EntityType.PERSON, "p"), "Changed after import")
        val api = object : ImportExportApi by a.importExport {
            override suspend fun undoImport(id: ImportBatchId) = LedgerResult.Ok(UndoImportResult(listOf(skipped)))
        }
        val vm = vm(a, io = api); vm.open("history"); runCurrent()
        vm.requestUndoImport(ImportBatchId("batch")); assertEquals(DataConfirmation.UNDO_IMPORT, vm.state.value.confirmation)
        vm.confirm(); runCurrent(); assertEquals(listOf(skipped), vm.state.value.undoResult!!.skipped)
        assertFalse(vm.state.value.undoResult!!.complete); assertFalse(vm.state.value.message!!.contains("exactly restored")); vm.end()
    }

    @Test fun `parse failure is actionable and unfinished previews require discard confirmation`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("import"); runCurrent()
        vm.simulateImport("corrupt"); runCurrent(); assertTrue(vm.state.value.error is LedgerError.CorruptFile)
        vm.simulateImport("normal"); runCurrent(); vm.requestLeave(); assertTrue(vm.state.value.discard)
        vm.keepEditing(); assertNotNull(vm.state.value.preview); assertFalse(vm.state.value.discard)
        vm.requestLeave(); vm.discard(); assertTrue(vm.state.value.exit); assertNull(vm.state.value.preview); vm.end()
    }

    @Test fun `real backup picker requests usable encrypted backup metadata`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a, services = DataServices.Real); vm.open("backup"); runCurrent()
        vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false)
        val encrypted = vm.state.value.picker!!
        assertEquals("iou-backup.ioub", encrypted.name); assertEquals("application/octet-stream", encrypted.mime); assertTrue(encrypted.create)
        vm.documentResult(null); runCurrent()
        vm.encryption(false); vm.beginBackup(charArrayOf(), charArrayOf(), true)
        val plain = vm.state.value.picker!!
        assertEquals("iou-backup.json", plain.name); assertEquals("application/json", plain.mime)
        vm.end()
    }

    @Test fun `fake backup picker keeps the simulation naming`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = vm(a, services = DataServices.Simulated); vm.open("backup"); runCurrent()
        vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false)
        val picker = vm.state.value.picker!!
        assertEquals("IOU-SIMULATION-EMPTY-backup.txt", picker.name); assertEquals("text/plain", picker.mime)
        vm.end()
    }

    @Test fun `xlsx export is rejected before any picker opens`() = runTest(dispatcher) {
        assertEquals(LedgerError.InvalidField("format"), ExportFields(shape = ExportShape.REPORT, format = ExportFormat.XLSX).request().errorOrNull())
        val a = FakeApis.seeded(); val vm = vm(a); vm.open("export"); runCurrent()
        vm.exportChange { it.copy(shape = ExportShape.REPORT, format = ExportFormat.XLSX) }
        vm.pickExport()
        assertNull(vm.state.value.picker); assertEquals(LedgerError.InvalidField("format"), vm.state.value.error)
        vm.end()
    }

    @Test fun `real data services write a usable backup and a zip export to the chosen documents`() = runTest(dispatcher) {
        val harness = io.github.codex07.iou.testutil.RealHarness(java.time.Clock.fixed(io.github.codex07.iou.testutil.T0, java.time.ZoneOffset.UTC), io.github.codex07.iou.testutil.SequentialIds("vm"))
        try {
            val backend = harness.start(); SeedData.populate(backend.store); val a = backend.apis
            val vm = DataManagementViewModel(a.backup, a.importExport, a.settings, a.people, a.groups, dispatcher, DataServices.Real, replacements())
            vm.open("backup"); awaitState(vm) { !it.loading }
            vm.encryption(false); vm.beginBackup(charArrayOf(), charArrayOf(), true); vm.documentResult("plain.json")
            awaitState(vm) { !it.busy && it.message != null }
            assertEquals("Backup saved to your chosen document.", vm.state.value.message)
            val plain = harness.documents.file(DocumentRef("plain.json")).readBytes()
            assertTrue(plain.isNotEmpty() && plain[0] == '{'.code.toByte()); assertFalse(String(plain, 0, 4, Charsets.US_ASCII) == "IOUB")

            vm.encryption(true); vm.beginBackup("testphrase".toCharArray(), "testphrase".toCharArray(), false); vm.documentResult("secret.ioub")
            awaitState(vm) { !it.busy && it.message != null && it.picker == null && harness.documents.file(DocumentRef("secret.ioub")).exists() }
            assertEquals("IOUB", String(harness.documents.file(DocumentRef("secret.ioub")).readBytes(), 0, 4, Charsets.US_ASCII))

            vm.pickExport(); assertEquals("iou-export.zip", vm.state.value.picker!!.name); assertEquals("application/zip", vm.state.value.picker!!.mime)
            vm.documentResult("export.zip")
            awaitState(vm) { !it.busy && it.message?.startsWith("Exported") == true }
            java.util.zip.ZipFile(harness.documents.file(DocumentRef("export.zip"))).use { assertNotNull(it.getEntry("manifest.json")) }
            vm.end()
        } finally { harness.stop() }
    }

    /** Waits for [condition] in real time (the virtual clock would skip the wait while real I/O runs) so a regression fails instead of hanging. */
    private suspend fun awaitState(vm: DataManagementViewModel, condition: (DataState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first(condition) } }
}
