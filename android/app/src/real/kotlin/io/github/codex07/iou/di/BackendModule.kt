package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.InMemoryLedger
import javax.inject.Singleton

/** real flavor in M0: the same in-memory ledger, empty (index, locked decision 8). M1 binds Room here. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun apis(): Apis = FakeApis.create(InMemoryLedger())
}
