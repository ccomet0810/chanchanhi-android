package com.example.aplassistmvp0.apl2.design

/** Framework-neutral roles shared by Compose screens and native assistant/overlay windows. */
object AplFoundation {
    const val InkArgb: Int = 0xFF151515.toInt()
    const val MutedArgb: Int = 0xFF595959.toInt()
    const val CanvasArgb: Int = 0xFFF5F5F5.toInt()
    const val SurfaceArgb: Int = 0xFFFFFFFF.toInt()
    const val OutlineArgb: Int = 0xFFD1D1D1.toInt()
    const val DisabledArgb: Int = 0xFFE6E6E6.toInt()

    const val Space4Dp = 4
    const val Space8Dp = 8
    const val Space12Dp = 12
    const val Space16Dp = 16
    const val Space24Dp = 24
    const val Space32Dp = 32
    const val MinTouchDp = 56
    const val MaxContentWidthDp = 600
    const val SmallRadiusDp = 12
    const val ControlRadiusDp = 16
    const val PanelRadiusDp = 24
    const val SheetRadiusDp = 32
    const val PillRadiusDp = 100
}

/** Figma page 11, source 00/15–20. Separate from the app's larger settings controls. */
object AplAssistantFoundation {
    const val BrandArgb: Int = 0xFF157E19.toInt()
    const val TextArgb: Int = 0xFF000000.toInt()
    const val HistoryArgb: Int = 0xFF666666.toInt()
    const val PlaceholderArgb: Int = 0xFFACACAC.toInt()
    const val ControlArgb: Int = 0xFFEFEFEF.toInt()
    const val TargetArgb: Int = 0xFF00CC44.toInt()
    const val PanelWidthDp = 380
    const val PanelRadiusDp = 24
    const val ControlHeightDp = 48
    const val RespondMs = 204L
    const val RepositionMs = 307L
    const val WaveLoopMs = 10_000L
    const val WaveTravelDp = 2230f
}
