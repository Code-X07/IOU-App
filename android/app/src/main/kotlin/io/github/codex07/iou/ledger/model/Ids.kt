package io.github.codex07.iou.ledger.model

import java.util.UUID

@JvmInline value class PersonId(val value: String) { override fun toString(): String = value }
@JvmInline value class GroupId(val value: String) { override fun toString(): String = value }
@JvmInline value class MembershipId(val value: String) { override fun toString(): String = value }
@JvmInline value class HandleId(val value: String) { override fun toString(): String = value }
@JvmInline value class EntryId(val value: String) { override fun toString(): String = value }
@JvmInline value class FxSnapshotId(val value: String) { override fun toString(): String = value }
@JvmInline value class ImportBatchId(val value: String) { override fun toString(): String = value }
@JvmInline value class RecurringId(val value: String) { override fun toString(): String = value }
@JvmInline value class RevisionId(val value: String) { override fun toString(): String = value }
@JvmInline value class DeviceId(val value: String) { override fun toString(): String = value }

/** Identifies the live database; changes on every restore and undo of a restore (spec §5.1). */
@JvmInline value class LedgerGeneration(val value: String) { override fun toString(): String = value }

/** Source of new UUID strings. Production uses [Random]; tests use deterministic generators. */
fun interface IdGenerator {
    fun next(): String

    companion object {
        val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
