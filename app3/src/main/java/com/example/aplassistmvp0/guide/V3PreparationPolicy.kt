package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** Preparation is checked immediately before guidance, separately from post-action outcome proof. */
internal object V3PreparationPolicy {
    internal data class Review(val bindings:List<PreparationBinding>,val plan:TaskPlan,val problem:String?=null,val evidenceNodeIds:List<Int> = emptyList())
    private fun identity(node:AccessibilityNodeModel):PreparationBinding? {
        if(!node.visibleToUser || node.editable || node.password || node.sensitiveContent ||
            AccessibilityNodePrivacy.hasHiddenIdentifier(node))return null
        val label=GuidePolicy.label(node);val resource=node.viewIdResourceName.orEmpty()
        if(label.isBlank() && resource.isBlank())return null
        return PreparationBinding(node.className.orEmpty(),resource,label,emptySet())
    }
    private fun overlaps(a:PreparationBinding,b:PreparationBinding)=
        a.resource.isNotBlank() && a.resource==b.resource || a.label.isNotBlank() && a.label==b.label && a.role==b.role
    fun review(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot):Review {
        V3ValueEvidence.problem(plan,frame)?.let{return Review(goal.preparationHistory,plan,it)}
        val bindings=goal.preparationHistory.toMutableList()
        fun reject(reason:String,effective:TaskPlan=plan)=Review(bindings.toList(),effective,reason)
        // Register the whole proposal atomically; one bad binding cannot teach a partial control inventory.
        for(gate in plan.actionGates){
            val node=frame.allNodes.singleOrNull{it.id==gate.targetNodeId} ?:return Review(goal.preparationHistory,plan,"missing_preparation_control")
            val control=identity(node) ?:return Review(goal.preparationHistory,plan,"invalid_preparation_control")
            if(frame.allNodes.mapNotNull(::identity).count{overlaps(it,control)}!=1)
                return Review(goal.preparationHistory,plan,"ambiguous_preparation_control")
            val reports=gate.conditionKeys.mapNotNull{key->plan.conditions.singleOrNull{it.key==key}}
            val ids=reports.mapNotNull{it.requirementId}.toSet()
            if(reports.size!=gate.conditionKeys.size || ids.size!=reports.size || ids.any{id->goal.requirements.none{it.id==id && !it.retired && it.phase==ConditionPhase.PREPARATION}})
                return Review(goal.preparationHistory,plan,"unowned_preparation")
            val matches=bindings.filter{overlaps(it,control)}
            if(matches.size>1)return Review(goal.preparationHistory,plan,"ambiguous_preparation_binding")
            val previous=matches.singleOrNull()
            if(previous!=null)bindings[bindings.indexOf(previous)]=previous.copy(requirementIds=previous.requirementIds+ids)
            else bindings+=control.copy(requirementIds=ids)
        }
        if(bindings.size>64)return Review(goal.preparationHistory,plan,"preparation_history_limit")
        if(plan.kind!=NextKind.GUIDE)return Review(bindings,plan)
        val control=frame.allNodes.singleOrNull{it.id==plan.targetNodeId}?.let(::identity)
        val matches=control?.let{c->bindings.filter{overlaps(it,c)}}.orEmpty()
        if(matches.isEmpty())return if(plan.effect==ActionEffect.COMMIT)reject("missing_preparation_binding")else Review(bindings,plan)
        if(matches.size!=1 || control==null || frame.allNodes.mapNotNull(::identity).count{overlaps(it,control)}!=1)
            return reject("ambiguous_preparation_binding")
        // An earlier declared commit cannot become harmless by omitting its gate or changing effect.
        val effective=plan.copy(effect=ActionEffect.COMMIT)
        if(plan.operation!=Operation.TAP)return reject("invalid_prepared_commit_action",effective)
        if(frame.truncated || !plan.groundingComplete)return reject("incomplete_preparation_observation",effective)
        val referenced=matches.single().requirementIds.map{id->goal.requirements.singleOrNull{it.id==id}
            ?:return reject("missing_owned_preparation",effective)}
        val owned=referenced.filter{!it.retired && it.required}
        if(owned.isEmpty())return reject("missing_required_preparation",effective)
        val evidence=mutableSetOf<Int>()
        for(requirement in owned){
            if(requirement.phase!=ConditionPhase.PREPARATION || requirement.staleAnswerKey!=null)return reject("stale_preparation",effective)
            val report=plan.conditions.singleOrNull{it.requirementId==requirement.id} ?:return reject("missing_preparation_report",effective)
            if(report.requirementVersion!=requirement.version || report.phase!=ConditionPhase.PREPARATION ||
                report.truth!=Truth.SATISFIED || report.evidence!=EvidenceKind.OBSERVED || report.evidenceNodeIds.isEmpty())
                return reject("unready_preparation",effective)
            if(report.evidenceNodeIds.any{id->frame.allNodes.singleOrNull{it.id==id}?.let{it.visibleToUser && !it.password && !it.sensitiveContent}!=true})
                return reject("unavailable_preparation_evidence",effective)
            evidence+=report.evidenceNodeIds
        }
        return Review(bindings,effective,evidenceNodeIds=evidence.toList())
    }
}
