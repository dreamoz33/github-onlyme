package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MidnightColorScheme = darkColorScheme(
    primary = HighContrastWhite,       // 활성화 상태를 표시하는 흰색 (White indicating active states!)
    secondary = DimSilver,             // silver sub-elements
    tertiary = CosmicSecondarySurface, // carbon gray overlays
    background = BlackBg,              // 검정색 배경 (Pure Black Background!)
    surface = DarkCardSurface,         // card surfaces in dark mode
    onBackground = MoonWhite,
    onSurface = MoonWhite,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    error = CoralError,
    // Add custom containers for active indicators
    primaryContainer = Color(0xFF2E2E33),
    onPrimaryContainer = HighContrastWhite,
    secondaryContainer = Color(0xFF1E1E22),
    onSecondaryContainer = MoonWhite
)

private val MorningColorScheme = lightColorScheme(
    primary = ModernRoyalBlue,          // royal blue (Matching the provided design screenshot)
    secondary = TextDarkSecondary,      // soft slate gray for details
    tertiary = CleanLightBg,            // soft lavender-gray background color
    background = CleanLightBg,          // soft lavender-gray background (Matching screenshot)
    surface = PureWhiteCard,            // pure white alarm card background
    onBackground = TextDarkPrimary,     // bold primary text
    onSurface = TextDarkPrimary,        // bold surface text
    onPrimary = Color.White,
    onSecondary = Color.White,
    error = CoralError,
    // Add containers for accents matching the screenshot
    primaryContainer = Color(0xFFE8E9FF), // light lavender/blue for pill shapes / active container
    onPrimaryContainer = ModernRoyalBlue,
    secondaryContainer = Color(0xFFF3F4F6),
    onSecondaryContainer = TextDarkPrimary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) MidnightColorScheme else MorningColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
