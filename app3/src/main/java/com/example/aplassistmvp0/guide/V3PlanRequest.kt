package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.json.JSONArray
import org.json.JSONObject

internal enum class V3PlanFailureKind { CONTEXT_TOO_LARGE, INVALID_OBSERVATION, INCOMPLETE_OBSERVATION, INVALID_RESPONSE, CONNECTION_CHANGED,
    AUTHENTICATION, LIMIT, BUSY, PROTOCOL_MISMATCH, UNKNOWN_REMOTE_RESULT, MODEL_REFUSAL, REQUEST_EXPIRED, REMOTE }
internal class V3PlanFailure(val kind:V3PlanFailureKind,val userMessage:String,cause:Throwable?=null,
    val diagnostic:V3PlanDiagnostic?=null):IllegalStateException(userMessage,cause)
internal data class V3PlanPacket(val bytes:ByteArray,val nodeIds:Set<Int>,val observationComplete:Boolean,val publicNodeCount:Int)

/** One production request encoder, also exercised through the real USB client by offline QA. */
internal object V3PlanRequest {
    const val MAX_BYTES=65_536
    private const val MAX_NODES=180
    fun encode(goal:TaskGoal,screen:ScreenSnapshot,prior:TaskPlan?,signal:String,backend:GuideBackend,schema:JSONObject,
        diagnostic:V3PlanDiagnostic?=null):V3PlanPacket {
        val visible=screen.allNodes.filter{it.visibleToUser && !it.password && !it.sensitiveContent && !AccessibilityNodePrivacy.isSensitivePreview(it)}
        if(visible.map{it.id}.distinct().size!=visible.size)throw V3PlanFailure(V3PlanFailureKind.INVALID_OBSERVATION,"화면 정보를 다시 확인해주세요.")
        var clipped=false
        fun bounded(s:String?,limit:Int):String?=s?.let{if(it.length>limit)clipped=true;it.take(limit)}
        val normalizedRequest = goal.request.replace(Regex("\\s+"), " ")
        val facts=visible.take(MAX_NODES).map{n->
            val editable=AccessibilityNodePrivacy.isEditable(n)
            JSONObject().put("id",n.id).put("path",bounded(n.path,180)).put("parentPath",bounded(n.parentPath,180)?:JSONObject.NULL)
                .put("role",bounded(n.className.orEmpty(),140)).put("label",bounded(GuidePolicy.label(n),140)).apply{
                    bounded(n.viewIdResourceName,180)?.takeIf{it.isNotEmpty()}?.let{put("resource",it)}
                    if(n.clickable)put("clickable",true)
                    if(editable)put("editable",true)
                    if(n.scrollable)put("scrollable",true)
                    if(!n.enabled)put("enabled",false)
                    n.checkable?.let{put("checkable",it)};n.checked?.let{put("checked",it)};n.selected?.let{put("selected",it)}
                    n.hasInput?.let{put("inputPresent",it)};n.contentInvalid?.let{put("invalid",it)}
                    if(!editable){
                        if(n.heading)put("heading",true)
                        AccessibilityNodePrivacy.publicPaneTitle(n)?.takeIf { normalizedRequest.contains(it, ignoreCase = true) }
                            ?.let { put("paneTitle", it) }
                        bounded(n.stateDescription?.let(AccessibilityNodePrivacy::redactIdentifiers),140)?.let{put("state",it)}
                        n.range?.takeIf{it.usable}?.let{put("range",JSONObject().put("min",it.min).put("max",it.max).put("current",it.current))}
                    }
                    if(n.actions.isNotEmpty())put("actions",JSONArray(n.actions.map{it.id}.distinct()))
                }
        }
        val defaults=JSONObject().put("resource","").put("clickable",false).put("editable",false).put("scrollable",false).put("enabled",true)
            .put("actions",JSONArray())
        for(key in listOf("checkable","checked","selected","inputPresent","invalid","state","range"))defaults.put(key,JSONObject.NULL)
        val answers=JSONObject();goal.answers.forEach{(key,value)->answers.put(key,value)}
        val authorities=JSONObject();goal.answerAuthorities.forEach{(key,value)->authorities.put(key,JSONObject()
            .put("revision",value.revision).put("requirementIds",JSONArray(value.requirementIds.sorted())))}
        val common=JSONObject().put("request",goal.request).put("answers",answers).put("targetApp",goal.targetLabel)
            .put("requiresNewEvent",goal.requiresNewEvent).put("goalRevision",goal.revision).put("answerAuthorities",authorities)
            .put("requirements",JSONArray(goal.requirements.map{JSONObject().put("id",it.id).put("version",it.version)
                .put("description",it.description).put("required",it.required).put("origin",it.basis.kind.name)
                .put("phase",it.phase.name)
                .put("expectations",V3PlanCodec.expectations(it.expectations))
                .put("changedAtRevision",it.changedAtRevision).put("retired",it.retired)
                .put("staleAnswerKey",it.staleAnswerKey?:JSONObject.NULL).put("lastChangeReason",it.lastChangeReason)}))
            .put("preparationHistory",JSONArray(goal.preparationHistory.map{JSONObject().put("role",it.role).put("resource",it.resource)
                .put("label",it.label).put("requirementIds",JSONArray(it.requirementIds.sorted()))}))
            .put("scopeHistory",JSONArray(goal.scopeHistory.map{JSONObject().put("label",it.publicLabel).put("role",it.role)
                .put("path",it.path).put("resource",it.resource).put("windowId",it.observation.windowId)
                .put("snapshotId",it.observation.sequence).put("sourceEpoch",it.observation.sourceEpoch)}))
            .put("operationHistory",JSONArray(goal.operationHistory.map{JSONObject().put("operationId",it.operationId)
                .put("receiptId",it.receiptId).put("attempt",it.attempt).put("goalRevision",it.goalRevision)
                .put("state",it.state.name).put("interactionVerified",it.interactionVerified)
                .put("label",it.label).put("role",it.role).put("resource",it.resource)}))
            .put("packageName",screen.packageName).put("windowId",screen.windowId).put("snapshotId",screen.snapshotId)
            .put("sourceEpoch",screen.sourceEpoch).put("blockingPopup",screen.blockingPopup)
            .put("userSignal",signal).put("nodeDefaults",defaults).put("visiblePublicNodeCount",visible.size)
            .put("inputProgress",JSONArray(goal.inputProgress.map{JSONObject().put("inputId",it.inputId)
                .put("nodeId",it.nodeId?:JSONObject.NULL).put("userFinished",it.userFinished).put("receiptId",it.receiptId?:JSONObject.NULL)}))
        prior?.let{common.put("previousPlan",V3PlanCodec.encode(it))}
        val whole=!screen.truncated && visible.size<=MAX_NODES && !clipped
        fun pack(count:Int):V3PlanPacket {
            val complete=whole && count==visible.size
            val encodedIds=visible.take(count).map{it.id}.toSet()
            val input=JSONObject(common.toString()).put("nodes",JSONArray(facts.take(count))).put("observationComplete",complete)
                .put("omittedPublicNodeCount",visible.size-count)
            diagnostic?.let {
                val feedback=JSONObject().put("attempt",1).put("maximumRepairs",V3RepairPolicy.MAX_REPAIRS)
                    .put("source",it.source.name).put("reason",it.reason)
                val blocked=it.blockedNodeIds.intersect(encodedIds).sorted()
                if (blocked.isNotEmpty()) feedback.put("blockedNodeIds",JSONArray(blocked))
                input.put("planningFeedback",feedback)
            }
            val payload=JSONObject().put("model",backend.model).put("store",false).put("stream",true)
                .put("service_tier","default").put("reasoning",JSONObject().put("effort","low"))
                .put("instructions",V3PlanClient.INSTRUCTIONS).put("input",JSONArray().put(JSONObject().put("role","user").put("content",input.toString())))
                .put("text",JSONObject().put("format",JSONObject().put("type","json_schema").put("name","task_plan").put("strict",true).put("schema",schema)))
            return V3PlanPacket(payload.toString().toByteArray(Charsets.UTF_8),encodedIds,complete,visible.size)
        }
        val maximum=pack(facts.size)
        if(maximum.bytes.size<=MAX_BYTES)return maximum
        var low=0;var high=facts.size
        var best=pack(0)
        if(best.bytes.size>MAX_BYTES)throw contextTooLarge()
        while(low<high){
            val mid=(low+high+1)/2;val candidate=pack(mid)
            if(candidate.bytes.size<=MAX_BYTES){low=mid;best=candidate}else high=mid-1
        }
        if(visible.isNotEmpty() && best.nodeIds.isEmpty())throw contextTooLarge()
        return best
    }
    private fun contextTooLarge()=V3PlanFailure(V3PlanFailureKind.CONTEXT_TOO_LARGE,
        "확인할 내용이 많아요. 안내를 끝낸 뒤 현재 할 일로 다시 시작해주세요.")
    fun validate(packet:V3PlanPacket,goal:TaskGoal,value:TaskPlan):TaskPlan {
        if(!packet.observationComplete && (value.kind==NextKind.COMPLETE || value.effect==ActionEffect.COMMIT))throw V3PlanFailure(V3PlanFailureKind.INCOMPLETE_OBSERVATION,
            "화면을 더 확인해야 해요. 현재 화면에서 다시 확인해주세요.")
        TaskPlanValidation.problem(value,goal,packet.nodeIds)?.let{problem->throw V3PlanFailure(V3PlanFailureKind.INVALID_RESPONSE,
            "안내 계획의 근거를 확인하지 못했어요. 다시 확인해주세요.",diagnostic=V3PlanDiagnostic(V3DiagnosticSource.VALIDATION,problem))}
        return value.copy(groundingComplete=packet.observationComplete)
    }
}
