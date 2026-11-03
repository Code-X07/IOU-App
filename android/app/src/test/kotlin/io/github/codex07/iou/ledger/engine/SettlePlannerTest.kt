package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SettlePlannerTest {

    private val a = PersonId("person-a")
    private val b = PersonId("person-b")

    @Test
    fun `spec example - B pays A and I pay A`() {
        val nets = mapOf(a to mapOf(EUR to 4_000L), b to mapOf(EUR to -2_000L), ME to mapOf(EUR to -2_000L))

        SettlePlanner.planGroup(nets) shouldContainExactlyInAnyOrder listOf(
            PlannedTransfer(b, a, eur(2_000)),
            PlannedTransfer(ME, a, eur(2_000)),
        )
    }

    @Test
    fun `ties go to the lowest person id`() {
        val nets = mapOf(a to mapOf(EUR to 4_000L), b to mapOf(EUR to -2_000L), ME to mapOf(EUR to -2_000L))
        // "person-0-me" sorts before "person-b".
        SettlePlanner.planGroup(nets).first() shouldBe PlannedTransfer(ME, a, eur(2_000))
    }

    @Test
    fun `each currency is planned on its own`() {
        val nets = mapOf(a to mapOf(EUR to 100L, SEK to -500L), b to mapOf(EUR to -100L, SEK to 500L))
        SettlePlanner.planGroup(nets) shouldBe listOf(PlannedTransfer(b, a, eur(100)), PlannedTransfer(a, b, sek(500)))
    }

    @Test
    fun `amounts above MAX_MINOR are split into several transfers`() {
        val nets = mapOf(a to mapOf(EUR to 2 * MAX_MINOR + 5), b to mapOf(EUR to -(2 * MAX_MINOR + 5)))
        SettlePlanner.planGroup(nets) shouldBe listOf(
            PlannedTransfer(b, a, eur(MAX_MINOR)),
            PlannedTransfer(b, a, eur(MAX_MINOR)),
            PlannedTransfer(b, a, eur(5)),
        )
    }

    @Test
    fun `person settle-up reduces each currency's debt`() {
        SettlePlanner.planPerson(ME, a, mapOf(EUR to 3_000L, SEK to -500L)) shouldBe listOf(
            PlannedTransfer(a, ME, eur(3_000)),
            PlannedTransfer(ME, a, sek(500)),
        )
        SettlePlanner.planPerson(ME, a, emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `random plans settle every net with valid amounts and at most n-1 pairs`() = runTest {
        checkAll(Arb.list(Arb.long(-5 * MAX_MINOR..5 * MAX_MINOR), 1..8)) { values ->
            val people = (values.indices + values.size).map { PersonId("person-%02d".format(it)) }
            val amounts = values + -values.sum()
            val nets = people.zip(amounts).associate { (p, v) -> p to mapOf(EUR to v) }

            val plan = SettlePlanner.planGroup(nets)

            val after = nets.mapValues { (_, m) -> m.getValue(EUR) }.toMutableMap()
            plan.forEach { t ->
                (t.amount.amountMinor in 1..MAX_MINOR) shouldBe true
                t.amount.currency shouldBe EUR
                after[t.from] = after.getValue(t.from) + t.amount.amountMinor
                after[t.to] = after.getValue(t.to) - t.amount.amountMinor
            }
            after.values.all { it == 0L } shouldBe true

            val nonZero = amounts.count { it != 0L }
            plan.map { it.from to it.to }.toSet().size shouldBeLessThanOrEqual maxOf(nonZero - 1, 0)
            if (amounts.all { kotlin.math.abs(it) <= MAX_MINOR }) {
                plan.size shouldBeLessThanOrEqual maxOf(nonZero - 1, 0)
            }
        }
    }
}
