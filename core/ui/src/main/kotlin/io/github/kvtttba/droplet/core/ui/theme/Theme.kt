package io.github.kvtttba.droplet.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

@Composable
fun DropletTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DropletDarkColorScheme
        else -> DropletLightColorScheme
    }

    val semanticColors = if (darkTheme) DropletDarkSemanticColors else DropletLightSemanticColors

    CompositionLocalProvider(LocalDropletSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DropletTypography,
            content = content,
        )
    }
}

object DropletTheme {
    val semanticColors: DropletSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDropletSemanticColors.current
}
