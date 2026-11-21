package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import java.time.LocalDate

data class ScheduleFields(
    val unit: RecurrenceUnit = RecurrenceUnit.MONTH,
    val interval: String = "1",
    val endOn: String = "",
    val autoPost: Boolean = false,
    val handledThrough: LocalDate? = null,
) {
    fun next(startOn: String): LedgerResult<LocalDate?> = ledgerResult {
        val start = parseDate(startOn, "date")
        previewNextOccurrence(start, unit, intervalValue(), endDate(), handledThrough).bind()
    }

    fun draft(expense: ExpenseDraft): LedgerResult<RecurringDraft> = ledgerResult {
        next(expense.occurredOn.toString()).bind()
        RecurringDraft(
            payers = expense.payers, description = expense.description, category = expense.category,
            total = expense.total, groupId = expense.groupId, splitType = expense.splitType,
            participants = expense.participants, note = expense.note, payerSplit = expense.payerSplit,
            unit = unit, interval = intervalValue(), startOn = expense.occurredOn, endOn = endDate(), autoPost = autoPost,
        )
    }

    private fun intervalValue() = interval.trim().toIntOrNull() ?: 0
    private fun ResultScope.endDate() = endOn.takeUnless(String::isBlank)?.let { parseDate(it, "endOn") }

    companion object {
        fun from(draft: RecurringDraft, handledThrough: LocalDate?) =
            ScheduleFields(draft.unit, draft.interval.toString(), draft.endOn?.toString().orEmpty(), draft.autoPost, handledThrough)
    }
}

private fun ResultScope.parseDate(text: String, field: String): LocalDate =
    try { LocalDate.parse(text.trim()) } catch (_: java.time.DateTimeException) { fail(LedgerError.InvalidField(field)) }

internal fun RecurringDraft.expenseTemplate() = ExpenseDraft(
    payers = payers, description = description, category = category, total = total, groupId = groupId,
    splitType = splitType, participants = participants, occurredOn = startOn, note = note, payerSplit = payerSplit,
)
