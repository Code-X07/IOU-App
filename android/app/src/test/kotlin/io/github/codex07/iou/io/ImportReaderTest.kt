package io.github.codex07.iou.io

import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.Zips
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ImportReaderTest {

    private val reader = ImportReader()

    private fun export(state: LedgerState, excelFriendly: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { LedgerExport("0.1.0").normalized(state, T0, excelFriendly, it) }.toByteArray()

    private fun decode(bytes: ByteArray, limits: ImportLimits = ImportLimits()): DecodedImport =
        ImportDecoder.decode((ImportReader(limits).read(bytes) as LedgerResult.Ok).value)

    private fun LedgerResult<ImportFile>.error() = (this as LedgerResult.Err).error

    @Test
    fun `an export decodes to the same records, except group rows until M2`() {
        val state = FakeApis.store(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("seed"))
            .also { runBlocking { SeedData.populate(it) } }
            .let { runBlocking { it.loaded().state } }

        for (excel in listOf(false, true)) {
            val decoded = decode(export(state, excel))
            decoded.ledger.people.map { it.record } shouldContainExactlyInAnyOrder state.people.values
            decoded.ledger.handles.map { it.record } shouldContainExactlyInAnyOrder state.handles.values
            decoded.ledger.fxSnapshots.map { it.record } shouldContainExactlyInAnyOrder state.fxSnapshots.values
            decoded.ledger.transfers.map { it.record } shouldContainExactlyInAnyOrder state.transfers.values.filter { it.groupId == null }
            decoded.ledger.expenses.map { it.record } shouldContainExactlyInAnyOrder state.expenses.values.filter { it.groupId == null }

            val grouped = state.groups.size + state.members.size + state.entries.count { it.groupId != null }
            decoded.invalid.size shouldBe grouped
            decoded.invalid.map { it.reason }.toSet() shouldBe setOf(ImportDecoder.GROUPS_IN_M2)
        }
    }

    @Test
    fun `text a spreadsheet would run comes back exactly`() {
        val id = EntryId("=t-1")
        val state = TestLedger().run {
            me()
            val x = person("X", id = "'person")
            transfer(ME, x, eur(1_000), id = id.value)
            state
        }.let { s -> s.with(s.transfers.getValue(id).let { it.copy(note = "=HYPERLINK(\"http://evil\",\"x\")", accountLabel = "'quoted", meta = it.meta.copy(authorId = PersonId("＠author"))) }) }

        for (excel in listOf(false, true)) {
            val transfer = decode(export(state, excel)).ledger.transfers.single()
            transfer.record.id shouldBe id
            transfer.record.toPersonId shouldBe PersonId("'person")
            transfer.record.meta.authorId shouldBe PersonId("＠author")
            transfer.record.note shouldBe "=HYPERLINK(\"http://evil\",\"x\")"
            transfer.record.accountLabel shouldBe "'quoted"
            transfer.row shouldBe 2
            transfer.sourceVersion shouldBe 1
        }
    }

    @Test
    fun `files that aren't own exports are rejected`() {
        val file = export(TestLedger().apply { me() }.state)
        reader.read("not a zip".toByteArray()).error() shouldBe LedgerError.CorruptFile("Not an IOU export: no manifest.json")
        reader.read(Zips.write(mapOf("people.csv" to "id\n"))).error() shouldBe LedgerError.CorruptFile("Not an IOU export: no manifest.json")
        reader.read(Zips.edit(file, "manifest.json") { it.replace("\"schemaVersion\":2", "\"schemaVersion\":3") }).error() shouldBe
            LedgerError.SchemaTooNew(3, 2)
        reader.read(Zips.edit(file, "manifest.json") { it.replace("quote-prefix-v1", "none") }).error() shouldBe
            LedgerError.CorruptFile("Unknown text escaping none")
        reader.read(Zips.edit(file, "manifest.json") { it.replace("iou-normalized-csv", "other") }).error() shouldBe
            LedgerError.CorruptFile("Not an IOU normalized export")

        val duplicateZip = Zips.duplicateName(file, "unknown1", "unknown2")
        reader.read(duplicateZip).error()::class shouldBe LedgerError.CorruptFile::class
        val duplicateHeader = Zips.edit(file, "people.csv") { it.replaceFirst(",", ",id,") }
        reader.read(duplicateHeader).error()::class shouldBe LedgerError.CorruptFile::class
        val malformed = Zips.edit(file, "people.csv") { it + "\"unterminated" }
        reader.read(malformed).error()::class shouldBe LedgerError.CorruptFile::class
    }

    @Test
    fun `a file that doesn't hold what its manifest counts is rejected`() {
        val file = export(TestLedger().apply { val me = me(); transfer(me, person("X"), eur(500)) }.state)
        (reader.read(file) is LedgerResult.Ok) shouldBe true
        fun without(name: String) = Zips.write(Zips.read(file).also { it.remove(name) })

        reader.read(without("transfers.csv")).error() shouldBe LedgerError.CorruptFile("Missing transfers.csv")
        // An empty table must be present as well: an export writes every table.
        reader.read(without("expenses.csv")).error() shouldBe LedgerError.CorruptFile("Missing expenses.csv")
        val dropped = Zips.edit(file, "transfers.csv") { it.lines().first() + "\r\n" }
        reader.read(dropped).error() shouldBe LedgerError.CorruptFile("transfers.csv has 0 data rows, manifest.json says 1")
        val uncounted = Zips.edit(file, "manifest.json") { it.replace(Regex(",?\"transfers\":\\d+"), "") }
        reader.read(uncounted).error() shouldBe LedgerError.CorruptFile("manifest.json has no row count for transfers")
    }

    @Test
    fun `payer rows decode in position order, and orphans and bad rows are reported`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.expense(
            ME, eur(10_000), mapOf(ME to 5_000L, x to 5_000L), id = "e-1", payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(x, 7_000, 7_000), ExpensePayer(ME, 3_000, 3_000)),
        )
        val file = export(ledger.state)

        decode(file).ledger.expenses.single().record shouldBe ledger.state.expenses.values.single()

        val orphan = Zips.write(
            Zips.read(file).also { entries ->
                entries["expense_payers.csv"] = entries.getValue("expense_payers.csv") + "e-404,0,person-x,100,1.00,\r\n"
                entries["manifest.json"] = entries.getValue("manifest.json").replace("\"expense_payers\":2", "\"expense_payers\":3")
            },
        )
        decode(orphan).invalid shouldBe listOf(
            InvalidRow("expense_payers", 4, "payer of an expense that isn't in the file", LedgerError.CorruptData("orphan payer")),
        )

        val badPosition = Zips.edit(file, "expense_payers.csv") { it.replace("e-1,0,person-x", "e-1,x,person-x") }
        decode(badPosition).invalid shouldBe listOf(
            InvalidRow("expense_payers", 2, "position: not a whole number", LedgerError.InvalidField("position")),
        )
    }

    @Test
    fun `a schema-1 export reads with one EQUAL payer per expense`() {
        val v1 = Zips.write(
            linkedMapOf(
                "people.csv" to "id,display_name,is_me,note,default_currency,archived_at,merged_into_id,version,created_at,updated_at,author_id,deleted_at\r\n" +
                    "person-0-me,Me,true,,,,,1,2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,person-0-me,\r\n" +
                    "person-x,X,false,,,,,1,2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,person-0-me,\r\n",
                "payment_handles.csv" to "id,person_id,type,value,holder_name,bic,label,is_default,version,created_at,updated_at,author_id,deleted_at\r\n",
                "groups.csv" to "id,name,default_currency,fx_mode,archived_at,version,created_at,updated_at,author_id,deleted_at\r\n",
                "group_members.csv" to "id,group_id,person_id,joined_at,removed_at,version,created_at,updated_at,author_id,deleted_at\r\n",
                "fx_snapshots.csv" to "id,base_currency,quote_currency,rate,rate_date,source,is_stale,fetched_at,paid_minor,charged_minor\r\n",
                "transfers.csv" to "id,from_person_id,to_person_id,amount_minor,amount,currency,effect_amount_minor,effect_amount," +
                    "effect_currency,fx_snapshot_id,kind,method,account_label,group_id,occurred_on,note,version,created_at,updated_at,author_id,deleted_at\r\n",
                "expenses.csv" to "id,group_id,payer_person_id,description,category,total_minor,total,currency,effect_total_minor," +
                    "effect_total,effect_currency,fx_snapshot_id,split_type,occurred_on,note,version,created_at,updated_at,author_id,deleted_at\r\n" +
                    "e-1,,'=person-x,Dinner,,3000,30.00,EUR,3000,30.00,EUR,,EQUAL,2026-01-02,,1,2026-01-01T00:00:00Z," +
                    "2026-01-01T00:00:00Z,person-0-me,\r\n",
                "expense_shares.csv" to "expense_id,position,person_id,share_minor,share,input_value\r\n" +
                    "e-1,0,person-0-me,1500,15.00,\r\n" +
                    "e-1,1,'=person-x,1500,15.00,\r\n",
                "manifest.json" to """{"format":"iou-normalized-csv","schemaVersion":1,"appVersion":"0.1.0",""" +
                    """"exportedAt":"2026-01-01T00:00:00Z","scope":"full-ledger","textEscaping":"quote-prefix-v1","delimiter":",",""" +
                    """"bom":false,"tables":{"people":2,"payment_handles":0,"groups":0,"group_members":0,"fx_snapshots":0,""" +
                    """"transfers":0,"expenses":1,"expense_shares":2}}""",
            ),
        )

        val expense = decode(v1).ledger.expenses.single().record

        expense.payerSplit shouldBe PayerSplit.EQUAL
        expense.payers shouldBe listOf(ExpensePayer(io.github.codex07.iou.ledger.model.PersonId("=person-x"), 3_000))
        reader.read(Zips.edit(v1, "manifest.json") { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") }).error() shouldBe
            LedgerError.CorruptFile("Missing recurring_expenses.csv")
    }

    @Test
    fun `posted expenses keep their schedule link`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val rent = ledger.schedule(x, eur(1_000), listOf(ME, x), id = "rent")
        ledger.expense(x, eur(1_000), mapOf(ME to 500L, x to 500L), id = "e-1", recurring = rent, recurringOn = D0)

        val expense = decode(export(ledger.state)).ledger.expenses.single().record

        expense shouldBe ledger.state.expenses.values.single()
        expense.recurringId shouldBe rent
        expense.recurringOn shouldBe D0
    }

    @Test
    fun `limits are enforced`() {
        val file = export(TestLedger().apply { me(); person("X"); person("Y") }.state)
        fun limited(limits: ImportLimits) = (ImportReader(limits).read(file) as LedgerResult.Err).error::class
        limited(ImportLimits(maxFileBytes = file.size - 1L)) shouldBe LedgerError.LimitsExceeded::class
        limited(ImportLimits(maxEntries = 3)) shouldBe LedgerError.LimitsExceeded::class
        limited(ImportLimits(maxRows = 2)) shouldBe LedgerError.LimitsExceeded::class
        (ImportReader(ImportLimits(maxRows = 3)).read(file) is LedgerResult.Ok) shouldBe true
        val malformedTail = Zips.edit(file, "people.csv") { it + "\"unterminated" }
        ImportReader(ImportLimits(maxRows = 2)).read(malformedTail).error()::class shouldBe LedgerError.LimitsExceeded::class
        limited(ImportLimits(maxUncompressedBytes = 100)) shouldBe LedgerError.LimitsExceeded::class

        val bomb = Zips.write(mapOf("manifest.json" to " ".repeat(2_000_000)))
        (reader.read(bomb) as LedgerResult.Err).error::class shouldBe LedgerError.LimitsExceeded::class
    }

    @Test
    fun `rows that don't decode are reported with their table, row number and reason`() {
        val file = export(TestLedger().run {
            me()
            val x = person("X")
            transfer(ME, x, eur(1_000), id = "t-1")
            transfer(ME, x, eur(2_000), id = "t-2")
            transfer(ME, x, eur(3_000), id = "t-3")
            expense(ME, eur(900), mapOf(ME to 450L, x to 450L), id = "e-1")
            state
        })
        val edited = Zips.write(
            Zips.read(file).also { entries ->
                entries["transfers.csv"] = entries.getValue("transfers.csv")
                    .replace("t-1,person-0-me,person-x,1000,10.00,EUR", "t-1,person-0-me,person-x,1000,10.001,EUR")
                    .replace("t-2,person-0-me,person-x,2000,20.00,EUR", "t-2,person-0-me,person-x,2001,20.00,EUR")
                    .replace("t-3,person-0-me,person-x,3000,30.00,EUR", "t-3,person-0-me,person-x,3000,30.00,XXX")
                entries["expense_shares.csv"] = entries.getValue("expense_shares.csv") + "e-404,0,person-x,100,1.00,\r\n"
                entries["people.csv"] = entries.getValue("people.csv").let { it + it.lines()[2] + "\r\n" }
                // The manifest counts the added rows, as it would for a consistent file.
                entries["manifest.json"] = entries.getValue("manifest.json")
                    .replace("\"people\":2", "\"people\":3").replace("\"expense_shares\":2", "\"expense_shares\":3")
            },
        )
        val parsed = (reader.read(file) as LedgerResult.Ok).value
        val shares = parsed.rows(NormalizedTables.expenseShares)
        fun withShares(rows: List<CsvRow>) = parsed.copy(tables = parsed.tables + (NormalizedTables.expenseShares.name to rows))
        fun changed(row: CsvRow, column: String, value: String) = row.copy(cells = row.cells + (column to value))
        for (position in listOf("", "bad")) {
            val result = ImportDecoder.decode(withShares(listOf(changed(shares.first(), "position", position))))
            result.ledger.expenses shouldBe emptyList()
            result.invalid shouldBe listOf(
                InvalidRow("expense_shares", 2, if (position.isEmpty()) "position: missing" else "position: not a whole number", LedgerError.InvalidField("position")),
            )
        }
        val badShares = ImportDecoder.decode(withShares(listOf(
            changed(shares[0], "position", "bad"),
            changed(shares[1], "person_id", ""),
        )))
        badShares.ledger.expenses shouldBe emptyList()
        badShares.invalid shouldContainExactlyInAnyOrder listOf(
            InvalidRow("expense_shares", 2, "position: not a whole number", LedgerError.InvalidField("position")),
            InvalidRow("expense_shares", 3, "person_id: missing", LedgerError.InvalidField("person_id")),
        )

        decode(edited).invalid shouldContainExactlyInAnyOrder listOf(
            InvalidRow("transfers", 2, "amount: invalid amount 10.001", LedgerError.TooManyDecimals(2)),
            InvalidRow("transfers", 3, "amount: 20.00 doesn't match amount_minor 2001", LedgerError.CorruptData("amount mismatch")),
            InvalidRow("transfers", 4, "currency: unknown currency XXX", LedgerError.UnknownCurrency("XXX")),
            InvalidRow("expense_shares", 4, "share of an expense that isn't in the file", LedgerError.CorruptData("orphan share")),
            InvalidRow("people", 4, "duplicate id person-x", LedgerError.CorruptData("duplicate id person-x")),
        )
    }
}
