package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** Compares public values with owned desired values. It does not parse app-specific instructions. */
internal object V3ValueEvidence {
    internal enum class Result { MATCH, MISMATCH, UNKNOWN }
    private fun public(node:AccessibilityNodeModel,property:ValueProperty?=null)=node.visibleToUser && !node.password && !node.sensitiveContent &&
        (if(property==ValueProperty.INPUT_PRESENT)AccessibilityNodePrivacy.isEditable(node)else !AccessibilityNodePrivacy.isEditable(node)) &&
        !AccessibilityNodePrivacy.isSensitivePreview(node) && !AccessibilityNodePrivacy.hasHiddenIdentifier(node)
    private fun matches(check:ValueExpectation,node:AccessibilityNodeModel)=node.className==check.subject.role &&
        if(check.subject.resource.isNotBlank())node.viewIdResourceName==check.subject.resource else GuidePolicy.label(node)==check.subject.label
    fun subject(expectation:ValueExpectation,frame:ScreenSnapshot):AccessibilityNodeModel? {
        return frame.allNodes.filter{public(it,expectation.property) && matches(expectation,it)}.singleOrNull()
    }
    fun evaluate(expectation:ValueExpectation,node:AccessibilityNodeModel):Result {
        if(!expectation.valid() || !public(node,expectation.property))return Result.UNKNOWN
        val matched=when(expectation.property){
            ValueProperty.RANGE->{
                val r=node.range?.takeIf{it.usable} ?:return Result.UNKNOWN
                // Same decimal representation as the actual Float metadata placed in JSON.
                val desired=expectation.expectedNumber!!
                desired>=r.min.toString().toDouble() && desired<=r.max.toString().toDouble() && r.current.toString().toDouble()==desired
            }
            ValueProperty.CHECKED->{
                if(node.checkable!=true || node.checked !in setOf(0,1))return Result.UNKNOWN
                (node.checked==1)==expectation.expectedBoolean
            }
            ValueProperty.SELECTED->{
                val selected=node.selected ?:return Result.UNKNOWN
                selected==expectation.expectedBoolean
            }
            ValueProperty.INPUT_PRESENT->{
                // Existing parser metadata: nonblank text presence, not exact raw emptiness or writing completion.
                val present=node.hasInput ?:return Result.UNKNOWN
                present==expectation.expectedBoolean
            }
        }
        return if(matched)Result.MATCH else Result.MISMATCH
    }
    fun problem(plan:TaskPlan,frame:ScreenSnapshot):String? {
        for(c in plan.conditions){
            if(c.truth==Truth.SATISFIED)for(check in c.expectations){
                if(c.evidence!=EvidenceKind.OBSERVED)return "unverified_value_evidence"
                val node=subject(check,frame) ?:return "unverified_value_evidence"
                if(node.id !in c.evidenceNodeIds)return "unverified_value_evidence"
                when(evaluate(check,node)){
                    Result.MISMATCH->return "value_mismatch"
                    Result.UNKNOWN->return "unverified_value_evidence"
                    Result.MATCH->Unit
                }
            }
            if(c.phase==ConditionPhase.PREPARATION && c.required && c.truth in setOf(Truth.SATISFIED,Truth.UNSATISFIED)){
                for(id in c.evidenceNodeIds){
                    val node=frame.allNodes.singleOrNull{it.id==id} ?:continue
                    if(!public(node))continue
                    if(node.range?.usable==true && c.expectations.none{it.property==ValueProperty.RANGE && matches(it,node)})return "missing_value_expectation"
                    if(node.checkable==true && c.expectations.none{it.property==ValueProperty.CHECKED && matches(it,node)})return "missing_value_expectation"
                }
            }
        }
        return null
    }
}
