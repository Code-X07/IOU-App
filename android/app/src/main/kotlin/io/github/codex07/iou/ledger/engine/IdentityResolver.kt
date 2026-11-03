package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.PersonId

/**
 * Canonical person IDs for one ledger state (spec §4.1). Built once per state; every read and
 * every validation resolves person IDs through it.
 */
class IdentityResolver(private val state: LedgerState) {

    private val canonicalOf: Map<PersonId, PersonId>

    /** People whose merge chain loops or points at a missing row (reported by rule 15). */
    val brokenChains: Set<PersonId>

    init {
        val resolved = HashMap<PersonId, PersonId>()
        val broken = HashSet<PersonId>()
        for (start in state.people.keys) {
            val seen = HashSet<PersonId>()
            var current = start
            while (true) {
                if (!seen.add(current)) {
                    broken += start
                    current = start
                    break
                }
                val next = state.people[current]?.mergedIntoId ?: break
                if (next !in state.people) {
                    broken += start
                    break
                }
                current = next
            }
            resolved[start] = current
        }
        canonicalOf = resolved
        brokenChains = broken
    }

    private val recordsByCanonical: Map<PersonId, Set<PersonId>> =
        canonicalOf.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }

    fun canonical(id: PersonId): PersonId = canonicalOf[id] ?: id

    /** Every person record that resolves to [canonical], itself included. */
    fun recordsOf(canonical: PersonId): Set<PersonId> = recordsByCanonical[canonical] ?: setOf(canonical)

    fun handlesOf(canonical: PersonId): List<PaymentHandle> =
        state.handles.values
            .filter { !it.isDeleted && canonical(it.personId) == canonical }
            .sortedBy { it.id.value }

    /** Own record's default first, otherwise the default of the alias with the lowest UUID (spec §3.2). */
    fun defaultHandle(canonical: PersonId, type: HandleType): PaymentHandle? {
        val defaults = handlesOf(canonical).filter { it.type == type && it.isDefault }
        return defaults.firstOrNull { it.personId == canonical }
            ?: defaults.minWithOrNull(compareBy({ it.personId.value }, { it.id.value }))
    }

    fun memberships(groupId: GroupId, canonical: PersonId): List<GroupMember> =
        state.members.values.filter { it.groupId == groupId && !it.isDeleted && canonical(it.personId) == canonical }

    fun hasMembership(groupId: GroupId, canonical: PersonId): Boolean = memberships(groupId, canonical).isNotEmpty()

    /** Canonical membership (spec §3.4): active if any record has an active membership. */
    fun isActiveMember(groupId: GroupId, canonical: PersonId): Boolean =
        memberships(groupId, canonical).any { it.removedAt == null }

    fun canonicalMembers(groupId: GroupId): Set<PersonId> =
        state.members.values
            .filter { it.groupId == groupId && !it.isDeleted }
            .map { canonical(it.personId) }
            .toSet()
}
