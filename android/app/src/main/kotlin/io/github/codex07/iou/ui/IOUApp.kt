package io.github.codex07.iou.ui

import androidx.activity.compose.BackHandler
import java.util.UUID
import kotlinx.coroutines.launch
import io.github.codex07.iou.ledger.model.PersonId
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.codex07.iou.ui.screens.*
import io.github.codex07.iou.ui.viewmodel.*
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
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activeForm = stack.lastOrNull()?.startsWith("form-") == true
    fun formRoute(kind: String, suffix: String = "") = "form-$kind:${UUID.randomUUID()}:$suffix"
    val addPerson: () -> Unit = { stack = stack + formRoute("person") }
    val addGroup: () -> Unit = { stack = stack + formRoute("group") }
    val addEntry: (String) -> Unit = { purpose -> stack = stack + formRoute("entry", purpose) }
    val session: BrowseSessionViewModel = hiltViewModel()
    val generation by session.generation.collectAsStateWithLifecycle()
    var lastGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(generation) {
        generation?.let {
            if (lastGeneration != null && lastGeneration != it.value) {
                stack = emptyList(); selectedName = Destination.Overview.name
                snackbar.showSnackbar("Your ledger was replaced. Unfinished forms were closed. Reopen records from the current ledger.")
            }
            lastGeneration = it.value
        }
    }
    val openPerson: (String) -> Unit = { stack = stack + "person:$it" }
    val openGroup: (String) -> Unit = { stack = stack + "group:$it" }
    val openEntry: (String) -> Unit = { stack = stack + "entry:$it" }
    BackHandler(enabled = !activeForm && stack.isNotEmpty() && unfinished == null && !addMenu) { stack = stack.dropLast(1) }
    val settings = stringResource(R.string.profile_settings)
    val add = stringResource(R.string.add_record)
    val expense = stringResource(R.string.shared_expense)
    val loan = stringResource(R.string.personal_loan)
    val repayment = stringResource(R.string.record_repayment)
    val addPersonLabel = stringResource(R.string.add_person)
    val createGroupLabel = stringResource(R.string.create_group)
    val addExpenseLabel = stringResource(R.string.add_expense)
    val profile = (state as? OverviewUiState.Ready)?.displayName.orEmpty()
    BackHandler(enabled = stack.isEmpty() && selected != Destination.Overview && unfinished == null && !addMenu) {
        selectedName = Destination.Overview.name
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (!activeForm) {
            TopAppBar(
                title = {
                    Text(if (stack.isNotEmpty()) when (stack.last().substringBefore(":")) { "person" -> "Person details"; "group" -> "Group details"; else -> "Transaction details" } else if (selected == Destination.Overview) stringResource(R.string.brand) else stringResource(selected.title),
                        style = MaterialTheme.typography.headlineSmall)
                },
                navigationIcon = { if (stack.isNotEmpty()) IconButton(onClick = { stack = stack.dropLast(1) }) { Icon(IouIcons.Back, "Back") } },
                actions = {
                    IconButton(onClick = { hidden = !hidden }) {
                        Icon(if (hidden) IouIcons.EyeOff else IouIcons.Eye,
                            stringResource(if (hidden) R.string.show_balances else R.string.hide_balances))
                    }
                    IconButton(onClick = { unfinished = settings }, modifier = Modifier.semantics {
                        contentDescription = settings
                    }) { Avatar(profile, Modifier.size(32.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        } },
        bottomBar = { if (!activeForm) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { stack = emptyList(); selectedName = destination.name },
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
        } },
        floatingActionButton = {
            if (stack.isEmpty()) ExtendedFloatingActionButton(
                onClick = { addMenu = true }, icon = { Icon(IouIcons.Add, null) }, text = { Text(add) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            val route = stack.lastOrNull()
            holder.SaveableStateProvider(route ?: selected.name) {
                if (route != null) {
                    val id = route.substringAfter(':')
                    when (route.substringBefore(':')) {
                        "form-person", "form-handle", "form-group" -> {
                            val parts = route.split(':')
                            val vm: MetadataFormViewModel = hiltViewModel(key = route)
                            val kind = when (parts[0]) { "form-person" -> MetadataKind.PERSON; "form-handle" -> MetadataKind.HANDLE; else -> MetadataKind.GROUP }
                            val entity = if (kind == MetadataKind.HANDLE) parts.getOrNull(3)?.takeIf { it.isNotBlank() } else parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                            val owner = if (kind == MetadataKind.HANDLE) parts.getOrNull(2) else null
                            val members = if (kind == MetadataKind.GROUP) parts.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.map(::PersonId).toSet() else emptySet()
                            val parent = stack.dropLast(1).lastOrNull()?.takeIf { it.startsWith("form-entry:") || it.startsWith("form-entryedit:") }
                            val parentVm: EntryFormViewModel? = if (parent != null) hiltViewModel(key = parent) else null
                            LaunchedEffect(route) { vm.open(kind, entity, owner, members) }
                            val form by vm.state.collectAsStateWithLifecycle()
                            val status by vm.status.collectAsStateWithLifecycle()
                            MetadataFormScreen(vm, form, status, hidden,
                                close = { vm.end(); stack = stack.dropLast(1) },
                                saved = { savedId ->
                                    vm.end()
                                    if (kind == MetadataKind.GROUP && parentVm != null) {
                                        parentVm.useCreatedGroup(savedId); stack = stack.dropLast(1)
                                    } else {
                                        stack = stack.dropLast(1)
                                        if (entity == null && kind != MetadataKind.HANDLE) stack = stack + "${if (kind == MetadataKind.PERSON) "person" else "group"}:$savedId"
                                    }
                                    scope.launch { snackbar.showSnackbar(if (entity == null) "Saved" else "Changes saved") }
                                })
                        }
                        "form-entry", "form-entryedit" -> {
                            val parts = route.split(':')
                            val vm: EntryFormViewModel = hiltViewModel(key = route)
                            LaunchedEffect(route) { vm.open(parts.getOrNull(2) ?: "expense", if (parts[0] == "form-entryedit") parts.getOrNull(2) else null) }
                            val form by vm.state.collectAsStateWithLifecycle()
                            val status by vm.status.collectAsStateWithLifecycle()
                            EntryFormScreen(vm, form, status, hidden,
                                close = { vm.finish(); stack = stack.dropLast(1) },
                                saved = { savedId ->
                                    vm.finish(); stack = stack.dropLast(1)
                                    if (stack.lastOrNull() != "entry:$savedId") stack = stack + "entry:$savedId"
                                }, createGroup = { members -> stack = stack + formRoute("group", ":" + members.joinToString(",") { it.value }) })
                        }
                        "group" -> {
                            val vm: GroupDetailViewModel = hiltViewModel(key = route)
                            LaunchedEffect(id) { vm.open(id) }
                            val detail by vm.state.collectAsStateWithLifecycle()
                            GroupDetailScreen(detail, hidden, vm::retry, openPerson, openEntry, onEdit = { stack = stack + formRoute("group", id) })
                        }
                        "entry" -> {
                            val vm: EntryDetailViewModel = hiltViewModel(key = route)
                            LaunchedEffect(id) { vm.open(id) }
                            val detail by vm.state.collectAsStateWithLifecycle()
                            EntryDetailScreen(detail, hidden, vm::retry, openPerson, openGroup, onEdit = { stack = stack + formRoute("entryedit", id) })
                        }
                        "person" -> {
                            val vm: PersonDetailViewModel = hiltViewModel(key = route)
                            LaunchedEffect(id) { vm.open(id) }
                            val detail by vm.state.collectAsStateWithLifecycle()
                            PersonDetailScreen(detail, hidden, vm::retry, openGroup, openEntry,
                                onEdit = { stack = stack + formRoute("person", id) },
                                onAddHandle = { stack = stack + formRoute("handle", id) },
                                onEditHandle = { handle -> stack = stack + formRoute("handle", "$id:$handle") })
                        }
                    }
                } else when (selected) {
                    Destination.Overview -> OverviewScreen(state, hidden,
                        onNavigate = { selectedName = it.name }, onFeature = { feature -> when (feature) { addPersonLabel -> addPerson(); createGroupLabel -> addGroup(); addExpenseLabel -> addEntry("expense"); else -> unfinished = feature } }, onRetry = viewModel::retry,
                        onPerson = openPerson, onGroup = openGroup, onEntry = openEntry)
                    Destination.Groups -> {
                        val vm: GroupsViewModel = hiltViewModel()
                        val groups by vm.state.collectAsStateWithLifecycle()
                        GroupsScreen(groups, hidden, vm::retry, openGroup, onAdd = addGroup)
                    }
                    Destination.Activity -> {
                        val vm: ActivityViewModel = hiltViewModel()
                        val activity by vm.state.collectAsStateWithLifecycle()
                        ActivityScreen(activity, hidden, vm::retry, vm::filter, openEntry)
                    }
                    Destination.People -> {
                        val vm: PeopleViewModel = hiltViewModel()
                        val people by vm.state.collectAsStateWithLifecycle()
                        PeopleScreen(people, hidden, vm::retry, openPerson, onAdd = addPerson)
                    }
                    else -> UnfinishedDestination(stringResource(selected.title)) { selectedName = Destination.Overview.name }
                }
            }
        }
    }
    if (addMenu) ModalBottomSheet(onDismissRequest = { addMenu = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(add, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("What would you like to record? IOU never moves money.", style = MaterialTheme.typography.bodyMedium)
            listOf(Triple("expense", "Add expense", IouIcons.Activity), Triple("loan", "Record loan", IouIcons.People),
                Triple("repayment", "Record repayment", IouIcons.Settle), Triple("transfer", "Record transfer", IouIcons.UpRight)).forEach { (purpose, label, icon) ->
                TextButton(onClick = { addMenu = false; addEntry(purpose) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f)); Icon(IouIcons.Chevron, null)
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
        Text("Settlement recording is planned for a later phase.", style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onBack) { Text(stringResource(R.string.back_overview)) }
    }
}
