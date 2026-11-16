package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyRulesTest {
    private val rules=StudyRules(JSONObject(javaClass.getResource("/pilot-1.json")!!.readText()))
    private val events=JSONArray(javaClass.getResource("/study-flow.json")!!.readText()).let{a->(0 until a.length()).map(a::getJSONObject)}
    private fun at(count:Int)=events.take(count).fold(StudyState()){s,e->rules.reduce(s,e.getString("kind"),e.getJSONObject("data"))}
    private fun fails(block:()->Unit){assertThrows(Exception::class.java,block)}
    @Test fun sharedContractCompletesOnlyAfterEveryRequiredSection(){
        assertEquals("",rules.mission.getString("goal"));assertEquals("",rules.mission.getString("title"))
        assertNull(at(3).mission);fails{rules.reduce(at(3),"mission_pre_submitted",events[4].getJSONObject("data"))}
        assertEquals(StudyPhase.COMPLETE,at(8).phase);assertEquals(listOf("public-guidance-0001"),at(8).sessions)
        assertEquals(4,at(8).post!!.getInt("find"));assertTrue(at(8).testFixture)
    }
    @Test fun entryGateBlocksVoiceOrRequestsUntilTheAssignedMissionAndPostSurvey(){
        val goal=events[3].getJSONObject("data").getString("goal")
        (0..4).forEach{assertFalse(rules.canGuide(at(it),goal))}
        assertTrue(rules.canGuide(at(5),goal));assertFalse(rules.canGuide(at(5),"다른 요청"))
        assertFalse(rules.canGuide(at(7),goal));assertTrue(rules.canGuide(at(8),"새 요청"))
        assertNull(rules.association(at(1)));assertEquals("public-mission-0001",rules.association(at(5))!!["missionId"])
        assertFalse(rules.association(at(5))!!.values.contains("공개 시험 참여자"))
    }
    @Test fun incompleteInvalidOrOutOfOrderAnswersCannotAdvance(){
        fails{rules.reduce(StudyState(),"profile_submitted",events[1].getJSONObject("data"))}
        fails{rules.reduce(at(1),"profile_submitted",JSONObject(events[1].getJSONObject("data").toString()).put("age",0))}
        fails{rules.reduce(at(2),"baseline_submitted",JSONObject().put("find",5))}
        fails{rules.reduce(at(2),"baseline_submitted",JSONObject(events[2].getJSONObject("data").toString()).put("find",6))}
        fails{rules.reduce(at(5),"mission_result_submitted",events[6].getJSONObject("data"))}
        fails{rules.reduce(at(6),"post_submitted",events[7].getJSONObject("data"))}
        fails{rules.reduce(at(7),"post_submitted",JSONObject().put("task_confidence",5))}
        fails{rules.reduce(at(8),"post_submitted",events[7].getJSONObject("data"))}
    }
    @Test fun partialCorrectionsAndWithdrawalAreRetainedWithoutAutoCompleting(){
        var s=rules.reduce(at(1),"profile_answered",JSONObject().put("field","name").put("value","공개 첫 이름"))
        s=rules.reduce(s,"profile_answered",JSONObject().put("field","name").put("value","공개 수정 이름"))
        assertEquals(StudyPhase.PROFILE,s.phase);assertEquals("공개 수정 이름",s.profileAnswers!!.getString("name"))
        s=rules.reduce(at(2),"baseline_answered",JSONObject().put("questionId","find").put("value",3))
        assertNull(s.baseline);assertEquals(3,s.baselineAnswers!!.getInt("find"))
        val withdrawn=rules.reduce(s,"withdrawn",JSONObject());assertEquals(StudyPhase.WITHDRAWN,withdrawn.phase)
        assertEquals(3,withdrawn.baselineAnswers!!.getInt("find"));fails{rules.reduce(withdrawn,"baseline_submitted",events[2].getJSONObject("data"))}
    }
    @Test fun missionCannotChangeAfterTheParticipantRatesIt(){
        val newMission=JSONObject(events[3].getJSONObject("data").toString()).put("id","another-public-mission")
        val s=rules.reduce(at(4),"mission_assigned",newMission)
        fails{rules.reduce(s,"mission_pre_submitted",events[4].getJSONObject("data"))}
        fails{rules.reduce(at(5),"mission_assigned",newMission)}
        fails{rules.reduce(at(3),"mission_assigned",JSONObject(newMission.toString()).put("goal",""))}
    }
}
