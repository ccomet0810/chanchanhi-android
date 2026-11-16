package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class ScreenSetupPresentationTest{
    @Test fun expiredConnectionOffersAnEnabledRecoveryInsteadOfWaitingForever(){
        val recovery=screenSetupPresentation(true,false,false,true,connectionTimedOut=true)
        assertFalse(recovery.waiting);assertFalse(recovery.ready)
        assertEquals("설정에서 확인하기",recovery.action)
        val recovered=screenSetupPresentation(true,true,false,true,connectionTimedOut=true)
        assertTrue(recovered.ready);assertFalse(recovered.waiting);assertEquals("",recovered.message)
        val revoked=screenSetupPresentation(false,false,false,true,connectionTimedOut=true)
        assertFalse(revoked.ready);assertEquals("",revoked.message)
    }
    @Test fun firstEntryDoesNotClaimTheUserFailed(){
        val state=screenSetupPresentation(false,false,false,true)
        assertEquals("설정 시작하기",state.action);assertEquals("",state.message)
    }
    @Test fun incompleteReturnExplainsWhichPermissionAndKeepsAResumeAction(){
        val state=screenSetupPresentation(false,false,true,true)
        assertEquals("설정 이어가기",state.action);assertEquals("화면 안내가 아직 꺼져 있어요.",state.message)
    }
    @Test fun enabledButUnboundServiceIsWaitingEvenAfterAnIncompleteAttempt(){
        val state=screenSetupPresentation(true,false,true,true)
        assertTrue(state.waiting);assertFalse(state.ready);assertEquals("화면 안내에 연결하고 있어요.",state.message)
    }
    @Test fun actualConnectionEnablesNextAndClearsIncompleteCopy(){
        val state=screenSetupPresentation(true,true,true,true)
        assertTrue(state.ready);assertFalse(state.waiting);assertEquals("다음",state.action);assertEquals("",state.message)
    }
    @Test fun staleConnectionCannotTurnARevokedPermissionIntoSuccess(){
        val state=screenSetupPresentation(false,true,true,true)
        assertFalse(state.ready);assertFalse(state.waiting);assertEquals("설정 이어가기",state.action)
    }
    @Test fun pausedConnectionDoesNotTrapTheUserOnADisabledWaitingAction(){
        val state=screenSetupPresentation(true,false,true,true,connectionPaused=true)
        assertFalse(state.waiting);assertFalse(state.ready);assertEquals("설정 이어가기",state.action)
        assertEquals("화면 안내 연결을 다시 확인해주세요.",state.message)
    }
}
