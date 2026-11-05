package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.R
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.format.MoneyFormatter
import io.github.codex07.iou.ui.navigation.Destination
import io.github.codex07.iou.ui.theme.IouSpacing
import io.github.codex07.iou.ui.theme.LocalIouColors
import io.github.codex07.iou.ui.viewmodel.OverviewUiState
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun OverviewScreen(state: OverviewUiState, hidden: Boolean, onNavigate: (Destination) -> Unit,
                   onFeature: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
                   onPerson: (String) -> Unit = {}, onGroup: (String) -> Unit = {}, onEntry: (String) -> Unit = {}) {
    val addPerson = stringResource(R.string.add_person)
    val createGroup = stringResource(R.string.create_group)
    val addExpense = stringResource(R.string.add_expense)
    val backup = stringResource(R.string.backup_controls)
    when (state) {
        OverviewUiState.NeedsSetup -> Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            val setupTitle = stringResource(R.string.setup_required)
            EmptyState(setupTitle, stringResource(R.string.setup_required_body),
                stringResource(R.string.setup_action), IouIcons.People, { onFeature(setupTitle) })
        }
        OverviewUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.loading_dashboard))
            }
        }
        OverviewUiState.Failed -> Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            EmptyState(stringResource(R.string.load_failed), stringResource(R.string.load_failed_body),
                stringResource(R.string.retry), IouIcons.Info, onRetry)
        }
        is OverviewUiState.Ready -> LazyColumn(modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "greeting") {
                Column(Modifier.padding(horizontal = IouSpacing.page), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hour = LocalTime.now().hour
                    val greeting = stringResource(when {
                        hour in 5..11 -> R.string.good_morning
                        hour in 12..17 -> R.string.good_afternoon
                        else -> R.string.good_evening
                    })
                    val first = state.displayName.trim().substringBefore(' ')
                    Text(if (first.isEmpty()) greeting else stringResource(R.string.greeting_name, greeting, first),
                        style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "hero") {
                FinancialHero(state.overview, hidden, { onNavigate(Destination.Settlements) },
                    Modifier.padding(horizontal = IouSpacing.page))
            }
            item(key = "totals") {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = IouSpacing.page)) {
                    val vertical = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.25f
                    if (vertical) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BalanceCard(state.overview, true, hidden, { onNavigate(Destination.People) }, Modifier.fillMaxWidth())
                        BalanceCard(state.overview, false, hidden, { onNavigate(Destination.Settlements) }, Modifier.fillMaxWidth())
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BalanceCard(state.overview, true, hidden, { onNavigate(Destination.People) }, Modifier.weight(1f))
                        BalanceCard(state.overview, false, hidden, { onNavigate(Destination.Settlements) }, Modifier.weight(1f))
                    }
                }
            }
            if (state.hasArchivedOpenBalances) item(key = "archived") {
                Box(Modifier.padding(horizontal = IouSpacing.page)) {
                    Notice(stringResource(R.string.archive_notice), stringResource(R.string.archive_notice_body))
                }
            }
            if (state.backupReminder.due) item(key = "backup") {
                Box(Modifier.padding(horizontal = IouSpacing.page)) {
                    Notice(stringResource(R.string.backup_due), stringResource(R.string.backup_due_body),
                        IouIcons.Backup, backup) { onFeature(backup) }
                }
            }
            item(key = "people") {
                Column(Modifier.padding(horizontal = IouSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(stringResource(R.string.nav_people)) { onNavigate(Destination.People) }
                    if (state.people.isEmpty()) EmptyState(stringResource(R.string.no_people),
                        stringResource(R.string.no_people_description), addPerson, IouIcons.People, { onFeature(addPerson) })
                    else IouCard {
                        Column {
                            state.people.forEachIndexed { index, person ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                PersonPreview(person, hidden) {
                                    onPerson(person.person.id.value)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "groups-title") {
                Box(Modifier.padding(horizontal = IouSpacing.page)) {
                    SectionHeader(stringResource(R.string.nav_groups)) { onNavigate(Destination.Groups) }
                }
            }
            if (state.groups.isEmpty()) item(key = "groups-empty") {
                Box(Modifier.padding(horizontal = IouSpacing.page)) {
                    EmptyState(stringResource(R.string.no_groups), stringResource(R.string.no_groups_description),
                        createGroup, IouIcons.Groups, { onFeature(createGroup) })
                }
            } else item(key = "groups") {
                LazyRow(contentPadding = PaddingValues(horizontal = IouSpacing.page),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.groups, key = { it.group.id.value }) { group ->
                        GroupPreview(group, hidden,
                            { onGroup(group.group.id.value) }, Modifier.width(272.dp))
                    }
                }
            }
            item(key = "activity") {
                Column(Modifier.padding(horizontal = IouSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(stringResource(R.string.recent_activity)) { onNavigate(Destination.Activity) }
                    if (state.recent.isEmpty()) EmptyState(stringResource(R.string.no_activity),
                        stringResource(R.string.no_activity_description), addExpense, IouIcons.Activity, { onFeature(addExpense) })
                    else IouCard {
                        Column {
                            state.recent.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                EntryPreview(entry, hidden) { onEntry(entry.entryId.value) }
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(IouIcons.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.local_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FinancialHero(overview: Overview, hidden: Boolean, onSettle: () -> Unit, modifier: Modifier) {
    val extra = LocalIouColors.current
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent) {
        Box(Modifier.background(Brush.linearGradient(listOf(extra.heroStart, extra.heroMiddle, extra.heroEnd)))) {
            // Decoration is independent of the ledger's numeric values.
            Box(Modifier.align(Alignment.TopEnd).size(180.dp).background(Brush.radialGradient(
                listOf(Color(0xFF38D6A1).copy(alpha = .16f), Color.Transparent))))
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.financial_summary), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = extra.heroMuted)
                    Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = .1f)) {
                        Text(overview.homeCurrency.code, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = extra.heroText)
                    }
                }
                if (overview.owedToMe.isEmpty() && overview.iOwe.isEmpty()) {
                    Text(stringResource(R.string.all_settled), style = MaterialTheme.typography.displaySmall, color = extra.heroText)
                    Text(stringResource(R.string.settled_description), color = extra.heroMuted)
                } else {
                    HeroAmounts(stringResource(R.string.owed_to_you), overview.owedToMe,
                        overview.approxOwedToMe, overview.homeCurrency.code, hidden)
                    HorizontalDivider(color = extra.heroText.copy(alpha = .12f))
                    HeroAmounts(stringResource(R.string.you_owe), overview.iOwe,
                        overview.approxIOwe, overview.homeCurrency.code, hidden)
                }
                HorizontalDivider(color = extra.heroText.copy(alpha = .12f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onSettle, colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = extra.heroText, contentColor = extra.heroEnd)) {
                        Icon(IouIcons.Settle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.nav_settle))
                    }
                    Text(stringResource(R.string.separate_currencies), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = extra.heroMuted)
                }
            }
        }
    }
}

@Composable
private fun HeroAmounts(label: String, amounts: List<Money>, approximation: Money?, home: String, hidden: Boolean) {
    val extra = LocalIouColors.current
    val foreign = amounts.any { it.currency.code != home }
    Text(label, color = extra.heroMuted, style = MaterialTheme.typography.bodySmall)
    if (amounts.isEmpty()) Text(stringResource(R.string.all_settled), color = extra.heroText,
        style = MaterialTheme.typography.headlineMedium)
    else if (foreign && approximation != null) {
        MoneyText(approximation, hidden, color = extra.heroText, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.approximate_label, home), color = extra.heroMuted,
            style = MaterialTheme.typography.labelMedium)
    } else {
        amounts.forEach { money ->
            MoneyText(money, hidden, color = extra.heroText, style = MaterialTheme.typography.headlineMedium)
        }
        if (foreign) Text(stringResource(R.string.conversion_unavailable), color = extra.heroMuted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BalanceCard(overview: Overview, owed: Boolean, hidden: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val amounts = if (owed) overview.owedToMe else overview.iOwe
    val approximate = if (owed) overview.approxOwedToMe else overview.approxIOwe
    val tint = if (owed) colors.primary else colors.error
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (owed) IouIcons.DownLeft else IouIcons.UpRight, null,
                Modifier.size(28.dp).background(tint.copy(alpha = .1f), MaterialTheme.shapes.small).padding(4.dp), tint = tint)
            Text(stringResource(if (owed) R.string.owed_to_you else R.string.you_owe),
                style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            MoneyLines(amounts, hidden, Modifier.fillMaxWidth(), color = tint)
            if (amounts.any { it.currency != overview.homeCurrency }) {
                Text(if (approximate != null) stringResource(R.string.approximate_total,
                    MoneyFormatter.format(approximate, hidden)) else stringResource(R.string.conversion_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
