package io.github.codex07.iou.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.data.RealBackend
import javax.inject.Singleton

/** real flavor: the shared store persisted in Room (ADR-0010). */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(@ApplicationContext context: Context): RealBackend = RealBackend.android(context)

    @Provides
    @Singleton
    fun apis(backend: RealBackend): Apis = backend.apis
}
