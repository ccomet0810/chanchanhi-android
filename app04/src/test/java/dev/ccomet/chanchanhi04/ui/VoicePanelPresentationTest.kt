package dev.ccomet.chanchanhi04.ui

import dev.ccomet.chanchanhi04.voice.VoicePhase
import org.junit.Assert.*
import org.junit.Test

class VoicePanelPresentationTest{
    @Test fun listeningWaitsBeforeTextArrivesAndWaveformStopsWithSpeech(){
        for(ms in listOf(0L,500L,1199L,1200L))assertEquals("",IntroFrame(IntroStep.LISTENING,ms/6000f).transcript)
        assertTrue(IntroFrame(IntroStep.LISTENING,.4f).transcript.isNotEmpty())
        assertEquals(IntroTimeline.request,IntroFrame(IntroStep.LISTENING,.8f).transcript)
        assertEquals(IntroFrame(IntroStep.LISTENING,.95f).waveformTime,IntroFrame(IntroStep.REVIEW,.3f).waveformTime)
    }
    @Test fun recognitionKeepsDraftForReviewAndDoesNotSubmitBeforeTheUserTap(){
        val listening=IntroFrame(IntroStep.LISTENING,.4f)
        assertEquals(VoicePhase.LISTENING,listening.voicePhase)
        assertTrue(IntroTimeline.request.startsWith(listening.transcript))
        val recognizing=IntroFrame(IntroStep.LISTENING,.93f)
        assertEquals(VoicePhase.PROCESSING,recognizing.voicePhase)
        assertEquals(IntroTimeline.request,recognizing.transcript)
        for(p in listOf(0f,.5f,.64f,.77f)){
            val frame=IntroFrame(IntroStep.REVIEW,p)
            assertEquals(IntroStep.REVIEW,frame.overlayStep)
            assertEquals(IntroTimeline.request,frame.transcript)
            assertFalse("The app does not guide its own send button with a gesture overlay",frame.showGestureGuide)
        }
        assertTrue(IntroFrame(IntroStep.REVIEW,.7f).showUserTouch)
        for(p in listOf(.78f,.9f,1f)){
            val sent=IntroFrame(IntroStep.REVIEW,p)
            assertEquals(IntroStep.THINKING,sent.overlayStep)
            assertFalse(sent.showUserTouch)
            assertEquals(IntroFrame(IntroStep.THINKING,0f).instruction,sent.instruction)
        }
    }
    @Test fun changingVoiceAndAgentOverlaysKeepsTheUnderlyingHomePage(){
        for(step in listOf(IntroStep.INVOKE,IntroStep.LISTENING,IntroStep.REVIEW,IntroStep.THINKING,IntroStep.OPEN_SETTINGS)){
            assertEquals(IntroPage.HOME,IntroFrame(step,.8f).page)
        }
    }
    @Test fun sharedVoiceControlsFollowTheActualRecognitionPhase(){
        for(phase in listOf(VoicePhase.STARTING,VoicePhase.LISTENING)){
            val model=VoicePanelPresentation.forState(phase)
            assertTrue(model.active);assertEquals(VoiceSecondary.STOP,model.secondary)
        }
        val processing=VoicePanelPresentation.forState(VoicePhase.PROCESSING)
        assertTrue(processing.active);assertEquals(VoiceSecondary.RETRY,processing.secondary)
        assertEquals("입력을 마무리해요",processing.title)
        val review=VoicePanelPresentation.forState(VoicePhase.REVIEW)
        assertEquals("내용을 확인해주세요",review.title)
        assertFalse(review.active);assertEquals(VoiceSecondary.RETRY,review.secondary)
        for(phase in listOf(VoicePhase.REVIEW,VoicePhase.TEXT,VoicePhase.ERROR)){
            assertEquals(VoiceSecondary.ALLOW_MICROPHONE,VoicePanelPresentation.forState(phase,microphoneAllowed=false).secondary)
            assertNull(VoicePanelPresentation.forState(phase,stale=true).secondary)
        }
        assertEquals("답변을 적어주세요",VoicePanelPresentation.forState(VoicePhase.TEXT,answering=true).title)
        assertEquals("오류 설명",VoicePanelPresentation.forState(VoicePhase.ERROR,message="오류 설명").title)
        assertNull(VoicePanelPresentation.forState(VoicePhase.CLOSED).secondary)
    }
}
