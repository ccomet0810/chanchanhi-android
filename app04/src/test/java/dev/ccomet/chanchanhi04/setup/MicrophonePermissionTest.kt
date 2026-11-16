package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class MicrophonePermissionTest {
    @Test fun explicitPreparationCannotLaunchBeforeTheScreenServiceIsGrantedAndConnected() {
        assertFalse(MicrophoneLaunchGate.allowed(false,false,true))
        assertFalse(MicrophoneLaunchGate.allowed(true,false,true))
        assertFalse(MicrophoneLaunchGate.allowed(false,true,true))
        assertTrue(MicrophoneLaunchGate.allowed(true,true,true))
    }
    @Test fun pausedActivityOrActiveSetupHelpCannotIssueANativeRequest() {
        assertFalse(MicrophoneLaunchGate.allowed(true,true,false))
        assertFalse(MicrophoneLaunchGate.allowed(true,true,true,setupHelpActive=true))
        assertTrue(MicrophoneLaunchGate.allowed(true,true,true,setupHelpActive=false))
    }
    @Test fun rapidClicksAndRecreationCannotLaunchAnotherPermissionRequest() {
        val gate=MicrophoneRequestGate()
        val ungranted=MicrophonePermissionState()
        assertTrue(gate.begin(ungranted))
        // Even a stale Compose observation cannot issue a second launch.
        assertFalse(gate.begin(ungranted))
        val recreated=MicrophoneRequestGate(restoredPending=gate.pending)
        assertFalse(recreated.begin(ungranted))
        recreated.finish()
        assertTrue(recreated.begin(ungranted.copy(requested=true)))
    }
    @Test fun denialWaitsForAnExplicitRetryAndBlockedPermissionDoesNotLaunch() {
        val gate=MicrophoneRequestGate()
        assertTrue(gate.begin(MicrophonePermissionState()))
        gate.finish()
        val denied=MicrophonePermissionState(requested=true)
        assertFalse(gate.pending)
        assertEquals(MicrophonePermissionStatus.RETRY,denied.status)
        assertTrue(gate.begin(denied))
        gate.finish()
        val blocked=denied.copy(deniedWithoutRationale=true)
        assertEquals(MicrophonePermissionStatus.SETTINGS,blocked.status)
        assertFalse(gate.begin(blocked))
    }
    @Test fun osGrantWinsOverStoredDenialAndRevocationCannotBeReportedAsReady() {
        val denied=MicrophonePermissionState(requested=true,deniedWithoutRationale=true)
        val fromSettings=denied.copy(granted=true)
        assertEquals(MicrophonePermissionStatus.READY,fromSettings.status)
        assertFalse(MicrophoneRequestGate().begin(fromSettings))
        assertNotEquals(MicrophonePermissionStatus.READY,fromSettings.copy(granted=false).status)
        // No previous callback: the first request is allowed even though Android initially has no rationale.
        assertTrue(MicrophoneRequestGate().begin(MicrophonePermissionState()))
    }
    @Test fun pendingRequestRemainsBlockedWhenTheUiIsReobserved() {
        assertFalse(MicrophoneRequestGate().begin(MicrophonePermissionState(pending=true)))
        val restored=MicrophoneRequestGate(true)
        restored.finish()
        assertFalse(restored.begin(MicrophonePermissionState(granted=true)))
        assertFalse(restored.pending)
    }
}
