package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.*
import io.github.codex07.iou.ui.viewmodel.*
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryFormScreen(vm: EntryFormViewModel, state: EntryFormState, status: FormStatus, hidden: Boolean,
                    close: () -> Unit, saved: (String) -> Unit, createGroup: (Set<PersonId>) -> Unit) {
    val fields = state.fields
    val enabled = !status.busy && !status.invalidated && status.completedId == null && status.error !is LedgerError.StaleVersion && status.error !is LedgerError.NotFound
    val parsedCurrency = Inputs.currency(fields.currency).getOrNull()
    val context = state.groups.firstOrNull { it.group.id.value == fields.group }
    val candidates = if (context == null && fields.group == null) state.people else context?.members?.filter { it.active }?.map { it.person }.orEmpty()
    val peopleOptions = candidates.map { it.id.value to if (it.isMe) "You" else it.displayName }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var reload by rememberSaveable { mutableStateOf(false) }
    val title = if (state.editing) "Edit transaction" else if (fields.expense) "Add expense" else if (fields.kind == TransferKind.REPAYMENT) "Record repayment" else "Record loan / transfer"
    FormScaffold(title, status, vm::requestLeave, vm::keepEditing, vm::discard, vm::save, hidden,
        close, done = { status.completedId?.let(saved) }, currency = parsedCurrency) {
        if (status.completedId != null) {
            BrowseHeading(if (!fields.expense && fields.kind == TransferKind.REPAYMENT) "Repayment recorded" else "Transaction saved")
            Text("Recorded in your ledger. IOU did not move any money.")
            state.remainingLabel?.let { BrowseHeading(it) }
            state.remaining?.let { BalancePanel(it, hidden, group = fields.group != null) }
            state.remainingMembers.forEach { member -> Text(member.person.displayName, style = MaterialTheme.typography.titleMedium); BalancePanel(member.nets, hidden, group = true) }
        } else {
            if (status.error is LedgerError.StaleVersion) TextButton(onClick = { reload = true }) { Text("Review latest version") }
            if (hidden) Notice("Balances hidden", "Reveal individual inputs deliberately to edit them. Other balances and previews remain hidden.")
            SensitiveFormField("Amount", fields.amount, { vm.change { f -> f.copy(amount = it) } }, hidden, enabled,
                error = status.error == LedgerError.InvalidAmount || status.error is LedgerError.TooManyDecimals || status.error == LedgerError.AmountTooLarge)
            FormTextField("Currency code", fields.currency, { vm.change { f -> f.copy(currency = it.uppercase(), acceptStale = false) } }, enabled,
                error = status.error is LedgerError.UnknownCurrency)
            ChoiceField("Common currencies", fields.currency, listOf("EUR", "SEK", "USD", "GBP", "JPY", "KWD", "INR").map { it to it },
                { value -> vm.change { it.copy(currency = value, acceptStale = false) } }, enabled)
            Text("Use a decimal comma or point, without grouping separators. ${parsedCurrency?.let { "${it.code} supports ${it.fractionDigits} decimal places." }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall)
            if (fields.expense) {
                FormTextField("Description", fields.description, { vm.change { f -> f.copy(description = it) } }, enabled,
                    error = (status.error as? LedgerError.InvalidField)?.field == "description")
                ChoiceField("Category", fields.category?.name ?: "none", listOf("none" to "No category") + Category.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
                    { value -> vm.change { it.copy(category = if (value == "none") null else Category.valueOf(value)) } }, enabled)
            } else {
                ChoiceField("Transaction classification", fields.kind.name, listOf("LOAN" to "Loan · creates or increases an obligation", "REPAYMENT" to "Repayment · records money paid back"),
                    { value -> vm.change { it.copy(kind = TransferKind.valueOf(value)) } }, enabled)
                Notice("A record, not a payment", "IOU records loans and repayments. Transfers use one of these two ledger classifications.")
            }
            OutlinedButton(onClick = { datePicker = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Date · ${fields.date}") }
            ChoiceField("Context", fields.group ?: "personal", listOf("personal" to "Personal · outside groups") +
                state.groups.filter { !it.archived || it.group.id.value == fields.group }.map { it.group.id.value to it.group.name },
                { value -> vm.chooseGroup(value.takeUnless { it == "personal" }) }, enabled)
            if (context != null) Text("${context.fxMode} · ${context.defaultCurrency.code}. Choose current group members.", style = MaterialTheme.typography.bodySmall)
            ChoiceField(if (fields.expense) "Payer" else "Sender", fields.payer, peopleOptions,
                { value -> vm.change { it.copy(payer = value) } }, enabled)
            if (!fields.expense) {
                ChoiceField("Recipient", fields.receiver, peopleOptions, { value -> vm.change { it.copy(receiver = value) } }, enabled)
                val sender = state.people.firstOrNull { it.id.value == fields.payer }
                val recipient = state.people.firstOrNull { it.id.value == fields.receiver }
                if (sender != null && recipient != null) Notice("Direction", when {
                    fields.kind == TransferKind.REPAYMENT -> "${sender.displayName} paid back ${recipient.displayName}."
                    sender.isMe -> "You lent money to ${recipient.displayName}."
                    recipient.isMe -> "You borrowed money from ${sender.displayName}."
                    else -> "${sender.displayName} lent money to ${recipient.displayName} in this group."
                })
                ChoiceField("Method", fields.method.name, TransferMethod.entries.map { it.name to it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } },
                    { value -> vm.change { it.copy(method = TransferMethod.valueOf(value)) } }, enabled)
                FormTextField("Method / account label (optional)", fields.account, { vm.change { f -> f.copy(account = it) } }, enabled)
                if (context?.fxMode != FxMode.CONVERT) {
                    FormTextField("Debt currency to settle (optional)", fields.settles, { vm.change { f -> f.copy(settles = it.uppercase(), acceptStale = false) } }, enabled)
                    Text("For a cross-currency repayment, enter the currency of the obligation. Leave blank to use the paid currency.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                BrowseHeading("Participants")
                MemberSelector(peopleOptions, fields.participants.toSet(), { id -> vm.change { f ->
                    f.copy(participants = if (id in f.participants) f.participants - id else f.participants + id)
                } }, enabled)
                // Keep invalidated selections visible so membership changes cannot silently alter a split.
                val unavailable = fields.participants.filter { id -> candidates.none { it.id.value == id } }
                unavailable.forEach { id ->
                    TextButton(onClick = { vm.change { it.copy(participants = it.participants - id) } }, enabled = enabled) {
                        Text("Remove unavailable participant · " + (state.people.firstOrNull { it.id.value == id }?.displayName ?: "Former member"))
                    }
                }
                ChoiceField("Split method", fields.split.name, listOf("EQUAL" to "Equal", "PERCENT" to "Percentage", "SHARES" to "Shares", "EXACT" to "Exact amount"),
                    { value -> vm.change { it.copy(split = SplitType.valueOf(value)) } }, enabled)
                if (fields.split != SplitType.EQUAL) fields.participants.forEach { id ->
                    val name = state.people.firstOrNull { it.id.value == id }?.displayName ?: "Participant"
                    SensitiveFormField(when (fields.split) { SplitType.PERCENT -> "$name · percent"; SplitType.SHARES -> "$name · weight"; else -> "$name · exact ${fields.currency}" },
                        fields.inputs[id] ?: if (fields.split == SplitType.SHARES) "1" else "", { value -> vm.change { it.copy(inputs = it.inputs + (id to value)) } }, hidden, enabled)
                }
                Text(when (fields.split) { SplitType.EQUAL -> "The ledger distributes rounding fairly in exact minor units."; SplitType.PERCENT -> "Enter percentages with at most two decimals. Total must be 100%."; SplitType.SHARES -> "Use whole-number weights from 1 to 1,000,000."; SplitType.EXACT -> "Amounts are entered in the original currency and must match the expense total." }, style = MaterialTheme.typography.bodySmall)
            }
            if (status.error is LedgerError.NeedsGroup || (fields.group == null && (fields.participants + fields.payer + fields.receiver).filter { it.isNotBlank() }.distinct().size > 2)) {
                Notice("This selection needs a group", "Create a group without losing this draft, then continue here.")
                FilledTonalButton(onClick = { createGroup(vm.groupMembersForDraft()) }, enabled = enabled) { Text("Create group & keep draft") }
            }
            if (hidden) SensitiveFormField("Notes", fields.note, { vm.change { f -> f.copy(note = it) } }, hidden, enabled, decimal = false)
            else FormTextField("Notes (optional)", fields.note, { vm.change { f -> f.copy(note = it) } }, enabled, multiline = true)
            FxEditor(fields, state, enabled, hidden, vm)
            if (fields.expense) {
                BrowseHeading("Live split preview")
                if (state.previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.previewError?.takeIf { fields.amount.isNotBlank() }?.let { Text(it.userMessage(hidden, parsedCurrency), color = MaterialTheme.colorScheme.error) }
                state.preview?.let { preview ->
                    Text(if (state.editing) { if (preview.recomputed) "Saving will recalculate allocations." else "Stored allocations will be preserved." } else "Allocations calculated by the ledger.")
                    preview.shares.forEach { share ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(share.person.displayName, Modifier.weight(1f)); MoneyText(share.share, hidden, Modifier.weight(1f))
                        }
                    }
                    if (state.editing && preview.recomputed) {
                        Text("Previously stored shares", style = MaterialTheme.typography.titleSmall)
                        preview.storedShares.orEmpty().forEach { share ->
                            Text(share.person.displayName); MoneyText(share.share, hidden)
                        }
                    }
                    Text("Expense effect total"); MoneyText(preview.effectTotal, hidden)
                }
            } else state.previewError?.takeIf { fields.amount.isNotBlank() }?.let { Text(it.userMessage(hidden, parsedCurrency), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (datePicker) {
        val date = runCatching { LocalDate.parse(fields.date) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { millis ->
                vm.change { it.copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(), acceptStale = false) }
            }; datePicker = false }) { Text("Choose date") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("Review latest version?") },
        text = { Text("Load the latest transaction into the form. Your unfinished changes will be discarded; no newer record will be overwritten.") },
        confirmButton = { TextButton(onClick = { reload = false; vm.reviewLatest() }) { Text("Load latest") } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("Keep editing") } })
}

@Composable
private fun FxEditor(fields: EntryFields, state: EntryFormState, enabled: Boolean, hidden: Boolean, vm: EntryFormViewModel) {
    val group = state.groups.firstOrNull { it.group.id.value == fields.group }
    val target = if (group?.fxMode == FxMode.CONVERT) group.defaultCurrency.code else fields.settles.ifBlank { fields.currency }
    if (target == fields.currency) return
    BrowseHeading("Exchange rate · ${fields.currency} → $target")
    val choices = (if (state.storedFx != null) listOf("SAVED" to "Keep stored snapshot") else emptyList()) +
        listOf("AUTO" to "Look up a quote", "MANUAL" to "Enter a manual rate", "BANK" to "Actual bank charge")
    ChoiceField("FX choice", fields.fxMode.name, choices, { value -> vm.change { it.copy(fxMode = FxInputMode.valueOf(value), acceptStale = false) } }, enabled)
    when (fields.fxMode) {
        FxInputMode.MANUAL -> {
            Text("Rate is $target per 1 ${fields.currency}.")
            SensitiveFormField("Exchange rate", fields.rate, { vm.change { f -> f.copy(rate = it) } }, hidden, enabled)
        }
        FxInputMode.BANK -> SensitiveFormField("Actual charge in $target", fields.charged, { vm.change { f -> f.copy(charged = it) } }, hidden, enabled)
        FxInputMode.SAVED -> state.storedFx?.let {
            Notice("Stored snapshot", if (hidden) "Rate hidden. The backend validates whether this snapshot still fits." else "${it.rate} · ${it.rateDate} · ${it.source}. The backend validates this snapshot before saving.${if (it.isStale) " This snapshot used a stale rate." else ""}")
        }
        FxInputMode.AUTO -> Text("If no quote is available, choose a manual rate or the actual amount charged.")
    }
    state.quote?.let { quote ->
        Text(if (hidden) "Quote hidden" else "1 ${quote.base.code} = ${quote.rate} ${quote.quote.code} · ${quote.rateDate}")
        if (quote.isStale) {
            Notice("Stale quote", "The exact date is unavailable. Confirm that you want to use this cached rate, or supply a new one.")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(fields.acceptStale, { value -> vm.change { it.copy(acceptStale = value) } }, enabled = enabled)
                Text("Use this stale quote", Modifier.weight(1f))
            }
        }
    }
}
