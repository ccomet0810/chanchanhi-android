package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** A user's scoped declaration of finished writing, never a claim about private content correctness. */
internal class V3InputCompletion {
    companion object {
        const val DONE_LABEL="입력했어요"
        const val EMPTY_DONE_LABEL="비워두었어요"
    }
    private data class Entry(val id:Long,val session:Long,var revision:Long,val pkg:String,val window:Int,val epoch:Long,
        var anchor:AccessibilityNodeModel,var scopes:List<AccessibilityNodeModel>,var message:String,var requirements:Set<Long>,
        var scopedPreparation:GuideScopedTap?,
        var receipt:Long?=null,var finishedRevision:Long?=null,var finishedPresence:Boolean?=null,
        var resumedBeforeNavigation:Boolean=false)
    data class Hold(val id:Long,val node:AccessibilityNodeModel,val message:String,val canFinish:Boolean,val completionLabel:String)
    private val entries=mutableListOf<Entry>()
    private var serial=0L
    private var receipts=0L
    private fun sameIdentity(a:AccessibilityNodeModel,b:AccessibilityNodeModel)=
        a.className==b.className && a.viewIdResourceName==b.viewIdResourceName &&
            AccessibilityNodePrivacy.publicInputLabel(a)==AccessibilityNodePrivacy.publicInputLabel(b)
    private fun input(n:AccessibilityNodeModel)=AccessibilityNodePrivacy.isEditable(n) && n.visibleToUser && n.enabled &&
        !n.password && !n.sensitiveContent && !n.bounds.isEmpty
    private fun visible(e:Entry,frame:ScreenSnapshot)=frame.packageName==e.pkg && frame.allNodes.any{input(it) && sameIdentity(e.anchor,it)}
    private fun current(e:Entry,frame:ScreenSnapshot):AccessibilityNodeModel? {
        if(frame.packageName!=e.pkg || frame.windowId!=e.window || frame.sourceEpoch!=e.epoch || frame.truncated)return null
        return frame.allNodes.filter{input(it) && sameIdentity(e.anchor,it)}.singleOrNull()?.takeIf{it.path==e.anchor.path}
    }
    private fun context(e:Entry,goal:TaskGoal,frame:ScreenSnapshot)=e.session==goal.sessionId && e.revision==goal.revision &&
        e.scopes.all{V3PlanGrounding.current(it,frame)!=null}
    private fun currentInScope(e:Entry,goal:TaskGoal,frame:ScreenSnapshot):AccessibilityNodeModel? {
        if(!context(e,goal,frame))return null
        return current(e,frame)?.takeIf{e.scopedPreparation?.retainsPreparationInput(it,frame)!=false}
    }
    private fun finished(e:Entry,goal:TaskGoal,frame:ScreenSnapshot):Boolean {
        if(e.receipt==null)return false
        val node=currentInScope(e,goal,frame) ?:return false
        return node.localInputRevision!=null &&
            node.localInputRevision==e.finishedRevision && node.hasInput!=null && node.hasInput==e.finishedPresence && node.contentInvalid!=true
    }
    fun begin(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot,node:AccessibilityNodeModel,scopedPreparation:GuideScopedTap?=null):Long? {
        if(!input(node) || frame.allNodes.count{input(it) && sameIdentity(node,it)}!=1)return null
        val existing=entries.singleOrNull{it.session==goal.sessionId && it.pkg==frame.packageName &&
            it.window==frame.windowId && it.epoch==frame.sourceEpoch && sameIdentity(it.anchor,node) && it.anchor.path==node.path}
        if(existing==null && entries.size>=30)return null
        val scopes=plan.scopeNodeIds.filter{it!=node.id}.mapNotNull{id->frame.allNodes.singleOrNull{it.id==id}}
        val requirements=plan.conditions.filter{it.required && it.phase==ConditionPhase.PREPARATION && node.id in it.evidenceNodeIds}.mapNotNull{it.requirementId}.toSet()
        if(existing==null){val e=Entry(++serial,goal.sessionId,goal.revision,frame.packageName,frame.windowId,frame.sourceEpoch,node,scopes,plan.message,requirements,scopedPreparation);entries+=e;return e.id}
        if(!finished(existing,goal,frame)){existing.receipt=null;existing.finishedRevision=null;existing.finishedPresence=null}
        existing.revision=goal.revision;existing.anchor=node;existing.scopes=scopes;existing.message=plan.message;existing.requirements+=requirements
        existing.scopedPreparation=scopedPreparation
        existing.resumedBeforeNavigation=false
        return existing.id
    }
    fun refresh(goal:TaskGoal,frame:ScreenSnapshot):Boolean {
        var expired=false
        for(e in entries)if(e.receipt!=null && !finished(e,goal,frame)){
            e.receipt=null;e.finishedRevision=null;e.finishedPresence=null
            val canResume=if(e.scopedPreparation==null)visible(e,frame) else currentInScope(e,goal,frame)!=null
            if(canResume)expired=true
        }
        return expired
    }
    fun hold(id:Long,goal:TaskGoal,frame:ScreenSnapshot,fresh:Boolean):Hold? {
        val e=entries.singleOrNull{it.id==id} ?:return null
        val node=currentInScope(e,goal,frame) ?:return null
        val required=e.scopedPreparation!=null
        val allowedPresence=node.hasInput==true || (!required && node.hasInput==false)
        val label=if(!required && node.hasInput==false)EMPTY_DONE_LABEL else DONE_LABEL
        val message=if(e.resumedBeforeNavigation && node.hasInput==true)
            "‘${AccessibilityNodePrivacy.publicInputLabel(node)}’ 입력을 마쳤으면 ‘$DONE_LABEL’를 눌러주세요."
            else e.message
        return Hold(id,node,message,fresh && node.localInputRevision!=null && allowedPresence && node.contentInvalid!=true,label)
    }
    fun finish(id:Long,goal:TaskGoal,frame:ScreenSnapshot,fresh:Boolean):Boolean {
        val h=hold(id,goal,frame,fresh)?.takeIf{it.canFinish} ?:return false
        val e=entries.single{it.id==id}
        e.finishedRevision=h.node.localInputRevision;e.finishedPresence=h.node.hasInput;e.receipt=++receipts;return true
    }
    fun unfinished(goal:TaskGoal,frame:ScreenSnapshot,requiredPreparations:Set<Long> = emptySet()):Long? {
        val required=goal.requirements.filter{it.required && !it.retired && it.phase==ConditionPhase.PREPARATION}.map{it.id}.toSet()
        return entries.firstOrNull{e->!finished(e,goal,frame) && (visible(e,frame) || e.requirements.any{it in requiredPreparations && it in required})}?.id
    }
    fun resumeBeforeScopedNavigation(goal:TaskGoal,frame:ScreenSnapshot,navigation:GuideScopedTap):Long? {
        val entry=entries.firstOrNull {
            it.scopedPreparation?.node?.path==navigation.node.path &&
                currentInScope(it,goal,frame)!=null && !finished(it,goal,frame)
        } ?:return null
        entry.resumedBeforeNavigation=true
        return entry.id
    }
    /** Shared by runtime and public model evaluation; a lowered model effect is checked after preparation binding. */
    fun blocking(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot,bindings:List<PreparationBinding>):Long? {
        if(plan.effect!=ActionEffect.COMMIT && plan.kind!=NextKind.COMPLETE)return null
        val target=frame.allNodes.singleOrNull{it.id==plan.targetNodeId}
        val required=bindings.filter{b->target!=null &&
            (b.resource.isNotBlank() && b.resource==target.viewIdResourceName ||
                b.label.isNotBlank() && b.label==GuidePolicy.label(target) && b.role==target.className)}.flatMap{it.requirementIds}.toSet()
        return unfinished(goal,frame,required)
    }
    fun hasEntries()=entries.isNotEmpty()
    fun invalidate(){entries.forEach{it.receipt=null;it.finishedRevision=null;it.finishedPresence=null}}
    fun progress(goal:TaskGoal,frame:ScreenSnapshot)=entries.map{e->InputProgress(e.id,current(e,frame)?.id,
        finished(e,goal,frame),e.receipt?.takeIf{finished(e,goal,frame)})}
    fun clear(){entries.clear()}
}
