package com.example.aplassistmvp0.v3

internal enum class ConditionOrigin { REQUEST, ANSWER, INFERRED }
internal data class ConditionBasis(val kind:ConditionOrigin,val key:String?=null,val quote:String,
    val rationale:String="",val evidenceNodeIds:List<Int> = emptyList())
internal enum class ConditionEditKind { REPLACE, RETIRE }
internal data class ConditionEdit(val requirementId:Long,val expectedVersion:Int,val kind:ConditionEditKind,
    val reason:String,val basis:ConditionBasis)
internal data class AnswerAuthority(val revision:Long,val requirementIds:Set<Long>)
/** Desired requirements only. Current SATISFIED evidence is never cached here. */
internal data class OwnedRequirement(val id:Long,val version:Int,val description:String,val required:Boolean,
    val basis:ConditionBasis,val changedAtRevision:Long,val retired:Boolean=false,val staleAnswerKey:String?=null,
    val lastChangeReason:String="",val phase:ConditionPhase=ConditionPhase.OUTCOME,val expectations:List<ValueExpectation> = emptyList())
internal data class ConditionReview(val problem:String?=null,val requirements:List<OwnedRequirement> = emptyList(),val plan:TaskPlan?=null)

/** Pure transactional reconciliation. A model proposes definitions and edits, never local identifiers. */
internal object GoalConditions {
    private fun normalized(s:String)=s.trim().replace(Regex("\\s+")," ")
    private fun same(a:OwnedRequirement,b:GoalCondition)=normalized(a.description)==normalized(b.description) && a.required==b.required && a.phase==b.phase &&
        a.expectations.toSet()==b.expectations.toSet()
    private fun strengthens(a:OwnedRequirement,b:GoalCondition)=a.staleAnswerKey==null && normalized(a.description)==normalized(b.description) &&
        a.required==b.required && a.phase==b.phase && b.expectations.size>a.expectations.size && b.expectations.toSet().containsAll(a.expectations)
    private fun basisProblem(goal:TaskGoal,b:ConditionBasis):String? {
        if(b.quote.isBlank() || b.quote.length>500 || b.rationale.length>320 || (b.key?.length ?:0)>100)return "invalid_condition_basis"
        val source=when(b.kind){
            ConditionOrigin.REQUEST,ConditionOrigin.INFERRED->if(b.key==null)goal.request else null
            ConditionOrigin.ANSWER->b.key?.let{goal.answers[it]}
        }
        if(source==null || !source.contains(b.quote))return "unanchored_condition_basis"
        if(b.kind==ConditionOrigin.INFERRED && b.rationale.isBlank())return "unexplained_inferred_condition"
        return null
    }
    private fun editProblem(goal:TaskGoal,owned:OwnedRequirement,edit:ConditionEdit,report:GoalCondition?):String? {
        basisProblem(goal,edit.basis)?.let{return it}
        if(edit.reason.isBlank() || edit.reason.length>320)return "missing_condition_edit_reason"
        val b=edit.basis
        if(edit.kind==ConditionEditKind.REPLACE && report!=null && strengthens(owned,report) && b.kind==ConditionOrigin.INFERRED &&
            b.evidenceNodeIds.isNotEmpty() && b.rationale.isNotBlank())return null
        if(b.kind==ConditionOrigin.ANSWER){
            val authority=b.key?.let{goal.answerAuthorities[it]}
            if(authority==null || owned.id !in authority.requirementIds || authority.revision<=owned.changedAtRevision || authority.revision>goal.revision)
                return "unauthorized_condition_edit"
            return null
        }
        // Explicit user requirements cannot be relaxed by model reinterpretation or an unrelated screen.
        if(owned.basis.kind!=ConditionOrigin.INFERRED)return "unauthorized_condition_edit"
        if(b.evidenceNodeIds.isEmpty() || b.rationale.isBlank())return "unsupported_inference_correction"
        return null
    }
    fun review(goal:TaskGoal,input:TaskPlan):ConditionReview {
        val proposal=input.copy(conditions=input.conditions.map{it.copy(expectations=it.expectations.map(ValueExpectation::canonical))})
        fun reject(reason:String)=ConditionReview(problem=reason)
        if(proposal.conditions.any{c->c.expectations.size>30 || c.expectations.any{!it.valid()} ||
            c.expectations.map{it.property to it.subject}.distinct().size!=c.expectations.size})return reject("invalid_value_expectation")
        if(proposal.conditionEdits.size>30)return reject("oversized_condition_edits")
        val owned=goal.requirements.associateBy{it.id}.toMutableMap()
        val reports=proposal.conditions.filter{it.requirementId!=null}
        if(reports.map{it.requirementId}.distinct().size!=reports.size)return reject("duplicate_requirement_report")
        val edits=proposal.conditionEdits.associateBy{it.requirementId}
        if(edits.size!=proposal.conditionEdits.size)return reject("duplicate_condition_edit")
        for(edit in proposal.conditionEdits){
            val before=owned[edit.requirementId] ?:return reject("unknown_requirement")
            if(before.version!=edit.expectedVersion || before.retired && edit.kind!=ConditionEditKind.REPLACE)return reject("stale_requirement_version")
            val report=reports.singleOrNull{it.requirementId==before.id}
            editProblem(goal,before,edit,report)?.let{return reject(it)}
            if(edit.kind==ConditionEditKind.RETIRE){
                if(report!=null)return reject("retired_requirement_reported")
                owned[before.id]=before.copy(version=before.version+1,retired=true,staleAnswerKey=null,
                    changedAtRevision=goal.revision,lastChangeReason=edit.reason)
            }else if(report==null)return reject("replacement_not_reported")
        }
        var serial=owned.keys.maxOrNull() ?:0
        val bound=mutableListOf<GoalCondition>()
        for(c in proposal.conditions){
            if((c.requirementId==null)!=(c.requirementVersion==null))return reject("partial_requirement_reference")
            val id=c.requirementId
            val requirement:OwnedRequirement
            if(id==null){
                val basis=c.basis ?:return reject("missing_condition_basis")
                basisProblem(goal,basis)?.let{return reject(it)}
                // An exact reword-free duplicate must use its already issued reference.
                if(owned.values.any{it.phase==c.phase && normalized(it.description)==normalized(c.description)})return reject("duplicate_requirement_definition")
                requirement=OwnedRequirement(++serial,1,normalized(c.description),c.required,
                    basis.copy(evidenceNodeIds=emptyList()),goal.revision,phase=c.phase,expectations=c.expectations.toList())
            }else{
                val before=goal.requirements.singleOrNull{it.id==id} ?:return reject("unknown_requirement")
                if(before.version!=c.requirementVersion || before.retired && edits[id]?.kind!=ConditionEditKind.REPLACE)return reject("stale_requirement_version")
                if(before.phase!=c.phase)return reject("requirement_phase_changed")
                val edit=edits[id]
                if(edit==null){
                    if(c.basis!=null || !same(before,c))return reject("unexplained_requirement_change")
                    if(before.staleAnswerKey!=null)return reject("answer_requires_reconciliation")
                    requirement=before
                }else{
                    if(edit.kind!=ConditionEditKind.REPLACE || c.basis!=null)return reject("replacement_basis_mismatch")
                    val retainedOrigin=edit.basis.kind==ConditionOrigin.INFERRED && strengthens(before,c)
                    requirement=before.copy(version=before.version+1,description=normalized(c.description),required=c.required,
                        basis=if(retainedOrigin)before.basis else edit.basis.copy(evidenceNodeIds=emptyList()),changedAtRevision=goal.revision,retired=false,staleAnswerKey=null,lastChangeReason=edit.reason,
                        expectations=c.expectations.toList())
                }
            }
            owned[requirement.id]=requirement
            bound+=c.copy(description=requirement.description,required=requirement.required,
                requirementId=requirement.id,requirementVersion=requirement.version,basis=null,expectations=requirement.expectations)
        }
        if(owned.size>128 || owned.values.count{!it.retired}>30)return reject("condition_history_limit")
        proposal.question?.let{q->
            if(q.conditionKeys.distinct().size!=q.conditionKeys.size || q.conditionKeys.any{key->bound.none{it.key==key}})
                return reject("unknown_question_condition")
        }
        if(proposal.kind==NextKind.COMPLETE){
            val byId=bound.associateBy{it.requirementId}
            for(r in owned.values.filter{!it.retired && it.required && it.phase==ConditionPhase.OUTCOME}){
                val current=byId[r.id] ?:return reject("missing_owned_requirement")
                if(r.staleAnswerKey!=null || current.truth!=Truth.SATISFIED || current.evidence!=EvidenceKind.OBSERVED || current.evidenceNodeIds.isEmpty())
                    return reject("unverified_owned_requirement")
            }
        }
        return ConditionReview(requirements=owned.values.sortedBy{it.id},plan=proposal.copy(conditions=bound,conditionEdits=emptyList()))
    }
}
