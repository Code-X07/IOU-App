package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

// Lossless conversions between domain records and Room rows. `fromX(toX(r)) == r` for every record.

private fun Instant.text(): String = toString()
private fun String.instant(): Instant = Instant.parse(this)
private fun LocalDate.text(): String = toString()
private fun String.date(): LocalDate = LocalDate.parse(this)

/** The sync columns of a row, in the order every row declares them. */
private class Meta(val createdAt: String, val updatedAt: String, val authorId: String, val deletedAt: String?, val version: Int, val importBatchId: String?) {
    fun toMeta() = SyncMeta(createdAt.instant(), updatedAt.instant(), PersonId(authorId), deletedAt?.instant(), version, importBatchId?.let(::ImportBatchId))
}

private fun SyncMeta.columns() =
    Meta(createdAt.text(), updatedAt.text(), authorId.value, deletedAt?.text(), version, importBatchId?.value)

fun Person.toRow(): PersonRow = meta.columns().let {
    PersonRow(id.value, displayName, isMe, note, defaultCurrency?.code, archivedAt?.text(), mergedIntoId?.value,
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun PersonRow.toPerson() = Person(
    id = PersonId(id), displayName = displayName, isMe = isMe, note = note, defaultCurrency = defaultCurrency?.let(::CurrencyCode),
    archivedAt = archivedAt?.instant(), mergedIntoId = mergedIntoId?.let(::PersonId),
    meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun PaymentHandle.toRow(): HandleRow = meta.columns().let {
    HandleRow(id.value, personId.value, type.name, value, holderName, bic, label, isDefault,
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun HandleRow.toHandle() = PaymentHandle(
    id = HandleId(id), personId = PersonId(personId), type = HandleType.valueOf(type), value = value, holderName = holderName,
    bic = bic, label = label, isDefault = isDefault, meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun Group.toRow(): GroupRow = meta.columns().let {
    GroupRow(id.value, name, defaultCurrency.code, fxMode.name, archivedAt?.text(),
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun GroupRow.toGroup() = Group(
    id = GroupId(id), name = name, defaultCurrency = CurrencyCode(defaultCurrency), fxMode = FxMode.valueOf(fxMode),
    archivedAt = archivedAt?.instant(), meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun GroupMember.toRow(): MemberRow = meta.columns().let {
    MemberRow(id.value, groupId.value, personId.value, joinedAt.text(), removedAt?.text(),
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun MemberRow.toMember() = GroupMember(
    id = MembershipId(id), groupId = GroupId(groupId), personId = PersonId(personId), joinedAt = joinedAt.instant(),
    removedAt = removedAt?.instant(), meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun Transfer.toRow(): TransferRow = meta.columns().let {
    TransferRow(id.value, fromPersonId.value, toPersonId.value, amount.amountMinor, amount.currency.code,
        effectAmount.amountMinor, effectAmount.currency.code, fxSnapshotId?.value, kind.name, method.name, accountLabel,
        groupId?.value, occurredOn.text(), note, it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun TransferRow.toTransfer() = Transfer(
    id = EntryId(id), fromPersonId = PersonId(fromPersonId), toPersonId = PersonId(toPersonId),
    amount = Money(amountMinor, CurrencyCode(currency)), effectAmount = Money(effectAmountMinor, CurrencyCode(effectCurrency)),
    fxSnapshotId = fxSnapshotId?.let(::FxSnapshotId), kind = TransferKind.valueOf(kind), method = TransferMethod.valueOf(method),
    accountLabel = accountLabel, groupId = groupId?.let(::GroupId), occurredOn = occurredOn.date(), note = note,
    meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun Expense.toRow(): ExpenseRow = meta.columns().let {
    ExpenseRow(id.value, groupId?.value, payers.single().personId.value, description, category?.name, total.amountMinor, total.currency.code,
        effectTotal.amountMinor, effectTotal.currency.code, fxSnapshotId?.value, splitType.name, occurredOn.text(), note,
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun Expense.shareRows(): List<ShareRow> =
    shares.mapIndexed { position, share -> ShareRow(id.value, share.personId.value, position, share.shareMinor, share.inputValue) }

/** [shares] are this expense's share rows, in any order. */
fun ExpenseRow.toExpense(shares: List<ShareRow>) = Expense(
    id = EntryId(id), groupId = groupId?.let(::GroupId), payers = ExpensePayer.single(PersonId(payerPersonId), effectTotalMinor),
    description = description,
    category = category?.let(Category::valueOf), total = Money(totalMinor, CurrencyCode(currency)),
    effectTotal = Money(effectTotalMinor, CurrencyCode(effectCurrency)), fxSnapshotId = fxSnapshotId?.let(::FxSnapshotId),
    splitType = SplitType.valueOf(splitType),
    shares = shares.sortedBy { it.position }.map { ExpenseShare(PersonId(it.personId), it.shareMinor, it.inputValue) },
    occurredOn = occurredOn.date(), note = note, meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)

fun FxSnapshot.toRow() = FxSnapshotRow(
    id.value, baseCurrency.code, quoteCurrency.code, rate, rateDate.text(), source.name, isStale, fetchedAt.text(), paidMinor, chargedMinor,
)

fun FxSnapshotRow.toSnapshot() = FxSnapshot(
    id = FxSnapshotId(id), baseCurrency = CurrencyCode(baseCurrency), quoteCurrency = CurrencyCode(quoteCurrency), rate = rate,
    rateDate = rateDate.date(), source = FxSource.valueOf(source), isStale = isStale, fetchedAt = fetchedAt.instant(),
    paidMinor = paidMinor, chargedMinor = chargedMinor,
)

fun ImportBatch.toRow() = ImportBatchRow(id.value, source.name, fileName, importedAt.text(), rowCount, undoneAt?.text())

fun ImportBatchRow.toBatch() = ImportBatch(
    ImportBatchId(id), ImportSource.valueOf(source), fileName, importedAt.instant(), rowCount, undoneAt?.instant(),
)
