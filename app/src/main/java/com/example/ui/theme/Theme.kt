package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Aura is dark-only: graphite background, white text, colour only for states. */
private val GraphiteColorScheme = darkColorScheme(
    primary = TextPrimary,
    onPrimary = Graphite,
    primaryContainer = GraphiteHigh,
    onPrimaryContainer = TextPrimary,
    inversePrimary = Graphite,
    secondary = TextBody,
    onSecondary = Graphite,
    secondaryContainer = GraphiteHighest,
    onSecondaryContainer = TextPrimary,
    tertiary = StateWarn,
    onTertiary = Graphite,
    tertiaryContainer = StateWarnContainer,
    onTertiaryContainer = StateWarn,
    background = Graphite,
    onBackground = TextPrimary,
    surface = GraphiteRaised,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteHigh,
    onSurfaceVariant = TextMuted,
    surfaceTint = Graphite, // no tinted elevation
    inverseSurface = TextBody,
    inverseOnSurface = Graphite,
    error = StateError,
    onError = Graphite,
    errorContainer = StateErrorContainer,
    onErrorContainer = StateError,
    outline = GraphiteLine,
    outlineVariant = GraphiteLineSoft,
    surfaceBright = GraphiteHighest,
    surfaceDim = Graphite,
    surfaceContainerLowest = Graphite,
    surfaceContainerLow = GraphiteRaised,
    surfaceContainer = GraphiteRaised,
    surfaceContainerHigh = GraphiteHigh,
    surfaceContainerHighest = GraphiteHighest
)

@Composable
fun AuraTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true, // kept for call-site compatibility; Aura is dark-only
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GraphiteColorScheme,
        typography = Typography,
        content = content
    )
}
