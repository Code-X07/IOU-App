package io.github.codex07.iou.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.codex07.iou.R
import io.github.codex07.iou.ui.components.Avatar
import io.github.codex07.iou.ui.components.IouIcons
import io.github.codex07.iou.ui.navigation.Destination
import io.github.codex07.iou.ui.screens.OverviewScreen
import io.github.codex07.iou.ui.viewmodel.OverviewUiState
import io.github.codex07.iou.ui.viewmodel.OverviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOUApp(viewModel: OverviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedName by rememberSaveable { mutableStateOf(Destination.Overview.name) }
    val selected = Destination.valueOf(selectedName)
    var hidden by rememberSaveable { mutableStateOf(false) }
    var unfinished by rememberSaveable { mutableStateOf<String?>(null) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    val settings = stringResource(R.string.profile_settings)
    val add = stringResource(R.string.add_record)
    val expense = stringResource(R.string.shared_expense)
    val loan = stringResource(R.string.personal_loan)
    val repayment = stringResource(R.string.record_repayment)
    val profile = (state as? OverviewUiState.Ready)?.displayName.orEmpty()
    BackHandler(enabled = selected != Destination.Overview && unfinished == null && !addMenu) {
        selectedName = Destination.Overview.name
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected == Destination.Overview) stringResource(R.string.brand) else stringResource(selected.title),
                        style = MaterialTheme.typography.headlineSmall)
                },
                actions = {
                    if (selected == Destination.Overview) IconButton(onClick = { hidden = !hidden }) {
                        Icon(if (hidden) IouIcons.EyeOff else IouIcons.Eye,
                            stringResource(if (hidden) R.string.show_balances else R.string.hide_balances))
                    }
                    IconButton(onClick = { unfinished = settings }, modifier = Modifier.semantics {
                        contentDescription = settings
                    }) { Avatar(profile, Modifier.size(32.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selectedName = destination.name },
                        icon = { Icon(destination.icon(), null) },
                        label = { Text(stringResource(destination.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.secondary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            if (selected == Destination.Overview) ExtendedFloatingActionButton(
                onClick = { addMenu = true }, icon = { Icon(IouIcons.Add, null) }, text = { Text(add) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            if (selected == Destination.Overview) OverviewScreen(state, hidden,
                onNavigate = { selectedName = it.name }, onFeature = { unfinished = it }, onRetry = viewModel::retry)
            else UnfinishedDestination(stringResource(selected.title)) { selectedName = Destination.Overview.name }
        }
    }
    if (addMenu) ModalBottomSheet(onDismissRequest = { addMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(add, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.not_implemented), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(expense to IouIcons.Activity, loan to IouIcons.People, repayment to IouIcons.Settle).forEach { (label, icon) ->
                TextButton(onClick = { addMenu = false; unfinished = label }, modifier = Modifier.fillMaxWidth()) {
                    Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f))
                    Icon(IouIcons.Chevron, null)
                }
            }
        }
    }
    if (unfinished != null) AlertDialog(
        onDismissRequest = { unfinished = null },
        icon = { Icon(IouIcons.Info, null) },
        title = { Text(stringResource(R.string.not_implemented)) },
        text = { Text(stringResource(R.string.feature_unfinished, unfinished.orEmpty())) },
        confirmButton = { TextButton(onClick = { unfinished = null }) { Text(stringResource(R.string.done)) } },
    )
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Overview -> IouIcons.Overview
    Destination.People -> IouIcons.People
    Destination.Groups -> IouIcons.Groups
    Destination.Activity -> IouIcons.Activity
    Destination.Settlements -> IouIcons.Settle
}

@Composable
private fun UnfinishedDestination(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(IouIcons.Info, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.not_implemented), color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.phase_one_only), style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.back_overview)) }
    }
}
