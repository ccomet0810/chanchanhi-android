package dev.ccomet.chanchanhi04.study

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MissionKnowledgeTest{
    private val rules=StudyRules(JSONObject(javaClass.classLoader!!.getResource("pilot-1.json")!!.readText()))
    private val preset=rules.defaultPreset()!!
    private fun assigned()=rules.reduce(StudyState(phase=StudyPhase.MISSION_PRE),"mission_assigned",JSONObject().put("id",preset.getString("id")).put("title",preset.getString("title")).put("description",preset.getString("description")).put("goal",preset.getString("goal")))
    private fun answer(value:String="known",method:String="설정에서 권한을 확인한다")=JSONObject().put("missionId",preset.getString("id")).put("answer",value).put("method",method)
    @Test fun methodKnowledgeIsIndependentOfConfidenceAndCorrectionsRemainPossibleBeforeStarting(){
        var state=rules.reduce(assigned(),"mission_knowledge_answered",answer())
        assertEquals("known",state.missionKnowledge);assertNull(state.confidence)
        state=rules.reduce(state,"mission_knowledge_answered",answer("unknown",""))
        assertEquals("unknown",state.missionKnowledge);assertEquals("",state.missionMethod)
        state=rules.reduce(state,"mission_pre_submitted",JSONObject().put("missionId",preset.getString("id")).put("confidence",2))
        assertEquals(StudyPhase.TASK,state.phase);assertEquals("unknown",state.missionKnowledge)
        assertThrows(IllegalArgumentException::class.java){rules.reduce(state,"mission_knowledge_answered",answer())}
    }
    @Test fun invalidAnswersWrongMissionAndOversizedMethodsAreRejected(){
        assertThrows(IllegalArgumentException::class.java){rules.reduce(assigned(),"mission_knowledge_answered",answer("unexpected"))}
        assertThrows(IllegalArgumentException::class.java){rules.reduce(assigned(),"mission_knowledge_answered",answer().put("missionId","different-mission"))}
        assertThrows(IllegalArgumentException::class.java){rules.reduce(assigned(),"mission_knowledge_answered",answer(method="가".repeat(1001)))}
    }
}
