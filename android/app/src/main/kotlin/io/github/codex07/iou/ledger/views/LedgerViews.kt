package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.AliasView
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.GroupDetail
import io.github.codex07.iou.api.GroupNetView
import io.github.codex07.iou.api.GroupRef
import io.github.codex07.iou.api.GroupSuggestion
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.FlowView
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MemberView
import io.github.codex07.iou.api.MergePreview
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.OverviewRow
import io.github.codex07.iou.api.PaymentHandleView
import io.github.codex07.iou.api.PersonDetail
import io.github.codex07.iou.api.PersonRef
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.DiffNames
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant

/**
 * Every `api` read model of one ledger snapshot (spec §4.3, §6.2). Pure; shared by the fake and the
 * real implementation. Requires setup to have run (exactly one me) for anything that needs balances.
 */
class LedgerViews(
    val state: LedgerState,
    val revisions: List<EntryRevision>,
    val generation: LedgerGeneration,
    val homeCurrency: CurrencyCode,
    private val rateToHome: (CurrencyCode) -> String?,
) {
    val resolver = IdentityResolver(state)
    val balances: Balances by lazy { LedgerEngine.compute(state) }
    internal val meId: PersonId by lazy { resolver.canonical(state.me.id) }

    /** Diffs name the record a snapshot references, not the person it resolves to today. */
    internal val names = object : DiffNames {
        override fun person(id: PersonId) = rawRef(id).displayName
        override fun group(id: GroupId) = groupRef(id).name
    }

    // ---- references and helpers ----

    /** The canonical person [id] resolves to. */
    fun personRef(id: PersonId): PersonRef = rawRef(resolver.canonical(id))

    /** The person record itself (used for aliases and handle owners). */
    fun rawRef(id: PersonId): PersonRef {
        val person = state.people[id]
        return PersonRef(id, person?.displayName ?: id.value, person?.isMe == true)
    }

    fun groupRef(id: GroupId) = GroupRef(id, state.groups[id]?.name ?: id.value)

    fun token(entity: LedgerEntity) = EditToken(generation, entity.meta.version)

    internal fun amounts(map: Map<CurrencyCode, Long>): List<Money> =
        map.entries.sortedBy { it.key.code }.map { (currency, amount) -> Money(amount, currency) }

    /** Σ |amount| converted to the home currency with the latest rates; null when a rate is missing (spec §4.3). */
    internal fun approxHome(map: Map<CurrencyCode, Long>): Money? {
        var total = 0L
        for ((currency, amount) in map) {
            val rate = rateToHome(currency) ?: return null
            val magnitude = Money(if (amount < 0) -amount else amount, currency)
            val converted = when (val result = FxConverter.convert(magnitude, homeCurrency, rate)) {
                is LedgerResult.Ok -> result.value.amountMinor
                is LedgerResult.Err -> if (result.error is LedgerError.ConvertedToZero) 0L else return null
            }
            total = Math.addExact(total, converted)
        }
        return Money(total, homeCurrency)
    }

    private fun canonicalPeople(includeDeleted: Boolean = false): List<Person> =
        state.people.values
            .filter { !it.isMe && (includeDeleted || !it.isDeleted) && it.mergedIntoId == null }
            .sortedBy { it.displayName.lowercase() }

    // ---- overview and people ----

    fun overview(): Overview {
        val people = canonicalPeople().mapNotNull { person ->
            val net = balances.net(person.id)
            val archived = person.archivedAt != null
            if (archived && net.isEmpty()) null
            else OverviewRow.PersonRow(rawRef(person.id), amounts(net), archived, approxHome(net))
        }
        val groups = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
            val net = balances.myGroupNet(group.id)
            val archived = group.archivedAt != null
            if (archived && net.isEmpty()) null
            else OverviewRow.GroupRow(groupRef(group.id), amounts(net), archived, approxHome(net))
        }
        val rows = (people + groups).sortedByDescending { it.approxHome?.amountMinor ?: -1L }
        return Overview(
            generation = generation,
            homeCurrency = homeCurrency,
            owedToMe = amounts(balances.owedToMe),
            iOwe = amounts(balances.iOwe),
            approxOwedToMe = approxHome(balances.owedToMe),
            approxIOwe = approxHome(balances.iOwe),
            rows = rows,
        )
    }

    /** Deleted people only with [includeDeleted]: the UI's way back to a deleted person's history and restore. */
    fun people(includeArchived: Boolean, includeDeleted: Boolean = false): List<PersonSummary> =
        canonicalPeople(includeDeleted)
            .filter { includeArchived || it.archivedAt == null }
            .map {
                PersonSummary(rawRef(it.id), amounts(balances.net(it.id)), it.archivedAt != null, it.isDeleted, resolver.recordsOf(it.id).size - 1)
            }

    fun person(id: PersonId): PersonDetail? {
        val canonical = resolver.canonical(id)
        val person = state.people[canonical] ?: return null
        val inGroups = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
            val items = groupPlan(group.id).filter { setOf(it.from.id, it.to.id) == setOf(meId, canonical) }
            if (items.isEmpty()) null else GroupSuggestion(groupRef(group.id), items)
        }
        return PersonDetail(
            generation = generation,
            person = rawRef(canonical),
            note = person.note,
            defaultCurrency = person.defaultCurrency,
            archived = person.archivedAt != null,
            deleted = person.isDeleted,
            token = token(person),
            aliases = (resolver.recordsOf(canonical) - canonical).sortedBy { it.value }.map { aliasId ->
                val alias = state.people.getValue(aliasId)
                AliasView(rawRef(aliasId), rawRef(alias.mergedIntoId!!), token(alias))
            },
            balances = amounts(balances.net(canonical)),
            inGroups = inGroups,
            handles = resolver.handlesOf(canonical).map { handleView(it, canonical) },
            activity = activity(ActivityFilter(personId = canonical)),
            history = history(personHistoryRefs(canonical)),
        )
    }

    /** The person's records (aliases included) and every handle they own, deleted ones too (`PersonDetail.history`). */
    private fun personHistoryRefs(canonical: PersonId): List<EntityRef> {
        val records = resolver.recordsOf(canonical)
        return records.map { EntityRef(EntityType.PERSON, it.value) } +
            state.handles.values.filter { it.personId in records }.map { EntityRef(EntityType.PAYMENT_HANDLE, it.id.value) }
    }

    fun handleView(handle: PaymentHandle, canonical: PersonId) = PaymentHandleView(
        id = handle.id,
        owner = rawRef(handle.personId),
        type = handle.type,
        value = handle.value,
        label = handle.label,
        holderName = handle.holderName,
        bic = handle.bic,
        isDefault = resolver.defaultHandle(canonical, handle.type)?.id == handle.id,
        token = token(handle),
    )

    /** Previews a merge by running the merge op on a copy; nothing is written (spec §5.6). */
    fun mergePreview(source: PersonId, target: PersonId): LedgerResult<MergePreview> = ledgerResult {
        val from = state.people[source] ?: fail(LedgerError.NotFound(source.value))
        val ctx = WriteContext(state, generation, Instant.EPOCH, IdGenerator.Random)
        val plan = PeopleOps.merge(ctx, source, target, token(from)).bind().plan
        val after = LedgerViews(state.applying(plan.changes), revisions, generation, homeCurrency, rateToHome)
        MergePreview(
            source = rawRef(source),
            target = rawRef(target),
            combinedBalances = after.amounts(after.balances.net(target)),
            combinedGroupNets = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
                val nets = after.balances.groupNet(group.id, target)
                if (nets.isEmpty()) null else GroupNetView(groupRef(group.id), after.amounts(nets))
            },
            // Involving both people isn't enough (C pays, split between them, still counts): the entry must
            // move a balance today and none after the merge.
            neutralizedEntries = state.entries
                .filter { !it.isDeleted }
                .filter { LedgerEngine.entryEffects(it, resolver).isNotEmpty() && LedgerEngine.entryEffects(it, after.resolver).isEmpty() }
                .map { activityItem(it) },
            defaultHandles = HandleType.entries.mapNotNull { type ->
                after.resolver.defaultHandle(target, type)?.let { after.handleView(it, target) }
            },
        )
    }

    // ---- groups ----

    fun groups(includeArchived: Boolean, includeDeleted: Boolean = false): List<GroupSummary> =
        state.groups.values
            .filter { (includeDeleted || !it.isDeleted) && (includeArchived || it.archivedAt == null) }
            .sortedBy { it.name.lowercase() }
            .map { group ->
                GroupSummary(
                    group = groupRef(group.id),
                    defaultCurrency = group.defaultCurrency,
                    fxMode = group.fxMode,
                    archived = group.archivedAt != null,
                    deleted = group.isDeleted,
                    myNets = amounts(balances.myGroupNet(group.id)),
                    activeMemberCount = resolver.canonicalMembers(group.id).count { resolver.isActiveMember(group.id, it) },
                )
            }

    fun group(id: GroupId): GroupDetail? {
        val group = state.groups[id] ?: return null
        val members = resolver.canonicalMembers(id)
            .map { MemberView(personRef(it), resolver.isActiveMember(id, it), amounts(balances.groupNet(id, it))) }
            .sortedBy { it.person.displayName.lowercase() }
        val flows = balances.flows[id].orEmpty().flatMap { (pair, perCurrency) ->
            amounts(perCurrency).map { FlowView(personRef(pair.first), personRef(pair.second), it) }
        }
        return GroupDetail(
            generation = generation,
            group = groupRef(id),
            defaultCurrency = group.defaultCurrency,
            fxMode = group.fxMode,
            archived = group.archivedAt != null,
            deleted = group.isDeleted,
            settingsLocked = state.entries.any { it.groupId == id },
            token = token(group),
            members = members,
            settlePlan = groupPlan(id),
            activity = activity(ActivityFilter(groupId = id)),
            flows = flows,
            history = history(
                listOf(EntityRef(EntityType.GROUP, id.value)) +
                    state.members.values.filter { it.groupId == id }.map { EntityRef(EntityType.GROUP_MEMBER, it.id.value) },
            ),
        )
    }

    fun groupPlan(groupId: GroupId): List<PlanItem> =
        SettlePlanner.planGroup(balances.groupNets[groupId].orEmpty())
            .map { PlanItem(generation, personRef(it.from), personRef(it.to), it.amount, groupRef(groupId)) }
}
