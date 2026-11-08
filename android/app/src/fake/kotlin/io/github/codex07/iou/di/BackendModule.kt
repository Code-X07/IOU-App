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
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RecurringRunner
import io.github.codex07.iou.store.TimedLockGate
import io.github.codex07.iou.ui.settings.DataServices
import java.time.ZoneId
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** fake flavor: the in-memory ledger with demo data, the shared runner (no worker, no notifications), the real app-lock policy. */
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
    fun store(): LedgerStore = FakeApis.seededStore(zone = { ZoneId.systemDefault() })

    @Provides
    @Singleton
    fun runner(store: LedgerStore): RecurringRunner = FakeApis.runner(store)

    @Provides
    @Singleton
    fun apis(store: LedgerStore, device: DeviceSettingsStore, lock: TimedLockGate, runner: RecurringRunner): Apis =
        FakeApis.create(store, device, lock, runner)

    @Provides
    fun dataServices(): DataServices = DataServices.Simulated
}
