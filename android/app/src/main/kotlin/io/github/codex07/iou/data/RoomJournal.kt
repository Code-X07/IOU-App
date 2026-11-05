package io.github.codex07.iou.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.Transfer
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

    suspend fun load(db: IouDatabase): LedgerContent = LedgerRows.read(db.ledgerDao()).toContent()
}
