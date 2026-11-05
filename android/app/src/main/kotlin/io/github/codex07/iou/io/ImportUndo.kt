package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.SkippedRow
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import java.time.Instant

/**
 * Undo of one import batch (spec §5.4), best-effort. It reads what the batch did from the revisions tagged
 * with its ID, and works out from the data alone which owned rows changed together with their owner.
 *
 * Order: entries, then handles undone on their own, then people with their cascade. A row is undone only if
 * its current version is still the one the import wrote. An owner is skipped when a row its cascade would
 * touch isn't one the import changed through it, unchanged since. Each step is checked against the state
 * invariants. One that would break one waits and is tried again once the other steps are done, since a later
 * step can be what makes it valid (a transfer moved back to a person whom the import deleted, restored by the
 * people step). Steps that still break one when a whole retry round applies nothing are skipped.
 */
object ImportUndo {

    data class Result(val changes: List<Change>, val skipped: List<SkippedRow>)

    private class Touched(val revisions: List<EntryRevision>, val pre: LedgerEntity?) {
        val created: Boolean get() = revisions.first().action == RevisionAction.CREATED
        val lastVersion: Int get() = revisions.last().version
        fun did(action: RevisionAction) = revisions.any { it.action == action }
    }

    fun plan(state: LedgerState, revisions: List<EntryRevision>, batch: ImportBatch, now: Instant): Result {
        if (batch.undoneAt != null) return Result(emptyList(), emptyList())
        val reason = "Undo import ${batch.fileName}"
        val me = state.me.id
        val byEntity = revisions.groupBy { it.entityType to it.entityId }
        val touched = byEntity.mapNotNull { (key, history) ->
            val ofBatch = history.filter { it.importBatchId == batch.id }
            if (ofBatch.isEmpty()) return@mapNotNull null
            val pre = history.lastOrNull { it.version < ofBatch.first().version }?.snapshot
            key to Touched(ofBatch, pre)
        }.toMap()

        var current = state
        val changes = mutableListOf<Change>()
        val skipped = mutableListOf<SkippedRow>()

        fun entity(type: EntityType, id: String): LedgerEntity? = when (type) {
            EntityType.PERSON -> current.people.values.firstOrNull { it.entityId == id }
            EntityType.PAYMENT_HANDLE -> current.handles.values.firstOrNull { it.entityId == id }
            EntityType.TRANSFER, EntityType.EXPENSE -> current.entries.firstOrNull { it.entityId == id }
            else -> null
        }

        fun unchanged(type: EntityType, id: String): Boolean =
            entity(type, id)?.meta?.version == touched[type to id]?.lastVersion

        fun change(entity: LedgerEntity, target: LedgerEntity, deletedAt: Instant?): Change {
            val action = when {
                entity.isDeleted && deletedAt == null -> RevisionAction.RESTORED
                !entity.isDeleted && deletedAt != null -> RevisionAction.DELETED
                else -> RevisionAction.UPDATED
            }
            val meta = entity.meta.copy(updatedAt = now, authorId = me, version = entity.meta.version + 1, deletedAt = deletedAt, importBatchId = null)
            return Change(target.withMeta(meta), action, reason)
        }

        /** Applies [step] if it keeps the state invariants; otherwise returns the rules it would break. */
        fun apply(step: List<Change>): List<Int> {
            val after = current.applying(step)
            val broken = LedgerValidator.validate(current, after, WriteOp.UndoImport) - LedgerValidator.validate(current, current, WriteOp.UndoImport).toSet()
            if (broken.isEmpty()) {
                current = after
                changes += step
            }
            return broken.map { it.rule }
        }

        /** Steps that would break an invariant when tried, with their keys, to try again at the end. */
        val waiting = mutableListOf<Pair<List<ImportRowKey>, List<Change>>>()

        fun attempt(keys: List<ImportRowKey>, step: List<Change>) {
            if (step.isNotEmpty() && apply(step).isNotEmpty()) waiting += keys to step
        }

        /** The undo of one row on its own: delete what it created, put back what it changed. */
        fun ownUndo(type: EntityType, id: String): Change? {
            val row = touched.getValue(type to id)
            val entity = entity(type, id) ?: return null
            return if (row.created) {
                if (entity.isDeleted) null else change(entity, entity, now)
            } else {
                val pre = row.pre ?: return null
                val deletedAt = if (pre.isDeleted) (entity.meta.deletedAt ?: now) else null
                change(entity, pre, deletedAt).takeIf { it.entity.meta.deletedAt != entity.meta.deletedAt || pre.withMeta(entity.meta) != entity }
            }
        }

        fun key(type: EntityType, id: String) = ImportRowKey(type, id)

        /** [entity] with its business fields from before the batch, unless the batch created it. */
        fun beforeImport(entity: LedgerEntity): LedgerEntity =
            touched[entity.entityType to entity.entityId]?.takeIf { !it.created }?.pre ?: entity

        // Entries.
        for ((key, _) in touched.filterKeys { it.first == EntityType.TRANSFER || it.first == EntityType.EXPENSE }) {
            val (type, id) = key
            if (!unchanged(type, id)) {
                skipped += SkippedRow(key(type, id), "changed since the import")
                continue
            }
            ownUndo(type, id)?.let { attempt(listOf(key(type, id)), listOf(it)) }
        }

        // Handles that changed with their owner go back with the owner's cascade; the rest go back on their own.
        val people = touched.filterKeys { it.first == EntityType.PERSON }
        fun withOwner(handle: PaymentHandle, row: Touched): Boolean {
            val owner = people[EntityType.PERSON to handle.personId.value] ?: return false
            val ownerEntity = state.people[handle.personId] ?: return false
            return when {
                owner.created -> row.created
                owner.did(RevisionAction.RESTORED) && !ownerEntity.isDeleted ->
                    row.created || (row.did(RevisionAction.RESTORED) && row.pre?.meta?.deletedAt == owner.pre?.meta?.deletedAt)
                owner.did(RevisionAction.DELETED) && ownerEntity.isDeleted -> handle.meta.deletedAt == ownerEntity.meta.deletedAt
                else -> false
            }
        }
        val handles = touched.filterKeys { it.first == EntityType.PAYMENT_HANDLE }
        val handlesWithOwner = mutableSetOf<String>()
        for ((key, row) in handles) {
            val handle = entity(key.first, key.second) as? PaymentHandle ?: continue
            if (withOwner(handle, row)) {
                handlesWithOwner += key.second
                continue
            }
            if (!unchanged(key.first, key.second)) {
                skipped += SkippedRow(key(key.first, key.second), "changed since the import")
                continue
            }
            ownUndo(key.first, key.second)?.let { attempt(listOf(key(key.first, key.second)), listOf(it)) }
        }

        // People, with their cascade.
        for ((key, row) in people) {
            val person = entity(key.first, key.second) as? Person ?: continue
            val personKey = key(EntityType.PERSON, person.id.value)
            val deleting = !person.isDeleted && (row.created || row.did(RevisionAction.RESTORED))
            val restoring = person.isDeleted && !row.created && row.did(RevisionAction.DELETED)
            val cascade = when {
                deleting -> current.handles.values.filter { it.personId == person.id && !it.isDeleted }
                restoring -> current.handles.values.filter { it.personId == person.id && it.meta.deletedAt == person.meta.deletedAt && touched[EntityType.PAYMENT_HANDLE to it.id.value]?.created != true }
                else -> emptyList()
            }
            val cascadeKeys = cascade.map { key(EntityType.PAYMENT_HANDLE, it.id.value) }
            val protected = cascade.filter { it.id.value !in handlesWithOwner || !unchanged(EntityType.PAYMENT_HANDLE, it.id.value) }
            if (!unchanged(EntityType.PERSON, person.id.value)) {
                skipped += SkippedRow(personKey, "changed since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "its owner changed since the import") }
                continue
            }
            if (protected.isNotEmpty()) {
                skipped += SkippedRow(personKey, "owns rows changed or added since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "owns rows changed or added since the import") }
                continue
            }
            val step = when {
                deleting -> (listOf<LedgerEntity>(person) + cascade).map { change(it, beforeImport(it), now) }
                restoring -> (listOf<LedgerEntity>(person) + cascade).map { change(it, beforeImport(it), null) }
                row.created -> emptyList()
                else -> listOfNotNull(ownUndo(EntityType.PERSON, person.id.value))
            }
            attempt(listOf(personKey) + cascadeKeys, step)
        }

        // Retry what waited until a round applies nothing. A waiting step's rows changed in no other step, so
        // the step still applies as built.
        do {
            val round = waiting.toList()
            waiting.clear()
            round.forEach { (keys, step) -> attempt(keys, step) }
        } while (waiting.isNotEmpty() && waiting.size < round.size)
        for ((keys, step) in waiting) {
            val rule = apply(step).first()
            keys.forEach { skipped += SkippedRow(it, "would break rule $rule") }
        }
        return Result(changes, skipped)
    }
}
