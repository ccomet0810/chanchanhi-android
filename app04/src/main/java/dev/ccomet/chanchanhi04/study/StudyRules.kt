package dev.ccomet.chanchanhi04.study

import org.json.JSONArray
import org.json.JSONObject

enum class StudyPhase(val title:String) { INTRO("온보딩"), PROFILE("기본정보 입력"), BASELINE("사전 설문"), MISSION_PRE("미션 준비"), TASK("미션 수행"), POST("사후 설문"), COMPLETE("완료"), WITHDRAWN("중단") }
data class StudyState(val phase:StudyPhase=StudyPhase.INTRO,val participantId:String?=null,val runId:String?=null,
    val captureConsent:Boolean=false,val profile:JSONObject?=null,val baseline:JSONObject?=null,val confidence:Int?=null,
    val sessions:List<String> = emptyList(),val outcome:String?=null,val post:JSONObject?=null,val completionSeen:Boolean=false,val mission:JSONObject?=null,
    val profileAnswers:JSONObject?=null,val baselineAnswers:JSONObject?=null,val postAnswers:JSONObject?=null,val testFixture:Boolean=false,val uiEpoch:Long=0,val resultRequested:Boolean=false,
    val missionKnowledge:String?=null,val missionMethod:String?=null,val recordsConsent:Boolean=true,
    val initialAnswers:JSONObject=JSONObject(),val answerHistory:JSONArray=JSONArray(),val missionReason:String?=null)

/** The same versioned questionnaire is bundled on Android and the server. No app-button routes here. */
class StudyRules(val template:JSONObject) {
    val version=template.getString("version")
    val mission get()=template.getJSONObject("mission")
    fun missionFor(state:StudyState):JSONObject=JSONObject(mission.toString()).also{out->state.mission?.let{m->m.keys().forEach{k->out.put(k,m.get(k))}}}
    fun presetFor(state:StudyState):JSONObject?=presets().firstOrNull{it.getString("id")==state.mission?.optString("id")}
    fun defaultPreset():JSONObject?=presets().firstOrNull{it.getString("id")==template.optString("defaultMissionPreset")}
    fun usesReadinessSurvey(state:StudyState)=(mission.optString("preparation")=="knowledge-confidence"||mission.optString("reasonQuestion").isNotBlank())&&presetFor(state)!=null
    fun requiresMissionReason(state:StudyState)=usesReadinessSurvey(state)&&mission.optString("reasonQuestion").isNotBlank()
    private fun presets():List<JSONObject> = template.optJSONArray("missionPresets")?.let{a->(0 until a.length()).map(a::getJSONObject)}?:emptyList()
    fun questions(section:String):List<JSONObject> = template.getJSONArray(section).let{a->(0 until a.length()).map(a::getJSONObject)}
    fun options(question:JSONObject):List<String> = strings(question.optJSONArray("choices")?:template.getJSONArray("likert"))
    fun strings(a:JSONArray)=(0 until a.length()).map(a::getString)
    private fun id(value:String)=value.matches(Regex("[A-Za-z0-9-]{8,80}"))
    private fun reason(value:Any):String {
        require(value is String&&value==value.trim()&&value.length in 1..1000&&value.none{it.isISOControl()&&it !in "\n\r\t"}){"응답한 이유를 적어주세요."}
        return value
    }
    private fun profileField(field:String,value:Any){when(field){
        "name"->require(value is String&&value==value.trim()&&value.length in 1..40&&value.none{it.isISOControl()}&&'@' !in value){"이름을 확인해주세요."}
        "age"->require(value is Number&&value.toDouble()==value.toInt().toDouble()&&value.toInt() in 1..120){"나이는 1~120 사이로 입력해주세요."}
        "gender"->require(value in strings(template.getJSONArray("genders"))){"성별을 선택해주세요."}
        "occupation"->require(if(template.optString("occupationInput")=="text")value is String&&value==value.trim()&&value.length in 1..80&&value.none{it.isISOControl()} else value in strings(template.getJSONArray("occupations"))){"직업을 입력해주세요."}
        else->error("알 수 없는 항목이에요.")
    }}
    private fun answer(section:String,key:String,value:Any){val q=questions(section).singleOrNull{it.getString("id")==key}?:error("알 수 없는 질문이에요.");require(value is Number&&value.toDouble()==value.toInt().toDouble()&&value.toInt() in 1..options(q).size){"답변을 선택해주세요."}}
    fun answers(section:String,value:JSONObject){
        val expected=questions(section);require(value.keys().asSequence().toSet()==expected.map{it.getString("id")}.toSet()){"모든 질문에 답해주세요."}
        expected.forEach{q->answer(section,q.getString("id"),value.get(q.getString("id")))}
    }
    private fun responseValues(s:StudyState)=JSONObject().apply{
        if(s.runId!=null)put("consent",JSONObject().put("records",s.recordsConsent).put("captures",s.captureConsent))
        (s.profile?:s.profileAnswers)?.let{put("profile",it)};(s.baseline?:s.baselineAnswers)?.let{put("baseline",it)};(s.post?:s.postAnswers)?.let{put("post",it)}
        val pre=JSONObject();s.missionKnowledge?.let{pre.put("knowledge",it);pre.put("method",s.missionMethod.orEmpty())};s.confidence?.let{pre.put("confidence",it)};s.missionReason?.let{pre.put("reason",it)};if(pre.length()>0)put("mission_pre",pre)
        s.outcome?.let{put("task",JSONObject().put("outcome",it))}
    }
    /** Derived audit state is replayable; the timestamped original events remain untouched. */
    fun reduce(state:StudyState,kind:String,data:JSONObject):StudyState {
        val next=reduceEvent(state,kind,data);val before=responseValues(state);val after=responseValues(next)
        val initial=JSONObject(state.initialAnswers.toString());val history=JSONArray(state.answerHistory.toString())
        after.keys().forEach{section->val values=after.getJSONObject(section);val first=initial.optJSONObject(section)?:JSONObject().also{initial.put(section,it)}
            values.keys().forEach{field->val value=values.get(field);val prior=before.optJSONObject(section)?.opt(field)
                if(!first.has(field))first.put(field,value)
                if(prior!=null&&prior!=value)history.put(JSONObject().put("section",section).put("field",field).put("before",prior).put("after",value).put("eventKind",kind))
            }
        }
        return next.copy(initialAnswers=initial,answerHistory=history)
    }
    private fun reduceEvent(state:StudyState,kind:String,data:JSONObject):StudyState {
        require(kind=="consent_given"||state.phase!=StudyPhase.INTRO){"먼저 참여 동의를 확인해주세요."}
        return when(kind){
            "consent_given"->{
                require(state.phase==StudyPhase.INTRO&&data.getString("templateVersion")==version&&data.getString("consentVersion")==template.getString("consentVersion")&&data.getBoolean("records"))
                require(id(data.getString("participantId"))&&id(data.getString("runId")))
                StudyState(StudyPhase.PROFILE,data.getString("participantId"),data.getString("runId"),data.getBoolean("captures"),testFixture=data.getBoolean("testFixture"),uiEpoch=state.uiEpoch)
            }
            "answer_revised"->{
                require(state.phase !in setOf(StudyPhase.INTRO,StudyPhase.WITHDRAWN)&&data.keys().asSequence().toSet()==setOf("section","field","previous","value"))
                val section=data.getString("section");val field=data.getString("field");val value=data.get("value")
                val old=responseValues(state).optJSONObject(section)?.opt(field)?:error("아직 제출하지 않은 답변이에요.")
                require(old==data.get("previous")&&old!=value){"기존 답변이 바뀌었어요. 다시 확인해주세요."}
                fun updated(current:JSONObject?)=JSONObject(current!!.toString()).put(field,value)
                when(section){
                    "profile"->{require(state.profile!=null);profileField(field,value);state.copy(profile=updated(state.profile),profileAnswers=updated(state.profile))}
                    "baseline"->{require(state.baseline!=null);answer(section,field,value);state.copy(baseline=updated(state.baseline),baselineAnswers=updated(state.baseline))}
                    "post"->{require(state.post!=null);answer(section,field,value);state.copy(post=updated(state.post),postAnswers=updated(state.post))}
                    "mission_pre"->{require(state.mission!=null);when(field){
                        "confidence"->{require(state.confidence!=null&&value is Number&&value.toDouble()==value.toInt().toDouble()&&value.toInt() in 1..5);state.copy(confidence=value.toInt())}
                        "reason"->{require(requiresMissionReason(state)&&state.missionReason!=null);state.copy(missionReason=reason(value))}
                        else->error("미션 방법은 함께 수정해주세요.")
                    }}
                    "task"->{require(field=="outcome"&&state.outcome!=null&&state.sessions.isNotEmpty()&&value in strings(mission.getJSONArray("outcomes")));state.copy(outcome=value as String)}
                    else->error("수정할 항목을 확인해주세요.")
                }
            }
            "mission_knowledge_revised"->{
                require(state.phase !in setOf(StudyPhase.INTRO,StudyPhase.WITHDRAWN)&&state.mission!=null&&data.keys().asSequence().toSet()==setOf("missionId","answer","method","previousAnswer","previousMethod"))
                require((if(data.isNull("previousAnswer"))null else data.getString("previousAnswer"))==state.missionKnowledge&&data.getString("previousMethod")==state.missionMethod.orEmpty())
                require(data.getString("answer")!="unknown"||data.getString("method").isEmpty())
                val validated=reduceEvent(state.copy(phase=StudyPhase.MISSION_PRE),"mission_knowledge_answered",JSONObject().put("missionId",data.get("missionId")).put("answer",data.get("answer")).put("method",data.get("method")))
                state.copy(missionKnowledge=validated.missionKnowledge,missionMethod=validated.missionMethod)
            }
            "consent_revised"->{
                require(state.phase !in setOf(StudyPhase.INTRO,StudyPhase.WITHDRAWN)&&data.keys().asSequence().toSet()==setOf("records","captures","previousRecords","previousCaptures"))
                require(data.get("records") is Boolean&&data.get("captures") is Boolean&&data.get("previousRecords")==state.recordsConsent&&data.get("previousCaptures")==state.captureConsent)
                require(data.getBoolean("records")||!data.getBoolean("captures"))
                state.copy(recordsConsent=data.getBoolean("records"),captureConsent=data.getBoolean("captures"),phase=if(data.getBoolean("records"))state.phase else StudyPhase.WITHDRAWN)
            }
            "profile_submitted"->{
                require(state.phase==StudyPhase.PROFILE)
                require(data.keys().asSequence().toSet()==setOf("name","age","gender","occupation"))
                data.keys().forEach{k->profileField(k,data.get(k))}
                state.copy(phase=StudyPhase.BASELINE,profile=JSONObject(data.toString()))
            }
            "profile_answered"->{require(state.phase==StudyPhase.PROFILE&&data.keys().asSequence().toSet()==setOf("field","value"));val field=data.getString("field");profileField(field,data.get("value"));state.copy(profileAnswers=JSONObject(state.profileAnswers?.toString()?:"{}").put(field,data.get("value")))}
            "baseline_answered","post_answered"->{
                val section=if(kind=="baseline_answered")"baseline"else"post";require(state.phase==if(section=="baseline")StudyPhase.BASELINE else StudyPhase.POST)
                require(data.keys().asSequence().toSet()==setOf("questionId","value"));val key=data.getString("questionId");answer(section,key,data.get("value"))
                if(section=="baseline")state.copy(baselineAnswers=JSONObject(state.baselineAnswers?.toString()?:"{}").put(key,data.get("value")))else state.copy(postAnswers=JSONObject(state.postAnswers?.toString()?:"{}").put(key,data.get("value")))
            }
            "baseline_submitted"->{require(state.phase==StudyPhase.BASELINE);answers("baseline",data);state.copy(phase=StudyPhase.MISSION_PRE,baseline=JSONObject(data.toString()))}
            "mission_assigned"->{
                require(state.phase==StudyPhase.MISSION_PRE&&data.keys().asSequence().toSet()==setOf("id","title","description","goal"))
                require(id(data.getString("id"))&&data.getString("title").trim().length in 1..300&&data.getString("description").length<=1000&&data.getString("goal").trim().length in 1..2000)
                state.copy(mission=JSONObject(data.toString()),missionKnowledge=null,missionMethod=null,missionReason=null)
            }
            "mission_knowledge_answered"->{
                require(state.phase==StudyPhase.MISSION_PRE&&data.keys().asSequence().toSet()==setOf("missionId","answer","method")&&data.getString("missionId")==state.mission?.getString("id"))
                val preset=presetFor(state)?:error("미션을 확인해주세요.")
                val choices=preset.getJSONObject("knowledge").getJSONArray("choices")
                require(data.getString("answer") in (0 until choices.length()).map{choices.getJSONObject(it).getString("id")}&&data.getString("method").length<=1000)
                require(version !in setOf("pilot-4","pilot-5","pilot-6")||data.getString("answer")!="unknown"||data.getString("method").isEmpty())
                require(!usesReadinessSurvey(state)||data.getString("method").isEmpty())
                state.copy(missionKnowledge=data.getString("answer"),missionMethod=data.getString("method"))
            }
            "mission_pre_submitted"->{
                require(state.phase==StudyPhase.MISSION_PRE&&state.mission!=null&&data.getString("missionId")==state.mission.getString("id"))
                val value=data.get("confidence");require(value is Number&&value.toDouble()==value.toInt().toDouble()&&value.toInt() in 1..5)
                val readiness=usesReadinessSurvey(state)
                val needsReason=requiresMissionReason(state)
                require(data.keys().asSequence().toSet()==if(needsReason)setOf("missionId","confidence","reason")else setOf("missionId","confidence"))
                if(readiness)require(state.missionKnowledge!=null){"설정 방법을 알고 있는지 답해주세요."}
                state.copy(phase=StudyPhase.TASK,confidence=value.toInt(),missionReason=if(needsReason)reason(data.get("reason"))else null)
            }
            "guidance_linked"->{
                require(state.phase==StudyPhase.TASK&&data.getString("missionId")==state.mission?.getString("id")&&id(data.getString("sessionId")))
                require(data.getString("sessionId") !in state.sessions){"이미 연결한 안내예요."}
                state.copy(sessions=state.sessions+data.getString("sessionId"))
            }
            "mission_result_submitted"->{
                require(state.phase==StudyPhase.TASK&&state.sessions.isNotEmpty()&&data.getString("missionId")==state.mission?.getString("id"))
                require(data.getString("outcome") in strings(mission.getJSONArray("outcomes")))
                state.copy(phase=StudyPhase.POST,outcome=data.getString("outcome"))
            }
            "post_submitted"->{require(state.phase==StudyPhase.POST);answers("post",data);state.copy(phase=StudyPhase.COMPLETE,post=JSONObject(data.toString()))}
            "withdrawn","admin_closed"->{require(state.phase !in setOf(StudyPhase.COMPLETE,StudyPhase.WITHDRAWN));state.copy(phase=StudyPhase.WITHDRAWN)}
            else->error("알 수 없는 실험 기록이에요.")
        }
    }
    fun canGuide(state:StudyState,goal:String)=state.phase==StudyPhase.COMPLETE||state.phase==StudyPhase.TASK&&state.mission!=null&&goal.trim()==state.mission.getString("goal")
    fun association(state:StudyState):Map<String,Any?>?=if(state.phase==StudyPhase.TASK)mapOf("participantId" to state.participantId,"runId" to state.runId,"missionId" to state.mission?.getString("id"),"templateVersion" to version)else null
}
