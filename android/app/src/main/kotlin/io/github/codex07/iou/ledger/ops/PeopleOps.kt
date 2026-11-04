package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MergeProblem
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.rules.FieldRules

/** People, payment handles, merge and unmerge (spec §5.2, §5.6). */
object PeopleOps {

    /** First launch: creates me, the permanent global identity (spec §3.1). */
    fun setup(ctx: WriteContext, displayName: String): LedgerResult<Planned<PersonId>> = ledgerResult {
        ensure(ctx.state.meOrNull == null) { LedgerError.CannotModifyMe }
        val id = PersonId(ctx.ids.next())
        val me = Person(id = id, displayName = displayName.trim(), isMe = true, meta = SyncMeta(ctx.now, ctx.now, id))
        Planned(id, validated(ctx, listOf(Change(me, RevisionAction.CREATED))))
    }

    fun createPerson(ctx: WriteContext, draft: PersonDraft, basis: LedgerGeneration): LedgerResult<Planned<PersonId>> =
        ledgerResult {
            checkBasis(ctx, basis)
            val person = Person(
                id = PersonId(ctx.ids.next()),
                displayName = draft.displayName.trim(),
                note = draft.note.cleaned(),
                defaultCurrency = draft.defaultCurrency,
                meta = ctx.newMeta(),
            )
            Planned(person.id, validated(ctx, listOf(Change(person, RevisionAction.CREATED))))
        }

    fun updatePerson(ctx: WriteContext, id: PersonId, draft: PersonDraft, token: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val person = edited(ctx, token, id.value) { it.people[id] }
            val updated = ctx.touch(
                person.copy(displayName = draft.displayName.trim(), note = draft.note.cleaned(), defaultCurrency = draft.defaultCurrency),
            )
            Planned(Unit, validated(ctx, listOf(Change(updated, RevisionAction.UPDATED))))
        }

    fun setPersonArchived(ctx: WriteContext, id: PersonId, archived: Boolean, token: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val person = edited(ctx, token, id.value) { it.people[id] }
            val updated = ctx.touch(person.copy(archivedAt = if (archived) person.archivedAt ?: ctx.now else null))
            val reason = if (archived) "Archived" else "Unarchived"
            Planned(Unit, validated(ctx, listOf(Change(updated, RevisionAction.UPDATED, reason))))
        }

    /** Deletes the person and their live handles with one `deletedAt` (spec §5.2). */
    fun deletePerson(ctx: WriteContext, id: PersonId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val person = edited(ctx, token, id.value) { it.people[id] }
        if (person.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        val handles = ctx.state.handles.values.filter { it.personId == id && !it.isDeleted }
        val changes = (listOf<LedgerEntity>(person) + handles).map {
            Change(ctx.touch(it, deletedAt = ctx.now), RevisionAction.DELETED)
        }
        Planned(Unit, validated(ctx, changes))
    }

    /** Restores the person and exactly the handles deleted together with them (spec §5.2). */
    fun restorePerson(ctx: WriteContext, id: PersonId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val person = edited(ctx, token, id.value) { it.people[id] }
        if (!person.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        val deletedAt = person.meta.deletedAt
        val handles = ctx.state.handles.values.filter { it.personId == id && it.meta.deletedAt == deletedAt }
        val changes = (listOf<LedgerEntity>(person) + handles).map {
            Change(ctx.touch(it, deletedAt = null), RevisionAction.RESTORED)
        }
        Planned(Unit, validated(ctx, changes))
    }

    fun addHandle(ctx: WriteContext, personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<Planned<HandleId>> =
        ledgerResult {
            checkBasis(ctx, basis)
            person(ctx, personId)
            val handle = PaymentHandle(
                id = HandleId(ctx.ids.next()),
                personId = personId,
                type = draft.type,
                value = FieldRules.normalizeHandleValue(draft.type, draft.value).bind(),
                holderName = draft.holderName.cleaned(),
                bic = draft.bic.cleaned()?.uppercase(),
                label = draft.label.trim(),
                isDefault = draft.isDefault,
                meta = ctx.newMeta(),
            )
            val changes = listOf(Change(handle, RevisionAction.CREATED)) + clearedDefaults(ctx, handle)
            Planned(handle.id, validated(ctx, changes))
        }

    fun updateHandle(ctx: WriteContext, id: HandleId, draft: HandleDraft, token: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val handle = edited(ctx, token, id.value) { it.handles[id] }
            // The draft's isDefault is the effective default the view showed. Left as shown, the stored flag stays
            // (a merged alias's own default survives for unmerge) and no other record's flag is cleared.
            val resolver = IdentityResolver(ctx.state)
            val sameType = draft.type == handle.type
            val wasDefault = sameType && resolver.defaultHandle(resolver.canonical(handle.personId), handle.type)?.id == handle.id
            val updated = ctx.touch(
                handle.copy(
                    type = draft.type,
                    value = FieldRules.normalizeHandleValue(draft.type, draft.value).bind(),
                    holderName = draft.holderName.cleaned(),
                    bic = draft.bic.cleaned()?.uppercase(),
                    label = draft.label.trim(),
                    isDefault = if (sameType && draft.isDefault == wasDefault) handle.isDefault else draft.isDefault,
                ),
            )
            val moved = if (draft.isDefault && !wasDefault) clearedDefaults(ctx, updated) else emptyList()
            val changes = listOf(Change(updated, RevisionAction.UPDATED)) + moved
            Planned(Unit, validated(ctx, changes))
        }

    fun deleteHandle(ctx: WriteContext, id: HandleId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val handle = edited(ctx, token, id.value) { it.handles[id] }
        if (handle.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(handle, deletedAt = ctx.now), RevisionAction.DELETED))))
    }

    /** Sets `mergedIntoId` on the source only; collisions resolve at read time (spec §5.6). */
    fun merge(ctx: WriteContext, source: PersonId, target: PersonId, sourceToken: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val from = edited(ctx, sourceToken, source.value) { it.people[source] }
            val into = person(ctx, target)
            ensure(source != target) { LedgerError.InvalidMerge(MergeProblem.SAME_PERSON) }
            ensure(!from.isMe && !into.isMe) { LedgerError.CannotModifyMe }
            ensure(!from.isDeleted && !into.isDeleted) { LedgerError.InvalidMerge(MergeProblem.DELETED) }
            ensure(from.mergedIntoId == null && into.mergedIntoId == null) { LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL) }
            val merged = ctx.touch(from.copy(mergedIntoId = target))
            Planned(Unit, validated(ctx, listOf(Change(merged, RevisionAction.UPDATED, "Merged into ${into.displayName}"))))
        }

    /** Splits the person off with its own aliases (spec §5.6). Rule 12: must be merged. */
    fun unmerge(ctx: WriteContext, personId: PersonId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val person = edited(ctx, token, personId.value) { it.people[personId] }
        val target = person.mergedIntoId ?: fail(LedgerError.InvalidMerge(MergeProblem.NOT_MERGED))
        val targetName = ctx.state.people[target]?.displayName ?: target.value
        val split = ctx.touch(person.copy(mergedIntoId = null))
        Planned(Unit, validated(ctx, listOf(Change(split, RevisionAction.UPDATED, "Unmerged from $targetName"))))
    }

    /**
     * Spec §3.2: a new default clears the flag on other handles of the type for the same canonical person.
     * A deleted handle is never the effective default, so its flag displaces nothing.
     */
    internal fun clearedDefaults(ctx: WriteContext, handle: PaymentHandle): List<Change> {
        if (!handle.isDefault || handle.isDeleted) return emptyList()
        val resolver = IdentityResolver(ctx.state)
        return resolver.handlesOf(resolver.canonical(handle.personId))
            .filter { it.id != handle.id && it.type == handle.type && it.isDefault }
            .map { Change(ctx.touch(it.copy(isDefault = false)), RevisionAction.UPDATED, "Default moved to ${handle.label}") }
    }
}
