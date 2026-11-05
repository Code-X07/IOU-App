package io.github.codex07.iou.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One compact chip row, native scrolling semantics and a discoverable 48dp scroll control. */
@Composable
fun FilterStrip(label: String, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val distance = with(LocalDensity.current) { 200.dp.toPx() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).clipToBounds().semantics { contentDescription = label }, state = state,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
        if (state.canScrollForward || state.canScrollBackward) {
            IconButton(onClick = { scope.launch {
                state.animateScrollBy(if (state.canScrollForward) distance else -distance)
            } }, modifier = Modifier.size(48.dp)) {
                Icon(if (state.canScrollForward) IouIcons.Chevron else IouIcons.Back,
                    if (state.canScrollForward) "Show more filters" else "Show previous filters")
            }
        }
    }
}
