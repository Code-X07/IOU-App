package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.api.contract.BackupApiContract
import io.github.codex07.iou.api.contract.FxApiContract
import io.github.codex07.iou.api.contract.GroupsApiContract
import io.github.codex07.iou.api.contract.ImportExportApiContract
import io.github.codex07.iou.api.contract.ImportFixture
import io.github.codex07.iou.api.contract.LedgerApiContract
import io.github.codex07.iou.api.contract.PeopleApiContract
import io.github.codex07.iou.api.contract.RecurringApiContract
import io.github.codex07.iou.api.contract.SettingsApiContract
import io.github.codex07.iou.api.contract.SetupApiContract
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.exportTo
import io.github.codex07.iou.testutil.withFirstTransferNote
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach

// The M0 contract suites against the real backend on Room (spec §11, AC M1-14), `groups` tests included
// (index, locked decision 6). FX runs on a mock Frankfurter, and backups, exports and imports on files
// (`RealHarness`).

private fun harness(prefix: String = "real") = RealHarness(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds(prefix))

class RealSetupApiContractTest : SetupApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealPeopleApiContractTest : PeopleApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealGroupsApiContractTest : GroupsApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealLedgerApiContractTest : LedgerApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealFxApiContractTest : FxApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealBackupApiContractTest : BackupApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    override fun backupTarget(name: String) = DocumentRef(name)
    @AfterEach fun stop() = harness.stop()
}

/**
 * The fixtures are made when a test asks for them: [ImportFixture.OWN_EXPORT] is an export of the live ledger
 * with the first transfer's note changed, [ImportFixture.FOREIGN_EXPORT] an export of a second install.
 */
class RealImportExportApiContractTest : ImportExportApiContract() {
    private val harness = harness()
    private val other = harness("other")
    override fun newApis() = harness.start().apis
    override fun exportTarget() = DocumentRef("out.zip")
    override fun backupTarget(name: String) = DocumentRef(name)

    override fun importFile(fixture: ImportFixture): DocumentRef = runBlocking {
        when (fixture) {
            ImportFixture.OWN_EXPORT -> {
                val file = apis.exportTo(harness.documents, "own-export.zip")
                file.writeBytes(withFirstTransferNote(file.readBytes(), "Imported note"))
            }
            ImportFixture.FOREIGN_EXPORT -> {
                val second = other.start()
                second.apis.setup.completeSetup("Me elsewhere", ApiContract.EUR)
                second.apis.exportTo(other.documents, "foreign-export.zip").copyTo(harness.documents.file(DocumentRef("foreign-export.zip")))
                other.stop()
            }
        }
        DocumentRef(if (fixture == ImportFixture.OWN_EXPORT) "own-export.zip" else "foreign-export.zip")
    }

    @AfterEach fun stop() {
        harness.stop()
        other.stop()
    }
}

class RealSettingsApiContractTest : SettingsApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealRecurringApiContractTest : RecurringApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}
