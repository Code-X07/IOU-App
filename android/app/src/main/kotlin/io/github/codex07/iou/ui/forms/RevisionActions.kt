package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.viewmodel.AdvancedAction

/** Every revert uses the revision's own current row token; null means the row no longer exists. */
fun PersonDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? {
    val token = revision.token ?: return null
    val allowed = when (revision.entity.type) {
        EntityType.PERSON -> revision.entity.id == person.id.value || aliases.any { it.person.id.value == revision.entity.id }
        EntityType.PAYMENT_HANDLE -> true // History is scoped to this person's handles, deleted ones included.
        else -> false
    }
    if (!allowed) return null
    val deletedHandle = revision.entity.type == EntityType.PAYMENT_HANDLE && handles.none { it.id.value == revision.entity.id }
    return AdvancedAction.Revision(revision, token, person.id, editable = !deleted, deletedHandle = deletedHandle)
}
fun GroupDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? {
    val token = revision.token ?: return null
    val owned = revision.entity == EntityRef(EntityType.GROUP, group.id.value) || revision.entity.type == EntityType.GROUP_MEMBER
    return if (owned) AdvancedAction.Revision(revision, token, editable = !deleted) else null
}
fun EntryDetail.restoreAction(revision: RevisionView): AdvancedAction.Revision? {
    val token = revision.token ?: return null
    return if (revision.entity.id == item.entryId.value && revision.entity.type in setOf(EntityType.EXPENSE, EntityType.TRANSFER))
        AdvancedAction.Revision(revision, token, editable = !item.deleted) else null
}
fun RevisionView.isIdentityChange(): Boolean = changes.any { it.field.contains("mergedInto", ignoreCase = true) } ||
    reason?.startsWith("Merged into") == true || reason?.startsWith("Unmerged") == true
