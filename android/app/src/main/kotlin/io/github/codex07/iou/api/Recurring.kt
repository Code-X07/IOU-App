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

/** Why a schedule is on hold (spec §5.7): its group, or the canonical person of one of its people, is archived. */
sealed interface HoldReason {
    data class GroupArchived(val group: GroupRef) : HoldReason
    data class PersonArchived(val person: PersonRef) : HoldReason
}

/** One schedule in a list. Held schedules ([holds] not empty) are meant for their own screen (spec §5.7). */
data class ScheduleSummary(
    val id: RecurringId,
    val description: String,
    val group: GroupRef?,
    /** The template amount, as paid. */
    val total: Money,
    val unit: RecurrenceUnit,
    val interval: Int,
    val autoPost: Boolean,
    /** The next occurrence; null once the schedule has ended. Show it on the edit screen before saving. */
    val next: LocalDate?,
    /** Occurrences due today; 0 while on hold or deleted. */
    val dueCount: Int,
    val holds: List<HoldReason>,
    val deleted: Boolean,
)

data class ScheduleDetail(
    val generation: LedgerGeneration,
    val summary: ScheduleSummary,
    /** For saves, delete, restore, posting and skipping. */
    val token: EditToken,
    /** The template and pattern as the edit form takes them: canonical people, records of one person combined. */
    val editDraft: RecurringDraft,
    /** The last occurrence posted or skipped (posted expenses included); null when none was. */
    val handledThrough: LocalDate?,
    /** Due occurrences, oldest first; empty while on hold or deleted. */
    val due: List<LocalDate>,
    /** Auto-post waits until older dates are posted or skipped ("confirm these to resume auto-posting"). */
    val confirmToResume: Boolean,
    /** The non-deleted expenses posted from this schedule, newest first. */
    val posted: List<ActivityItem>,
    /** Revisions newest first; revert and "Open as draft" go through `LedgerApi` as for other entities. */
    val history: List<RevisionView>,
)

/** One due occurrence in the due list, oldest first (spec §5.7). */
data class DueOccurrence(
    val schedule: RecurringId,
    val description: String,
    val date: LocalDate,
    /** The template amount, as paid; the posted amount may differ. */
    val amount: Money,
    val group: GroupRef?,
    val autoPost: Boolean,
    /** An auto-post schedule that posts nothing more until its older dates are handled. */
    val confirmToResume: Boolean,
    /** Why auto-posting this occurrence failed last time; in memory only, cleared by the next successful run. */
    val autoPostError: LedgerError? = null,
)

/** The draft for posting a schedule's next occurrence; post it with that [occurrence] and [token]. */
data class OccurrenceDraft(
    val schedule: RecurringId,
    val occurrence: LocalDate,
    val draft: ExpenseDraft,
    val token: EditToken,
)
