package com.thorctl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material 3 Expressive with the system (Monet) palette and expressive (springy) motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThorTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/**
 * Colors of the Android 17 shade: everything sits on a heavily blurred, tinted backdrop, so
 * containers are translucent darker layers rather than opaque cards.
 */
@Immutable
data class QsColors(
    val scrim: Color,
    val container: Color,
    val containerPressed: Color,
    val iconActive: Color,
    val onIconActive: Color,
    val iconInactive: Color,
    val onIconInactive: Color,
    val text: Color,
    val textSecondary: Color,
)

fun ColorScheme.qs(dark: Boolean): QsColors = QsColors(
    scrim = if (dark) Color(0xFF000000).copy(alpha = 0.40f) else Color(0xFFFFFFFF).copy(alpha = 0.35f),
    container = if (dark) surfaceContainerLowest.copy(alpha = 0.58f) else surfaceContainerLowest.copy(alpha = 0.62f),
    containerPressed = if (dark) surfaceContainerLow.copy(alpha = 0.75f) else surfaceContainerHigh.copy(alpha = 0.75f),
    iconActive = primary,
    onIconActive = onPrimary,
    iconInactive = surfaceContainerHighest.copy(alpha = 0.85f),
    onIconInactive = onSurface,
    text = onSurface,
    textSecondary = onSurfaceVariant,
)

@Composable
fun qsColors(): QsColors = MaterialTheme.colorScheme.qs(isSystemInDarkTheme())
