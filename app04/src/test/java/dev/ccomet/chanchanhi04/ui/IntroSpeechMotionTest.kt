package dev.ccomet.chanchanhi04.ui

import dev.ccomet.chanchanhi04.setup.ScreenSetupStep
import dev.ccomet.chanchanhi04.setup.ScreenSetupTour
import org.junit.Assert.*
import org.junit.Test

class IntroSpeechMotionTest{
    private fun at(ms:Long)=IntroFrame(IntroStep.LISTENING,ms.toFloat()/IntroStep.LISTENING.durationMs)
    @Test fun silenceIsFlatAndSpeechAttacksGradually(){
        assertTrue(at(1000).waveformLevels.all{it==0f});assertEquals(0f,at(1200).waveformGain,0f)
        val early=at(1250).waveformGain;assertTrue(early>0&&early<1)
        assertTrue(at(1450).waveformGain>early);assertEquals(1f,at(1700).waveformGain,0f)
        assertTrue(IntroSpeechEnvelope.history(1000).distinct().size>12)
        val peaks=(0..35).filter{IntroSpeechEnvelope.level(it*100L)>IntroSpeechEnvelope.level((it-1)*100L)&&IntroSpeechEnvelope.level(it*100L)>IntroSpeechEnvelope.level((it+1)*100L)}
        assertTrue(peaks.zipWithNext{a,b->b-a}.distinct().size>1)
    }
    @Test fun speechReleaseAndOverlayDockHaveContinuousIntermediateFrames(){
        assertEquals(1f,at(4800).waveformGain,0f);assertTrue(at(4900).waveformGain in .01f.. .99f)
        assertEquals(0f,at(5200).waveformGain,0f)
        assertEquals(0f,IntroFrame(IntroStep.FONT_SIZE,0f).dockProgress,0f)
        assertTrue(IntroFrame(IntroStep.FONT_SIZE,.04f).dockProgress in .01f.. .99f)
        assertEquals(1f,IntroFrame(IntroStep.FONT_SIZE,.1f).dockProgress,0f)
    }
    @Test fun accessibilitySwitchStaysOffUntilConfirmationIsAccepted(){
        for(p in listOf(0f,.5f,.8f,1f))assertFalse(ScreenSetupTour.switchOn(ScreenSetupStep.SERVICE,p))
        assertFalse(ScreenSetupTour.switchOn(ScreenSetupStep.CONFIRM,.87f))
        assertTrue(ScreenSetupTour.switchOn(ScreenSetupStep.CONFIRM,.89f))
        assertTrue(ScreenSetupTour.PipStepMs>=9000)
    }
}
