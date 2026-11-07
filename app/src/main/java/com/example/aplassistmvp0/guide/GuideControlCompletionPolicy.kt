package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** Text presence is not a user's statement that they finished composing. */
internal object GuideControlCompletionPolicy {
    fun input(decision: GuideDecision) = decision.operation in setOf("fill_input", "edit_input")
    fun current(decision: GuideDecision, requested: ScreenSnapshot, live: ScreenSnapshot): AccessibilityNodeModel? {
        if (requested.packageName!=live.packageName || requested.windowId!=live.windowId || live.truncated ||
            live.snapshotId<requested.snapshotId || live.capturedAtMillis<requested.capturedAtMillis ||
            requested.blockingPopup!=live.blockingPopup) return null
        val old=requested.allNodes.singleOrNull{it.id==decision.targetNodeId}?:return null
        return GuideActionPolicy.target(old,live)?.takeIf{it.enabled && it.visibleToUser && AccessibilityNodePrivacy.isEditable(it)}
    }
    fun ready(node:AccessibilityNodeModel?) = node?.hasInput==true && node.contentInvalid!=true
}

/** A framework time-picker is a form with several wheels, not a single scrolling list.
 * Only public widget structure/labels determine roles; no app package or entered text is used. */
internal class GuideTimePickerPlan {
    data class Control(val role:String,val node:AccessibilityNodeModel)
    private var scope:Triple<String,Int,Any?>?=null
    private val acknowledged=mutableSetOf<String>()
    fun reset(){scope=null;acknowledged.clear()}
    fun controls(screen:ScreenSnapshot):List<Control> {
        if(screen.truncated || screen.viewport==null || screen.blockingPopup)return emptyList()
        return com.example.aplassistmvp0.accessibility.PublicTimePickerRoles.roles(screen.allNodes).map{Control(it.name,it.node)}
    }
    fun step(goal:String,screen:ScreenSnapshot):GuideDecision? {
        val controls=controls(screen)
        if(controls.isEmpty()){reset();return null}
        if(!Regex("알람|시간|시각|예약|alarm|time",RegexOption.IGNORE_CASE).containsMatchIn(goal) ||
            Regex("삭제|취소|목록|끄|꺼|보기만|cancel|delete",RegexOption.IGNORE_CASE).containsMatchIn(goal))return null
        val key=Triple(screen.packageName,screen.windowId,screen.viewport)
        if(scope!=key){reset();scope=key}
        val next=controls.firstOrNull{it.role !in acknowledged}
        if(next!=null)return GuideDecision("highlight",next.node.id,"${next.role} 영역을 위아래로 움직여 맞춰주세요.",operation="adjust_picker",source="local")
        val save=GuidePolicy.candidates(screen).filter{it.enabled && it.clickable && GuidePolicy.label(it).trim().lowercase() in setOf("저장","save")}.singleOrNull()
        return save?.let{GuideDecision("highlight",it.id,"저장을 눌러주세요.",operation="tap",source="local")}
    }
    fun acknowledge(decision:GuideDecision,requested:ScreenSnapshot,live:ScreenSnapshot):Boolean {
        if(decision.operation!="adjust_picker" || requested.packageName!=live.packageName || requested.windowId!=live.windowId ||
            requested.viewport!=live.viewport || live.snapshotId<requested.snapshotId)return false
        val role=controls(requested).singleOrNull{it.node.id==decision.targetNodeId}?.role?:return false
        val current=controls(live).singleOrNull{it.role==role}?:return false
        if(current.node.bounds.isEmpty || scope!=Triple(live.packageName,live.windowId,live.viewport))return false
        acknowledged+=role;return true
    }
}
