package dev.androml.app

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2E9),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCDE8E3),
    tertiary = Color(0xFF45617A),
    background = Color(0xFFF5FAF8),
    surface = Color(0xFFF5FAF8),
    surfaceVariant = Color(0xFFDAE5E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5CC),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9CF2E9),
    secondary = Color(0xFFB1CCC7),
    secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFACCBE7),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF3F4947),
)

@Composable
fun AndroMLTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
