package com.flygonlc.onebattery.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.flygonlc.onebattery.MonitorSettings
import com.flygonlc.onebattery.R

/**
 * The four Flygon LC house styles, from the shared style catalog
 * (Flygon LC Redesign/src/generated/styleCatalog.js). paper/surface/ink/muted/accent/
 * onAccent/line and radius are exact; good/bad/warn/info are status colours the catalog
 * doesn't define, chosen per style for contrast.
 */
class AppTheme(
    val id: String,
    val name: String,
    val description: String,
    val dark: Boolean,
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val line: Color,
    val good: Color,
    val bad: Color,
    val warn: Color,
    val info: Color,
    val heading: FontFamily,
    val body: FontFamily,
    val radius: Dp,
    val chipRadius: Dp,
    /** Card outline width; 0 for none. */
    val border: Dp,
    /** Offset of a hard ink shadow behind cards (Flygon LC's pixel look); 0 for none. */
    val hardShadow: Dp,
    /** Wide display faces (pixel type) need smaller sizes to fit the same headings. */
    val headingScale: Float = 1f,
) {
    val cardHi = lerp(surface, ink, if (dark) 0.07f else 0.05f)
    val grid = lerp(surface, line, if (dark) 0.6f else 0.45f)
    val band = good.copy(alpha = 0.18f)
}

private val PressStart = FontFamily(Font(R.font.press_start_2p))
private val SourceSans = FontFamily(
    Font(R.font.source_sans_regular, FontWeight.Normal),
    Font(R.font.source_sans_semibold, FontWeight.SemiBold),
    Font(R.font.source_sans_semibold, FontWeight.Bold),
)
private val InstrumentSerif = FontFamily(Font(R.font.instrument_serif))
private val InstrumentSans = FontFamily(Font(R.font.instrument_sans))

object Themes {
    val FlygonLC = AppTheme(
        id = "flygon", name = "Flygon LC", description = "Pixel character. Warm green. A little red, used deliberately.",
        dark = false,
        paper = Color(0xFF7CB980), surface = Color(0xFFF4F7EE), ink = Color(0xFF263A28), muted = Color(0xFF263A28),
        accent = Color(0xFFA8524C), onAccent = Color.White, line = Color(0xFF324B34),
        good = Color(0xFF2F6B34), bad = Color(0xFFA8524C), warn = Color(0xFF8A5A00), info = Color(0xFF2B5C8A),
        heading = PressStart, body = FontFamily.SansSerif,
        radius = 4.dp, chipRadius = 4.dp, border = 2.dp, hardShadow = 3.dp, headingScale = 0.62f,
    )
    val Othello = AppTheme(
        id = "othello", name = "Othello", description = "Quiet Harbor blue, warm Hearth neutrals, and spacious editorial type.",
        dark = false,
        paper = Color(0xFFF3F7FA), surface = Color(0xFFE5EEF4), ink = Color(0xFF293E50), muted = Color(0xFF365874),
        accent = Color(0xFF365874), onAccent = Color.White, line = Color(0xFFA7C4D8),
        good = Color(0xFF2F6F5A), bad = Color(0xFFA3433A), warn = Color(0xFF8F6110), info = Color(0xFF5D89AB),
        heading = SourceSans, body = SourceSans,
        radius = 24.dp, chipRadius = 999.dp, border = 0.dp, hardShadow = 0.dp,
    )
    val SystemBoring = AppTheme(
        id = "system", name = "System Boring", description = "Paper and forest green. Quiet, legible, and dependable.",
        dark = false,
        paper = Color(0xFFF7F7F4), surface = Color(0xFFFFFFFF), ink = Color(0xFF181B19), muted = Color(0xFF606662),
        accent = Color(0xFF155747), onAccent = Color.White, line = Color(0xFFD9DDDA),
        good = Color(0xFF155747), bad = Color(0xFFA33A2B), warn = Color(0xFF8F5D0A), info = Color(0xFF2E5E86),
        heading = InstrumentSerif, body = InstrumentSans,
        radius = 3.dp, chipRadius = 3.dp, border = 1.dp, hardShadow = 0.dp,
    )
    val Night = AppTheme(
        id = "night", name = "Night", description = "Dark and restrained, with comfortable touch targets.",
        dark = true,
        paper = Color(0xFF151719), surface = Color(0xFF222529), ink = Color(0xFFF1F3F4), muted = Color(0xFFBBC1C7),
        accent = Color(0xFFB7D9AE), onAccent = Color(0xFF172414), line = Color(0xFF42474D),
        good = Color(0xFFB7D9AE), bad = Color(0xFFFF8A80), warn = Color(0xFFF2CC74), info = Color(0xFF9CC3F5),
        heading = FontFamily.Default, body = FontFamily.Default,
        radius = 16.dp, chipRadius = 8.dp, border = 0.dp, hardShadow = 0.dp,
    )

    val all = listOf(FlygonLC, Othello, SystemBoring, Night)

    private const val KEY = "theme"

    fun load(c: Context) {
        val id = c.getSharedPreferences(MonitorSettings.PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        // Default: Night in system dark mode, otherwise System Boring.
        val systemDark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = all.firstOrNull { it.id == id } ?: if (systemDark) Night else SystemBoring
        Palette.theme = theme
        // No-op when the matching alias is already the enabled one.
        LauncherIcon.apply(c, theme.id)
    }

    fun select(c: Context, theme: AppTheme) {
        Palette.theme = theme
        c.getSharedPreferences(MonitorSettings.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.id).apply()
        LauncherIcon.apply(c, theme.id)
    }
}

/**
 * Colour roles used across the UI. Backed by observable state, so every composable
 * that reads a role recomposes when the theme changes.
 */
object Palette {
    var theme by mutableStateOf(Themes.Night)

    val bg get() = theme.paper
    val card get() = theme.surface
    val cardHi get() = theme.cardHi
    /** Positive / healthy / charging. */
    val green get() = theme.good
    val accent get() = theme.accent
    val onAccent get() = theme.onAccent
    val red get() = theme.bad
    val amber get() = theme.warn
    val blue get() = theme.info
    val text get() = theme.ink
    val muted get() = theme.muted
    val grid get() = theme.grid
    val band get() = theme.band
    val heading get() = theme.heading
    val radius get() = theme.radius
    val chipShape get() = RoundedCornerShape(theme.chipRadius)

    fun headingSize(base: TextUnit) = base * theme.headingScale

    fun forLevel(level: Int) = when {
        level <= 15 -> red
        level <= 35 -> amber
        else -> green
    }

    fun forHealth(pct: Int) = when {
        pct >= 90 -> green
        pct >= 80 -> amber
        else -> red
    }
}

@Composable
fun OneBatteryTheme(content: @Composable () -> Unit) {
    val t = Palette.theme
    val scheme = if (t.dark) {
        darkColorScheme(
            primary = t.accent, onPrimary = t.onAccent, secondary = t.bad,
            background = t.paper, surface = t.surface, onSurface = t.ink, onBackground = t.ink, surfaceVariant = t.cardHi,
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = t.onAccent, secondary = t.bad,
            background = t.paper, surface = t.surface, onSurface = t.ink, onBackground = t.ink, surfaceVariant = t.cardHi,
        )
    }
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = t.body),
        displayMedium = base.displayMedium.copy(fontFamily = t.body),
        displaySmall = base.displaySmall.copy(fontFamily = t.body),
        headlineLarge = base.headlineLarge.copy(fontFamily = t.body),
        headlineMedium = base.headlineMedium.copy(fontFamily = t.body),
        headlineSmall = base.headlineSmall.copy(fontFamily = t.body),
        titleLarge = base.titleLarge.copy(fontFamily = t.body),
        titleMedium = base.titleMedium.copy(fontFamily = t.body),
        titleSmall = base.titleSmall.copy(fontFamily = t.body),
        bodyLarge = base.bodyLarge.copy(fontFamily = t.body),
        bodyMedium = base.bodyMedium.copy(fontFamily = t.body),
        bodySmall = base.bodySmall.copy(fontFamily = t.body),
        labelLarge = base.labelLarge.copy(fontFamily = t.body),
        labelMedium = base.labelMedium.copy(fontFamily = t.body),
        labelSmall = base.labelSmall.copy(fontFamily = t.body),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
