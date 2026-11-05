package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import javax.inject.Singleton

/** real flavor until M1 Part 3: the in-memory store, empty (M0 index, locked decision 8). Part 3 binds Room here. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun apis(): Apis = FakeApis.create(FakeApis.store())
}
