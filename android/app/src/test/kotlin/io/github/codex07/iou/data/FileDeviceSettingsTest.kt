package io.github.codex07.iou.data

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
        file.parentFile.list()!!.toList() shouldBe listOf("device-settings.json")
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
        val store = FileDeviceSettings(file, io)
        val update = launch(caller) { store.update { it.copy(appLockEnabled = true) } }
        runCurrent()
        // The write completes, then the caller is cancelled before it resumes, as leaving a settings screen could.
        writes.single().run()
        caller.cancel()
        update.join()

        store.settings.first().appLockEnabled shouldBe true
        FileDeviceSettings(file).settings.first().appLockEnabled shouldBe true
    }
}
