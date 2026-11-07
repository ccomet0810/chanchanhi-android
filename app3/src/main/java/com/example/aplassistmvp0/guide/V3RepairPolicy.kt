package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.PlanTicket

internal enum class V3DiagnosticSource { RESPONSE, VALIDATION, SESSION, PREPARATION }
/** Local validator codes and optional IDs in the frozen request. */
internal data class V3PlanDiagnostic(val source:V3DiagnosticSource,val reason:String,val blockedNodeIds:Set<Int> = emptySet()) {
    init {
        require(Regex("[a-z][a-z0-9_]{0,63}").matches(reason))
        require(blockedNodeIds.isEmpty() || source == V3DiagnosticSource.VALIDATION &&
            reason == REPEATED_WRONG_NAVIGATION && blockedNodeIds.all { it > 0 })
    }

    companion object {
        const val REPEATED_WRONG_NAVIGATION = "repeated_wrong_navigation"
    }
}
internal data class V3PlanningAttempt(val ticket:PlanTicket,val index:Int=0,val diagnostic:V3PlanDiagnostic?=null) {
    init { require(index in 0..V3RepairPolicy.MAX_REPAIRS && (index==0)==(diagnostic==null)) }
}
internal object V3RepairPolicy {
    const val MAX_REPAIRS=1
    const val REPAIR_SPACING_MILLIS=400L
    fun allows(attempt:Int,diagnostic:V3PlanDiagnostic?,unresolved:Boolean):Boolean {
        if(attempt>=MAX_REPAIRS || diagnostic==null || unresolved)return false
        return when(diagnostic.source){
            V3DiagnosticSource.RESPONSE->diagnostic.reason in setOf("server_invalid_model_result","response_codec")
            V3DiagnosticSource.VALIDATION,V3DiagnosticSource.SESSION->diagnostic.reason !in setOf("condition_history_limit","missing_goal")
            V3DiagnosticSource.PREPARATION->diagnostic.reason in setOf("unready_preparation","missing_preparation_report",
                "missing_preparation_binding","invalid_prepared_commit_action","unavailable_preparation_evidence","stale_preparation",
                "value_mismatch","missing_value_expectation",V3ScopedPreparation.MISSING_REQUIRED_INPUTS)
        }
    }
    /** Strict repair context. A confirmed scope may exempt only its peer buttons' notices. */
    fun sameObservation(a:ScreenSnapshot,b:ScreenSnapshot,peerButtonPaths:Set<String> = emptySet()):Boolean {
        fun facts(frame:ScreenSnapshot)=frame.allNodes.filter{it.visibleToUser && !it.password && !it.sensitiveContent &&
            !AccessibilityNodePrivacy.isSensitivePreview(it)}.map{n->listOf(n.path,n.parentPath,n.className,n.viewIdResourceName,
            GuidePolicy.label(n),n.clickable,n.editable,n.scrollable,n.enabled,n.checkable,n.checked,n.selected,n.hasInput,
            n.contentInvalid,n.heading,AccessibilityNodePrivacy.publicPaneTitle(n),
            if(AccessibilityNodePrivacy.isEditable(n) || n.path in peerButtonPaths)null else n.stateDescription,
            if(AccessibilityNodePrivacy.isEditable(n))null else n.range,n.actions.map{it.id}.sorted(),n.localInputRevision)}.toSet()
        return a.packageName==b.packageName && a.windowId==b.windowId && a.sourceEpoch==b.sourceEpoch &&
            a.blockingPopup==b.blockingPopup && a.platformActionBarBounds==b.platformActionBarBounds &&
            a.inputObservationVersion==b.inputObservationVersion && !b.truncated && facts(a)==facts(b)
    }
}
internal object V3FailureClassifier {
    fun fromBridge(error:Exception):V3PlanFailure {
        val failure=error as? BridgeRequestFailure
        val kind=when {
            failure==null->V3PlanFailureKind.REMOTE
            failure.status in setOf(401,403)->V3PlanFailureKind.AUTHENTICATION
            failure.code in setOf("guide_busy","guide_rate_limit") && failure.status in setOf(409,429)->V3PlanFailureKind.BUSY
            failure.status==429 || failure.code in setOf("usage_limit","budget_limit")->V3PlanFailureKind.LIMIT
            failure.code=="invalid_model_request"->V3PlanFailureKind.PROTOCOL_MISMATCH
            failure.status==502 && failure.code=="invalid_model_result"->V3PlanFailureKind.INVALID_RESPONSE
            failure.code in setOf("missing_completed","invalid_model_usage")->V3PlanFailureKind.UNKNOWN_REMOTE_RESULT
            else->V3PlanFailureKind.REMOTE
        }
        return V3PlanFailure(kind,error.message?.takeIf{it.isNotBlank()} ?:"AI 연결을 확인해주세요.",error,
            if(kind==V3PlanFailureKind.INVALID_RESPONSE)V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result")else null)
    }
}
