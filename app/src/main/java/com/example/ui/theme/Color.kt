package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Aura "graphite" palette: dark gray with a faint grain, crisp white text,
// and colour reserved for states (amber = warning, soft red = failure).

val Graphite = Color(0xFF1E1E1E)          // app background (under the grain)
val GraphiteRaised = Color(0xFF242424)    // cards, sheets, nav bar
val GraphiteHigh = Color(0xFF2B2B2B)      // user bubbles, inputs, chips
val GraphiteHighest = Color(0xFF323232)
val GraphiteLine = Color(0xFF383838)      // hairlines and outlines
val GraphiteLineSoft = Color(0xFF2E2E2E)

val TextPrimary = Color(0xFFFFFFFF)       // headings, bold, controls
val TextBody = Color(0xFFE4E4E2)          // long reply text (softer than pure white)
val TextMuted = Color(0xFFA6A6A6)         // labels, timestamps (7:1 on Graphite)

val StateWarn = Color(0xFFE3B65E)
val StateWarnContainer = Color(0xFF3A3222)
val StateError = Color(0xFFF2897E)
val StateErrorContainer = Color(0xFF3D2523)
