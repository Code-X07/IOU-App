package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LedgerEnginePropertyTest {

    private val moves = Arb.list(Arb.triple(Arb.int(0..4), Arb.int(0..4), Arb.long(1L..MAX_MINOR)), 1..30)

    private fun groupLedger(moves: List<Triple<Int, Int, Long>>): Pair<TestLedger, io.github.codex07.iou.ledger.model.GroupId> {
        val ledger = TestLedger()
        ledger.me()
        val people = listOf(ME) + (1..4).map { ledger.person("P$it") }
        val group = ledger.group("G", members = people)
        moves.forEach { (a, b, amount) ->
            if (a != b) {
                ledger.transfer(people[a], people[b], eur(amount), group = group)
            } else {
                val other = people[(a + 1) % people.size]
                ledger.expense(people[a], eur(amount), mapOf(people[a] to amount / 2, other to amount - amount / 2), group = group)
            }
        }
        return ledger to group
    }

    @Test
    fun `group nets sum to zero`() = runTest {
        checkAll(moves) { generated ->
            val (ledger, group) = groupLedger(generated)
            val nets = LedgerEngine.compute(ledger.state).groupNets[group].orEmpty()
            nets.values.sumOf { it[EUR] ?: 0L } shouldBe 0L
        }
    }

    @Test
    fun `non-group nets are antisymmetric`() = runTest {
        checkAll(Arb.list(Arb.triple(Arb.int(0..1), Arb.long(1L..MAX_MINOR), Arb.int(0..1)), 1..30)) { generated ->
            val ledger = TestLedger()
            ledger.me()
            val x = ledger.person("X")
            generated.forEach { (direction, amount, kind) ->
                val (from, to) = if (direction == 0) ME to x else x to ME
                if (kind == 0) ledger.transfer(from, to, eur(amount))
                else ledger.expense(from, eur(amount), mapOf(to to amount))
            }
            val effects = LedgerEngine.effects(ledger.state)
            val forward = LedgerEngine.nonGroupNet(effects, ME, x)
            val backward = LedgerEngine.nonGroupNet(effects, x, ME)
            forward shouldBe backward.mapValues { -it.value }
            LedgerEngine.compute(ledger.state).net(x) shouldBe forward
        }
    }
}
