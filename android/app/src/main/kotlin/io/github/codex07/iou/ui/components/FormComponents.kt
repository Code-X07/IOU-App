package io.github.codex07.iou.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ui.forms.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScaffold(title: String, status: FormStatus, leave: () -> Unit, keep: () -> Unit, discard: () -> Unit,
                 save: () -> Unit, hidden: Boolean, onExit: () -> Unit, done: (() -> Unit)? = null,
                 currency: io.github.codex07.iou.money.CurrencyCode? = null,
                 content: @Composable ColumnScope.() -> Unit) {
    BackHandler { leave() }
    LaunchedEffect(status.exit) { if (status.exit) onExit() }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) },
            navigationIcon = { IconButton(onClick = leave, enabled = !status.busy) { Icon(IouIcons.Back, if (status.completedId != null || status.invalidated) "Close" else "Cancel") } }) },
        bottomBar = {
            Surface {
                Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = leave, enabled = !status.busy, modifier = Modifier.weight(1f)) { Text(if (status.completedId != null || status.invalidated) "Close" else "Cancel") }
                    Button(onClick = { if (status.completedId != null) done?.invoke() else if (status.invalidated) leave() else save() },
                        enabled = !status.loading && !status.busy && status.error !is LedgerError.StaleVersion && status.error !is LedgerError.NotFound, modifier = Modifier.weight(1f)) {
                        if (status.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(when { status.completedId != null -> "Done"; status.invalidated -> "Close"; else -> "Save" })
                    }
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (status.loading) CircularProgressIndicator()
                status.error?.let { Notice("Check this form", it.userMessage(hidden, currency)) }
                status.message?.let { Notice("Please review", it) }
                if (!status.loading) content()
            }
        }
    }
    if (status.discard) AlertDialog(onDismissRequest = keep,
        title = { Text("Discard changes?") }, text = { Text("Your unfinished changes will be lost.") },
        confirmButton = { TextButton(onClick = discard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = keep) { Text("Keep editing") } })
}


@Composable
fun FormTextField(label: String, value: String, change: (String) -> Unit, enabled: Boolean = true,
                  decimal: Boolean = false, multiline: Boolean = false, error: Boolean = false) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 2 else 1, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        shape = MaterialTheme.shapes.medium,
        textStyle = if (label == "Amount") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodyLarge)
}

/** Deliberate reveal affects this input only; previews and unrelated balances stay masked. */
@Composable
fun SensitiveFormField(label: String, value: String, change: (String) -> Unit, hidden: Boolean,
                       enabled: Boolean, decimal: Boolean = true, error: Boolean = false) {
    var revealed by rememberSaveable(hidden) { mutableStateOf(false) }
    if (hidden && !revealed) OutlinedButton(onClick = { revealed = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(IouIcons.Eye, null); Spacer(Modifier.width(8.dp)); Text("Reveal $label to edit")
    } else FormTextField(label, value, change, enabled, decimal, error = error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceField(label: String, selected: String?, options: List<Pair<String, String>>, change: (String) -> Unit,
                enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedButton(onClick = { open = true; query = "" }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(options.firstOrNull { it.first == selected }?.second ?: if (selected.isNullOrBlank()) "Choose" else if (label in listOf("Payer", "Sender", "Recipient", "Context")) "Unavailable selection" else selected, style = MaterialTheme.typography.titleMedium)
        }
        Icon(IouIcons.Chevron, null)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            FormTextField("Search", query, { query = it })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).imePadding()) {
                items(options.filter { it.second.contains(query.trim(), true) }, key = { it.first }) { option ->
                    TextButton(onClick = { change(option.first); open = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(option.second, Modifier.weight(1f))
                        if (option.first == selected) Text("Selected")
                    }
                }
            }
        }
    }
}

@Composable
fun MemberSelector(options: List<Pair<String, String>>, selected: Set<String>, toggle: (String) -> Unit, enabled: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    FormTextField("Search people", query, { query = it }, enabled)
    options.filter { it.second.contains(query.trim(), true) }.forEach { (id, name) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Checkbox) { toggle(id) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(id in selected, onCheckedChange = null, enabled = enabled)
            Text(name, Modifier.weight(1f).padding(start = 12.dp))
        }
    }
}
