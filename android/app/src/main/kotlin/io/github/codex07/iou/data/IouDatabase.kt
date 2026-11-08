package io.github.codex07.iou.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Every ledger table (spec §3). Only [RoomJournal] and the backup restore write it; nothing outside `data`
 * may touch [LedgerDao] (checked by `DataAccessTest`). Rows are replaced whole (`REPLACE`; there are no
 * foreign keys); a replaced import batch moves to the end of the `rowid` order, as it does in the store.
 */
@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPeople(rows: List<PersonRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHandles(rows: List<HandleRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertGroups(rows: List<GroupRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMembers(rows: List<MemberRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTransfers(rows: List<TransferRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExpenses(rows: List<ExpenseRow>)
    @Query("DELETE FROM expense_share WHERE expenseId = :expenseId") suspend fun deleteShares(expenseId: String)
    @Insert suspend fun insertShares(rows: List<ShareRow>)
    @Query("DELETE FROM expense_payer WHERE expenseId = :expenseId") suspend fun deletePayers(expenseId: String)
    @Insert suspend fun insertPayers(rows: List<PayerRow>)

    /** Snapshots are immutable (spec §3.2): a second insert of the same ID fails the transaction. */
    @Insert suspend fun insertFxSnapshots(rows: List<FxSnapshotRow>)
    @Insert suspend fun insertRevisions(rows: List<RevisionRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertImportBatches(rows: List<ImportBatchRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSettings(rows: List<AppSettingRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRates(rows: List<FxRateRow>)

    @Query("SELECT * FROM person") suspend fun people(): List<PersonRow>
    @Query("SELECT * FROM payment_handle") suspend fun handles(): List<HandleRow>
    @Query("SELECT * FROM ledger_group") suspend fun groups(): List<GroupRow>
    @Query("SELECT * FROM group_member") suspend fun members(): List<MemberRow>
    @Query("SELECT * FROM transfer") suspend fun transfers(): List<TransferRow>
    @Query("SELECT * FROM expense") suspend fun expenses(): List<ExpenseRow>
    @Query("SELECT * FROM expense_share ORDER BY expenseId, position") suspend fun shares(): List<ShareRow>
    @Query("SELECT * FROM expense_payer ORDER BY expenseId, position") suspend fun payers(): List<PayerRow>
    @Query("SELECT * FROM fx_snapshot") suspend fun fxSnapshots(): List<FxSnapshotRow>
    @Query("SELECT * FROM entry_revision ORDER BY rowid") suspend fun revisions(): List<RevisionRow>
    @Query("SELECT * FROM import_batch ORDER BY rowid") suspend fun importBatches(): List<ImportBatchRow>
    @Query("SELECT * FROM app_setting") suspend fun settings(): List<AppSettingRow>
    @Query("SELECT * FROM fx_rate_cache") suspend fun rates(): List<FxRateRow>
}

@Database(
    entities = [
        PersonRow::class, HandleRow::class, GroupRow::class, MemberRow::class, TransferRow::class, ExpenseRow::class,
        ShareRow::class, PayerRow::class, FxSnapshotRow::class, RevisionRow::class, ImportBatchRow::class, AppSettingRow::class,
        FxRateRow::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class IouDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        /**
         * Opens the database at the absolute path [file], or an in-memory one when [file] is null. In WAL mode
         * readers never block the writer, and closing the last connection checkpoints the WAL into the database
         * file and deletes it, so a closed database is a single file; the restore's file swap relies on that
         * (spec §7.1).
         */
        fun open(context: Context, driver: SQLiteDriver, file: String?): IouDatabase {
            val builder = if (file == null) {
                Room.inMemoryDatabaseBuilder(context, IouDatabase::class.java)
            } else {
                Room.databaseBuilder(context, IouDatabase::class.java, file)
            }
            return builder
                .addMigrations(*Migrations.ALL)
                .setDriver(driver)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }
}
