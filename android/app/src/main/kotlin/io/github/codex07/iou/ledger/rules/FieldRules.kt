package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
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
            is Transfer -> listOfNotNull(amount(entity.amount), amount(entity.effectAmount))
            is Expense -> listOfNotNull(
                blank(entity.description, "description"),
                amount(entity.total),
                amount(entity.effectTotal),
            ) + shares(entity)
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
        val sum = expense.shares.fold(0L) { acc, share -> Math.addExact(acc, share.shareMinor) }
        return if (sum == expense.effectTotal.amountMinor) {
            emptyList()
        } else {
            listOf(LedgerError.CorruptData("shares of ${expense.id} sum to $sum, not ${expense.effectTotal.amountMinor}"))
        }
    }
}
