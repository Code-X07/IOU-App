package io.github.codex07.iou.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    IouCard(Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun SettingsRow(title: String, subtitle: String?, icon: ImageVector, enabled: Boolean = true,
                destructive: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.iouClickable(enabled, onClick) else Modifier)
        .heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (destructive) colors.error else colors.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (destructive) colors.error else colors.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(IouIcons.Chevron, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
fun SettingsToggle(title: String, subtitle: String, icon: ImageVector, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Compact form variant: the whole labeled row is one switch target. */
@Composable
fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change)
        .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Critical summary stays visible; optional detail expands without retaining hidden content. */
@Composable
fun DisclosureNotice(title: String, summary: String, details: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(IouMotion.expand)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) { Text(if (expanded) "Less detail" else "Read limitations") }
            if (expanded) Text(details, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun HistoryToggle(title: String, expanded: Boolean, count: Int, toggle: () -> Unit) {
    SettingsRow(title, "$count revisions · ${if (expanded) "Hide history" else "View history"}", IouIcons.Activity,
        onClick = toggle, trailing = {
            Text(if (expanded) "Hide" else "Show", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" })
        })
}
