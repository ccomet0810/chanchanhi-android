package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyReadinessProtocolTest {
    private val rules=StudyRules(JSONObject(javaClass.getResource("/pilot-6.json")!!.readText()))
    private fun events()=JSONArray(javaClass.getResource("/study-readiness.json")!!.readText()).let{a->(0 until a.length()).map(a::getJSONObject)}
    private fun at(count:Int)=events().take(count).fold(StudyState()){s,e->rules.reduce(s,e.getString("kind"),e.getJSONObject("data"))}
    @Test fun sharedServerFixtureRetainsKnowledgeConfidenceReasonAndPostAnswers(){
        val done=at(9)
        assertEquals(StudyPhase.COMPLETE,done.phase);assertEquals("partial",done.missionKnowledge)
        assertEquals(2,done.confidence);assertEquals(events()[5].getJSONObject("data").getString("reason"),done.missionReason)
        assertEquals(done.missionReason,done.initialAnswers.getJSONObject("mission_pre").getString("reason"))
        assertEquals(rules.questions("post").map{it.getString("id")}.toSet(),done.post!!.keys().asSequence().toSet())
    }
    @Test fun knowledgeAndANonemptyBoundedReasonAreRequiredBeforeStartingTheMission(){
        val request=JSONObject(events()[5].getJSONObject("data").toString())
        assertThrows(Exception::class.java){rules.reduce(at(4),"mission_pre_submitted",request)}
        for(value in listOf("", " ","가".repeat(1001),"앞\u0000뒤")){
            assertThrows(Exception::class.java){rules.reduce(at(5),"mission_pre_submitted",JSONObject(request.toString()).put("reason",value))}
        }
        val missing=JSONObject(request.toString()).apply{remove("reason")}
        assertThrows(Exception::class.java){rules.reduce(at(5),"mission_pre_submitted",missing)}
        assertEquals(StudyPhase.TASK,rules.reduce(at(5),"mission_pre_submitted",request).phase)
    }
    @Test fun reasonRevisionsKeepTheFirstAnswerAndAppendHistoryWithoutRestartingTheTask(){
        val before=at(9)
        val revised=rules.reduce(before,"answer_revised",JSONObject().put("section","mission_pre").put("field","reason").put("previous",before.missionReason).put("value","설정해본 경험이 적어요."))
        assertEquals(StudyPhase.COMPLETE,revised.phase);assertEquals(before.sessions,revised.sessions)
        assertEquals(before.missionReason,revised.initialAnswers.getJSONObject("mission_pre").getString("reason"))
        assertEquals("reason",revised.answerHistory.getJSONObject(0).getString("field"))
    }
    @Test fun screenNumbersTenThroughThirteenAndTheFinalThankYouAreCalculated(){
        assertEquals(10,SurveyProgressInfo.forScreen(StudyPhase.MISSION_PRE,0,rules.template)!!.current)
        assertEquals(11,SurveyProgressInfo.forScreen(StudyPhase.MISSION_PRE,1,rules.template)!!.current)
        assertEquals(12,SurveyProgressInfo.forScreen(StudyPhase.MISSION_PRE,2,rules.template)!!.current)
        assertEquals(13,SurveyProgressInfo.forScreen(StudyPhase.TASK,0,rules.template)!!.current)
        assertEquals(SurveyProgressInfo("설문 조사",20,20),SurveyProgressInfo.forScreen(StudyPhase.COMPLETE,0,rules.template))
        assertFalse(rules.defaultPreset()!!.getString("description").contains("마이크"))
        assertTrue(rules.questions("post").all{it.getString("text").contains("찬찬히")})
    }
}
