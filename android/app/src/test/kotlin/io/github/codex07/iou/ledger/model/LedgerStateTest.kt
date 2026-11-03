package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerStateTest {

    @Test
    fun `me is the single isMe person`() {
        val ledger = TestLedger().apply { me(); person("Alex") }
        ledger.state.me.id shouldBe ME
        ledger.state.meOrNull?.id shouldBe ME
    }

    @Test
    fun `a state without me has no me`() {
        val state = LedgerState()
        state.meOrNull shouldBe null
        shouldThrow<NoSuchElementException> { state.me }
    }

    @Test
    fun `entries contains transfers and expenses, and lookups find each table`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val transfer = ledger.transfer(ME, alex, eur(5000))
        val expense = ledger.expense(ME, eur(1000), mapOf(ME to 500L, alex to 500L))
        val state = ledger.state

        state.entries.map { it.id } shouldContainExactlyInAnyOrder listOf(transfer, expense)
        state.entry(transfer) shouldBe state.transfers[transfer]
        state.entry(expense) shouldBe state.expenses[expense]
        state.entity(EntityType.PERSON, alex.value) shouldBe state.people[alex]
        state.entity(EntityType.EXPENSE, expense.value) shouldBe state.expenses[expense]
    }

    @Test
    fun `applying changes replaces rows by id and adds snapshots`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val renamed = ledger.state.people.getValue(alex).copy(displayName = "Alex S.", meta = testMeta(version = 2))

        val snapshot = FxSnapshot(FxSnapshotId("fx-1"), EUR, SEK, "11.2", D0, FxSource.USER_ENTERED, fetchedAt = T0)

        val after = ledger.state.applying(listOf(Change(renamed, RevisionAction.UPDATED)), listOf(snapshot))

        after.people.getValue(alex).displayName shouldBe "Alex S."
        after.fxSnapshots.getValue(snapshot.id) shouldBe snapshot
        after.people.size shouldBe 2
        ledger.state.people.getValue(alex).displayName shouldBe "Alex"
    }

    @Test
    fun `entry person ids include payer and every share`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val bob = ledger.person("Bob")
        val expense = ledger.expense(alex, eur(900), mapOf(ME to 300L, bob to 600L))

        ledger.state.entry(expense)!!.personIds shouldBe setOf(alex, ME, bob)
    }
}
