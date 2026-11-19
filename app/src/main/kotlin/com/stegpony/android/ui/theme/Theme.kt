package com.stegpony.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stegpony.android.R

// Magenta on plum-black. No other Pony app uses pink or magenta (the family
// already has violet, red, teal, blue, orange and gold), and it stays clear of
// the green, amber and red that the detectability ratings use. The ground and
// panels carry a little of the same hue. Every role is set, so no component
// falls back to Material's baseline purple. Not dynamic color: the palette is
// the look.
private val Magenta = Color(0xFFD63A8E)
private val MagentaDeep = Color(0xFFB0206F)

private val DarkColors = darkColorScheme(
    primary = Magenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1530),
    onPrimaryContainer = Color(0xFFFFD8EA),
    inversePrimary = MagentaDeep,
    secondary = Color(0xFFF58CC3),
    onSecondary = Color(0xFF3A0D27),
    secondaryContainer = Color(0xFF3A1530),
    onSecondaryContainer = Color(0xFFFFD8EA),
    tertiary = Color(0xFFC9BCD0),
    onTertiary = Color(0xFF2E2634),
    tertiaryContainer = Color(0xFF25202B),
    onTertiaryContainer = Color(0xFFEDE8EF),
    background = Color(0xFF0F0D12),
    onBackground = Color(0xFFEDE8EF),
    surface = Color(0xFF0F0D12),
    onSurface = Color(0xFFEDE8EF),
    surfaceVariant = Color(0xFF1E1A23),
    onSurfaceVariant = Color(0xFFADA4B5),
    surfaceTint = Magenta,
    inverseSurface = Color(0xFFEDE8EF),
    inverseOnSurface = Color(0xFF18151C),
    error = Color(0xFFFF7A59),
    onError = Color(0xFF3B1206),
    errorContainer = Color(0xFF5C2414),
    onErrorContainer = Color(0xFFFFDBD0),
    outline = Color(0xFF6E657A),
    outlineVariant = Color(0xFF2A2530),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2733),
    surfaceDim = Color(0xFF0F0D12),
    surfaceContainerLowest = Color(0xFF09080B),
    surfaceContainerLow = Color(0xFF18151C),
    surfaceContainer = Color(0xFF1E1A23),
    surfaceContainerHigh = Color(0xFF25202B),
    surfaceContainerHighest = Color(0xFF2C2733),
)

private val LightColors = lightColorScheme(
    primary = MagentaDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE0EE),
    onPrimaryContainer = Color(0xFF4A0D30),
    inversePrimary = Color(0xFFF58CC3),
    secondary = Color(0xFF8F2A62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE0EE),
    onSecondaryContainer = Color(0xFF4A0D30),
    tertiary = Color(0xFF5F5568),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE7EC),
    onTertiaryContainer = Color(0xFF1C181F),
    background = Color(0xFFFAF7F9),
    onBackground = Color(0xFF1C181F),
    surface = Color(0xFFFAF7F9),
    onSurface = Color(0xFF1C181F),
    surfaceVariant = Color(0xFFEDE7EC),
    onSurfaceVariant = Color(0xFF5C5364),
    surfaceTint = MagentaDeep,
    inverseSurface = Color(0xFF25202B),
    inverseOnSurface = Color(0xFFF3EEF2),
    error = Color(0xFFC0391B),
    onError = Color.White,
    errorContainer = Color(0xFFFFDBD0),
    onErrorContainer = Color(0xFF3B1206),
    outline = Color(0xFF8B8192),
    outlineVariant = Color(0xFFE4DCE3),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE6DFE5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF3EEF2),
    surfaceContainerHigh = Color(0xFFEDE7EC),
    surfaceContainerHighest = Color(0xFFE6DFE5),
)

// Two radii: 12dp for controls and panels, 16dp for dialogs and sheets.
private val StegShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Monospace for keys and fingerprints only. Everything else is the platform sans. */
val KeyMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** Colors for the three detectability ratings. */
object RatingColors {
    val low = Color(0xFF2E7D32)
    val medium = Color(0xFF9A5B00)
    val lowDark = Color(0xFF5FD38A)
    val mediumDark = Color(0xFFF0B24A)
}

@Composable
fun StegPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = StegShapes,
        content = content,
    )
}
