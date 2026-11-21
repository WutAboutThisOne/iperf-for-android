package local.net.iperf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF00658A), onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E7FF), onPrimaryContainer = Color(0xFF001E2D),
    secondary = Color(0xFF4E616D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5F3), onSecondaryContainer = Color(0xFF0A1E28),
    tertiary = Color(0xFF9A4600), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBC8), onTertiaryContainer = Color(0xFF321300),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF181C1F),
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF181C1F),
    surfaceVariant = Color(0xFFDDE3EA), onSurfaceVariant = Color(0xFF41484D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE5E8EC),
    surfaceContainerHighest = Color(0xFFDFE3E7),
    outline = Color(0xFF71787E), outlineVariant = Color(0xFFC1C7CE),
    inverseSurface = Color(0xFF2D3134), inverseOnSurface = Color(0xFFEEF1F5), inversePrimary = Color(0xFF7FD0FF),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7FD0FF), onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004C69), onPrimaryContainer = Color(0xFFC5E7FF),
    secondary = Color(0xFFB5C9D7), onSecondary = Color(0xFF20333E),
    secondaryContainer = Color(0xFF364955), onSecondaryContainer = Color(0xFFD1E5F3),
    tertiary = Color(0xFFFFB68B), onTertiary = Color(0xFF532200),
    tertiaryContainer = Color(0xFF763300), onTertiaryContainer = Color(0xFFFFDBC8),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101417), onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF101417), onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF41484D), onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceContainerLowest = Color(0xFF0B0F12), surfaceContainerLow = Color(0xFF181C1F),
    surfaceContainer = Color(0xFF1C2023), surfaceContainerHigh = Color(0xFF262A2E),
    surfaceContainerHighest = Color(0xFF313539),
    outline = Color(0xFF8B9298), outlineVariant = Color(0xFF41484D),
    inverseSurface = Color(0xFFDFE3E7), inverseOnSurface = Color(0xFF2D3134), inversePrimary = Color(0xFF00658A),
)

private val Base = Typography()
val AppTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Series colours: upload uses primary, download uses tertiary. */
object Series {
    @Composable fun up() = MaterialTheme.colorScheme.primary
    @Composable fun down() = MaterialTheme.colorScheme.tertiary
}

@Composable
fun IperfTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
