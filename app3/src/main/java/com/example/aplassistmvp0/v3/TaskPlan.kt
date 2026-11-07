package com.example.aplassistmvp0.v3

/** The goal belongs to the session, never to a model response or a view. */
internal data class TaskGoal(val sessionId:Long,val revision:Long,val request:String,
    val targetPackage:String,val targetLabel:String,val answers:Map<String,String> = emptyMap(),
    val scopeHistory:List<TaskScopeObservation> = emptyList(),
    val operationHistory:List<OperationRecord> = emptyList(),val requiresNewEvent:Boolean=false,
    val requirements:List<OwnedRequirement> = emptyList(),val answerAuthorities:Map<String,AnswerAuthority> = emptyMap(),
    val preparationHistory:List<PreparationBinding> = emptyList(),val inputProgress:List<InputProgress> = emptyList())

/** Native declaration only; no private text, content digest or local edit version is exported. */
internal data class InputProgress(val inputId:Long,val nodeId:Int?,val userFinished:Boolean,val receiptId:Long?)

/** Locally issued history. Model task names never mint an operation or a native attempt. */
internal data class OperationRecord(val operationId:Long,val receiptId:Long,val attempt:Int,val goalRevision:Long,
    val state:CommitOutcome,val interactionVerified:Boolean,val label:String,val role:String,val resource:String)

internal data class ObservationRef(val packageName:String,val windowId:Int,val sequence:Long,val capturedAt:Long,val sourceEpoch:Long=0)
internal data class PlanTicket(val sessionId:Long,val goalRevision:Long,val requestId:Long,val observation:ObservationRef)
internal data class TaskScopeObservation(val path:String,val role:String,val publicLabel:String,val resource:String,
    val observation:ObservationRef)
internal enum class NextKind { GUIDE, ASK, OBSERVE, WAIT, RECOVER, COMPLETE }
internal enum class Truth { SATISFIED, UNSATISFIED, UNKNOWN, NOT_APPLICABLE }
internal enum class EvidenceKind { OBSERVED, USER_REPORTED, INFERRED, UNKNOWN }
internal enum class RelationKind { ALL, ANY, BEFORE, SET, CONDITIONAL, MAINTAIN }
internal enum class Operation { TAP, DOUBLE_TAP, LONG_PRESS, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT,
    ADJUST, SELECT, INPUT, BACK, OPEN, NONE }
internal enum class WaitKind { USER_INPUT, USER_ACTIVITY, SCREEN_TRANSITION, NONE }
internal enum class GoalKind { STATE, CHANGE, NEW_EVENT, ACTIVITY, INSPECT, UNKNOWN }
internal enum class ActionEffect { NONE, PREPARE, COMMIT, START_ACTIVITY }
internal enum class CommitOutcome { NONE, PENDING, SUCCEEDED, FAILED, UNKNOWN }
internal enum class ConditionPhase { OUTCOME, PREPARATION }

/** Locally retained control-to-requirement references, never cached satisfaction. */
internal data class PreparationBinding(val role:String,val resource:String,val label:String,val requirementIds:Set<Long>)
internal data class ActionGate(val targetNodeId:Int,val conditionKeys:List<String>)

internal data class GoalCondition(val key:String,val description:String,val required:Boolean,
    val truth:Truth,val evidence:EvidenceKind,val evidenceNodeIds:List<Int>,
    val requirementId:Long?=null,val requirementVersion:Int?=null,val basis:ConditionBasis?=null,
    val phase:ConditionPhase=ConditionPhase.OUTCOME,val expectations:List<ValueExpectation> = emptyList())
internal data class TaskStep(val key:String,val description:String,val nodeIds:List<Int>,val dependsOn:List<String>)
internal data class TaskRelation(val kind:RelationKind,val members:List<String>)
internal data class PlanQuestion(val key:String,val message:String,val neededNow:Boolean,
    val changesGuidance:Boolean,val canEnterInApp:Boolean,val conditionKeys:List<String> = emptyList())
internal data class TaskPlan(val kind:NextKind,val summary:String,val goalKind:GoalKind,
    val conditions:List<GoalCondition>,val tasks:List<TaskStep>,val relations:List<TaskRelation>,
    val operation:Operation,val targetNodeId:Int?,val message:String,val question:PlanQuestion?,
    val waitKind:WaitKind,val relevantNodeIds:List<Int>,val newEventNodeIds:List<Int>,
    val effect:ActionEffect=ActionEffect.NONE,val commitOutcome:CommitOutcome=CommitOutcome.NONE,
    val outcomeNodeIds:List<Int> = emptyList(),val scopeNodeIds:List<Int> = emptyList(),
    val outcomeReceiptId:Long?=null,val conditionEdits:List<ConditionEdit> = emptyList(),
    val actionGates:List<ActionGate> = emptyList(),val groundingComplete:Boolean=true)

/** Structural validation is not a claim that a model understood the app correctly. */
internal object TaskPlanValidation {
    fun problem(plan:TaskPlan,goal:TaskGoal,nodeIds:Set<Int>):String? {
        if(plan.message.isBlank() || plan.message.length>320 || plan.summary.length>500)return "invalid_message"
        if(plan.tasks.size>30 || plan.conditions.size>30 || plan.relations.size>30)return "oversized_plan"
        val tasks=plan.tasks.associateBy{it.key}
        if(tasks.size!=plan.tasks.size || plan.conditions.map{it.key}.distinct().size!=plan.conditions.size)return "duplicate_key"
        if(plan.tasks.any{it.key.isBlank() || it.description.isBlank() || it.dependsOn.any{key->key !in tasks}})return "unknown_dependency"
        val visiting=mutableSetOf<String>();val visited=mutableSetOf<String>()
        fun cyclic(key:String):Boolean {
            if(key in visited)return false
            if(!visiting.add(key))return true
            if(tasks.getValue(key).dependsOn.any(::cyclic))return true
            visiting.remove(key);visited.add(key);return false
        }
        if(tasks.keys.any(::cyclic))return "dependency_cycle"
        if(plan.relations.any{it.members.isEmpty() || it.members.distinct().size!=it.members.size || it.members.any{key->key !in tasks}})return "unknown_relation_member"
        if(plan.actionGates.size>30 || plan.actionGates.map{it.targetNodeId}.distinct().size!=plan.actionGates.size)return "invalid_action_gates"
        if(plan.actionGates.any{g->g.conditionKeys.isEmpty() || g.conditionKeys.distinct().size!=g.conditionKeys.size ||
            g.conditionKeys.any{key->plan.conditions.none{it.key==key && it.phase==ConditionPhase.PREPARATION}} ||
            plan.conditions.none{it.key in g.conditionKeys && it.required}})return "invalid_preparation_reference"
        if(plan.effect==ActionEffect.COMMIT && plan.actionGates.none{it.targetNodeId==plan.targetNodeId})return "missing_preparation_gate"
        if(plan.effect==ActionEffect.COMMIT){
            val gate=plan.actionGates.single{it.targetNodeId==plan.targetNodeId}
            if(plan.conditions.filter{it.key in gate.conditionKeys && it.required}.any{
                it.truth!=Truth.SATISFIED || it.evidence!=EvidenceKind.OBSERVED || it.evidenceNodeIds.isEmpty()})return "unready_preparation"
        }
        val references=plan.relevantNodeIds+plan.newEventNodeIds+plan.outcomeNodeIds+plan.scopeNodeIds+plan.tasks.flatMap{it.nodeIds}+
            plan.actionGates.map{it.targetNodeId}+
            plan.conditions.flatMap{it.basis?.evidenceNodeIds.orEmpty()}+plan.conditionEdits.flatMap{it.basis.evidenceNodeIds}+
            plan.conditions.flatMap{it.evidenceNodeIds}+listOfNotNull(plan.targetNodeId)
        if(references.any{it !in nodeIds})return "invented_node"
        if(plan.conditions.any{it.key.isBlank() || it.description.isBlank() ||
            it.evidence==EvidenceKind.OBSERVED && it.truth==Truth.SATISFIED && it.evidenceNodeIds.isEmpty()})return "unsupported_condition"
        if(plan.conditions.any{c->c.expectations.size>30 || c.expectations.any{!it.valid()} ||
            c.expectations.map{it.property to it.subject}.distinct().size!=c.expectations.size})return "invalid_value_expectation"
        if(plan.kind==NextKind.GUIDE && (plan.targetNodeId==null || plan.operation==Operation.NONE))return "missing_action"
        if(plan.effect==ActionEffect.COMMIT && (plan.kind!=NextKind.GUIDE || plan.operation!=Operation.TAP))return "invalid_commit_action"
        if(plan.commitOutcome in setOf(CommitOutcome.SUCCEEDED,CommitOutcome.FAILED) && plan.outcomeNodeIds.isEmpty())return "missing_outcome_evidence"
        if((plan.commitOutcome==CommitOutcome.NONE)!=(plan.outcomeReceiptId==null))return "missing_or_unexpected_receipt"
        if(plan.outcomeReceiptId!=null && goal.operationHistory.none{it.receiptId==plan.outcomeReceiptId})return "unknown_receipt"
        if(plan.kind==NextKind.ASK){
            val q=plan.question ?: return "missing_question"
            if(q.key.isBlank() || q.message.isBlank() || q.message.length>240)return "invalid_question"
            if(!q.neededNow || !q.changesGuidance || q.canEnterInApp)return "unnecessary_question"
            if(q.key in goal.answers)return "repeated_question"
        }else if(plan.question!=null)return "unexpected_question"
        if(plan.kind==NextKind.WAIT && plan.waitKind==WaitKind.NONE)return "missing_wait_reason"
        if(plan.kind==NextKind.COMPLETE){
            val required=plan.conditions.filter{it.required && it.phase==ConditionPhase.OUTCOME}
            if(required.isEmpty() || required.any{it.truth!=Truth.SATISFIED || it.evidence!=EvidenceKind.OBSERVED || it.evidenceNodeIds.isEmpty()})
                return "unverified_completion"
            if(plan.goalKind==GoalKind.UNKNOWN)return "unknown_goal_kind"
            if(goal.requiresNewEvent || plan.goalKind==GoalKind.NEW_EVENT){
                if(plan.newEventNodeIds.isEmpty())return "missing_new_event_evidence"
                if(plan.commitOutcome!=CommitOutcome.SUCCEEDED || plan.outcomeReceiptId==null)return "unverified_event_completion"
            }
        }
        return GoalConditions.review(goal,plan).problem
    }
}

/** One authority for cancellation, answer revisions and request lifetime. No UI can revive a ticket. */
internal class TaskSession {
    var goal:TaskGoal?=null;private set
    var ticket:PlanTicket?=null;private set
    var question:PlanQuestion?=null;private set
    var plan:TaskPlan?=null;private set
    private var serial=0L
    private var requests=0L
    private val questions=mutableMapOf<String,Pair<PlanQuestion,Set<Long>>>()
    fun start(request:String,packageName:String,label:String):TaskGoal {
        require(request.isNotBlank() && request.length<=500)
        close();return TaskGoal(++serial,1,request,packageName,label).also{goal=it}
    }
    fun issue(observation:ObservationRef):PlanTicket {
        val current=checkNotNull(goal)
        require(observation.packageName==current.targetPackage)
        return PlanTicket(current.sessionId,current.revision,++requests,observation).also{ticket=it}
    }
    fun current(value:PlanTicket)=goal?.let{it.sessionId==value.sessionId && it.revision==value.goalRevision && ticket===value}==true
    fun review(proposal:TaskPlan):ConditionReview {
        val current=goal ?:return ConditionReview(problem="missing_goal")
        val review=GoalConditions.review(current,proposal)
        if(review.problem!=null)return review
        val accepted=review.plan!!
        val nextQuestion=accepted.question
        val nextScope=accepted.conditions.filter{it.key in nextQuestion?.conditionKeys.orEmpty()}.mapNotNull{it.requirementId}.toSet()
        nextQuestion?.let{q->questions[q.key]?.let{previous->
            if(previous.second!=nextScope || previous.first.message.trim()!=q.message.trim()){
                return ConditionReview(problem="question_scope_changed")
            }
        }}
        return review
    }
    fun accept(value:PlanTicket,proposal:TaskPlan):Boolean {
        if(!current(value))return false
        val current=goal!!
        val review=review(proposal)
        if(review.problem!=null){invalidate();return false}
        val accepted=review.plan!!
        val nextScope=accepted.conditions.filter{it.key in accepted.question?.conditionKeys.orEmpty()}.mapNotNull{it.requirementId}.toSet()
        goal=current.copy(requirements=review.requirements,requiresNewEvent=current.requiresNewEvent || accepted.goalKind==GoalKind.NEW_EVENT)
        ticket=null;plan=accepted;question=accepted.question
        question?.let{q->questions[q.key]=q to nextScope}
        return true
    }
    fun answer(key:String,text:String):Boolean {
        val current=goal ?:return false
        val q=question ?:return false
        if(q.key!=key || text.isBlank() || text.length>500)return false
        val nextRevision=current.revision+1
        val scope=questions[key]?.second.orEmpty()
        goal=current.copy(revision=nextRevision,answers=current.answers+(key to text.trim()),
            answerAuthorities=current.answerAuthorities+(key to AnswerAuthority(nextRevision,scope)),
            requirements=current.requirements.map{if(it.id in scope && !it.retired)it.copy(staleAnswerKey=key)else it})
        invalidate();question=null;plan=null;return true
    }
    fun rememberScope(facts:List<TaskScopeObservation>){
        val current=goal ?:return
        val combined=(current.scopeHistory+facts).distinctBy{listOf(it.path,it.role,it.publicLabel,it.resource,it.observation.sourceEpoch)}.takeLast(20)
        goal=current.copy(scopeHistory=combined)
    }
    fun rememberOperations(records:List<OperationRecord>){
        val current=goal ?:return
        if(current.operationHistory==records)return
        goal=current.copy(operationHistory=records.toList())
    }
    fun rememberPreparations(records:List<PreparationBinding>){
        val current=goal ?:return
        goal=current.copy(preparationHistory=records.map{it.copy(requirementIds=it.requirementIds.toSet())})
    }
    fun rememberInputs(records:List<InputProgress>){goal=goal?.copy(inputProgress=records.toList())}
    fun beginAnswerEdit(key:String):Boolean {
        if(key !in goal?.answers.orEmpty())return false
        val recorded=questions[key] ?:return false
        invalidate();question=recorded.first;plan=null;return true
    }
    fun invalidate(){ticket=null}
    fun close(){goal=null;ticket=null;question=null;plan=null;questions.clear()}
}
