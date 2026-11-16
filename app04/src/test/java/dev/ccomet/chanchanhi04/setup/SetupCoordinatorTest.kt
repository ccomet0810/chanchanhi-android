package dev.ccomet.chanchanhi04.setup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupCoordinatorTest{
    private class Port:SetupPort{
        var actual=SetupSnapshot(false,false,false);var awake=true;var settings=true;var ready=true;var openSuccess=true;var paused=false
        val opened=mutableListOf<SetupRequirement>();val guided=mutableListOf<SetupRequirement>();var pending=false;var complete=0
        override fun snapshot()=actual
        override fun interactive()=awake
        override fun inSettings()=settings
        override fun serviceReady()=ready
        override fun open(requirement:SetupRequirement)=openSuccess.also{if(it)opened+=requirement}
        override fun guide(requirement:SetupRequirement)=true.also{guided+=requirement}
        override fun guidancePaused()=paused
        override fun pauseGuidance(message:String){paused=true}
        override fun stopGuidance(){paused=false}
        override fun completed(){complete++}
        override fun rememberPending(pending:Boolean){this.pending=pending}
    }
    @Test fun screenSwitchAloneDoesNotFinishUntilTheActualServiceBinds()=runTest{
        val port=Port().apply{ready=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start(setOf(SetupRequirement.SCREEN));advanceTimeBy(1000);runCurrent()
        port.actual=SetupSnapshot(true,false,false);advanceTimeBy(1500);runCurrent()
        assertEquals(SetupPhase.RUNNING,flow.state.value.phase);assertEquals(0,port.complete)
        port.ready=true;advanceTimeBy(400);runCurrent()
        assertEquals(SetupPhase.COMPLETE,flow.state.value.phase);assertEquals(1,port.complete)
    }
    @Test fun anAlreadyEnabledButUnboundScreenServiceWaitsWithoutReopeningSettingsAndTimesOut()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,false,false);ready=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start(setOf(SetupRequirement.SCREEN));runCurrent();assertTrue(port.opened.isEmpty())
        advanceTimeBy(23000);runCurrent();assertEquals(SetupPhase.PAUSED,flow.state.value.phase);assertEquals(0,port.complete)
    }
    @Test fun accessibilityThenAssistantDelegatesAiWithoutOpeningItsDestinationOrMic()=runTest{
        val port=Port();val flow=SetupCoordinator(backgroundScope,port);flow.start();advanceTimeBy(1000);runCurrent()
        assertEquals(listOf(SetupRequirement.SCREEN),port.opened);assertTrue(port.guided.isEmpty())
        port.actual=SetupSnapshot(true,false,false);advanceTimeBy(1500);runCurrent()
        assertEquals(listOf(SetupRequirement.ASSISTANT),port.guided)
        assertEquals(listOf(SetupRequirement.SCREEN),port.opened)
        advanceTimeBy(10000);runCurrent();assertEquals(0,port.complete)
        port.actual=SetupSnapshot(true,false,true);advanceTimeBy(400);runCurrent()
        assertEquals(SetupPhase.COMPLETE,flow.state.value.phase);assertEquals(1,port.complete);assertFalse(port.pending)
    }
    @Test fun alreadyGrantedCapabilitiesAreSkippedAndSearchCompanionsNeedNoPackageGate()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,true,false);settings=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start();advanceTimeBy(1000);runCurrent()
        assertTrue(port.opened.isEmpty());assertEquals(listOf(SetupRequirement.ASSISTANT),port.guided)
        assertEquals(SetupPhase.RUNNING,flow.state.value.phase)
    }
    @Test fun returningOrCancellingPreventsLateGrantFromStartingAnotherGuide()=runTest{
        val port=Port();val flow=SetupCoordinator(backgroundScope,port);flow.start();runCurrent();flow.pause()
        port.actual=SetupSnapshot(true,false,false);advanceTimeBy(2000);runCurrent()
        assertEquals(1,port.opened.size);assertTrue(port.guided.isEmpty());assertTrue(port.pending)
        flow.start();runCurrent();flow.cancel()
        port.actual=SetupSnapshot(true,true,false);advanceTimeBy(2000);runCurrent()
        assertEquals(1,port.opened.size);assertTrue(port.guided.isEmpty());assertFalse(port.pending)
    }
    @Test fun bootstrapCanRequireOnlyScreenAndDefersOtherCapabilities()=runTest{
        val port=Port();val flow=SetupCoordinator(backgroundScope,port)
        flow.start(setOf(SetupRequirement.SCREEN));runCurrent()
        port.actual=SetupSnapshot(true,false,false);advanceTimeBy(1000);runCurrent()
        assertEquals(SetupPhase.COMPLETE,flow.state.value.phase);assertEquals(1,port.opened.size)
        assertTrue(port.guided.isEmpty());assertFalse(port.actual.microphone)
    }
    @Test fun lockOrLeavingTheUnreadableBootstrapPausesInsteadOfAdvancing()=runTest{
        for(locked in listOf(true,false)){
            val port=Port();val flow=SetupCoordinator(backgroundScope,port);flow.start();runCurrent()
            if(locked)port.awake=false else port.settings=false
            advanceTimeBy(1000);runCurrent()
            assertEquals(SetupPhase.PAUSED,flow.state.value.phase);assertEquals(1,port.opened.size);flow.cancel()
        }
    }
    @Test fun lostAccessibilityAndUserPauseStopContinuousSetup()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,true,false)};val flow=SetupCoordinator(backgroundScope,port)
        flow.start();advanceTimeBy(1000);runCurrent();port.actual=SetupSnapshot(false,true,false)
        advanceTimeBy(400);runCurrent();assertEquals(SetupPhase.PAUSED,flow.state.value.phase)
        port.actual=SetupSnapshot(true,true,false);flow.start();advanceTimeBy(1000);runCurrent();port.paused=true
        advanceTimeBy(400);runCurrent();assertEquals(SetupPhase.PAUSED,flow.state.value.phase)
    }
    @Test fun serviceMayBindLateAndAiCompletionAloneCannotAdvance()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,true,false);ready=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start();advanceTimeBy(2000);runCurrent();assertTrue(port.guided.isEmpty())
        port.ready=true;advanceTimeBy(400);runCurrent();assertEquals(1,port.guided.size)
        advanceTimeBy(5000);runCurrent();assertTrue(port.opened.isEmpty());assertEquals(0,port.complete)
    }
    @Test fun failedBootstrapRemainsIncompleteAndCanRetry()=runTest{
        val port=Port().apply{openSuccess=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start();runCurrent();assertEquals(SetupPhase.PAUSED,flow.state.value.phase);assertTrue(port.pending)
        port.openSuccess=true;flow.start();advanceTimeBy(3000);runCurrent();assertEquals(1,port.opened.size);assertEquals(0,port.complete)
    }
    @Test fun processRestorationRequiresExplicitResumeAndDoesNotLaunchSettings()=runTest{
        val port=Port();val flow=SetupCoordinator(backgroundScope,port,pending=true)
        advanceTimeBy(3000);runCurrent();assertTrue(port.opened.isEmpty());assertEquals(SetupPhase.PAUSED,flow.state.value.phase)
    }
    @Test fun duplicateStartDoesNotRepeatAndDisconnectedServiceDoesNotWaitForever()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,true,false);ready=false};val flow=SetupCoordinator(backgroundScope,port)
        flow.start();runCurrent();flow.start();advanceTimeBy(23000);runCurrent()
        assertTrue(port.opened.isEmpty());assertEquals(SetupPhase.PAUSED,flow.state.value.phase);assertTrue(port.guided.isEmpty())
    }
    @Test fun anExplicitMicrophoneIdentifierNeverOpensSettingsOrStartsAi()=runTest{
        val port=Port().apply{actual=SetupSnapshot(true,false,false)}
        val flow=SetupCoordinator(backgroundScope,port);flow.start(setOf(SetupRequirement.MICROPHONE));advanceTimeBy(30000);runCurrent()
        assertEquals(SetupPhase.PAUSED,flow.state.value.phase)
        assertTrue(port.opened.isEmpty());assertTrue(port.guided.isEmpty());assertEquals(0,port.complete)
    }
}
