package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.TaskPlan

/** Binds each response reference to the current capture without reissuing its original ticket. */
internal object V3PlanGrounding {
    fun sameMeaning(a:AccessibilityNodeModel,b:AccessibilityNodeModel)=
        a.visibleToUser && b.visibleToUser && !a.password && !b.password && !a.sensitiveContent && !b.sensitiveContent &&
        a.className==b.className && a.viewIdResourceName==b.viewIdResourceName &&
        GuidePolicy.label(a)==GuidePolicy.label(b) && a.clickable==b.clickable && a.scrollable==b.scrollable &&
        a.editable==b.editable && a.enabled==b.enabled && a.checked==b.checked && a.selected==b.selected &&
        a.hasInput==b.hasInput && a.contentInvalid==b.contentInvalid && a.stateDescription==b.stateDescription &&
        a.heading==b.heading && a.paneTitle==b.paneTitle &&
        a.range==b.range && a.localRecipientKey==b.localRecipientKey && a.localInputRevision==b.localInputRevision
    fun current(anchor:AccessibilityNodeModel,frame:ScreenSnapshot):AccessibilityNodeModel? =
        frame.allNodes.filter{it.path==anchor.path && sameMeaning(anchor,it)}.singleOrNull()
    fun references(plan:TaskPlan)=
        (plan.relevantNodeIds+plan.newEventNodeIds+plan.outcomeNodeIds+plan.scopeNodeIds+plan.conditions.flatMap{it.evidenceNodeIds}+
            plan.conditions.flatMap{it.basis?.evidenceNodeIds.orEmpty()}+plan.conditionEdits.flatMap{it.basis.evidenceNodeIds}+
            plan.tasks.flatMap{it.nodeIds}+plan.actionGates.map{it.targetNodeId}+listOfNotNull(plan.targetNodeId)).distinct()
    fun bind(plan:TaskPlan,requested:ScreenSnapshot,live:ScreenSnapshot):TaskPlan? {
        if(requested.packageName!=live.packageName || requested.windowId!=live.windowId || requested.sourceEpoch!=live.sourceEpoch ||
            live.snapshotId<requested.snapshotId || live.capturedAtMillis<requested.capturedAtMillis || live.truncated ||
            requested.blockingPopup!=live.blockingPopup || requested.platformActionBarBounds!=live.platformActionBarBounds)return null
        val mapping=mutableMapOf<Int,Int>()
        for(id in references(plan)){
            val before=requested.allNodes.singleOrNull{it.id==id}?:return null
            val now=if(requested===live)before.takeIf{it.visibleToUser && !it.password && !it.sensitiveContent} else current(before,live)
            mapping[id]=now?.id ?:return null
        }
        if(mapping.values.distinct().size!=mapping.size)return null
        fun ids(values:List<Int>)=values.map{mapping.getValue(it)}
        return plan.copy(targetNodeId=plan.targetNodeId?.let{mapping.getValue(it)},
            conditions=plan.conditions.map{it.copy(evidenceNodeIds=ids(it.evidenceNodeIds),basis=it.basis?.let{b->b.copy(evidenceNodeIds=ids(b.evidenceNodeIds))})},
            conditionEdits=plan.conditionEdits.map{it.copy(basis=it.basis.copy(evidenceNodeIds=ids(it.basis.evidenceNodeIds)))},
            tasks=plan.tasks.map{it.copy(nodeIds=ids(it.nodeIds))},relevantNodeIds=ids(plan.relevantNodeIds),
            actionGates=plan.actionGates.map{it.copy(targetNodeId=mapping.getValue(it.targetNodeId))},
            newEventNodeIds=ids(plan.newEventNodeIds),outcomeNodeIds=ids(plan.outcomeNodeIds),scopeNodeIds=ids(plan.scopeNodeIds))
    }
}
