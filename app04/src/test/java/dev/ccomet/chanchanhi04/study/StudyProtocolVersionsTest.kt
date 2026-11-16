package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyProtocolVersionsTest{
    private fun rules(version:String)=StudyRules(JSONObject(javaClass.getResource("/$version.json")!!.readText()))
    private fun events()=JSONArray(javaClass.getResource("/study-flow.json")!!.readText()).let{a->(0 until a.length()).map{JSONObject(a.getJSONObject(it).toString())}}
    @Test fun legacyAnswersStillReplayWithTheirOriginalQuestions(){
        val legacy=rules("pilot-1");val state=events().fold(StudyState()){s,e->legacy.reduce(s,e.getString("kind"),e.getJSONObject("data"))}
        assertEquals(StudyPhase.COMPLETE,state.phase);assertTrue(state.baseline!!.has("experience"))
        assertTrue(legacy.questions("post").first().getString("text").contains("혼자서"))
    }
    @Test fun newQuestionnaireAcceptsFreeOccupationAndRejectsTheRemovedQuestion(){
        val current=rules("pilot-2");val old=events();val consent=old[0].getJSONObject("data").put("templateVersion","pilot-2")
        var state=current.reduce(StudyState(),"consent_given",consent)
        val profile=old[1].getJSONObject("data").put("occupation","도서관 운영")
        state=current.reduce(state,"profile_submitted",profile)
        assertThrows(Exception::class.java){current.reduce(state,"baseline_submitted",old[2].getJSONObject("data"))}
        val baseline=old[2].getJSONObject("data");baseline.remove("experience")
        state=current.reduce(state,"baseline_submitted",baseline)
        val preset=current.defaultPreset()!!
        assertEquals("assistant-setup",preset.getString("scene"));assertFalse(current.questions("baseline").any{it.getString("id")=="experience"})
        assertTrue(current.questions("post").take(4).all{it.getString("text").contains("찬찬히")})
        assertEquals(0,current.template.getJSONArray("pairedItems").length())
        val mission=JSONObject();listOf("id","title","description","goal").forEach{mission.put(it,preset.get(it))}
        state=current.reduce(state,"mission_assigned",mission)
        assertEquals(StudyPhase.MISSION_PRE,state.phase)
        assertThrows(Exception::class.java){current.reduce(StudyState(),"consent_given",old[0].getJSONObject("data").put("templateVersion","pilot-1"))}
    }
}
