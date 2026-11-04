package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.FormStatus
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun MetadataFormScreen(vm: MetadataFormViewModel, state: MetadataState, status: FormStatus, hidden: Boolean,
                       close: () -> Unit, saved: (String) -> Unit) {
    val fields = state.fields
    val enabled = !status.busy && !status.invalidated && status.completedId == null && status.error !is LedgerError.StaleVersion && status.error !is LedgerError.NotFound
    var reload by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.completedId) { status.completedId?.let(saved) }
    val title = when (state.kind) {
        MetadataKind.PERSON -> if (state.editing) "Edit person" else "Add person"
        MetadataKind.HANDLE -> if (state.editing) "Edit payment handle" else "Add payment handle"
        MetadataKind.GROUP -> if (state.editing) "Edit group" else "Create group"
    }
    FormScaffold(title, status, vm::requestLeave, vm::keepEditing, vm::discard, vm::save, hidden, close) {
        if (status.error is LedgerError.StaleVersion) TextButton(onClick = { reload = true }) { Text("Review latest version") }
        when (state.kind) {
            MetadataKind.PERSON -> {
                FormTextField("Display name", fields.name, { vm.change { f -> f.copy(name = it) } }, enabled,
                    error = (status.error as? LedgerError.InvalidField)?.field == "displayName")
                FormTextField("Notes (optional)", fields.note, { vm.change { f -> f.copy(note = it) } }, enabled, multiline = true)
                FormTextField("Preferred currency code (optional)", fields.currency, { vm.change { f -> f.copy(currency = it.uppercase()) } }, enabled,
                    error = status.error is LedgerError.UnknownCurrency)
                Text("For example EUR, SEK, JPY or KWD. Leave blank for no preference.", style = MaterialTheme.typography.bodySmall)
            }
            MetadataKind.HANDLE -> {
                ChoiceField("Handle type", fields.handleType.name, HandleType.entries.map { it.name to when (it) {
                    HandleType.REVTAG -> "Revolut tag"; HandleType.PHONE -> "Phone (E.164)"; HandleType.IBAN -> "IBAN"; else -> "Other"
                } }, { value -> vm.change { it.copy(handleType = HandleType.valueOf(value)) } }, enabled)
                SensitiveFormField("Payment handle", fields.value, { vm.change { f -> f.copy(value = it) } }, hidden, enabled, decimal = false)
                Text(when (fields.handleType) { HandleType.IBAN -> "Enter the full IBAN. Spaces are allowed."; HandleType.PHONE -> "Use + followed by 8–15 digits."; HandleType.REVTAG -> "Enter a Revolut tag with or without @."; else -> "Contact reference only." }, style = MaterialTheme.typography.bodySmall)
                FormTextField("Label", fields.label, { vm.change { f -> f.copy(label = it) } }, enabled,
                    error = (status.error as? LedgerError.InvalidField)?.field == "label")
                Text("The ledger requires a label, for example Main account or Mobile.", style = MaterialTheme.typography.bodySmall)
                if (fields.handleType == HandleType.IBAN) {
                    FormTextField("Holder name (optional)", fields.holder, { vm.change { f -> f.copy(holder = it) } }, enabled)
                    FormTextField("BIC (optional)", fields.bic, { vm.change { f -> f.copy(bic = it) } }, enabled)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Default for this handle type", Modifier.weight(1f))
                    Switch(fields.defaultHandle, { value -> vm.change { it.copy(defaultHandle = value) } }, enabled = enabled)
                }
                Notice("Contact details only", "IOU never sends payments. Default selections are managed by the ledger, including merged contacts.")
            }
            MetadataKind.GROUP -> {
                FormTextField("Group name", fields.name, { vm.change { f -> f.copy(name = it) } }, enabled,
                    error = (status.error as? LedgerError.InvalidField)?.field == "name")
                FormTextField("Default currency code", fields.currency, { vm.change { f -> f.copy(currency = it.uppercase()) } },
                    enabled && !(state.locked && fields.fxMode == FxMode.CONVERT), error = status.error is LedgerError.UnknownCurrency)
                ChoiceField("Currency handling", fields.fxMode.name, listOf("CONVERT" to "Convert to default currency", "KEEP" to "Keep original currencies separate"),
                    { value -> vm.change { it.copy(fxMode = FxMode.valueOf(value)) } }, enabled && !state.locked)
                Text(if (fields.fxMode == FxMode.CONVERT) "Foreign entry effects use a saved FX rate into ${fields.currency}." else "Each original currency has its own balance. No combined total.")
                if (state.locked) Notice("Currency settings locked", "This group already has entries. Its currency mode and CONVERT currency cannot be changed.")
                if (!state.editing) {
                    BrowseHeading("Members")
                    Text("You are included automatically. Choose the other members.")
                    MemberSelector(state.people.map { it.person.id.value to it.person.displayName }, fields.members.map { it.value }.toSet(), { id ->
                        vm.change { it.copy(members = if (PersonId(id) in it.members) it.members - PersonId(id) else it.members + PersonId(id)) }
                    }, enabled)
                } else Notice("Metadata only", "Member changes are a separate operation and will be available in Phase 4.")
            }
        }
    }
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("Review latest version?") },
        text = { Text("This replaces the unfinished form with the latest backend record. Your changes will be discarded.") },
        confirmButton = { TextButton(onClick = { reload = false; vm.reviewLatest() }) { Text("Load latest") } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("Keep editing") } })
}
