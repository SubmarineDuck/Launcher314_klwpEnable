package com.bearinmind.launcher314.ui.theme

import android.app.Activity
import android.app.WallpaperManager
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Bumped when the wallpaper-accent toggle flips so the theme recomposes immediately (issue #102). */
object ThemeAccentSignal {
    val state = mutableIntStateOf(0)
}

// Updated color schemes with white navigation bar
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9E9E9E),       // Medium Gray
    secondary = Color(0xFF757575),      // Darker Gray
    tertiary = Color(0xFF616161),       // Even Darker Gray
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A), // For dark mode
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5E5E5E),        // Dark Gray: selected values, thumbs, switches stay readable (issue #123)
    secondary = Color(0xFF6E6E6E),      // Gray section headers
    tertiary = Color(0xFF616161),       // Dark Gray
    background = Color(0xFFF5F5F5),     // Very Light Gray
    surface = Color(0xFFFFFFFF),        // White - THIS IS KEY FOR NAV BAR
    surfaceVariant = Color(0xFFFFFFFF), // White
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF212121),   // Very Dark Gray
    onSurface = Color(0xFF212121)       // Very Dark Gray
)

@Composable
fun Launcher314Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = launcherColorScheme(darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Launcher mode already set transparent bars (detected via the nav bar) — don't override them.
            val isLauncher = window.navigationBarColor == android.graphics.Color.TRANSPARENT

            if (!isLauncher) {
                window.statusBarColor = if (darkTheme) {
                    Color(0xFF121212).toArgb()
                } else {
                    Color.White.toArgb()
                }

                window.navigationBarColor = if (darkTheme) {
                    Color(0xFF2A2A2A).toArgb()
                } else {
                    Color.White.toArgb()
                }

                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/** Screens drawn dark in every mode (hardcoded #121212) keep dark-palette controls in light mode too (issue #123). */
@Composable
fun DarkScreenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = launcherColorScheme(darkTheme = true), content = content)
}

/** Dark status/nav bar icons for light screens; the edge-to-edge bars otherwise keep white icons (issue #123). */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(dark) {
        if (view.isInEditMode) return@LaunchedEffect
        var ctx = view.context
        while (ctx is android.content.ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}

/** App palette for [darkTheme], with the wallpaper accent when that option is on. */
@Composable
private fun launcherColorScheme(darkTheme: Boolean): ColorScheme {
    // Wallpaper accent (issue #102, experimental): Monet dynamic color on 12+ (Launcher3/Lawnchair path), WallpaperColors on 8.1-11, stock grey otherwise.
    ThemeAccentSignal.state.intValue
    val themeContext = LocalContext.current
    val wallpaperAccentOn = com.bearinmind.launcher314.data.getWallpaperAccentEnabled(themeContext)
    val accent: Color? = if (!wallpaperAccentOn) null else when {
        Build.VERSION.SDK_INT >= 31 -> {
            val dyn = if (darkTheme) dynamicDarkColorScheme(themeContext) else dynamicLightColorScheme(themeContext)
            dyn.primary
        }
        Build.VERSION.SDK_INT >= 27 -> {
            try {
                WallpaperManager.getInstance(themeContext)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor
                    ?.let { Color(it.toArgb()) }
                    // Lift too-dark wallpaper colors so the accent stays visible on the dark UI.
                    ?.let { c -> if (darkTheme && c.luminance() < 0.2f) androidx.compose.ui.graphics.lerp(c, Color.White, 0.45f) else c }
            } catch (_: Exception) { null }
        }
        else -> null
    }
    return when {
        darkTheme -> if (accent != null) DarkColorScheme.copy(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.5f) Color.Black else Color.White
        ) else DarkColorScheme
        else -> if (accent != null) LightColorScheme.copy(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.5f) Color.Black else Color.White
        ) else LightColorScheme
    }
}