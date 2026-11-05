package io.github.codex07.iou.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Device-local settings storage: DataStore in the real flavor, memory in the fake and in tests. */
interface DeviceSettingsStore {
    val settings: Flow<DeviceSettings>

    suspend fun update(transform: (DeviceSettings) -> DeviceSettings)
}

class InMemoryDeviceSettings(initial: DeviceSettings = DeviceSettings()) : DeviceSettingsStore {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = state.update(transform)
}

/**
 * The app-lock state behind `SettingsApi.observeLockRequired` and `markUnlocked` (spec §9). The real
 * flavor's policy also follows the process lifecycle; [SimpleLockGate] only knows cold start and unlocks.
 */
interface LockGate {
    val required: Flow<Boolean>

    fun unlocked()

    /** Called after the app-lock setting changed; turning it on mid-session doesn't lock the open app. */
    fun lockEnabledChanged(enabled: Boolean)
}

/** Locked from start exactly when app lock was enabled at start; cleared by an unlock or by disabling it. */
class SimpleLockGate(lockedAtStart: Boolean = false) : LockGate {
    private val state = MutableStateFlow(lockedAtStart)

    override val required: Flow<Boolean> = state.asStateFlow()

    override fun unlocked() {
        state.value = false
    }

    override fun lockEnabledChanged(enabled: Boolean) {
        if (!enabled) state.value = false
    }
}
