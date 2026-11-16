package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyRevisionTest{
    private fun fixture(version:String):Pair<StudyRules,StudyState>{
        val rules=StudyRules(JSONObject(javaClass.getResource("/$version.json")!!.readText()))
        val rows=JSONArray(javaClass.getResource("/study-flow.json")!!.readText()).let{a->(0 until a.length()).map{JSONObject(a.getJSONObject(it).toString())}}
        rows[0].getJSONObject("data").put("templateVersion",version)
        if(version!="pilot-1"){
            rows[1].getJSONObject("data").put("occupation","공개 시험");rows[2].getJSONObject("data").remove("experience")
            val preset=rules.defaultPreset()!!;val mission=JSONObject();listOf("id","title","description","goal").forEach{mission.put(it,preset.get(it))};rows[3].put("data",mission)
            listOf(4,5,6).forEach{rows[it].getJSONObject("data").put("missionId",preset.getString("id"))}
        }
        return rules to rows.fold(StudyState()){s,e->rules.reduce(s,e.getString("kind"),e.getJSONObject("data"))}
    }
    private fun revise(rules:StudyRules,s:StudyState,section:String,field:String,previous:Any,value:Any)=rules.reduce(s,"answer_revised",JSONObject().put("section",section).put("field",field).put("previous",previous).put("value",value))
    @Test fun allCohortsKeepOriginalAnswersAndAllowValidatedLateCorrections(){
        for(version in listOf("pilot-1","pilot-2","pilot-3","pilot-4","pilot-5")){
            val (rules,original)=fixture(version)
            var s=revise(rules,original,"baseline","find",original.baseline!!.getInt("find"),5)
            s=revise(rules,s,"profile","age",original.profile!!.getInt("age"),71)
            s=revise(rules,s,"mission_pre","confidence",original.confidence!!,5)
            val outcome=rules.mission.getJSONArray("outcomes").getString(1)
            s=revise(rules,s,"task","outcome",s.outcome!!,outcome)
            s=revise(rules,s,"post","find",s.post!!.getInt("find"),1)
            assertEquals(StudyPhase.COMPLETE,s.phase);assertEquals(original.sessions,s.sessions);assertEquals(original.mission.toString(),s.mission.toString())
            assertEquals(original.baseline!!.getInt("find"),s.initialAnswers.getJSONObject("baseline").getInt("find"));assertEquals(5,s.baseline!!.getInt("find"));assertEquals(5,s.answerHistory.length())
            assertEquals(1,s.post!!.getInt("find"));assertEquals(71,s.profile!!.getInt("age"))
            assertThrows(Exception::class.java){revise(rules,s,"baseline","find",2,4)}
            assertThrows(Exception::class.java){revise(rules,s,"profile","age",71,0)}
            if(version!="pilot-1")assertThrows(Exception::class.java){revise(rules,s,"baseline","experience",3,4)}
        }
    }
    @Test fun knowledgeCorrectionPreservesMissionAndClearsTheDependentMethod(){
        val (rules,base)=fixture("pilot-3");val s=base.copy(missionKnowledge="known",missionMethod="공개 방법")
        val data=JSONObject().put("missionId",s.mission!!.getString("id")).put("answer","unknown").put("method","").put("previousAnswer","known").put("previousMethod","공개 방법")
        val next=rules.reduce(s,"mission_knowledge_revised",data)
        assertEquals("",next.missionMethod);assertEquals("unknown",next.missionKnowledge);assertEquals(s.phase,next.phase);assertEquals(s.sessions,next.sessions)
        assertThrows(Exception::class.java){rules.reduce(s,"mission_knowledge_revised",JSONObject(data.toString()).put("method","남은 방법"))}
    }
    @Test fun consentChangesAreDurableAndWithdrawalCannotResumeOrEraseAnswers(){
        val (rules,base)=fixture("pilot-4")
        val granted=rules.reduce(base,"consent_revised",JSONObject().put("records",true).put("captures",true).put("previousRecords",true).put("previousCaptures",base.captureConsent))
        val revoked=rules.reduce(granted,"consent_revised",JSONObject().put("records",false).put("captures",false).put("previousRecords",true).put("previousCaptures",true))
        assertEquals(StudyPhase.WITHDRAWN,revoked.phase);assertFalse(revoked.recordsConsent);assertFalse(revoked.captureConsent);assertEquals(base.post.toString(),revoked.post.toString());assertEquals(base.sessions,revoked.sessions)
        assertThrows(Exception::class.java){revise(rules,revoked,"post","find",4,3)}
        assertThrows(Exception::class.java){rules.reduce(revoked,"consent_revised",JSONObject().put("records",true).put("captures",false).put("previousRecords",false).put("previousCaptures",false))}
    }
    @Test fun imageConsentChangesAffectFutureCollectionWithoutBackfillingOldSessions(){
        val rows=mutableListOf<JSONObject>()
        fun add(kind:String,data:JSONObject){rows+=JSONObject().put("seq",rows.size+1).put("kind",kind).put("data",data)}
        add("consent_given",JSONObject().put("records",true).put("captures",false))
        add("guidance_linked",JSONObject().put("sessionId","public-session-one"))
        add("consent_revised",JSONObject().put("records",true).put("captures",true))
        add("guidance_linked",JSONObject().put("sessionId","public-session-two"))
        assertFalse(StudyConsentPolicy.sessionBinding(rows,"public-session-one")!!.getBoolean("captures"))
        assertTrue(StudyConsentPolicy.sessionBinding(rows,"public-session-two")!!.getBoolean("captures"))
        add("consent_revised",JSONObject().put("records",true).put("captures",false))
        assertFalse(StudyConsentPolicy.sessionBinding(rows,"public-session-two")!!.getBoolean("captures"))
        add("consent_revised",JSONObject().put("records",true).put("captures",true))
        assertFalse(StudyConsentPolicy.sessionBinding(rows,"public-session-two")!!.getBoolean("captures"))
        add("guidance_linked",JSONObject().put("sessionId","public-session-three"))
        assertTrue(StudyConsentPolicy.sessionBinding(rows,"public-session-three")!!.getBoolean("captures"))
        add("consent_revised",JSONObject().put("records",false).put("captures",false))
        assertFalse(StudyConsentPolicy.sessionBinding(rows,"public-session-three")!!.getBoolean("records"))
        assertNull(StudyConsentPolicy.sessionBinding(rows,"unknown-session"))
    }
    @Test fun pilot4UsesTheNewWordingAndOlderQuestionFilesStayDistinct(){
        val (rules,_)=fixture("pilot-4");val knowledge=rules.defaultPreset()!!.getJSONObject("knowledge")
        assertEquals("휴대폰 설정 앱에서 마이크 권한과 디지털 어시스턴트 앱을 직접 설정할 수 있나요?",knowledge.getString("question"));assertEquals("",knowledge.getString("description"))
        assertTrue(fixture("pilot-3").first.defaultPreset()!!.getJSONObject("knowledge").getString("description").contains("자동으로"))
    }
}
