package io.github.codex07.iou.io

import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.toLedgerError
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyParseResult
import io.github.codex07.iou.money.MoneyParser
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** An incoming record, the row it came from and the version it had in the source. */
data class Incoming<out T>(val table: String, val row: Int, val record: T, val sourceVersion: Int)

/** Every record of an import that decoded. Expenses carry their shares, in `position` order. */
data class IncomingLedger(
    val people: List<Incoming<Person>> = emptyList(),
    val handles: List<Incoming<PaymentHandle>> = emptyList(),
    val fxSnapshots: List<Incoming<FxSnapshot>> = emptyList(),
    val transfers: List<Incoming<Transfer>> = emptyList(),
    val expenses: List<Incoming<Expense>> = emptyList(),
)

/** The decoded records and the rows that didn't decode. */
data class DecodedImport(val ledger: IncomingLedger, val invalid: List<InvalidRow>)

/**
 * Turns the rows of an [ImportFile] into records (spec §7.5 step 2–3, per row). Every problem becomes an
 * [InvalidRow] with its table, row number and reason; nothing is thrown. Amounts are parsed from their
 * decimal strings with [MoneyParser] and must match their `*_minor` column. Group rows, memberships and
 * entries in a group are invalid until M2 (index, locked decision 6).
 */
object ImportDecoder {

    const val GROUPS_IN_M2 = "group rows are imported from M2 on"

    private class RowError(val reason: String, val error: LedgerError?) : Exception(reason)

    private class ShareErrors(val rows: List<InvalidRow>) : Exception()

    fun decode(file: ImportFile): DecodedImport {
        val invalid = mutableListOf<InvalidRow>()

        fun <T> table(table: CsvTable<*>, id: (CsvRow) -> String, decode: RowReader.() -> T): List<Incoming<T>> {
            val seen = mutableSetOf<String>()
            return file.rows(table).mapNotNull { row ->
                try {
                    val reader = RowReader(row)
                    val rowId = id(row)
                    if (!seen.add(rowId)) throw RowError("duplicate id $rowId", LedgerError.CorruptData("duplicate id $rowId"))
                    Incoming(table.name, row.number, reader.decode(), reader.sourceVersion())
                } catch (e: ShareErrors) {
                    invalid += e.rows
                    null
                } catch (e: RowError) {
                    invalid += InvalidRow(table.name, row.number, e.reason, e.error)
                    null
                }
            }
        }

        for (table in listOf(NormalizedTables.groups, NormalizedTables.groupMembers)) {
            file.rows(table).forEach { invalid += InvalidRow(table.name, it.number, GROUPS_IN_M2, null) }
        }

        val people = table(NormalizedTables.people, { it.cells["id"].orEmpty() }) { person() }
        val handles = table(NormalizedTables.paymentHandles, { it.cells["id"].orEmpty() }) { handle() }
        val snapshots = table(NormalizedTables.fxSnapshots, { it.cells["id"].orEmpty() }) { snapshot() }
        val transfers = table(NormalizedTables.transfers, { it.cells["id"].orEmpty() }) { transfer() }

        val shareRows = file.rows(NormalizedTables.expenseShares).groupBy { it.cells["expense_id"].orEmpty() }
        val expenseIds = file.rows(NormalizedTables.expenses).map { it.cells["id"].orEmpty() }.toSet()
        for ((expenseId, rows) in shareRows) {
            if (expenseId !in expenseIds) {
                rows.forEach {
                    invalid += InvalidRow(NormalizedTables.expenseShares.name, it.number, "share of an expense that isn't in the file", LedgerError.CorruptData("orphan share"))
                }
            }
        }
        val payerRows = file.rows(NormalizedTables.expensePayers).groupBy { it.cells["expense_id"].orEmpty() }
        for ((expenseId, rows) in payerRows) {
            if (expenseId !in expenseIds) {
                rows.forEach {
                    invalid += InvalidRow(NormalizedTables.expensePayers.name, it.number, "payer of an expense that isn't in the file", LedgerError.CorruptData("orphan payer"))
                }
            }
        }
        val expenses = table(NormalizedTables.expenses, { it.cells["id"].orEmpty() }) {
            val id = cells["id"].orEmpty()
            expense(shareRows[id].orEmpty(), payerRows[id].orEmpty(), file.schemaVersion)
        }
        return DecodedImport(IncomingLedger(people, handles, snapshots, transfers, expenses), invalid)
    }

    /** Reads typed values from one row; every failure is a [RowError] naming the column. */
    private class RowReader(row: CsvRow) {
        val cells = row.cells

        private fun fail(column: String, problem: String, error: LedgerError? = LedgerError.InvalidField(column)): Nothing =
            throw RowError("$column: $problem", error)

        fun optional(column: String): String? = cells[column]?.takeIf { it.isNotEmpty() }

        fun required(column: String): String = optional(column) ?: fail(column, "missing")

        fun long(column: String): Long = required(column).toLongOrNull() ?: fail(column, "not a whole number")

        fun optionalLong(column: String): Long? = optional(column)?.let { it.toLongOrNull() ?: fail(column, "not a whole number") }

        fun bool(column: String): Boolean = when (required(column)) {
            "true" -> true
            "false" -> false
            else -> fail(column, "not true or false")
        }

        fun instant(column: String): Instant = parseInstant(column, required(column))

        fun optionalInstant(column: String): Instant? = optional(column)?.let { parseInstant(column, it) }

        private fun parseInstant(column: String, text: String): Instant =
            try { Instant.parse(text) } catch (_: DateTimeParseException) { fail(column, "not an ISO-8601 instant") }

        fun date(column: String): LocalDate =
            try { LocalDate.parse(required(column)) } catch (_: DateTimeParseException) { fail(column, "not a yyyy-MM-dd date") }

        fun currency(column: String): CurrencyCode = required(column).let { code ->
            CurrencyCode.parse(code) ?: fail(column, "unknown currency $code", LedgerError.UnknownCurrency(code))
        }

        fun optionalCurrency(column: String): CurrencyCode? = optional(column)?.let { currency(column) }

        inline fun <reified E : Enum<E>> enum(column: String): E = required(column).let { name ->
            enumValues<E>().firstOrNull { it.name == name } ?: fail(column, "unknown value $name")
        }

        inline fun <reified E : Enum<E>> optionalEnum(column: String): E? = optional(column)?.let { enum<E>(column) }

        /** `<prefix>` as a decimal string in [currencyColumn], checked against `<prefix>_minor`. */
        fun money(prefix: String, currencyColumn: String): Money = amount(prefix, required(prefix), currency(currencyColumn), long("${prefix}_minor"))

        fun amount(column: String, text: String, currency: CurrencyCode, minor: Long): Money {
            val money = when (val parsed = MoneyParser.parse(text, currency)) {
                is MoneyParseResult.Ok -> parsed.money
                is MoneyParseResult.Err -> fail(column, "invalid amount $text", parsed.error.toLedgerError())
            }
            if (money.amountMinor != minor) fail(column, "$text doesn't match ${column}_minor $minor", LedgerError.CorruptData("$column mismatch"))
            return money
        }

        fun meta(): SyncMeta = SyncMeta(
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
            authorId = PersonId(required("author_id")),
            deletedAt = optionalInstant("deleted_at"),
            version = sourceVersion(),
        )

        fun sourceVersion(): Int = cells["version"]?.let { text ->
            text.toIntOrNull()?.takeIf { it >= 1 } ?: fail("version", "not a positive whole number")
        } ?: 1

        fun noGroup() {
            if (optional("group_id") != null) throw RowError(GROUPS_IN_M2, null)
        }

        fun person() = Person(
            id = PersonId(required("id")),
            displayName = required("display_name"),
            isMe = bool("is_me"),
            note = optional("note"),
            defaultCurrency = optionalCurrency("default_currency"),
            archivedAt = optionalInstant("archived_at"),
            mergedIntoId = optional("merged_into_id")?.let(::PersonId),
            meta = meta(),
        )

        fun handle() = PaymentHandle(
            id = HandleId(required("id")),
            personId = PersonId(required("person_id")),
            type = enum<HandleType>("type"),
            value = required("value"),
            holderName = optional("holder_name"),
            bic = optional("bic"),
            label = required("label"),
            isDefault = bool("is_default"),
            meta = meta(),
        )

        fun snapshot(): FxSnapshot {
            val rate = required("rate")
            if (FxConverter.validateRate(rate) is LedgerResult.Err) fail("rate", "invalid rate $rate", LedgerError.InvalidRate(rate))
            return FxSnapshot(
                id = FxSnapshotId(required("id")),
                baseCurrency = currency("base_currency"),
                quoteCurrency = currency("quote_currency"),
                rate = rate,
                rateDate = date("rate_date"),
                source = enum<FxSource>("source"),
                isStale = bool("is_stale"),
                fetchedAt = instant("fetched_at"),
                paidMinor = optionalLong("paid_minor"),
                chargedMinor = optionalLong("charged_minor"),
            )
        }

        fun transfer(): Transfer {
            noGroup()
            return Transfer(
                id = EntryId(required("id")),
                fromPersonId = PersonId(required("from_person_id")),
                toPersonId = PersonId(required("to_person_id")),
                amount = money("amount", "currency"),
                effectAmount = money("effect_amount", "effect_currency"),
                fxSnapshotId = optional("fx_snapshot_id")?.let(::FxSnapshotId),
                kind = enum<TransferKind>("kind"),
                method = enum<TransferMethod>("method"),
                accountLabel = optional("account_label"),
                occurredOn = date("occurred_on"),
                note = optional("note"),
                meta = meta(),
            )
        }

        /**
         * An expense with its owned rows. A schema-1 file names one payer in `payer_person_id`, which becomes one EQUAL
         * payer paying the effect total (spec §7.4); a schema-2 file has `payer_split` and `expense_payers` rows.
         */
        fun expense(shareRows: List<CsvRow>, payerRows: List<CsvRow>, schemaVersion: Int): Expense {
            noGroup()
            val effectTotal = money("effect_total", "effect_currency")
            val errors = mutableListOf<InvalidRow>()
            val payers = if (schemaVersion < 2) {
                ExpensePayer.single(PersonId(required(NormalizedTables.SCHEMA_1_PAYER)), effectTotal.amountMinor)
            } else {
                payerRows.mapNotNull { row ->
                    val payer = RowReader(row)
                    try {
                        payer.long("position") to ExpensePayer(
                            personId = PersonId(payer.required("person_id")),
                            paidMinor = payer.amount("paid", payer.required("paid"), effectTotal.currency, payer.long("paid_minor")).amountMinor,
                            inputValue = payer.optionalLong("input_value"),
                        )
                    } catch (e: RowError) {
                        errors += InvalidRow(NormalizedTables.expensePayers.name, row.number, e.reason, e.error)
                        null
                    }
                }.sortedBy { it.first }.map { it.second }
            }
            val shares = shareRows.mapNotNull { row ->
                val share = RowReader(row)
                try {
                    val position = share.long("position")
                    position to ExpenseShare(
                        personId = PersonId(share.required("person_id")),
                        shareMinor = share.amount("share", share.required("share"), effectTotal.currency, share.long("share_minor")).amountMinor,
                        inputValue = share.optionalLong("input_value"),
                    )
                } catch (e: RowError) {
                    errors += InvalidRow(NormalizedTables.expenseShares.name, row.number, e.reason, e.error)
                    null
                }
            }
            if (errors.isNotEmpty()) throw ShareErrors(errors)
            return Expense(
                id = EntryId(required("id")),
                payerSplit = if (schemaVersion < 2) PayerSplit.EQUAL else enum<PayerSplit>("payer_split"),
                payers = payers,
                description = required("description"),
                category = optionalEnum<Category>("category"),
                total = money("total", "currency"),
                effectTotal = effectTotal,
                fxSnapshotId = optional("fx_snapshot_id")?.let(::FxSnapshotId),
                splitType = enum<SplitType>("split_type"),
                shares = shares.sortedBy { it.first }.map { it.second },
                occurredOn = date("occurred_on"),
                note = optional("note"),
                meta = meta(),
            )
        }
    }
}
