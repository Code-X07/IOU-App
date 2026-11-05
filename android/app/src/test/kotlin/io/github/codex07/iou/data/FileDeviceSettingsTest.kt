package io.github.codex07.iou.data

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.TimedLockGate
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileDeviceSettingsTest {

    private val file = File(createTempDirectory("iou-device").toFile(), "device-settings.json")

    @Test
    fun `settings start at the spec defaults and persist across instances`() = runTest {
        val first = FileDeviceSettings(file)
        first.settings.first() shouldBe DeviceSettings()
        first.update { it.copy(appLockEnabled = true, lockTimeout = LockTimeout.IMMEDIATELY) }
        first.update { it.copy(screenProtection = true, lastManualBackupAt = T0) }

        val second = FileDeviceSettings(file)
        second.settings.first() shouldBe DeviceSettings(true, LockTimeout.IMMEDIATELY, true, T0)
        second.update { it.copy(lastManualBackupAt = null) }
        FileDeviceSettings(file).settings.first().lastManualBackupAt shouldBe null
        requireNotNull(file.parentFile).list()!!.toList() shouldBe listOf("device-settings.json")
    }

    @Test
    fun `an unreadable file counts as the defaults`() = runTest {
        for (text in listOf("{appLockEnabled:true}", """{"lastManualBackupAt":"yesterday"}""")) {
            file.writeText(text)
            FileDeviceSettings(file).settings.first() shouldBe DeviceSettings()
        }

        val directory = File(requireNotNull(file.parentFile), "device-settings-directory")
        directory.mkdir()
        FileDeviceSettings(directory).settings.first() shouldBe DeviceSettings()
    }

    @Test
    fun `an update whose caller is cancelled once the file is written still updates memory`() = runTest {
        val caller = Job()
        val writes = mutableListOf<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                writes += block
            }
        }
        FileDeviceSettings(file).update { it.copy(appLockEnabled = true) }
        val store = FileDeviceSettings(file, io)
        val gate = TimedLockGate(store)
        val ledger = LedgerStore(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-1"), initial = LedgerContent())
        val api = StoreSettingsApi(ledger, store, gate)
        gate.required.value shouldBe true
        val update = launch(caller) { api.setAppLockEnabled(false) }
        runCurrent()
        // The write completes, then the caller is cancelled before it resumes, as leaving a settings screen could.
        writes.single().run()
        caller.cancel()
        update.join()

        store.settings.first().appLockEnabled shouldBe false
        FileDeviceSettings(file).settings.first().appLockEnabled shouldBe false
        gate.required.value shouldBe false
    }
}
