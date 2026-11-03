package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.MAX_MINOR
import java.math.BigInteger

/** Exact split resolution (spec §4.4). */
object SplitResolver {

    const val FULL_PERCENT_BP = 10_000L
    const val MAX_WEIGHT = 1_000_000L

    fun resolve(
        type: SplitType,
        effectTotalMinor: Long,
        originalTotalMinor: Long,
        inputs: List<SplitInput>,
    ): LedgerResult<List<ExpenseShare>> = ledgerResult {
        ensure(inputs.isNotEmpty()) { LedgerError.NoParticipants }
        inputs.groupBy { it.personId }.entries.firstOrNull { it.value.size > 1 }
            ?.let { fail(LedgerError.DuplicateParticipant(it.key)) }
        inputs.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
        if (type != SplitType.EQUAL) {
            inputs.firstOrNull { it.value == null }?.let { fail(LedgerError.MissingInput(it.personId)) }
        }

        val weights: List<Long> = when (type) {
            SplitType.EQUAL -> inputs.map { 1L }
            SplitType.PERCENT -> {
                inputs.firstOrNull { it.value!! > FULL_PERCENT_BP }
                    ?.let { fail(LedgerError.WeightOutOfRange(it.personId, it.value!!)) }
                val sum = inputs.sumOf { it.value!! }
                ensure(sum == FULL_PERCENT_BP) { LedgerError.PercentNotHundred(sum) }
                inputs.map { it.value!! }
            }
            SplitType.SHARES -> {
                inputs.firstOrNull { it.value!! > MAX_WEIGHT }
                    ?.let { fail(LedgerError.WeightOutOfRange(it.personId, it.value!!)) }
                ensure(inputs.sumOf { it.value!! } > 0L) { LedgerError.ZeroWeights }
                inputs.map { it.value!! }
            }
            SplitType.EXACT -> {
                ensure(inputs.all { it.value!! <= MAX_MINOR }) { LedgerError.AmountTooLarge }
                val sum = inputs.sumOf { it.value!! }
                ensure(sum == originalTotalMinor) { LedgerError.ExactRemaining(originalTotalMinor - sum) }
                ensure(sum > 0L) { LedgerError.ZeroWeights }
                inputs.map { it.value!! }
            }
        }

        val amounts = largestRemainder(effectTotalMinor, inputs.map { it.personId }.zip(weights))
        inputs.mapIndexed { index, input ->
            ExpenseShare(input.personId, amounts[index], if (type == SplitType.EQUAL) null else input.value)
        }
    }

    /**
     * Distributes [total] in proportion to the weights: floors first, then +1 to the largest
     * remainders, ties to the lowest person ID (string order). Exact integer arithmetic only.
     * Results are in input order.
     */
    fun largestRemainder(total: Long, weights: List<Pair<PersonId, Long>>): List<Long> {
        val totalBig = BigInteger.valueOf(total)
        val weightSum = weights.fold(BigInteger.ZERO) { acc, (_, weight) -> acc + BigInteger.valueOf(weight) }
        require(weights.all { it.second >= 0L }) { "Weights must not be negative" }
        require(weightSum.signum() > 0) { "Weights must not all be zero" }

        val parts = weights.map { (_, weight) -> totalBig.multiply(BigInteger.valueOf(weight)).divideAndRemainder(weightSum) }
        // Each floor is at most |total|, so it fits a Long. Not longValueExact(): BigInteger's needs API 31 (minSdk is 30).
        val result = parts.map { it[0].toLong() }.toMutableList()
        var leftover = total - result.sum()

        val byRemainder = weights.indices.sortedWith(
            compareByDescending<Int> { parts[it][1] }.thenBy { weights[it].first.value },
        )
        for (index in byRemainder) {
            if (leftover == 0L) break
            result[index] += 1
            leftover--
        }
        return result
    }
}
