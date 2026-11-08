package io.github.codex07.iou.io

import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.Money

/** One column: its header, whether it holds escaped string text, including opaque IDs (spec §7.2), and its value for a record. */
class Column<in T>(val name: String, val text: Boolean = false, val value: (T) -> String?)

/**
 * One CSV file of the normalized export: `<name>.csv` with [columns] as its header. [legacyTextColumns] are escaped
 * text columns that only older schemas have (e.g. `payer_person_id` in a schema-1 `expenses.csv`); the import
 * unescapes them too.
 */
class CsvTable<T>(
    val name: String,
    val columns: List<Column<T>>,
    val legacyTextColumns: Set<String> = emptySet(),
    val rows: (LedgerState) -> List<T>,
) {
    val fileName: String get() = "$name.csv"
    val header: List<String> get() = columns.map { it.name }

    /** The cells of [record]: null stays an empty cell; text is escaped. */
    fun cells(record: T): List<String?> = columns.map { column ->
        column.value(record)?.let { if (column.text) TextEscaping.escape(it) else it }
    }
}

/** An expense share with the expense it belongs to and its position in the expense's share list. */
data class ShareLine(val expense: Expense, val position: Int, val share: ExpenseShare)

/** An expense payer with the expense it belongs to and its position in the expense's payer list. */
data class PayerLine(val expense: Expense, val position: Int, val payer: ExpensePayer)

/**
 * The normalized shape (spec §7.2): one table per entity, every row including deleted ones and the me person.
 * Amounts appear as `*_minor` integers next to decimal strings; instants are ISO-8601 UTC, dates `yyyy-MM-dd`,
 * enums by name, booleans `true`/`false`. The sync columns are the same for every entity table.
 */
object NormalizedTables {
    /** 2 from M2: `expense_payers` and `expenses.payer_split` replace `expenses.payer_person_id`. */
    const val SCHEMA_VERSION = 2

    private fun <T : LedgerEntity> sync(): List<Column<T>> = listOf(
        Column("version") { it.meta.version.toString() },
        Column("created_at") { it.meta.createdAt.toString() },
        Column("updated_at") { it.meta.updatedAt.toString() },
        Column("author_id", text = true) { it.meta.authorId.value },
        Column("deleted_at") { it.meta.deletedAt?.toString() },
    )

    private fun <T> money(prefix: String, currencyColumn: String, amount: (T) -> Money): List<Column<T>> = listOf(
        Column("${prefix}_minor") { amount(it).amountMinor.toString() },
        Column(prefix) { amount(it).toPlainString() },
        Column(currencyColumn) { amount(it).currency.code },
    )

    val people = CsvTable(
        "people",
        listOf<Column<Person>>(
            Column("id", text = true) { it.id.value },
            Column("display_name", text = true) { it.displayName },
            Column("is_me") { it.isMe.toString() },
            Column("note", text = true) { it.note },
            Column("default_currency") { it.defaultCurrency?.code },
            Column("archived_at") { it.archivedAt?.toString() },
            Column("merged_into_id", text = true) { it.mergedIntoId?.value },
        ) + sync(),
    ) { it.people.values.toList() }

    val paymentHandles = CsvTable(
        "payment_handles",
        listOf<Column<PaymentHandle>>(
            Column("id", text = true) { it.id.value },
            Column("person_id", text = true) { it.personId.value },
            Column("type") { it.type.name },
            Column("value", text = true) { it.value },
            Column("holder_name", text = true) { it.holderName },
            Column("bic", text = true) { it.bic },
            Column("label", text = true) { it.label },
            Column("is_default") { it.isDefault.toString() },
        ) + sync(),
    ) { it.handles.values.toList() }

    val groups = CsvTable(
        "groups",
        listOf<Column<Group>>(
            Column("id", text = true) { it.id.value },
            Column("name", text = true) { it.name },
            Column("default_currency") { it.defaultCurrency.code },
            Column("fx_mode") { it.fxMode.name },
            Column("archived_at") { it.archivedAt?.toString() },
        ) + sync(),
    ) { it.groups.values.toList() }

    val groupMembers = CsvTable(
        "group_members",
        listOf<Column<GroupMember>>(
            Column("id", text = true) { it.id.value },
            Column("group_id", text = true) { it.groupId.value },
            Column("person_id", text = true) { it.personId.value },
            Column("joined_at") { it.joinedAt.toString() },
            Column("removed_at") { it.removedAt?.toString() },
        ) + sync(),
    ) { it.members.values.toList() }

    val transfers = CsvTable(
        "transfers",
        listOf<Column<Transfer>>(
            Column("id", text = true) { it.id.value },
            Column("from_person_id", text = true) { it.fromPersonId.value },
            Column("to_person_id", text = true) { it.toPersonId.value },
        ) + money("amount", "currency") { it.amount } + money("effect_amount", "effect_currency") { it.effectAmount } + listOf(
            Column("fx_snapshot_id", text = true) { it.fxSnapshotId?.value },
            Column("kind") { it.kind.name },
            Column("method") { it.method.name },
            Column("account_label", text = true) { it.accountLabel },
            Column("group_id", text = true) { it.groupId?.value },
            Column("occurred_on") { it.occurredOn.toString() },
            Column("note", text = true) { it.note },
        ) + sync(),
    ) { it.transfers.values.toList() }

    val expenses = CsvTable(
        "expenses",
        listOf<Column<Expense>>(
            Column("id", text = true) { it.id.value },
            Column("group_id", text = true) { it.groupId?.value },
            Column("payer_split") { it.payerSplit.name },
            Column("description", text = true) { it.description },
            Column("category") { it.category?.name },
        ) + money("total", "currency") { it.total } + money("effect_total", "effect_currency") { it.effectTotal } + listOf(
            Column("fx_snapshot_id", text = true) { it.fxSnapshotId?.value },
            Column("split_type") { it.splitType.name },
            Column("occurred_on") { it.occurredOn.toString() },
            Column("note", text = true) { it.note },
        ) + sync(),
        legacyTextColumns = setOf(SCHEMA_1_PAYER),
    ) { it.expenses.values.toList() }

    val expenseShares = CsvTable(
        "expense_shares",
        listOf<Column<ShareLine>>(
            Column("expense_id", text = true) { it.expense.id.value },
            Column("position") { it.position.toString() },
            Column("person_id", text = true) { it.share.personId.value },
            Column("share_minor") { it.share.shareMinor.toString() },
            Column("share") { Money(it.share.shareMinor, it.expense.effectTotal.currency).toPlainString() },
            Column("input_value") { it.share.inputValue?.toString() },
        ),
    ) { state -> state.expenses.values.flatMap { expense -> expense.shares.mapIndexed { i, share -> ShareLine(expense, i, share) } } }

    val expensePayers = CsvTable(
        "expense_payers",
        listOf<Column<PayerLine>>(
            Column("expense_id", text = true) { it.expense.id.value },
            Column("position") { it.position.toString() },
            Column("person_id", text = true) { it.payer.personId.value },
            Column("paid_minor") { it.payer.paidMinor.toString() },
            Column("paid") { Money(it.payer.paidMinor, it.expense.effectTotal.currency).toPlainString() },
            Column("input_value") { it.payer.inputValue?.toString() },
        ),
    ) { state -> state.expenses.values.flatMap { expense -> expense.payers.mapIndexed { i, payer -> PayerLine(expense, i, payer) } } }

    val fxSnapshots = CsvTable(
        "fx_snapshots",
        listOf<Column<FxSnapshot>>(
            Column("id", text = true) { it.id.value },
            Column("base_currency") { it.baseCurrency.code },
            Column("quote_currency") { it.quoteCurrency.code },
            Column("rate") { it.rate },
            Column("rate_date") { it.rateDate.toString() },
            Column("source") { it.source.name },
            Column("is_stale") { it.isStale.toString() },
            Column("fetched_at") { it.fetchedAt.toString() },
            Column("paid_minor") { it.paidMinor?.toString() },
            Column("charged_minor") { it.chargedMinor?.toString() },
        ),
    ) { it.fxSnapshots.values.toList() }

    /** Schema 1's expense column naming the one payer (spec §7.4); schema-1 imports turn it into a payer row. */
    const val SCHEMA_1_PAYER = "payer_person_id"

    /** In dependency order, which is also the order an import applies them (spec §7.5). */
    val all: List<CsvTable<*>> =
        listOf(people, paymentHandles, groups, groupMembers, fxSnapshots, transfers, expenses, expenseShares, expensePayers)

    /** The tables a file of [schemaVersion] holds: every one of them, and no others (spec §7.4). */
    fun tablesOf(schemaVersion: Int): List<CsvTable<*>> = if (schemaVersion >= 2) all else all - expensePayers
}
