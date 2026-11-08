package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** Rules 0, 1, 3, 10, 15 and 18 for schedules and the expenses posted from them (spec §3.4). */
class ScheduleRulesTest {

    private fun d(text: String) = LocalDate.parse(text)

    /** Every violation of [state], as import validation finds them. */
    private fun violations(state: LedgerState) = LedgerValidator.validate(state, state, WriteOp.Import)

    @Test
    fun `a schedule's fields and template rows are checked as stored`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(10_000), listOf(ME, x))
        fun errors(change: (RecurringExpense) -> RecurringExpense) =
            FieldRules.check(listOf(change(ledger.state.schedules.getValue(id)))).map { it.error }

        errors { it } shouldBe emptyList()
        errors { it.copy(interval = 0) } shouldBe listOf(LedgerError.InvalidField("interval"))
        errors { it.copy(interval = 100) } shouldBe listOf(LedgerError.InvalidField("interval"))
        errors { it.copy(endOn = it.startOn.minusDays(1)) } shouldBe listOf(LedgerError.InvalidField("endOn"))
        errors { it.copy(description = " ") } shouldBe listOf(LedgerError.InvalidField("description"))
        errors { it.copy(total = eur(0)) } shouldBe listOf(LedgerError.InvalidAmount)
        errors { it.copy(participants = it.payerRows) } shouldBe listOf(LedgerError.NoParticipants)
        errors { it.copy(participants = it.shareRows) } shouldBe listOf(LedgerError.NoPayers)
        errors { it.copy(splitType = SplitType.EXACT) } shouldBe listOf(LedgerError.MissingInput(ME))
        errors { it.copy(participants = it.participants.map { row -> row.copy(inputValue = 1) }) }
            .map { it::class } shouldBe listOf(LedgerError.CorruptData::class, LedgerError.CorruptData::class)
    }

    @Test
    fun `an expense names both its schedule and its occurrence, or neither`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val rent = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        val id = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        val expense = ledger.state.expenses.getValue(id)

        FieldRules.check(listOf(expense)) shouldBe emptyList()
        FieldRules.check(listOf(expense.copy(recurringOn = null))).single().error::class shouldBe LedgerError.CorruptData::class
        FieldRules.check(listOf(expense.copy(recurringId = null))).single().error::class shouldBe LedgerError.CorruptData::class
    }

    @Test
    fun `schedules follow the people and membership rules`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val z = ledger.person("Z", deleted = true)
        val flat = ledger.group("Flat", members = listOf(ME, x))
        ledger.schedule(ME, eur(1_000), listOf(ME, x, y))
        ledger.schedule(ME, eur(1_000), listOf(ME, y), group = flat)
        val withZ = ledger.schedule(ME, eur(1_000), listOf(ME, z))
        ledger.schedule(ME, eur(1_000), listOf(ME, x, y), deleted = true)

        val found = violations(ledger.state).map { it.rule to it.error }

        found shouldContain (1 to LedgerError.NeedsGroup(setOf(ME, x, y)))
        found shouldContain (3 to LedgerError.NotGroupMember(flat, setOf(y)))
        found shouldContain (10 to LedgerError.DeletedReference(setOf(withZ.value, z.value)))
        found.count { it.first == 1 } shouldBe 1
    }

    @Test
    fun `references to schedules must exist, and an occurrence has at most one live expense`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val rent = ledger.schedule(ME, eur(1_000), listOf(ME, x))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-02-01"))
        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-02-01"), deleted = true)

        violations(ledger.state) shouldBe emptyList()

        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = rent, recurringOn = d("2026-01-01"))
        violations(ledger.state).map { it.rule } shouldBe listOf(18)

        ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), recurring = RecurringId("gone"), recurringOn = d("2026-01-01"))
        violations(ledger.state).map { it.rule } shouldBe listOf(15)
    }

    @Test
    fun `import validation checks live schedules' fields`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.schedule(ME, eur(1_000), listOf(ME, x), interval = 0)

        violations(ledger.state).map { it.rule to it.error } shouldBe listOf(0 to LedgerError.InvalidField("interval"))
    }

    @Test
    fun `a person or group that any schedule references can't be deleted`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val flat = ledger.group("Flat", members = listOf(ME, y))
        ledger.schedule(ME, eur(1_000), listOf(ME, x))
        ledger.schedule(ME, eur(1_000), listOf(ME, y), group = flat, deleted = true)
        val h = OpsHarness(ledger.state)

        h.run { PeopleOps.deletePerson(it, x, h.token(h.state.people.getValue(x))) } shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(x.value))
        h.run { GroupOps.deleteGroup(it, flat, h.token(h.state.groups.getValue(flat))) } shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(flat.value))
    }
}
