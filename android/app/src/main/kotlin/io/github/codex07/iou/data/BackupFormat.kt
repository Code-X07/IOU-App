package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.store.LedgerContent
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A backup file's JSON (spec §7.1): every table as rows, plus the schema version, app version and time. */
@Serializable
data class BackupDocument(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: String,
    val rows: LedgerRows,
)

/** A backup that passed step 1 of the restore: its rows, and the ledger they hold. */
data class ValidBackup(val rows: LedgerRows, val content: LedgerContent)

/**
 * Writes and reads backup files (spec §7.1): encrypted with [BackupCipher] when a passphrase is given,
 * plain JSON otherwise. Reading decrypts, parses and validates the whole file before anything is written.
 */
class BackupFormat(
    private val kdf: KdfParams = KdfParams.DEFAULT,
    val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxJsonBytes: Long = MAX_JSON_BYTES,
) {

    fun write(rows: LedgerRows, appVersion: String, createdAt: Instant, passphrase: CharArray?): ByteArray {
        val text = text(rows, appVersion, createdAt)
        return if (passphrase == null) text else BackupCipher.encrypt(text, passphrase, kdf)
    }

    private fun text(rows: LedgerRows, appVersion: String, createdAt: Instant): ByteArray {
        val document = BackupDocument(FORMAT, SCHEMA_VERSION, appVersion, createdAt.toString(), rows)
        return json.encodeToString(BackupDocument.serializer(), document).toByteArray(Charsets.UTF_8)
    }

    /**
     * [write], refused with `LimitsExceeded` when [read] would refuse the file for its size (spec §7.1), so a backup
     * that can't be restored is never written.
     */
    fun encode(rows: LedgerRows, appVersion: String, createdAt: Instant, passphrase: CharArray?): LedgerResult<ByteArray> {
        val text = text(rows, appVersion, createdAt)
        if (text.size > maxJsonBytes) return LedgerResult.Err(LedgerError.LimitsExceeded("Backup JSON larger than $maxJsonBytes bytes"))
        val file = if (passphrase == null) text else BackupCipher.encrypt(text, passphrase, kdf)
        if (file.size > maxFileBytes) return LedgerResult.Err(LedgerError.LimitsExceeded("Backup larger than $maxFileBytes bytes"))
        return LedgerResult.Ok(file)
    }

    /**
     * Step 1 of the restore. Errors:
     * - `WrongPassphraseOrDamagedFile` (also for an encrypted file without a passphrase);
     * - `CorruptFile` (not a backup, malformed, rows that don't map, a cached rate that isn't valid, a ledger that
     *   breaks a §3.4 rule);
     * - `SchemaTooNew`;
     * - `LimitsExceeded`.
     */
    fun read(file: ByteArray, passphrase: CharArray?): LedgerResult<ValidBackup> {
        if (file.size > maxFileBytes) return LedgerResult.Err(LedgerError.LimitsExceeded("Backup larger than $maxFileBytes bytes"))
        val text = when {
            !BackupCipher.isEncrypted(file) ->
                if (file.size > maxJsonBytes) return LedgerResult.Err(LedgerError.LimitsExceeded("Backup larger than $maxJsonBytes bytes")) else file
            passphrase == null -> return LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
            else -> when (val plain = BackupCipher.decrypt(file, passphrase, maxJsonBytes)) {
                is LedgerResult.Err -> return plain
                is LedgerResult.Ok -> plain.value
            }
        }.toString(Charsets.UTF_8)
        val document = try {
            val header = json.parseToJsonElement(text).jsonObject
            if (header["format"]?.jsonPrimitive?.content != FORMAT) return LedgerResult.Err(LedgerError.CorruptFile("Not an IOU backup"))
            val schema = requireNotNull(header["schemaVersion"]) { "Missing schemaVersion" }.jsonPrimitive.int
            if (schema > SCHEMA_VERSION) return LedgerResult.Err(LedgerError.SchemaTooNew(schema, SCHEMA_VERSION))
            json.decodeFromJsonElement(BackupDocument.serializer(), upgrade(header, schema))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's exceptions are IllegalArgumentExceptions.
            return LedgerResult.Err(LedgerError.CorruptFile("Malformed backup: ${e.message}"))
        }
        val content = try {
            val expenseIds = document.rows.expenses.map { it.id }.toSet()
            require(document.rows.shares.all { it.expenseId in expenseIds }) { "Share references a missing expense" }
            require(document.rows.payers.all { it.expenseId in expenseIds }) { "Payer references a missing expense" }
            val scheduleIds = document.rows.schedules.map { it.id }.toSet()
            require(document.rows.participants.all { it.recurringId in scheduleIds }) { "Participant references a missing schedule" }
            // The rules skip deleted schedules, but their dates are still computed (history, details), and
            // `Recurrence` requires a valid interval.
            for (schedule in document.rows.schedules) {
                require(schedule.interval in 1..99) { "Schedule ${schedule.id} has interval ${schedule.interval}" }
                require(schedule.endOn == null || LocalDate.parse(schedule.endOn) >= LocalDate.parse(schedule.startOn)) {
                    "Schedule ${schedule.id} ends before it starts"
                }
            }
            // The rate cache isn't ledger data, but `FxService.load` reads it once the restore has committed.
            for (rate in document.rows.rates.map { it.toCachedRate().rate }) {
                require(FxConverter.validateRate(rate) is LedgerResult.Ok) { "Invalid cached rate $rate" }
            }
            document.rows.requireUniqueKeys()
            document.rows.toContent().also { content ->
                // A revision's snapshot is the row it names at that version (spec §5.3); a mismatch would let a
                // revert write a different row.
                for (revision in content.revisions) {
                    val snapshot = revision.snapshot
                    require(
                        snapshot.entityType == revision.entityType && snapshot.entityId == revision.entityId &&
                            snapshot.meta.version == revision.version,
                    ) { "Revision ${revision.id.value} holds a snapshot of another row" }
                }
            }
        } catch (e: RuntimeException) {
            return LedgerResult.Err(LedgerError.CorruptFile("Unreadable rows: ${e.message}"))
        }
        if (content.state != LedgerState()) {
            val violation = LedgerValidator.validate(LedgerState(), content.state, WriteOp.Import).firstOrNull()
            if (violation != null) return LedgerResult.Err(LedgerError.CorruptFile("Backup breaks rule ${violation.rule}: ${violation.error}"))
        }
        return LedgerResult.Ok(ValidBackup(document.rows, content))
    }

    /**
     * Older backups are migrated before they are decoded (spec §7.1 step 1): a version-1 backup's expense rows get
     * their payer rows exactly as `Migration(1, 2)` gives them. Revision snapshots stay as written; the snapshot
     * upcaster reads them.
     */
    private fun upgrade(document: JsonObject, schema: Int): JsonObject {
        if (schema >= SCHEMA_VERSION) return document
        val rows = document["rows"]?.jsonObject ?: return document
        return JsonObject(
            document + mapOf("schemaVersion" to JsonPrimitive(SCHEMA_VERSION), "rows" to Migrations.backupRows1To2(rows)),
        )
    }

    companion object {
        const val FORMAT = "iou-backup"

        /** The Room schema version (`IouDatabase`); older backups are migrated when it grows. */
        const val SCHEMA_VERSION = 2
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_JSON_BYTES = 256L * 1024 * 1024

        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
