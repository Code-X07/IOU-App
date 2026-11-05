package io.github.codex07.iou.data

import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerSettings
import kotlinx.serialization.Serializable

/**
 * Every table of a ledger database as rows, in `rowid` order where that order matters (revisions, import
 * batches). It is what [LedgerLoader] reads and the body of a backup (spec §7.1), so a backup holds exactly
 * what the database holds, the rate cache included.
 */
@Serializable
data class LedgerRows(
    val people: List<PersonRow> = emptyList(),
    val handles: List<HandleRow> = emptyList(),
    val groups: List<GroupRow> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    val transfers: List<TransferRow> = emptyList(),
    val expenses: List<ExpenseRow> = emptyList(),
    val shares: List<ShareRow> = emptyList(),
    val fxSnapshots: List<FxSnapshotRow> = emptyList(),
    val revisions: List<RevisionRow> = emptyList(),
    val importBatches: List<ImportBatchRow> = emptyList(),
    val settings: List<AppSettingRow> = emptyList(),
    val rates: List<FxRateRow> = emptyList(),
) {
    /** The ledger these rows hold; defaults for missing settings. Throws for rows that don't map (bad enums, dates, snapshots). */
    fun toContent(): LedgerContent {
        val sharesByExpense = shares.groupBy { it.expenseId }
        val state = LedgerState(
            people = people.associate { PersonId(it.id) to it.toPerson() },
            handles = handles.associate { HandleId(it.id) to it.toHandle() },
            groups = groups.associate { GroupId(it.id) to it.toGroup() },
            members = members.associate { MembershipId(it.id) to it.toMember() },
            transfers = transfers.associate { EntryId(it.id) to it.toTransfer() },
            expenses = expenses.associate { EntryId(it.id) to it.toExpense(sharesByExpense[it.id].orEmpty()) },
            fxSnapshots = fxSnapshots.associate { FxSnapshotId(it.id) to it.toSnapshot() },
        )
        val values = settings.associate { it.key to it.value }
        val defaults = LedgerSettings()
        return LedgerContent(
            state = state,
            revisions = revisions.map { it.toRevision() },
            importBatches = importBatches.map { it.toBatch() },
            ledgerSeq = values[SettingKeys.LEDGER_SEQ]?.toLong() ?: 0,
            settings = LedgerSettings(
                homeCurrency = values[SettingKeys.HOME_CURRENCY]?.let(::CurrencyCode) ?: defaults.homeCurrency,
                backupEncryptionDefault = values[SettingKeys.BACKUP_ENCRYPTION_DEFAULT]?.toBooleanStrict() ?: defaults.backupEncryptionDefault,
            ),
        )
    }

    /** Inserts every row through [dao], in order; the caller provides the transaction. */
    suspend fun writeTo(dao: LedgerDao) {
        dao.upsertPeople(people)
        dao.upsertHandles(handles)
        dao.upsertGroups(groups)
        dao.upsertMembers(members)
        dao.upsertTransfers(transfers)
        dao.upsertExpenses(expenses)
        dao.insertShares(shares)
        dao.insertFxSnapshots(fxSnapshots)
        dao.insertRevisions(revisions)
        dao.upsertImportBatches(importBatches)
        dao.upsertSettings(settings)
        dao.upsertRates(rates)
    }

    companion object {
        suspend fun read(dao: LedgerDao) = LedgerRows(
            people = dao.people(),
            handles = dao.handles(),
            groups = dao.groups(),
            members = dao.members(),
            transfers = dao.transfers(),
            expenses = dao.expenses(),
            shares = dao.shares(),
            fxSnapshots = dao.fxSnapshots(),
            revisions = dao.revisions(),
            importBatches = dao.importBatches(),
            settings = dao.settings(),
            rates = dao.rates(),
        )

        /** The rows a database holding [content] and the cached [rates] would have. */
        fun of(content: LedgerContent, rates: Collection<CachedRate>): LedgerRows {
            val state = content.state
            return LedgerRows(
                people = state.people.values.map { it.toRow() },
                handles = state.handles.values.map { it.toRow() },
                groups = state.groups.values.map { it.toRow() },
                members = state.members.values.map { it.toRow() },
                transfers = state.transfers.values.map { it.toRow() },
                expenses = state.expenses.values.map { it.toRow() },
                shares = state.expenses.values.flatMap { it.shareRows() },
                fxSnapshots = state.fxSnapshots.values.map { it.toRow() },
                revisions = content.revisions.map { it.toRow() },
                importBatches = content.importBatches.map { it.toRow() },
                settings = RoomJournal.settingRows(content.settings, content.ledgerSeq),
                rates = rates.map { it.toRow() },
            )
        }
    }
}
