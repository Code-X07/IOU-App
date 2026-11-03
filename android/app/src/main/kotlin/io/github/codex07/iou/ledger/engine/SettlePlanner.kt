package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.MAX_MINOR
import io.github.codex07.iou.money.Money

/** A suggested transfer: [from] pays [to] (spec §4.6). Never stored. */
data class PlannedTransfer(val from: PersonId, val to: PersonId, val amount: Money)

/** Greedy settle plans (spec §4.6). */
object SettlePlanner {

    /**
     * Plans a group settle-up from [nets] (positive: the group owes the person). The nets must sum to 0 per
     * currency, as `Balances.groupNets` do; otherwise the unmatched remainder is left out of the plan.
     */
    fun planGroup(nets: Map<PersonId, Map<CurrencyCode, Long>>): List<PlannedTransfer> =
        nets.values
            .flatMap { it.keys }
            .distinct()
            .sortedBy { it.code }
            .flatMap { currency -> planCurrency(currency, nets.mapValues { it.value[currency] ?: 0L }) }

    fun planPerson(me: PersonId, other: PersonId, net: Map<CurrencyCode, Long>): List<PlannedTransfer> =
        net.entries
            .sortedBy { it.key.code }
            .flatMap { (currency, amount) ->
                when {
                    amount > 0 -> chunks(other, me, amount, currency)
                    amount < 0 -> chunks(me, other, Math.negateExact(amount), currency)
                    else -> emptyList()
                }
            }

    private fun planCurrency(currency: CurrencyCode, nets: Map<PersonId, Long>): List<PlannedTransfer> {
        val credits = nets.filterValues { it > 0 }.toMutableMap()
        val debts = nets.filterValues { it < 0 }.mapValues { Math.negateExact(it.value) }.toMutableMap()
        // Largest amount first; on equal amounts the lowest person ID wins.
        val largest = compareBy<Map.Entry<PersonId, Long>> { it.value }.thenByDescending { it.key.value }
        val plan = mutableListOf<PlannedTransfer>()

        while (credits.isNotEmpty() && debts.isNotEmpty()) {
            val creditor = credits.entries.maxWith(largest).key
            val debtor = debts.entries.maxWith(largest).key
            val amount = minOf(credits.getValue(creditor), debts.getValue(debtor))
            plan += chunks(debtor, creditor, amount, currency)
            settle(credits, creditor, amount)
            settle(debts, debtor, amount)
        }
        return plan
    }

    private fun settle(open: MutableMap<PersonId, Long>, person: PersonId, amount: Long) {
        val left = open.getValue(person) - amount
        if (left == 0L) open.remove(person) else open[person] = left
    }

    private fun chunks(from: PersonId, to: PersonId, amount: Long, currency: CurrencyCode): List<PlannedTransfer> {
        val result = mutableListOf<PlannedTransfer>()
        var left = amount
        while (left > 0) {
            val part = minOf(left, MAX_MINOR)
            result += PlannedTransfer(from, to, Money(part, currency))
            left -= part
        }
        return result
    }
}
