package io.github.kvtttba.droplet.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Droplet brand seed: calm deep ocean teal (#0B6E75)
internal val DropletLightColorScheme = lightColorScheme(
    primary = Color(0xFF006970),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCEFEF),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4E5E7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6E3FF),
    onTertiaryContainer = Color(0xFF081B37),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F9FA),
    onBackground = Color(0xFF161D1E),
    surface = Color(0xFFF5F9FA),
    onSurface = Color(0xFF161D1E),
    surfaceVariant = Color(0xFFDAE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBEC8CA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F6),
    surfaceContainer = Color(0xFFE9EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDDE4E5),
)

internal val DropletDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6ED5DD),
    onPrimary = Color(0xFF00373B),
    primaryContainer = Color(0xFF004F55),
    onPrimaryContainer = Color(0xFF9DF0F6),
    secondary = Color(0xFFB1CCCE),
    onSecondary = Color(0xFF1C3437),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Color(0xFFB6C7EB),
    onTertiary = Color(0xFF20304D),
    tertiaryContainer = Color(0xFF374765),
    onTertiaryContainer = Color(0xFFD6E3FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDDE4E5),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDDE4E5),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBEC8CA),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
    surfaceContainerLowest = Color(0xFF090F10),
    surfaceContainerLow = Color(0xFF161D1E),
    surfaceContainer = Color(0xFF1A2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF2F3637),
)

@Immutable
data class DropletSemanticColors(
    val income: Color,
    val incomeContainer: Color,
    val expense: Color,
    val expenseContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val cnyBar: Color,
    val usdBar: Color,
    val hkdBar: Color,
)

internal val DropletLightSemanticColors = DropletSemanticColors(
    income = Color(0xFF127A56),
    incomeContainer = Color(0xFFD8F3E7),
    expense = Color(0xFFC24038),
    expenseContainer = Color(0xFFFCE2E0),
    warning = Color(0xFFB36B00),
    warningContainer = Color(0xFFFFEBD0),
    cnyBar = Color(0xFF006970),
    usdBar = Color(0xFF4E5E7E),
    hkdBar = Color(0xFF8C6D46),
)

internal val DropletDarkSemanticColors = DropletSemanticColors(
    income = Color(0xFF63D9AC),
    incomeContainer = Color(0xFF123B2D),
    expense = Color(0xFFFF8C82),
    expenseContainer = Color(0xFF471F1D),
    warning = Color(0xFFFFC168),
    warningContainer = Color(0xFF432C08),
    cnyBar = Color(0xFF6ED5DD),
    usdBar = Color(0xFF9BB4E8),
    hkdBar = Color(0xFFD8B88C),
)

val LocalDropletSemanticColors = staticCompositionLocalOf { DropletLightSemanticColors }
