package io.github.codex07.iou.ui

import androidx.activity.compose.BackHandler
import java.util.UUID
import kotlinx.coroutines.launch
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import java.time.LocalDate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.codex07.iou.ui.screens.*
import io.github.codex07.iou.ui.viewmodel.*
import io.github.codex07.iou.ui.components.PageEntrance
import io.github.codex07.iou.ui.navigation.NavigationScene
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.codex07.iou.ui.navigation.RouteStores
import io.github.codex07.iou.ui.navigation.RecurringNotificationRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.codex07.iou.R
import io.github.codex07.iou.ui.components.AdvancedDialog
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ui.components.Avatar
import io.github.codex07.iou.ui.components.IouIcons
import io.github.codex07.iou.ui.navigation.Destination
import io.github.codex07.iou.ui.screens.OverviewScreen
import io.github.codex07.iou.ui.viewmodel.OverviewUiState
import io.github.codex07.iou.ui.viewmodel.OverviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOUApp(viewModel: OverviewViewModel = hiltViewModel(), settingsModel: AppSettingsViewModel = hiltViewModel(),
           security: SecurityViewModel = hiltViewModel(), requestAuthentication: (AuthPurpose) -> Unit = {}, ledgerGeneration: io.github.codex07.iou.ledger.model.LedgerGeneration? = null,
           dueRequest: RecurringNotificationRoute? = null, onDueOpened: (RecurringNotificationRoute) -> Unit = {},
           onScheduleSaved: () -> Unit = {}) {
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
    val advanced: AdvancedViewModel = hiltViewModel()
    val advancedState by advanced.state.collectAsStateWithLifecycle()
    LaunchedEffect(advancedState.openRoute) {
        advancedState.openRoute?.let { stack = stack + it; advanced.consumeRoute() }
    }
    val session: BrowseSessionViewModel = hiltViewModel()
    val observedGeneration by session.generation.collectAsStateWithLifecycle()
    val generation = ledgerGeneration ?: observedGeneration
    val routeStores: RouteStores = viewModel()
    val activity = LocalActivity.current as ComponentActivity
    // Routes outlive rotation; this drops the destroyed activity they delegate their ViewModel factory to.
    DisposableEffect(routeStores, activity) { onDispose { routeStores.detach(activity) } }
    var previousKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(stack, generation?.value) {
        routeStores.retainOnly(stack.toSet())
        val keys = stack.map { it + "@" + generation?.value.orEmpty() }.toSet()
        (previousKeys - keys).forEach { holder.removeState(it) }
        previousKeys = keys
    }
    var lastGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    val replacementOutcome by settingsModel.replacements.outcome.collectAsStateWithLifecycle()
    val restoreInterrupted by settingsModel.restoreInterrupted.collectAsStateWithLifecycle()
    LaunchedEffect(generation) {
        generation?.let {
            val replaced = lastGeneration != null && lastGeneration != it.value
            lastGeneration = it.value
            if (replaced) {
                stack = emptyList(); selectedName = Destination.Overview.name
                // A restore or undo from Data management delivers its own outcome below; the generic text is for outside replacements.
                if (!settingsModel.replacements.running.value && settingsModel.replacements.outcome.value == null)
                    snackbar.showSnackbar("Your ledger was replaced. Unfinished forms were closed. Reopen records from the current ledger.")
            }
        }
    }
    LaunchedEffect(replacementOutcome) {
        replacementOutcome?.let { text -> snackbar.showSnackbar(text); settingsModel.replacements.consumeOutcome() }
    }
    val openPerson: (String) -> Unit = { stack = stack + "person:$it" }
    val openGroup: (String) -> Unit = { stack = stack + "group:$it" }
    val openEntry: (String) -> Unit = { stack = stack + "entry:$it" }
    val openSchedule: (RecurringId) -> Unit = { id -> stack = stack + "schedule:${id.value}" }
    val openDue: () -> Unit = { if ("due:" !in stack) stack = stack + "due:" else stack = stack.take(stack.indexOf("due:") + 1) }
    BackHandler(enabled = !activeForm && stack.isNotEmpty() && unfinished == null && !addMenu) { stack = stack.dropLast(1) }
    val settings = stringResource(R.string.profile_settings)
    val add = stringResource(R.string.add_record)
    val expense = stringResource(R.string.shared_expense)
    val loan = stringResource(R.string.personal_loan)
    val repayment = stringResource(R.string.record_repayment)
    val addPersonLabel = stringResource(R.string.add_person)
    val createGroupLabel = stringResource(R.string.create_group)
    val addExpenseLabel = stringResource(R.string.add_expense)
    val backupControlsLabel = stringResource(R.string.backup_controls)
    val scene = NavigationScene(selectedName, stack, generation?.value)
    val profile = (state as? OverviewUiState.Ready)?.displayName.orEmpty()
    BackHandler(enabled = stack.isEmpty() && selected != Destination.Overview && unfinished == null && !addMenu) {
        selectedName = Destination.Overview.name
    }
    if (generation != null && lastGeneration != null && generation.value != lastGeneration) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LaunchedEffect(dueRequest) {
        dueRequest?.let { request ->
            addMenu = false
            stack = request.open(stack)
            onDueOpened(request)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (!activeForm) {
            TopAppBar(
                title = {
                    Text(if (stack.isNotEmpty()) when (stack.last().substringBefore(":")) { "settings" -> "Settings"; "person" -> "Person details"; "group" -> "Group details"; "members" -> "Manage members"; "schedules" -> "Recurring expenses"; "schedule" -> "Schedule details"; "held" -> "Schedules on hold"; "deleted-schedules" -> "Deleted schedules"; "due" -> "Due expenses"; else -> "Transaction details" } else if (selected == Destination.Overview) stringResource(R.string.brand) else stringResource(selected.title),
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { if (stack.isNotEmpty()) IconButton(onClick = { stack = stack.dropLast(1) }) { Icon(IouIcons.Back, "Back") } },
                actions = {
                    IconButton(onClick = { hidden = !hidden }) {
                        Icon(if (hidden) IouIcons.EyeOff else IouIcons.Eye,
                            stringResource(if (hidden) R.string.show_balances else R.string.hide_balances))
                    }
                    if (stack.isEmpty()) IconButton(onClick = { stack = stack + "schedules:" }) {
                        Icon(IouIcons.Recurring, "Recurring expenses")
                    }
                    if (!scene.isSettings) IconButton(onClick = { if (stack.lastOrNull() != "settings:") stack = stack + "settings:" }, modifier = Modifier.semantics {
                        contentDescription = settings
                    }) { Avatar(profile, Modifier.size(32.dp)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        } },
        bottomBar = { if (scene.showBottomBar) {
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
            if (scene.showAdd) ExtendedFloatingActionButton(
                onClick = { addMenu = true }, icon = { Icon(IouIcons.Add, null) }, text = { Text(add) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            val route = stack.lastOrNull()
            CompositionLocalProvider(LocalReserveAddSpace provides scene.showAdd) {
            PageEntrance(scene) {
                    holder.SaveableStateProvider((route ?: selected.name) + "@" + generation?.value.orEmpty()) {
                        if (route != null) {
                          CompositionLocalProvider(LocalViewModelStoreOwner provides routeStores.owner(route, activity)) {
                            val id = route.substringAfter(':')
                            when (route.substringBefore(':')) {
                                "form-data" -> {
                                    val vm: DataManagementViewModel = hiltViewModel()
                                    LaunchedEffect(route) { vm.open(route.split(':').getOrNull(2) ?: "backup") }
                                    DataManagementScreen(vm, hidden) { stack = stack.dropLast(1) }
                                }
                                "settings" -> {
                                    SettingsScreen(settingsModel, security, hidden, requestAuthentication,
                                        openData = { page -> stack = stack + formRoute("data", page) })
                                }
                                "form-prepared" -> {
                                    val key = route.substringAfter(':')
                                    when (val prepared = advanced.form(key)) {
                                        is PreparedForm.Repayment -> {
                                            val vm: EntryFormViewModel = hiltViewModel()
                                            LaunchedEffect(route) { vm.openSettlement(prepared.draft) }
                                            val form by vm.state.collectAsStateWithLifecycle()
                                            val status by vm.status.collectAsStateWithLifecycle()
                                            EntryFormScreen(vm, form, status, hidden,
                                                close = { vm.finish(); advanced.forget(key); stack = stack.dropLast(1) },
                                                saved = { savedId -> vm.finish(); advanced.forget(key); stack = stack.dropLast(1) + "entry:$savedId" },
                                                createGroup = { members -> stack = stack + formRoute("group", ":" + members.joinToString(",") { it.value }) })
                                        }
                                        is PreparedForm.Recovery -> {
                                            if (prepared.draft.draft is VersionDraft.OfEntry) {
                                                val vm: EntryFormViewModel = hiltViewModel()
                                                LaunchedEffect(route) { vm.openRecovery(prepared.draft) }
                                                val form by vm.state.collectAsStateWithLifecycle()
                                                val status by vm.status.collectAsStateWithLifecycle()
                                                EntryFormScreen(vm, form, status, hidden,
                                                    close = { vm.finish(); advanced.forget(key); stack = stack.dropLast(1) },
                                                    saved = { savedId -> vm.finish(); advanced.forget(key); stack = stack.dropLast(1); if (stack.lastOrNull() != "entry:$savedId") stack = stack + "entry:$savedId" },
                                                    createGroup = { members -> stack = stack + formRoute("group", ":" + members.joinToString(",") { it.value }) })
                                            } else if (prepared.draft.draft is VersionDraft.OfSchedule) {
                                                val vm: EntryFormViewModel = hiltViewModel()
                                                LaunchedEffect(route) { vm.openSchedule(RecurringId(prepared.draft.entity.id), prepared.draft) }
                                                val form by vm.state.collectAsStateWithLifecycle()
                                                val status by vm.status.collectAsStateWithLifecycle()
                                                EntryFormScreen(vm, form, status, hidden,
                                                    close = { vm.finish(); advanced.forget(key); stack = stack.dropLast(1) },
                                                    saved = { vm.finish(); advanced.forget(key); stack = stack.dropLast(1) },
                                                    createGroup = { members -> stack = stack + formRoute("group", ":" + members.joinToString(",") { it.value }) },
                                                    scheduleForm = true, onScheduleSaved = onScheduleSaved)
                                            } else {
                                                val vm: MetadataFormViewModel = hiltViewModel()
                                                LaunchedEffect(route) { vm.openRecovery(prepared.draft) }
                                                val form by vm.state.collectAsStateWithLifecycle()
                                                val status by vm.status.collectAsStateWithLifecycle()
                                                MetadataFormScreen(vm, form, status, hidden,
                                                    close = { vm.end(); advanced.forget(key); stack = stack.dropLast(1) },
                                                    saved = { vm.end(); advanced.forget(key); stack = stack.dropLast(1); scope.launch { snackbar.showSnackbar("Historical draft saved as a new revision") } })
                                            }
                                        }
                                        null -> {
                                            BackHandler { stack = stack.dropLast(1) }
                                            Column(Modifier.padding(20.dp)) {
                                                Text("This prepared draft is unavailable. Return to the current ledger and prepare it again.")
                                                TextButton(onClick = { stack = stack.dropLast(1) }) { Text("Back") }
                                            }
                                        }
                                    }
                                }
                                "form-merge" -> {
                                    val vm: MergeViewModel = hiltViewModel()
                                    LaunchedEffect(route) { vm.open(route.split(':').getOrNull(2).orEmpty()) }
                                    val form by vm.state.collectAsStateWithLifecycle()
                                    val status by vm.status.collectAsStateWithLifecycle()
                                    MergeScreen(vm, form, status, hidden,
                                        close = { vm.end(); stack = stack.dropLast(1) },
                                        merged = { canonical -> vm.end(); stack = stack.dropLast(1); if (stack.lastOrNull()?.startsWith("person:") == true) stack = stack.dropLast(1); stack = stack + "person:$canonical" })
                                }
                                "members" -> {
                                    val vm: MembersViewModel = hiltViewModel()
                                    LaunchedEffect(id) { vm.open(id) }
                                    val members by vm.state.collectAsStateWithLifecycle()
                                    MembersScreen(members, hidden, vm::retry, advanced::request)
                                }
                                "form-person", "form-handle", "form-group" -> {
                                    val parts = route.split(':')
                                    val vm: MetadataFormViewModel = hiltViewModel()
                                    val kind = when (parts[0]) { "form-person" -> MetadataKind.PERSON; "form-handle" -> MetadataKind.HANDLE; else -> MetadataKind.GROUP }
                                    val entity = if (kind == MetadataKind.HANDLE) parts.getOrNull(3)?.takeIf { it.isNotBlank() } else parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                                    val owner = if (kind == MetadataKind.HANDLE) parts.getOrNull(2) else null
                                    val members = if (kind == MetadataKind.GROUP) parts.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.map(::PersonId).toSet() else emptySet()
                                    val parent = stack.dropLast(1).lastOrNull()?.takeIf { it.startsWith("form-entry:") || it.startsWith("form-entryedit:") || it.startsWith("form-prepared:") || it.startsWith("form-schedule:") || it.startsWith("form-occurrence:") }
                                    val parentVm: EntryFormViewModel? = if (parent != null) hiltViewModel(viewModelStoreOwner = routeStores.owner(parent, activity)) else null
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
                                    val vm: EntryFormViewModel = hiltViewModel()
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
                                "schedules", "held", "deleted-schedules" -> {
                                    val kind = when (route.substringBefore(':')) {
                                        "held" -> ScheduleList.HELD
                                        "deleted-schedules" -> ScheduleList.DELETED
                                        else -> ScheduleList.ACTIVE
                                    }
                                    val vm: ScheduleListViewModel = hiltViewModel()
                                    LaunchedEffect(kind) { vm.open(kind) }
                                    val schedules by vm.state.collectAsStateWithLifecycle()
                                    ScheduleListScreen(kind, schedules, hidden, vm::retry, openSchedule,
                                        create = { stack = stack + formRoute("schedule") }, due = openDue,
                                        held = { stack = stack + "held:" }, deleted = { stack = stack + "deleted-schedules:" })
                                }
                                "schedule" -> {
                                    val vm: ScheduleDetailViewModel = hiltViewModel()
                                    LaunchedEffect(id) { vm.open(RecurringId(id)) }
                                    val detail by vm.state.collectAsStateWithLifecycle()
                                    ScheduleDetailScreen(detail, hidden, vm::retry, edit = { stack = stack + formRoute("schedule", id) },
                                        openDue = openDue, openEntry = openEntry, request = advanced::request)
                                }
                                "due" -> {
                                    val vm: DueViewModel = hiltViewModel()
                                    val due by vm.state.collectAsStateWithLifecycle()
                                    val action by vm.action.collectAsStateWithLifecycle()
                                    val focus = if (id.isNotBlank()) {
                                        val detailVm: ScheduleDetailViewModel = hiltViewModel()
                                        LaunchedEffect(id) { detailVm.open(RecurringId(id)) }
                                        val detail by detailVm.state.collectAsStateWithLifecycle()
                                        (detail as? BrowseState.Ready)?.value?.summary?.takeUnless { it.deleted }
                                    } else null
                                    DueScreen(due, action, hidden, vm::retry, openSchedule,
                                        post = { occurrence -> stack = stack + formRoute("occurrence", "${occurrence.schedule.value}:${occurrence.date}") },
                                        skip = vm::prepareSkip, confirmSkip = vm::confirmSkip, dismiss = vm::dismiss,
                                        focus = focus, showAll = { stack = RecurringNotificationRoute(null).open(stack) })
                                }
                                "form-schedule", "form-occurrence" -> {
                                    val posting = route.startsWith("form-occurrence:")
                                    val parts = route.split(':')
                                    val vm: EntryFormViewModel = hiltViewModel()
                                    LaunchedEffect(route) {
                                        if (posting) vm.openOccurrence(RecurringId(parts[2]), LocalDate.parse(parts[3]))
                                        else vm.openSchedule(parts[2].takeIf(String::isNotBlank)?.let(::RecurringId))
                                    }
                                    val form by vm.state.collectAsStateWithLifecycle()
                                    val status by vm.status.collectAsStateWithLifecycle()
                                    EntryFormScreen(vm, form, status, hidden,
                                        close = { vm.finish(); stack = stack.dropLast(1) },
                                        saved = { savedId ->
                                            vm.finish()
                                            val target = if (posting) "entry:$savedId" else "schedule:$savedId"
                                            stack = stack.dropLast(1)
                                            if (stack.lastOrNull() != target) stack = stack + target
                                        },
                                        createGroup = { members -> stack = stack + formRoute("group", ":" + members.joinToString(",") { it.value }) },
                                        scheduleForm = !posting, posting = posting, onScheduleSaved = onScheduleSaved)
                                }
                                "group" -> {
                                    val vm: GroupDetailViewModel = hiltViewModel()
                                    LaunchedEffect(id) { vm.open(id) }
                                    val detail by vm.state.collectAsStateWithLifecycle()
                                    GroupDetailScreen(detail, hidden, vm::retry, openPerson, openEntry, onEdit = { stack = stack + formRoute("group", id) }, request = advanced::request,
                                        onMembers = { stack = stack + "members:$id" }, onRecordPlan = advanced::preparePlan)
                                }
                                "entry" -> {
                                    val vm: EntryDetailViewModel = hiltViewModel()
                                    LaunchedEffect(id) { vm.open(id) }
                                    val detail by vm.state.collectAsStateWithLifecycle()
                                    EntryDetailScreen(detail, hidden, vm::retry, openPerson, openGroup, onEdit = { stack = stack + formRoute("entryedit", id) }, request = advanced::request, openSchedule = openSchedule)
                                }
                                "person" -> {
                                    val vm: PersonDetailViewModel = hiltViewModel()
                                    LaunchedEffect(id) { vm.open(id) }
                                    val detail by vm.state.collectAsStateWithLifecycle()
                                    PersonDetailScreen(detail, hidden, vm::retry, openGroup, openEntry,
                                        onEdit = { stack = stack + formRoute("person", id) },
                                        onAddHandle = { stack = stack + formRoute("handle", id) },
                                        onEditHandle = { handle -> stack = stack + formRoute("handle", "$id:$handle") },
                                        request = advanced::request, onMerge = { stack = stack + formRoute("merge", id) }, onRecordPlan = advanced::preparePlan)
                                }
                            }
                          }
                        } else when (selected) {
                            Destination.Overview -> OverviewScreen(state, hidden,
                                onNavigate = { selectedName = it.name }, onFeature = { feature -> when (feature) { addPersonLabel -> addPerson(); createGroupLabel -> addGroup(); addExpenseLabel -> addEntry("expense"); else -> if (feature == backupControlsLabel) stack = stack + formRoute("data", "backup") else unfinished = feature } }, onRetry = viewModel::retry,
                                onPerson = openPerson, onGroup = openGroup, onEntry = openEntry)
                            Destination.Groups -> {
                                val vm: GroupsViewModel = hiltViewModel()
                                val groups by vm.state.collectAsStateWithLifecycle()
                                GroupsScreen(groups, hidden, vm::retry, openGroup, onAdd = addGroup)
                            }
                            Destination.Activity -> {
                                val vm: ActivityViewModel = hiltViewModel()
                                val activity by vm.state.collectAsStateWithLifecycle()
                                ActivityScreen(activity, hidden, vm::retry, vm::filter, openEntry, openSchedule)
                            }
                            Destination.People -> {
                                val vm: PeopleViewModel = hiltViewModel()
                                val people by vm.state.collectAsStateWithLifecycle()
                                PeopleScreen(people, hidden, vm::retry, openPerson, onAdd = addPerson)
                            }
                            Destination.Settlements -> {
                                val vm: SettlementsViewModel = hiltViewModel()
                                val settlements by vm.state.collectAsStateWithLifecycle()
                                SettlementsScreen(settlements, hidden, vm::retry, advanced::preparePersonal, advanced::preparePlan, openGroup)
                            }
                        }
                    }
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
                Triple("repayment", "Record repayment", IouIcons.Settle), Triple("transfer", "Record transfer", IouIcons.UpRight),
                Triple("schedule", "New recurring schedule", IouIcons.Recurring)).forEach { (purpose, label, icon) ->
                TextButton(onClick = { addMenu = false; if (purpose == "schedule") stack = stack + formRoute("schedule") else addEntry(purpose) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f)); Icon(IouIcons.Chevron, null)
                }
            }
        }
    }
    AdvancedDialog(advanced, advancedState, hidden)
    if (restoreInterrupted) AlertDialog(
        onDismissRequest = {},
        icon = { Icon(IouIcons.Info, null) },
        title = { Text("Restore interrupted") },
        text = { Text("The last restore was interrupted. Nothing changed.") },
        confirmButton = { TextButton(onClick = settingsModel::acknowledgeRestoreInterrupted) { Text(stringResource(R.string.done)) } },
    )
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
