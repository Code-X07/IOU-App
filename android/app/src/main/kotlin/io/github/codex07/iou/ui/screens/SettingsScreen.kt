package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.BuildConfig
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.settings.*
import io.github.codex07.iou.ui.viewmodel.*
import kotlinx.coroutines.launch

fun LockTimeout.displayLabel(): String = when (this) {
    LockTimeout.IMMEDIATELY -> "Immediately"; LockTimeout.ONE_MINUTE -> "1 minute"; LockTimeout.FIVE_MINUTES -> "5 minutes"; LockTimeout.FIFTEEN_MINUTES -> "15 minutes"
}

@Composable
fun SettingsScreen(vm: AppSettingsViewModel, security: SecurityViewModel, hidden: Boolean,
                   authenticate: (AuthPurpose) -> Unit, openData: (String) -> Unit) {
    val page by vm.page.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val guard by security.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    var currency by rememberSaveable { mutableStateOf("") }
    var editCurrency by rememberSaveable { mutableStateOf(false) }
    var chooseTimeout by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var tokenGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    var tokenVersion by rememberSaveable { mutableStateOf<Int?>(null) }
    val token = if (tokenGeneration != null && tokenVersion != null) EditToken(io.github.codex07.iou.ledger.model.LedgerGeneration(tokenGeneration!!), tokenVersion!!) else null
    val scope = rememberCoroutineScope()
    val current = page
    if (current == null) { CircularProgressIndicator(); return }
    BrowseList(scroll, reserveAddSpace = false) {
        action.error?.let { error -> item { Notice("Settings need review", error.userMessage(hidden)) } }
        action.message?.let { message -> item { Notice("Settings", message) } }
        guard.message?.let { message -> item { Notice("Authentication", message) } }
        item { BrowseHeading("Profile & account") }
        item {
            SettingsGroup {
                SettingsRow("Display name", (current.setup as? SetupState.Ready)?.me?.displayName.orEmpty(), IouIcons.People,
                    enabled = !action.busy, onClick = {
                        name = (current.setup as? SetupState.Ready)?.me?.displayName.orEmpty()
                        tokenGeneration = null; tokenVersion = null; editName = true
                        scope.launch { val captured = vm.profileToken(); tokenGeneration = captured?.generation?.value; tokenVersion = captured?.version }
                    })
                SettingsDivider()
                SettingsRow("Home currency · ${current.settings.homeCurrency.code}", "Used for approximate display totals", IouIcons.Settle,
                    enabled = !action.busy, onClick = { currency = current.settings.homeCurrency.code; editCurrency = true })
            }
        }
        item { BrowseHeading("Appearance") }
        item {
            SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceField("Theme on this device", theme.name, ThemePreference.entries.map { it.name to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, { vm.setTheme(ThemePreference.valueOf(it)) })
                    Text("System text scaling is respected. Theme preference is saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { BrowseHeading("Security & privacy") }
        item {
            SettingsGroup {
                SettingsToggle("App lock", "Authenticate with your Android screen lock", IouIcons.Shield,
                    current.settings.appLockEnabled, !guard.busy && guard.request == null) {
                    if (it) authenticate(AuthPurpose.ENABLE_LOCK) else security.disable()
                }
                SettingsDivider()
                SettingsRow("Saved timeout · ${current.settings.lockTimeout.displayLabel()}", "Lock after the app has been in the background this long", IouIcons.Activity,
                    enabled = !action.busy, onClick = { chooseTimeout = true })
                SettingsDivider()
                SettingsToggle("Screen protection", "Block screenshots and protected task previews", IouIcons.EyeOff,
                    current.settings.screenProtection, !action.busy, vm::screenProtection)
            }
        }
        item { Text("Hide Balances masks financial amounts throughout IOU. Sensitive form inputs require deliberate reveal.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { BrowseHeading("Backup & data management") }
        if (vm.dataServices.simulated) item { DisclosureNotice("Simulated data services", "No usable financial backup or export files are written. Use test passphrases only.", vm.dataServices.notice) }
        item {
            SettingsGroup {
                SettingsToggle("Encrypt backups by default", "Applies to the next backup form", IouIcons.Shield,
                    current.settings.backupEncryptionDefault, !action.busy, vm::backupEncryption)
                SettingsDivider()
                SettingsRow("Create backup", "${if (current.reminder.due) "Reminder due" else if (vm.dataServices.simulated) "Simulated backup status" else "Backup status"} · Last: ${current.reminder.lastBackupAt?.toString() ?: "Never"}", IouIcons.Backup,
                    onClick = { openData("backup") })
                SettingsDivider()
                SettingsRow("Restore & undo restore", "Replaces the whole ledger after confirmation", IouIcons.Back,
                    destructive = true, onClick = { openData("restore") })
                SettingsDivider()
                SettingsRow("Export data", "Choose report, format and scope", IouIcons.UpRight, onClick = { openData("export") })
                SettingsDivider()
                SettingsRow("Import data", "Preview normalized CSV ZIP before applying", IouIcons.DownLeft, onClick = { openData("import") })
                SettingsDivider()
                SettingsRow("Import history", "Review batches and available undo", IouIcons.Activity, onClick = { openData("history") })
            }
        }
        item { BrowseHeading("About IOU") }
        item {
            SettingsGroup {
                SettingsRow("IOU ${BuildConfig.VERSION_NAME}", "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.FLAVOR}", IouIcons.Info)
                Text("IOU records obligations and repayments. It never transfers money, adds cloud sync or signs you into an account. " + if (vm.dataServices.simulated) "This build uses simulated in-memory data services." else "Your data stays on this device.",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (editCurrency) AlertDialog(onDismissRequest = { if (!action.busy) editCurrency = false }, title = { Text("Home currency") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormTextField("Currency code", currency, { currency = it.uppercase() }, !action.busy)
            Text("Changes display approximations only. Original transaction currencies and historical FX snapshots stay intact.")
        } }, confirmButton = { TextButton(onClick = { vm.setCurrency(currency); editCurrency = false }, enabled = !action.busy && currency.isNotBlank()) { Text("Update") } },
        dismissButton = { TextButton(onClick = { editCurrency = false }) { Text("Cancel") } })
    if (chooseTimeout) AlertDialog(onDismissRequest = { chooseTimeout = false }, title = { Text("Saved lock timeout") },
        text = { Column {
            Text("The app locks after it has been in the background for the chosen time. Immediately locks on every return.")
            LockTimeout.entries.forEach { timeout ->
                TextButton(onClick = { vm.setTimeout(timeout); chooseTimeout = false }, enabled = !action.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(timeout.displayLabel(), Modifier.weight(1f))
                    if (timeout == current.settings.lockTimeout) Text("Selected")
                }
            }
        } }, confirmButton = { TextButton(onClick = { chooseTimeout = false }) { Text("Close") } })
    if (editName) AlertDialog(onDismissRequest = { if (!action.busy) editName = false }, title = { Text("Edit display name") },
        text = { FormTextField("Display name", name, { name = it }, !action.busy) },
        confirmButton = { TextButton(onClick = { token?.let { vm.updateProfile(name, it); editName = false } }, enabled = !action.busy && token != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel") } })
}
