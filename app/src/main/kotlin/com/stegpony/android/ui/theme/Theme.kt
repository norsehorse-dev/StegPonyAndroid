package com.stegpony.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ink indigo on charcoal, which nothing else in the family uses (planning Q10,
// still open). Not dynamic color, so the app keeps its own look.
private val Indigo40 = Color(0xFF4A4C9C)
private val Indigo80 = Color(0xFFC0C1FF)
private val Violet40 = Color(0xFF675A75)
private val Violet80 = Color(0xFFD3C0E1)
private val Slate40 = Color(0xFF4F5F6F)
private val Slate80 = Color(0xFFB7C8DA)

private val LightColors = lightColorScheme(
    primary = Indigo40,
    secondary = Violet40,
    tertiary = Slate40,
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFBFAFF),
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    secondary = Violet80,
    tertiary = Slate80,
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
)

/** Colors for the three detectability ratings. */
object RatingColors {
    val low = Color(0xFF2E7D32)
    val medium = Color(0xFFB26A00)
    val lowDark = Color(0xFF81C784)
    val mediumDark = Color(0xFFFFB74D)
}

@Composable
fun StegPonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
