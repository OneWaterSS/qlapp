package com.example.qlapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Blush = Color(0xFFE8547C)
val BlushLight = Color(0xFFF58AA6)
val BlushContainer = Color(0xFFFFDCE4)
val BlushDarkContainer = Color(0xFF5E2135)
val Ink = Color(0xFF33262B)
val Muted = Color(0xFF8E7C83)

private val LightColors = lightColorScheme(
    primary = Blush,
    onPrimary = Color.White,
    primaryContainer = BlushContainer,
    onPrimaryContainer = Color(0xFF5A1128),
    secondary = Color(0xFF8E5A6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3D1F29),
    surface = Color(0xFFFFF8FA),
    onSurface = Ink,
    background = Color(0xFFFFF8FA),
    onBackground = Ink,
    outline = Color(0xFFE3CBD3),
)

private val DarkColors = darkColorScheme(
    primary = BlushLight,
    onPrimary = Color(0xFF4A1122),
    primaryContainer = BlushDarkContainer,
    onPrimaryContainer = Color(0xFFFFDCE4),
    secondary = Color(0xFFE3B8C6),
    onSecondary = Color(0xFF3D1F29),
    surface = Color(0xFF1A1114),
    onSurface = Color(0xFFF6EDEF),
    background = Color(0xFF1A1114),
    onBackground = Color(0xFFF6EDEF),
    outline = Color(0xFF4A343B),
)

@Composable
fun QlappTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
