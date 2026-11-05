package io.github.codex07.iou.data

import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.api.contract.GroupsApiContract
import io.github.codex07.iou.api.contract.LedgerApiContract
import io.github.codex07.iou.api.contract.PeopleApiContract
import io.github.codex07.iou.api.contract.SettingsApiContract
import io.github.codex07.iou.api.contract.SetupApiContract
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import java.time.Clock
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach

// The M0 contract suites against the real backend on Room (spec §11, AC M1-14), `groups` tests included
// (index, locked decision 6). FX, backup and import/export join in Parts 4, 5 and 7.

private fun harness() = RealHarness(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("real"))

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

class RealSettingsApiContractTest : SettingsApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}
