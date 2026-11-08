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
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.rules.FieldRules
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

/**
 * How an incoming row compares with the local ledger (spec §7.4). [FORWARD]: a schedule that is a duplicate except
 * for a later `handled_through`, which the import applies without a choice.
 */
enum class Classification { NEW, DUPLICATE, CONFLICT, FORWARD }

/** An incoming record after identity mapping, the local row with its ID (if any) and how they compare. */
data class Classified<T : LedgerEntity>(val incoming: Incoming<T>, val local: T?, val kind: Classification) {
    val record: T get() = incoming.record
    val key: ImportRowKey get() = ImportRowKey(record.entityType, record.entityId)
}

/**
 * What an import would do to one local ledger (spec §7.4, §7.5 steps 2–4): identity mapping, classification
 * by UUID, row validation with attribution, and, for a set of choices, the preview rows, the balance changes
 * and the plan the commit writes. Owned rows follow their owner through the §5.2 cascade: people own their
 * payment handles, groups their memberships ([Owners]).
 */
class ImportAnalysis private constructor(
    val state: LedgerState,
    val fileName: String,
    private val mappedFrom: PersonId?,
    val people: List<Classified<Person>>,
    val handles: List<Classified<PaymentHandle>>,
    val groups: List<Classified<Group>>,
    val members: List<Classified<GroupMember>>,
    val snapshots: List<Incoming<FxSnapshot>>,
    val transfers: List<Classified<Transfer>>,
    val expenses: List<Classified<Expense>>,
    val schedules: List<Classified<RecurringExpense>>,
    val invalid: List<InvalidRow>,
    private val duplicates: Int,
    private val mappedKeys: Set<ImportRowKey>,
    /** When the file shows each kept or duplicate owner and owned row (people, groups, handles, memberships) deleted. */
    private val sourceDeletedAt: Map<ImportRowKey, Instant?>,
) {
    private val owners: List<Classified<*>> get() = people + groups
    private val entries: List<Classified<out Entry>> get() = transfers + expenses
    private val rows: List<Classified<*>> get() = people + handles + groups + members + entries + schedules

    val counts: ImportCounts
        get() = ImportCounts(
            new = rows.count { it.kind == Classification.NEW } + snapshots.size,
            conflicts = rows.count { it.kind == Classification.CONFLICT || it.kind == Classification.FORWARD },
            duplicates = duplicates,
            invalid = invalid.size,
        )

    // ---- Owners and their cascades ----

    private enum class OwnerAction { NONE, CREATE_DELETED, DELETE, RESTORE }

    private fun Classified<*>.action(take: Set<ImportRowKey>): OwnerAction {
        val taken = kind == Classification.CONFLICT && key in take
        return when {
            kind == Classification.NEW && record.isDeleted -> OwnerAction.CREATE_DELETED
            taken && local!!.isDeleted != record.isDeleted -> if (record.isDeleted) OwnerAction.DELETE else OwnerAction.RESTORE
            else -> OwnerAction.NONE
        }
    }

    private fun owner(key: ImportRowKey): Classified<*>? = owners.firstOrNull { it.key == key }

    /** An owner's name as the file has it, else as the ledger has it. */
    private fun ownerName(key: ImportRowKey): String = when (val record = owner(key)?.record ?: state.entity(key.type, key.id)) {
        is Person -> record.displayName
        is Group -> record.name
        else -> key.id
    }

    /** How an owned [row]'s deleted state is decided: by its owner's cascade, or (null) by its own row. */
    private fun forcedBy(row: LedgerEntity, local: LedgerEntity?, take: Set<ImportRowKey>): ImportRowChoice? {
        val ownerKey = Owners.ownerOf(row) ?: return null
        val owner = owner(ownerKey)
        val name = ownerName(ownerKey)
        return when (owner?.action(take)) {
            OwnerAction.CREATE_DELETED, OwnerAction.DELETE -> ImportRowChoice.DeletedWith(name)
            OwnerAction.RESTORE ->
                if (local != null && local.meta.deletedAt == owner.local!!.meta.deletedAt) ImportRowChoice.RestoredWith(name) else null
            // The owner stays deleted locally: its owned rows end deleted too.
            else -> if (state.entity(ownerKey.type, ownerKey.id)?.isDeleted == true) ImportRowChoice.DeletedWith(name) else null
        }
    }

    // ---- Preview ----

    /** Conflicts with their field diffs, and every row whose outcome a cascade forces (local rows included). */
    fun previewRows(take: Set<ImportRowKey>): List<ImportRow> {
        val shown = mutableListOf<ImportRow>()
        for (row in rows) {
            val forced = forcedBy(row.record, row.local, take)
            if (row.kind == Classification.CONFLICT || row.kind == Classification.FORWARD || forced != null) {
                shown += ImportRow(
                    row.key, label(row.record), diff(row.local, written(row)), forced ?: ImportRowChoice.KeepOrTake,
                    changedSinceLastPreview = false, forcedChanges = forcedChanges(row),
                )
            }
        }
        val listed = shown.map { it.key }.toSet()
        for (owner in owners) {
            val action = owner.action(take)
            val name = ownerName(owner.key)
            val local = Owners.owned(state, owner.key)
            val touched = when (action) {
                OwnerAction.DELETE -> local.filter { !it.isDeleted || joinsCascade(it, owner.key) }.map { it to ImportRowChoice.DeletedWith(name) }
                OwnerAction.RESTORE -> local.filter { it.meta.deletedAt == owner.local!!.meta.deletedAt }.map { it to ImportRowChoice.RestoredWith(name) }
                else -> emptyList()
            }
            for ((entity, choice) in touched) {
                if (entity.rowKey in listed) continue
                val after = entity.withMeta(entity.meta.copy(deletedAt = if (action == OwnerAction.DELETE) Instant.EPOCH else null))
                shown += ImportRow(entity.rowKey, label(entity), diff(entity, after), choice, changedSinceLastPreview = false)
            }
        }
        return shown
    }

    /** What taking [row] writes: for a schedule, the incoming template with the later handled date and the local `autoPostFrom`. */
    private fun written(row: Classified<*>): LedgerEntity {
        val local = row.local as? RecurringExpense ?: return row.record
        return taken(local, row.record as RecurringExpense)
    }

    /** The changes the import makes to [row] whatever its choice: a schedule's later `handledThrough` (spec §7.4). */
    private fun forcedChanges(row: Classified<*>): List<FieldChange> {
        val local = row.local as? RecurringExpense ?: return emptyList()
        if (!movesForward(local, row.record as RecurringExpense)) return emptyList()
        return diff(local, written(row)).filter { it.field == "handledThrough" }
    }

    /**
     * Non-group balances with each person, then my net in each group, before and after the import with [take],
     * where they differ. A group's line is labelled "<name> (group)".
     */
    fun balanceChanges(take: Set<ImportRowKey>): List<BalanceChange> {
        val before = LedgerEngine.compute(state)
        val afterState = state.applying(plan(take, PREVIEW_BATCH, Instant.EPOCH, PREVIEW_DAY).changes)
        val after = LedgerEngine.compute(afterState)
        val people = (before.pairwise.keys + after.pairwise.keys).mapNotNull { person ->
            val was = before.net(person)
            val now = after.net(person)
            if (was == now) return@mapNotNull null
            BalanceChange(afterState.people[person]?.displayName ?: person.value, was.toMoney(), now.toMoney())
        }.sortedBy { it.counterpart }
        val groups = (before.groupNets.keys + after.groupNets.keys).mapNotNull { group ->
            val was = before.myGroupNet(group)
            val now = after.myGroupNet(group)
            if (was == now) return@mapNotNull null
            BalanceChange("${afterState.groups[group]?.name ?: group.value} (group)", was.toMoney(), now.toMoney())
        }.sortedBy { it.counterpart }
        return people + groups
    }

    private fun Map<CurrencyCode, Long>.toMoney() = entries.sortedBy { it.key.code }.map { Money(it.value, it.key) }

    private fun label(entity: LedgerEntity): String = when (entity) {
        is Person -> "Person ${entity.displayName}"
        is PaymentHandle -> "${entity.type.name} ${entity.label} of ${names.person(entity.personId)}"
        is Group -> "Group ${entity.name}"
        is GroupMember -> "Membership of ${names.person(entity.personId)} in ${names.group(entity.groupId)}"
        is Transfer -> "Transfer of ${entity.amount} on ${entity.occurredOn}"
        is Expense -> "Expense ${entity.description} on ${entity.occurredOn}"
        is RecurringExpense -> "Schedule ${entity.description}"
    }

    private fun diff(local: LedgerEntity?, incoming: LedgerEntity): List<FieldChange> = RevisionDiff.diff(local, incoming, names)

    private val names = object : DiffNames {
        override fun person(id: PersonId) = ownerName(ImportRowKey(EntityType.PERSON, id.value))
        override fun group(id: GroupId) = ownerName(ImportRowKey(EntityType.GROUP, id.value))
    }

    // ---- Commit plan ----

    /**
     * The rows the commit writes for [take] (spec §7.5 step 6): creates and updates in dependency order (people,
     * handles, groups, memberships, FX snapshots, schedules, entries), then deletions (entries, schedules, owned
     * rows on their own, owners with their cascade). Invalid rows are left out. Rows get [batch] as their
     * `importBatchId` and a reason naming the file and source version. [today] is the import day, a new schedule's
     * `autoPostFrom`.
     */
    fun plan(take: Set<ImportRowKey>, batch: ImportBatchId, now: Instant, today: LocalDate): PlanResult {
        val writer = Writer(state, batch, now)
        val ownerActions = owners.associate { it.key to it.action(take) }
        val ownedDeletions = mutableListOf<Pair<Classified<*>, Instant>>()

        fun writeOwners(rows: List<Classified<*>>) {
            for (row in rows) {
                when (row.kind) {
                    Classification.NEW -> writer.create(row.record, reasonNew(row))
                    Classification.CONFLICT -> if (row.key in take) writer.update(row, reasonConflict(row))
                    Classification.DUPLICATE, Classification.FORWARD -> Unit
                }
                if (ownerActions[row.key] == OwnerAction.RESTORE) {
                    val restoredAt = row.local!!.meta.deletedAt
                    writer.restore(row.key, reasonConflict(row))
                    Owners.owned(state, row.key).filter { it.meta.deletedAt == restoredAt }.forEach {
                        writer.restore(it.rowKey, "${reasonConflict(row)} with ${ownerName(row.key)}")
                    }
                }
            }
        }

        /**
         * [owned], deleted here under an owner the import leaves deleted (at [ownerAt]), grouped as the file shows it
         * (spec §7.4): with the owner it takes [ownerAt], so it comes back with the owner; [apart] it stays apart, or
         * moves 1 ns before the owner if it was deleted with the owner here.
         */
        fun groupUnderKeptOwner(owned: LedgerEntity, apart: Boolean, ownerKey: ImportRowKey, ownerAt: Instant, reason: String) {
            if (!apart) writer.regroup(owned.rowKey, ownerAt, "$reason with ${ownerName(ownerKey)}")
            else if (owned.meta.deletedAt == ownerAt) writer.regroup(owned.rowKey, ownerAt.minusNanos(1), "$reason, deleted in the source")
        }

        fun writeOwned(rows: List<Classified<*>>) {
            for (row in rows) {
                val ownerKey = checkNotNull(Owners.ownerOf(row.record))
                val ownerAction = ownerActions[ownerKey] ?: OwnerAction.NONE
                val ownerLocal = state.entity(ownerKey.type, ownerKey.id)
                when (row.kind) {
                    Classification.NEW -> {
                        writer.create(row.record, reasonNew(row))
                        when {
                            ownerAction == OwnerAction.CREATE_DELETED || ownerAction == OwnerAction.DELETE ->
                                if (deletedApart(row)) ownedDeletions += row to now.minusNanos(1)
                            // Under an owner that stays deleted, it goes with the owner unless the file shows it deleted
                            // apart; then it is deleted on its own, just before the owner, so restoring the owner skips it.
                            ownerAction == OwnerAction.NONE && ownerLocal?.isDeleted == true ->
                                ownedDeletions += row to ownerLocal.meta.deletedAt!!.let { if (deletedApart(row)) it.minusNanos(1) else it }
                            row.record.isDeleted -> ownedDeletions += row to now
                        }
                    }
                    Classification.CONFLICT -> {
                        if (row.key in take) writer.update(row, reasonConflict(row))
                        val local = row.local!!
                        when {
                            // Its deleted state isn't a choice here ("deleted with <owner>"), whether or not it is taken.
                            ownerAction == OwnerAction.DELETE -> if (deletedApart(row)) ownedDeletions += row to now.minusNanos(1)
                            // Under an owner kept deleted (so the row is deleted here too) it takes the owner's deletedAt,
                            // so it comes back with the owner, unless the file shows it deleted apart: then it stays apart,
                            // or moves 1 ns before the owner if it was deleted with the owner here (spec §7.4).
                            ownerAction == OwnerAction.NONE && ownerLocal?.isDeleted == true ->
                                groupUnderKeptOwner(local, deletedApart(row), ownerKey, ownerLocal.meta.deletedAt!!, reasonConflict(row))
                            row.key in take && forcedBy(row.record, local, take) == null && local.isDeleted != row.record.isDeleted ->
                                if (row.record.isDeleted) ownedDeletions += row to now else writer.restore(row.key, reasonConflict(row))
                        }
                    }
                    Classification.DUPLICATE, Classification.FORWARD -> Unit
                }
            }
        }

        writeOwners(people)
        writeOwned(handles)
        writeOwners(groups)
        writeOwned(members)
        // Duplicates are not classified rows (business fields and deleted state match, and `deletedAt` is never
        // compared), but under an owner kept deleted the file's `deletedAt` still decides which cascade they are in.
        // Only owners in the map are visited, so the rows of a rejected (skipped) owner stay as they are.
        val classifiedKeys = rows.map { it.key }.toSet()
        for (ownerKey in sourceDeletedAt.keys.filter { it.type == EntityType.PERSON || it.type == EntityType.GROUP }) {
            if ((ownerActions[ownerKey] ?: OwnerAction.NONE) != OwnerAction.NONE) continue
            val ownerAt = state.entity(ownerKey.type, ownerKey.id)?.meta?.deletedAt ?: continue
            for (owned in Owners.owned(state, ownerKey)) {
                if (owned.rowKey in classifiedKeys || owned.rowKey !in sourceDeletedAt) continue
                val apart = sourceDeletedAt[owned.rowKey] != sourceDeletedAt[ownerKey]
                groupUnderKeptOwner(owned, apart, ownerKey, ownerAt, "Import from $fileName")
            }
        }
        val snapshots = this.snapshots.map { it.record }
        // Schedules are one unit with their participants. handled_through only moves forward, whatever the choice;
        // auto_post_from is local: a new schedule gets the import day, the cut-off rule (finalize) does the rest.
        val scheduleDeletions = mutableListOf<Pair<Classified<*>, String>>()
        for (row in schedules) {
            val local = row.local
            when (row.kind) {
                Classification.NEW -> {
                    writer.create(row.record.copy(autoPostFrom = today), reasonNew(row))
                    if (row.record.isDeleted) scheduleDeletions += row to reasonDeleted(row)
                }
                Classification.CONFLICT -> if (row.key in take) {
                    writer.update(row, reasonConflict(row), taken(local!!, row.record))
                    if (local.isDeleted != row.record.isDeleted) {
                        if (row.record.isDeleted) scheduleDeletions += row to reasonConflict(row) else writer.restore(row.key, reasonConflict(row))
                    }
                } else if (movesForward(local!!, row.record)) {
                    writer.update(row, reasonForward(row), local.copy(handledThrough = row.record.handledThrough))
                }
                Classification.FORWARD -> writer.update(row, reasonForward(row), local!!.copy(handledThrough = row.record.handledThrough))
                Classification.DUPLICATE -> Unit
            }
        }
        val entryDeletions = mutableListOf<Classified<*>>()
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
                        if (row.record.isDeleted) entryDeletions += row else writer.restore(row.key, reasonConflict(row))
                    }
                }
                Classification.DUPLICATE, Classification.FORWARD -> Unit
            }
        }
        for (row in entryDeletions) writer.delete(row.key, now, reasonDeleted(row))
        for ((row, reason) in scheduleDeletions) writer.delete(row.key, now, reason)
        for ((row, at) in ownedDeletions) writer.delete(row.key, at, reasonDeleted(row))
        for (row in owners) {
            val action = ownerActions.getValue(row.key)
            if (action != OwnerAction.CREATE_DELETED && action != OwnerAction.DELETE) continue
            val reason = if (action == OwnerAction.DELETE) reasonConflict(row) else reasonDeleted(row)
            writer.delete(row.key, now, reason)
            Owners.owned(writer.current, row.key).filter { !it.isDeleted }.forEach {
                writer.delete(it.rowKey, now, "$reason with ${ownerName(row.key)}")
            }
            // Owned rows deleted here earlier, on their own, that the file shows deleted with the owner join its
            // cascade, so restoring the owner brings back what went together in the source.
            if (action == OwnerAction.DELETE) {
                Owners.owned(state, row.key).filter { joinsCascade(it, row.key) }.forEach {
                    writer.regroup(it.rowKey, now, "$reason with ${ownerName(row.key)}")
                }
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
     * An owned row the file shows deleted at another time than its owner as the file shows it (the owner's row
     * counts even when it duplicates the local one): it is deleted on its own, so restoring the owner later doesn't
     * bring it back (spec §7.4).
     */
    private fun deletedApart(row: Classified<*>) =
        row.record.isDeleted && row.record.meta.deletedAt != sourceDeletedAt[Owners.ownerOf(row.record)]

    /**
     * An owned row already deleted locally that the file shows deleted together with its owner [ownerKey] (same
     * `deletedAt` in the file, even where the row duplicates the local one). When the import deletes the owner, the
     * row gets the owner's new `deletedAt` (spec §7.4); the preview lists it as "deleted with <owner>".
     */
    private fun joinsCascade(owned: LedgerEntity, ownerKey: ImportRowKey): Boolean {
        val ownerAt = sourceDeletedAt[ownerKey] ?: return false
        return owned.isDeleted && sourceDeletedAt[owned.rowKey] == ownerAt
    }

    private fun mapping(row: Classified<*>) = if (mappedFrom != null && row.key in mappedKeys) ", source identity ${mappedFrom.value} mapped to me" else ""

    private fun reasonNew(row: Classified<*>) = "Imported from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    private fun reasonDeleted(row: Classified<*>) = "${reasonNew(row)}, deleted in the source"

    private fun reasonConflict(row: Classified<*>) = "Import conflict resolved from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    private fun reasonForward(row: Classified<*>) =
        "Imported progress from $fileName (source v${row.incoming.sourceVersion}): handled through ${(row.record as RecurringExpense).handledThrough}${mapping(row)}"

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

        private fun find(key: ImportRowKey): LedgerEntity = checkNotNull(current.entity(key.type, key.id)) { "No ${key.type} ${key.id}" }

        /** Version 1, active; a row deleted in the source is deleted afterwards. */
        fun create(record: LedgerEntity, reason: String) =
            write(record.withMeta(SyncMeta(createdAt = now, updatedAt = now, authorId = me, importBatchId = batch)), RevisionAction.CREATED, reason)

        /** [record]'s business fields (by default the incoming ones) on the local row; the deleted state changes separately. */
        fun update(row: Classified<*>, reason: String, record: LedgerEntity = row.record) {
            val local = find(row.key)
            if (local.withMeta(NEUTRAL) == record.withMeta(NEUTRAL)) return
            write(record.withMeta(next(local, local.meta.deletedAt)), RevisionAction.UPDATED, reason)
        }

        fun delete(key: ImportRowKey, at: Instant, reason: String) {
            val entity = find(key)
            if (!entity.isDeleted) write(entity.withMeta(next(entity, at)), RevisionAction.DELETED, reason)
        }

        fun restore(key: ImportRowKey, reason: String) {
            val entity = find(key)
            if (entity.isDeleted) write(entity.withMeta(next(entity, null)), RevisionAction.RESTORED, reason)
        }

        /** Moves an already deleted row's `deletedAt` to [at]: its owner's, to join the cascade, or 1 ns before it, to leave it. */
        fun regroup(key: ImportRowKey, at: Instant, reason: String) {
            val entity = find(key)
            if (entity.isDeleted && entity.meta.deletedAt != at) write(entity.withMeta(next(entity, at)), RevisionAction.UPDATED, reason)
        }
    }

    companion object {
        private val PREVIEW_BATCH = ImportBatchId("preview")
        private val PREVIEW_DAY: LocalDate = LocalDate.EPOCH
        private val NEUTRAL = SyncMeta(Instant.EPOCH, Instant.EPOCH, PersonId(""))

        /** Business fields and deleted state equal: the sync columns are ignored (spec §7.4). */
        private fun same(a: LedgerEntity, b: LedgerEntity) = a.withMeta(NEUTRAL) == b.withMeta(NEUTRAL) && a.isDeleted == b.isDeleted

        /** A schedule as the import compares it: `handled_through` and `auto_post_from` have rules of their own (spec §7.4). */
        private fun RecurringExpense.comparable() = copy(handledThrough = null, autoPostFrom = LocalDate.MIN)

        /** True when [incoming] handled a later occurrence than [local]. */
        private fun movesForward(local: RecurringExpense, incoming: RecurringExpense): Boolean {
            val later = incoming.handledThrough ?: return false
            return local.handledThrough?.let { later > it } ?: true
        }

        /** The incoming template on the local schedule: the later handled date, the local `autoPostFrom`. */
        private fun taken(local: RecurringExpense, incoming: RecurringExpense): RecurringExpense = incoming.copy(
            handledThrough = if (movesForward(local, incoming)) incoming.handledThrough else local.handledThrough,
            autoPostFrom = local.autoPostFrom,
        )

        private fun personKey(id: PersonId) = ImportRowKey(EntityType.PERSON, id.value)

        private fun groupKey(id: GroupId) = ImportRowKey(EntityType.GROUP, id.value)

        /** The rows [record] references by ID, as keys. */
        private fun references(record: LedgerEntity): Set<ImportRowKey> = when (record) {
            is Person -> setOfNotNull(record.mergedIntoId?.let(::personKey))
            is PaymentHandle -> setOf(personKey(record.personId))
            is GroupMember -> setOf(personKey(record.personId), groupKey(record.groupId))
            is Transfer -> setOfNotNull(personKey(record.fromPersonId), personKey(record.toPersonId), record.groupId?.let(::groupKey))
            is Expense -> record.personIds.map(::personKey).toSet() + setOfNotNull(
                record.groupId?.let(::groupKey),
                record.recurringId?.let { ImportRowKey(EntityType.RECURRING_EXPENSE, it.value) },
            )
            is RecurringExpense -> record.personIds.map(::personKey).toSet() + setOfNotNull(record.groupId?.let(::groupKey))
            else -> emptySet()
        }

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
            val handles = incoming.handles.map { row -> row.mapped { it.copy(personId = mapped(it.personId)) } }
            val groups = incoming.groups
            val members = incoming.members.map { row -> row.mapped { it.copy(personId = mapped(it.personId)) } }
            val sourceDeletedAt = (people.map { it.record } + handles.map { it.record } + groups.map { it.record } + members.map { it.record })
                .associate { it.rowKey to it.meta.deletedAt }
            val transfers = incoming.transfers.map { row -> row.mapped { it.copy(fromPersonId = mapped(it.fromPersonId), toPersonId = mapped(it.toPersonId)) } }
            val expenses = incoming.expenses.map { row ->
                row.mapped { e ->
                    e.copy(
                        payers = e.payers.map { it.copy(personId = mapped(it.personId)) },
                        shares = e.shares.map { it.copy(personId = mapped(it.personId)) },
                    )
                }
            }

            val schedules = incoming.schedules.map { row ->
                row.mapped { s -> s.copy(participants = s.participants.map { it.copy(personId = mapped(it.personId)) }) }
            }

            var duplicates = 0
            val duplicateKeys = mutableSetOf<ImportRowKey>()
            fun <T : LedgerEntity> classify(rows: List<Incoming<T>>, local: (T) -> T?): List<Classified<T>> = rows.mapNotNull { row ->
                val existing = local(row.record)
                when {
                    existing == null -> Classified(row, null, Classification.NEW)
                    same(existing, row.record) -> {
                        duplicates++
                        duplicateKeys += row.record.rowKey
                        null
                    }
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
            var classifiedGroups = classify(groups) { state.groups[it.id] }
            var classifiedMembers = classify(members) { state.members[it.id] }
            var classifiedTransfers = classify(transfers) { state.transfers[it.id] }
            var classifiedExpenses = classify(expenses) { state.expenses[it.id] }
            var classifiedSchedules = schedules.mapNotNull { row ->
                val local = state.schedules[row.record.id]
                when {
                    local == null -> Classified(row, null, Classification.NEW)
                    !same(local.comparable(), row.record.comparable()) -> Classified(row, local, Classification.CONFLICT)
                    movesForward(local, row.record) -> Classified(row, local, Classification.FORWARD)
                    else -> { duplicates++; null }
                }
            }

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
            classifiedGroups = classifiedGroups.checked()
            classifiedMembers = classifiedMembers.checked()
            classifiedTransfers = classifiedTransfers.checked()
            classifiedExpenses = classifiedExpenses.checked()
            classifiedSchedules = classifiedSchedules.checked()

            // State invariants (spec §3.4) over the complete state the import would produce with every conflict
            // taken, cascades included. Only when it breaks one are rows blamed: a row is to blame for a violation
            // that goes away without it. Leaving out a new row (a person, a group or a schedule) would leave the rows that name
            // it dangling, and the validator then reports only that broken structure (rule 15), hiding every other
            // violation; so a new row is left out together with those rows, and is to blame only for a violation
            // that stays while just those rows are left out. People, handles, groups and memberships answer first,
            // for the violations that also remain with the incoming active entries left out; incoming deletions stay
            // in, as they can be what makes a deletion valid (a person deleted together with their transfers).
            // Entries and schedules answer for the rest. Each round repeats until nothing is broken or no row is to blame, and
            // both passes run again while either blames a row, since one broken rule (above all a dangling
            // reference) can hide others.
            val rejected = mutableSetOf<ImportRowKey>()
            fun <T : LedgerEntity> List<Classified<T>>.kept() = filter { it.key !in rejected }
            fun violations(withActiveEntries: Boolean, without: Set<ImportRowKey> = emptySet()): Set<RuleViolation> {
                fun <T : LedgerEntity> List<Classified<T>>.taken(entries: Boolean) =
                    kept().filter { it.key !in without && (!entries || withActiveEntries || it.record.isDeleted) }
                val rows = ImportAnalysis(
                    state, fileName, mapFrom, classifiedPeople.taken(false), classifiedHandles.taken(false),
                    classifiedGroups.taken(false), classifiedMembers.taken(false), newSnapshots,
                    classifiedTransfers.taken(true), classifiedExpenses.taken(true), classifiedSchedules.taken(true),
                    emptyList(), 0, emptySet(), sourceDeletedAt,
                )
                val take = rows.rows.filter { it.kind == Classification.CONFLICT }.map { it.key }.toSet()
                val after = rows.plan(take, PREVIEW_BATCH, Instant.EPOCH, PREVIEW_DAY).state
                return LedgerValidator.validate(state, after, WriteOp.Import).toSet()
            }
            fun keptRows(): List<Classified<*>> = classifiedPeople.kept() + classifiedHandles.kept() + classifiedGroups.kept() +
                classifiedMembers.kept() + classifiedTransfers.kept() + classifiedExpenses.kept() + classifiedSchedules.kept()
            // The kept rows that name [row] if it is new, directly or through other new rows.
            fun dependents(row: Classified<*>): Set<ImportRowKey> {
                val rows = keptRows()
                val gone = mutableSetOf<ImportRowKey>()
                val found = mutableSetOf<ImportRowKey>()
                var next: List<Classified<*>> = listOf(row)
                while (next.isNotEmpty()) {
                    gone += next.filter { it.local == null }.map { it.key }
                    next = rows.filter { it.key != row.key && it.key !in found && references(it.record).any(gone::contains) }
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
                    classifiedPeople + classifiedHandles + classifiedGroups + classifiedMembers,
                    broken = { violations(withActiveEntries = false) intersect violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = false, without = it) },
                )
                val entries = blame(
                    classifiedTransfers + classifiedExpenses + classifiedSchedules,
                    broken = { violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = true, without = it) },
                )
                if (!owners && !entries) break
            }
            classifiedPeople = classifiedPeople.kept()
            classifiedHandles = classifiedHandles.kept()
            classifiedGroups = classifiedGroups.kept()
            classifiedMembers = classifiedMembers.kept()
            classifiedTransfers = classifiedTransfers.kept()
            classifiedExpenses = classifiedExpenses.kept()
            classifiedSchedules = classifiedSchedules.kept()

            // The file's deletedAt counts only for a duplicate or a kept row: a row rejected as invalid (and skipped),
            // owner or owned, must not move a local row into or out of a cascade (spec §7.4, §7.5). Without its
            // owner's time, an owned row the file shows deleted counts as deleted apart from it.
            val kept = (classifiedPeople + classifiedHandles + classifiedGroups + classifiedMembers).map { it.key }.toSet() + duplicateKeys
            val keptDeletedAt = sourceDeletedAt.filterKeys { it in kept }

            return ImportAnalysis(
                state, fileName, mapFrom, classifiedPeople, classifiedHandles, classifiedGroups, classifiedMembers, newSnapshots,
                classifiedTransfers, classifiedExpenses, classifiedSchedules, invalid.sortedWith(compareBy({ it.table }, { it.rowNumber })), duplicates,
                mappedKeys, keptDeletedAt,
            )
        }

        private fun describe(violation: RuleViolation) =
            if (violation.rule == 0) "invalid field: ${violation.error}" else "breaks rule ${violation.rule}: ${violation.error}"
    }
}
