package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.api.SkippedRow
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import java.time.Instant

/**
 * Undo of one import batch (spec §5.4), best-effort. It reads what the batch did from the revisions tagged
 * with its ID, and works out from the data alone which owned rows changed together with their owner ([Owners]).
 *
 * Order: entries, schedules, owned rows (handles, memberships) undone on their own, then owners (groups,
 * people) with their cascade. A row is undone only if its current version is still the one the import wrote;
 * an owner is skipped when a row its cascade would touch isn't one the import changed through it, unchanged
 * since. Every step the protection leaves is applied and the result is checked once against the state
 * invariants; when it holds, that is the undo. Only otherwise does undo go step by step: a step that would
 * break an invariant waits and is tried again after the others; when a whole round applies nothing, waiting
 * entry steps tied by a removed member of their group are tried together; what still breaks one is skipped.
 * Expenses the batch created are deleted directly, not through the `api` delete, so their schedule's
 * `handledThrough` is not raised and their occurrences are due again.
 */
object ImportUndo {

    data class Result(val changes: List<Change>, val skipped: List<SkippedRow>)

    private class Touched(val revisions: List<EntryRevision>, val pre: LedgerEntity?) {
        val created: Boolean get() = revisions.first().action == RevisionAction.CREATED
        val lastVersion: Int get() = revisions.last().version
        fun did(action: RevisionAction) = revisions.any { it.action == action }
    }

    /** One step of the undo: the rows it puts back and their changes. Steps never share a row. */
    private class Step(val keys: List<ImportRowKey>, val changes: List<Change>)

    fun plan(state: LedgerState, revisions: List<EntryRevision>, batch: ImportBatch, now: Instant): Result {
        if (batch.undoneAt != null) return Result(emptyList(), emptyList())
        val reason = "Undo import ${batch.fileName}"
        val me = state.me.id
        val touched: Map<ImportRowKey, Touched> = revisions.groupBy { ImportRowKey(it.entityType, it.entityId) }
            .mapNotNull { (key, history) ->
                val ofBatch = history.filter { it.importBatchId == batch.id }
                if (ofBatch.isEmpty()) return@mapNotNull null
                val pre = history.lastOrNull { it.version < ofBatch.first().version }?.snapshot
                key to Touched(ofBatch, pre)
            }.toMap()
        fun keys(vararg types: EntityType) = touched.keys.filter { it.type in types }

        // Each step is built against the state the steps before it produce, as if every one of them applies.
        var built = state
        val steps = mutableListOf<Step>()
        val skipped = mutableListOf<SkippedRow>()

        fun entity(key: ImportRowKey): LedgerEntity? = built.entity(key.type, key.id)

        fun unchanged(key: ImportRowKey): Boolean = entity(key)?.meta?.version == touched[key]?.lastVersion

        fun change(entity: LedgerEntity, target: LedgerEntity, deletedAt: Instant?): Change {
            val action = when {
                entity.isDeleted && deletedAt == null -> RevisionAction.RESTORED
                !entity.isDeleted && deletedAt != null -> RevisionAction.DELETED
                else -> RevisionAction.UPDATED
            }
            val meta = entity.meta.copy(updatedAt = now, authorId = me, version = entity.meta.version + 1, deletedAt = deletedAt, importBatchId = null)
            return Change(target.withMeta(meta), action, reason)
        }

        fun add(keys: List<ImportRowKey>, changes: List<Change>) {
            if (changes.isEmpty()) return
            steps += Step(keys, changes)
            built = built.applying(changes)
        }

        /** The undo of one row on its own: delete what it created, put back what it changed. */
        fun ownUndo(key: ImportRowKey): Change? {
            val row = touched.getValue(key)
            val entity = entity(key) ?: return null
            return if (row.created) {
                if (!entity.isDeleted) return change(entity, entity, now)
                // Created deleted into the cascade of an owner this import didn't create (an owner kept deleted):
                // it moves out, to the undo time, so restoring the owner later doesn't bring back an undone row.
                val ownerKey = Owners.ownerOf(entity) ?: return null
                val owner = built.entity(ownerKey.type, ownerKey.id) ?: return null
                val inCascade = touched[ownerKey]?.created != true && owner.meta.deletedAt == entity.meta.deletedAt
                if (inCascade) change(entity, entity, now) else null
            } else {
                val pre = row.pre ?: return null
                // A row deleted before the import gets its own pre-import deletedAt back, whether the import restored
                // it or moved it into an owner's cascade, so it is apart from (or together with) its owner's cascade
                // exactly as before the import (spec §5.2).
                val deletedAt = if (pre.isDeleted) pre.meta.deletedAt else null
                change(entity, pre, deletedAt).takeIf { it.entity.meta.deletedAt != entity.meta.deletedAt || pre.withMeta(entity.meta) != entity }
            }
        }

        /** [entity] with its business fields from before the batch, unless the batch created it. */
        fun beforeImport(entity: LedgerEntity): LedgerEntity = touched[entity.rowKey]?.takeIf { !it.created }?.pre ?: entity

        // Entries, then schedules, each on its own.
        for (key in keys(EntityType.TRANSFER, EntityType.EXPENSE) + keys(EntityType.RECURRING_EXPENSE)) {
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                continue
            }
            ownUndo(key)?.let { add(listOf(key), listOf(it)) }
        }

        // Owned rows that changed with their owner go back with the owner's cascade; the rest go back on their own.
        fun changedWithOwner(owned: LedgerEntity, row: Touched): Boolean {
            val ownerKey = Owners.ownerOf(owned) ?: return false
            val owner = touched[ownerKey] ?: return false
            val ownerEntity = state.entity(ownerKey.type, ownerKey.id) ?: return false
            return when {
                owner.created -> row.created
                owner.did(RevisionAction.RESTORED) && !ownerEntity.isDeleted ->
                    row.created || (row.did(RevisionAction.RESTORED) && row.pre?.meta?.deletedAt == owner.pre?.meta?.deletedAt)
                owner.did(RevisionAction.DELETED) && ownerEntity.isDeleted -> owned.meta.deletedAt == ownerEntity.meta.deletedAt
                else -> false
            }
        }
        val withOwner = mutableSetOf<ImportRowKey>()
        for (key in keys(EntityType.PAYMENT_HANDLE, EntityType.GROUP_MEMBER)) {
            val owned = entity(key) ?: continue
            if (changedWithOwner(owned, touched.getValue(key))) {
                withOwner += key
                continue
            }
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                continue
            }
            ownUndo(key)?.let { add(listOf(key), listOf(it)) }
        }

        // Owners, with their cascade.
        for (key in keys(EntityType.GROUP) + keys(EntityType.PERSON)) {
            val owner = entity(key) ?: continue
            val row = touched.getValue(key)
            val deleting = !owner.isDeleted && (row.created || row.did(RevisionAction.RESTORED))
            val restoring = owner.isDeleted && !row.created && row.did(RevisionAction.DELETED)
            val cascade = when {
                deleting -> Owners.owned(built, key).filter { !it.isDeleted }
                restoring -> Owners.owned(built, key).filter { it.meta.deletedAt == owner.meta.deletedAt && touched[it.rowKey]?.created != true }
                else -> emptyList()
            }
            val cascadeKeys = cascade.map { it.rowKey }
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "its owner changed since the import") }
                continue
            }
            if (cascade.any { it.rowKey !in withOwner || !unchanged(it.rowKey) }) {
                skipped += SkippedRow(key, "owns rows changed or added since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "owns rows changed or added since the import") }
                continue
            }
            // An owner the import created is deleted now; one it restored gets its pre-import deletedAt back, and its
            // cascade shares it, so a later restore brings back what went together and nothing deleted apart.
            val deletedAt = if (row.created) now else row.pre?.meta?.deletedAt ?: now
            val changes = when {
                deleting -> (listOf(owner) + cascade).map { change(it, beforeImport(it), deletedAt) }
                // An owned row deleted before the import that the import moved into this cascade stays deleted, at
                // its own pre-import deletedAt; the rest come back with the owner.
                restoring -> (listOf(owner) + cascade).map { entity ->
                    val pre = touched[entity.rowKey]?.pre?.takeIf { entity !== owner && it.isDeleted }
                    change(entity, beforeImport(entity), pre?.meta?.deletedAt)
                }
                row.created -> emptyList()
                else -> listOfNotNull(ownUndo(key))
            }
            add(listOf(key) + cascadeKeys, changes)
        }

        /** The state invariants [after] breaks that [before] didn't. */
        fun broken(before: LedgerState, after: LedgerState): List<RuleViolation> {
            val existing = LedgerValidator.validate(before, before, WriteOp.UndoImport).toSet()
            return LedgerValidator.validate(before, after, WriteOp.UndoImport).filter { it !in existing }
        }

        // The whole undo first. An unchanged import always passes: it gives back the pre-import state.
        if (broken(state, built).isEmpty()) return Result(steps.flatMap { it.changes }, skipped)

        // Step by step. A waiting step's rows changed in no other step, so it still applies as built.
        var current = state
        val changes = mutableListOf<Change>()
        fun apply(step: Step): List<Int> {
            val after = current.applying(step.changes)
            val rules = broken(current, after).map { it.rule }
            if (rules.isEmpty()) {
                current = after
                changes += step.changes
            }
            return rules
        }
        var waiting = steps.filter { apply(it).isNotEmpty() }
        while (waiting.isNotEmpty()) {
            val still = waiting.filter { apply(it).isNotEmpty() }
            if (still.size < waiting.size) {
                waiting = still
                continue
            }
            val joined = tied(still, current).filter { cluster ->
                apply(Step(cluster.flatMap { it.keys }, cluster.flatMap { it.changes })).isEmpty()
            }
            if (joined.isEmpty()) break
            val done = joined.flatten().toSet()
            waiting = still.filter { it !in done }
        }
        for (step in waiting) {
            val rule = apply(step).firstOrNull() ?: continue
            step.keys.forEach { skipped += SkippedRow(it, "would break rule $rule") }
        }
        return Result(changes, skipped)
    }

    /**
     * The waiting entry steps that are only valid together (spec §5.4): entries of one group that reference a common
     * member who is removed there, joined transitively, since rule 4 judges a removed member's entries only together.
     * Only clusters of two or more steps are returned.
     */
    private fun tied(waiting: List<Step>, state: LedgerState): List<List<Step>> {
        val resolver = IdentityResolver(state)
        fun removedMembers(step: Step): Set<Pair<GroupId, PersonId>> {
            val target = step.changes.singleOrNull()?.entity as? Entry ?: return emptySet()
            return listOfNotNull(target, state.entry(target.id)).flatMap { entry ->
                val group = entry.groupId ?: return@flatMap emptyList()
                entry.personIds.map(resolver::canonical)
                    .filter { resolver.hasMembership(group, it) && !resolver.isActiveMember(group, it) }
                    .map { group to it }
            }.toSet()
        }
        val clusters = mutableListOf<Pair<MutableList<Step>, MutableSet<Pair<GroupId, PersonId>>>>()
        for (step in waiting) {
            val tags = removedMembers(step)
            if (tags.isEmpty()) continue
            val joined = clusters.filter { (_, members) -> members.any(tags::contains) }
            val merged = mutableListOf(step) to tags.toMutableSet()
            for ((steps, members) in joined) {
                merged.first += steps
                merged.second += members
            }
            clusters.removeAll(joined)
            clusters += merged
        }
        return clusters.map { it.first }.filter { it.size > 1 }
    }
}
