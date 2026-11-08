package io.github.codex07.iou.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// One Room row per stored record. Rows are also the JSON shape of revision snapshots and backups, so a
// column change is a schema change for all three (spec §5.5). Values are plain text and integers:
// instants and dates as ISO-8601 strings, money as minor units plus an ISO 4217 code, enums by name.

@Serializable
@Entity(tableName = "person")
data class PersonRow(
    @PrimaryKey val id: String,
    val displayName: String,
    val isMe: Boolean,
    val note: String?,
    val defaultCurrency: String?,
    val archivedAt: String?,
    val mergedIntoId: String?,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

@Serializable
@Entity(tableName = "payment_handle")
data class HandleRow(
    @PrimaryKey val id: String,
    val personId: String,
    val type: String,
    val value: String,
    val holderName: String?,
    val bic: String?,
    val label: String,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

@Serializable
@Entity(tableName = "ledger_group")
data class GroupRow(
    @PrimaryKey val id: String,
    val name: String,
    val defaultCurrency: String,
    val fxMode: String,
    val archivedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

@Serializable
@Entity(tableName = "group_member")
data class MemberRow(
    @PrimaryKey val id: String,
    val groupId: String,
    val personId: String,
    val joinedAt: String,
    val removedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

@Serializable
@Entity(tableName = "transfer")
data class TransferRow(
    @PrimaryKey val id: String,
    val fromPersonId: String,
    val toPersonId: String,
    val amountMinor: Long,
    val currency: String,
    val effectAmountMinor: Long,
    val effectCurrency: String,
    val fxSnapshotId: String?,
    val kind: String,
    val method: String,
    val accountLabel: String?,
    val groupId: String?,
    val occurredOn: String,
    val note: String?,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

@Serializable
@Entity(tableName = "expense")
data class ExpenseRow(
    @PrimaryKey val id: String,
    val groupId: String?,
    val payerSplit: String,
    val description: String,
    val category: String?,
    val totalMinor: Long,
    val currency: String,
    val effectTotalMinor: Long,
    val effectCurrency: String,
    val fxSnapshotId: String?,
    val splitType: String,
    val occurredOn: String,
    val note: String?,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
    val recurringId: String? = null,
    val recurringOn: String? = null,
)

/** Owned by its expense (spec §3.2); [position] keeps the expense's share order. */
@Serializable
@Entity(tableName = "expense_share", primaryKeys = ["expenseId", "personId"])
data class ShareRow(
    val expenseId: String,
    val personId: String,
    val position: Int,
    val shareMinor: Long,
    val inputValue: Long?,
)

/** Owned by its expense like [ShareRow] (spec §3.2); [position] keeps the expense's payer order. */
@Serializable
@Entity(tableName = "expense_payer", primaryKeys = ["expenseId", "personId"])
data class PayerRow(
    val expenseId: String,
    val personId: String,
    val position: Int,
    val paidMinor: Long,
    val inputValue: Long?,
)

@Serializable
@Entity(tableName = "fx_snapshot")
data class FxSnapshotRow(
    @PrimaryKey val id: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: String,
    val rateDate: String,
    val source: String,
    val isStale: Boolean,
    val fetchedAt: String,
    val paidMinor: Long?,
    val chargedMinor: Long?,
)

/** The append-only audit log (spec §3.2). Read in insertion order (`rowid`). */
@Serializable
@Entity(tableName = "entry_revision")
data class RevisionRow(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val version: Int,
    val action: String,
    val authorId: String,
    val deviceId: String,
    val at: String,
    val reason: String?,
    val importBatchId: String?,
    val snapshotJson: String,
)

@Serializable
@Entity(tableName = "import_batch")
data class ImportBatchRow(
    @PrimaryKey val id: String,
    val source: String,
    val fileName: String,
    val importedAt: String,
    val rowCount: Int,
    val undoneAt: String?,
)

/** Ledger settings and counters (spec §3.3, §5.1): see [SettingKeys]. */
@Serializable
@Entity(tableName = "app_setting")
data class AppSettingRow(
    @PrimaryKey val key: String,
    val value: String,
)

/** Cached exchange rates (spec §3.3, §4.5): [rate] quote units per one base unit on [rateDate]. */
@Serializable
@Entity(tableName = "fx_rate_cache", primaryKeys = ["base", "quote", "rateDate"])
data class FxRateRow(
    val base: String,
    val quote: String,
    val rateDate: String,
    val rate: String,
    val fetchedAt: String,
)

object SettingKeys {
    const val HOME_CURRENCY = "homeCurrency"
    const val BACKUP_ENCRYPTION_DEFAULT = "backupEncryptionDefault"
    const val LEDGER_SEQ = "ledgerSeq"
}

/** A schedule (spec §3.2, §5.7); its template rows are [RecurringParticipantRow]s. */
@Serializable
@Entity(tableName = "recurring_expense")
data class RecurringExpenseRow(
    @PrimaryKey val id: String,
    val groupId: String?,
    val description: String,
    val category: String?,
    val note: String?,
    val totalMinor: Long,
    val currency: String,
    val payerSplit: String,
    val splitType: String,
    val unit: String,
    val interval: Int,
    val startOn: String,
    val endOn: String?,
    val handledThrough: String?,
    val autoPostFrom: String,
    val autoPost: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

/** Owned by its schedule like [ShareRow]; [position] keeps the order within its [role] (`PAYER` or `SHARE`). */
@Serializable
@Entity(tableName = "recurring_participant", primaryKeys = ["recurringId", "role", "personId"])
data class RecurringParticipantRow(
    val recurringId: String,
    val role: String,
    val personId: String,
    val position: Int,
    val inputValue: Long?,
)
