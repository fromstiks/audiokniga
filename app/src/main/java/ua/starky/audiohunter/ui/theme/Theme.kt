package ua.starky.audiohunter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Палитра неоморфизма. Ключевое отличие от обычной темы: помимо цвета поверхности
 * нужны ДВА цвета тени — светлый (highlight) и тёмный (shadow). Они и создают объём.
 */
@Immutable
data class NeuColors(
    val background: Color,
    val highlight: Color,
    val shadow: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val onAccent: Color,
    /** Цвет всего, что относится к офлайну: скачано, лежит на устройстве. */
    val offline: Color,
    val line: Color,
    val isDark: Boolean,
)

val LightNeu = NeuColors(
    background = Color(0xFFE7E3DA),
    highlight = Color(0xFFFCFAF6),
    shadow = Color(0xFFC0BAAC),
    ink = Color(0xFF2B2924),
    inkMuted = Color(0xFF726D62),
    inkFaint = Color(0xFF989285),
    accent = Color(0xFF2F7370),
    onAccent = Color(0xFFFFFFFF),
    offline = Color(0xFF8A6F3D),
    line = Color(0x1A2B2924),
    isDark = false,
)

val DarkNeu = NeuColors(
    background = Color(0xFF24272B),
    highlight = Color(0xFF2E3238),
    shadow = Color(0xFF15171A),
    ink = Color(0xFFE8EAEC),
    inkMuted = Color(0xFF9BA1A8),
    inkFaint = Color(0xFF71777E),
    accent = Color(0xFF4FBFB2),
    onAccent = Color(0xFF10201F),
    offline = Color(0xFFD6A75B),
    line = Color(0x1AE8EAEC),
    isDark = true,
)

val LocalNeuColors = staticCompositionLocalOf { LightNeu }

object Neu {
    val colors: NeuColors
        @Composable @ReadOnlyComposable get() = LocalNeuColors.current
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

/**
 * @param themeMode 0 — как в системе, 1 — светлая, 2 — тёмная.
 */
@Composable
fun AudioHunterTheme(themeMode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val neu = if (dark) DarkNeu else LightNeu
    val scheme = if (dark) {
        darkColorScheme(
            primary = neu.accent,
            onPrimary = neu.onAccent,
            background = neu.background,
            onBackground = neu.ink,
            surface = neu.background,
            onSurface = neu.ink,
        )
    } else {
        lightColorScheme(
            primary = neu.accent,
            onPrimary = neu.onAccent,
            background = neu.background,
            onBackground = neu.ink,
            surface = neu.background,
            onSurface = neu.ink,
        )
    }
    CompositionLocalProvider(LocalNeuColors provides neu) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
