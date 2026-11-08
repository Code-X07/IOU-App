package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ResultScope
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import java.time.LocalDate

/** Schedules (spec §5.7): save, delete, restore, post and skip, and the cut-off rule with hold resumes on every write. */
object RecurringOps {

    fun save(ctx: WriteContext, draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<Planned<RecurringId>> = ledgerResult {
        val existing: RecurringExpense? = when (mode) {
            is ScheduleSaveMode.Create -> {
                checkBasis(ctx, mode.basis)
                null
            }
            is ScheduleSaveMode.Update -> edited(ctx, mode.token, mode.id.value) { it.schedules[mode.id] }
        }
        checkAmount(draft.total)
        val resolver = IdentityResolver(ctx.state)
        val total = draft.total.amountMinor
        val shares = templateRows(
            ParticipantRole.SHARE, draft.participants, draft.splitType,
            existing?.takeIf { it.splitType == draft.splitType }?.shareRows, resolver,
        ) { SplitResolver.resolve(draft.splitType, total, total, it) }
        val payers = templateRows(
            ParticipantRole.PAYER, draft.payers, draft.payerSplit.splitType,
            existing?.takeIf { it.payerSplit == draft.payerSplit }?.payerRows, resolver,
        ) { SplitResolver.resolvePayers(draft.payerSplit, total, total, it) }

        val schedule = RecurringExpense(
            id = existing?.id ?: RecurringId(ctx.ids.next()),
            groupId = draft.groupId,
            description = draft.description.trim(),
            category = draft.category,
            note = draft.note.cleaned(),
            total = draft.total,
            payerSplit = draft.payerSplit,
            splitType = draft.splitType,
            participants = payers + shares,
            unit = draft.unit,
            interval = draft.interval,
            startOn = draft.startOn,
            endOn = draft.endOn,
            // An edit changes only later occurrences: what was handled stays handled (spec §5.7).
            handledThrough = existing?.handledThrough,
            // The cut-off rule in finalize sets today when the schedule auto-posts after this write.
            autoPostFrom = existing?.autoPostFrom ?: ctx.today,
            autoPost = draft.autoPost,
            meta = existing?.meta ?: ctx.newMeta(),
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(schedule.id, validated(ctx, listOf(Change(schedule, action))))
    }

    fun delete(ctx: WriteContext, id: RecurringId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        if (schedule.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(schedule, deletedAt = ctx.now), RevisionAction.DELETED))))
    }

    fun restore(ctx: WriteContext, id: RecurringId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        if (!schedule.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(schedule, deletedAt = null), RevisionAction.RESTORED))))
    }

    /**
     * Posts [occurrence], which must be the schedule's next one (rule 19), as the expense [draft] describes (spec
     * §5.7): the expense is created through the normal expense create, so every rule applies, and the schedule's
     * `handledThrough` moves to [occurrence] in the same write. [token] is the schedule's.
     */
    fun post(
        ctx: WriteContext,
        id: RecurringId,
        occurrence: LocalDate,
        draft: ExpenseDraft,
        token: EditToken,
    ): LedgerResult<Planned<EntryId>> = ledgerResult {
        val schedule = live(ctx, id, token)
        val next = Recurrence.next(schedule, ctx.state)
        ensure(next != null && occurrence == next) { LedgerError.OccurrenceNotDue(next) }
        val created = EntryOps.saveExpense(ctx, draft, SaveMode.Create(ctx.generation)).bind()
        val expense = (created.plan.changes.single().entity as Expense).copy(recurringId = id, recurringOn = occurrence)
        val handled = ctx.touch(schedule.copy(handledThrough = occurrence))
        val changes = listOf(
            Change(expense, RevisionAction.CREATED, "Posted from ${schedule.description}"),
            Change(handled, RevisionAction.UPDATED, "Posted $occurrence", keepsCutOff = true),
        )
        Planned(expense.id, validated(ctx, changes, created.plan.snapshots))
    }

    /** Skips every occurrence from the next one through [through], which must be an occurrence (rule 19). */
    fun skip(ctx: WriteContext, id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val schedule = live(ctx, id, token)
        val next = Recurrence.next(schedule, ctx.state)
        ensure(next != null && !through.isBefore(next) && Recurrence.isOccurrence(schedule, through)) {
            LedgerError.OccurrenceNotDue(next)
        }
        val skipped = ctx.touch(schedule.copy(handledThrough = through))
        Planned(Unit, validated(ctx, listOf(Change(skipped, RevisionAction.UPDATED, "Skipped through $through", keepsCutOff = true))))
    }

    /**
     * Spec §5.7: deleting a posted expense through the api raises its schedule's `handledThrough` to the expense's
     * occurrence when it is lower, so the date doesn't become due again. Like a skip it only moves forward.
     */
    internal fun raisedByDelete(ctx: WriteContext, entry: Entry): List<Change> {
        val expense = entry as? Expense ?: return emptyList()
        val on = expense.recurringOn ?: return emptyList()
        val schedule = expense.recurringId?.let { ctx.state.schedules[it] } ?: return emptyList()
        val handled = schedule.handledThrough
        if (handled != null && !handled.isBefore(on)) return emptyList()
        val raised = ctx.touch(schedule.copy(handledThrough = on))
        return listOf(Change(raised, RevisionAction.UPDATED, "Handled through $on: posted expense deleted", keepsCutOff = true))
    }

    // ---- internals ----

    /** The schedule a post or skip acts on: token checked first, and a deleted schedule is `NotFound` (rule 19). */
    private fun ResultScope.live(ctx: WriteContext, id: RecurringId, token: EditToken): RecurringExpense {
        val schedule = edited(ctx, token, id.value) { it.schedules[id] }
        ensure(!schedule.isDeleted) { LedgerError.NotFound(id.value) }
        return schedule
    }

    /**
     * One role's template rows (spec §4.4, §5.7): checked per draft record (negatives, the same record twice),
     * combined per canonical person, resolved over the template total so split errors come back now, and stored as
     * the combined inputs. When the combined inputs equal the stored rows' ([stored] is given only when the split
     * type is unchanged), the stored rows are kept, so an edit never rewrites a template's IDs by itself (spec §5.6).
     */
    private fun ResultScope.templateRows(
        role: ParticipantRole,
        inputs: List<SplitInput>,
        type: SplitType,
        stored: List<RecurringParticipant>?,
        resolver: IdentityResolver,
        resolve: (List<SplitInput>) -> LedgerResult<*>,
    ): List<RecurringParticipant> {
        val duplicate: (PersonId) -> LedgerError =
            if (role == ParticipantRole.PAYER) LedgerError::DuplicatePayer else LedgerError::DuplicateParticipant
        val combined = with(EntryOps) { checkedInputs(inputs, type, resolver, duplicate) }
        resolve(combined).bind()
        if (stored != null) {
            val storedInputs = EntryOps.combineInputs(stored.map { SplitInput(it.personId, it.inputValue) }, type, resolver)
            if (storedInputs.toSet() == combined.toSet()) return stored
        }
        return combined.map { RecurringParticipant(role, it.personId, it.value) }
    }
}
