package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.model.Transfer
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON of `EntryRevision.snapshotJson` (spec §3.2, §5.5): the entity's row, an expense with its share and
 * payer rows, plus a `schema` number. Stored snapshots are never rewritten. When a migration changes a row,
 * [CURRENT_SCHEMA] goes up and an entry in [upcasters] turns the previous schema's JSON into the next one's
 * when it is read.
 */
object SnapshotCodec {
    const val CURRENT_SCHEMA = 2

    /** Schema n → n + 1, applied in order to older snapshots. */
    private val upcasters: Map<Int, (JsonObject) -> JsonObject> = mapOf(1 to ::schema1To2)

    /** Schema 1 → 2 (spec §5.5): an expense's `payerPersonId` becomes one EQUAL payer row; other entities are unchanged. */
    private fun schema1To2(snapshot: JsonObject): JsonObject {
        val expense = snapshot["expense"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: return JsonObject(snapshot + ("schema" to JsonPrimitive(2)))
        val (row, payer) = Migrations.expense1To2(expense)
        return JsonObject(snapshot + mapOf("schema" to JsonPrimitive(2), "expense" to row, "payers" to JsonArray(listOf(payer))))
    }

    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Serializable
    private data class Snapshot(
        val schema: Int,
        val person: PersonRow? = null,
        val handle: HandleRow? = null,
        val group: GroupRow? = null,
        val member: MemberRow? = null,
        val transfer: TransferRow? = null,
        val expense: ExpenseRow? = null,
        val shares: List<ShareRow>? = null,
        val payers: List<PayerRow>? = null,
    )

    fun encode(entity: LedgerEntity): String = json.encodeToString(
        Snapshot.serializer(),
        when (entity) {
            is Person -> Snapshot(CURRENT_SCHEMA, person = entity.toRow())
            is PaymentHandle -> Snapshot(CURRENT_SCHEMA, handle = entity.toRow())
            is Group -> Snapshot(CURRENT_SCHEMA, group = entity.toRow())
            is GroupMember -> Snapshot(CURRENT_SCHEMA, member = entity.toRow())
            is Transfer -> Snapshot(CURRENT_SCHEMA, transfer = entity.toRow())
            is Expense -> Snapshot(CURRENT_SCHEMA, expense = entity.toRow(), shares = entity.shareRows(), payers = entity.payerRows())
            // Persisted from M2 Part 4; until then no schedule can reach the real flavor's journal.
            is RecurringExpense -> throw UnsupportedOperationException("Schedule snapshots are stored from M2 Part 4")
        },
    )

    /** Throws [IllegalArgumentException] for a snapshot from a newer schema or one that names no entity. */
    fun decode(text: String): LedgerEntity {
        var obj = json.parseToJsonElement(text).jsonObject
        var schema = obj.getValue("schema").jsonPrimitive.int
        require(schema <= CURRENT_SCHEMA) { "Snapshot schema $schema is newer than $CURRENT_SCHEMA" }
        while (schema < CURRENT_SCHEMA) {
            obj = upcasters.getValue(schema)(obj)
            schema += 1
        }
        val snapshot = json.decodeFromJsonElement<Snapshot>(obj)
        return snapshot.person?.toPerson()
            ?: snapshot.handle?.toHandle()
            ?: snapshot.group?.toGroup()
            ?: snapshot.member?.toMember()
            ?: snapshot.transfer?.toTransfer()
            ?: snapshot.expense?.toExpense(snapshot.shares.orEmpty(), snapshot.payers.orEmpty())
            ?: throw IllegalArgumentException("Snapshot names no entity")
    }
}

fun EntryRevision.toRow() = RevisionRow(
    id = id.value, entityType = entityType.name, entityId = entityId, version = version, action = action.name,
    authorId = authorId.value, deviceId = deviceId.value, at = at.toString(), reason = reason,
    importBatchId = importBatchId?.value, snapshotJson = SnapshotCodec.encode(snapshot),
)

fun RevisionRow.toRevision() = EntryRevision(
    id = RevisionId(id), entityType = EntityType.valueOf(entityType), entityId = entityId, version = version,
    action = RevisionAction.valueOf(action), authorId = PersonId(authorId), deviceId = DeviceId(deviceId),
    at = Instant.parse(at), reason = reason, importBatchId = importBatchId?.let(::ImportBatchId),
    snapshot = SnapshotCodec.decode(snapshotJson),
)
