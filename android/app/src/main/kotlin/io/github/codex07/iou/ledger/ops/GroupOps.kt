package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction

/** Groups and canonical membership (spec §3.4, §5.2). */
object GroupOps {

    fun createGroup(ctx: WriteContext, draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<Planned<GroupId>> =
        ledgerResult {
            checkBasis(ctx, basis)
            val resolver = IdentityResolver(ctx.state)
            val group = Group(GroupId(ctx.ids.next()), draft.name.trim(), draft.defaultCurrency, draft.fxMode, meta = ctx.newMeta())
            members.forEach { person(ctx, it) }
            val people = (listOf(ctx.meId) + members.map(resolver::canonical)).distinct()
            val memberships = people.map { membership(ctx, group.id, it) }
            val changes = (listOf<LedgerEntity>(group) + memberships).map { Change(it, RevisionAction.CREATED) }
            Planned(group.id, validated(ctx, changes))
        }

    fun updateGroup(ctx: WriteContext, id: GroupId, draft: GroupDraft, token: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val group = edited(ctx, token, id.value) { it.groups[id] }
            val updated = ctx.touch(group.copy(name = draft.name.trim(), defaultCurrency = draft.defaultCurrency, fxMode = draft.fxMode))
            Planned(Unit, validated(ctx, listOf(Change(updated, RevisionAction.UPDATED))))
        }

    /** Archiving = leaving; allowed with an open balance (spec §5.2). */
    fun setGroupArchived(ctx: WriteContext, id: GroupId, archived: Boolean, token: EditToken): LedgerResult<Planned<Unit>> =
        ledgerResult {
            val group = edited(ctx, token, id.value) { it.groups[id] }
            val updated = ctx.touch(group.copy(archivedAt = if (archived) group.archivedAt ?: ctx.now else null))
            val reason = if (archived) "Archived" else "Unarchived"
            Planned(Unit, validated(ctx, listOf(Change(updated, RevisionAction.UPDATED, reason))))
        }

    fun deleteGroup(ctx: WriteContext, id: GroupId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val group = edited(ctx, token, id.value) { it.groups[id] }
        if (group.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        val memberships = ctx.state.members.values.filter { it.groupId == id && !it.isDeleted }
        val changes = (listOf<LedgerEntity>(group) + memberships).map {
            Change(ctx.touch(it, deletedAt = ctx.now), RevisionAction.DELETED)
        }
        Planned(Unit, validated(ctx, changes))
    }

    fun restoreGroup(ctx: WriteContext, id: GroupId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val group = edited(ctx, token, id.value) { it.groups[id] }
        if (!group.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        val deletedAt = group.meta.deletedAt
        val memberships = ctx.state.members.values.filter { it.groupId == id && it.meta.deletedAt == deletedAt }
        val changes = (listOf<LedgerEntity>(group) + memberships).map {
            Change(ctx.touch(it, deletedAt = null), RevisionAction.RESTORED)
        }
        Planned(Unit, validated(ctx, changes))
    }

    /** Adds the canonical person, or re-adds them by clearing `removedAt` on all their memberships. */
    fun addMember(ctx: WriteContext, groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Planned<Unit>> =
        ledgerResult {
            checkBasis(ctx, basis)
            group(ctx, groupId)
            person(ctx, personId)
            val resolver = IdentityResolver(ctx.state)
            val canonical = resolver.canonical(personId)
            val memberships = resolver.memberships(groupId, canonical)
            val changes = when {
                memberships.any { it.removedAt == null } -> emptyList()
                memberships.isNotEmpty() -> memberships.map {
                    Change(ctx.touch(it.copy(removedAt = null)), RevisionAction.UPDATED, "Re-added")
                }
                else -> listOf(Change(membership(ctx, groupId, canonical), RevisionAction.CREATED))
            }
            Planned(Unit, validated(ctx, changes))
        }

    /** Sets `removedAt` on every active membership of the canonical person (rules 2 and 4 apply). */
    fun removeMember(ctx: WriteContext, groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Planned<Unit>> =
        ledgerResult {
            checkBasis(ctx, basis)
            group(ctx, groupId)
            person(ctx, personId)
            val resolver = IdentityResolver(ctx.state)
            val canonical = resolver.canonical(personId)
            ensure(canonical != ctx.meId) { LedgerError.CannotModifyMe }
            val changes = resolver.memberships(groupId, canonical)
                .filter { it.removedAt == null }
                .map { Change(ctx.touch(it.copy(removedAt = ctx.now)), RevisionAction.UPDATED, "Removed from group") }
            Planned(Unit, validated(ctx, changes))
        }

    private fun membership(ctx: WriteContext, groupId: GroupId, personId: PersonId) =
        GroupMember(MembershipId(ctx.ids.next()), groupId, personId, joinedAt = ctx.now, meta = ctx.newMeta())
}
