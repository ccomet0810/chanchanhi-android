package dev.ccomet.chanchanhi04.platform

import android.content.Context
import dev.ccomet.chanchanhi04.agent.AgentJson
import dev.ccomet.chanchanhi04.agent.SessionRecorder
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import dev.ccomet.chanchanhi04.research.ResearchConnection
import dev.ccomet.chanchanhi04.agent.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogRow(val sequence: Long, val kind: String, val json: String)

/** Per-session append-only ledger. Model context clipping never removes the original trace. */
class SessionLog(context: Context,private val study:dev.ccomet.chanchanhi04.study.StudyRepository?=null,private val capabilitySubgoal:(String)->Boolean={false}) : SessionRecorder {
    private val contextForConsent=context.applicationContext
    private val directory = File(context.filesDir,"v04-sessions").apply { mkdirs() }
    private val writer = Executors.newSingleThreadExecutor()
    private class Sink(val file:File,var sequence:Long=0,var terminal:Boolean=false)
    private var current:Sink?=null
    private val research=ResearchConnection(context)
    private val mutable = MutableStateFlow<List<LogRow>>(emptyList())
    val rows = mutable.asStateFlow()
    @Synchronized override fun begin(sessionId: String, goal: String) {
        require(sessionId.matches(Regex("[A-Za-z0-9-]{8,80}")))
        current?.terminal=true
        current=Sink(File(directory,"$sessionId.jsonl"))
        val association=study?.linkSession(sessionId,goal,capabilitySubgoal(goal))
        research.begin(sessionId)
        mutable.value = emptyList()
        event("session_started", mapOf("protocol" to "chanchanhi/0.4", "sessionId" to sessionId, "goal" to goal,"device" to research.metadata(),"study" to association))
    }
    @Synchronized override fun event(kind: String, value: Any?) {
        val sink=current ?: return
        append(sink,kind,value)
    }
    /** Capture before dispatching model I/O. A late response stays in its original session. */
    @Synchronized fun boundEvent():(String,Any?)->Unit {
        val sink=current
        return {kind,value->synchronized(this){if(sink!=null)append(sink,kind,value)}}
    }
    @Synchronized fun passive(kind:String,value:Any?) {current?.takeIf{!it.terminal}?.let{append(it,kind,value)}}
    @Synchronized fun imageSession():String?=current?.takeIf{!it.terminal&&research.imageEligible(it.file.nameWithoutExtension)&&dev.ccomet.chanchanhi04.study.StudyRepository.binding(contextForConsent,it.file.nameWithoutExtension)?.optBoolean("captures")==true}?.file?.nameWithoutExtension
    /** Bind both the original session and the exact immutable observation; late captures cannot migrate. */
    suspend fun image(sessionId:String,observation:Observation,bytes:ByteArray,width:Int,height:Int)=withContext(Dispatchers.IO){synchronized(this@SessionLog){
        val sink=current?.takeIf{!it.terminal&&it.file.nameWithoutExtension==sessionId&&research.imageEligible(sessionId)&&imageSession()==sessionId}?:return@synchronized
        val snapshot=mapOf("snapshotId" to observation.id,"packageName" to observation.packageName,"windowId" to observation.windowId,
            "epoch" to observation.epoch,"visualGeneration" to observation.visualGeneration,"width" to observation.width,"height" to observation.height)
        val meta=AgentJson.encode(mapOf("snapshot" to snapshot,"imageWidth" to width,"imageHeight" to height,"mime" to "image/jpeg",
            "sha256" to ResearchConnection.frameHash(bytes),"overlayIncluded" to true,"consentVersion" to "screen-images-1")) as JSONObject
        // Persist the exact full observation immediately before its image; compact model input is unrelated.
        append(sink,"screen_frame",observation)
        val queued=runCatching{research.queueFrame(sessionId,sink.sequence+1,meta,bytes)}.getOrDefault(false)
        if(queued)append(sink,"screen_image",meta) else append(sink,"screen_image_unavailable",mapOf("reason" to "queue_unavailable","snapshot" to snapshot))
    }}
    @Synchronized fun imageUnavailable(sessionId:String,reason:String){current?.takeIf{!it.terminal&&it.file.nameWithoutExtension==sessionId&&research.imageEligible(sessionId)&&imageSession()==sessionId}?.let{append(it,"screen_image_unavailable",mapOf("reason" to reason))}}
    private fun append(sink:Sink,kind:String,value:Any?) {
        val json = JSONObject().put("seq",++sink.sequence).put("at",System.currentTimeMillis())
            .put("kind",kind).put("data",AgentJson.encode(value,rendering=true)).toString()
        if(sink===current)mutable.value = (mutable.value + LogRow(sink.sequence,kind,json)).takeLast(300)
        if(kind in setOf("stopped","completed","session_superseded"))sink.terminal=true
        writer.execute { sink.file.appendText(json + "\n",Charsets.UTF_8);research.stored() }
    }
    fun files(): List<File> = directory.listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    /** Call off the main thread before an explicitly authorized export. */
    fun awaitWrites(){writer.submit{}.get(5,java.util.concurrent.TimeUnit.SECONDS)}
}
