package io.github.originalrecipe1.unfurlit.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF355F5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8ECE4),
    onPrimaryContainer = Color(0xFF00201D),
    onSurface = Color(0xFF191C1B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD0C8),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF1B4F4A),
    onPrimaryContainer = Color(0xFFB8ECE4),
    onSurface = Color(0xFFE0E3E1),
)

@Composable
fun UnfurlitTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val baseColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Keep personalized accents, with consistent, softer neutral surfaces.
    val colors = if (darkTheme) {
        baseColors.copy(
            background = Color(0xFF181818),
            onBackground = baseColors.onSurface,
            surface = Color(0xFF181818),
            surfaceDim = Color(0xFF181818),
            surfaceBright = Color(0xFF383838),
            surfaceContainerLowest = Color(0xFF141414),
            surfaceContainerLow = Color(0xFF202020),
            surfaceContainer = Color(0xFF242424),
            surfaceContainerHigh = Color(0xFF2B2B2B),
            surfaceContainerHighest = Color(0xFF353535),
        )
    } else {
        baseColors.copy(
            background = Color(0xFFFAF9F6),
            onBackground = baseColors.onSurface,
            surface = Color(0xFFFAF9F6),
            surfaceDim = Color(0xFFDEDDDA),
            surfaceBright = Color(0xFFFAF9F6),
            surfaceContainerLowest = Color(0xFFFDFCF9),
            surfaceContainerLow = Color(0xFFF5F4F1),
            surfaceContainer = Color(0xFFEFEEEB),
            surfaceContainerHigh = Color(0xFFE9E8E5),
            surfaceContainerHighest = Color(0xFFE3E2DF),
        )
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
