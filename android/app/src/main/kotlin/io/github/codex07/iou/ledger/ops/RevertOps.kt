package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp

/** Revert to version (spec §5.3). */
object RevertOps {

    fun revertTo(
        ctx: WriteContext,
        ref: EntityRef,
        version: Int,
        token: EditToken,
        revisions: List<EntryRevision>,
    ): LedgerResult<Planned<Unit>> = ledgerResult {
        val current = edited(ctx, token, ref.id) { it.entity(ref.type, ref.id) }
        val snapshot = revisions
            .firstOrNull { it.entityType == ref.type && it.entityId == ref.id && it.version == version }
            ?.snapshot
            // Only a snapshot of this very row can be reverted to: anything else would write another row.
            ?.takeIf { it.entityType == ref.type && it.entityId == ref.id }
            ?: fail(LedgerError.NotFound("${ref.id} v$version"))

        // Business fields from the snapshot; identity, links and sync columns (including the deleted state) stay current.
        val reverted = ctx.touch(businessFieldsOf(snapshot, current))
        // A revert that makes a handle the default of a type (flag turned on, or kept on while the type changes)
        // sets it through the api: it clears the others of that type (spec §3.2). A default that stays the
        // default of the same type leaves the other records' flags as they are.
        val clearedDefaults = (reverted as? PaymentHandle)
            ?.takeIf { it.isDefault && !(current as PaymentHandle).let { now -> now.isDefault && now.type == it.type } }
            ?.let { PeopleOps.clearedDefaults(ctx, it) }.orEmpty()
        val changes = listOf(Change(reverted, RevisionAction.UPDATED, "Reverted to v$version")) + clearedDefaults
        val violations = LedgerValidator.validate(ctx.state, ctx.state.applying(changes), WriteOp.Edit(changes))
        ensure(violations.isEmpty()) { LedgerError.RestoreConflict(violations) }
        Planned(Unit, WritePlan(changes))
    }

    /** [old]'s business fields on [current]'s identity: links only their own ops change stay as they are now. */
    private fun businessFieldsOf(old: LedgerEntity, current: LedgerEntity): LedgerEntity = when (old) {
        is Person -> (current as Person).let { old.copy(isMe = it.isMe, mergedIntoId = it.mergedIntoId) }
        is PaymentHandle -> (current as PaymentHandle).let { old.copy(personId = it.personId) }
        is GroupMember -> (current as GroupMember).let { old.copy(groupId = it.groupId, personId = it.personId, joinedAt = it.joinedAt) }
        else -> old
    }.withMeta(current.meta)
}
