package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class SetupRequirementTest{
    @Test fun onlyAssistantCanUseAiAndCompletionStillRequiresActualCapabilities(){
        val none=SetupSnapshot(false,false,false)
        SetupRequirement.entries.forEach{assertFalse(it.canUseAi(none))}
        assertFalse(SetupRequirement.SCREEN.canUseAi(SetupSnapshot(true,false,false)))
        assertFalse(SetupRequirement.MICROPHONE.canUseAi(SetupSnapshot(true,false,false)))
        assertTrue(SetupRequirement.ASSISTANT.canUseAi(SetupSnapshot(true,true,false)))
        assertFalse(SetupRequirement.ASSISTANT.canUseAi(SetupSnapshot(true,true,true)))
        assertFalse(SetupSnapshot(true,true,false).complete)
        assertTrue(SetupSnapshot(true,true,true).complete)
        assertFalse(SetupRequirement.MICROPHONE.goal("예시 앱").contains("설정 앱"))
        assertTrue(SetupRequirement.ASSISTANT.goal("실제 앱").contains("실제 앱"))
        assertTrue(SetupRequirement.ASSISTANT.goal("실제 앱").contains("경로 힌트"))
    }
}
