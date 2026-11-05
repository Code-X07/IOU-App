package io.github.codex07.iou.io

import io.github.codex07.iou.api.BalanceChange
import io.github.codex07.iou.api.ImportCounts
import io.github.codex07.iou.api.ImportRow
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.DiffNames
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.RevisionDiff
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.rules.FieldRules
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.money.Money
import java.time.Instant

/** How an incoming row compares with the local ledger (spec §7.4). */
enum class Classification { NEW, DUPLICATE, CONFLICT }

/** An incoming record after identity mapping, the local row with its ID (if any) and how they compare. */
data class Classified<T : LedgerEntity>(val incoming: Incoming<T>, val local: T?, val kind: Classification) {
    val record: T get() = incoming.record
    val key: ImportRowKey get() = ImportRowKey(record.entityType, record.entityId)
}

/**
 * What an import would do to one local ledger (spec §7.4, §7.5 steps 2–4): identity mapping, classification
 * by UUID, row validation with attribution, and, for a set of choices, the preview rows, the balance changes
 * and the plan the commit writes. Owned rows follow their owner through the §5.2 cascade; in M1 the only
 * owners are people with their payment handles (group rows wait for M2).
 */
class ImportAnalysis private constructor(
    val state: LedgerState,
    val fileName: String,
    private val mappedFrom: PersonId?,
    val people: List<Classified<Person>>,
    val handles: List<Classified<PaymentHandle>>,
    val snapshots: List<Incoming<FxSnapshot>>,
    val transfers: List<Classified<Transfer>>,
    val expenses: List<Classified<Expense>>,
    val invalid: List<InvalidRow>,
    private val duplicates: Int,
    private val mappedKeys: Set<ImportRowKey>,
    /** When the file shows each of its people deleted, duplicates of local rows included. */
    private val sourceDeletedAt: Map<PersonId, Instant?>,
) {
    private val entries: List<Classified<out Entry>> get() = transfers + expenses
    private val rows: List<Classified<out LedgerEntity>> get() = people + handles + entries

    val counts: ImportCounts
        get() = ImportCounts(
            new = rows.count { it.kind == Classification.NEW } + snapshots.size,
            conflicts = rows.count { it.kind == Classification.CONFLICT },
            duplicates = duplicates,
            invalid = invalid.size,
        )

    // ---- Owners and their cascades ----

    private enum class OwnerAction { NONE, CREATE_DELETED, DELETE, RESTORE }

    private fun Classified<Person>.action(take: Set<ImportRowKey>): OwnerAction {
        val taken = kind == Classification.CONFLICT && key in take
        return when {
            kind == Classification.NEW && record.isDeleted -> OwnerAction.CREATE_DELETED
            taken && local!!.isDeleted != record.isDeleted -> if (record.isDeleted) OwnerAction.DELETE else OwnerAction.RESTORE
            else -> OwnerAction.NONE
        }
    }

    private fun ownerName(id: PersonId): String =
        people.firstOrNull { it.record.id == id }?.record?.displayName ?: state.people[id]?.displayName ?: id.value

    /** How [handle]'s deleted state is decided: by its owner's cascade, or (null) by its own row. */
    private fun forcedBy(handle: PaymentHandle, local: PaymentHandle?, take: Set<ImportRowKey>): ImportRowChoice? {
        val owner = people.firstOrNull { it.record.id == handle.personId }
        val name = ownerName(handle.personId)
        return when (owner?.action(take)) {
            OwnerAction.CREATE_DELETED, OwnerAction.DELETE -> ImportRowChoice.DeletedWith(name)
            OwnerAction.RESTORE ->
                if (local != null && local.meta.deletedAt == owner.local!!.meta.deletedAt) ImportRowChoice.RestoredWith(name) else null
            // The owner stays deleted locally: its handles end deleted too.
            else -> if (state.people[handle.personId]?.isDeleted == true) ImportRowChoice.DeletedWith(name) else null
        }
    }

    // ---- Preview ----

    /** Conflicts with their field diffs, and every row whose outcome a cascade forces (local rows included). */
    fun previewRows(take: Set<ImportRowKey>): List<ImportRow> {
        val shown = mutableListOf<ImportRow>()
        for (row in rows) {
            val handle = row.record as? PaymentHandle
            val forced = handle?.let { forcedBy(it, row.local as PaymentHandle?, take) }
            if (row.kind == Classification.CONFLICT || forced != null) {
                shown += ImportRow(row.key, label(row.record), diff(row.local, row.record), forced ?: ImportRowChoice.KeepOrTake, changedSinceLastPreview = false)
            }
        }
        val listed = shown.map { it.key }.toSet()
        for (owner in people) {
            val action = owner.action(take)
            val local = state.handles.values.filter { it.personId == owner.record.id }
            val touched = when (action) {
                OwnerAction.DELETE -> local.filter { !it.isDeleted }.map { it to ImportRowChoice.DeletedWith(owner.record.displayName) }
                OwnerAction.RESTORE -> local.filter { it.meta.deletedAt == owner.local!!.meta.deletedAt }
                    .map { it to ImportRowChoice.RestoredWith(owner.record.displayName) }
                else -> emptyList()
            }
            for ((handle, choice) in touched) {
                val key = ImportRowKey(EntityType.PAYMENT_HANDLE, handle.id.value)
                if (key in listed) continue
                val after = handle.withMeta(handle.meta.copy(deletedAt = if (action == OwnerAction.DELETE) Instant.EPOCH else null))
                shown += ImportRow(key, label(handle), diff(handle, after), choice, changedSinceLastPreview = false)
            }
        }
        return shown
    }

    /** Non-group balances with each person before and after the import with [take], where they differ. */
    fun balanceChanges(take: Set<ImportRowKey>): List<BalanceChange> {
        val before = LedgerEngine.compute(state).pairwise
        val afterState = state.applying(plan(take, PREVIEW_BATCH, Instant.EPOCH).changes)
        val after = LedgerEngine.compute(afterState).pairwise
        return (before.keys + after.keys).mapNotNull { person ->
            val was = before[person].orEmpty()
            val now = after[person].orEmpty()
            if (was == now) return@mapNotNull null
            val name = afterState.people[person]?.displayName ?: person.value
            BalanceChange(name, was.toMoney(), now.toMoney())
        }.sortedBy { it.counterpart }
    }

    private fun Map<io.github.codex07.iou.money.CurrencyCode, Long>.toMoney() =
        entries.sortedBy { it.key.code }.map { Money(it.value, it.key) }

    private fun label(entity: LedgerEntity): String = when (entity) {
        is Person -> "Person ${entity.displayName}"
        is PaymentHandle -> "${entity.type.name} ${entity.label} of ${ownerName(entity.personId)}"
        is Transfer -> "Transfer of ${entity.amount} on ${entity.occurredOn}"
        is Expense -> "Expense ${entity.description} on ${entity.occurredOn}"
        else -> "${entity.entityType} ${entity.entityId}"
    }

    private fun diff(local: LedgerEntity?, incoming: LedgerEntity): List<FieldChange> = RevisionDiff.diff(local, incoming, names)

    private val names = object : DiffNames {
        override fun person(id: PersonId) = ownerName(id)
        override fun group(id: GroupId) = state.groups[id]?.name ?: id.value
    }

    // ---- Commit plan ----

    /**
     * The rows the commit writes for [take] (spec §7.5 step 6): creates and updates in dependency order, then
     * deletions (entries, owned rows on their own, owners with their cascade). Invalid rows are left out. Rows
     * get [batch] as their `importBatchId` and a reason naming the file and source version.
     */
    fun plan(take: Set<ImportRowKey>, batch: ImportBatchId, now: Instant): PlanResult {
        val writer = Writer(state, batch, now)
        val ownerActions = people.associate { it.record.id to it.action(take) }

        for (row in people) {
            when (row.kind) {
                Classification.NEW -> writer.create(row.record, reasonNew(row))
                Classification.CONFLICT -> if (row.key in take) writer.update(row, reasonConflict(row))
                Classification.DUPLICATE -> Unit
            }
            if (ownerActions[row.record.id] == OwnerAction.RESTORE) {
                val restoredAt = row.local!!.meta.deletedAt
                writer.restore(row.record.id.value, EntityType.PERSON, reasonConflict(row))
                state.handles.values.filter { it.personId == row.record.id && it.meta.deletedAt == restoredAt }.forEach {
                    writer.restore(it.id.value, EntityType.PAYMENT_HANDLE, "${reasonConflict(row)} with ${row.record.displayName}")
                }
            }
        }
        val handleDeletions = mutableListOf<Pair<Classified<PaymentHandle>, Instant>>()
        for (row in handles) {
            val ownerAction = ownerActions[row.record.personId] ?: OwnerAction.NONE
            val ownerLocal = state.people[row.record.personId]
            when (row.kind) {
                Classification.NEW -> {
                    writer.create(row.record, reasonNew(row))
                    when {
                        ownerAction == OwnerAction.CREATE_DELETED || ownerAction == OwnerAction.DELETE ->
                            if (deletedApart(row)) handleDeletions += row to now.minusNanos(1)
                        // Under an owner that stays deleted, it goes with the owner unless the file shows it deleted
                        // apart; then it is deleted on its own, just before the owner, so restoring the owner skips it.
                        ownerAction == OwnerAction.NONE && ownerLocal?.isDeleted == true ->
                            handleDeletions += row to ownerLocal.meta.deletedAt!!.let { if (deletedApart(row)) it.minusNanos(1) else it }
                        row.record.isDeleted -> handleDeletions += row to now
                    }
                }
                Classification.CONFLICT -> {
                    if (row.key in take) writer.update(row, reasonConflict(row))
                    val local = row.local!!
                    when {
                        // Its deleted state isn't a choice here ("deleted with <owner>"), whether or not it is taken.
                        ownerAction == OwnerAction.DELETE -> if (deletedApart(row)) handleDeletions += row to now.minusNanos(1)
                        row.key in take && forcedBy(row.record, local, take) == null && local.isDeleted != row.record.isDeleted ->
                            if (row.record.isDeleted) handleDeletions += row to now else writer.restore(row.record.id.value, EntityType.PAYMENT_HANDLE, reasonConflict(row))
                    }
                }
                Classification.DUPLICATE -> Unit
            }
        }
        val snapshots = this.snapshots.map { it.record }
        val entryDeletions = mutableListOf<Classified<out Entry>>()
        for (row in entries) {
            when (row.kind) {
                Classification.NEW -> {
                    writer.create(row.record, reasonNew(row))
                    if (row.record.isDeleted) entryDeletions += row
                }
                Classification.CONFLICT -> if (row.key in take) {
                    writer.update(row, reasonConflict(row))
                    val local = row.local!!
                    if (local.isDeleted != row.record.isDeleted) {
                        if (row.record.isDeleted) entryDeletions += row else writer.restore(row.record.entityId, row.record.entityType, reasonConflict(row))
                    }
                }
                Classification.DUPLICATE -> Unit
            }
        }
        for (row in entryDeletions) writer.delete(row.record.entityId, row.record.entityType, now, reasonDeleted(row))
        for ((row, at) in handleDeletions) writer.delete(row.record.id.value, EntityType.PAYMENT_HANDLE, at, reasonDeleted(row))
        for (row in people) {
            val action = ownerActions.getValue(row.record.id)
            if (action != OwnerAction.CREATE_DELETED && action != OwnerAction.DELETE) continue
            val reason = if (action == OwnerAction.DELETE) reasonConflict(row) else reasonDeleted(row)
            writer.delete(row.record.id.value, EntityType.PERSON, now, reason)
            writer.current.handles.values.filter { it.personId == row.record.id && !it.isDeleted }.forEach {
                writer.delete(it.id.value, EntityType.PAYMENT_HANDLE, now, "$reason with ${row.record.displayName}")
            }
        }
        return PlanResult(writer.changes, snapshots, writer.current.applyingSnapshots(snapshots))
    }

    /** The changes, the new FX snapshots, and the state they produce. */
    data class PlanResult(val changes: List<Change>, val snapshots: List<FxSnapshot>, val state: LedgerState) {
        val rowCount: Int get() = changes.map { it.entity.entityType to it.entity.entityId }.distinct().size + snapshots.size
    }

    private fun LedgerState.applyingSnapshots(snapshots: List<FxSnapshot>) = snapshots.fold(this) { s, snapshot -> s.with(snapshot) }

    /**
     * A handle the file shows deleted at another time than its owner as the file shows it (the owner's row counts
     * even when it duplicates the local one): it is deleted on its own, so restoring the owner later doesn't bring
     * it back (spec §7.4).
     */
    private fun deletedApart(row: Classified<PaymentHandle>) =
        row.record.isDeleted && row.record.meta.deletedAt != sourceDeletedAt[row.record.personId]

    private fun mapping(row: Classified<*>) = if (mappedFrom != null && row.key in mappedKeys) ", source identity ${mappedFrom.value} mapped to me" else ""

    private fun reasonNew(row: Classified<*>) = "Imported from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    private fun reasonDeleted(row: Classified<*>) = "${reasonNew(row)}, deleted in the source"

    private fun reasonConflict(row: Classified<*>) = "Import conflict resolved from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    /** Writes rows against a running state, so later rows see earlier ones. */
    private class Writer(var current: LedgerState, private val batch: ImportBatchId, private val now: Instant) {
        val changes = mutableListOf<Change>()
        private val me = current.me.id

        private fun write(entity: LedgerEntity, action: RevisionAction, reason: String) {
            changes += Change(entity, action, reason)
            current = current.with(entity)
        }

        private fun next(entity: LedgerEntity, deletedAt: Instant?): SyncMeta =
            entity.meta.copy(updatedAt = now, authorId = me, version = entity.meta.version + 1, deletedAt = deletedAt, importBatchId = batch)

        private fun find(id: String, type: EntityType): LedgerEntity = when (type) {
            EntityType.PERSON -> current.people.getValue(PersonId(id))
            EntityType.PAYMENT_HANDLE -> current.handles.getValue(HandleId(id))
            EntityType.TRANSFER -> current.transfers.getValue(EntryId(id))
            EntityType.EXPENSE -> current.expenses.getValue(EntryId(id))
            else -> error("$type rows are not imported in M1")
        }

        /** Version 1, active; a row deleted in the source is deleted afterwards. */
        fun create(record: LedgerEntity, reason: String) =
            write(record.withMeta(SyncMeta(createdAt = now, updatedAt = now, authorId = me, importBatchId = batch)), RevisionAction.CREATED, reason)

        /** The incoming business fields on the local row; the deleted state changes separately. */
        fun update(row: Classified<*>, reason: String) {
            val local = find(row.record.entityId, row.record.entityType)
            if (local.withMeta(NEUTRAL) == row.record.withMeta(NEUTRAL)) return
            write(row.record.withMeta(next(local, local.meta.deletedAt)), RevisionAction.UPDATED, reason)
        }

        fun delete(id: String, type: EntityType, at: Instant, reason: String) {
            val entity = find(id, type)
            if (!entity.isDeleted) write(entity.withMeta(next(entity, at)), RevisionAction.DELETED, reason)
        }

        fun restore(id: String, type: EntityType, reason: String) {
            val entity = find(id, type)
            if (entity.isDeleted) write(entity.withMeta(next(entity, null)), RevisionAction.RESTORED, reason)
        }
    }

    companion object {
        private val PREVIEW_BATCH = ImportBatchId("preview")
        private val NEUTRAL = SyncMeta(Instant.EPOCH, Instant.EPOCH, PersonId(""))

        /** Business fields and deleted state equal: the sync columns are ignored (spec §7.4). */
        private fun same(a: LedgerEntity, b: LedgerEntity) = a.withMeta(NEUTRAL) == b.withMeta(NEUTRAL) && a.isDeleted == b.isDeleted

        /**
         * Analyses [decoded] against [state]. With [mapFrom], every reference to that source me becomes the local
         * me and the source me's own row is dropped (spec §7.4, identity mapping).
         */
        fun build(state: LedgerState, fileName: String, decoded: DecodedImport, mapFrom: PersonId?): ImportAnalysis {
            val me = state.me.id
            val invalid = decoded.invalid.toMutableList()
            val mappedKeys = mutableSetOf<ImportRowKey>()
            fun mapped(id: PersonId) = if (id == mapFrom) me else id
            fun <T : LedgerEntity> Incoming<T>.mapped(transform: (T) -> T): Incoming<T> {
                val after = transform(record)
                if (after != record) mappedKeys += ImportRowKey(record.entityType, record.entityId)
                return copy(record = after)
            }

            val incoming = decoded.ledger
            val people = incoming.people.filter { mapFrom == null || it.record.id != mapFrom }
                .map { row -> row.mapped { it.copy(mergedIntoId = it.mergedIntoId?.let(::mapped)) } }
            val sourceDeletedAt = people.associate { it.record.id to it.record.meta.deletedAt }
            val handles = incoming.handles.map { row -> row.mapped { it.copy(personId = mapped(it.personId)) } }
            val transfers = incoming.transfers.map { row -> row.mapped { it.copy(fromPersonId = mapped(it.fromPersonId), toPersonId = mapped(it.toPersonId)) } }
            val expenses = incoming.expenses.map { row ->
                row.mapped { e -> e.copy(payerPersonId = mapped(e.payerPersonId), shares = e.shares.map { it.copy(personId = mapped(it.personId)) }) }
            }

            var duplicates = 0
            fun <T : LedgerEntity> classify(rows: List<Incoming<T>>, local: (T) -> T?): List<Classified<T>> = rows.mapNotNull { row ->
                val existing = local(row.record)
                when {
                    existing == null -> Classified(row, null, Classification.NEW)
                    same(existing, row.record) -> { duplicates++; null }
                    else -> Classified(row, existing, Classification.CONFLICT)
                }
            }

            // FX snapshots are immutable: unknown → new, identical → duplicate, different → invalid (spec §7.4).
            val rejectedSnapshots = mutableSetOf<String>()
            val newSnapshots = incoming.fxSnapshots.filter { row ->
                val existing = state.fxSnapshots[row.record.id]
                when {
                    existing == null -> true
                    existing == row.record -> { duplicates++; false }
                    else -> {
                        rejectedSnapshots += row.record.id.value
                        invalid += InvalidRow(row.table, row.row, "differs from the local FX snapshot with the same ID", LedgerError.CorruptData("FX snapshot ${row.record.id.value} differs"))
                        false
                    }
                }
            }

            var classifiedPeople = classify(people) { state.people[it.id] }
            var classifiedHandles = classify(handles) { state.handles[it.id] }
            var classifiedTransfers = classify(transfers) { state.transfers[it.id] }
            var classifiedExpenses = classify(expenses) { state.expenses[it.id] }

            fun reject(row: Classified<*>, reason: String, error: LedgerError?) {
                invalid += InvalidRow(row.incoming.table, row.incoming.row, reason, error)
            }

            // Entries that would be written with a rejected snapshot, and rows that fail their own field checks.
            fun <T : LedgerEntity> List<Classified<T>>.checked(): List<Classified<T>> = filter { row ->
                val entry = row.record as? Entry
                if (entry?.fxSnapshotId?.value in rejectedSnapshots) {
                    reject(row, "references an FX snapshot that differs from the local copy", LedgerError.FxSnapshotMismatch(entry?.id))
                    return@filter false
                }
                val field = if (row.record.isDeleted) null else FieldRules.check(listOf(row.record)).firstOrNull()
                if (field != null) reject(row, describe(field), field.error)
                field == null
            }
            classifiedPeople = classifiedPeople.checked()
            classifiedHandles = classifiedHandles.checked()
            classifiedTransfers = classifiedTransfers.checked()
            classifiedExpenses = classifiedExpenses.checked()

            // State invariants (spec §3.4) over the complete state the import would produce with every conflict
            // taken, cascades included. Only when it breaks one are rows blamed: a row is to blame for a violation
            // that goes away without it. Leaving out a new person would leave the rows that name them dangling, and
            // the validator then reports only that broken structure (rule 15), hiding every other violation; so a
            // new person is left out together with those rows, and is to blame only for a violation that stays
            // while just those rows are left out. People and handles answer first, for the violations that also
            // remain with the incoming active entries left out; incoming deletions stay in, as they can be what
            // makes a deletion valid (a person deleted together with their transfers). Entries answer for the rest.
            // Each round repeats until nothing is broken or no row is to blame, and both passes run again while
            // either blames a row, since one broken rule (above all a dangling reference) can hide others.
            val rejected = mutableSetOf<ImportRowKey>()
            fun <T : LedgerEntity> List<Classified<T>>.kept() = filter { it.key !in rejected }
            fun violations(withActiveEntries: Boolean, without: Set<ImportRowKey> = emptySet()): Set<RuleViolation> {
                fun <T : LedgerEntity> List<Classified<T>>.taken(entries: Boolean) =
                    kept().filter { it.key !in without && (!entries || withActiveEntries || it.record.isDeleted) }
                val rows = ImportAnalysis(
                    state, fileName, mapFrom, classifiedPeople.taken(false), classifiedHandles.taken(false), newSnapshots,
                    classifiedTransfers.taken(true), classifiedExpenses.taken(true), emptyList(), 0, emptySet(), sourceDeletedAt,
                )
                val after = rows.plan(rows.rows.filter { it.kind == Classification.CONFLICT }.map { it.key }.toSet(), PREVIEW_BATCH, Instant.EPOCH).state
                return LedgerValidator.validate(state, after, WriteOp.Import).toSet()
            }
            fun references(row: Classified<*>): Set<PersonId> = when (val record = row.record) {
                is Person -> setOfNotNull(record.mergedIntoId)
                is PaymentHandle -> setOf(record.personId)
                is Transfer -> setOf(record.fromPersonId, record.toPersonId)
                is Expense -> record.shares.map { it.personId }.toSet() + record.payerPersonId
                else -> emptySet()
            }
            // The kept rows that name [row] if it is a new person, directly or through other new people.
            fun dependents(row: Classified<*>): Set<ImportRowKey> {
                val rows = classifiedPeople.kept() + classifiedHandles.kept() + classifiedTransfers.kept() + classifiedExpenses.kept()
                val gone = mutableSetOf<PersonId>()
                val found = mutableSetOf<ImportRowKey>()
                var next: List<Classified<*>> = listOf(row)
                while (next.isNotEmpty()) {
                    gone += next.mapNotNull { r -> (r.record as? Person)?.id?.takeIf { r.local == null } }
                    next = rows.filter { it.key != row.key && it.key !in found && references(it).any(gone::contains) }
                    found += next.map { it.key }
                }
                return found
            }
            fun blame(candidates: List<Classified<*>>, broken: () -> Set<RuleViolation>, without: (Set<ImportRowKey>) -> Set<RuleViolation>): Boolean {
                var any = false
                var found = broken()
                while (found.isNotEmpty()) {
                    val blamed = candidates.filter { it.key !in rejected }.filter { row ->
                        val others = dependents(row)
                        val stays = if (others.isEmpty()) found else found intersect without(others)
                        (stays - without(others + row.key)).firstOrNull()?.let { reject(row, describe(it), it.error) } != null
                    }
                    if (blamed.isEmpty()) return any
                    any = true
                    rejected += blamed.map { it.key }
                    found = broken()
                }
                return any
            }
            while (violations(withActiveEntries = true).isNotEmpty()) {
                val owners = blame(
                    classifiedPeople + classifiedHandles,
                    broken = { violations(withActiveEntries = false) intersect violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = false, without = it) },
                )
                val entries = blame(
                    classifiedTransfers + classifiedExpenses,
                    broken = { violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = true, without = it) },
                )
                if (!owners && !entries) break
            }
            classifiedPeople = classifiedPeople.kept()
            classifiedHandles = classifiedHandles.kept()
            classifiedTransfers = classifiedTransfers.kept()
            classifiedExpenses = classifiedExpenses.kept()

            return ImportAnalysis(
                state, fileName, mapFrom, classifiedPeople, classifiedHandles, newSnapshots,
                classifiedTransfers, classifiedExpenses, invalid.sortedWith(compareBy({ it.table }, { it.rowNumber })), duplicates, mappedKeys, sourceDeletedAt,
            )
        }

        private fun describe(violation: RuleViolation) =
            if (violation.rule == 0) "invalid field: ${violation.error}" else "breaks rule ${violation.rule}: ${violation.error}"
    }
}
