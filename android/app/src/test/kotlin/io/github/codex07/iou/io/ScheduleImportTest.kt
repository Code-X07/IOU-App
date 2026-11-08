package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Schedules through the import: one unit with their participants, `handled_through` forward only (spec §7.4, AC M2-35). */
class ScheduleImportTest {

    private fun d(text: String) = LocalDate.parse(text)

    private val rent = RecurringId("rent")
    private val key = ImportRowKey(EntityType.RECURRING_EXPENSE, "rent")

    /** Me and X; a monthly rent [total] from 1 January paid by me and shared by both, handled through [handled]. */
    private fun ledger(
        handled: LocalDate?,
        total: Long = 90_000,
        autoPost: Boolean = false,
        autoPostFrom: LocalDate = D0,
    ): LedgerState = TestLedger().run {
        me()
        val x = person("X")
        schedule(ME, eur(total), listOf(ME, x), id = "rent", handledThrough = handled, autoPost = autoPost, autoPostFrom = autoPostFrom)
        state
    }

    @Test
    fun `an older incoming handled date never rewinds the local one`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01")))

        val preview = bench.preview(ledger(d("2026-10-01")))
        bench.commit(preview)

        preview.rows shouldBe emptyList()
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-11-01")
    }

    @Test
    fun `a later incoming handled date moves the local one forward as a forced change, with nothing to choose`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01")))

        val preview = bench.preview(ledger(d("2026-12-01")))
        val row = preview.rows.single()
        bench.commit(preview)

        row.key shouldBe key
        row.forcedChanges shouldBe listOf(FieldChange("handledThrough", "2026-11-01", "2026-12-01"))
        row.changes shouldBe row.forcedChanges
        preview.counts.conflicts shouldBe 1
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-12-01")
        bench.store.loaded().revisions.last { it.entityId == "rent" }.reason shouldBe
            "Imported progress from export.zip (source v1): handled through 2026-12-01"
    }

    @Test
    fun `taking a changed template keeps the later handled date and makes an auto-post schedule wait from the import day`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01"), autoPost = true))
        val take = ImportChoices(takeIncoming = setOf(key))

        val preview = bench.preview(ledger(d("2026-10-01"), total = 95_000, autoPost = true, autoPostFrom = d("2026-10-15")))
        val row = preview.rows.single()
        bench.commit(preview, take)

        row.choice shouldBe ImportRowChoice.KeepOrTake
        row.forcedChanges shouldBe emptyList()
        row.changes shouldContain FieldChange("total", "900.00 EUR", "950.00 EUR")
        row.changes.none { it.field == "handledThrough" } shouldBe true
        val schedule = bench.state.schedules.getValue(rent)
        schedule.total shouldBe eur(95_000)
        schedule.handledThrough shouldBe d("2026-11-01")
        schedule.autoPostFrom shouldBe bench.today
    }

    @Test
    fun `a new schedule gets the import day as autoPostFrom, whatever the file says`() = runTest {
        val bench = ImportBench(TestLedger().apply { me(); person("X") }.state)

        bench.import(ledger(null, autoPostFrom = d("2025-06-01")))

        bench.state.schedules.getValue(rent).autoPostFrom shouldBe bench.today
    }

    @Test
    fun `two live expenses for the same occurrence are invalid rows`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            val id = schedule(ME, eur(90_000), listOf(ME, x), id = "rent")
            for (expense in listOf("e-a", "e-b")) {
                expense(ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), id = expense, recurring = id, recurringOn = d("2026-02-01"))
            }
            state
        }
        val bench = ImportBench(TestLedger().apply { me(); person("X") }.state)

        val invalid = bench.preview(source).invalidRows

        invalid.map { it.table } shouldBe listOf("expenses", "expenses")
        invalid.all { it.reason.startsWith("breaks rule 18") } shouldBe true
    }

    @Test
    fun `participants of a schedule from another install are mapped to the local me`() = runTest {
        val localMe = PersonId("local-me")
        val bench = ImportBench(
            LedgerState()
                .with(Person(localMe, "Me", isMe = true, meta = testMeta()))
                .with(Person(PersonId("person-x"), "X", meta = testMeta())),
        )

        val preview = bench.preview(ledger(null))
        bench.commit(preview)

        preview.identity shouldBe ImportIdentity.Mapped(ME)
        bench.state.schedules.getValue(rent).personIds shouldBe setOf(localMe, PersonId("person-x"))
    }
}
