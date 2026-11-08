package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The cut-off rule and hold resumes (spec §5.7, AC 31–32 at op level). */
class CutOffTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    private fun rent(x: PersonId, autoPost: Boolean) = RecurringDraft(
        payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
        participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = d("2026-02-01"), autoPost = autoPost,
    )

    @Test
    fun `saving an auto-post schedule sets its cut-off to today, and posting and skipping keep it`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        h.today = d("2026-05-10")
        val id = h.run { RecurringOps.save(it, rent(x, autoPost = true), ScheduleSaveMode.Create(h.generation)) }.ok()
        fun schedule() = h.state.schedules.getValue(id)
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.today = d("2026-05-20")
        val february = ExpenseDraft(ME, "Rent", total = eur(90_000), participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = d("2026-02-01"))
        h.run { RecurringOps.post(it, id, d("2026-02-01"), february, h.token(schedule())) }.ok()
        h.run { RecurringOps.skip(it, id, d("2026-03-01"), h.token(schedule())) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.run { RecurringOps.save(it, rent(x, autoPost = true).copy(description = "Flat rent"), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-20")
    }

    @Test
    fun `a confirm-first schedule keeps its cut-off until auto-post is switched on`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        h.today = d("2026-05-10")
        val id = h.run { RecurringOps.save(it, rent(x, autoPost = false), ScheduleSaveMode.Create(h.generation)) }.ok()
        fun schedule() = h.state.schedules.getValue(id)

        h.today = d("2026-05-20")
        h.run { RecurringOps.save(it, rent(x, autoPost = false).copy(note = "Due on the 1st"), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-10")

        h.run { RecurringOps.save(it, rent(x, autoPost = true), ScheduleSaveMode.Update(id, h.token(schedule()))) }.ok()
        schedule().autoPostFrom shouldBe d("2026-05-20")
    }

    @Test
    fun `the handledThrough raise of a deleted posted expense keeps the cut-off`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), handledThrough = d("2026-02-01"), autoPost = true)
        val march = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = id, recurringOn = d("2026-03-01"))
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")

        h.run { EntryOps.deleteEntry(it, march, h.token(h.state.expenses.getValue(march))) }.ok()

        h.state.schedules.getValue(id).handledThrough shouldBe d("2026-03-01")
        h.state.schedules.getValue(id).autoPostFrom shouldBe D0
    }

    @Test
    fun `unarchiving a group resumes its held schedules with a fresh cut-off, and archiving writes none`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x), archived = true)
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, autoPost = true)
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")
        fun group() = h.state.groups.getValue(flat)

        h.run { GroupOps.setGroupArchived(it, flat, false, h.token(group())) }.ok()

        h.lastPlan!!.changes.map { it.entity.entityType to it.reason } shouldBe listOf(
            EntityType.GROUP to "Unarchived",
            EntityType.RECURRING_EXPENSE to "Resumed: Flat unarchived",
        )
        h.state.schedules.getValue(id).let { it.meta.version to it.autoPostFrom } shouldBe (2 to d("2026-06-01"))

        h.run { GroupOps.setGroupArchived(it, flat, true, h.token(group())) }.ok()
        h.lastPlan!!.changes.map { it.entity.entityType } shouldBe listOf(EntityType.GROUP)
    }

    @Test
    fun `merging an archived person into an active one, or unmerging an active alias from an archived person, resumes`() {
        val ledger = TestLedger().apply { me() }
        val old = ledger.person("Old", archived = true)
        val new = ledger.person("New")
        val pat = ledger.person("Pat", archived = true)
        val patsy = ledger.person("Patsy", mergedInto = pat)
        val viaOld = ledger.schedule(ME, eur(1_000), listOf(ME, old))
        val viaPatsy = ledger.schedule(ME, eur(1_000), listOf(ME, patsy))
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.merge(it, old, new, h.token(h.state.people.getValue(old))) }.ok()
        h.revisions.last().let { it.entityId to it.reason } shouldBe (viaOld.value to "Resumed: Old merged into New")

        h.run { PeopleOps.unmerge(it, patsy, h.token(h.state.people.getValue(patsy))) }.ok()
        h.revisions.last().let { it.entityId to it.reason } shouldBe (viaPatsy.value to "Resumed: Patsy unmerged from Pat")
        // Confirm-first: the resume is written, but the cut-off stays.
        h.state.schedules.getValue(viaPatsy).autoPostFrom shouldBe D0
    }

    @Test
    fun `editing a held schedule so it no longer references the archived person is its own resume`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val bea = ledger.person("Bea", archived = true)
        val flat = ledger.group("Flat", members = listOf(ME, x, bea))
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, bea), group = flat, autoPost = true)
        val h = OpsHarness(ledger.state)
        h.today = d("2026-06-01")
        val edit = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000), groupId = flat,
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0, autoPost = true,
        )

        h.run { RecurringOps.save(it, edit, ScheduleSaveMode.Update(id, h.token(h.state.schedules.getValue(id)))) }.ok()

        h.lastPlan!!.changes.map { it.entity.entityType to it.reason } shouldBe listOf(EntityType.RECURRING_EXPENSE to null)
        h.state.schedules.getValue(id).autoPostFrom shouldBe d("2026-06-01")
        Recurrence.holds(h.state.schedules.getValue(id), h.state) shouldBe emptyList()
    }

    @Test
    fun `the store finalizes every write it commits`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x), archived = true)
        val id = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat, autoPost = true)
        val store = LedgerStore(
            Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-test"),
            initial = LedgerContent(ledger.state),
        )

        store.write { ctx ->
            GroupOps.setGroupArchived(ctx, flat, false, EditToken(ctx.generation, ctx.state.groups.getValue(flat).meta.version))
        } shouldBe LedgerResult.Ok(Unit)

        val snapshot = store.loaded()
        snapshot.state.schedules.getValue(id).autoPostFrom shouldBe d("2026-06-01")
        snapshot.revisions.last().reason shouldBe "Resumed: Flat unarchived"
    }
}
