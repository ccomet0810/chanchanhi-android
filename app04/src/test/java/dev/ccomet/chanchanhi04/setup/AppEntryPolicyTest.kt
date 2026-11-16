package dev.ccomet.chanchanhi04.setup

import dev.ccomet.chanchanhi04.study.*
import org.junit.Assert.*
import org.junit.Test

class AppEntryPolicyTest {
    @Test fun freshTestInstallShowsStudyBeforeRegistration(){assertTrue(AppEntryPolicy.showStudy(false,StudyState(),true));assertFalse(AppEntryPolicy.showStudy(false,StudyState(),false))}
    @Test fun enrolledOrExistingParticipantsKeepTheirConsentAndSurveyFlow(){
        assertTrue(AppEntryPolicy.showStudy(true,StudyState()))
        assertTrue(AppEntryPolicy.showStudy(false,StudyState(phase=StudyPhase.BASELINE,participantId="existing")))
    }
    @Test fun completedParticipantsCanUseGeneralGuidance(){assertFalse(AppEntryPolicy.showStudy(true,StudyState(phase=StudyPhase.COMPLETE,participantId="existing",completionSeen=true)))}
}
