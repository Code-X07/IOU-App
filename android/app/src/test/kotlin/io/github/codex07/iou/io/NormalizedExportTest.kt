package io.github.codex07.iou.io

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.junit.jupiter.api.Test

class NormalizedExportTest {

    private val export = LedgerExport("0.1.0")
    private val transfer = EntryId("transfer-a")
    private val state = TestLedger().run {
        me()
        val x = person("X")
        person("Alias", mergedInto = x)
        handle(x, deleted = true)
        transfer(ME, x, eur(1_000), id = transfer.value)
        transfer(x, ME, eur(500), deleted = true, id = "transfer-b")
        state
    }.let { s ->
        val t = s.transfers.getValue(transfer)
        s.with(t.copy(note = "=HYPERLINK(\"http://evil\",\"x\")", accountLabel = "'quoted"))
    }

    /** The zip's entries, in order, as text. */
    private fun export(excelFriendly: Boolean = false): Pair<Int, LinkedHashMap<String, String>> {
        val out = ByteArrayOutputStream()
        val rows = export.normalized(state, T0, excelFriendly, out)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return rows to entries
    }

    @Test
    fun `the zip has every table and the manifest`() {
        val (rows, entries) = export()
        entries.keys.toList() shouldContainExactly listOf(
            "people.csv", "payment_handles.csv", "groups.csv", "group_members.csv", "fx_snapshots.csv",
            "transfers.csv", "expenses.csv", "expense_shares.csv", "manifest.json",
        )
        rows shouldBe 6
        val manifest = Json.decodeFromString(ExportManifest.serializer(), entries.getValue("manifest.json"))
        manifest shouldBe ExportManifest(
            format = "iou-normalized-csv", schemaVersion = 1, appVersion = "0.1.0", exportedAt = "2026-01-01T00:00:00Z",
            scope = "full-ledger", textEscaping = "quote-prefix-v1", delimiter = ",", bom = false,
            tables = mapOf(
                "people" to 3, "payment_handles" to 1, "groups" to 0, "group_members" to 0, "fx_snapshots" to 0,
                "transfers" to 2, "expenses" to 0, "expense_shares" to 0,
            ),
        )
    }

    @Test
    fun `rows are complete, deleted and merged ones included, with text escaped and quoted`() {
        val (_, entries) = export()
        entries.getValue("people.csv").lines().map { it.substringBefore(',') } shouldContainExactly
            listOf("id", "person-0-me", "person-x", "person-alias", "")
        entries.getValue("transfers.csv") shouldBe
            "id,from_person_id,to_person_id,amount_minor,amount,currency,effect_amount_minor,effect_amount,effect_currency," +
            "fx_snapshot_id,kind,method,account_label,group_id,occurred_on,note,version,created_at,updated_at,author_id,deleted_at\r\n" +
            "$transfer,person-0-me,person-x,1000,10.00,EUR,1000,10.00,EUR,,LOAN,BANK,''quoted,,2026-01-01," +
            "\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",1,2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,person-0-me,\r\n" +
            "transfer-b,person-x,person-0-me,500,5.00,EUR,500,5.00,EUR,,LOAN,BANK,,,2026-01-01,,1,2026-01-01T00:00:00Z," +
            "2026-01-01T00:00:00Z,person-0-me,2026-01-01T00:00:00Z\r\n"

        val original = state.transfers.getValue(transfer)
        val risky = original.copy(
            id = EntryId("=entry"), fromPersonId = PersonId("'person"),
            toPersonId = PersonId("＋person"), meta = original.meta.copy(authorId = PersonId("@author")),
        )
        for (excelFriendly in listOf(false, true)) {
            val out = ByteArrayOutputStream()
            export.normalized(state.with(risky), T0, excelFriendly, out)
            val entriesWithIds = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entriesWithIds[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val csv = entriesWithIds.getValue("transfers.csv").removePrefix("\uFEFF")
            val format = CSVFormat.RFC4180.builder().setDelimiter(if (excelFriendly) ';' else ',').get()
            val row = format.parse(csv.reader()).records.single { it[0] == "'=entry" }
            listOf(row[0], row[1], row[2], row[3], row[4], row[19]) shouldBe
                listOf("'=entry", "''person", "'＋person", "1000", "10.00", "'@author")
            listOf(row[0], row[1], row[2], row[19]).map(TextEscaping::unescape) shouldBe
                listOf("=entry", "'person", "＋person", "@author")
        }
    }

    @Test
    fun `excel-friendly mode writes a BOM and semicolons, and says so in the manifest`() {
        val (_, entries) = export(excelFriendly = true)
        for (text in entries.filterKeys { it.endsWith(".csv") }.values) {
            text shouldStartWith "\uFEFF"
            val header = text.lines().first()
            ((';' in header) to (',' in header)) shouldBe (true to false)
        }
        entries.getValue("people.csv") shouldStartWith "\uFEFFid;display_name;is_me;"
        val manifest = Json.decodeFromString(ExportManifest.serializer(), entries.getValue("manifest.json"))
        (manifest.delimiter to manifest.bom) shouldBe (";" to true)
    }
}
