package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.PayerSplit
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
     * Payer rows (spec §4.4), resolved like shares over the same totals: `EQUAL` divides the effect total
     * among the payers; `EXACT` takes each payer's amount in the paid currency, each greater than 0 and summing
     * to [originalTotalMinor], as the weights for the effect total. Errors name the payer side: `NoPayers`,
     * `DuplicatePayer`, `PaidRemaining`, `InvalidAmount` (an EXACT payer who paid nothing), and `NegativeInput`,
     * `MissingInput`, `AmountTooLarge` as for shares.
     */
    fun resolvePayers(
        split: PayerSplit,
        effectTotalMinor: Long,
        originalTotalMinor: Long,
        inputs: List<SplitInput>,
    ): LedgerResult<List<ExpensePayer>> = ledgerResult {
        ensure(inputs.isNotEmpty()) { LedgerError.NoPayers }
        inputs.groupBy { it.personId }.entries.firstOrNull { it.value.size > 1 }
            ?.let { fail(LedgerError.DuplicatePayer(it.key)) }
        if (split == PayerSplit.EXACT) {
            inputs.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
            inputs.firstOrNull { it.value == null }?.let { fail(LedgerError.MissingInput(it.personId)) }
            ensure(inputs.none { it.value == 0L }) { LedgerError.InvalidAmount }
        }
        val resolved = when (val result = resolve(split.splitType, effectTotalMinor, originalTotalMinor, inputs)) {
            is LedgerResult.Ok -> result.value
            is LedgerResult.Err -> {
                val error = result.error
                fail(if (error is LedgerError.ExactRemaining) LedgerError.PaidRemaining(error.diffMinor) else error)
            }
        }
        resolved.map { ExpensePayer(it.personId, it.shareMinor, it.inputValue) }
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
