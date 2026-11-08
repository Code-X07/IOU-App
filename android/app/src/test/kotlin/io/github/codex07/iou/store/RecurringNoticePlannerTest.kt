package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** What the notifier shows, re-shows, cancels and records (spec §5.7 Notifications, AC 34 logic). */
class RecurringNoticePlannerTest {

    private fun d(text: String) = LocalDate.parse(text)
    private val rent = RecurringId("rent")
    private val gym = RecurringId("gym")

    private fun due(schedule: RecurringId, date: String, autoPost: Boolean = false, waits: Boolean = false, error: LedgerError? = null) =
        DueOccurrence(schedule, if (schedule == rent) "Rent" else "Gym", d(date), eur(90_000), null, autoPost, waits, error)

    @Test
    fun `a schedule is notified once per oldest due occurrence, and cancelled when nothing is due`() {
        val first = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), emptyList(), false, NotifiedRecord())
        first.show.map { it.schedule } shouldBe listOf(rent)
        first.show.single().text shouldContain "2 due"
        first.show.single().text shouldContain "2026-03-01"

        val again = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), emptyList(), false, first.notified)
        again.show shouldBe emptyList()

        // An edit (or an import, or a restore) that changes what is shown redraws it, silently.
        val edited = RecurringNoticePlanner.plan(
            listOf(due(rent, "2026-03-01").copy(amount = eur(95_000)), due(rent, "2026-04-01")), emptyList(), false, again.notified,
        )
        edited.show.single().text shouldContain "950.00 EUR"
        edited.show.single().silent shouldBe true
        val oneMore = RecurringNoticePlanner.plan(
            listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01"), due(rent, "2026-05-01")), emptyList(), false, again.notified,
        )
        oneMore.show.single().text shouldContain "3 due"
        oneMore.show.single().silent shouldBe true

        val moved = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01")), emptyList(), false, again.notified)
        moved.show.map { it.schedule } shouldBe listOf(rent)

        val failed = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01", true, error = LedgerError.RateRequired(SEK, EUR))), emptyList(), false, moved.notified)
        failed.show.single().text shouldContain "exchange rate"

        val done = RecurringNoticePlanner.plan(emptyList(), emptyList(), false, failed.notified)
        done.cancel shouldBe setOf(rent)
        done.notified shouldBe NotifiedRecord()
    }

    @Test
    fun `a backlog says why auto-post waits, and posted occurrences get one summary`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)))
        val plan = RecurringNoticePlanner.plan(listOf(due(rent, "2026-02-01", autoPost = true, waits = true)), posted, false, NotifiedRecord())

        plan.show.single().text shouldContain "Older dates need confirmation"
        plan.posted!!.text shouldContain "Gym 40.00 EUR, 2026-03-01"
        RecurringNoticePlanner.plan(emptyList(), emptyList(), false, NotifiedRecord()).posted shouldBe null
    }

    @Test
    fun `while app lock is on, texts name no description or amount`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)), PostedOccurrence(gym, "Gym", d("2026-03-08"), eur(4_000)))
        val plan = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), posted, true, NotifiedRecord())

        val notice = plan.show.single()
        "${notice.title} ${notice.text}" shouldNotContain "Rent"
        "${notice.title} ${notice.text}" shouldNotContain "900.00"
        notice.text shouldBe "2 recurring expenses are due"
        plan.posted!!.text shouldBe "2 recurring expenses were posted"
    }

    @Test
    fun `switching app lock on redraws shown notices silently and redacted, and takes a detailed posted summary away`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)))
        val open = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), posted, false, NotifiedRecord())
        open.notified.detailedPosted shouldBe true

        val locked = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), true, open.notified)
        val notice = locked.show.single()
        notice.silent shouldBe true
        "${notice.title} ${notice.text}" shouldNotContain "Rent"
        locked.cancelPosted shouldBe true
        locked.notified.detailedPosted shouldBe false

        val again = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), true, locked.notified)
        again.show shouldBe emptyList()
        again.cancelPosted shouldBe false

        val newDate = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01")), emptyList(), true, again.notified)
        newDate.show.single().silent shouldBe false
    }

    @Test
    fun `the group summary is drawn only when the group changes`() {
        val both = listOf(due(rent, "2026-03-01"), due(gym, "2026-03-01"))
        val first = RecurringNoticePlanner.plan(both, emptyList(), false, NotifiedRecord())
        first.summary shouldBe 2

        val unchanged = RecurringNoticePlanner.plan(both, emptyList(), false, first.notified)
        unchanged.show shouldBe emptyList()
        unchanged.summary shouldBe null

        val one = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), false, unchanged.notified)
        one.cancel shouldBe setOf(gym)
        one.summary shouldBe 0
        // Removing the summary removes rent's notification too, so rent is drawn again, silently, and only once.
        one.show.map { it.schedule to it.silent } shouldBe listOf(rent to true)
        RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), false, one.notified).show shouldBe emptyList()
    }
}
