package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Schedules survive a restart of the real backend (AC 28: "neither comes back after a restart"). */
class RealRecurringTest {

    private val harness = RealHarness(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("recurring"))

    @AfterEach
    fun stop() = harness.stop()

    @Test
    fun `posted and skipped occurrences stay handled after a restart`() = runTest {
        val apis = harness.start().apis
        val me = (apis.setup.completeSetup("Me", CurrencyCode("EUR")) as LedgerResult.Ok).value
        val x = (apis.people.createPerson(PersonDraft("X"), apis.backup.observeLedgerGeneration().first()) as LedgerResult.Ok).value
        val draft = RecurringDraft(
            payers = listOf(SplitInput(me)), description = "Rent", total = Money(90_000, CurrencyCode("EUR")),
            participants = listOf(SplitInput(me), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = LocalDate.parse("2026-02-01"),
        )
        val id = (apis.recurring.saveSchedule(draft, ScheduleSaveMode.Create(apis.backup.observeLedgerGeneration().first())) as LedgerResult.Ok).value
        val february = (apis.recurring.occurrenceDraft(id) as LedgerResult.Ok).value
        apis.recurring.postOccurrence(id, february.occurrence, february.draft, february.token)
        val token = apis.recurring.observeSchedule(id).first()!!.token
        apis.recurring.skipOccurrences(id, LocalDate.parse("2026-03-01"), token) shouldBe LedgerResult.Ok(Unit)
        harness.stop()

        val again = harness.start().apis
        again.recurring.observeDue().first() shouldBe emptyList()
        again.recurring.observeSchedule(id).first()!!.handledThrough shouldBe LocalDate.parse("2026-03-01")
    }
}
