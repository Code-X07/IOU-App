package io.github.codex07.iou.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = DarkPrimary, onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimary.copy(alpha = .13f), onPrimaryContainer = DarkPrimary,
    secondary = DarkSecondary, onSecondary = DarkBackground,
    secondaryContainer = DarkSecondary.copy(alpha = .13f), onSecondaryContainer = DarkSecondary,
    tertiary = DarkNegative, onTertiary = DarkBackground,
    error = DarkNegative, onError = DarkBackground,
    background = DarkBackground, onBackground = DarkOnBackground,
    surface = DarkSurface, onSurface = DarkOnBackground,
    surfaceVariant = DarkSurface2, onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground, surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface, surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    outline = DarkOutline, outlineVariant = DarkOnBackground.copy(alpha = .08f),
    scrim = Color(0xFF04080A),
)
private val LightColors = lightColorScheme(
    primary = LightPrimary, onPrimary = LightSurface,
    primaryContainer = LightPrimary.copy(alpha = .11f), onPrimaryContainer = LightPrimary,
    secondary = LightSecondary, onSecondary = LightSurface,
    secondaryContainer = LightSecondary.copy(alpha = .12f), onSecondaryContainer = LightSecondary,
    tertiary = LightNegative, onTertiary = LightSurface,
    error = LightNegative, onError = LightSurface,
    background = LightBackground, onBackground = LightOnBackground,
    surface = LightSurface, onSurface = LightOnBackground,
    surfaceVariant = LightSurface2, onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface, surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurface, surfaceContainerHigh = LightSurface2,
    surfaceContainerHighest = LightSurface3,
    outline = LightOutline, outlineVariant = LightOnBackground.copy(alpha = .09f),
)

data class IouExtraColors(
    val heroStart: Color, val heroMiddle: Color, val heroEnd: Color,
    val heroText: Color, val heroMuted: Color, val warning: Color,
)
private val DarkExtras = IouExtraColors(Color(0xFF1B5243), Color(0xFF183A33), DarkSurface,
    DarkOnBackground, Color(0xFFB7C6C2), DarkWarning)
private val LightExtras = IouExtraColors(LightPrimary, Color(0xFF11654B), Color(0xFF0E5640),
    LightBackground, LightBackground.copy(alpha = .78f), LightWarning)
val LocalIouColors = staticCompositionLocalOf { DarkExtras }

@Composable
fun IOUTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIouColors provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = IouTypography,
            shapes = IouShapes,
            content = content,
        )
    }
}
