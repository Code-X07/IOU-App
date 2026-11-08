package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ResultScope
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType

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

    // ---- internals ----

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
        inputs.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
        inputs.groupingBy { it.personId }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            fail(if (role == ParticipantRole.PAYER) LedgerError.DuplicatePayer(it.key) else LedgerError.DuplicateParticipant(it.key))
        }
        val combined = try {
            EntryOps.combineInputs(inputs, type, resolver)
        } catch (overflow: ArithmeticException) {
            fail(LedgerError.AmountTooLarge)
        }
        resolve(combined).bind()
        if (stored != null) {
            val storedInputs = EntryOps.combineInputs(stored.map { SplitInput(it.personId, it.inputValue) }, type, resolver)
            if (storedInputs.toSet() == combined.toSet()) return stored
        }
        return combined.map { RecurringParticipant(role, it.personId, it.value) }
    }
}
