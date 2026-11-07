package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.state.AssistUiState
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** Retains a successfully shown commit classification while its replacement is being planned.
 * This binds an interaction that already happened; it never authorizes an app action. */
internal class V3CommitObservation {
    data class Candidate(val sessionId:Long,val revision:Long,val plan:TaskPlan,val before:ScreenSnapshot,
        val shownAt:Long,val anchors:List<AccessibilityNodeModel>)
    private var candidate:Candidate?=null
    fun show(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot,shownAt:Long){
        clear()
        if(plan.kind!=NextKind.GUIDE || plan.operation!=Operation.TAP || plan.effect!=ActionEffect.COMMIT ||
            frame.packageName!=goal.targetPackage || frame.truncated || frame.viewport==null)return
        val target=frame.allNodes.singleOrNull{it.id==plan.targetNodeId} ?:return
        if(!target.clickable || V3InteractionEvidence.signature(target)==null)return
        val ids=(plan.scopeNodeIds+plan.conditions.filter{it.phase==ConditionPhase.PREPARATION}.flatMap{it.evidenceNodeIds}+target.id).distinct()
        val anchors=ids.map{id->frame.allNodes.singleOrNull{it.id==id} ?:return}
        if(anchors.any{V3PlanGrounding.current(it,frame)==null})return
        candidate=Candidate(goal.sessionId,goal.revision,plan,frame,shownAt,anchors)
    }
    fun current(goal:TaskGoal,state:AssistUiState):Candidate? {
        val (value,frame)=matchingContext(goal,state) ?:return null
        if(frame.truncated || frame.viewport==null ||
            value.anchors.any{V3PlanGrounding.current(it,frame)==null}){clear();return null}
        return value
    }

    /** A late click can forbid a repeat while the prior control's current geometry is unverified. */
    fun forUncertainInteraction(goal: TaskGoal, state: AssistUiState): Candidate? {
        val (value, frame) = matchingContext(goal, state) ?: return null
        return value.takeIf { frame.truncated || frame.viewport == null }
    }

    private fun matchingContext(goal: TaskGoal, state: AssistUiState): Pair<Candidate, ScreenSnapshot>? {
        val value = candidate ?: return null
        val frame = state.snapshot
        if (goal.sessionId != value.sessionId || goal.revision != value.revision || !state.serviceConnected ||
            state.foregroundPackage != goal.targetPackage || frame == null || frame.packageName != value.before.packageName ||
            frame.windowId != value.before.windowId || frame.sourceEpoch != value.before.sourceEpoch ||
            frame.blockingPopup != value.before.blockingPopup ||
            frame.snapshotId < value.before.snapshotId || frame.capturedAtMillis < value.before.capturedAtMillis) {
            clear()
            return null
        }
        return value to frame
    }
    fun clear(){candidate=null}
}
