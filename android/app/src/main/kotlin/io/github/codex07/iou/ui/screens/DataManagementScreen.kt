package io.github.codex07.iou.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.settings.*
import io.github.codex07.iou.ui.viewmodel.DataManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(vm: DataManagementViewModel, hidden: Boolean, close: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val replacementRunning by vm.replacementRunning.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val services = vm.services
    var phrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var plainAcknowledged by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    BackHandler { vm.requestLeave() }
    LaunchedEffect(state.exit) { if (state.exit) { vm.end(); close() } }
    DisposableEffect(Unit) { onDispose { phrase = ""; confirmation = "" } }
    // Every operation uses the returned URI right away, so the temporary grant is enough; nothing is persisted.
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        vm.documentResult(uri?.toString())
    }
    LaunchedEffect(state.picker?.id, state.picker?.launched) {
        state.picker?.takeIf { !it.launched }?.let { picker ->
            vm.pickerLaunched(picker.id)
            val intent = Intent(if (picker.create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(picker.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (picker.create) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION).putExtra(Intent.EXTRA_TITLE, picker.name)
            try { documents.launch(intent) } catch (_: Exception) { vm.documentFailure() }
        }
    }
    val enabled = !state.loading && !state.busy && state.picker == null && !replacementRunning
    val title = when (state.mode) { "backup" -> "Create backup"; "restore" -> "Restore & undo"; "export" -> "Export data"; "import" -> "Import data"; else -> "Import history" }
    Scaffold(topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = vm::requestLeave) { Icon(IouIcons.Back, "Back") } }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), state = scroll,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "operation-status") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Notice(if (services.simulated) "Simulation only" else "Your data", services.notice)
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Notice("Please review", it.userMessage(hidden)) }
                state.message?.let { Notice("Data management", it) }
            } }
            if (state.mode == "history") {
                val history = catalog?.batches.orEmpty()
                if (history.isEmpty()) item(key = "empty-history") { Notice("No import history", "Imported batches will appear here after you review and confirm an import.") }
                items(history, key = { "batch:${it.id.value}" }) { batch ->
                    IouCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (hidden) "Import batch · file name hidden" else batch.fileName, style = MaterialTheme.typography.titleMedium)
                        Text("${batch.importedAt} · ${batch.rowCount} records")
                        Text(if (batch.undoneAt == null) "Imported" else "Undo recorded at ${batch.undoneAt}")
                        if (batch.undoneAt == null) TextButton(onClick = { vm.requestUndoImport(batch.id) }, enabled = enabled) { Text("Review import undo") }
                    } }
                }
                state.undoResult?.let { result ->
                    item(key = "undo-result") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Notice(if (result.complete) "Backend reports complete undo" else "Partial undo", if (result.complete) (
                            if (services.simulated) "The API reported no skipped rows. This remains a simulation; no on-disk financial data was undone." else "No rows were skipped. The import was fully undone.") else "Some rows could not be undone because of later changes. Do not assume balances were exactly restored.")
                        result.skipped.forEach { skipped -> InfoLine("${skipped.key.type.name.lowercase().replace('_', ' ')} record", if (hidden) "Reason hidden" else skipped.reason) }
                    } }
                }
            } else if (state.mode == "import" && state.preview != null) {
                importPreviewRows(state.preview!!, state, hidden, enabled, vm)
            } else item(key = "data-editor") { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state.mode) {
                "backup" -> {
                    catalog?.let { InfoLine("Last manual backup", it.reminder.lastBackupAt?.toString() ?: "None recorded"); Text(if (it.reminder.due) "Backup reminder is due." else if (services.simulated) "The backend reports a recent backup simulation." else "Your last backup is recent.") }
                    SettingsSwitch("Encrypt this backup", state.encrypted, enabled) { value -> phrase = ""; confirmation = ""; plainAcknowledged = false; vm.encryption(value) }
                    if (state.encrypted) {
                        SecretField("Passphrase (at least 8 characters)", phrase, { phrase = it }, enabled)
                        SecretField("Confirm passphrase", confirmation, { confirmation = it }, enabled)
                        Text("A forgotten passphrase cannot be recovered. Nothing is saved in UI preferences or saved instance state.${if (services.simulated) " Use test passphrases with this simulated backend." else ""}")
                    } else {
                        Notice("Unencrypted data warning", if (services.simulated) "A real unencrypted backup would be readable by anyone with access to the file. This build writes no backup contents." else "This backup will not be encrypted. Anyone with access to the file can read your financial records.")
                        SettingsSwitch("I understand the unencrypted-file risk", plainAcknowledged, enabled) { plainAcknowledged = it }
                    }
                    Button(onClick = {
                        vm.beginBackup(phrase.toCharArray(), confirmation.toCharArray(), plainAcknowledged); phrase = ""; confirmation = ""
                    }, enabled = enabled) { Text(if (services.simulated) "Choose document & simulate backup" else "Choose document & save backup") }
                }
                "restore" -> {
                    Text("Restore replaces the current ledger. Unsaved forms and import previews become invalid. Choosing a file does not restore it.")
                    Button(onClick = { phrase = ""; vm.pickRestore() }, enabled = enabled) { Text("Choose backup document") }
                    if (state.restoreSelected) {
                        SecretField("Passphrase (leave empty for unencrypted)", phrase, { phrase = it }, enabled)
                        Button(onClick = { vm.requestRestore(phrase.toCharArray()); phrase = "" }, enabled = enabled) { Text("Review ledger replacement") }
                    }
                    if (catalog?.canUndoRestore == true) OutlinedButton(onClick = vm::requestUndoRestore, enabled = enabled) { Text("Undo last restore") }
                    Text(if (services.simulated) "Undo restore is another ledger replacement. In this build, restore only exercises the in-memory simulation." else "Undo restore is another ledger replacement: it brings back the ledger as it was before the last restore.", style = MaterialTheme.typography.bodySmall)
                }
                "export" -> ExportEditor(state.export, catalog, enabled, vm)
                "import" -> {
                    if (state.preview == null) {
                        Text("Only an IOU normalized CSV ZIP is supported for import. Report CSV and XLSX files are not import sources.")
                        Button(onClick = vm::pickImport, enabled = enabled) { Text("Choose normalized CSV ZIP") }
                        if (services.simulated) {
                            BrowseHeading("Simulation scenarios")
                            Text("These use canned API previews and do not read the selected file contents.", style = MaterialTheme.typography.bodySmall)
                            listOf("normal", "foreign", "invalid", "corrupt").forEach { scenario -> TextButton(onClick = { vm.simulateImport(scenario) }, enabled = enabled) { Text("Simulate $scenario import") } }
                        }
                    }
                }

            }
            } }
        }
    }

    if (state.confirmation != null) AlertDialog(onDismissRequest = vm::dismissConfirmation,
        title = { Text(when (state.confirmation) { DataConfirmation.RESTORE -> "Replace this ledger?"; DataConfirmation.UNDO_RESTORE -> "Undo the last restore?"; DataConfirmation.COMMIT_IMPORT -> "Commit this import?"; else -> "Undo this import?" }) },
        text = { Text(when (state.confirmation) {
            DataConfirmation.RESTORE -> "The current ledger will be replaced and unfinished drafts will be discarded. A valid backup and its passphrase are required.${if (services.simulated) " This backend only simulates replacement; it does not read an on-disk backup." else " This cannot be undone except with Undo last restore."}"
            DataConfirmation.UNDO_RESTORE -> "This replaces the current ledger with the backend's previous state and invalidates all old drafts and previews."
            DataConfirmation.COMMIT_IMPORT -> "Apply the reviewed choices through the backend? If the preview is stale, nothing will be committed.${if (services.simulated) " This build records a simulated batch only." else " The ledger on this device will change; you can review and undo the import from Import history."}"
            else -> "Undo is best-effort. Records changed later may be skipped; the actual skipped-row result will be shown."
        }) }, confirmButton = { TextButton(onClick = vm::confirm, enabled = enabled) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = vm::dismissConfirmation, enabled = enabled) { Text("Cancel") } })
    if (state.discard) AlertDialog(onDismissRequest = vm::keepEditing, title = { Text("Discard unfinished workflow?") },
        text = { Text("Prepared selections and import choices will be discarded. No import or restore will be committed.") },
        confirmButton = { TextButton(onClick = vm::discard) { Text("Discard") } }, dismissButton = { TextButton(onClick = vm::keepEditing) { Text("Keep reviewing") } })
}


@Composable
private fun SecretField(label: String, text: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(text, change, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
}

@Composable
private fun ExportEditor(fields: ExportFields, catalog: DataCatalog?, enabled: Boolean, vm: DataManagementViewModel) {
    val services = vm.services
    ChoiceField("Export shape", fields.shape.name, ExportShape.entries.map { it.name to if (it == ExportShape.NORMALIZED) "Normalized full ledger" else "Human-readable report" }, { value -> vm.exportChange {
        it.copy(shape = ExportShape.valueOf(value), scope = "full", from = "", to = "")
    } }, enabled)
    ChoiceField("Format", fields.format.name, listOf("CSV" to "CSV (ZIP for normalized)"), { value -> vm.exportChange { it.copy(format = ExportFormat.valueOf(value), excelFriendly = false) } }, enabled)
    Text("XLSX export arrives in a later version.", style = MaterialTheme.typography.bodySmall)
    if (fields.shape == ExportShape.REPORT) {
        val scopes = listOf("full" to "Full ledger report") + catalog?.people.orEmpty().map { "person:${it.person.id.value}" to "Person · ${it.person.displayName}" } + catalog?.groups.orEmpty().map { "group:${it.group.id.value}" to "Group · ${it.group.name}" }
        ChoiceField("Report scope", fields.scope, scopes, { value -> vm.exportChange { it.copy(scope = value) } }, enabled)
        FormTextField("From date (optional, YYYY-MM-DD)", fields.from, { value -> vm.exportChange { it.copy(from = value) } }, enabled)
        FormTextField("To date (optional, YYYY-MM-DD)", fields.to, { value -> vm.exportChange { it.copy(to = value) } }, enabled)
    } else Text("Normalized exports always include the entire ledger, including history references and deleted rows. Scope/date restrictions are unavailable.")
    if (fields.format == ExportFormat.CSV) SettingsSwitch("Excel-friendly CSV (BOM and semicolon)", fields.excelFriendly, enabled) { value -> vm.exportChange { it.copy(excelFriendly = value) } }
    Text("Exports may contain sensitive financial data and are not encrypted. IOU does not share them automatically.")
    Button(onClick = vm::pickExport, enabled = enabled) { Text(if (services.simulated) "Choose destination & simulate export" else "Choose destination & export") }
}

private fun LazyListScope.importPreviewRows(preview: ImportPreview, state: DataState, hidden: Boolean, enabled: Boolean, vm: DataManagementViewModel) {
    if (preview.identity is ImportIdentity.NeedsConfirmation) {
        item(key = "identity-confirmation") { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Notice("Confirm the source identity", "Is this your own IOU export from another installation? Accepting maps the source identity to you. No financial preview is exposed before you decide.")
        Button(onClick = { vm.confirmIdentity(true) }, enabled = enabled) { Text("This is my own data") }
        OutlinedButton(onClick = { vm.confirmIdentity(false) }, enabled = enabled) { Text("Decline; do not import") }
        } }
        return
    }
    item(key = "import-summary") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BrowseHeading("Review import")
    InfoLine("File", if (hidden) "File name hidden" else preview.fileName)
    Text("${preview.counts.new} new · ${preview.counts.conflicts} conflicts · ${preview.counts.duplicates} duplicates · ${preview.counts.invalid} invalid")
    if (preview.identity is ImportIdentity.Mapped) Text("Source identity explicitly mapped to your existing identity.")
    } }
    items(preview.balanceChanges) { change ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BrowseHeading(change.counterpart); Text("Before"); MoneyLines(change.before, hidden, signed = true); Text("After"); MoneyLines(change.after, hidden, signed = true)
    }
    }
    items(preview.rows, key = { "row:${it.key.type}:${it.key.id}" }) { row ->
        IouCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (hidden) "${row.key.type.name.lowercase().replace('_', ' ')} record · details hidden" else row.label, style = MaterialTheme.typography.titleMedium)
            if (row.changedSinceLastPreview) Text("Changed since last preview · review this row", color = MaterialTheme.colorScheme.primary)
            row.changes.forEach { change -> Text(if (hidden) "${change.field}: values hidden" else "${change.field}: ${change.before ?: "—"} → ${change.after ?: "—"}") }
            when (val choice = row.choice) {
                ImportRowChoice.KeepOrTake -> SettingsSwitch(if (row.key in state.choices.takeIncoming) "Take incoming" else "Keep local", row.key in state.choices.takeIncoming, enabled) { take ->
                    vm.choices { it.copy(takeIncoming = if (take) it.takeIncoming + row.key else it.takeIncoming - row.key) }
                }
                is ImportRowChoice.DeletedWith -> Text(if (hidden) "Forced deletion with owner · not individually selectable" else "Deleted with ${choice.owner} · controlled by owner")
                is ImportRowChoice.RestoredWith -> Text(if (hidden) "Forced restoration with owner · not individually selectable" else "Restored with ${choice.owner} · controlled by owner")
            }
        } }
    }
    items(preview.invalidRows, key = { "invalid:${it.table}:${it.rowNumber}" }) { row ->
        Notice("Invalid row ${row.rowNumber} · ${row.table}", row.error?.userMessage(hidden) ?: if (hidden) "Validation details hidden" else row.reason)
    }
    item(key = "import-actions") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (preview.invalidRows.isNotEmpty()) SettingsSwitch("Deliberately skip invalid rows", state.choices.skipInvalidRows, enabled) { skip -> vm.choices { it.copy(skipInvalidRows = skip) } }
    OutlinedButton(onClick = vm::refresh, enabled = enabled) { Text("Refresh preview") }
    Button(onClick = vm::requestCommit, enabled = enabled && state.error != LedgerError.ImportPreviewStale) { Text("Review import confirmation") }
    } }
}
