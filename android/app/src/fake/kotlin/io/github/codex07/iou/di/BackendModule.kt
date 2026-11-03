package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import javax.inject.Singleton

/** fake flavor: the in-memory ledger with demo data. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun apis(): Apis = FakeApis.seeded()
}
