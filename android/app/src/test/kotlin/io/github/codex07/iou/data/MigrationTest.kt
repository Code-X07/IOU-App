package io.github.codex07.iou.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestDatabases
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `Migration(1, 2)` (spec §3.2): a database written by `v0.1.0` opens as schema 2 with every expense paying through
 * one `EQUAL` payer row, and loads to exactly the ledger the current mapping gives. Without `room-testing`
 * (index, locked decision 4), the version-1 database is built from the exported `1.json`.
 */
class MigrationTest {

    @TempDir
    lateinit var dir: File

    /** Ad-hoc, group, converted, deleted and merged-participant expenses, as `v0.1.0` could hold them. */
    private fun ledger(): LedgerContent {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val alias = ledger.person("Alias", mergedInto = alex)
        val bea = ledger.person("Bea")
        val trip = ledger.group("Trip", members = listOf(ME, alex, bea))
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, bea))
        val rate = ledger.fxSnapshot(SEK, EUR, "0.08765", source = FxSource.ECB_FRANKFURTER)
        ledger.expense(ME, eur(3_000), mapOf(ME to 1_500L, alex to 1_500L))
        ledger.expense(alex, eur(9_000), mapOf(ME to 3_000L, alex to 3_000L, bea to 3_000L), group = trip, splitType = SplitType.EQUAL)
        ledger.expense(bea, sek(100_000), mapOf(ME to 4_383L, bea to 4_382L), group = flat, effectTotal = eur(8_765), fx = rate)
        ledger.expense(ME, eur(1_000), mapOf(alex to 1_000L), deleted = true)
        ledger.expense(alias, eur(2_000), mapOf(ME to 2_000L))
        val dinner = ledger.state.expenses.values.first()
        val revision = EntryRevision(
            RevisionId("rev-1"), EntityType.EXPENSE, dinner.id.value, 1, RevisionAction.CREATED, ME, DeviceId("device-1"), T0,
            snapshot = dinner,
        )
        return LedgerContent(state = ledger.state, revisions = listOf(revision))
    }

    @Test
    fun `a version-1 database migrates to the same ledger with one payer per expense`() = runTest {
        val content = ledger()
        val file = File(dir, "iou-v1.db")
        payerOf = content.state.expenses.values.associate { it.id.value to it.payers.single().personId.value }
        writeVersion1(file, LedgerRows.of(content, emptyList()))

        val db = TestDatabases.file(file)
        try {
            val loaded = LedgerLoader.load(db)
            loaded.state shouldBe content.state
            loaded.revisions shouldBe content.revisions
            LedgerEngine.compute(loaded.state) shouldBe LedgerEngine.compute(content.state)
            loaded.state.expenses.values.forEach { expense: Expense ->
                expense.payers.single().paidMinor shouldBe expense.effectTotal.amountMinor
            }
        } finally {
            db.close()
        }
    }

    /**
     * Creates [file] as Room schema 1 (tables and setup queries from `1.json`, `user_version` 1) and inserts [rows]
     * the way `v0.1.0` stored them: expenses with `payerPersonId` and no payer table, snapshots of schema 1.
     */
    private fun writeVersion1(file: File, rows: LedgerRows) {
        val schema = Json.parseToJsonElement(File(SCHEMA_1).readText()).jsonObject.getValue("database").jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = 1")

            val all = SnapshotCodec.json.encodeToJsonElement(LedgerRows.serializer(), rows).jsonObject
            for ((field, table) in TABLES) {
                for (row in all.getValue(field).jsonArray.map { it.jsonObject }) {
                    insert(connection, table, if (table == "expense") version1Expense(row) else version1Row(table, row))
                }
            }
        } finally {
            connection.close()
        }
    }

    /** The schema-1 expense row: `payerPersonId` instead of `payerSplit`. */
    private fun version1Expense(row: JsonObject): JsonObject {
        val payer = payerOf.getValue(row.getValue("id").jsonPrimitive.content)
        return JsonObject(row - "payerSplit" + ("payerPersonId" to JsonPrimitive(payer)))
    }

    /** A revision row keeps its columns, but its snapshot is written in schema 1, as `v0.1.0` wrote it. */
    private fun version1Row(table: String, row: JsonObject): JsonObject {
        if (table != "entry_revision") return row
        val snapshot = Json.parseToJsonElement(row.getValue("snapshotJson").jsonPrimitive.content).jsonObject
        val expense = snapshot["expense"]?.takeIf { it !is JsonNull }?.jsonObject ?: return row
        val v1 = JsonObject(
            snapshot - "payers" + mapOf(
                "schema" to JsonPrimitive(1),
                "expense" to version1Expense(expense),
            ),
        )
        return JsonObject(row + ("snapshotJson" to JsonPrimitive(v1.toString())))
    }

    /** Each expense's one payer, by expense ID, set by the test before it writes the version-1 rows. */
    private lateinit var payerOf: Map<String, String>

    private fun insert(connection: SQLiteConnection, table: String, row: JsonObject) {
        val columns = row.keys.toList()
        val sql = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
        val statement = connection.prepare(sql)
        try {
            columns.forEachIndexed { index, column ->
                val value = row.getValue(column)
                when {
                    value is JsonNull -> statement.bindNull(index + 1)
                    value is JsonPrimitive && value.isString -> statement.bindText(index + 1, value.content)
                    value is JsonPrimitive && value.booleanOrNull != null -> statement.bindLong(index + 1, if (value.booleanOrNull!!) 1 else 0)
                    value is JsonPrimitive && value.longOrNull != null -> statement.bindLong(index + 1, value.longOrNull!!)
                    else -> error("Unexpected value $value in $table.$column")
                }
            }
            statement.step()
        } finally {
            statement.close()
        }
    }

    private companion object {
        const val SCHEMA_1 = "schemas/io.github.codex07.iou.data.IouDatabase/1.json"

        /** `LedgerRows` fields and their schema-1 tables; `payers` has no schema-1 table. */
        val TABLES = listOf(
            "people" to "person", "handles" to "payment_handle", "groups" to "ledger_group", "members" to "group_member",
            "transfers" to "transfer", "expenses" to "expense", "shares" to "expense_share", "fxSnapshots" to "fx_snapshot",
            "revisions" to "entry_revision", "importBatches" to "import_batch", "settings" to "app_setting", "rates" to "fx_rate_cache",
        )
    }
}
