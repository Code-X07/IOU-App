package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.MergeProblem
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.Transfer

/** The [E] rules of spec §3.4: only the changed entities and the operation's preconditions. */
internal object EditRules {

    fun check(before: LedgerState, after: LedgerState, changes: List<Change>): List<RuleViolation> {
        val out = mutableListOf<RuleViolation>()
        fun add(rule: Int, error: LedgerError) { out += RuleViolation(rule, error) }

        val resolver = IdentityResolver(after)
        val me = after.me.id

        for (change in changes) {
            val new = change.entity
            val old = before.entity(new.entityType, new.entityId)
            when (new) {
                is GroupMember -> {
                    val wasActive = (old as GroupMember?)?.removedAt == null && old != null
                    if (wasActive && new.removedAt != null && resolver.canonical(new.personId) == me) {
                        add(2, LedgerError.CannotModifyMe)
                    }
                }

                is Entry -> {
                    for (version in listOfNotNull(old as Entry?, new)) {
                        val groupId = version.groupId ?: continue
                        val removed = version.personIds.map(resolver::canonical)
                            .filter { resolver.hasMembership(groupId, it) && !resolver.isActiveMember(groupId, it) }
                            .toSet()
                        if (removed.isNotEmpty()) add(5, LedgerError.MemberRemoved(groupId, removed))
                    }
                    if (new is Transfer &&
                        (change.action == RevisionAction.CREATED || change.action == RevisionAction.UPDATED) &&
                        resolver.canonical(new.fromPersonId) == resolver.canonical(new.toPersonId)
                    ) {
                        add(13, LedgerError.SelfTransfer)
                    }
                }

                is Group -> {
                    val previous = old as Group?
                    if (previous != null && before.entries.any { it.groupId == new.id }) {
                        if (previous.fxMode != new.fxMode) add(6, LedgerError.GroupSettingsLocked(new.id))
                        if (previous.fxMode == FxMode.CONVERT && previous.defaultCurrency != new.defaultCurrency) {
                            add(7, LedgerError.GroupSettingsLocked(new.id))
                        }
                    }
                    val groupReferenced = after.entries.any { it.groupId == new.id } || after.schedules.values.any { it.groupId == new.id }
                    if (previous != null && !previous.isDeleted && new.isDeleted && groupReferenced) {
                        add(10, LedgerError.StillReferenced(new.id.value))
                    }
                }

                is Person -> checkPerson(before, after, old as Person?, new, ::add)

                is PaymentHandle -> Unit

                // Template edits break no edit rule; posting checks its expense like any other create (spec §5.7).
                is RecurringExpense -> Unit
            }
        }
        return out
    }

    private fun checkPerson(
        before: LedgerState,
        after: LedgerState,
        old: Person?,
        new: Person,
        add: (Int, LedgerError) -> Unit,
    ) {
        val newlyDeleted = old != null && !old.isDeleted && new.isDeleted

        // Rule 9
        if (new.isMe) {
            val archived = old?.archivedAt == null && new.archivedAt != null
            if (newlyDeleted || archived || new.mergedIntoId != null) add(9, LedgerError.CannotModifyMe)
        }
        val target = new.mergedIntoId?.let { before.people[it] }
        if (old?.mergedIntoId != new.mergedIntoId && target?.isMe == true) add(9, LedgerError.CannotModifyMe)

        // Rule 10
        if (newlyDeleted) {
            val referenced = after.entries.any { new.id in it.personIds } ||
                after.schedules.values.any { new.id in it.personIds } ||
                after.members.values.any { it.personId == new.id } ||
                after.people.values.any { !it.isDeleted && it.mergedIntoId == new.id }
            if (referenced) add(10, LedgerError.StillReferenced(new.id.value))
        }

        // Rule 11 (merge preconditions; merging me is rule 9)
        val targetId = new.mergedIntoId
        if (old != null && targetId != null && old.mergedIntoId != targetId && !new.isMe && target?.isMe != true) {
            when {
                targetId == new.id -> add(11, LedgerError.InvalidMerge(MergeProblem.SAME_PERSON))
                old.mergedIntoId != null -> add(11, LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL))
                target == null -> Unit // reported by rule 15
                old.isDeleted || target.isDeleted -> add(11, LedgerError.InvalidMerge(MergeProblem.DELETED))
                target.mergedIntoId != null -> add(11, LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL))
            }
        }
    }
}
