package dev.ccomet.chanchanhi04.ui

import org.junit.Assert.*
import org.junit.Test

class VoiceKeyboardMotionTest {
    @Test fun openingAndClosingUseTheSamePhysicalInsetsInReverse() {
        val motion = VoiceKeyboardMotion()
        val opening = (0..320 step 8).map { motion.progress(it, 0, 320) }
        val closing = (320 downTo 0 step 8).map { motion.progress(it, 320, 0) }
        opening.zip(closing.reversed()).forEach { (a, b) -> assertEquals(a, b, .00001f) }
        assertEquals(0f, opening.first(), 0f)
        assertEquals(1f, opening.last(), 0f)
        assertTrue(opening.zipWithNext().all { (a, b) -> b >= a })
        // A different keyboard height must keep the same visual position at half opening.
        assertEquals(VoiceKeyboardMotion().progress(160, 0, 320), VoiceKeyboardMotion().progress(260, 0, 520), 0f)
    }

    @Test fun reversalAndKeyboardResizeCannotOvershootOrLeaveAResidualGap() {
        val motion = VoiceKeyboardMotion()
        assertEquals(1f, motion.progress(320, 320, 320), 0f)
        assertEquals(.5f, motion.progress(160, 320, 0), 0f)
        assertEquals(.5f, motion.progress(160, 160, 320), 0f)
        assertEquals(.75f, motion.progress(240, 160, 320), 0f)
        assertEquals(1f, motion.progress(320, 160, 320), 0f)
        // Changing keyboard height while open must not reveal either collapsed region.
        assertEquals(1f, motion.progress(320, 320, 420), 0f)
        assertEquals(1f, motion.progress(370, 320, 420), 0f)
        assertEquals(1f, motion.progress(420, 320, 420), 0f)
        assertEquals(0f, motion.progress(0, 420, 0), 0f)
        assertEquals(0f, motion.progress(0, 0, 0), 0f)
        assertEquals(0, VoiceKeyboardMotion.remainingHeight(76, 1f))
        assertEquals(38, VoiceKeyboardMotion.remainingHeight(76, .5f))
        assertEquals(76, VoiceKeyboardMotion.remainingHeight(76, 0f))
    }

    @Test fun disabledAnimationsAndOutOfRangeCallbacksStayWithinMeasuredBounds() {
        val motion = VoiceKeyboardMotion()
        assertEquals(0f, motion.progress(0, 0, 0), 0f)
        assertEquals(1f, motion.progress(320, 320, 320), 0f)
        assertEquals(0f, motion.progress(0, 0, 0), 0f)
        assertEquals(1f, motion.progress(600, 0, 320), 0f)
        assertEquals(0f, motion.progress(-20, 320, 0), 0f)
        for (height in listOf(0, 76, 140, 240)) for (fraction in listOf(-1f, 0f, .5f, 1f, 2f)) {
            assertTrue(VoiceKeyboardMotion.remainingHeight(height, fraction) in 0..height)
        }
    }
}
