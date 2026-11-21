package io.github.codex07.iou.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.navigation.RecurringNotificationRoute
import io.github.codex07.iou.ui.settings.ThemePreference
import io.github.codex07.iou.ui.theme.IOUTheme
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun IouRoot(security: SecurityViewModel, requestAuthentication: (AuthPurpose) -> Unit,
            launchAuthentication: (AuthenticationRequest) -> Unit, cancelAuthentication: () -> Unit,
            settings: AppSettingsViewModel = hiltViewModel(),
            dueRequest: RecurringNotificationRoute? = null, onDueOpened: (RecurringNotificationRoute) -> Unit = {},
            onScheduleSaved: () -> Unit = {}) {
    val protection by security.state.collectAsStateWithLifecycle()
    val page by settings.page.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    val generation by settings.generation.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(protection.request?.id, protection.request?.launched) {
        if (protection.request == null) cancelAuthentication() else protection.request?.takeIf { !it.launched }?.let(launchAuthentication)
    }
    IOUTheme(darkTheme = when (theme) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.DARK -> true; ThemePreference.LIGHT -> false }) {
        Surface(Modifier.fillMaxSize()) {
            when {
                protection.covered -> {
                    BackHandler(enabled = protection.locked) { }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(IouIcons.Shield, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("IOU", style = MaterialTheme.typography.headlineLarge)
                        if (protection.unavailable) Unit
                        else if (protection.loading) CircularProgressIndicator()
                        else if (!protection.foreground) Text("Private financial records")
                        else {
                            Text("Unlock to view your financial records")
                            Button(onClick = { requestAuthentication(AuthPurpose.UNLOCK) }, enabled = protection.request == null && !protection.busy) { Text("Unlock") }
                        }
                        protection.message?.let { Text(it) }
                    }
                }
                page == null || generation == null -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    val action by settings.action.collectAsStateWithLifecycle()
                    if (action.message == null) CircularProgressIndicator() else { Text(action.message.orEmpty()); Button(onClick = settings::retry) { Text("Retry settings") } }
                }
                page?.setup is SetupState.NeedsSetup -> SetupScreen(settings)
                else -> holder.SaveableStateProvider("financial-app") {
                    IOUApp(settingsModel = settings, security = security, requestAuthentication = requestAuthentication,
                        ledgerGeneration = generation, dueRequest = dueRequest, onDueOpened = onDueOpened,
                        onScheduleSaved = onScheduleSaved)
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: AppSettingsViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EUR") }
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text("Welcome to IOU", style = MaterialTheme.typography.headlineLarge)
        val services = vm.dataServices
        if (services.simulated) Notice("Demo data only", "This build uses an in-memory demo ledger. Your setup and records are not saved when the app process ends.")
        FormTextField("Your display name", name, { name = it }, !action.busy)
        FormTextField("Home currency code", currency, { currency = it.uppercase() }, !action.busy)
        action.error?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        action.message?.let { Text(it) }
        Button(onClick = { vm.completeSetup(name, currency) }, enabled = !action.busy) { Text(if (action.busy) "Setting up…" else "Create my ledger identity") }
    }
}
