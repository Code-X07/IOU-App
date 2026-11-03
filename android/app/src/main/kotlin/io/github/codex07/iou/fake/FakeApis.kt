package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis
import java.time.Clock

object FakeApis {
    fun create(ledger: InMemoryLedger): Apis = Apis(
        setup = FakeSetupApi(ledger),
        people = FakePeopleApi(ledger),
        groups = FakeGroupsApi(ledger),
        ledger = FakeLedgerApi(ledger),
        fx = FakeFxApi(),
        backup = FakeBackupApi(ledger),
        importExport = FakeImportExportApi(ledger),
        settings = FakeSettingsApi(ledger),
    )

    /** The fake with demo data, as the `fake` flavor and Compose previews use it. */
    fun seeded(clock: Clock = Clock.systemUTC()): Apis = create(InMemoryLedger(clock).also(SeedData::populate))
}
