package dev.ccomet.chanchanhi04.study
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class StudyNavigationTest{
    @Test fun mergedPermissionPagesPreserveLegacyDraftCursors(){
        val latest=JSONObject(javaClass.getResource("/pilot-5.json")!!.readText())
        assertEquals(2,StudyNavigation.onboarding(latest).length())
        assertEquals(1,StudyNavigation.introIndex(JSONObject().put("_index",2),latest))
        assertEquals(2,StudyNavigation.introIndex(JSONObject().put("_index",3),latest))
        assertEquals(3,StudyNavigation.introDraftIndex(2,latest))
        assertEquals(1,StudyNavigation.introDraftIndex(1,latest))
        assertEquals(3,latest.getJSONArray("onboarding").length())
    }
    private val template=JSONObject(javaClass.getResource("/pilot-3.json")!!.readText())
    @Test fun firstProfileMissionAndTaskAllHaveAPreviousPage(){
        assertEquals(StudyPhase.INTRO,StudyNavigation.previous(StudyPhase.PROFILE))
        assertEquals(StudyPhase.BASELINE,StudyNavigation.previous(StudyPhase.MISSION_PRE))
        assertEquals(StudyPhase.MISSION_PRE,StudyNavigation.previous(StudyPhase.TASK))
        assertNull(StudyNavigation.previous(StudyPhase.INTRO))
        assertEquals(StudyPhase.PROFILE,StudyNavigation.next(StudyPhase.INTRO))
        assertEquals(3,StudyNavigation.pages(StudyPhase.MISSION_PRE,template,true))
    }
    @Test fun newMissionAsksAboutSettingsRatherThanAutomaticPermissionPrompts(){
        val rules=StudyRules(template);val preset=rules.defaultPreset()!!
        assertEquals("pilot-3",rules.version);assertEquals("휴대폰 설정 미션",preset.getString("title"))
        assertTrue(preset.getString("description").contains("설정 앱을 직접"))
        assertTrue(preset.getJSONObject("knowledge").getString("description").contains("자동으로 뜨는 허용 창"))
    }
}
