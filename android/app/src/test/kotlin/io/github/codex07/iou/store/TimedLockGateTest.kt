package io.github.codex07.iou.store

import io.github.codex07.iou.api.LockTimeout
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TimedLockGateTest {

    private var now = Duration.ZERO

    private fun gate(settings: DeviceSettings = DeviceSettings(appLockEnabled = true)): Pair<TimedLockGate, InMemoryDeviceSettings> {
        val device = InMemoryDeviceSettings(settings)
        return TimedLockGate(device) { now } to device
    }

    private fun TimedLockGate.away(time: Duration) {
        backgrounded()
        now += time
        foregrounded()
    }

    @Test
    fun `a cold start is locked exactly when app lock is on`() {
        gate().first.required.value shouldBe true
        gate(DeviceSettings(appLockEnabled = false)).first.required.value shouldBe false
    }

    @Test
    fun `the app locks again after the timeout in the background, not before`() {
        val (gate, _) = gate()
        gate.unlocked()
        gate.required.value shouldBe false

        gate.away(59.seconds)
        gate.required.value shouldBe false
        gate.away(1.minutes)
        gate.required.value shouldBe true
    }

    @Test
    fun `immediately locks on every return, and a start without a background never locks`() {
        val (gate, _) = gate(DeviceSettings(appLockEnabled = true, lockTimeout = LockTimeout.IMMEDIATELY))
        gate.unlocked()
        gate.foregrounded()
        gate.required.value shouldBe false

        gate.away(Duration.ZERO)
        gate.required.value shouldBe true
    }

    @Test
    fun `the timeout and the switch are read on return`() = runTest {
        val (gate, device) = gate()
        gate.unlocked()

        gate.backgrounded()
        device.update { it.copy(lockTimeout = LockTimeout.FIVE_MINUTES) }
        now += 2.minutes
        gate.foregrounded()
        gate.required.value shouldBe false

        device.update { it.copy(appLockEnabled = false) }
        gate.away(1.minutes * 60)
        gate.required.value shouldBe false
    }

    @Test
    fun `turning app lock off clears it, and turning it on mid-session doesn't lock`() = runTest {
        val (gate, device) = gate()
        device.update { it.copy(appLockEnabled = false) }
        gate.lockEnabledChanged(false)
        gate.required.value shouldBe false

        device.update { it.copy(appLockEnabled = true) }
        gate.lockEnabledChanged(true)
        gate.required.value shouldBe false
        gate.away(1.minutes)
        gate.required.value shouldBe true
    }
}
