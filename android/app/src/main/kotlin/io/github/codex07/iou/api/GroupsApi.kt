package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface GroupsApi {
    /** By name. Archived and deleted groups only when asked (deleted ones to view history or restore). */
    fun observeGroups(includeArchived: Boolean = false, includeDeleted: Boolean = false): Flow<List<GroupSummary>>

    fun observeGroup(id: GroupId): Flow<GroupDetail?>

    /** Me is added automatically; [members] are the other people. Errors: `InvalidField("name")`. */
    suspend fun createGroup(draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<GroupId>

    /** Errors: `GroupSettingsLocked` when changing a locked `fxMode` or CONVERT currency (rules 6–7). */
    suspend fun updateGroup(id: GroupId, draft: GroupDraft, token: EditToken): LedgerResult<Unit>

    /** Archiving = leaving; allowed with an open balance (spec §5.2). */
    suspend fun setGroupArchived(id: GroupId, archived: Boolean, token: EditToken): LedgerResult<Unit>

    /** Deletes the group with its memberships; any entry → `StillReferenced`. */
    suspend fun deleteGroup(id: GroupId, token: EditToken): LedgerResult<Unit>

    suspend fun restoreGroup(id: GroupId, token: EditToken): LedgerResult<Unit>

    /** Adds the person, or re-adds a removed member (unlocking their entries, rule 5). */
    suspend fun addMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Unit>

    /** Errors: `CannotModifyMe`, `MemberHasBalance` (rule 4). */
    suspend fun removeMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration): LedgerResult<Unit>
}
