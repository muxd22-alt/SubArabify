package com.subarabify.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SubArabifyColorScheme = darkColorScheme(
    primary            = Gold500,
    onPrimary          = DarkBg,
    primaryContainer   = Gold700,
    onPrimaryContainer = Gold50,
    secondary          = InfoCyan,
    onSecondary        = DarkBg,
    secondaryContainer = DarkCardHigh,
    tertiary           = SuccessGreen,
    background         = DarkBg,
    onBackground       = TextPrimary,
    surface            = DarkSurface,
    onSurface          = TextPrimary,
    surfaceVariant     = DarkCard,
    onSurfaceVariant   = TextSecondary,
    outline            = DarkBorder,
    error              = ErrorRose,
    onError            = DarkBg,
)

@Composable
fun SubArabifyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBg.toArgb()
            window.navigationBarColor = DarkBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = SubArabifyColorScheme,
        typography = SubArabifyTypography,
        content = content,
    )
}
