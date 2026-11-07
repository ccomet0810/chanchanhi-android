package com.example.aplassistmvp0.apl2.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Product foundations. Values describe roles rather than individual screens. */
object AplTokens {
    val ink = Color(AplFoundation.InkArgb)
    val muted = Color(AplFoundation.MutedArgb)
    val canvas = Color(AplFoundation.CanvasArgb)
    val surface = Color(AplFoundation.SurfaceArgb)
    val outline = Color(AplFoundation.OutlineArgb)
    val disabled = Color(AplFoundation.DisabledArgb)

    val space4 = AplFoundation.Space4Dp.dp
    val space8 = AplFoundation.Space8Dp.dp
    val space12 = AplFoundation.Space12Dp.dp
    val space16 = AplFoundation.Space16Dp.dp
    val space24 = AplFoundation.Space24Dp.dp
    val space32 = AplFoundation.Space32Dp.dp
    val minTouch = AplFoundation.MinTouchDp.dp
    val maxContentWidth = AplFoundation.MaxContentWidthDp.dp
    val radiusSmall = AplFoundation.SmallRadiusDp.dp
    val radiusCard = AplFoundation.PanelRadiusDp.dp
    val radiusSheet = AplFoundation.SheetRadiusDp.dp
    val radiusPill = AplFoundation.PillRadiusDp.dp

    // Use Android's own Korean font and the user's font scale. Never convert sp to dp.
    private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = size.sp,
        lineHeight = height.sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

    val typography = Typography(
        displayLarge = type(36, 46, FontWeight.Bold),
        displayMedium = type(32, 42, FontWeight.Bold),
        displaySmall = type(30, 40, FontWeight.Bold),
        headlineLarge = type(28, 38, FontWeight.Bold),
        headlineMedium = type(26, 36, FontWeight.Bold),
        headlineSmall = type(24, 34, FontWeight.Bold),
        titleLarge = type(22, 32, FontWeight.Bold),
        titleMedium = type(20, 30, FontWeight.SemiBold),
        titleSmall = type(18, 28, FontWeight.SemiBold),
        bodyLarge = type(18, 28),
        bodyMedium = type(16, 26),
        bodySmall = type(14, 22),
        labelLarge = type(18, 26, FontWeight.SemiBold),
        labelMedium = type(16, 24, FontWeight.Medium),
        labelSmall = type(14, 22, FontWeight.Medium),
    )
}

private val AplColorScheme = lightColorScheme(
    primary = AplTokens.ink,
    onPrimary = AplTokens.surface,
    primaryContainer = AplTokens.ink,
    onPrimaryContainer = AplTokens.surface,
    inversePrimary = AplTokens.surface,
    secondary = AplTokens.ink,
    onSecondary = AplTokens.surface,
    secondaryContainer = AplTokens.disabled,
    onSecondaryContainer = AplTokens.ink,
    tertiary = AplTokens.ink,
    onTertiary = AplTokens.surface,
    tertiaryContainer = AplTokens.disabled,
    onTertiaryContainer = AplTokens.ink,
    background = AplTokens.canvas,
    onBackground = AplTokens.ink,
    surface = AplTokens.surface,
    onSurface = AplTokens.ink,
    surfaceVariant = AplTokens.canvas,
    onSurfaceVariant = AplTokens.muted,
    surfaceTint = Color.Transparent,
    inverseSurface = AplTokens.ink,
    inverseOnSurface = AplTokens.surface,
    outline = AplTokens.outline,
    outlineVariant = AplTokens.outline,
    scrim = Color.Black,
    // An error is identified by words and its action, never by color alone.
    error = AplTokens.ink,
    onError = AplTokens.surface,
    errorContainer = AplTokens.disabled,
    onErrorContainer = AplTokens.ink,
    surfaceBright = AplTokens.surface,
    surfaceDim = AplTokens.canvas,
    surfaceContainerLowest = AplTokens.surface,
    surfaceContainerLow = AplTokens.surface,
    surfaceContainer = AplTokens.canvas,
    surfaceContainerHigh = AplTokens.disabled,
    surfaceContainerHighest = AplTokens.outline,
)

/** Intentional monochrome appearance; device wallpaper never changes product colors. */
@Composable
fun AplTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AplColorScheme,
        typography = AplTokens.typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(AplTokens.space8),
            small = RoundedCornerShape(AplTokens.radiusSmall),
            medium = RoundedCornerShape(AplTokens.space16),
            large = RoundedCornerShape(AplTokens.radiusCard),
            extraLarge = RoundedCornerShape(AplTokens.radiusSheet),
        ),
        content = content,
    )
}
