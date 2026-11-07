package com.example.aplassistmvp0.guide

import android.view.accessibility.AccessibilityEvent
import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** A click receipt is evidence of an interaction, never proof that the operation succeeded. */
internal data class V3CommitReceipt(val goal:TaskGoal,val before:ScreenSnapshot,val nodePath:String,
    val eventUptime:Long,val publicBefore:Set<List<Any?>>,val interactionVerified:Boolean=true,
    val baselineTimed:Boolean=false)

internal object V3InteractionEvidence {
    private fun matching(evidence:PublicActionEvidence,frame:ScreenSnapshot):AccessibilityNodeModel? {
        fun matchesLabel(n:AccessibilityNodeModel)=!AccessibilityNodePrivacy.hasHiddenIdentifier(n) &&
            evidence.label in (listOfNotNull(n.text,n.contentDescription)+n.descendantLabels+GuidePolicy.label(n))
        val identity=frame.allNodes.filter{it.visibleToUser && it.enabled && !it.password && !it.sensitiveContent &&
            it.className==evidence.role && it.viewIdResourceName.orEmpty()==evidence.viewId &&
            (if(evidence.label.isNotBlank())matchesLabel(it)else evidence.viewId.isNotBlank())}
        val positioned=identity.filter{it.bounds==evidence.bounds}
        if(positioned.size==1)return positioned.single()
        if(positioned.size>1 || evidence.label.isBlank())return null
        // This binds an interaction that already happened, not coordinates for a future click.
        // A unique public identity can survive insets/layout motion; geometry alone cannot identify it.
        return identity.filter(::matchesLabel).singleOrNull()
    }
    fun rejection(evidence:PublicActionEvidence?,frame:ScreenSnapshot,shownAt:Long,now:Long):String? {
        evidence ?:return "missing_evidence"
        val event=evidence.eventUptimeMillis ?:return "missing_event_time"
        val received=evidence.receivedUptimeMillis ?:return "missing_receipt_time"
        if(evidence.eventType!=AccessibilityEvent.TYPE_VIEW_CLICKED)return "not_a_click"
        if(event<shownAt)return "before_current_instruction"
        if(event>received || received>now)return "invalid_time_order"
        if(evidence.packageName!=frame.packageName)return "other_package"
        if(evidence.windowId!=frame.windowId)return "other_window"
        if(evidence.sourceEpoch!=frame.sourceEpoch)return "other_source_epoch"
        if(evidence.bounds.isEmpty || evidence.role.isBlank())return "missing_source_geometry"
        if(matching(evidence,frame)==null)return "source_node_not_unique_or_changed"
        return null
    }
    fun click(evidence:PublicActionEvidence?,frame:ScreenSnapshot,shownAt:Long,now:Long):AccessibilityNodeModel? {
        if(rejection(evidence,frame,shownAt,now)!=null)return null
        return matching(evidence!!,frame)
    }
    fun signature(node:AccessibilityNodeModel):List<Any?>? {
        if(!node.visibleToUser || node.password || node.sensitiveContent || AccessibilityNodePrivacy.isEditable(node))return null
        val label=GuidePolicy.label(node)
        if(label.isBlank() || AccessibilityNodePrivacy.hasHiddenIdentifier(node))return null
        return listOf(node.className,node.viewIdResourceName,label,node.stateDescription,node.checked,node.selected,node.enabled,node.range)
    }
}

/** Session-owned operation/attempt ledger. Public identity is deliberately independent of model task keys. */
internal class V3CommitTracker(private val historyLimit:Int=8192,private val receiptLimit:Int=128) {
    internal enum class Gate { ALLOW, UNRESOLVED, ALREADY_SUCCEEDED, UNKNOWN_IDENTITY, HISTORY_LIMIT }
    private data class Control(val role:String,val resource:String,val label:String) {
        fun overlaps(other:Control)=resource.isNotBlank() && resource==other.resource ||
            label.isNotBlank() && label==other.label && role==other.role
    }
    private class Attempt(val id:Long,val receipt:V3CommitReceipt) {
        var observed=CommitOutcome.PENDING
        var terminal:CommitOutcome?=null
        fun unresolved()=terminal==null || !receipt.interactionVerified
    }
    private class Entry(val id:Long,val aliases:MutableSet<Control>,val attempts:MutableList<Attempt>)
    private data class PublicFact(val packageName:String,val sourceEpoch:Long,val signature:List<Any?>)
    private val entries=mutableListOf<Entry>()
    private var operationSerial=0L
    private var receiptSerial=0L
    private val firstSeen=mutableMapOf<PublicFact,Long>()
    private var historyComplete=true
    private fun latest()=entries.flatMap{it.attempts}.maxByOrNull{it.id}
    val receipt get()=latest()?.receipt
    val outcome get()=latest()?.observed ?:CommitOutcome.NONE
    fun reset(){entries.clear();operationSerial=0;receiptSerial=0;firstSeen.clear();historyComplete=true}
    fun history():List<OperationRecord> = entries.flatMap{entry->entry.attempts.mapIndexed{index,a->
        val c=entry.aliases.first()
        OperationRecord(entry.id,a.id,index+1,a.receipt.goal.revision,a.terminal ?:a.observed,
            a.receipt.interactionVerified,c.label.take(140),c.role.take(140),c.resource.take(180))
    }}.sortedBy{it.receiptId}
    private fun timed(frame:ScreenSnapshot)=frame.captureStartedAtUptimeMillis?.let{start->
        start>=0 && frame.captureFinishedAtUptimeMillis?.let{it>=start}==true}==true
    fun remember(frame:ScreenSnapshot) {
        val at=if(timed(frame))frame.captureStartedAtUptimeMillis!! else Long.MIN_VALUE
        for(signature in frame.allNodes.mapNotNull(V3InteractionEvidence::signature)) {
            val key=PublicFact(frame.packageName,frame.sourceEpoch,signature)
            val previous=firstSeen[key]
            if(previous!=null){if(at<previous)firstSeen[key]=at}
            else if(firstSeen.size<historyLimit)firstSeen[key]=at
            else historyComplete=false
        }
    }
    private fun fresh(r:V3CommitReceipt,node:AccessibilityNodeModel):Boolean {
        if(!historyComplete || !r.baselineTimed)return false
        val signature=V3InteractionEvidence.signature(node) ?:return false
        val at=firstSeen[PublicFact(r.before.packageName,r.before.sourceEpoch,signature)] ?:return false
        return signature !in r.publicBefore && at>r.eventUptime
    }
    private fun control(plan:TaskPlan,frame:ScreenSnapshot):Control? {
        val node=frame.allNodes.singleOrNull{it.id==plan.targetNodeId} ?:return null
        if(node.password || node.sensitiveContent || node.editable || AccessibilityNodePrivacy.hasHiddenIdentifier(node))return null
        val resource=node.viewIdResourceName.orEmpty()
        if(resource.isBlank() && GuideVisionPolicy.anonymous(node))return null
        val label=GuidePolicy.label(node)
        if(resource.isBlank() && label.isBlank())return null
        return Control(node.className.orEmpty(),resource,label)
    }
    private fun matching(control:Control)=entries.filter{e->e.aliases.any{it.overlaps(control)}}
    fun unresolved()=entries.any{e->e.attempts.any{it.unresolved()}}
    fun gate(plan:TaskPlan,frame:ScreenSnapshot):Gate {
        val control=control(plan,frame)
        val matches=control?.let(::matching).orEmpty()
        if(matches.any{e->e.attempts.any{it.terminal==CommitOutcome.SUCCEEDED}})return Gate.ALREADY_SUCCEEDED
        if(matches.any{e->e.attempts.any{it.unresolved()}})return Gate.UNRESOLVED
        // Known native effects cannot be downgraded by a new model classification.
        // Other preparation remains available while an independent result is pending.
        if(plan.effect!=ActionEffect.COMMIT)return if(matches.isEmpty())Gate.ALLOW else Gate.UNKNOWN_IDENTITY
        if(unresolved())return Gate.UNRESOLVED
        if(control==null)return Gate.UNKNOWN_IDENTITY
        if(matches.size>1)return Gate.UNKNOWN_IDENTITY
        if(receiptSerial>=receiptLimit)return Gate.HISTORY_LIMIT
        return Gate.ALLOW
    }
    private fun append(goal:TaskGoal,decision:TaskPlan,before:ScreenSnapshot,evidence:PublicActionEvidence,verified:Boolean) {
        val c=checkNotNull(control(decision,before))
        val entry=matching(c).singleOrNull() ?:Entry(++operationSerial,linkedSetOf(c),mutableListOf()).also{entries+=it}
        entry.aliases+=c
        remember(before)
        val receipt=V3CommitReceipt(goal,before,before.allNodes.single{it.id==decision.targetNodeId}.path,evidence.eventUptimeMillis!!,
            before.allNodes.mapNotNull(V3InteractionEvidence::signature).toSet(),verified,
            baselineTimed=verified && timed(before) && before.captureFinishedAtUptimeMillis!!<=evidence.eventUptimeMillis)
        entry.attempts+=Attempt(++receiptSerial,receipt).also{if(!verified)it.observed=CommitOutcome.UNKNOWN}
    }
    fun record(goal:TaskGoal,decision:TaskPlan,before:ScreenSnapshot,evidence:PublicActionEvidence?,shownAt:Long,now:Long):Boolean {
        if(decision.kind!=NextKind.GUIDE || decision.effect!=ActionEffect.COMMIT || decision.operation!=Operation.TAP || gate(decision,before)!=Gate.ALLOW)return false
        val clicked=V3InteractionEvidence.click(evidence,before,shownAt,now) ?:return false
        if(clicked.id!=decision.targetNodeId || clicked.editable || !clicked.clickable)return false
        append(goal,decision,before,evidence!!,true);return true
    }
    fun recordPossible(goal:TaskGoal,decision:TaskPlan,before:ScreenSnapshot,evidence:PublicActionEvidence?,shownAt:Long,now:Long):Boolean {
        if(decision.kind!=NextKind.GUIDE || decision.effect!=ActionEffect.COMMIT || decision.operation!=Operation.TAP || gate(decision,before)!=Gate.ALLOW)return false
        evidence ?:return false
        val event=evidence.eventUptimeMillis ?:return false
        val received=evidence.receivedUptimeMillis ?:return false
        if(evidence.eventType!=AccessibilityEvent.TYPE_VIEW_CLICKED || event<shownAt || event>received || received>now ||
            evidence.packageName!=before.packageName || evidence.windowId!=before.windowId || evidence.sourceEpoch!=before.sourceEpoch)return false
        val target=before.allNodes.singleOrNull{it.id==decision.targetNodeId} ?:return false
        val candidate=V3InteractionEvidence.click(evidence,before,shownAt,now)
        if(candidate!=null && candidate.id!=target.id)return false
        val sharedId=evidence.viewId.isNotBlank() && evidence.viewId==target.viewIdResourceName
        val overlap=minOf(evidence.bounds.right,target.bounds.right)>maxOf(evidence.bounds.left,target.bounds.left) &&
            minOf(evidence.bounds.bottom,target.bounds.bottom)>maxOf(evidence.bounds.top,target.bounds.top)
        val label=evidence.label.isNotBlank() && evidence.label in (listOfNotNull(target.text,target.contentDescription)+target.descendantLabels+GuidePolicy.label(target))
        if(!sharedId && !overlap && !label && !evidence.bounds.isEmpty)return false
        append(goal,decision,before,evidence,false);return true
    }
    fun observe(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot):CommitOutcome {
        val a=entries.flatMap{it.attempts}.singleOrNull{it.id==plan.outcomeReceiptId} ?:return CommitOutcome.UNKNOWN
        val r=a.receipt
        remember(frame)
        fun unknown():CommitOutcome{a.observed=CommitOutcome.UNKNOWN;return a.observed}
        if(goal.sessionId!=r.goal.sessionId || frame.packageName!=r.before.packageName ||
            frame.sourceEpoch!=r.before.sourceEpoch || frame.snapshotId<=r.before.snapshotId || frame.truncated ||
            !timed(frame) || frame.captureStartedAtUptimeMillis!!<=r.eventUptime)return unknown()
        if(plan.commitOutcome in setOf(CommitOutcome.SUCCEEDED,CommitOutcome.FAILED)) {
            val nodes=plan.outcomeNodeIds.mapNotNull{id->frame.allNodes.singleOrNull{it.id==id}}
            val supported=nodes.size==plan.outcomeNodeIds.size && nodes.isNotEmpty() && nodes.any{fresh(r,it)}
            if(supported && r.interactionVerified){
                // A later ambiguous/error screen cannot erase an already executed successful operation.
                if(a.terminal==CommitOutcome.SUCCEEDED && plan.commitOutcome!=CommitOutcome.SUCCEEDED)return unknown()
                a.observed=plan.commitOutcome;a.terminal=plan.commitOutcome;return a.observed
            }
        }
        a.observed=if(plan.commitOutcome==CommitOutcome.PENDING)CommitOutcome.PENDING else CommitOutcome.UNKNOWN
        return a.observed
    }
    fun canComplete(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot):Boolean {
        val r=entries.flatMap{it.attempts}.singleOrNull{it.id==plan.outcomeReceiptId}?.receipt ?:return false
        if(!r.interactionVerified || r.goal.revision!=goal.revision)return false
        if(observe(goal,plan,frame)!=CommitOutcome.SUCCEEDED || unresolved() || plan.newEventNodeIds.isEmpty())return false
        val ids=plan.outcomeNodeIds.toSet()
        return plan.newEventNodeIds.all{id->id in ids && frame.allNodes.singleOrNull{it.id==id}?.let{fresh(r,it)}==true}
    }
}
