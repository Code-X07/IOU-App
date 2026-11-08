package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.money.Money
import java.time.LocalDate

/**
 * A schedule to create or save (spec §5.7, §6.2): an expense template without date or FX choice, the same
 * payer and participant inputs as [ExpenseDraft], and a pattern: every [interval] (1–99) [unit]s from [startOn],
 * none after [endOn]. [autoPost] posts due occurrences by itself; otherwise they wait in the due list.
 */
data class RecurringDraft(
    val payers: List<SplitInput>,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val note: String? = null,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
    val unit: RecurrenceUnit,
    val interval: Int = 1,
    val startOn: LocalDate,
    val endOn: LocalDate? = null,
    val autoPost: Boolean = false,
)

/** How a [RecurringDraft] is saved: as a new schedule prepared against [Create.basis], or as an edit. */
sealed interface ScheduleSaveMode {
    data class Create(val basis: LedgerGeneration) : ScheduleSaveMode
    data class Update(val id: RecurringId, val token: EditToken) : ScheduleSaveMode
}
