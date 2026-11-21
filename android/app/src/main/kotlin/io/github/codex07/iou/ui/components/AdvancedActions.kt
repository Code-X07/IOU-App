package io.github.codex07.iou.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.screens.BalancePanel
import io.github.codex07.iou.ui.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActions(label: String, actions: List<Pair<String, () -> Unit>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(label) }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            actions.forEach { (title, action) -> TextButton(onClick = { open = false; action() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title) } }
        }
    }
}

@Composable
fun LifecycleActions(entity: EntityRef, token: EditToken, subject: String, deleted: Boolean, archived: Boolean?, request: (AdvancedAction) -> Unit) {
    fun action(operation: LifeOperation) = request(AdvancedAction.Lifecycle(entity, token, operation, subject))
    val choices = if (deleted) listOf("Restore record" to { action(LifeOperation.RESTORE) }) else buildList {
        if (archived != null) add((if (archived) "Unarchive" else "Archive") to { action(if (archived) LifeOperation.UNARCHIVE else LifeOperation.ARCHIVE) })
        add("Delete record" to { action(LifeOperation.DELETE) })
    }
    QuickActions(if (deleted) "Recovery actions" else "Manage record", choices)
}

@Composable
fun AdvancedDialog(vm: AdvancedViewModel, state: AdvancedState, hidden: Boolean) {
    if (state.pending == null && state.error == null && state.message == null && !state.busy) return
    val action = state.pending
    val title = when {
        state.error != null -> "Action needs review"
        state.outcome == AdvancedOutcome.UNCONFIRMED -> "Change not confirmed"
        state.outcome == AdvancedOutcome.SUCCESS -> "Ledger updated"
        action is AdvancedAction.Revision -> "Restore version ${action.revision.version}?"
        action is AdvancedAction.Membership -> if (action.remove) "Remove member?" else "Add member?"
        action is AdvancedAction.Unmerge -> "Unmerge contact?"
        action is AdvancedAction.Lifecycle -> "${action.operation.name.lowercase().replaceFirstChar { it.uppercase() }} record?"
        else -> "Preparing repayment"
    }
    AlertDialog(onDismissRequest = vm::dismiss, title = { Text(title) },
        text = { androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) CircularProgressIndicator()
                state.message?.let { Text(it) }
                state.error?.let { error ->
                    Text(error.userMessage(hidden))
                    if (error is LedgerError.MemberHasBalance) {
                        Text("Outstanding group positions")
                        BalancePanel(error.nets.map { Money(it.value, it.key) }, hidden, group = true)
                    }
                    if (error is LedgerError.RestoreConflict) error.violations.forEach { Text(it.error.userMessage(hidden)) }
                    if (error is LedgerError.StaleVersion) Text("Close this dialog to review the latest record, then choose the action again. Nothing was retried.")
                }
                if (state.error == null && state.message == null) when (action) {
                    is AdvancedAction.Lifecycle -> Text(when (action.operation) {
                        LifeOperation.ARCHIVE -> "Archive ${action.subject}? Open balances remain in your totals. History is retained."
                        LifeOperation.UNARCHIVE -> "Return ${action.subject} to the active list?"
                        LifeOperation.DELETE -> if (action.entity.type == EntityType.RECURRING_EXPENSE)
                            "Delete this schedule? Posted expenses stay in the ledger."
                        else "Delete this record? Financial references may block deletion. The backend preserves history; recovery uses its Restore action."
                        LifeOperation.RESTORE -> if (action.entity.type == EntityType.RECURRING_EXPENSE)
                            "Restore this schedule? Unhandled older occurrences will be available to review in the due list."
                        else "Restore this record using the current ledger rules? Its financial effects may return."
                    })
                    is AdvancedAction.Membership -> Text(if (action.remove) "Remove ${action.subject}? History remains. The ledger will block removal while a balance is outstanding." else "Add or re-add ${action.subject} to this group?")
                    is AdvancedAction.Unmerge -> Text("Separate ${action.subject} from the canonical contact? Original records, handles and references follow the backend's identity rules.")
                    is AdvancedAction.Revision -> {
                        Text(if (hidden) "Historical business fields will be restored on the affected record. Values hidden.${if (action.deletedHandle) " The handle stays deleted." else ""}" else "${action.revision.subject}: restore the business fields of version ${action.revision.version} as a new revision. ${if (action.deletedHandle) "The handle stays deleted; only its historical fields are restored." else "Identity links and deletion state stay current."}")
                        action.revision.changes.forEach { change -> Text(if (hidden) "${change.field}: values hidden" else "${change.field}: ${change.before ?: "—"} → ${change.after ?: "—"}") }
                    }
                    null -> Unit
                }
                if (state.error is LedgerError.StillReferenced && action is AdvancedAction.Lifecycle && action.entity.type in setOf(EntityType.PERSON, EntityType.GROUP)) {
                    TextButton(onClick = vm::archiveInstead, enabled = !state.busy) { Text("Archive instead") }
                }
                if (state.error is LedgerError.RestoreConflict && action is AdvancedAction.Revision && !action.editable) Text("Restore the current record first, then open an editable historical draft.")
                if (state.error is LedgerError.RestoreConflict && action is AdvancedAction.Revision && action.revision.entity.type != EntityType.GROUP_MEMBER && action.editable) {
                    TextButton(onClick = vm::openAsDraft, enabled = !state.busy) { Text("Open as Draft") }
                }
            }
        } },
        confirmButton = {
            if (action != null && state.error == null && state.message == null) TextButton(onClick = vm::confirm, enabled = !state.busy) { Text("Confirm") }
            else TextButton(onClick = vm::dismiss, enabled = !state.busy) { Text(if (state.error is LedgerError.StaleVersion) "Review latest" else "Close") }
        }, dismissButton = {
            if (action != null && state.error == null) TextButton(onClick = vm::dismiss, enabled = !state.busy) { Text("Cancel") }
        })
}
