package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.GroupsApi
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.RecurringApi
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.api.SetupApi

/** Exposes the nine interfaces of whichever `Apis` the flavor's BackendModule provides. */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Provides fun setup(apis: Apis): SetupApi = apis.setup
    @Provides fun people(apis: Apis): PeopleApi = apis.people
    @Provides fun groups(apis: Apis): GroupsApi = apis.groups
    @Provides fun ledger(apis: Apis): LedgerApi = apis.ledger
    @Provides fun fx(apis: Apis): FxApi = apis.fx
    @Provides fun backup(apis: Apis): BackupApi = apis.backup
    @Provides fun importExport(apis: Apis): ImportExportApi = apis.importExport
    @Provides fun settings(apis: Apis): SettingsApi = apis.settings
    @Provides fun recurring(apis: Apis): RecurringApi = apis.recurring
}
