package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class PipSetupGateTest{
    @Test fun cancellingDoesNotSkipPermissionOrLoopBackToSettings(){
        val gate=PipSetupGate()
        assertEquals(PipSetupAction.REQUEST_PERMISSION,gate.next(true,false))
        repeat(3){assertEquals(PipSetupAction.WAIT_PERMISSION,gate.next(true,false))}
        val restored=PipSetupGate(gate.requested)
        assertEquals(PipSetupAction.WAIT_PERMISSION,restored.next(true,false))
        restored.retry();assertEquals(PipSetupAction.REQUEST_PERMISSION,restored.next(true,false))
    }
    @Test fun theActualGrantRatherThanTheSettingsResultOpensPip(){
        val gate=PipSetupGate()
        gate.next(true,false)
        assertEquals(PipSetupAction.ENTER_PIP,gate.next(true,true))
        assertEquals(PipSetupAction.WAIT_PERMISSION,gate.next(true,false))
    }
    @Test fun unsupportedHardwareDoesNotRequestASettingThatCannotBeEnabled(){
        val gate=PipSetupGate()
        assertEquals(PipSetupAction.UNSUPPORTED,gate.next(false,false));assertFalse(gate.requested)
    }
}
