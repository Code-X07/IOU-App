package io.github.codex07.iou.ui.viewmodel

import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }
    private fun replacements() = LedgerReplacements(CoroutineScope(SupervisorJob() + dispatcher))
    private fun vm(a: Apis, backup: BackupApi = a.backup, transfer: ImportExportApi = a.importExport, holder: LedgerReplacements = replacements()) =
        DataManagementViewModel(backup, transfer, a.settings, a.people, a.groups, dispatcher, DataServices.Simulated, holder)
    private fun spy(a: Apis, discarded: MutableList<ImportPreview>) = object : ImportExportApi by a.importExport {
        override fun discardImport(preview: ImportPreview) { discarded += preview; a.importExport.discardImport(preview) }
    }

    /** A backup API that publishes a new generation and then waits on [gate] before returning [result]. */
    private fun gated(a: Apis, generations: MutableStateFlow<LedgerGeneration>, gate: CompletableDeferred<Unit>, result: LedgerResult<Unit> = LedgerResult.Ok(Unit)) =
        object : BackupApi by a.backup {
            override fun observeLedgerGeneration() = generations
            override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> {
                generations.value = LedgerGeneration("replacement"); gate.await(); return result
            }
            override suspend fun undoLastRestore(): LedgerResult<Unit> {
                generations.value = LedgerGeneration("undone"); gate.await(); return result
            }
        }
    private suspend fun generations(a: Apis) = MutableStateFlow(a.backup.observeLedgerGeneration().first())
    private fun TestScope.startRestore(vm: DataManagementViewModel) {
        vm.open("restore"); runCurrent(); vm.pickRestore(); vm.documentResult("backup"); vm.requestRestore(charArrayOf()); vm.confirm()
    }

    @Test fun `replacement stays busy after generation is published until restore returns`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); val holder = replacements()
        val vm = vm(a, gated(a, generations(a), gate), holder = holder)
        try {
            vm.open("restore"); runCurrent(); vm.pickRestore(); vm.documentResult("backup")
            vm.requestRestore(charArrayOf()); vm.confirm(); runCurrent()
            assertFalse(gate.isCompleted)
            assertTrue(vm.state.value.busy, "Replacement is still running after publishing the new generation")
            assertTrue(holder.running.value); assertNull(holder.outcome.value)
            vm.confirm(); vm.requestUndoRestore(); vm.pickRestore(); runCurrent()
            assertNull(vm.state.value.picker, "Duplicate actions are rejected while the restore runs")
            gate.complete(Unit); runCurrent()
            assertFalse(vm.state.value.busy); assertFalse(holder.running.value)
            assertTrue(vm.state.value.message!!.startsWith("Restore simulation completed"))
            assertEquals(vm.state.value.message, holder.outcome.value)
        } finally { gate.complete(Unit); runCurrent(); vm.end() }
    }

    @Test fun `a failed replacement ends busy and publishes the failure text`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); val holder = replacements()
        val failure = LedgerError.RestoreFailed("x")
        val vm = vm(a, gated(a, generations(a), gate, LedgerResult.Err(failure)), holder = holder)
        try {
            startRestore(vm); runCurrent(); assertTrue(vm.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertFalse(vm.state.value.busy); assertEquals(failure, vm.state.value.error)
            assertEquals(failure.userMessage(), holder.outcome.value); assertFalse(holder.running.value)
        } finally { vm.end() }
    }

    @Test fun `undo restore stays busy until it returns`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); val holder = replacements()
        val vm = vm(a, gated(a, generations(a), gate), holder = holder)
        try {
            vm.open("restore"); runCurrent(); vm.requestUndoRestore(); vm.confirm(); runCurrent()
            assertTrue(vm.state.value.busy); assertTrue(holder.running.value)
            gate.complete(Unit); runCurrent()
            assertFalse(vm.state.value.busy); assertTrue(holder.outcome.value!!.startsWith("Undo-restore simulation completed"))
        } finally { gate.complete(Unit); runCurrent(); vm.end() }
    }

    @Test fun `the restore finishes and its outcome is kept after the screen's ViewModel is gone`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); val holder = replacements()
        val vm = vm(a, gated(a, generations(a), gate), holder = holder)
        startRestore(vm); runCurrent(); assertTrue(holder.running.value)
        vm.end()
        gate.complete(Unit); runCurrent()
        assertFalse(holder.running.value); assertTrue(holder.outcome.value!!.startsWith("Restore simulation completed"))
        holder.consumeOutcome(); assertNull(holder.outcome.value)
    }

    @Test fun `only one replacement runs at a time across screens`() = runTest(dispatcher) {
        val holder = replacements(); val gate = CompletableDeferred<Unit>(); var second = 0
        assertTrue(holder.run({ gate.await(); LedgerResult.Ok(Unit) }, "done") { })
        assertFalse(holder.run({ second++; LedgerResult.Ok(Unit) }, "other") { })
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(0, second); assertEquals("done", holder.outcome.value)
        assertTrue(holder.run({ LedgerResult.Ok(Unit) }, "again") { })
    }

    @Test fun `an unexpected failure is reported as unconfirmed`() = runTest(dispatcher) {
        val holder = replacements(); var received: LedgerResult<Unit>? = LedgerResult.Ok(Unit)
        holder.run({ error("boom") }, "done") { received = it }; runCurrent()
        assertNull(received); assertFalse(holder.running.value); assertTrue(holder.outcome.value!!.contains("could not be confirmed"))
    }

    @Test fun `discarding an import preview closes its backend session`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val discarded = mutableListOf<ImportPreview>()
        val vm = vm(a, transfer = spy(a, discarded))
        try {
            vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
            val preview = requireNotNull(vm.state.value.preview)
            vm.requestLeave(); vm.discard()
            assertEquals(listOf(preview.id), discarded.map { it.id })
        } finally { vm.end() }
    }

    @Test fun `a ledger replacement closes the preview session`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val discarded = mutableListOf<ImportPreview>(); val generations = generations(a)
        val backup = object : BackupApi by a.backup { override fun observeLedgerGeneration() = generations }
        val vm = vm(a, backup, spy(a, discarded))
        try {
            vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
            val preview = requireNotNull(vm.state.value.preview)
            generations.value = LedgerGeneration("other"); runCurrent()
            assertNull(vm.state.value.preview); assertEquals(listOf(preview.id), discarded.map { it.id })
        } finally { vm.end() }
    }

    @Test fun `a new parse replacing the preview closes the earlier session`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val discarded = mutableListOf<ImportPreview>(); val vm = vm(a, transfer = spy(a, discarded))
        try {
            vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
            val first = requireNotNull(vm.state.value.preview)
            vm.simulateImport("normal"); runCurrent()
            val second = requireNotNull(vm.state.value.preview)
            assertNotEquals(first.id, second.id)
            assertEquals(listOf(first.id), discarded.map { it.id })
        } finally { vm.end() }
    }

    @Test fun `ending the screen and clearing the ViewModel close the preview session`() = runTest(dispatcher) {
        for (clear in listOf(true, false)) {
            val a = FakeApis.seeded(); val discarded = mutableListOf<ImportPreview>(); val vm = vm(a, transfer = spy(a, discarded))
            vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
            val preview = requireNotNull(vm.state.value.preview)
            if (clear) DataManagementViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm) else vm.end()
            assertEquals(listOf(preview.id), discarded.map { it.id }, "clear=$clear")
            vm.end()
        }
    }

    @Test fun `a parse cancelled by leaving still discards the late preview`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val discarded = mutableListOf<ImportPreview>(); val gate = CompletableDeferred<Unit>()
        val transfer = object : ImportExportApi by spy(a, discarded) {
            override suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview> {
                val parsed = a.importExport.parseImport(source); withContext(NonCancellable) { gate.await() }; return parsed
            }
        }
        val vm = vm(a, transfer = transfer)
        vm.open("import"); runCurrent(); vm.simulateImport("normal"); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.requestLeave(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(vm.state.value.preview); assertEquals(1, discarded.size)
        vm.end()
    }

    private fun settingsVm(backup: BackupApi, a: Apis) = AppSettingsViewModel(a.setup, a.settings, a.people, backup,
        ThemePreferences(object : ThemeStorage { override fun read(): String? = null; override fun write(value: String) = true }), DataServices.Simulated, replacements())

    @Test fun `an interrupted restore is reported until acknowledged and then not again`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val interrupted = MutableStateFlow(true); var dismissed = 0
        val backup = object : BackupApi by a.backup {
            override fun observeRestoreInterrupted() = interrupted
            override suspend fun dismissRestoreInterrupted() { dismissed++; interrupted.value = false }
        }
        val vm = settingsVm(backup, a); runCurrent()
        assertTrue(vm.restoreInterrupted.value); assertEquals(0, dismissed, "Nothing is dismissed before the user acknowledges it")
        vm.acknowledgeRestoreInterrupted(); runCurrent()
        assertEquals(1, dismissed); assertFalse(vm.restoreInterrupted.value)
        assertFalse(settingsVm(backup, a).also { runCurrent() }.restoreInterrupted.value, "A later launch does not show it again")
    }

    @Test fun `a normal launch and a committed restore show no interruption notice`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); a.backup.createBackup(DocumentRef("backup"), "testphrase".toCharArray())
        val settings = settingsVm(a.backup, a); runCurrent(); assertFalse(settings.restoreInterrupted.value)
        val vm = vm(a); vm.open("restore"); runCurrent(); vm.pickRestore(); vm.documentResult("backup")
        vm.requestRestore("testphrase".toCharArray()); vm.confirm(); runCurrent()
        assertFalse(settings.restoreInterrupted.value); vm.end()
    }

    @Test fun `a cancellation from the block while the scope is active is reported as unconfirmed`() = runTest(dispatcher) {
        val holder = replacements(); var received: LedgerResult<Unit>? = LedgerResult.Ok(Unit); var called = 0
        holder.run({ throw CancellationException("inner") }, "done") { received = it; called++ }; runCurrent()
        assertFalse(holder.running.value); assertEquals(1, called); assertNull(received)
        assertTrue(holder.outcome.value!!.contains("could not be confirmed"))
        assertTrue(holder.run({ LedgerResult.Ok(Unit) }, "again") { }, "A new replacement can start afterwards")
    }

    @Test fun `a second screen starts nothing and reports its controls unavailable while another restore runs`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); val holder = replacements(); var invoked = 0
        val first = vm(a, gated(a, generations(a), gate), holder = holder)
        val counting = object : BackupApi by a.backup {
            override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> { invoked++; return LedgerResult.Ok(Unit) }
            override suspend fun undoLastRestore(): LedgerResult<Unit> { invoked++; return LedgerResult.Ok(Unit) }
        }
        val second = vm(a, counting, holder = holder)
        try {
            assertFalse(second.replacementRunning.value)
            startRestore(first); runCurrent()
            assertTrue(holder.running.value); assertTrue(second.replacementRunning.value)
            second.open("restore"); runCurrent()
            second.requestUndoRestore(); second.confirm(); second.pickRestore(); runCurrent()
            assertNull(second.state.value.confirmation); assertNull(second.state.value.picker)
            assertEquals(0, invoked); assertFalse(second.state.value.busy)
            gate.complete(Unit); runCurrent()
            assertFalse(second.replacementRunning.value)
        } finally { gate.complete(Unit); runCurrent(); first.end(); second.end() }
    }
}
