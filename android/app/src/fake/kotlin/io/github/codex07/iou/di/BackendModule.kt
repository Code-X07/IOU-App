package io.github.codex07.iou.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.InMemoryDeviceSettings
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** fake flavor: the in-memory ledger with demo data, and the real app-lock policy. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun device(): DeviceSettingsStore = InMemoryDeviceSettings()

    @Provides
    @Singleton
    fun lock(device: DeviceSettingsStore): TimedLockGate = TimedLockGate(device) { SystemClock.elapsedRealtime().milliseconds }

    @Provides
    @Singleton
    fun apis(device: DeviceSettingsStore, lock: TimedLockGate): Apis = FakeApis.seeded(device = device, lock = lock)
}
