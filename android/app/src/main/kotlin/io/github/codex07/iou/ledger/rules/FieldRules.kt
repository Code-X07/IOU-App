package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money
import java.math.BigInteger

/** Rule 0: field validity, and normalization of payment handle values (spec §3.2). */
object FieldRules {

    private val ibanShape = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")
    private val revtagShape = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val e164Shape = Regex("^\\+[1-9][0-9]{7,14}$")
    private val ninetySeven = BigInteger.valueOf(97)

    fun normalizeHandleValue(type: HandleType, raw: String): LedgerResult<String> {
        val normalized = when (type) {
            HandleType.IBAN -> raw.filterNot { it.isWhitespace() }.uppercase()
            HandleType.REVTAG -> raw.trim().removePrefix("@")
            HandleType.PHONE -> raw.filterNot { it.isWhitespace() || it == '-' }
            HandleType.OTHER -> raw.trim()
        }
        return when {
            type == HandleType.IBAN && !isValidIban(normalized) -> LedgerResult.Err(LedgerError.InvalidIban(raw))
            !hasValidShape(type, normalized) -> LedgerResult.Err(LedgerError.InvalidField("value"))
            else -> LedgerResult.Ok(normalized)
        }
    }

    /** Shape plus ISO 13616 mod-97 check of an already normalized IBAN. */
    fun isValidIban(normalized: String): Boolean {
        if (!ibanShape.matches(normalized)) return false
        val rearranged = normalized.substring(4) + normalized.substring(0, 4)
        val digits = rearranged.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
        return BigInteger(digits).mod(ninetySeven) == BigInteger.ONE
    }

    fun check(entities: List<LedgerEntity>): List<RuleViolation> = entities.flatMap { entity ->
        when (entity) {
            is Person -> listOfNotNull(blank(entity.displayName, "displayName"))
            is PaymentHandle -> listOfNotNull(blank(entity.label, "label"), handleValue(entity))
            is Group -> listOfNotNull(blank(entity.name, "name"))
            is GroupMember -> emptyList()
            is RecurringExpense -> listOfNotNull(
                blank(entity.description, "description"),
                amount(entity.total),
                LedgerError.InvalidField("interval").takeIf { entity.interval !in 1..99 },
                entity.endOn?.takeIf { it.isBefore(entity.startOn) }?.let { LedgerError.InvalidField("endOn") },
            ) + template(entity)
            is Transfer -> listOfNotNull(amount(entity.amount), amount(entity.effectAmount))
            is Expense -> listOfNotNull(
                blank(entity.description, "description"),
                amount(entity.total),
                amount(entity.effectTotal),
                recurringLink(entity),
            ) + shares(entity) + payers(entity)
        }.map { RuleViolation(0, it) }
    }

    private fun hasValidShape(type: HandleType, value: String): Boolean = when (type) {
        HandleType.IBAN -> true
        HandleType.REVTAG -> revtagShape.matches(value)
        HandleType.PHONE -> e164Shape.matches(value)
        HandleType.OTHER -> value.isNotBlank()
    }

    private fun blank(value: String, field: String): LedgerError? =
        if (value.isBlank()) LedgerError.InvalidField(field) else null

    private fun handleValue(handle: PaymentHandle): LedgerError? = when (val result = normalizeHandleValue(handle.type, handle.value)) {
        is LedgerResult.Err -> result.error
        is LedgerResult.Ok -> if (result.value != handle.value) {
            if (handle.type == HandleType.IBAN) LedgerError.InvalidIban(handle.value) else LedgerError.InvalidField("value")
        } else {
            null
        }
    }

    private fun amount(money: Money): LedgerError? = when {
        money.amountMinor <= 0 -> LedgerError.InvalidAmount
        money.amountMinor > MAX_MINOR -> LedgerError.AmountTooLarge
        else -> null
    }

    private fun shares(expense: Expense): List<LedgerError> {
        if (expense.shares.isEmpty()) return listOf(LedgerError.NoParticipants)
        expense.shares.groupBy { it.personId }.entries.firstOrNull { it.value.size > 1 }
            ?.let { return listOf(LedgerError.DuplicateParticipant(it.key)) }
        expense.shares.firstOrNull { it.shareMinor < 0 }?.let { return listOf(LedgerError.NegativeInput(it.personId)) }
        var sum = 0L
        for (share in expense.shares) {
            // Shares are non-negative here; stopping above MAX_MINOR keeps a corrupt import from overflowing.
            if (share.shareMinor > MAX_MINOR - sum) {
                return listOf(LedgerError.CorruptData("shares of ${expense.id} sum to more than $MAX_MINOR"))
            }
            sum += share.shareMinor
        }
        return if (sum == expense.effectTotal.amountMinor) {
            listOfNotNull(inputs(expense))
        } else {
            listOf(LedgerError.CorruptData("shares of ${expense.id} sum to $sum, not ${expense.effectTotal.amountMinor}"))
        }
    }

    /**
     * Payer rows as stored (spec §3.2, §4.4): at least one, distinct people, non-negative, summing to the effect
     * total, with inputs that suit `payerSplit` (none for EQUAL; for EXACT, amounts > 0 summing to the paid total).
     * Like shares, the stored amounts are never recomputed here.
     */
    private fun payers(expense: Expense): List<LedgerError> {
        if (expense.payers.isEmpty()) return listOf(LedgerError.NoPayers)
        expense.payers.groupBy { it.personId }.entries.firstOrNull { it.value.size > 1 }
            ?.let { return listOf(LedgerError.DuplicatePayer(it.key)) }
        expense.payers.firstOrNull { it.paidMinor < 0 }?.let { return listOf(LedgerError.NegativeInput(it.personId)) }
        var sum = 0L
        for (payer in expense.payers) {
            if (payer.paidMinor > MAX_MINOR - sum) {
                return listOf(LedgerError.CorruptData("payers of ${expense.id} sum to more than $MAX_MINOR"))
            }
            sum += payer.paidMinor
        }
        if (sum != expense.effectTotal.amountMinor) {
            return listOf(LedgerError.CorruptData("payers of ${expense.id} sum to $sum, not ${expense.effectTotal.amountMinor}"))
        }
        if (expense.payerSplit == PayerSplit.EQUAL) {
            return listOfNotNull(
                expense.payers.firstOrNull { it.inputValue != null }
                    ?.let { LedgerError.CorruptData("EQUAL payers of ${expense.id} carry an input") },
            )
        }
        val inputs = expense.payers.map { SplitInput(it.personId, it.inputValue) }
        val resolved = SplitResolver.resolvePayers(expense.payerSplit, expense.effectTotal.amountMinor, expense.total.amountMinor, inputs)
        return listOfNotNull((resolved as? LedgerResult.Err)?.error)
    }

    /** Spec §3.4 rule 0: an expense names both its schedule and its occurrence date, or neither. */
    private fun recurringLink(expense: Expense): LedgerError? =
        if ((expense.recurringId == null) != (expense.recurringOn == null)) {
            LedgerError.CorruptData("${expense.id} names only one of recurringId and recurringOn")
        } else {
            null
        }

    /**
     * A schedule's template rows as stored (spec §3.4 rule 0): payers and participants resolve over the template
     * total, and EQUAL rows carry no input. Without a valid total there is nothing to resolve over; the amount
     * error is reported instead.
     */
    private fun template(schedule: RecurringExpense): List<LedgerError> {
        val total = schedule.total.amountMinor
        if (total <= 0 || total > MAX_MINOR) return emptyList()
        val payers = schedule.payerRows.map { SplitInput(it.personId, it.inputValue) }
        val shares = schedule.shareRows.map { SplitInput(it.personId, it.inputValue) }
        val equalWithInputs = listOfNotNull(
            LedgerError.CorruptData("EQUAL payers of ${schedule.id} carry an input")
                .takeIf { schedule.payerSplit == PayerSplit.EQUAL && payers.any { it.value != null } },
            LedgerError.CorruptData("EQUAL participants of ${schedule.id} carry an input")
                .takeIf { schedule.splitType == SplitType.EQUAL && shares.any { it.value != null } },
        )
        return listOfNotNull(
            (SplitResolver.resolve(schedule.splitType, total, total, shares) as? LedgerResult.Err)?.error,
            (SplitResolver.resolvePayers(schedule.payerSplit, total, total, payers) as? LedgerResult.Err)?.error,
        ) + equalWithInputs
    }

    /**
     * Spec §7.4: stored inputs must suit the split type, row by row as stored, without recomputing the stored
     * allocations. Each row keeps the limits it was saved under, so a later merge of its people never breaks it.
     */
    private fun inputs(expense: Expense): LedgerError? {
        if (expense.splitType == SplitType.EQUAL) {
            return expense.shares.firstOrNull { it.inputValue != null }
                ?.let { LedgerError.CorruptData("EQUAL shares of ${expense.id} carry an input") }
        }
        val inputs = expense.shares.map { SplitInput(it.personId, it.inputValue) }
        val resolved = SplitResolver.resolve(expense.splitType, expense.effectTotal.amountMinor, expense.total.amountMinor, inputs)
        return (resolved as? LedgerResult.Err)?.error
    }
}
