package ru.domru.technics.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ru.domru.technics.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = LightSurface,
    primaryContainer = ColorTokens.lightRedContainer,
    onPrimaryContainer = ColorTokens.lightRedText,
    secondary = ColorTokens.lightBlue,
    onSecondary = LightSurface,
    secondaryContainer = LightSurfaceSoft,
    onSecondaryContainer = LightText,
    tertiary = SuccessGreen,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceSoft,
    onSurfaceVariant = LightTextMuted,
    outline = LightOutline,
    error = BrandRed,
    onError = LightSurface,
)

private val DarkColors = darkColorScheme(
    primary = BrandRedDark,
    onPrimary = DarkBackground,
    primaryContainer = ColorTokens.darkRedContainer,
    onPrimaryContainer = ColorTokens.darkRedText,
    secondary = ColorTokens.darkBlue,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceSoft,
    onSecondaryContainer = DarkText,
    tertiary = ColorTokens.darkGreen,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceSoft,
    onSurfaceVariant = DarkTextMuted,
    outline = DarkOutline,
    error = BrandRedDark,
    onError = DarkBackground,
)

/** Цвета, которые используются только при сборке светлой и тёмной палитры. */
private object ColorTokens {
    val lightRedContainer = androidx.compose.ui.graphics.Color(0xFFFFE2E3)
    val lightRedText = androidx.compose.ui.graphics.Color(0xFF7B1118)
    val lightBlue = androidx.compose.ui.graphics.Color(0xFF247F9D)
    val darkRedContainer = androidx.compose.ui.graphics.Color(0xFF5C2025)
    val darkRedText = androidx.compose.ui.graphics.Color(0xFFFFD9DA)
    val darkBlue = androidx.compose.ui.graphics.Color(0xFF78C6E0)
    val darkGreen = androidx.compose.ui.graphics.Color(0xFF56D59B)
}

/** Выбирает палитру, настраивает системные панели и применяет общие шрифты. */
@Composable
fun DomRuTechnicsTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // Строка состояния прозрачная, а значки остаются заметными на любом фоне.
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = colors.background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
