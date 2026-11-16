package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DriftDarkColorScheme = darkColorScheme(
    primary = DriftEmerald,
    onPrimary = DriftObsidian,
    primaryContainer = DriftEmeraldContainer,
    onPrimaryContainer = DriftOnEmeraldContainer,
    secondary = DriftTeal,
    onSecondary = DriftObsidian,
    secondaryContainer = DriftTealContainer,
    onSecondaryContainer = DriftOnTealContainer,
    tertiary = DriftAmberSoft,
    onTertiary = DriftObsidian,
    background = DriftObsidian,
    onBackground = DriftTextPrimary,
    surface = DriftCharcoal,
    onSurface = DriftTextPrimary,
    surfaceVariant = DriftCharcoalElevated,
    onSurfaceVariant = DriftTextSecondary,
    surfaceContainerLowest = DriftObsidian,
    surfaceContainerLow = DriftCharcoal,
    surfaceContainer = DriftCharcoalElevated,
    surfaceContainerHigh = DriftCharcoalHighest,
    outline = DriftBorderSubtle,
    outlineVariant = DriftBorderSubtle.copy(alpha = 0.55f),
    error = DriftCoralMuted,
    onError = DriftObsidian
)

val DriftShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DriftTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DriftDarkColorScheme,
        typography = DriftTypography,
        shapes = DriftShapes,
        content = content
    )
}
