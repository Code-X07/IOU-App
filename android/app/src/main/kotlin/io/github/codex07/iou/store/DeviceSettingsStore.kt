package io.github.codex07.iou.store

import io.github.codex07.iou.api.LockTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Device-local settings storage: a file in the real flavor, memory in the fake and in tests. */
interface DeviceSettingsStore {
    /** Readable at once, so start-up decisions (the cold-start lock, `FLAG_SECURE`) never wait. */
    val settings: StateFlow<DeviceSettings>

    suspend fun update(transform: (DeviceSettings) -> DeviceSettings)
}

class InMemoryDeviceSettings(initial: DeviceSettings = DeviceSettings()) : DeviceSettingsStore {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = state.update(transform)
}

/** The app-lock state behind `SettingsApi.observeLockRequired` and `markUnlocked` (spec §9). */
interface LockGate {
    val required: Flow<Boolean>

    fun unlocked()

    /** Called after the app-lock setting changed; turning it on mid-session doesn't lock the open app. */
    fun lockEnabledChanged(enabled: Boolean)
}

/**
 * The app-lock policy (spec §9) for both flavors. With app lock on, the app is locked on a cold start and
 * again when it comes back after at least the lock timeout in the background; [LockTimeout.IMMEDIATELY]
 * locks on every return. An unlock or turning app lock off clears it.
 *
 * The process lifecycle calls [backgrounded] and [foregrounded] (`security.AppSecurity`). [elapsed] is a
 * monotonic clock; on Android pass `SystemClock.elapsedRealtime`, which keeps counting in deep sleep.
 */
class TimedLockGate(
    private val device: DeviceSettingsStore,
    private val elapsed: () -> Duration = { System.nanoTime().nanoseconds },
) : LockGate {
    private val state = MutableStateFlow(device.settings.value.appLockEnabled)
    @Volatile private var backgroundedAt: Duration? = null

    override val required: StateFlow<Boolean> = state.asStateFlow()

    override fun unlocked() {
        state.value = false
    }

    override fun lockEnabledChanged(enabled: Boolean) {
        if (!enabled) state.value = false
    }

    fun backgrounded() {
        backgroundedAt = elapsed()
    }

    /** Settings are read on return, so a timeout changed while in the background applies at once. */
    fun foregrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val settings = device.settings.value
        if (settings.appLockEnabled && elapsed() - since >= settings.lockTimeout.duration) state.value = true
    }

    companion object {
        val LockTimeout.duration: Duration
            get() = when (this) {
                LockTimeout.IMMEDIATELY -> Duration.ZERO
                LockTimeout.ONE_MINUTE -> 1.minutes
                LockTimeout.FIVE_MINUTES -> 5.minutes
                LockTimeout.FIFTEEN_MINUTES -> 15.minutes
            }
    }
}
