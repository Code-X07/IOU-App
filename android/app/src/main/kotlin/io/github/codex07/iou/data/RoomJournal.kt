package io.github.codex07.iou.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.store.JournalEntry
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerJournal
import io.github.codex07.iou.store.LedgerSettings

/** Where [RoomJournal] finds the live database; Part 3's holder swaps it on restore. */
fun interface DatabaseProvider {
    fun database(): IouDatabase
}

/** The real flavor's journal (spec §5.1 `LedgerWriter`): one Room transaction per store write. */
class RoomJournal(private val databases: DatabaseProvider) : LedgerJournal {

    override suspend fun append(entry: JournalEntry) {
        val db = databases.database()
        db.useWriterConnection { connection ->
            connection.immediateTransaction { write(db.ledgerDao(), entry) }
        }
    }

    companion object {
        /** Writes [entry] through [dao]; the caller provides the transaction. Also used by the backup restore. */
        suspend fun write(dao: LedgerDao, entry: JournalEntry) {
            val entities = entry.changes.map { it.entity }
            dao.insertFxSnapshots(entry.snapshots.map { it.toRow() })
            dao.upsertPeople(entities.filterIsInstance<Person>().map { it.toRow() })
            dao.upsertHandles(entities.filterIsInstance<PaymentHandle>().map { it.toRow() })
            dao.upsertGroups(entities.filterIsInstance<Group>().map { it.toRow() })
            dao.upsertMembers(entities.filterIsInstance<GroupMember>().map { it.toRow() })
            dao.upsertTransfers(entities.filterIsInstance<Transfer>().map { it.toRow() })
            val expenses = entities.filterIsInstance<Expense>()
            dao.upsertExpenses(expenses.map { it.toRow() })
            for (expense in expenses) {
                dao.deleteShares(expense.id.value)
                dao.insertShares(expense.shareRows())
            }
            dao.insertRevisions(entry.revisions.map { it.toRow() })
            dao.upsertImportBatches(entry.importBatches.map { it.toRow() })
            dao.upsertSettings(settingRows(entry.settings, entry.ledgerSeq))
        }

        fun settingRows(settings: LedgerSettings, ledgerSeq: Long) = listOf(
            AppSettingRow(SettingKeys.HOME_CURRENCY, settings.homeCurrency.code),
            AppSettingRow(SettingKeys.BACKUP_ENCRYPTION_DEFAULT, settings.backupEncryptionDefault.toString()),
            AppSettingRow(SettingKeys.LEDGER_SEQ, ledgerSeq.toString()),
        )
    }
}

/** Reads a whole ledger database into memory (ADR-0010). An empty database gives an empty ledger with defaults. */
object LedgerLoader {

    suspend fun load(db: IouDatabase): LedgerContent {
        val dao = db.ledgerDao()
        val shares = dao.shares().groupBy { it.expenseId }
        val state = LedgerState(
            people = dao.people().associate { PersonId(it.id) to it.toPerson() },
            handles = dao.handles().associate { HandleId(it.id) to it.toHandle() },
            groups = dao.groups().associate { GroupId(it.id) to it.toGroup() },
            members = dao.members().associate { MembershipId(it.id) to it.toMember() },
            transfers = dao.transfers().associate { EntryId(it.id) to it.toTransfer() },
            expenses = dao.expenses().associate { EntryId(it.id) to it.toExpense(shares[it.id].orEmpty()) },
            fxSnapshots = dao.fxSnapshots().associate { FxSnapshotId(it.id) to it.toSnapshot() },
        )
        val settings = dao.settings().associate { it.key to it.value }
        val defaults = LedgerSettings()
        return LedgerContent(
            state = state,
            revisions = dao.revisions().map { it.toRevision() },
            importBatches = dao.importBatches().map { it.toBatch() },
            ledgerSeq = settings[SettingKeys.LEDGER_SEQ]?.toLong() ?: 0,
            settings = LedgerSettings(
                homeCurrency = settings[SettingKeys.HOME_CURRENCY]?.let(::CurrencyCode) ?: defaults.homeCurrency,
                backupEncryptionDefault = settings[SettingKeys.BACKUP_ENCRYPTION_DEFAULT]?.toBooleanStrict() ?: defaults.backupEncryptionDefault,
            ),
        )
    }
}
