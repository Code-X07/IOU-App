package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis

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
}
