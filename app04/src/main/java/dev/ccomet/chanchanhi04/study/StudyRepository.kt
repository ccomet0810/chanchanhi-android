package dev.ccomet.chanchanhi04.study

import android.content.Context
import android.util.AtomicFile
import dev.ccomet.chanchanhi04.research.ResearchConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Small, crash-safe append-only logical journals; identity never enters the AI prompt or trace. */
class StudyRepository(context:Context,private val notifyStored:(()->Unit)?=null){
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("study-1",Context.MODE_PRIVATE)
    private val templates=listOf("pilot-1","pilot-2","pilot-3","pilot-4","pilot-5","pilot-6","pilot-7").associateWith{version->StudyRules(JSONObject(app.assets.open("$version.json").bufferedReader().use{it.readText()}))}
    var rules=templates.getValue("pilot-7");private set
    private val mutable=MutableStateFlow(load())
    val state=mutable.asStateFlow()
    /** Labels instrumentation data; does not bypass a single survey or permission gate. */
    internal var testRun=false
    val problem get()=prefs.getString("problem",null)
    private fun load():StudyState=synchronized(lock){
        val initial=StudyState(uiEpoch=prefs.getLong("uiEpoch",0))
        val current=prefs.getString("active",null)?:return@synchronized initial
        runCatching{val events=read(File(directory(app),"$current.jsonl"));rules=templates.getValue(events.first().getJSONObject("data").getString("templateVersion"));events.fold(initial){s,e->rules.reduce(s,e.getString("kind"),e.getJSONObject("data"))}}
            .map{it.copy(completionSeen=prefs.getBoolean("complete-$current",false),resultRequested=runCatching{JSONObject(prefs.getString("draft-$current-task","{}")!!).optBoolean("_showResult",false)}.getOrDefault(false))}
            .onSuccess{prefs.edit().remove("problem").commit()}.getOrElse{
                prefs.edit().putString("problem","진행 기록을 읽지 못했어요.").commit();StudyState(StudyPhase.WITHDRAWN,runId=current)
            }
    }
    fun consent(captures:Boolean)=synchronized(lock){
        check(ResearchConnection(app).enrolled){"실험 서버 연결을 다시 확인해주세요."}
        require(mutable.value.phase==StudyPhase.INTRO)
        val run=UUID.randomUUID().toString()
        val data=JSONObject().put("templateVersion",rules.version).put("consentVersion",rules.template.getString("consentVersion"))
            .put("records",true).put("captures",captures)
            .put("participantId",UUID.randomUUID().toString()).put("runId",run).put("testFixture",testRun)
        commit("consent_given",data,run)
        ResearchConnection(app).setEnabled(true)
        check(prefs.edit().putString("active",run).remove("problem").remove("draft-null-intro").commit())
    }
    fun submit(kind:String,data:JSONObject)=synchronized(lock){
        val run=mutable.value.runId?:error("먼저 참여를 시작해주세요.");commit(kind,data,run)
        if(kind=="baseline_submitted")ensureDefaultMission()
    }
    fun revise(section:String,field:String,value:Any)=synchronized(lock){
        val s=mutable.value
        val previous=when(section){"profile"->s.profile?.opt(field);"baseline"->s.baseline?.opt(field);"post"->s.post?.opt(field);"mission_pre"->if(field=="reason")s.missionReason else s.confidence;"task"->s.outcome;else->null}?:error("기존 답변을 확인해주세요.")
        if(previous!=value)submit("answer_revised",JSONObject().put("section",section).put("field",field).put("previous",previous).put("value",value))
    }
    fun reviseKnowledge(answer:String,method:String)=synchronized(lock){
        val s=mutable.value
        if(s.missionKnowledge!=answer||s.missionMethod.orEmpty()!=method)submit("mission_knowledge_revised",JSONObject().put("missionId",s.mission!!.getString("id")).put("answer",answer).put("method",method).put("previousAnswer",s.missionKnowledge?:JSONObject.NULL).put("previousMethod",s.missionMethod.orEmpty()))
    }
    fun reviseConsent(records:Boolean,captures:Boolean)=synchronized(lock){
        val s=mutable.value;val images=records&&captures
        if(s.recordsConsent!=records||s.captureConsent!=images)submit("consent_revised",JSONObject().put("records",records).put("captures",images).put("previousRecords",s.recordsConsent).put("previousCaptures",s.captureConsent))
    }
    /** Add a preset to a waiting legacy run too; never replace an assigned or started mission. */
    fun ensureDefaultMission()=synchronized(lock){
        val s=mutable.value
        if(s.phase==StudyPhase.MISSION_PRE&&s.mission==null&&s.runId!=null)rules.defaultPreset()?.let{preset->
            commit("mission_assigned",JSONObject().put("id",preset.getString("id")).put("title",preset.getString("title")).put("description",preset.getString("description")).put("goal",preset.getString("goal")),s.runId)
        }
    }
    private fun commit(kind:String,data:JSONObject,run:String){
        val previous=mutable.value
        val next=rules.reduce(previous,kind,data)
        val file=File(directory(app),"$run.jsonl")
        val events=if(file.exists())read(file)else emptyList()
        val row=JSONObject().put("seq",events.size+1).put("at",System.currentTimeMillis()).put("kind",kind).put("data",data)
        val atomic=AtomicFile(file);val output=atomic.startWrite()
        try{output.write((events+row).joinToString("\n",postfix="\n"){it.toString()}.toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}
        catch(e:Exception){atomic.failWrite(output);throw e}
        mutable.value=next
        if(notifyStored!=null)notifyStored.invoke()else ResearchConnection(app).stored()
    }
    fun linkSession(sessionId:String,goal:String,internalCapabilitySubgoal:Boolean=false):Map<String,Any?>?=synchronized(lock){
        val s=mutable.value
        if(s.phase!=StudyPhase.TASK||!(rules.canGuide(s,goal)||internalCapabilitySubgoal&&rules.presetFor(s)?.optString("scene")=="assistant-setup"))return@synchronized null
        submit("guidance_linked",JSONObject().put("missionId",rules.missionFor(s).getString("id")).put("sessionId",sessionId))
        rules.association(mutable.value)
    }
    fun nextParticipant()=synchronized(lock){
        if(mutable.value.phase !in setOf(StudyPhase.INTRO,StudyPhase.COMPLETE,StudyPhase.WITHDRAWN))submit("admin_closed",JSONObject())
        val epoch=mutable.value.uiEpoch+1
        check(prefs.edit().remove("active").remove("problem").remove("draft-null-intro").putLong("uiEpoch",epoch).commit());rules=templates.getValue("pilot-7");mutable.value=StudyState(uiEpoch=epoch)
    }
    fun draft(section:String):JSONObject {
        val s=mutable.value;val confirmed=when(section){"profile"->s.profile?:s.profileAnswers;"baseline"->s.baseline?:s.baselineAnswers;"post"->s.post?:s.postAnswers;else->null}
        val result=JSONObject(confirmed?.toString()?:"{}")
        if(section=="mission_pre"&&s.missionKnowledge!=null){result.put("knowledge",s.missionKnowledge);result.put("method",s.missionMethod.orEmpty())}
        if(section=="mission_pre"&&s.confidence!=null)result.put("confidence",s.confidence)
        if(section=="mission_pre"&&s.missionReason!=null)result.put("reason",s.missionReason)
        if(section=="task"&&s.outcome!=null)result.put("outcome",s.outcome)
        runCatching{JSONObject(prefs.getString("draft-${s.runId}-$section","{}")!!)}.getOrNull()?.let{draft->draft.keys().forEach{k->result.put(k,draft.get(k))}}
        if(section=="profile"&&result.has("age"))result.put("age",result.get("age").toString())
        return result
    }
    fun saveDraft(section:String,data:JSONObject){prefs.edit().putString("draft-${mutable.value.runId}-$section",data.toString()).commit()}
    fun clearDraft(section:String){prefs.edit().remove("draft-${mutable.value.runId}-$section").apply()}
    fun assignMission(title:String,description:String,goal:String)=synchronized(lock){
        clearDraft("mission_pre")
        submit("mission_assigned",JSONObject().put("id",UUID.randomUUID().toString()).put("title",title.trim()).put("description",description.trim()).put("goal",goal.trim().ifEmpty{title.trim()}))
    }
    fun acknowledgeComplete(){check(mutable.value.phase==StudyPhase.COMPLETE);prefs.edit().putBoolean("complete-${mutable.value.runId}",true).commit();mutable.value=mutable.value.copy(completionSeen=true)}
    fun requestResult(){val s=mutable.value;if(s.phase==StudyPhase.TASK&&s.sessions.isNotEmpty()){saveDraft("task",draft("task").put("_showResult",true));mutable.value=s.copy(resultRequested=true)}}
    companion object {
        private val lock=Any()
        fun directory(context:Context)=File(context.filesDir,"v04-studies").apply{mkdirs()}
        fun read(file:File):List<JSONObject> = synchronized(lock){AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use{input->input.readLines().filter{it.isNotBlank()}.map(::JSONObject)}}
        /** Session-level consent survives participant changes and never reads the new person's choices. */
        fun binding(context:Context,sessionId:String):JSONObject?=synchronized(lock){
            for(file in directory(context).listFiles().orEmpty().filter{it.extension=="jsonl"}){
                val events=runCatching{read(file)}.getOrNull()?:continue
                StudyConsentPolicy.sessionBinding(events,sessionId)?.let{return@synchronized it}
            };null
        }
    }
}
