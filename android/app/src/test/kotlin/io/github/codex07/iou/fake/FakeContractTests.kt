package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.api.contract.BackupApiContract
import io.github.codex07.iou.api.contract.FxApiContract
import io.github.codex07.iou.api.contract.GroupsApiContract
import io.github.codex07.iou.api.contract.ImportExportApiContract
import io.github.codex07.iou.api.contract.ImportFixture
import io.github.codex07.iou.api.contract.LedgerApiContract
import io.github.codex07.iou.api.contract.PeopleApiContract
import io.github.codex07.iou.api.contract.SettingsApiContract
import io.github.codex07.iou.api.contract.SetupApiContract
import io.github.codex07.iou.testutil.SequentialIds
import java.time.Clock
import java.time.ZoneOffset

private fun fakeApis(): Apis =
    FakeApis.create(InMemoryLedger(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("fake")))

class FakeSetupApiContractTest : SetupApiContract() { override fun newApis() = fakeApis() }
class FakePeopleApiContractTest : PeopleApiContract() { override fun newApis() = fakeApis() }
class FakeGroupsApiContractTest : GroupsApiContract() { override fun newApis() = fakeApis() }
class FakeLedgerApiContractTest : LedgerApiContract() { override fun newApis() = fakeApis() }
class FakeFxApiContractTest : FxApiContract() { override fun newApis() = fakeApis() }
class FakeSettingsApiContractTest : SettingsApiContract() { override fun newApis() = fakeApis() }

class FakeBackupApiContractTest : BackupApiContract() {
    override fun newApis() = fakeApis()
    override fun backupTarget(name: String) = DocumentRef("fake://backups/$name")
}

class FakeImportExportApiContractTest : ImportExportApiContract() {
    override fun newApis() = fakeApis()
    override fun importFile(fixture: ImportFixture) = DocumentRef(
        when (fixture) {
            ImportFixture.OWN_EXPORT -> "fake://imports/own-export.zip"
            ImportFixture.FOREIGN_EXPORT -> "fake://imports/foreign-export.zip"
        },
    )
    override fun exportTarget() = DocumentRef("fake://exports/out.zip")
    override fun backupTarget(name: String) = DocumentRef("fake://backups/$name")
}
