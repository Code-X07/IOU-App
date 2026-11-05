package io.github.codex07.iou.io

import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.testutil.ME
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
        reader.read(Zips.edit(file, "manifest.json") { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") }).error() shouldBe
            LedgerError.SchemaTooNew(2, 1)
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
            },
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
