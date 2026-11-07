package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.AssistUiState
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.TaskGoal

/** A public app click may request observation. It never finishes writing or proves a submitted result. */
internal class V3InputRecheck {
    private data class Anchor(val session:Long,val revision:Long,val inputId:Long,val shownAt:Long,val frame:ScreenSnapshot)
    private var anchor:Anchor?=null
    private var lastClick:PublicActionEvidence?=null
    fun alreadyObserved(evidence:PublicActionEvidence?)=evidence!=null && evidence.copy(receivedUptimeMillis=null)==lastClick
    fun show(goal:TaskGoal,inputId:Long,frame:ScreenSnapshot,now:Long){
        if(frame.truncated || frame.packageName!=goal.targetPackage){clear();return}
        val before=anchor
        val same=before!=null && before.session==goal.sessionId && before.revision==goal.revision && before.inputId==inputId &&
            before.frame.windowId==frame.windowId && before.frame.sourceEpoch==frame.sourceEpoch
        anchor=Anchor(goal.sessionId,goal.revision,inputId,if(same)before!!.shownAt else now,frame)
    }
    fun afterClick(goal:TaskGoal,state:AssistUiState,evidence:PublicActionEvidence?,now:Long):Long? {
        if(alreadyObserved(evidence))return null
        val before=anchor ?:return null
        val live=state.snapshot ?:return null
        if(before.session!=goal.sessionId || before.revision!=goal.revision || !state.serviceConnected ||
            state.foregroundPackage!=goal.targetPackage || live.packageName!=goal.targetPackage ||
            live.windowId!=before.frame.windowId || live.sourceEpoch!=before.frame.sourceEpoch || live.snapshotId<before.frame.snapshotId)return null
        val clicked=V3InteractionEvidence.click(evidence,before.frame,before.shownAt,now) ?:return null
        if(!clicked.clickable || AccessibilityNodePrivacy.isEditable(clicked) || V3InteractionEvidence.signature(clicked)==null)return null
        lastClick=evidence!!.copy(receivedUptimeMillis=null)
        return evidence.receivedUptimeMillis
    }
    fun clear(){anchor=null}
    fun reset(){clear();lastClick=null}
}
