package io.github.codex07.iou.fake

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InMemoryLedgerTest {

    private fun ledger() = InMemoryLedger(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("fake"))

    @Test
    fun `writes before setup are refused, setup works`() = runTest {
        val ledger = ledger()
        ledger.write { PeopleOps.createPerson(it, PersonDraft("X"), ledger.snapshot.value.generation) } shouldBe
            LedgerResult.Err(LedgerError.NotFound("me"))
        (ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") } is LedgerResult.Ok) shouldBe true
        ledger.ready.first().state.me.displayName shouldBe "Me"
    }

    @Test
    fun `a successful write applies the plan, records one revision per change and bumps ledgerSeq`() {
        val ledger = ledger()
        ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val seq = ledger.snapshot.value.ledgerSeq
        val id = (ledger.write { PeopleOps.createPerson(it, PersonDraft("X"), ledger.snapshot.value.generation) } as LedgerResult.Ok).value

        val snapshot = ledger.snapshot.value
        snapshot.state.people.getValue(id).displayName shouldBe "X"
        snapshot.ledgerSeq shouldBe seq + 1
        val revision = snapshot.revisions.last()
        revision.entityId shouldBe id.value
        revision.action shouldBe RevisionAction.CREATED
        revision.deviceId shouldBe ledger.deviceId
        revision.at shouldBe T0
    }

    @Test
    fun `a failed write changes nothing`() {
        val ledger = ledger()
        ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val before = ledger.snapshot.value
        ledger.write { PeopleOps.createPerson(it, PersonDraft(" "), before.generation) } shouldBe
            LedgerResult.Err(LedgerError.InvalidField("displayName"))
        ledger.snapshot.value shouldBe before
    }

    @Test
    fun `fake rates cross through EUR`() {
        FakeRates.rate(EUR, EUR) shouldBe "1"
        FakeRates.rate(EUR, SEK) shouldBe "11.5"
        FakeRates.rate(SEK, EUR) shouldBe "0.08695652173913043478260869565217391"
    }
}
