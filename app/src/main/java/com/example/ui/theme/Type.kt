package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()
private val Sans = FontFamily.SansSerif

/** Crisp sans-serif type: tighter tracking, medium-weight titles. */
val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Sans),
    displayMedium = base.displayMedium.copy(fontFamily = Sans),
    displaySmall = base.displaySmall.copy(fontFamily = Sans),
    headlineLarge = base.headlineLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontFamily = Sans, letterSpacing = 0.sp, lineHeight = 25.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Sans, letterSpacing = 0.sp, lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Sans, letterSpacing = 0.1.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold)
)
