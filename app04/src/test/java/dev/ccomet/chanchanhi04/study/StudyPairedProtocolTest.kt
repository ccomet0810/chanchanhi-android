package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyPairedProtocolTest {
    private val rules=StudyRules(JSONObject(javaClass.getResource("/pilot-7.json")!!.readText()))
    private fun events()=JSONArray(javaClass.getResource("/study-paired.json")!!.readText()).let{a->(0 until a.length()).map(a::getJSONObject)}
    private fun at(count:Int)=events().take(count).fold(StudyState()){s,e->rules.reduce(s,e.getString("kind"),e.getJSONObject("data"))}
    @Test fun pairedQuestionsUseTheSameFieldsAndIdenticalResponseAnchors(){
        val pairs=rules.template.getJSONArray("comparisonPairs");assertEquals(4,pairs.length())
        val ids=mutableSetOf<String>()
        repeat(pairs.length()){i->
            val p=pairs.getJSONObject(i);assertTrue(ids.add(p.getString("id")))
            val after=rules.questions("post").single{it.getString("id")==p.getString("afterField")}
            val before=if(p.getString("beforeSection")=="mission_pre")rules.strings(rules.mission.getJSONArray("confidenceChoices"))
                else rules.options(rules.questions("baseline").single{it.getString("id")==p.getString("beforeField")})
            assertEquals(before,rules.options(after));assertTrue(after.getString("text").startsWith("찬찬히의 도움을 받으면"))
        }
        assertEquals(setOf("task_confidence","find","continue","recover"),ids)
        assertFalse(rules.template.getJSONArray("pairedItems").toString().contains("difficulty"))
    }
    @Test fun onlyKnowledgeAndConfidenceAreRequiredAndNoReasonIsAccepted(){
        val state=at(5);assertTrue(rules.usesReadinessSurvey(state));assertFalse(rules.requiresMissionReason(state))
        assertEquals(2,StudyNavigation.pages(StudyPhase.MISSION_PRE,rules.template,true))
        val request=events()[5].getJSONObject("data")
        assertEquals(StudyPhase.TASK,rules.reduce(state,"mission_pre_submitted",request).phase)
        assertThrows(Exception::class.java){rules.reduce(state,"mission_pre_submitted",JSONObject(request.toString()).put("reason","old reason"))}
        assertThrows(Exception::class.java){rules.reduce(at(4),"mission_pre_submitted",request)}
        assertEquals(StudyPhase.COMPLETE,at(9).phase);assertNull(at(9).missionReason)
    }
    @Test fun wordingAndPageCountsFollowTheRemovedSubjectiveQuestion(){
        assertEquals("옆 버튼을 길게 눌렀을 때 실행되는 기본 디지털 어시스턴트를 변경하는 방법을 알고 있나요?",rules.defaultPreset()!!.getJSONObject("knowledge").getString("question"))
        assertEquals("",rules.defaultPreset()!!.getJSONObject("knowledge").getString("description"))
        assertEquals("혼자서 기본 디지털 어시스턴트를 찬찬히 서비스로 변경하실 수 있을 것 같나요?",rules.mission.getString("confidenceQuestion"))
        assertEquals(12,SurveyProgressInfo.forScreen(StudyPhase.TASK,0,rules.template)!!.current)
        assertEquals(SurveyProgressInfo("설문 조사",19,19),SurveyProgressInfo.forScreen(StudyPhase.COMPLETE,0,rules.template))
    }
}
