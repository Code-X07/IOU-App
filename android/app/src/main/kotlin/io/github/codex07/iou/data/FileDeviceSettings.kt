package io.github.codex07.iou.data

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.DeviceSettingsStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Device-local settings (spec §3.3) as a small JSON file. The app keeps it in `noBackupFilesDir`, so it never
 * leaves the device (spec §9). Read once at construction; every update replaces the file atomically.
 */
class FileDeviceSettings(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DeviceSettingsStore {
    private val lock = Mutex()
    private val state = MutableStateFlow(read())

    override val settings: Flow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = lock.withLock {
        val next = transform(state.value)
        if (next == state.value) return@withLock
        // Not cancellable: a caller cancelled once the file is written would otherwise skip the memory update,
        // and the next update would then write its change over stale settings.
        withContext(NonCancellable + io) {
            file.writeAtomically(json.encodeToString(Stored.serializer(), Stored.of(next)))
            state.value = next
        }
    }

    private fun read(): DeviceSettings =
        if (file.exists()) json.decodeFromString(Stored.serializer(), file.readText()).toSettings() else DeviceSettings()

    @Serializable
    private data class Stored(
        val appLockEnabled: Boolean = false,
        val lockTimeout: String = LockTimeout.ONE_MINUTE.name,
        val screenProtection: Boolean = false,
        val lastManualBackupAt: String? = null,
    ) {
        fun toSettings() = DeviceSettings(
            appLockEnabled = appLockEnabled,
            lockTimeout = LockTimeout.entries.firstOrNull { it.name == lockTimeout } ?: LockTimeout.ONE_MINUTE,
            screenProtection = screenProtection,
            lastManualBackupAt = lastManualBackupAt?.let(Instant::parse),
        )

        companion object {
            fun of(settings: DeviceSettings) = Stored(
                settings.appLockEnabled, settings.lockTimeout.name, settings.screenProtection, settings.lastManualBackupAt?.toString(),
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
