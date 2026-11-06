package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.viewmodel.AdvancedAction

/** Tokens are supplied by current read models; never substitute a parent's token for an alias/handle. */
fun PersonDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? {
    val token = when (revision.entity.type) {
        EntityType.PERSON -> if (revision.entity.id == person.id.value) token
            else aliases.firstOrNull { it.person.id.value == revision.entity.id }?.token
        EntityType.PAYMENT_HANDLE -> handles.firstOrNull { it.id.value == revision.entity.id }?.token
        else -> null
    } ?: return null
    return AdvancedAction.Revision(revision, token, person.id, editable = !deleted)
}
fun GroupDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? =
    if (revision.entity == EntityRef(EntityType.GROUP, group.id.value)) AdvancedAction.Revision(revision, token, editable = !deleted) else null
fun EntryDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? =
    if (revision.entity.id == item.entryId.value && revision.entity.type in setOf(EntityType.EXPENSE, EntityType.TRANSFER)) AdvancedAction.Revision(revision, token, editable = !item.deleted) else null
fun RevisionView.isIdentityChange(): Boolean = changes.any { it.field.contains("mergedInto", ignoreCase = true) } ||
    reason?.startsWith("Merged into") == true || reason?.startsWith("Unmerged") == true
