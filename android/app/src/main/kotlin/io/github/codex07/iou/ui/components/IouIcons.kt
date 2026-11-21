package io.github.codex07.iou.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small local vector set: no icon font, web renderer or extra icon dependency. */
object IouIcons {
    private fun icon(name: String, data: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(data).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val Overview = icon("Overview", "M3 3H10V12H3ZM14 3H21V8H14ZM14 12H21V21H14ZM3 16H10V21H3Z")
    val People = icon("People", "M15 7A3 3 0 1 1 9 7A3 3 0 1 1 15 7M5 21V18C5 13 19 13 19 18V21M18 4C23 4 23 10 19 10M21 14C23 15 23 18 23 20")
    val Groups = icon("Groups", "M14.5 6A2.5 2.5 0 1 1 9.5 6A2.5 2.5 0 1 1 14.5 6M8 21V17C8 13 16 13 16 17V21M6 10A2 2 0 1 1 2 10A2 2 0 1 1 6 10M1 21V18C1 15 5 14 7 16M22 10A2 2 0 1 1 18 10A2 2 0 1 1 22 10M17 16C19 14 23 15 23 18V21")
    val Activity = icon("Activity", "M5 3L7 4L9 3L11 4L13 3L15 4L17 3L19 4V21L17 20L15 21L13 20L11 21L9 20L7 21L5 20ZM8 8H16M8 12H16M8 16H13")
    val Recurring = icon("Recurring expenses", "M5 3H19V21H5ZM5 8H19M8 2V5M16 2V5M15 13A3 3 0 1 0 15 17M15 13V11M15 13H13")
    val Settle = icon("Settle up", "M3 7H17M14 4L17 7L14 10M21 17H7M10 14L7 17L10 20M3 12V17M21 7V12")
    val Add = icon("Add", "M12 5V19M5 12H19")
    val Eye = icon("Visibility", "M2 12C7 4 17 4 22 12C17 20 7 20 2 12M15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12")
    val EyeOff = icon("Hidden", "M3 3L21 21M6 6C4 8 3 10 2 12C7 20 17 20 22 12C20 9 18 7 15 6M10 9C7 12 11 17 15 14")
    val Chevron = icon("Open", "M9 5L16 12L9 19")
    val Back = icon("Back", "M15 5L8 12L15 19")
    val DownLeft = icon("Owed to you", "M18 6L6 18M6 7V18H17")
    val UpRight = icon("You owe", "M6 18L18 6M7 6H18V17")
    val Shield = icon("Private", "M12 2L20 6V12C20 17 16 20 12 22C8 20 4 17 4 12V6ZM8 12L11 15L16 9")
    val Backup = icon("Backup", "M5 19H18C24 18 22 10 18 10C18 2 6 2 5 10C0 10 0 18 5 19M12 16V9M9 12L12 9L15 12")
    val Home = icon("Group", "M3 11L12 3L21 11M5 10V21H19V10M10 21V15H14V21")
    val Info = icon("Information", "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12M12 11V17M12 7V7.1")
    val Search = icon("Search", "M16 10A6 6 0 1 1 4 10A6 6 0 1 1 16 10M15 15L21 21")
    val Close = icon("Close", "M6 6L18 18M6 18L18 6")
}
