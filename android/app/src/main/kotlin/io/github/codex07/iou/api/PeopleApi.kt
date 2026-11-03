package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface PeopleApi {
    /**
     * Canonical people other than me, by display name. Archived and deleted ones only when asked: deleted
     * people are found here to view their history or restore them.
     */
    fun observePeople(includeArchived: Boolean = false, includeDeleted: Boolean = false): Flow<List<PersonSummary>>

    /** Detail of the canonical person [id] resolves to; null if unknown. */
    fun observePerson(id: PersonId): Flow<PersonDetail?>

    /** Errors: `InvalidField("displayName")`. */
    suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration): LedgerResult<PersonId>

    suspend fun updatePerson(id: PersonId, draft: PersonDraft, token: EditToken): LedgerResult<Unit>

    /** Allowed with an open balance (spec §5.2); me → `CannotModifyMe`. */
    suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken): LedgerResult<Unit>

    /** Deletes the person with their payment handles; referenced → `StillReferenced`; me → `CannotModifyMe`. */
    suspend fun deletePerson(id: PersonId, token: EditToken): LedgerResult<Unit>

    /** Restores the person with the handles deleted together with them. */
    suspend fun restorePerson(id: PersonId, token: EditToken): LedgerResult<Unit>

    /** Errors: `InvalidIban`, `InvalidField("value" | "label")`. A new default clears the old one of that type. */
    suspend fun addHandle(personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<HandleId>

    /**
     * Fill [draft] from the `PaymentHandleView`. Leaving `isDefault` as shown keeps every stored default
     * flag; switching it on makes this the default and clears the others of that type.
     */
    suspend fun updateHandle(id: HandleId, draft: HandleDraft, token: EditToken): LedgerResult<Unit>

    suspend fun deleteHandle(id: HandleId, token: EditToken): LedgerResult<Unit>

    /** Errors: `InvalidMerge`, `CannotModifyMe`, `NotFound`. Writes nothing. */
    suspend fun previewMerge(source: PersonId, target: PersonId): LedgerResult<MergePreview>

    /** Merges [source] into [target]; [sourceToken] is the source's token (the only row written, spec §5.6). */
    suspend fun merge(source: PersonId, target: PersonId, sourceToken: EditToken): LedgerResult<Unit>

    /**
     * Splits [person] (with its own aliases) off again. [token] is the alias's own, from
     * `PersonDetail.aliases`. Errors: `InvalidMerge(NOT_MERGED)`, rule errors.
     */
    suspend fun unmerge(person: PersonId, token: EditToken): LedgerResult<Unit>

    /** Plain text of my balance with this person, for sharing (e.g. "Alex owes you 30.00 EUR"). */
    suspend fun balanceSummaryText(id: PersonId): LedgerResult<String>
}
