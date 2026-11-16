package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class ScreenSetupTourTest{
    @Test fun thePreviewStartsAtTheDestinationActuallyAvailableToThisInstallation(){
        assertEquals(listOf(ScreenSetupStep.ACCESSIBILITY,ScreenSetupStep.APPS,ScreenSetupStep.SERVICE,ScreenSetupStep.CONFIRM),ScreenSetupTour.steps(false))
        assertEquals(listOf(ScreenSetupStep.SERVICE,ScreenSetupStep.CONFIRM),ScreenSetupTour.steps(true))
    }
    @Test fun manualNavigationNeverInventsAnotherScreenOrAdvancesItself(){
        assertEquals(0,ScreenSetupTour.move(0,-1,4))
        assertEquals(1,ScreenSetupTour.move(0,1,4))
        assertEquals(2,ScreenSetupTour.move(2,0,4))
        assertEquals(3,ScreenSetupTour.move(3,1,4))
        assertEquals(3,ScreenSetupTour.stepIndex(Long.MAX_VALUE,4))
        assertEquals(1f,ScreenSetupTour.progress(16000,16000),0f)
    }
    @Test fun captionsStayWithTheirContextAcrossTheIndividualActions(){
        assertEquals(ScreenSetupTour.caption(ScreenSetupStep.ACCESSIBILITY),ScreenSetupTour.caption(ScreenSetupStep.APPS))
        assertEquals("사용 안 함의 오른쪽 버튼을 눌러주세요",ScreenSetupTour.caption(ScreenSetupStep.SERVICE))
        assertEquals("허용을 눌러주세요",ScreenSetupTour.caption(ScreenSetupStep.CONFIRM))
    }
    @Test fun compactConfirmationKeepsTheWindowAndNamesTheRealAction(){
        assertEquals("허용 누르기",ScreenSetupTour.compactInstruction(ScreenSetupStep.CONFIRM))
        assertEquals(3,ScreenSetupTour.move(3,1,4))
    }
    @Test fun manualGuideExplainsSwitchAndConfirmationBeforeTheProtectedPopup(){
        assertEquals(listOf(ScreenSetupStep.ACCESSIBILITY,ScreenSetupStep.APPS,ScreenSetupStep.CONFIRM),ScreenSetupTour.manualSteps(false))
        assertEquals(listOf(ScreenSetupStep.CONFIRM),ScreenSetupTour.manualSteps(true))
    }
}
