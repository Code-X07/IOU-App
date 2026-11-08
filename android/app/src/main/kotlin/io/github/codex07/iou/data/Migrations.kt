package io.github.codex07.iou.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Schema changes of the ledger database and of the formats that share its row shape (spec §3.2, §5.5, §7.1).
 * Schema 1 is `v0.1.0`; schema 2 is M2. Every expense of schema 1 had exactly one payer, `payerPersonId`, which
 * becomes one payer row paying the effect total with `payerSplit = EQUAL`, so no balance changes (§4.2).
 */
object Migrations {

    /**
     * Room schema 1 → 2 (spec §3.2), written by hand. One migration for all of M2 (index, locked decision 3). It also
     * creates the `recurring_expense` and `recurring_participant` tables and adds the expense link columns (spec §3.2).
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `expense_payer` (`expenseId` TEXT NOT NULL, `personId` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `paidMinor` INTEGER NOT NULL, `inputValue` INTEGER, " +
                    "PRIMARY KEY(`expenseId`, `personId`))",
            )
            connection.execSQL(
                "INSERT INTO `expense_payer` (`expenseId`, `personId`, `position`, `paidMinor`, `inputValue`) " +
                    "SELECT `id`, `payerPersonId`, 0, `effectTotalMinor`, NULL FROM `expense`",
            )
            connection.execSQL(
                "CREATE TABLE `expense_new` (`id` TEXT NOT NULL, `groupId` TEXT, `payerSplit` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, `category` TEXT, `totalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                    "`effectTotalMinor` INTEGER NOT NULL, `effectCurrency` TEXT NOT NULL, `fxSnapshotId` TEXT, " +
                    "`splitType` TEXT NOT NULL, `occurredOn` TEXT NOT NULL, `note` TEXT, `createdAt` TEXT NOT NULL, " +
                    "`updatedAt` TEXT NOT NULL, `authorId` TEXT NOT NULL, `deletedAt` TEXT, `version` INTEGER NOT NULL, " +
                    "`importBatchId` TEXT, `recurringId` TEXT, `recurringOn` TEXT, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "INSERT INTO `expense_new` (`id`, `groupId`, `payerSplit`, `description`, `category`, `totalMinor`, `currency`, " +
                    "`effectTotalMinor`, `effectCurrency`, `fxSnapshotId`, `splitType`, `occurredOn`, `note`, `createdAt`, " +
                    "`updatedAt`, `authorId`, `deletedAt`, `version`, `importBatchId`) " +
                    "SELECT `id`, `groupId`, 'EQUAL', `description`, `category`, `totalMinor`, `currency`, `effectTotalMinor`, " +
                    "`effectCurrency`, `fxSnapshotId`, `splitType`, `occurredOn`, `note`, `createdAt`, `updatedAt`, `authorId`, " +
                    "`deletedAt`, `version`, `importBatchId` FROM `expense`",
            )
            connection.execSQL("DROP TABLE `expense`")
            connection.execSQL("ALTER TABLE `expense_new` RENAME TO `expense`")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_expense` (`id` TEXT NOT NULL, `groupId` TEXT, `description` TEXT NOT NULL, " +
                    "`category` TEXT, `note` TEXT, `totalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                    "`payerSplit` TEXT NOT NULL, `splitType` TEXT NOT NULL, `unit` TEXT NOT NULL, `interval` INTEGER NOT NULL, " +
                    "`startOn` TEXT NOT NULL, `endOn` TEXT, `handledThrough` TEXT, `autoPostFrom` TEXT NOT NULL, " +
                    "`autoPost` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
                    "`deletedAt` TEXT, `version` INTEGER NOT NULL, `importBatchId` TEXT, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_participant` (`recurringId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                    "`personId` TEXT NOT NULL, `position` INTEGER NOT NULL, `inputValue` INTEGER, " +
                    "PRIMARY KEY(`recurringId`, `role`, `personId`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /**
     * A schema-1 expense row as JSON → the schema-2 row and its one payer row. Shared by the snapshot upcaster and
     * the version-1 backup reader, so both give exactly what [MIGRATION_1_2] gives.
     */
    fun expense1To2(expense: JsonObject): Pair<JsonObject, JsonObject> {
        val payer = buildJsonObject {
            put("expenseId", expense.required("id"))
            put("personId", expense.required("payerPersonId"))
            put("position", JsonPrimitive(0))
            put("paidMinor", expense.required("effectTotalMinor"))
            put("inputValue", JsonNull)
        }
        val row = expense - "payerPersonId" +
            mapOf("payerSplit" to JsonPrimitive("EQUAL"), "recurringId" to JsonNull, "recurringOn" to JsonNull)
        return JsonObject(row) to payer
    }

    /** The `rows` object of a version-1 backup → version 2: expense rows upgraded, their payer rows added. */
    fun backupRows1To2(rows: JsonObject): JsonObject {
        val upgraded = rows["expenses"]?.jsonArray.orEmpty().map { expense1To2(it.jsonObject) }
        return JsonObject(
            rows + mapOf(
                "expenses" to JsonArray(upgraded.map { it.first }),
                "payers" to JsonArray(upgraded.map { it.second }),
            ),
        )
    }

    /** A key every schema-1 expense row has; a missing one is a damaged file, reported like other bad rows. */
    private fun JsonObject.required(key: String): JsonElement =
        requireNotNull(this[key]) { "Schema-1 expense row without $key" }
}
