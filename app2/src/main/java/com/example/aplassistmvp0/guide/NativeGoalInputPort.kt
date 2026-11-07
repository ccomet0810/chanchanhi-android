package com.example.aplassistmvp0.guide

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Interpretation is an external semantic hypothesis, not an authorization from the UI. */
internal sealed interface NativeGoalInterpretation {
    data class Proposal(val packetJson:String,val displayedGoal:String,val question:String="") : NativeGoalInterpretation
    data class Question(val message:String) : NativeGoalInterpretation
}
internal fun interface NativeGoalInterpreter {
    suspend fun interpret(capture:NativeGoalOwner.InterpretationCapture):NativeGoalInterpretation
}

/** Same owner identity as the future engine authority; implementations also own provider/knowledge checks. */
internal interface NativeGoalBindingProvider {
    val owner:NativeGoalOwner
    fun binding(reference:GuideGoalRef,inputId:String,displayedGoal:String):GuideGoalHandoff?
}

/** Main-thread input actor. Holds original receipts/projections; the NativeGoalOwner is the only ledger. */
internal class NativeGoalInputPort(
    private val owner:NativeGoalOwner,
    private val interpreter:NativeGoalInterpreter,
    private val bindings:NativeGoalBindingProvider,
) : AssistantGoalInputPort {
    override val ownerId:String get()=owner.ownerId
    private val thread=Thread.currentThread()
    private val updates=MutableSharedFlow<Unit>(replay=1,extraBufferCapacity=1)
    private class Entry(val input:NativeGoalOwner.Input,var anchor:NativeGoalOwner.Snapshot) {
        var job:NativeGoalOwner.Job?=null
        var result:AssistantGoalResolution?=null
        var accepted:Accepted?=null
        var abandoned=false
    }
    private data class Accepted(val snapshot:NativeGoalOwner.Snapshot,val reference:GuideGoalRef,val projection:String)
    private val entries=linkedMapOf<String,Entry>()
    init {require(bindings.owner===owner){"SAME_NATIVE_OWNER_REQUIRED"}}
    private fun checkThread(){check(Thread.currentThread()===thread){"NATIVE_INPUT_ACTOR_THREAD_REQUIRED"}}
    private fun entry(ticket:AssistantGoalInputTicket):Entry?=entries[ticket.id]?.takeIf{
        ticket.ownerId==ownerId && ticket.rawInput==it.input.utterance && !it.abandoned}
    override fun isCurrent(ticket:AssistantGoalInputTicket):Boolean {
        checkThread();val current=entry(ticket)?:return false
        return owner.consumeSnapshot(current.anchor){}
    }
    override fun register(rawInput:String,kind:AssistantGoalInputKind,previousInput:AssistantGoalInputTicket?,previousGoal:GuideGoalHandoff?):AssistantGoalInputTicket {
        checkThread()
        if(previousInput!=null)require(previousInput.ownerId==ownerId){"FOREIGN_PREVIOUS_INPUT"}
        if(previousGoal!=null)require(previousGoal.ownerId==ownerId){"FOREIGN_PREVIOUS_GOAL"}
        val context=mapOf("kind" to kind.name,"previous_input" to previousInput?.id.orEmpty())
        var original:NativeGoalOwner.Input?=null
        if(kind==AssistantGoalInputKind.CLARIFICATION){
            val parent=previousInput?.let(::entry)?:error("CURRENT_CLARIFICATION_PARENT_REQUIRED")
            check(parent.result !is AssistantGoalResolution.Stopped){"CLOSED_CLARIFICATION_PARENT"}
            check(owner.consumeSnapshot(parent.anchor){original=owner.registerUserTurn(rawInput,context)}){"STALE_CLARIFICATION_PARENT"}
        }else original=owner.registerUserTurn(rawInput,context)
        val input=requireNotNull(original)
        entries[input.requestId]=Entry(input,owner.snapshot())
        updates.tryEmit(Unit)
        return AssistantGoalInputTicket(ownerId,input.requestId,input.utterance)
    }
    override fun abandon(ticket:AssistantGoalInputTicket) {
        checkThread();val current=entry(ticket)?:return
        owner.consumeSnapshot(current.anchor){owner.closeLocally()}
        current.job?.let(owner::retire);current.abandoned=true;updates.tryEmit(Unit)
    }
    override suspend fun resolve(ticket:AssistantGoalInputTicket):AssistantGoalResolution {
        checkThread();val current=entry(ticket)?:error("UNKNOWN_NATIVE_INPUT")
        check(isCurrent(ticket)){"STALE_NATIVE_INPUT"}
        current.result?.let{return it}
        // A provider outage must not reinterpret an already committed user turn.
        current.accepted?.let{return publish(current,ticket,it)}
        val job=owner.prepare(current.input);current.job=job
        val capture=requireNotNull(owner.interpretationCapture(job))
        try {
            val interpretation=interpreter.interpret(capture)
            checkThread();check(isCurrent(ticket)){"STALE_NATIVE_INTERPRETATION"}
            check(current.job==job && owner.interpretationCapture(job)!=null){"RETIRED_NATIVE_INTERPRETATION"}
            if(interpretation is NativeGoalInterpretation.Question){
                val result=AssistantGoalResolution.Question(message(interpretation.message))
                current.result=result;return result
            }
            interpretation as NativeGoalInterpretation.Proposal
            val mode=GoalCanonicalJson.parse(interpretation.packetJson).goalObject()["mode"]
            val projection=interpretation.displayedGoal.trim()
            if(mode!="close")check(projection.isNotEmpty() && projection.length<=500 && GoalCanonicalJson.validText(projection)){"INVALID_GOAL_PROJECTION"}
            val outcome=owner.receive(job,interpretation.packetJson)
            check(outcome.snapshot.ownerId==ownerId)
            check(owner.consumeSnapshot(outcome.snapshot){}){"REVOKED_NATIVE_OUTCOME"}
            current.anchor=outcome.snapshot
            if(outcome.kind !in setOf("APPLY","KEEP","CLOSE")) {
                val result=AssistantGoalResolution.Question(message(interpretation.question))
                current.result=result;return result
            }
            // Inactivity alone is not success. Only this accepted close interpretation stops guidance.
            if(mode=="close"){
                val result=AssistantGoalResolution.Stopped("안내를 중단했어요.")
                current.result=result;updates.tryEmit(Unit);return result
            }
            var view:NativeGoalOwner.View?=null
            check(owner.consumeSnapshot(outcome.snapshot){view=owner.view()}){"REVOKED_NATIVE_VIEW"}
            val accepted=requireNotNull(view)
            if(accepted.report?.status!="NO_CONTRADICTION"){
                val result=AssistantGoalResolution.Question(message(interpretation.question))
                current.result=result;updates.tryEmit(Unit);return result
            }
            val reference=requireNotNull(accepted.reference)
            val committed=Accepted(outcome.snapshot,reference,projection)
            current.accepted=committed
            return publish(current,ticket,committed)
        }catch(cancelled:CancellationException){throw cancelled}
        finally {owner.retire(job);if(current.job==job)current.job=null}
    }
    private fun publish(current:Entry,ticket:AssistantGoalInputTicket,accepted:Accepted):AssistantGoalResolution.Ready {
        check(owner.consumeSnapshot(accepted.snapshot){}){"REVOKED_NATIVE_BINDING_PREPARATION"}
        val raw=bindings.binding(accepted.reference,ticket.id,accepted.projection)?:error("NATIVE_BINDING_UNAVAILABLE")
        check(raw.ownerId==ownerId && raw.inputId==ticket.id && raw.displayedGoal==accepted.projection){"FOREIGN_NATIVE_BINDING"}
        check(owner.consumeSnapshot(accepted.snapshot){} && raw.runCurrent(null){} &&
            owner.consumeSnapshot(accepted.snapshot){}){"REVOKED_NATIVE_BINDING"}
        val result=AssistantGoalResolution.Ready(bound(raw,accepted.reference))
        current.result=result;updates.tryEmit(Unit);return result
    }
    private fun bound(original:GuideGoalHandoff,reference:GuideGoalRef)=object:GuideGoalHandoff by original {
        override fun consume(target:TargetApp?,effect:()->Unit):Boolean {
            var accepted=false;var performed=false
            val current=owner.consumeCurrent(reference){accepted=original.runCurrent(target){
                performed=owner.consumeCurrent(reference,effect)
            }}
            return current && accepted && performed
        }
        override suspend fun startGuide(target:TargetApp):Boolean {
            if(!runCurrent(target){})return false
            val started=original.startGuide(target)
            return started && runCurrent(target){}
        }
        override suspend fun knownRoute(installedApps:List<TargetApp>,origin:String?):GoalRoute? {
            check(runCurrent(null){}){"STALE_NATIVE_ROUTE"}
            val route=original.knownRoute(installedApps,origin)
            check(runCurrent(null){}){"STALE_NATIVE_ROUTE_RESULT"}
            return route
        }
        override fun changes():Flow<Unit> = channelFlow {
            val inputChanges=launch {updates.collect{send(Unit)}}
            try {original.changes().collect{send(Unit)}} finally {inputChanges.cancel()}
        }
    }
    private fun message(text:String):String {
        val fallback="원하는 작업을 조금 더 구체적으로 말씀해주세요."
        if(!GoalCanonicalJson.validText(text))return fallback
        val shortened=text.trim().take(240)
        return (if(shortened.lastOrNull()?.isHighSurrogate()==true)shortened.dropLast(1) else shortened).ifBlank{fallback}
    }
}
