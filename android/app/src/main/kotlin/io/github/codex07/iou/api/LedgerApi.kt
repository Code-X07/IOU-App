package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import kotlinx.coroutines.flow.Flow

interface LedgerApi {
    fun observeOverview(): Flow<Overview>

    fun observeActivity(filter: ActivityFilter = ActivityFilter()): Flow<List<ActivityItem>>

    fun observeEntry(id: EntryId): Flow<EntryDetail?>

    /**
     * Creates or saves a transfer. Errors include `RateRequired` (conversion needed, no usable [TransferDraft.fx]),
     * `FxSnapshotMismatch` (kept snapshot no longer fits, rules 14/16), `SelfTransfer`, `NeedsGroup`,
     * `MemberRemoved`, `ConvertedToZero`, `AmountTooLarge`.
     */
    suspend fun saveTransfer(draft: TransferDraft, mode: SaveMode): LedgerResult<EntryId>

    /** Creates or saves an expense. Errors as [saveTransfer] plus the split errors (spec §4.4). */
    suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId>

    /** The shares saving [draft] would store; [editing] compares with the stored ones (spec §4.4). Writes nothing. */
    suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId? = null): LedgerResult<SplitPreview>

    /** A pre-filled repayment for a plan item. Stale [item] → `LedgerReplaced`. */
    suspend fun settleUpFromPlan(item: PlanItem): LedgerResult<SettleUpDraft>

    /** Pre-filled repayments for every non-zero currency of `net(me, X)` (several if above `MAX_MINOR`). */
    suspend fun settleUpWithPerson(id: PersonId, basis: LedgerGeneration): LedgerResult<List<SettleUpDraft>>

    suspend fun deleteEntry(id: EntryId, token: EditToken): LedgerResult<Unit>

    /** Errors: `DeletedReference`, `MemberRemoved`, and any rule the restored entry breaks. */
    suspend fun restoreEntry(id: EntryId, token: EditToken): LedgerResult<Unit>

    /**
     * Reverts any entity to the business fields of [version]. Merges, the me flag and which person or group
     * a handle or membership belongs to stay as they are now (use `merge`/`unmerge` for those). If today's
     * rules reject it: `RestoreConflict` listing each violated rule, nothing written (spec §5.3).
     */
    suspend fun revertTo(entity: EntityRef, version: Int, token: EditToken): LedgerResult<Unit>

    /**
     * "Open as draft" after a `RestoreConflict`: [version] of [entity] as an edit draft, saved as a normal
     * update (see [VersionDraft]). Memberships have no edit form (`InvalidField("entity")`): add or remove
     * the member instead.
     */
    suspend fun draftFromVersion(entity: EntityRef, version: Int): LedgerResult<VersionDraft>
}
