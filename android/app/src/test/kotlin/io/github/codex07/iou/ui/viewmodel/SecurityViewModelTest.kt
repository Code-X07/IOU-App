package io.github.codex07.iou.ui.viewmodel

import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.fake.FakeApis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }

    @Test fun `return before the backend lock requires it stays unlocked`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        apis.settings.setAppLockEnabled(true)
        apis.settings.setLockTimeout(LockTimeout.FIVE_MINUTES)
        // Model the main branch gate: it remains unlocked on a short return.
        val backendLock = MutableStateFlow(true)
        val settings = object : SettingsApi by apis.settings {
            override fun observeLockRequired() = backendLock
            override fun markUnlocked() { backendLock.value = false }
        }
        val vm = SecurityViewModel(settings, apis.backup)
        runCurrent()
        vm.foreground()
        vm.begin(AuthPurpose.UNLOCK, true, true)
        vm.result(vm.state.value.request!!.id, true)
        runCurrent()
        assertFalse(vm.state.value.locked)

        vm.background()
        vm.foreground()
        runCurrent()
        assertFalse(backendLock.value)
        assertFalse(vm.state.value.locked, "The backend timeout has not elapsed")
    }

    @Test fun `generation observation failure stays covered after a later settings emission`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val failGeneration = CompletableDeferred<Unit>()
        val backup = object : BackupApi by apis.backup {
            override fun observeLedgerGeneration() = flow {
                emit(apis.backup.observeLedgerGeneration().first())
                failGeneration.await()
                throw IllegalStateException("Generation observation failed")
            }
        }
        val vm = SecurityViewModel(apis.settings, backup)
        runCurrent()
        vm.foreground()
        assertFalse(vm.state.value.covered)

        failGeneration.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.covered)
        apis.settings.setScreenProtection(true)
        runCurrent()
        assertTrue(vm.state.value.covered, "A settings update cannot restore the failed generation observer")
    }
}
