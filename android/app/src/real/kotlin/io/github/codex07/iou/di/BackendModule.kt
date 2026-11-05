package io.github.codex07.iou.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.data.RateRefreshWorker
import io.github.codex07.iou.data.RealBackend
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Singleton

/** real flavor: the shared store persisted in Room (ADR-0010), with the daily rate refresh scheduled. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(@ApplicationContext context: Context): RealBackend =
        RealBackend.android(context).also { RateRefreshWorker.schedule(context) }

    @Provides
    @Singleton
    fun apis(backend: RealBackend): Apis = backend.apis

    @Provides
    fun device(backend: RealBackend): DeviceSettingsStore = backend.device

    @Provides
    fun lock(backend: RealBackend): TimedLockGate = backend.lock
}
