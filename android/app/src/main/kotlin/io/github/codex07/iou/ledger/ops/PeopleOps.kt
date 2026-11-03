package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SyncMeta

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
}
