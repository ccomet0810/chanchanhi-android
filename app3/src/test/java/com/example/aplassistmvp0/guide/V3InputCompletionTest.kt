package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3InputCompletionTest {
    @Test fun aKnownEmptyEditorNeedsAnExplicitDeclarationButCanFinishWriting(){
        val t=V3InputCompletion();val f=withPresence(false);val id=t.begin(goal,plan(1),f,f.allNodes[0])!!
        repeat(5){t.refresh(goal,f)}
        assertFalse(t.progress(goal,f).single().userFinished)
        assertTrue("Clearing or leaving a field blank is also a possible end of writing",t.finish(id,goal,f,true))
        assertTrue(t.progress(goal,f).single().userFinished)
        assertEquals(1L,t.progress(goal,f).single().receiptId)
    }
    @Test fun unknownPresenceIsNotTheSameAsKnownEmpty(){
        val t=V3InputCompletion();val f=withPresence(null);val id=t.begin(goal,plan(1),f,f.allNodes[0])!!
        assertFalse(t.hold(id,goal,f,true)!!.canFinish);assertFalse(t.finish(id,goal,f,true))
    }
    @Test fun typingAfterAnEmptyDeclarationRevokesIt(){
        val t=V3InputCompletion();val empty=withPresence(false);val id=t.begin(goal,plan(1),empty,empty.allNodes[0])!!
        assertTrue(t.finish(id,goal,empty,true))
        val typed=withPresence(true).let{f->val ns=f.allNodes.map{if(it.id==1)it.copy(localInputRevision=9)else it};f.copy(nodes=ns,allNodes=ns)}
        assertTrue(t.refresh(goal,typed));assertEquals(id,t.unfinished(goal,typed));assertFalse(t.progress(goal,typed).single().userFinished)
    }
    @Test fun clearingAfterANonemptyDeclarationRequiresANewDeclaration(){
        val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true))
        val empty=withPresence(false).let{f->val ns=f.allNodes.map{if(it.id==1)it.copy(localInputRevision=9)else it};f.copy(nodes=ns,allNodes=ns)}
        assertTrue(t.refresh(goal,empty));assertFalse(t.progress(goal,empty).single().userFinished)
        assertTrue(t.finish(id,goal,empty,true));assertEquals(2L,t.progress(goal,empty).single().receiptId)
    }
    @Test fun aStaleOrInvalidEmptyEditorCannotBeDeclaredFinished(){
        val t=V3InputCompletion();val f=withPresence(false);val id=t.begin(goal,plan(1),f,f.allNodes[0])!!
        assertFalse(t.finish(id,goal,f,false))
        val nodes=f.allNodes.map{it.copy(contentInvalid=true)};assertFalse(t.finish(id,goal,f.copy(nodes=nodes,allNodes=nodes),true))
    }
    @Test fun anEmptyReceiptCannotBeBorrowedForUnknownPresenceOrAnUnreportedPresenceChange(){
        for(presence in listOf(null,true)){
            val t=V3InputCompletion();val empty=withPresence(false);val id=t.begin(goal,plan(1),empty,empty.allNodes[0])!!
            assertTrue(t.finish(id,goal,empty,true))
            val changed=withPresence(presence)
            assertTrue(t.refresh(goal,changed));assertFalse(t.progress(goal,changed).single().userFinished)
        }
    }
    private fun withPresence(value:Boolean?):ScreenSnapshot {val f=frame();val nodes=f.allNodes.map{it.copy(hasInput=value)};return f.copy(nodes=nodes,allNodes=nodes)}
    @Test fun anUnfinishedHelperEditorOutsideTheCurrentFormDoesNotBlockUnrelatedWork(){val t=V3InputCompletion();val f=frame();t.begin(goal,plan(1),f,f.allNodes[0]);val nodes=f.allNodes.filter{it.id==2};val otherForm=f.copy(nodes=nodes,allNodes=nodes);assertNull(t.unfinished(goal,otherForm));assertFalse(t.progress(goal,otherForm).single().userFinished)}
    @Test fun aMissingEditorStillBlocksItsOwnRequiredPreparation(){val t=V3InputCompletion();val f=frame();val g=goal.copy(requirements=listOf(OwnedRequirement(9,1,"입력 완료",true,ConditionBasis(ConditionOrigin.REQUEST,quote=goal.request),1,phase=ConditionPhase.PREPARATION)));val p=plan(1).copy(conditions=listOf(GoalCondition("draft","입력 완료",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,listOf(1),requirementId=9,requirementVersion=1,phase=ConditionPhase.PREPARATION)));val id=t.begin(g,p,f,f.allNodes[0])!!;val nodes=f.allNodes.filter{it.id==2};assertEquals(id,t.unfinished(g,f.copy(nodes=nodes,allNodes=nodes),setOf(9)))}
    @Test fun aFinishedHelperLeavingTheFormIsNotAnErrorOrAnInventedSuccess(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));val nodes=f.allNodes.filter{it.id==2};val other=f.copy(nodes=nodes,allNodes=nodes);assertFalse(t.refresh(goal,other));assertNull(t.unfinished(goal,other));assertFalse(t.progress(goal,other).single().userFinished)}
    private val goal=TaskGoal(1,1,"공개 양식 작성","public.input.fixture","시험")
    private fun input(id:Int)=AccessibilityNodeModel(id,"0/$id","0","android.widget.EditText",null,null,"public:id/editor$id",
        true,true,true,false,true,false,NodeBounds(0,id*100,400,id*100+80),emptyList(),editable=true,inputHint="공개 입력 $id",hasInput=true,localInputRevision=id.toLong())
    private fun frame()=ScreenSnapshot(1,goal.targetPackage,5,1,listOf(input(1),input(2)),listOf(input(1),input(2)),false,NodeBounds(0,0,400,800),sourceEpoch=7)
    private fun plan(id:Int)=TaskPlan(NextKind.GUIDE,"양식 작성",GoalKind.CHANGE,emptyList(),emptyList(),emptyList(),Operation.INPUT,id,"내용을 입력해주세요.",null,WaitKind.NONE,listOf(id),emptyList())
    @Test fun presenceAndStableObservationsNeverIssueAFinishReceipt(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;repeat(10){t.refresh(goal,f)};assertEquals(id,t.unfinished(goal,f));assertFalse(t.progress(goal,f).single().userFinished)}
    @Test fun explicitFinishIsScopedAndNeverExportsThePrivateEditVersion(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));assertNull(t.unfinished(goal,f));assertEquals(InputProgress(id,1,true,1),t.progress(goal,f).single())}
    @Test fun missingNativeRevisionCannotBeConfirmed(){val t=V3InputCompletion();val base=frame();val n=base.allNodes.map{it.copy(localInputRevision=null)};val f=base.copy(nodes=n,allNodes=n);val id=t.begin(goal,plan(1),f,n[0])!!;assertFalse(t.finish(id,goal,f,true))}
    @Test fun aStaleCaptureCannotBeConfirmed(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertFalse(t.finish(id,goal,f,false))}
    @Test fun editingOneFieldDoesNotEraseAFinishedIndependentField(){val t=V3InputCompletion();val f=frame();val a=t.begin(goal,plan(1),f,f.allNodes[0])!!;val b=t.begin(goal,plan(2),f,f.allNodes[1])!!;assertTrue(t.finish(a,goal,f,true));assertTrue(t.finish(b,goal,f,true));val nodes=f.allNodes.map{if(it.id==2)it.copy(localInputRevision=3)else it};val changed=f.copy(nodes=nodes,allNodes=nodes);assertTrue(t.refresh(goal,changed));assertEquals(listOf(true,false),t.progress(goal,changed).map{it.userFinished});assertEquals(b,t.unfinished(goal,changed))}
    @Test fun scopeInputEditsInvalidateTheDependentDeclaration(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1).copy(scopeNodeIds=listOf(2)),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));val nodes=f.allNodes.map{if(it.id==2)it.copy(localInputRevision=3)else it};val changed=f.copy(nodes=nodes,allNodes=nodes);assertTrue(t.refresh(goal,changed));assertFalse(t.finish(id,goal,changed,true))}
    @Test fun aNewQuestionRevisionCannotBorrowTheOldDeclaration(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));assertTrue(t.refresh(goal.copy(revision=2),f));assertFalse(t.finish(id,goal.copy(revision=2),f,true))}
    @Test fun windowAndSourceChangesAreNotTheSameEditor(){for(fresh in listOf(frame().copy(windowId=6),frame().copy(sourceEpoch=8))){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));assertTrue(t.refresh(goal,fresh));assertNull(t.hold(id,goal,fresh,true))}}
    @Test fun ambiguousEditorsCannotAcquireAReceipt(){val t=V3InputCompletion();val f=frame();val nodes=listOf(input(1),input(1).copy(id=3,path="0/3"));assertNull(t.begin(goal,plan(1),f.copy(nodes=nodes,allNodes=nodes),nodes[0]))}
    @Test fun invalidContentCannotBeDeclaredFinished(){val t=V3InputCompletion();val f=frame();val nodes=f.allNodes.map{it.copy(contentInvalid=true)};val changed=f.copy(nodes=nodes,allNodes=nodes);val id=t.begin(goal,plan(1),changed,nodes[0])!!;assertFalse(t.finish(id,goal,changed,true))}
    @Test fun stopAndExplicitInvalidationRevokeEarlierReceipts(){val t=V3InputCompletion();val f=frame();val id=t.begin(goal,plan(1),f,f.allNodes[0])!!;assertTrue(t.finish(id,goal,f,true));t.invalidate();assertEquals(id,t.unfinished(goal,f));t.clear();assertFalse(t.finish(id,goal,f,true));assertTrue(t.progress(goal,f).isEmpty())}
}
