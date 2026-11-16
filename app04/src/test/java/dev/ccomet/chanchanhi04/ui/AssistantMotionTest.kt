package dev.ccomet.chanchanhi04.ui

import org.junit.Assert.*
import org.junit.Test

class AssistantMotionTest{
    @Test fun entranceAndQuietingStayBoundedAndMonotonic(){
        assertEquals(0f,AssistantMotion.reveal(-1),0f)
        assertEquals(1f,AssistantMotion.reveal(1000),0f)
        assertEquals(1f,AssistantMotion.quietGain(-1),0f)
        assertEquals(0f,AssistantMotion.quietGain(1000),0f)
        val reveal=(0L..420L step 10).map{AssistantMotion.reveal(it)}
        val quiet=(0L..380L step 10).map{AssistantMotion.quietGain(it)}
        assertTrue(reveal.zipWithNext().all{(a,b)->a<=b})
        assertTrue(quiet.zipWithNext().all{(a,b)->a>=b})
    }
    @Test fun sheetArrivesAtTheEndOfInvocationAndDoesNotReplayForTranscriptUpdates(){
        assertEquals(0f,IntroFrame(IntroStep.INVOKE,.5f).revealProgress,0f)
        assertTrue(IntroFrame(IntroStep.INVOKE,.93f).revealProgress in .01f.. .99f)
        for(p in listOf(0f,.1f,.5f,.9f))assertEquals(1f,IntroFrame(IntroStep.LISTENING,p).revealProgress,0f)
        assertEquals(1f,IntroFrame(IntroStep.REVIEW,.2f).revealProgress,0f)
        assertEquals(0f,IntroFrame(IntroStep.REVIEW,.8f).revealProgress,0f)
    }
    @Test fun speechEndsWithAShortDecayInsteadOfFrozenTallBars(){
        val end=IntroFrame(IntroStep.LISTENING,.8f)
        val falling=IntroFrame(IntroStep.LISTENING,.83f)
        val quiet=IntroFrame(IntroStep.LISTENING,.9f)
        assertEquals(1f,end.waveformGain,0f)
        assertTrue(falling.waveformGain in .01f.. .99f)
        assertEquals(0f,quiet.waveformGain,0f)
        assertEquals(0f,IntroFrame(IntroStep.REVIEW,.3f).waveformGain,0f)
        assertEquals(2,Foundation.VoicePreviewLines)
    }
}
