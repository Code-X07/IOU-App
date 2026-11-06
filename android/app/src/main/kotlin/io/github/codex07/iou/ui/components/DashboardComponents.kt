package io.github.codex07.iou.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.R
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.ui.format.MoneyFormatter
import io.github.codex07.iou.ui.theme.MoneyFeatures
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun IouCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}

@Composable
fun MoneyText(money: Money, hidden: Boolean, modifier: Modifier = Modifier,
              magnitude: Boolean = false, signed: Boolean = false,
              color: Color = MaterialTheme.colorScheme.onSurface,
              style: TextStyle = MaterialTheme.typography.titleMedium) {
    val description = stringResource(R.string.amount_hidden)
    Text(MoneyFormatter.format(money, hidden, magnitude, signed),
        modifier = if (hidden) modifier.clearAndSetSemantics { contentDescription = description } else modifier,
        color = color, style = style.merge(MoneyFeatures),
        textAlign = TextAlign.End)
}

@Composable
fun MoneyLines(amounts: List<Money>, hidden: Boolean, modifier: Modifier = Modifier,
               magnitude: Boolean = false, signed: Boolean = false,
               color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (amounts.isEmpty()) Text(stringResource(R.string.all_settled),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else amounts.forEach { MoneyText(it, hidden, magnitude = magnitude, signed = signed, color = color) }
    }
}

@Composable
fun SectionHeader(title: String, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAll) {
            Text(stringResource(R.string.see_all))
            Icon(IouIcons.Chevron, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = when (Math.floorMod(name.hashCode(), 3)) {
        0 -> colors.primaryContainer; 1 -> colors.secondaryContainer; else -> colors.error.copy(alpha = .12f)
    }
    val initials = androidx.compose.runtime.remember(name) { name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" } }
    Box(modifier.size(42.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = colors.onSurface, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
fun Notice(title: String, body: String, icon: ImageVector = IouIcons.Info,
           action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.secondaryContainer.copy(alpha = colors.secondaryContainer.alpha * .6f)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                if (action != null) TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String, icon: ImageVector,
               onAction: () -> Unit, modifier: Modifier = Modifier) {
    IouCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun balanceLabel(amounts: List<Money>, group: Boolean = false): String = when {
    amounts.isEmpty() || amounts.all { it.amountMinor == 0L } -> stringResource(R.string.all_settled)
    amounts.all { it.amountMinor > 0L } -> stringResource(if (group) R.string.you_get_back else R.string.owes_you)
    amounts.all { it.amountMinor < 0L } -> stringResource(if (group) R.string.you_owe_group else R.string.you_owe_person)
    else -> stringResource(R.string.mixed_currencies)
}

@Composable
private fun FinancialPreviewRow(onClick: () -> Unit, leading: @Composable () -> Unit,
                                main: @Composable () -> Unit, amounts: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 76.dp).iouClickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        val stacked = io.github.codex07.iou.ui.theme.AdaptiveLayout.stackFinancialRow(maxWidth.value, androidx.compose.ui.platform.LocalDensity.current.fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { main() }
                if (!stacked) Column(Modifier.weight(.9f), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) { amounts() }
                Icon(IouIcons.Chevron, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stacked) Column(Modifier.fillMaxWidth().padding(start = 54.dp), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)) { amounts() }
        }
    }
}

@Composable
fun PersonPreview(person: PersonSummary, hidden: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = balanceLabel(person.balances)
    FinancialPreviewRow(onClick, leading = { Avatar(person.person.displayName) }, main = {
        Text(person.person.displayName, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (person.archived) stringResource(R.string.archived_status, status) else status,
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }, amounts = {
        if (person.balances.isEmpty()) Text(stringResource(R.string.all_settled), style = MaterialTheme.typography.bodySmall)
        person.balances.forEach { money ->
            MoneyText(money, hidden, magnitude = true, color = if (money.amountMinor < 0) colors.error else colors.primary)
            if (person.balances.size > 1) Text(stringResource(when {
                money.amountMinor > 0 -> R.string.owes_you
                money.amountMinor < 0 -> R.string.you_owe_person
                else -> R.string.all_settled
            }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    })
}

@Composable
fun GroupPreview(group: GroupSummary, hidden: Boolean, onClick: () -> Unit,
                 modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    IouCard(modifier.clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(colors.secondaryContainer, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center) { Icon(IouIcons.Home, null, tint = colors.secondary) }
                Column(Modifier.weight(1f)) {
                    Text(group.group.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.members_currency, group.activeMemberCount, group.activeMemberCount,
                        if (group.fxMode == FxMode.CONVERT) stringResource(R.string.converted_to, group.defaultCurrency.code)
                        else stringResource(R.string.original_currencies)), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Text(balanceLabel(group.myNets, group = true), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            MoneyLines(group.myNets, hidden, signed = true,
                color = if (group.myNets.all { it.amountMinor >= 0 }) colors.primary else colors.error)
            if (group.archived) Text(stringResource(R.string.archived_status,
                balanceLabel(group.myNets, true)), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun EntryPreview(item: ActivityItem, hidden: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = item.description ?: stringResource(when (item.transferKind) {
        TransferKind.LOAN -> R.string.loan_title
        TransferKind.REPAYMENT -> R.string.repayment_title
        null -> R.string.transfer_title
    })
    val payer = if (item.paidBy.isMe) stringResource(R.string.you) else item.paidBy.displayName
    FinancialPreviewRow(onClick, leading = {
        Box(Modifier.size(40.dp).background(colors.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Icon(when {
                item.kind == io.github.codex07.iou.api.EntryKind.EXPENSE -> IouIcons.Activity
                item.transferKind == TransferKind.LOAN -> IouIcons.People
                item.transferKind == TransferKind.REPAYMENT -> IouIcons.Settle
                else -> IouIcons.UpRight
            }, null, tint = colors.primary)
        }
    }, main = {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(when {
            item.kind == io.github.codex07.iou.api.EntryKind.EXPENSE -> "Shared expense"
            item.transferKind == TransferKind.LOAN -> "Personal loan"
            item.transferKind == TransferKind.REPAYMENT -> "Recorded repayment"
            else -> "Transfer"
        }, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        Text(stringResource(R.string.paid_context, payer, item.group?.name ?: stringResource(R.string.personal_context)),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        item.receiver?.let { Text("To ${it.displayName}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        Text(item.occurredOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }, amounts = {
        Text("Paid amount", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        MoneyText(item.paid, hidden)
        item.myBalanceEffect?.takeIf { it.amountMinor != 0L }?.let {
            Text("Your position", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            MoneyText(it, hidden, signed = true, color = if (it.amountMinor > 0) colors.primary else colors.error)
        }
    })
}
