package io.github.codex07.iou.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.codex07.iou.R

private val RobotoFlex = FontFamily(
    Font(R.font.roboto_flex_regular, FontWeight.Normal),
    Font(R.font.roboto_flex_medium, FontWeight.Medium),
    Font(R.font.roboto_flex_semibold, FontWeight.SemiBold),
    Font(R.font.roboto_flex_bold, FontWeight.Bold),
)
private fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = RobotoFlex, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight,
)
val IouTypography = Typography(
    displaySmall = style(34, 40, FontWeight.SemiBold),
    headlineLarge = style(28, 34, FontWeight.SemiBold),
    headlineMedium = style(24, 30, FontWeight.SemiBold),
    headlineSmall = style(22, 28, FontWeight.SemiBold),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24), bodyMedium = style(15, 22), bodySmall = style(14, 20),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(13, 18, FontWeight.Medium), labelSmall = style(12, 17, FontWeight.Medium),
)
val MoneyFeatures = TextStyle(fontFeatureSettings = "tnum")
