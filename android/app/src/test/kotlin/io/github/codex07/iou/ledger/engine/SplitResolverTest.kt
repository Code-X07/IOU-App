package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.MAX_MINOR
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigInteger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SplitResolverTest {

    private val a = PersonId("person-a")
    private val b = PersonId("person-b")
    private val c = PersonId("person-c")

    private fun shares(result: LedgerResult<List<ExpenseShare>>): Map<PersonId, Long> =
        (result as LedgerResult.Ok).value.associate { it.personId to it.shareMinor }

    @Test
    fun `equal split of 100,00 among three gives the extra cent to the lowest id`() {
        val inputs = listOf(SplitInput(c), SplitInput(a), SplitInput(b))
        val result = SplitResolver.resolve(SplitType.EQUAL, 10_000, 10_000, inputs)

        shares(result) shouldBe mapOf(a to 3334L, b to 3333L, c to 3333L)
        (result as LedgerResult.Ok).value.map { it.personId } shouldBe listOf(c, a, b)
        result.value.map { it.inputValue } shouldBe listOf(null, null, null)
    }

    @Test
    fun `percent uses basis points`() {
        val inputs = listOf(SplitInput(a, 5_000), SplitInput(b, 2_500), SplitInput(c, 2_500))
        shares(SplitResolver.resolve(SplitType.PERCENT, 1_001, 1_001, inputs)) shouldBe
            mapOf(a to 501L, b to 250L, c to 250L)
    }

    @Test
    fun `percent must sum to ten thousand basis points`() {
        val inputs = listOf(SplitInput(a, 5_000), SplitInput(b, 4_999))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, inputs) shouldBe
            LedgerResult.Err(LedgerError.PercentNotHundred(9_999))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, listOf(SplitInput(a, 10_001))) shouldBe
            LedgerResult.Err(LedgerError.WeightOutOfRange(a, 10_001))
    }

    @Test
    fun `shares split non-terminating fractions exactly`() {
        val inputs = listOf(SplitInput(a, 1), SplitInput(b, 1), SplitInput(c, 1))
        shares(SplitResolver.resolve(SplitType.SHARES, 100, 100, inputs)) shouldBe mapOf(a to 34L, b to 33L, c to 33L)
    }

    @Test
    fun `shares weights are bounded and may not all be zero`() {
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 1_000_001))) shouldBe
            LedgerResult.Err(LedgerError.WeightOutOfRange(a, 1_000_001))
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 0), SplitInput(b, 0))) shouldBe
            LedgerResult.Err(LedgerError.ZeroWeights)
        shares(SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, 0), SplitInput(b, 3)))) shouldBe
            mapOf(a to 0L, b to 100L)
    }

    @Test
    fun `exact inputs must sum to the paid total`() {
        SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 5_000), SplitInput(b, 4_000))) shouldBe
            LedgerResult.Err(LedgerError.ExactRemaining(1_000))
        SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 6_000), SplitInput(b, 5_000))) shouldBe
            LedgerResult.Err(LedgerError.ExactRemaining(-1_000))
        shares(SplitResolver.resolve(SplitType.EXACT, 10_000, 10_000, listOf(SplitInput(a, 2_500), SplitInput(b, 7_500)))) shouldBe
            mapOf(a to 2_500L, b to 7_500L)
        SplitResolver.resolve(SplitType.EXACT, 0, 0, listOf(SplitInput(a, 0), SplitInput(b, 0))) shouldBe
            LedgerResult.Err(LedgerError.ZeroWeights)
    }

    @Test
    fun `exact amounts in a converted expense become weights over the converted total`() {
        // 1 000.00 SEK split 500/250/250, converted to 87.65 EUR.
        val inputs = listOf(SplitInput(a, 50_000), SplitInput(b, 25_000), SplitInput(c, 25_000))
        val result = SplitResolver.resolve(SplitType.EXACT, 8_765, 100_000, inputs)

        shares(result) shouldBe mapOf(a to 4_383L, b to 2_191L, c to 2_191L)
        (result as LedgerResult.Ok).value.map { it.inputValue } shouldBe listOf(50_000L, 25_000L, 25_000L)
    }

    @Test
    fun `input errors come in a fixed order`() {
        SplitResolver.resolve(SplitType.EQUAL, 100, 100, emptyList()) shouldBe LedgerResult.Err(LedgerError.NoParticipants)
        SplitResolver.resolve(SplitType.EQUAL, 100, 100, listOf(SplitInput(a), SplitInput(a))) shouldBe
            LedgerResult.Err(LedgerError.DuplicateParticipant(a))
        SplitResolver.resolve(SplitType.SHARES, 100, 100, listOf(SplitInput(a, -1), SplitInput(b))) shouldBe
            LedgerResult.Err(LedgerError.NegativeInput(a))
        SplitResolver.resolve(SplitType.PERCENT, 100, 100, listOf(SplitInput(a, 10_000), SplitInput(b))) shouldBe
            LedgerResult.Err(LedgerError.MissingInput(b))
        SplitResolver.resolve(SplitType.EXACT, 100, 100, listOf(SplitInput(a, MAX_MINOR + 1))) shouldBe
            LedgerResult.Err(LedgerError.AmountTooLarge)
    }

    @Test
    fun `largest remainder always sums exactly and stays within one unit of the exact share`() = runTest {
        checkAll(
            Arb.long(0L..MAX_MINOR),
            Arb.list(Arb.long(0L..SplitResolver.MAX_WEIGHT), 1..12),
        ) { total, rawWeights ->
            val weights = if (rawWeights.all { it == 0L }) listOf(1L) + rawWeights.drop(1) else rawWeights
            val people = weights.indices.map { PersonId("person-%03d".format(it)) }
            val amounts = SplitResolver.largestRemainder(total, people.zip(weights))

            amounts.sum() shouldBe total
            val weightSum = BigInteger.valueOf(weights.sum())
            amounts.zip(weights).forEach { (amount, weight) ->
                amount shouldBeGreaterThanOrEqual 0L
                val exactNumerator = BigInteger.valueOf(total).multiply(BigInteger.valueOf(weight))
                val deviation = BigInteger.valueOf(amount).multiply(weightSum).subtract(exactNumerator).abs()
                (deviation < weightSum) shouldBe true
            }
        }
    }
}
