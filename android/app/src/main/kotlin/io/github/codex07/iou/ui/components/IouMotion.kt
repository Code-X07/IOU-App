package io.github.codex07.iou.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import io.github.codex07.iou.ui.navigation.NavigationScene
import io.github.codex07.iou.ui.navigation.PageMotion
import kotlin.coroutines.coroutineContext

object IouMotion {
    const val press = 120
    const val expand = 230
    const val placement = 210
}

/** One live page, one SaveableStateProvider. No outgoing copy of financial content or form effects. */
@Composable
fun PageEntrance(scene: NavigationScene, content: @Composable () -> Unit) {
    var previous by remember { mutableStateOf<NavigationScene?>(null) }
    val motion = remember(scene.key) { PageMotion.between(previous, scene) }
    val progress = remember(scene.key) { Animatable(if (motion == PageMotion.NONE) 1f else 0f) }
    SideEffect { previous = scene }
    LaunchedEffect(scene.key) {
        if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f || motion == PageMotion.NONE) progress.snapTo(1f)
        else progress.animateTo(1f, tween(motion.durationMillis, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = .82f + .18f * progress.value
        translationX = motion.offsetDp * density * (1f - progress.value)
        clip = true
    }) { content() }
}

/** A single native click target and ripple, with draw-phase press feedback. */
fun Modifier.iouClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .985f else 1f, if (pressed) tween(IouMotion.press) else spring(dampingRatio = 1f, stiffness = 700f), label = "Card press")
    this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled,
            role = Role.Button, onClick = onClick)
}
