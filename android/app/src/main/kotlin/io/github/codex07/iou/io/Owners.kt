package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle

/** A row's key, as import previews and undo results name it. */
internal val LedgerEntity.rowKey: ImportRowKey get() = ImportRowKey(entityType, entityId)

/**
 * Owners and the rows they own (spec §5.2): a person owns their payment handles, a group its memberships.
 * Import (§7.4) and import undo (§5.4) move owned rows together with their owner through the cascade.
 */
internal object Owners {

    /** The owner of a payment handle (its person) or a membership (its group); null for any other row. */
    fun ownerOf(entity: LedgerEntity): ImportRowKey? = when (entity) {
        is PaymentHandle -> ImportRowKey(EntityType.PERSON, entity.personId.value)
        is GroupMember -> ImportRowKey(EntityType.GROUP, entity.groupId.value)
        else -> null
    }

    /** Every row [owner] owns in [state], deleted ones included. */
    fun owned(state: LedgerState, owner: ImportRowKey): List<LedgerEntity> = when (owner.type) {
        EntityType.PERSON -> state.handles.values.filter { it.personId.value == owner.id }
        EntityType.GROUP -> state.members.values.filter { it.groupId.value == owner.id }
        else -> emptyList()
    }
}
