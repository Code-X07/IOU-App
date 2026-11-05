package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.InMemoryDeviceSettings
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RateLookup
import io.github.codex07.iou.store.SimpleLockGate
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
import io.github.codex07.iou.store.StorePeopleApi
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

object FakeApis {
    /** An empty in-memory store with the fake's fixed demo rates; nothing is persisted. */
    fun store(clock: Clock = Clock.systemUTC(), ids: IdGenerator = IdGenerator.Random): LedgerStore =
        LedgerStore(clock, ids, DeviceId(ids.next()), rates = MutableStateFlow(RateLookup(FakeRates::rate)), initial = LedgerContent())

    fun create(store: LedgerStore, device: DeviceSettingsStore = InMemoryDeviceSettings()): Apis = Apis(
        setup = StoreSetupApi(store),
        people = StorePeopleApi(store),
        groups = StoreGroupsApi(store),
        ledger = StoreLedgerApi(store),
        fx = FakeFxApi(),
        backup = FakeBackupApi(store, device),
        importExport = FakeImportExportApi(store),
        settings = StoreSettingsApi(store, device, SimpleLockGate()),
    )

    /** The fake with demo data, as the `fake` flavor and Compose previews use it. */
    fun seeded(clock: Clock = Clock.systemUTC()): Apis = create(store(clock).also { runBlocking { SeedData.populate(it) } })
}
