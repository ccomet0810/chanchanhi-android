package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class PermissionJourneyTest {
    private fun observe(pip:Boolean=true,screen:Boolean=true,connected:Boolean=true,mic:MicrophonePermissionStatus=MicrophonePermissionStatus.READY)=
        PermissionJourney.observe(true,pip,screen,connected,mic)
    @Test fun grantsChooseTheFirstMissingCapability(){
        assertEquals(PermissionStage.PIP,observe(false,false,false,MicrophonePermissionStatus.AVAILABLE).stage)
        assertEquals(PermissionStage.ACCESSIBILITY,observe(screen=false,connected=false).stage)
        assertEquals(PermissionStage.ACCESSIBILITY,observe(connected=false).stage)
        assertEquals(PermissionStage.MICROPHONE,observe(mic=MicrophonePermissionStatus.AVAILABLE).stage)
        assertTrue(observe().complete)
    }
    @Test fun denialPendingAndPermanentDenialNeverReachCompletion(){
        for(status in listOf(MicrophonePermissionStatus.AVAILABLE,MicrophonePermissionStatus.RETRY,MicrophonePermissionStatus.SETTINGS,MicrophonePermissionStatus.PENDING)){
            val state=observe(mic=status);assertFalse(state.complete);assertEquals(2,state.completed)
        }
    }
    @Test fun revocationReopensTheNeededStepEvenAfterAllPermissionsWereReady(){
        assertTrue(observe().complete)
        assertEquals(PermissionStage.PIP,observe(pip=false).stage)
        assertEquals(PermissionStage.ACCESSIBILITY,observe(screen=false,connected=false).stage)
        assertEquals(PermissionStage.MICROPHONE,observe(mic=MicrophonePermissionStatus.RETRY).stage)
    }
    @Test fun unsupportedPipAndAlreadyGrantedStepsAreSkipped(){
        assertEquals(PermissionStage.MICROPHONE,PermissionJourney.observe(false,false,true,true,MicrophonePermissionStatus.AVAILABLE).stage)
        assertEquals(3,observe().completed)
    }
}
