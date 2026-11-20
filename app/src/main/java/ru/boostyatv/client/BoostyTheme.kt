package ru.boostyatv.client

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
internal data class BoostyPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceFocused: Color,
    val railBackground: Color,
    val hairline: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentOnColor: Color,
    val focusRing: Color,
    val danger: Color,
    val scrim: Color,
    val badgeBg: Color,
)

internal object BoostyRadii {
    val Card = 14.dp
    val Control = 12.dp
    val Sheet = 20.dp
    val Pill = 999.dp
}

internal val BoostyDarkPalette = BoostyPalette(
    background = Color(0xFF0C0D12),
    surface = Color(0xFF151720),
    surfaceElevated = Color(0xFF1E212E),
    surfaceFocused = Color(0xFF272B3B),
    railBackground = Color(0xFF11131B),
    hairline = Color(0x1AFFFFFF),
    ink = Color(0xFFF6F4F9),
    muted = Color(0xFF9E9BB0),
    accent = Color(0xFFFF7A4D),
    accentSoft = Color(0x2EFF7A4D),
    accentOnColor = Color(0xFF120C09),
    focusRing = Color(0xFFF7F5FA),
    danger = Color(0xFFFF6E6E),
    scrim = Color(0xB806070B),
    badgeBg = Color(0xCC0D0E12),
)

internal val BoostyOledPalette = BoostyDarkPalette.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF101116),
    surfaceElevated = Color(0xFF181A22),
    surfaceFocused = Color(0xFF232530),
    railBackground = Color(0xFF08090C),
)

@OptIn(ExperimentalTextApi::class)
internal val GolosFontFamily: FontFamily = FontFamily(
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.golos_text,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

internal val BoostyTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GolosFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

internal val LocalBoostyPalette = staticCompositionLocalOf { BoostyDarkPalette }
internal val LocalIsTv = staticCompositionLocalOf { false }
internal val LocalArtwork = staticCompositionLocalOf<Artwork?> { null }

internal object BoostyTheme {
    val palette: BoostyPalette
        @Composable get() = LocalBoostyPalette.current

    val isTv: Boolean
        @Composable get() = LocalIsTv.current
}

internal val CardShape = RoundedCornerShape(BoostyRadii.Card)
internal val ControlShape = RoundedCornerShape(BoostyRadii.Control)
internal val SheetShape = RoundedCornerShape(BoostyRadii.Sheet)
internal val PillShape = RoundedCornerShape(BoostyRadii.Pill)

@Composable
internal fun BoostyAppTheme(
    oled: Boolean,
    isTv: Boolean,
    artwork: Artwork?,
    content: @Composable () -> Unit,
) {
    val palette = if (oled) BoostyOledPalette else BoostyDarkPalette
    val colorScheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.accentOnColor,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.accent,
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.surfaceElevated,
        onSurfaceVariant = palette.muted,
        outline = palette.hairline,
        error = palette.danger,
    )
    CompositionLocalProvider(
        LocalBoostyPalette provides palette,
        LocalIsTv provides isTv,
        LocalArtwork provides artwork,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BoostyTypography,
            content = content,
        )
    }
}
