package com.kavach.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Kavach uses a fixed palette rather than Material You dynamic colour.
 *
 * Allowed and blocked must be instantly distinguishable at a glance, and a
 * wallpaper-derived accent could easily collide with the block colour. Correctness
 * of signal beats personalisation here.
 */
val ShieldGreen = Color(0xFF2E7D5B)
val ShieldGreenLight = Color(0xFF6FD3A5)
val BlockRed = Color(0xFFD1544F)
val BlockRedLight = Color(0xFFFFB4AB)
val WarnAmber = Color(0xFFE0A64B)
val InkDark = Color(0xFF0E1A14)
val InkSurface = Color(0xFF16241D)
val InkSurfaceHigh = Color(0xFF1F3129)

private val DarkColors = darkColorScheme(
    primary = ShieldGreenLight,
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF1B5E43),
    onPrimaryContainer = Color(0xFF8CF0BF),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1E352B),
    error = BlockRedLight,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = InkDark,
    onBackground = Color(0xFFE0E4E0),
    surface = InkDark,
    onSurface = Color(0xFFE0E4E0),
    surfaceVariant = InkSurface,
    onSurfaceVariant = Color(0xFFB9CCC1),
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    outline = Color(0xFF54655C),
)

private val LightColors = lightColorScheme(
    primary = ShieldGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2CB),
    onPrimaryContainer = Color(0xFF002013),
    secondary = Color(0xFF4E6357),
    onSecondary = Color.White,
    error = BlockRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
)

private val KavachTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun KavachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KavachTypography,
        content = content,
    )
}
