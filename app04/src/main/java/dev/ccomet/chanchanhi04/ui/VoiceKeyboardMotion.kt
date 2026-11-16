package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/** The IME owns the clock and easing; never tween its already animated height a second time. */
class VoiceKeyboardMotion {
    private var previousSource = -1
    private var previousTarget = -1
    private var start = 0f
    private var value = 0f

    fun progress(bottom: Int, source: Int, target: Int): Float {
        if (source == target) {
            // Also covers disabled platform animations and floating/hardware keyboards.
            value = if (bottom > 0) 1f else 0f
        } else {
            if (source != previousSource || target != previousTarget) {
                // Continue from the rendered position when the user reverses the IME mid-flight.
                start = if (previousSource < 0) { if (source > 0) 1f else 0f } else value
            }
            val elapsed = ((bottom - source).toFloat() / (target - source)).coerceIn(0f, 1f)
            val end = if (target > 0) 1f else 0f
            value = start + (end - start) * elapsed
        }
        previousSource = source
        previousTarget = target
        return value
    }

    companion object {
        fun remainingHeight(fullHeight: Int, collapsed: Float): Int =
            (fullHeight * (1f - collapsed.coerceIn(0f, 1f))).roundToInt()
    }
}

/** Read after insets are updated, in the same layout pass as safeDrawingPadding. No padding here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun rememberVoiceKeyboardProgress(): () -> Float {
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val density = LocalDensity.current
    return remember(ime, source, target, density) {
        val motion = VoiceKeyboardMotion()
        val reader: () -> Float = { motion.progress(ime.getBottom(density), source.getBottom(density), target.getBottom(density)) }
        reader
    }
}

/** Keep content composed, but relinquish its measured height and clip towards the adjoining body. */
@Composable internal fun VoiceKeyboardFold(
    progress: () -> Float,
    towards: Alignment.Vertical,
    content: @Composable () -> Unit
) {
    Box(Modifier.clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val collapsed = progress().coerceIn(0f, 1f)
        val height = constraints.constrainHeight(VoiceKeyboardMotion.remainingHeight(child.height, collapsed))
        layout(child.width, height) {
            // Unplaced children cannot expose invisible buttons to touch or accessibility.
            if (height > 0) child.placeRelativeWithLayer(0, towards.align(child.height, height)) {
                alpha = 1f - collapsed
            }
        }
    }) { content() }
}
