package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

/** Sync columns shared by every syncable entity (spec §3.1). */
data class SyncMeta(
    val createdAt: Instant,
    val updatedAt: Instant,
    val authorId: PersonId,
    val deletedAt: Instant? = null,
    val version: Int = 1,
    val importBatchId: ImportBatchId? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

/** A row that has sync columns and revisions. */
sealed interface LedgerEntity {
    val meta: SyncMeta
    val entityType: EntityType
    val entityId: String
    val isDeleted: Boolean get() = meta.isDeleted

    fun withMeta(meta: SyncMeta): LedgerEntity
}

data class Person(
    val id: PersonId,
    val displayName: String,
    val isMe: Boolean = false,
    val note: String? = null,
    val defaultCurrency: CurrencyCode? = null,
    val archivedAt: Instant? = null,
    val mergedIntoId: PersonId? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.PERSON
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): Person = copy(meta = meta)
}

data class PaymentHandle(
    val id: HandleId,
    val personId: PersonId,
    val type: HandleType,
    val value: String,
    val holderName: String? = null,
    val bic: String? = null,
    val label: String,
    val isDefault: Boolean = false,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.PAYMENT_HANDLE
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): PaymentHandle = copy(meta = meta)
}

data class Group(
    val id: GroupId,
    val name: String,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode = FxMode.CONVERT,
    val archivedAt: Instant? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.GROUP
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): Group = copy(meta = meta)
}

data class GroupMember(
    val id: MembershipId,
    val groupId: GroupId,
    val personId: PersonId,
    val joinedAt: Instant,
    val removedAt: Instant? = null,
    override val meta: SyncMeta,
) : LedgerEntity {
    override val entityType: EntityType get() = EntityType.GROUP_MEMBER
    override val entityId: String get() = id.value
    override fun withMeta(meta: SyncMeta): GroupMember = copy(meta = meta)
}

/** A transfer or an expense: something with a paid amount and an effect on balances. */
sealed interface Entry : LedgerEntity {
    val id: EntryId
    val groupId: GroupId?
    val paid: Money
    val effect: Money
    val fxSnapshotId: FxSnapshotId?
    val occurredOn: LocalDate
    val note: String?

    /** Every person this entry references, as stored (not resolved through merges). */
    val personIds: Set<PersonId>

    override val entityId: String get() = id.value
}

data class Transfer(
    override val id: EntryId,
    val fromPersonId: PersonId,
    val toPersonId: PersonId,
    val amount: Money,
    val effectAmount: Money,
    override val fxSnapshotId: FxSnapshotId? = null,
    val kind: TransferKind = TransferKind.LOAN,
    val method: TransferMethod = TransferMethod.BANK,
    val accountLabel: String? = null,
    override val groupId: GroupId? = null,
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
) : Entry {
    override val entityType: EntityType get() = EntityType.TRANSFER
    override val paid: Money get() = amount
    override val effect: Money get() = effectAmount
    override val personIds: Set<PersonId> get() = setOf(fromPersonId, toPersonId)
    override fun withMeta(meta: SyncMeta): Transfer = copy(meta = meta)
}

/** Owned by its expense; versioned as part of the expense (spec §3.2). */
data class ExpenseShare(
    val personId: PersonId,
    val shareMinor: Long,
    val inputValue: Long? = null,
)

/**
 * Who paid how much (spec §3.2): owned and versioned with its expense like a share. [paidMinor] is in the
 * effect currency; [inputValue] is the EXACT amount in the paid currency (null for EQUAL).
 */
data class ExpensePayer(
    val personId: PersonId,
    val paidMinor: Long,
    val inputValue: Long? = null,
) {
    companion object {
        /**
         * The payer rows of a single-payer expense: [personId] paid the whole effect total, no input, `EQUAL`.
         * This is also what a schema-1 `payerPersonId` becomes (spec §3.2, §5.5).
         */
        fun single(personId: PersonId, effectTotalMinor: Long): List<ExpensePayer> = listOf(ExpensePayer(personId, effectTotalMinor))
    }
}

data class Expense(
    override val id: EntryId,
    override val groupId: GroupId? = null,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
    /** At least one; they sum to [effectTotal] (rule 0). */
    val payers: List<ExpensePayer>,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val effectTotal: Money,
    override val fxSnapshotId: FxSnapshotId? = null,
    val splitType: SplitType,
    val shares: List<ExpenseShare>,
    override val occurredOn: LocalDate,
    override val note: String? = null,
    override val meta: SyncMeta,
) : Entry {
    override val entityType: EntityType get() = EntityType.EXPENSE
    override val paid: Money get() = total
    override val effect: Money get() = effectTotal
    override val personIds: Set<PersonId> get() = payers.map { it.personId }.toSet() + shares.map { it.personId }
    override fun withMeta(meta: SyncMeta): Expense = copy(meta = meta)
}
