package com.android.rcsclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RcsBluePrimary = Color(0xFF0B57D0)
val RcsBlueContainer = Color(0xFFD3E3FD)
val OnRcsBlueContainer = Color(0xFF041E49)

val E2eeGreen = Color(0xFF146C2E)
val E2eeGreenContainer = Color(0xFFC4EED0)

val RbmPurple = Color(0xFF6750A4)
val SmsSlate = Color(0xFF535F70)

private val LightColorScheme = lightColorScheme(
    primary = RcsBluePrimary,
    onPrimary = Color.White,
    primaryContainer = RcsBlueContainer,
    onPrimaryContainer = OnRcsBlueContainer,
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE5FF),
    onSecondaryContainer = Color(0xFF001D32),
    tertiary = E2eeGreen,
    tertiaryContainer = E2eeGreenContainer,
    background = Color(0xFFF8FAFD),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F8)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF96CCFF),
    onSecondary = Color(0xFF003353),
    secondaryContainer = Color(0xFF004A76),
    onSecondaryContainer = Color(0xFFCEE5FF),
    tertiary = Color(0xFF6DD58C),
    tertiaryContainer = Color(0xFF0F5223),
    background = Color(0xFF0E141B),
    surface = Color(0xFF151C24),
    surfaceVariant = Color(0xFF222B36)
)

@Composable
fun RcsClientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
