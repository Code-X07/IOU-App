package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer

/** The [S] rules of spec §3.4. */
internal object StateInvariants {

    /** Rule 15, over every row including deleted ones. */
    fun structure(state: LedgerState): List<RuleViolation> {
        val problems = mutableListOf<String>()
        val meCount = state.people.values.count { it.isMe }
        if (meCount != 1) problems += "expected exactly one me, found $meCount"

        fun person(id: PersonId, owner: String) {
            if (id !in state.people) problems += "$owner references missing person $id"
        }
        fun group(id: GroupId?, owner: String) {
            if (id != null && id !in state.groups) problems += "$owner references missing group $id"
        }
        fun snapshot(id: FxSnapshotId?, owner: String) {
            if (id != null && id !in state.fxSnapshots) problems += "$owner references missing FX snapshot $id"
        }

        state.people.values.forEach { p -> p.mergedIntoId?.let { person(it, p.id.value) } }
        state.handles.values.forEach { person(it.personId, it.id.value) }
        state.members.values.forEach {
            person(it.personId, it.id.value)
            group(it.groupId, it.id.value)
        }
        state.transfers.values.forEach {
            person(it.fromPersonId, it.id.value)
            person(it.toPersonId, it.id.value)
            group(it.groupId, it.id.value)
            snapshot(it.fxSnapshotId, it.id.value)
        }
        state.expenses.values.forEach { e ->
            e.payers.forEach { person(it.personId, e.id.value) }
            e.shares.forEach { person(it.personId, e.id.value) }
            group(e.groupId, e.id.value)
            snapshot(e.fxSnapshotId, e.id.value)
        }
        IdentityResolver(state).brokenChains.sortedBy { it.value }.forEach {
            problems += "merge chain of $it loops or ends at a missing person"
        }
        return problems.map { RuleViolation(15, LedgerError.CorruptData(it)) }
    }

    /** Every other [S] rule. Assumes [structure] passed. [balances] is null when they overflowed. */
    fun check(state: LedgerState, resolver: IdentityResolver, balances: Balances?): List<RuleViolation> {
        val out = mutableListOf<RuleViolation>()
        fun add(rule: Int, error: LedgerError) { out += RuleViolation(rule, error) }

        val me = state.me
        val liveEntries = state.entries.filter { !it.isDeleted }
        val liveGroups = state.groups.values.filter { !it.isDeleted }

        // Rule 1
        for (entry in liveEntries.filter { it.groupId == null }) {
            val people = entry.personIds.map(resolver::canonical).toSet()
            val ok = when (entry) {
                is Transfer -> me.id in people
                is Expense -> people.size == 2 && me.id in people
            }
            if (!ok) add(1, LedgerError.NeedsGroup(people))
        }

        // Rule 2
        for (group in liveGroups) {
            if (!resolver.isActiveMember(group.id, me.id)) add(2, LedgerError.CannotModifyMe)
        }

        // Rule 3
        for (entry in liveEntries) {
            val groupId = entry.groupId ?: continue
            val missing = entry.personIds.map(resolver::canonical).filterNot { resolver.hasMembership(groupId, it) }.toSet()
            if (missing.isNotEmpty()) add(3, LedgerError.NotGroupMember(groupId, missing))
        }

        // Rule 4
        if (balances != null) {
            for (group in liveGroups) {
                for (person in resolver.canonicalMembers(group.id).sortedBy { it.value }) {
                    if (resolver.isActiveMember(group.id, person)) continue
                    val nets = balances.groupNet(group.id, person)
                    if (nets.isNotEmpty()) add(4, LedgerError.MemberHasBalance(group.id, person, nets))
                }
            }
        }

        // Rule 8
        for (entry in liveEntries) {
            val group = entry.groupId?.let { state.groups[it] } ?: continue
            if (group.fxMode == FxMode.CONVERT && entry.effect.currency != group.defaultCurrency) {
                add(8, LedgerError.FxSnapshotMismatch(entry.id))
            }
        }

        // Rule 9
        if (me.isDeleted || me.mergedIntoId != null) add(9, LedgerError.CannotModifyMe)

        // Rule 10
        for (handle in state.handles.values.filter { !it.isDeleted }) {
            if (state.people.getValue(handle.personId).isDeleted) {
                add(10, LedgerError.DeletedReference(setOf(handle.id.value, handle.personId.value)))
            }
        }
        for (member in state.members.values.filter { !it.isDeleted }) {
            val dead = listOfNotNull(
                member.personId.value.takeIf { state.people.getValue(member.personId).isDeleted },
                member.groupId.value.takeIf { state.groups.getValue(member.groupId).isDeleted },
            )
            if (dead.isNotEmpty()) add(10, LedgerError.DeletedReference(setOf(member.id.value) + dead))
        }
        for (entry in liveEntries) {
            val dead = entry.personIds.filter { state.people.getValue(it).isDeleted }.map { it.value } +
                listOfNotNull(entry.groupId?.takeIf { state.groups.getValue(it).isDeleted }?.value)
            if (dead.isNotEmpty()) add(10, LedgerError.DeletedReference(setOf(entry.id.value) + dead))
        }

        // Rule 11
        for (person in state.people.values.filter { !it.isDeleted && it.mergedIntoId != null }) {
            val canonical = resolver.canonical(person.id)
            if (state.people.getValue(canonical).isDeleted) {
                add(11, LedgerError.DeletedReference(setOf(person.id.value, canonical.value)))
            }
        }

        // Rules 14, 16, 17
        for (entry in liveEntries) fxRule(state, entry)?.let { (rule, error) -> add(rule, error) }

        return out
    }

    private fun fxRule(state: LedgerState, entry: Entry): Pair<Int, LedgerError>? {
        val mismatch = LedgerError.FxSnapshotMismatch(entry.id)
        val converted = entry.effect.currency != entry.paid.currency
        val snapshotId = entry.fxSnapshotId
        if (snapshotId == null) {
            if (converted) return 14 to mismatch
            return if (entry.effect != entry.paid) 17 to mismatch else null
        }
        if (!converted) return 14 to mismatch
        val snapshot = state.fxSnapshots.getValue(snapshotId)
        if (snapshot.baseCurrency != entry.paid.currency || snapshot.quoteCurrency != entry.effect.currency) {
            return 14 to mismatch
        }
        return when (snapshot.source) {
            FxSource.BANK_ACTUAL ->
                if (snapshot.paidMinor == entry.paid.amountMinor && snapshot.chargedMinor == entry.effect.amountMinor) null
                else 16 to mismatch
            FxSource.ECB_FRANKFURTER, FxSource.USER_ENTERED -> {
                val expected = FxConverter.convert(entry.paid, entry.effect.currency, snapshot.rate)
                if (expected == LedgerResult.Ok(entry.effect)) null else 17 to mismatch
            }
        }
    }
}
