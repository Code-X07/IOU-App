package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.github.codex07.iou.testutil.testMeta
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StateInvariantsTest {

    private fun errors(state: LedgerState) = LedgerValidator.validate(state, state, WriteOp.Import).map { it.error }
    private fun rules(state: LedgerState) = LedgerValidator.validate(state, state, WriteOp.Import).map { it.rule }

    private fun base(): Pair<TestLedger, PersonId> {
        val ledger = TestLedger()
        ledger.me()
        return ledger to ledger.person("X")
    }

    @Test
    fun `a sound ledger has no violations`() {
        val (ledger, x) = base()
        val y = ledger.person("Y")
        val group = ledger.group("Trip", members = listOf(ME, x, y))
        ledger.transfer(ME, x, eur(100))
        ledger.expense(x, eur(300), mapOf(x to 100L, y to 100L, ME to 100L), group = group)
        errors(ledger.state).shouldBeEmpty()
    }

    @Test
    fun `rule 1 - outside groups only me and one other person`() {
        val (ledger, x) = base()
        val y = ledger.person("Y")
        ledger.transfer(x, y, eur(100))
        errors(ledger.state) shouldBe listOf(LedgerError.NeedsGroup(setOf(x, y)))

        val (ledger2, x2) = base()
        val y2 = ledger2.person("Y")
        ledger2.expense(ME, eur(300), mapOf(ME to 100L, x2 to 100L, y2 to 100L))
        errors(ledger2.state) shouldBe listOf(LedgerError.NeedsGroup(setOf(ME, x2, y2)))

        val (ledger3, x3) = base()
        ledger3.transfer(x3, ledger3.person("Y"), eur(100), deleted = true)
        errors(ledger3.state).shouldBeEmpty()
    }

    @Test
    fun `rule 2 - me is an active member of every group`() {
        val (ledger, x) = base()
        ledger.group("Trip", members = listOf(x))
        errors(ledger.state) shouldBe listOf(LedgerError.CannotModifyMe)
        rules(ledger.state) shouldBe listOf(2)
    }

    @Test
    fun `rule 3 - group entries reference members only`() {
        val (ledger, x) = base()
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.transfer(ME, x, eur(100), group = group)
        errors(ledger.state) shouldBe listOf(LedgerError.NotGroupMember(group, setOf(x)))
    }

    @Test
    fun `rule 4 - removed members have a zero net`() {
        val (ledger, a) = base()
        val b = ledger.person("B")
        val c = ledger.person("C")
        val group = ledger.group("Trip", members = listOf(ME, b, c))
        ledger.member(group, a, removed = true)
        // A owes B 10 and C owes A 10 through the flows: A's net is 0.
        ledger.expense(b, eur(1_000), mapOf(a to 1_000L), group = group)
        ledger.expense(a, eur(1_000), mapOf(c to 1_000L), group = group)
        errors(ledger.state).shouldBeEmpty()

        ledger.expense(b, eur(500), mapOf(a to 500L), group = group)
        errors(ledger.state) shouldBe listOf(LedgerError.MemberHasBalance(group, a, mapOf(EUR to -500L)))
    }

    @Test
    fun `rule 8 - convert groups keep everything in the group currency`() {
        val (ledger, x) = base()
        val group = ledger.group("Trip", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val t = ledger.transfer(ME, x, sek(1_000), group = group)
        errors(ledger.state) shouldBe listOf(LedgerError.FxSnapshotMismatch(t))
    }

    @Test
    fun `rule 9 - me is never deleted or merged`() {
        val (ledger, x) = base()
        val me = ledger.state.me
        val deleted = ledger.state.with(me.copy(meta = testMeta(deleted = true)))
        errors(deleted) shouldContain LedgerError.CannotModifyMe
        val merged = ledger.state.with(me.copy(mergedIntoId = x))
        errors(merged) shouldContain LedgerError.CannotModifyMe
    }

    @Test
    fun `rule 10 - live rows never reference deleted people or groups`() {
        val (ledger, _) = base()
        val gone = ledger.person("Gone", deleted = true)
        val t = ledger.transfer(ME, gone, eur(100))
        val h = ledger.handle(gone)
        errors(ledger.state) shouldBe listOf(
            LedgerError.DeletedReference(setOf(h.value, gone.value)),
            LedgerError.DeletedReference(setOf(t.value, gone.value)),
        )

        val (ledger2, x2) = base()
        val group = ledger2.group("Old", members = emptyList(), deleted = true)
        val m = ledger2.member(group, ME)
        ledger2.member(group, x2, deleted = true)
        errors(ledger2.state) shouldBe listOf(LedgerError.DeletedReference(setOf(m.value, group.value)))
    }

    @Test
    fun `rule 11 - live aliases resolve to a live person`() {
        val (ledger, _) = base()
        val target = ledger.person("Target", deleted = true)
        val alias = ledger.person("Alias", mergedInto = target)
        errors(ledger.state) shouldBe listOf(LedgerError.DeletedReference(setOf(alias.value, target.value)))
    }

    @Test
    fun `rule 14 - snapshots exist exactly for converted entries, for the right pair`() {
        val (ledger, x) = base()
        val sekEur = ledger.fxSnapshot(SEK, EUR, "0.09")
        val t1 = ledger.transfer(ME, x, eur(100), fx = sekEur)
        errors(ledger.state) shouldContain LedgerError.FxSnapshotMismatch(t1)

        val (ledger2, x2) = base()
        val t2 = ledger2.transfer(ME, x2, sek(100_000), effect = eur(9_000))
        errors(ledger2.state) shouldContain LedgerError.FxSnapshotMismatch(t2)
    }

    @Test
    fun `rule 15 - structure problems are reported alone`() {
        val (ledger, _) = base()
        ledger.person("A", mergedInto = PersonId("person-b"), deleted = true)
        ledger.person("B", mergedInto = PersonId("person-a"), deleted = true)
        rules(ledger.state) shouldBe listOf(15, 15)
        errors(ledger.state).all { it is LedgerError.CorruptData } shouldBe true

        val (ledger2, x) = base()
        ledger2.transfer(ME, x, eur(100), group = GroupId("missing"))
        errors(ledger2.state) shouldBe listOf(LedgerError.CorruptData("transfer-1 references missing group missing"))

        val twoMes = ledger2.state.with(Person(PersonId("person-1-other-me"), "Me 2", isMe = true, meta = testMeta()))
        errors(twoMes) shouldContain LedgerError.CorruptData("expected exactly one me, found 2")
    }

    @Test
    fun `rule 16 - bank-actual snapshots fix both amounts`() {
        val (ledger, x) = base()
        val bank = ledger.fxSnapshot(SEK, EUR, "0.08765", FxSource.BANK_ACTUAL, paidMinor = 100_000, chargedMinor = 8_765)
        ledger.transfer(ME, x, sek(100_000), effect = eur(8_765), fx = bank)
        errors(ledger.state).shouldBeEmpty()

        val t = ledger.transfer(ME, x, sek(200_000), effect = eur(8_765), fx = bank)
        errors(ledger.state) shouldBe listOf(LedgerError.FxSnapshotMismatch(t))
    }

    @Test
    fun `rule 17 - effects equal their rate conversion`() {
        val (ledger, x) = base()
        val rate = ledger.fxSnapshot(SEK, EUR, "0.09")
        ledger.transfer(ME, x, sek(100_000), effect = eur(9_000), fx = rate)
        errors(ledger.state).shouldBeEmpty()

        val wrong = ledger.transfer(ME, x, sek(100_000), effect = eur(10_000), fx = rate)
        val unconverted = ledger.transfer(ME, x, eur(100), effect = eur(101))
        errors(ledger.state) shouldBe listOf(
            LedgerError.FxSnapshotMismatch(wrong),
            LedgerError.FxSnapshotMismatch(unconverted),
        )
    }

    @Test
    fun `overflowing balances are rejected as too large`() {
        val (ledger, x) = base()
        ledger.transfer(ME, x, Money(Long.MAX_VALUE, EUR), effect = Money(Long.MAX_VALUE, EUR))
        ledger.transfer(ME, x, eur(1))
        errors(ledger.state) shouldContain LedgerError.AmountTooLarge
    }
}
