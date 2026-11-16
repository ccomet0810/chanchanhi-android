package dev.ccomet.chanchanhi04.voice

import dev.ccomet.chanchanhi04.agent.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureTest {
    private class Engine:VoiceEngine {
        lateinit var events:VoiceEngine.Events
        var cancelled=0;var stopped=0
        override fun start(events:VoiceEngine.Events){this.events=events}
        override fun stop(){stopped++}
        override fun cancel(){cancelled++}
    }
    @Test fun cancelDiscardsEvenLateFinalResults(){
        val engine=Engine();val capture=VoiceCapture({engine},{0})
        capture.start();engine.events.ready();capture.cancel();engine.events.result("late");engine.events.ready();engine.events.level(10f)
        assertEquals(VoicePhase.CLOSED,capture.state.value.phase);assertEquals("",capture.state.value.text);assertNull(capture.confirm());assertEquals(1,engine.cancelled)
    }
    @Test fun partialsAndFinalRemainDraftUntilExplicitConfirmation(){
        val engine=Engine();val capture=VoiceCapture({engine},{0})
        capture.start();engine.events.ready();engine.events.partial("알람을");engine.events.level(12f)
        assertEquals(VoicePhase.LISTENING,capture.state.value.phase);assertEquals("알람을",capture.state.value.text);assertEquals(1f,capture.state.value.levels.last())
        engine.events.level(Float.NaN);engine.events.level(Float.POSITIVE_INFINITY);assertEquals(22,capture.state.value.levels.size)
        capture.stop();assertEquals(1,engine.stopped);assertEquals(VoicePhase.PROCESSING,capture.state.value.phase)
        engine.events.result("  알람을 맞추고 싶어요  ");engine.events.result("duplicate")
        assertEquals(VoicePhase.REVIEW,capture.state.value.phase);assertEquals("알람을 맞추고 싶어요",capture.state.value.text)
        assertEquals("알람을 맞추고 싶어요",capture.confirm());assertNull(capture.confirm())
    }
    @Test fun typingWhileListeningCancelsRecognitionAndLateCallbacksCannotOverwriteEdits(){
        val engine=Engine();val capture=VoiceCapture({engine},{0})
        capture.start();engine.events.ready();engine.events.partial("initial")
        capture.edit("corrected");engine.events.partial("obsolete partial");engine.events.result("obsolete final")
        assertEquals(VoicePhase.TEXT,capture.state.value.phase);assertEquals("corrected",capture.state.value.text);assertEquals(1,engine.cancelled)
        assertEquals("corrected",capture.confirm())
    }
    @Test fun switchingToTextKeepsRecognizedDraft(){
        val engine=Engine();val capture=VoiceCapture({engine},{0})
        capture.start();engine.events.ready();engine.events.partial("draft");capture.textMode();engine.events.result("late")
        assertEquals(VoicePhase.TEXT,capture.state.value.phase);assertEquals("draft",capture.state.value.text)
        capture.edit("typed");assertEquals("typed",capture.confirm())
    }
    @Test fun retryWithoutSpeechPreservesDraftUntilNewRecognitionArrives(){
        val first=Engine();val next=Engine();var engine=first
        val capture=VoiceCapture({engine},{0});capture.start();first.events.partial("keep this");first.events.result(" ")
        assertEquals(VoicePhase.ERROR,capture.state.value.phase)
        engine=next;capture.start();assertEquals("keep this",capture.state.value.text)
        first.events.result("obsolete");next.events.ready();next.events.result("new")
        assertEquals(VoicePhase.REVIEW,capture.state.value.phase);assertEquals("new",capture.confirm())
    }
    @Test fun blankPartialDoesNotEraseTextAlreadyShown(){
        val engine=Engine();val capture=VoiceCapture({engine},{0});capture.start();engine.events.ready()
        engine.events.partial("visible");engine.events.partial("");engine.events.partial("  ")
        assertEquals("visible",capture.state.value.text)
    }
    @Test fun userCanSendVisiblePartialAndRejectLaterFinal(){
        val engine=Engine();val capture=VoiceCapture({engine},{0});capture.start();engine.events.ready();engine.events.partial("approved")
        assertEquals("approved",capture.confirm());engine.events.result("different final")
        assertEquals("approved",capture.state.value.text);assertNull(capture.confirm());assertEquals(1,engine.cancelled)
    }
    @Test fun missingReadyCallbackHasBoundedStartupWait(){
        val engine=Engine();var time=0L;val capture=VoiceCapture({engine},{time})
        capture.start();time=12001;capture.poll();assertEquals(VoicePhase.ERROR,capture.state.value.phase);assertEquals(1,engine.cancelled)
        engine.events.result("late");assertEquals(VoicePhase.ERROR,capture.state.value.phase)
    }
    @Test fun listeningLimitStopsThenResultWaitAlsoTimesOut(){
        val engine=Engine();var time=0L;val capture=VoiceCapture({engine},{time})
        capture.start();engine.events.ready();time=60001;capture.poll();assertEquals(1,engine.stopped)
        assertEquals(VoicePhase.PROCESSING,capture.state.value.phase)
        time+=12001;capture.poll();assertEquals(VoicePhase.ERROR,capture.state.value.phase);assertEquals(1,engine.cancelled)
    }
    @Test fun engineFailureReleasesMicrophoneAndRetainsTextForCorrection(){
        val engine=Engine();val capture=VoiceCapture({engine},{0})
        capture.start();engine.events.ready();engine.events.partial("draft");engine.events.error("network")
        assertEquals(VoicePhase.ERROR,capture.state.value.phase);assertEquals(1,engine.cancelled)
        engine.events.ended();assertEquals(VoicePhase.ERROR,capture.state.value.phase);assertEquals("draft",capture.state.value.text)
    }
    @Test fun questionReplyCannotBeRoutedToNewSessionOrNewQuestion(){
        val first=AgentState(sessionId="one",phase=Phase.QUESTION,pending=ToolCall("q1",Tool.ASK_USER,"",question="언제?"))
        val target=VoiceTarget.from(first);assertTrue(target.accepts(first.copy(message="화면 갱신")))
        assertFalse(target.accepts(first.copy(sessionId="two")))
        assertFalse(target.accepts(first.copy(pending=first.pending!!.copy(callId="q2"))))
        assertFalse(target.accepts(first.copy(phase=Phase.STOPPED)))
    }
    @Test fun newGoalCannotRestartWorkAfterUserStoppedOrResumed(){
        val paused=AgentState(sessionId="one",phase=Phase.PAUSED);val target=VoiceTarget.from(paused)
        assertTrue(target.accepts(paused));assertFalse(target.answering)
        assertFalse(target.accepts(paused.copy(phase=Phase.STOPPED)))
        assertFalse(target.accepts(paused.copy(phase=Phase.THINKING)))
    }
}
