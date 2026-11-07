package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** Watch only planned public readonly facts during a transition, never private input or unrelated activity clocks. */
internal class V3TransitionObservation {
    private data class Anchor(val path:String,val role:String?,val resource:String?,val value:List<Any?>?)
    private var source:Triple<String,Int,Long>?=null
    private var anchors=emptyList<Anchor>()
    private fun value(node:AccessibilityNodeModel)=if(node.clickable || node.scrollable ||
        AccessibilityNodePrivacy.isSensitivePreview(node))null else V3InteractionEvidence.signature(node)
    fun begin(plan:TaskPlan,frame:ScreenSnapshot){
        clear()
        if(plan.kind!=NextKind.WAIT || plan.waitKind!=WaitKind.SCREEN_TRANSITION || frame.truncated)return
        source=Triple(frame.packageName,frame.windowId,frame.sourceEpoch)
        anchors=plan.relevantNodeIds.distinct().take(180).mapNotNull{id->
            val node=frame.allNodes.singleOrNull{it.id==id} ?:return@mapNotNull null
            value(node)?.let{Anchor(node.path,node.className,node.viewIdResourceName,it)}
        }
    }
    fun changed(frame:ScreenSnapshot):Boolean {
        if(anchors.isEmpty() || frame.truncated)return false
        if(source!=Triple(frame.packageName,frame.windowId,frame.sourceEpoch))return true
        return anchors.any{current(it,frame)!=it.value}
    }
    private fun current(anchor:Anchor,frame:ScreenSnapshot)=frame.allNodes.filter{
        it.path==anchor.path && it.className==anchor.role && it.viewIdResourceName==anchor.resource
    }.singleOrNull()?.let(::value)
    fun rebase(frame:ScreenSnapshot){
        if(frame.truncated || source!=Triple(frame.packageName,frame.windowId,frame.sourceEpoch)){clear();return}
        anchors=anchors.map{it.copy(value=current(it,frame))}
    }
    fun clear(){source=null;anchors=emptyList()}
}
