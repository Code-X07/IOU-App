package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money

/** [debtor] owes [creditor] [amount] because of one entry (spec §4.2). Person IDs are canonical. */
data class Effect(
    val creditor: PersonId,
    val debtor: PersonId,
    val amount: Money,
    val groupId: GroupId?,
)

/** Every balance of one ledger state. Maps never contain zero amounts. Person keys are canonical. */
data class Balances(
    val me: PersonId,
    /** `net(me, X)` per canonical X: positive means X owes me. Non-group effects only. */
    val pairwise: Map<PersonId, Map<CurrencyCode, Long>>,
    /** `groupNet(M)` per group and member: positive means the group owes M. */
    val groupNets: Map<GroupId, Map<PersonId, Map<CurrencyCode, Long>>>,
    /** Gross amounts per group and (creditor, debtor) pair: "who paid for whom". */
    val flows: Map<GroupId, Map<Pair<PersonId, PersonId>, Map<CurrencyCode, Long>>>,
) {
    fun net(other: PersonId): Map<CurrencyCode, Long> = pairwise[other].orEmpty()

    fun groupNet(groupId: GroupId, person: PersonId): Map<CurrencyCode, Long> =
        groupNets[groupId]?.get(person).orEmpty()

    fun myGroupNet(groupId: GroupId): Map<CurrencyCode, Long> = groupNet(groupId, me)

    /** Σ positive `net(me, X)` + Σ over groups of `max(groupNet(me), 0)`, per currency. */
    val owedToMe: Map<CurrencyCode, Long> = totals(positive = true)

    /** Σ |negative `net(me, X)`| + Σ over groups of |`min(groupNet(me), 0)`|, per currency, as magnitudes. */
    val iOwe: Map<CurrencyCode, Long> = totals(positive = false)

    private fun totals(positive: Boolean): Map<CurrencyCode, Long> {
        val sums = Sums<Unit>()
        val myPositions = pairwise.values.asSequence() + groupNets.values.asSequence().map { it[me].orEmpty() }
        for (position in myPositions) {
            for ((currency, amount) in position) {
                if (positive && amount > 0) sums.add(Unit, currency, amount)
                if (!positive && amount < 0) sums.add(Unit, currency, Math.negateExact(amount))
            }
        }
        return sums.result()[Unit].orEmpty()
    }
}

/** Pure balance computation (spec §4.2, §4.3). Recomputed on every emission. */
object LedgerEngine {

    fun effects(state: LedgerState, resolver: IdentityResolver = IdentityResolver(state)): List<Effect> =
        state.entries.filter { !it.isDeleted }.flatMap { entryEffects(it, resolver) }

    fun entryEffects(entry: Entry, resolver: IdentityResolver): List<Effect> = when (entry) {
        is Transfer -> {
            val from = resolver.canonical(entry.fromPersonId)
            val to = resolver.canonical(entry.toPersonId)
            if (from == to) emptyList() else listOf(Effect(from, to, entry.effectAmount, entry.groupId))
        }
        is Expense -> {
            val payer = resolver.canonical(entry.payerPersonId)
            entry.shares
                .filter { it.shareMinor > 0 }
                .mapNotNull { share ->
                    val debtor = resolver.canonical(share.personId)
                    if (debtor == payer) null
                    else Effect(payer, debtor, Money(share.shareMinor, entry.effectTotal.currency), entry.groupId)
                }
        }
    }

    /** Computes every balance of [state]. Throws [ArithmeticException] when any balance or total overflows. */
    fun compute(state: LedgerState): Balances {
        val resolver = IdentityResolver(state)
        val me = resolver.canonical(state.me.id)
        val pairwise = Sums<PersonId>()
        val groupNets = HashMap<GroupId, Sums<PersonId>>()
        val flows = HashMap<GroupId, Sums<Pair<PersonId, PersonId>>>()

        for (effect in effects(state, resolver)) {
            val currency = effect.amount.currency
            val amount = effect.amount.amountMinor
            val groupId = effect.groupId
            if (groupId == null) {
                if (effect.creditor == me) pairwise.add(effect.debtor, currency, amount)
                if (effect.debtor == me) pairwise.add(effect.creditor, currency, Math.negateExact(amount))
            } else {
                val nets = groupNets.getOrPut(groupId) { Sums() }
                nets.add(effect.creditor, currency, amount)
                nets.add(effect.debtor, currency, Math.negateExact(amount))
                flows.getOrPut(groupId) { Sums() }.add(effect.creditor to effect.debtor, currency, amount)
            }
        }

        return Balances(
            me = me,
            pairwise = pairwise.result(),
            groupNets = groupNets.mapValues { it.value.result() }.filterValues { it.isNotEmpty() },
            flows = flows.mapValues { it.value.result() }.filterValues { it.isNotEmpty() },
        )
    }

    /** `net(a, b)` over non-group effects, for any two canonical people (used by property tests and views). */
    fun nonGroupNet(effects: List<Effect>, a: PersonId, b: PersonId): Map<CurrencyCode, Long> {
        val sums = Sums<Unit>()
        for (effect in effects) {
            if (effect.groupId != null) continue
            if (effect.creditor == a && effect.debtor == b) sums.add(Unit, effect.amount.currency, effect.amount.amountMinor)
            if (effect.creditor == b && effect.debtor == a) {
                sums.add(Unit, effect.amount.currency, Math.negateExact(effect.amount.amountMinor))
            }
        }
        return sums.result()[Unit].orEmpty()
    }
}

/** Exact per-key, per-currency sums; drops zero results. */
internal class Sums<K> {
    private val sums = LinkedHashMap<K, LinkedHashMap<CurrencyCode, Long>>()

    fun add(key: K, currency: CurrencyCode, amount: Long) {
        val perCurrency = sums.getOrPut(key) { LinkedHashMap() }
        perCurrency[currency] = Math.addExact(perCurrency[currency] ?: 0L, amount)
    }

    fun result(): Map<K, Map<CurrencyCode, Long>> =
        sums.mapValues { (_, perCurrency) -> perCurrency.filterValues { it != 0L } }.filterValues { it.isNotEmpty() }
}
