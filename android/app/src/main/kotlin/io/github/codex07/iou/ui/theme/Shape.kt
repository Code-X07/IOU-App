package io.github.codex07.iou.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val IouShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
object IouSpacing {
    val small = 8.dp
    val item = 12.dp
    val card = 16.dp
    val page = 20.dp
    val section = 24.dp
    val large = 32.dp
}
