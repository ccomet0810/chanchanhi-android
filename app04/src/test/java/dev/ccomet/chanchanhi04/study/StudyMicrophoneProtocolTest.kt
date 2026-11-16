package dev.ccomet.chanchanhi04.study

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyMicrophoneProtocolTest {
    private fun rules(version:String="pilot-5")=StudyRules(JSONObject(javaClass.getResource("/$version.json")!!.readText()))
    @Test fun microphoneIsPreparationAfterScreenSetupAndMissionMeasuresAssistantInvocation() {
        val current=rules()
        val onboarding=current.template.getJSONArray("onboarding")
        assertEquals(listOf("introduction","setup","microphone"),(0 until onboarding.length()).map{onboarding.getJSONObject(it).getString("id")})
        assertEquals(3,StudyNavigation.pages(StudyPhase.INTRO,current.template,true))
        val preset=current.defaultPreset()!!
        assertEquals("assistant-setup",preset.getString("scene"))
        assertEquals("scenario-chanchanhi-assistant-5",preset.getString("id"))
        assertFalse(preset.getJSONObject("knowledge").getString("question").contains("마이크"))
        assertTrue(preset.getString("goal").contains("실제로 열린 호출 이벤트"))
        assertTrue(preset.getString("goal").contains("마이크 권한을 변경하도록 안내하지 마세요"))
        assertTrue(current.questions("post").first().getString("text").contains("옆 버튼"))
        assertEquals(2,rules("pilot-4").template.getJSONArray("onboarding").length())
        assertTrue(rules("pilot-4").defaultPreset()!!.getJSONObject("knowledge").getString("question").contains("마이크 권한"))
    }
    @Test fun microphoneChoiceDoesNotBypassConsentOrRequiredSurveyPhases() {
        val current=rules()
        val consent=JSONObject().put("templateVersion",current.version).put("consentVersion",current.template.getString("consentVersion"))
            .put("records",true).put("captures",false).put("participantId","public-person-0005").put("runId","public-run-0005").put("testFixture",true)
        assertThrows(Exception::class.java){current.reduce(StudyState(),"mission_pre_submitted",JSONObject())}
        val profile=current.reduce(StudyState(),"consent_given",consent)
        assertEquals(StudyPhase.PROFILE,profile.phase)
        assertFalse(current.canGuide(profile,current.defaultPreset()!!.getString("goal")))
        assertThrows(Exception::class.java){current.reduce(profile,"mission_assigned",JSONObject())}
    }
    @Test fun pilot5UnknownMethodCannotKeepAStaleWrittenMethod() {
        val current=rules();val preset=current.defaultPreset()!!
        val mission=JSONObject();listOf("id","title","description","goal").forEach{mission.put(it,preset.get(it))}
        val state=StudyState(phase=StudyPhase.MISSION_PRE,mission=mission)
        val answer=JSONObject().put("missionId",preset.getString("id")).put("answer","unknown").put("method","이전 방법")
        assertThrows(Exception::class.java){current.reduce(state,"mission_knowledge_answered",answer)}
        assertEquals("",current.reduce(state,"mission_knowledge_answered",answer.put("method","")).missionMethod)
    }
}
