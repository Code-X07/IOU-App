package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.Money

/** How IDs inside diffs are shown. Views pass display names; tests and imports may use raw IDs. */
interface DiffNames {
    fun person(id: PersonId): String
    fun group(id: GroupId): String

    companion object {
        val Ids: DiffNames = object : DiffNames {
            override fun person(id: PersonId) = id.value
            override fun group(id: GroupId) = id.value
        }
    }
}

/**
 * One business field of a snapshot: [value] is what is compared (IDs for references), [shown] how it is
 * displayed, [label] the `FieldChange.field` it is reported under.
 */
data class DiffField(val label: String, val value: String, val shown: String)

/** Field-level diffs of entity snapshots (spec §5.3). Sync columns other than the deleted state are ignored. */
object RevisionDiff {

    fun diff(before: LedgerEntity?, after: LedgerEntity, names: DiffNames = DiffNames.Ids): List<FieldChange> {
        val old = before?.let { fields(it, names) }.orEmpty()
        val new = fields(after, names)
        val keys = new.keys + (old.keys - new.keys)
        return keys.mapNotNull { key ->
            val was = old[key]
            val now = new[key]
            if (was?.value == now?.value) return@mapNotNull null
            // Different IDs that read the same ("Alex" and "Alex"): show the IDs too, or the change looks empty.
            val ambiguous = was != null && now != null && was.shown == now.shown
            fun DiffField.display() = if (ambiguous) "$shown ($value)" else shown
            FieldChange((now ?: was)!!.label, was?.display(), now?.display())
        }
    }

    /** Business fields, keyed so the same field of two snapshots pairs up (shares by person ID). Null values are omitted. */
    fun fields(entity: LedgerEntity, names: DiffNames = DiffNames.Ids): Map<String, DiffField> {
        val fields = LinkedHashMap<String, DiffField>()
        fun plain(label: String, value: String?) {
            if (value != null) fields[label] = DiffField(label, value, value)
        }
        fun person(label: String, id: PersonId?) {
            if (id != null) fields[label] = DiffField(label, id.value, names.person(id))
        }
        fun group(label: String, id: GroupId?) {
            if (id != null) fields[label] = DiffField(label, id.value, names.group(id))
        }
        when (entity) {
            is Person -> {
                plain("displayName", entity.displayName)
                plain("note", entity.note)
                plain("defaultCurrency", entity.defaultCurrency?.code)
                plain("archived", entity.archivedAt?.let { "yes" })
                person("mergedInto", entity.mergedIntoId)
            }
            is PaymentHandle -> {
                person("person", entity.personId)
                plain("type", entity.type.name)
                plain("value", entity.value)
                plain("holderName", entity.holderName)
                plain("bic", entity.bic)
                plain("label", entity.label)
                plain("isDefault", yesNo(entity.isDefault))
            }
            is Group -> {
                plain("name", entity.name)
                plain("defaultCurrency", entity.defaultCurrency.code)
                plain("fxMode", entity.fxMode.name)
                plain("archived", entity.archivedAt?.let { "yes" })
            }
            is GroupMember -> {
                group("group", entity.groupId)
                person("person", entity.personId)
                plain("removed", entity.removedAt?.let { "yes" })
            }
            is Transfer -> {
                person("from", entity.fromPersonId)
                person("to", entity.toPersonId)
                plain("amount", entity.amount.toString())
                plain("effect", entity.effectAmount.toString())
                plain("fxSnapshot", entity.fxSnapshotId?.value)
                plain("kind", entity.kind.name)
                plain("method", entity.method.name)
                plain("accountLabel", entity.accountLabel)
                group("group", entity.groupId)
                plain("occurredOn", entity.occurredOn.toString())
                plain("note", entity.note)
            }
            is Expense -> {
                person("payer", entity.payerPersonId)
                plain("description", entity.description)
                plain("category", entity.category?.name)
                plain("total", entity.total.toString())
                plain("effectTotal", entity.effectTotal.toString())
                plain("fxSnapshot", entity.fxSnapshotId?.value)
                plain("splitType", entity.splitType.name)
                group("group", entity.groupId)
                plain("occurredOn", entity.occurredOn.toString())
                plain("note", entity.note)
                for (share in entity.shares) {
                    val id = share.personId.value
                    val name = names.person(share.personId)
                    val amount = Money(share.shareMinor, entity.effectTotal.currency).toString()
                    fields["share[$id]"] = DiffField("share[$name]", amount, amount)
                    share.inputValue?.let { fields["input[$id]"] = DiffField("input[$name]", it.toString(), it.toString()) }
                }
            }
        }
        plain("state", if (entity.isDeleted) "deleted" else "active")
        return fields
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}
