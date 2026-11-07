package com.example.aplassistmvp0.guide

import android.view.accessibility.AccessibilityEvent
import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3OperationLedgerTest {
    private val goal=TaskGoal(1,1,"두 항목을 준비하고 확정하고 싶어",PKG,"공개 시험")
    private val first=frame(1,"기존 내역")
    private val action=TaskPlan(NextKind.GUIDE,"관련 작업",GoalKind.NEW_EVENT,emptyList(),
        listOf(TaskStep("model_key","첫 확정",listOf(1),emptyList())),emptyList(),Operation.TAP,1,
        "첫 확정을 눌러주세요.",null,WaitKind.NONE,listOf(1),emptyList(),effect=ActionEffect.COMMIT)
    private fun tracker()=V3CommitTracker().apply{assertTrue(record(goal,action,first,click(first,1,20),10,25))}
    private fun outcome(id:Long,status:CommitOutcome=CommitOutcome.SUCCEEDED)=action.copy(kind=NextKind.COMPLETE,
        operation=Operation.NONE,targetNodeId=null,effect=ActionEffect.NONE,commitOutcome=status,outcomeReceiptId=id,
        outcomeNodeIds=listOf(2),newEventNodeIds=listOf(2))

    @Test fun modelKeysScopesAndCaptureIdsCannotMintAnotherSuccessfulOperation(){
        val t=tracker();assertEquals(CommitOutcome.SUCCEEDED,t.observe(goal,outcome(1),frame(2,"첫 결과")))
        val moved=frame(3,"첫 결과").let{f->f.copy(windowId=7,allNodes=f.allNodes.map{
            it.copy(id=it.id+10,path="new/${it.id}",bounds=it.bounds.copy(top=it.bounds.top+50,bottom=it.bounds.bottom+50))})}
        val renamed=action.copy(targetNodeId=11,tasks=listOf(TaskStep("new_key","완전히 다른 문구",listOf(11),emptyList())),scopeNodeIds=listOf(12))
        assertEquals(V3CommitTracker.Gate.ALREADY_SUCCEEDED,t.gate(renamed,moved))
        assertEquals(listOf(1L),t.history().map{it.operationId})
    }
    @Test fun aChangedLabelOrResourceAloneCannotEraseSuccessfulIdentity(){
        val t=tracker();t.observe(goal,outcome(1),frame(2,"첫 결과"))
        for(change in listOf<(AccessibilityNodeModel)->AccessibilityNodeModel>({it.copy(text="다시 확정")},{it.copy(viewIdResourceName="changed:id/button")})){
            val updated=first.copy(allNodes=first.allNodes.map{if(it.id==1)change(it)else it})
            assertEquals(V3CommitTracker.Gate.ALREADY_SUCCEEDED,t.gate(action,updated))
        }
    }
    @Test fun multipleDistinctCommitsRetainEverySuccessInsteadOfOverwritingIt(){
        val t=tracker();val between=frame(2,"첫 결과");t.observe(goal,outcome(1),between)
        val second=action.copy(targetNodeId=3)
        assertEquals(V3CommitTracker.Gate.ALLOW,t.gate(second,between))
        assertTrue(t.record(goal,second,between,click(between,3,50),40,55))
        assertEquals(listOf(1L,2L),t.history().map{it.receiptId})
        assertEquals(listOf(1L,2L),t.history().map{it.operationId})
        assertEquals(CommitOutcome.SUCCEEDED,t.observe(goal,outcome(2),frame(3,"둘째 결과")))
        assertFalse(t.unresolved())
        assertEquals(V3CommitTracker.Gate.ALREADY_SUCCEEDED,t.gate(action,frame(4,"다른 화면")))
        assertFalse(t.record(goal,action,frame(4,"다른 화면"),click(frame(4,"다른 화면"),1,120),100,125))
        assertEquals(2,t.history().size)
    }
    @Test fun aLateResultForAnEarlierReceiptCannotFinishAnotherPendingOperation(){
        val t=tracker();val between=frame(2,"첫 결과");t.observe(goal,outcome(1),between)
        assertTrue(t.record(goal,action.copy(targetNodeId=3),between,click(between,3,50),40,55))
        assertFalse(t.canComplete(goal,outcome(1),frame(3,"다른 결과")))
        assertTrue(t.unresolved());assertEquals(CommitOutcome.PENDING,t.history().last().state)
        assertTrue(t.canComplete(goal,outcome(2),frame(4,"둘째 성공 결과")))
    }
    @Test fun aVerifiedFailureRetryKeepsItsOperationAndIssuesANewNativeReceipt(){
        val t=tracker();val failed=frame(2,"실패 결과")
        assertEquals(CommitOutcome.FAILED,t.observe(goal,outcome(1,CommitOutcome.FAILED),failed))
        assertEquals(V3CommitTracker.Gate.ALLOW,t.gate(action,failed))
        assertTrue(t.record(goal,action,failed,click(failed,1,50),40,55))
        assertEquals(listOf(1L,1L),t.history().map{it.operationId})
        assertEquals(listOf(1,2),t.history().map{it.attempt})
        assertEquals(listOf(1L,2L),t.history().map{it.receiptId})
        t.observe(goal,outcome(1,CommitOutcome.FAILED),frame(3,"실패 결과"))
        assertTrue(t.unresolved());assertFalse(t.canComplete(goal,outcome(1),frame(3,"실패 결과")))
        assertTrue(t.canComplete(goal,outcome(2),frame(4,"재시도 성공 결과")))
    }
    @Test fun lossOrContradictionOfCurrentEvidenceNeverAuthorizesRepeatingSuccess(){
        val t=tracker();t.observe(goal,outcome(1),frame(2,"성공 결과"))
        t.observe(goal,outcome(1,CommitOutcome.UNKNOWN),frame(3,"다른 화면"))
        t.observe(goal,outcome(1,CommitOutcome.FAILED),frame(4,"다른 오류"))
        assertEquals(CommitOutcome.SUCCEEDED,t.history().single().state)
        assertEquals(V3CommitTracker.Gate.ALREADY_SUCCEEDED,t.gate(action,first))
        assertFalse(t.canComplete(goal,outcome(1,CommitOutcome.UNKNOWN),frame(5,"근거 없음")))
    }
    @Test fun anUnknownInteractionBlocksOtherCommitsAndCannotBecomeSuccess(){
        val t=V3CommitTracker();val e=click(first,1,20).copy(bounds=NodeBounds(0,0,0,0),viewId="",label="첫 확정")
        assertTrue(t.recordPossible(goal,action,first,e,10,25))
        assertEquals(V3CommitTracker.Gate.UNRESOLVED,t.gate(action.copy(targetNodeId=3),frame(2,"새 결과")))
        assertEquals(CommitOutcome.UNKNOWN,t.observe(goal,outcome(1),frame(2,"새 결과")))
        assertTrue(t.unresolved());assertFalse(t.history().single().interactionVerified)
        assertEquals(V3CommitTracker.Gate.UNRESOLVED,t.gate(action.copy(effect=ActionEffect.PREPARE),first))
        assertEquals(V3CommitTracker.Gate.ALLOW,t.gate(action.copy(effect=ActionEffect.PREPARE,targetNodeId=3),first))
    }
    @Test fun nativeReceiptCapacityNeverEvictsHistoryToPermitARepeat(){
        val t=V3CommitTracker(receiptLimit=1)
        assertTrue(t.record(goal,action,first,click(first,1,20),10,25))
        t.observe(goal,outcome(1,CommitOutcome.FAILED),frame(2,"실패 결과"))
        assertEquals(V3CommitTracker.Gate.HISTORY_LIMIT,t.gate(action,frame(3,"실패 결과")))
        assertEquals(V3CommitTracker.Gate.ALLOW,t.gate(action.copy(effect=ActionEffect.PREPARE,targetNodeId=3),frame(3,"실패 결과")))
        assertEquals(1,t.history().size)
    }
    @Test fun aMissingOrFabricatedReceiptCannotResolveAnActualPendingAttempt(){
        val t=tracker()
        for(id in listOf(null,0L,2L))assertFalse(t.canComplete(goal,outcome(1).copy(outcomeReceiptId=id),frame(2,"새 결과")))
        assertTrue(t.unresolved());assertEquals(CommitOutcome.PENDING,t.history().single().state)
    }
    @Test fun queryingGuidanceDoesNotIssueReceiptsAndResetEndsTheirAuthority(){
        val t=V3CommitTracker();repeat(20){assertEquals(V3CommitTracker.Gate.ALLOW,t.gate(action,first))}
        assertTrue(t.history().isEmpty());assertTrue(t.record(goal,action,first,click(first,1,20),10,25))
        t.reset();assertTrue(t.history().isEmpty());assertFalse(t.canComplete(goal,outcome(1),frame(2,"새 결과")))
    }
    @Test fun changingAnAnswerDoesNotEraseTheHistoricalExecution(){
        val t=tracker();t.observe(goal,outcome(1),frame(2,"성공 결과"))
        val changed=goal.copy(revision=2,answers=mapOf("later_choice" to "다른 값"))
        assertEquals(V3CommitTracker.Gate.ALREADY_SUCCEEDED,t.gate(action,first))
        assertFalse(t.canComplete(changed,outcome(1),frame(3,"성공 결과")))
        assertEquals(1L,t.history().single().goalRevision)
    }
    private fun click(f:ScreenSnapshot,id:Int,time:Long):PublicActionEvidence {
        val n=f.allNodes.single{it.id==id}
        return PublicActionEvidence(f.windowId,n.bounds,n.className.orEmpty(),n.viewIdResourceName.orEmpty(),n.text.orEmpty(),
            eventType=AccessibilityEvent.TYPE_VIEW_CLICKED,eventUptimeMillis=time,receivedUptimeMillis=time+2,packageName=PKG,sourceEpoch=f.sourceEpoch)
    }
    private fun frame(id:Long,result:String):ScreenSnapshot {
        fun n(id:Int,label:String,clickable:Boolean,resource:String)=AccessibilityNodeModel(id,"0/$id","0",
            if(clickable)"android.widget.Button"else"android.widget.TextView",label,null,resource,clickable,true,true,false,true,false,
            NodeBounds(30,id*120,450,id*120+90),emptyList())
        val nodes=listOf(n(1,"첫 확정",true,"public:id/first"),n(2,result,false,"public:id/result"),n(3,"둘째 확정",true,"public:id/second"))
        val start=if(id==1L)1 else (id-1)*30+1
        return ScreenSnapshot(id,PKG,5,1000+id,nodes,nodes,false,NodeBounds(0,0,500,1000),sourceEpoch=4,
            captureStartedAtUptimeMillis=start,captureFinishedAtUptimeMillis=start+4)
    }
    companion object{private const val PKG="public.ledger.fixture"}
}
