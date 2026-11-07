package com.example.aplassistmvp0.guide

import android.view.accessibility.AccessibilityEvent
import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3CommitEvidenceTest {
    private val goal=TaskGoal(1,1,"새 항목을 만들고 싶어",PKG,"공개 양식")
    private val before=frame(1,"이전에 만든 항목")
    private val action=plan().copy(kind=NextKind.GUIDE,operation=Operation.TAP,targetNodeId=1,effect=ActionEffect.COMMIT)
    private fun tracker()=V3CommitTracker().apply{assertTrue(record(goal,action,before,click(),10,30))}
    @Test fun onlyAnActualFreshClickOnTheBoundTargetCreatesAReceipt(){
        for(change in listOf<(PublicActionEvidence)->PublicActionEvidence>(
            {it.copy(eventType=AccessibilityEvent.TYPE_VIEW_SCROLLED)}, {it.copy(eventType=AccessibilityEvent.TYPE_VIEW_FOCUSED)},
            {it.copy(eventType=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)}, {it.copy(eventUptimeMillis=9)},
            {it.copy(eventUptimeMillis=31)}, {it.copy(receivedUptimeMillis=40)}, {it.copy(packageName="other.app")},
            {it.copy(windowId=7)}, {it.copy(sourceEpoch=5)}, {it.copy(role="android.widget.TextView")},
            {it.copy(bounds=NodeBounds(0,0,30,30))}, {it.copy(viewId="other:id/button")},
            {it.copy(label="다른 의미의 버튼")}
        ))assertFalse(change.toString(),V3CommitTracker().record(goal,action,before,change(click()),10,30))
        assertFalse(V3CommitTracker().record(goal,action,before,null,10,30))
        assertTrue(V3CommitTracker().record(goal,action,before,click(),10,30))
    }
    @Test fun NavigationAndInputDoNotCreateCommitReceipts(){
        for(effect in listOf(ActionEffect.NONE,ActionEffect.PREPARE,ActionEffect.START_ACTIVITY))
            assertFalse(V3CommitTracker().record(goal,action.copy(effect=effect),before,click(),10,30))
    }
    @Test fun layoutMovementCanBeMatchedByUniquePublicIdentityButNotAnAmbiguousLabel(){
        val moved=click().copy(bounds=NodeBounds(40,70,450,160),label="확정")
        assertTrue(V3CommitTracker().record(goal,action,before,moved,10,30))
        val duplicate=before.copy(allNodes=before.allNodes+before.allNodes.first().copy(id=9,path="0/9",bounds=NodeBounds(40,500,450,590)))
        assertFalse(V3CommitTracker().record(goal,action,duplicate,moved,10,30))
    }
    @Test fun aClickDoesNotProveSuccess(){val t=tracker();assertTrue(t.unresolved());assertEquals(CommitOutcome.PENDING,t.outcome);assertFalse(t.canComplete(goal,plan(),frame(2,"새 항목")))}
    @Test fun oldHistoryAndMovedOrRenumberedNodesDoNotBecomeNewResults(){
        val t=tracker()
        assertFalse(t.canComplete(goal,success(),frame(2,"이전에 만든 항목")))
        val moved=frame(3,"이전에 만든 항목").let{f->f.copy(allNodes=f.allNodes.map{if(it.id==2)it.copy(id=9,bounds=NodeBounds(50,400,450,500))else it})}
        assertFalse(t.canComplete(goal,success().copy(outcomeNodeIds=listOf(9),newEventNodeIds=listOf(9)),moved))
        assertTrue(t.unresolved())
    }
    @Test fun freshOutcomeNeedsTheSameGoalSourceAndAPostClickObservation(){
        for(f in listOf(before,frame(0,"새 항목"),frame(2,"새 항목").copy(sourceEpoch=5),frame(2,"새 항목").copy(packageName="other.app"),frame(2,"새 항목").copy(truncated=true)))
            assertFalse(tracker().canComplete(goal,success(),f))
        assertFalse(tracker().canComplete(goal.copy(revision=2),success(),frame(2,"새 항목")))
        assertFalse(tracker().canComplete(goal.copy(sessionId=2),success(),frame(2,"새 항목")))
        assertTrue(tracker().canComplete(goal,success(),frame(2,"이번에 새로 만든 항목")))
    }
    @Test fun unknownOutcomeBlocksRepeatedCommitAdviceAndRecording(){
        val t=tracker();t.observe(goal,plan().copy(commitOutcome=CommitOutcome.UNKNOWN),frame(2,"이전에 만든 항목"))
        assertTrue(t.unresolved());assertFalse(t.record(goal,action,before,click(),10,30))
    }
    @Test fun aFailureNeedsFreshEvidenceBeforeRetryCanBeConsidered(){
        val t=tracker();val p=plan().copy(commitOutcome=CommitOutcome.FAILED,outcomeNodeIds=listOf(2),outcomeReceiptId=1)
        assertEquals(CommitOutcome.UNKNOWN,t.observe(goal,p,frame(2,"이전에 만든 항목")))
        assertTrue(t.unresolved());assertEquals(CommitOutcome.FAILED,t.observe(goal,p,frame(3,"오류: 저장하지 못함")));assertFalse(t.unresolved())
    }
    @Test fun cachedSuccessCannotAuthorizeAnotherObservationWithoutItsEvidence(){
        val t=tracker();assertTrue(t.canComplete(goal,success(),frame(2,"이번에 새로 만든 항목")))
        assertFalse(t.canComplete(goal,success().copy(commitOutcome=CommitOutcome.NONE,outcomeNodeIds=emptyList()),frame(3,"다른 화면")))
    }
    @Test fun completionIdsMustBeAmongTheVerifiedOutcomeIds(){assertFalse(tracker().canComplete(goal,success().copy(newEventNodeIds=listOf(1)),frame(2,"새 항목")))}
    @Test fun aResultSeenAfterTheInstructionButBeforeTheClickIsStillOld(){
        val t=V3CommitTracker();t.remember(frame(2,"이미 생긴 결과"))
        assertTrue(t.record(goal,action,before,click().copy(eventUptimeMillis=100,receivedUptimeMillis=105),10,110))
        val later=frame(5,"이미 생긴 결과")
        assertFalse(t.canComplete(goal,success(),later));assertTrue(t.unresolved())
    }
    @Test fun overlappingMissingOrInvalidNativeReadTimesCannotProveANewOutcome(){
        for(f in listOf(
            frame(2,"새 항목").copy(captureStartedAtUptimeMillis=19,captureFinishedAtUptimeMillis=35),
            frame(2,"새 항목").copy(captureStartedAtUptimeMillis=20,captureFinishedAtUptimeMillis=35),
            frame(2,"새 항목").copy(captureStartedAtUptimeMillis=null),
            frame(2,"새 항목").copy(captureFinishedAtUptimeMillis=null),
            frame(2,"새 항목").copy(captureStartedAtUptimeMillis=40,captureFinishedAtUptimeMillis=35)))
            assertFalse(tracker().canComplete(goal,success(),f))
        val t=tracker()
        assertFalse(t.canComplete(goal,success(),frame(2,"새 항목").copy(captureStartedAtUptimeMillis=19)))
        assertFalse("A later read must not erase an ambiguous pre-click sighting",t.canComplete(goal,success(),frame(3,"새 항목")))
    }
    @Test fun missingBaselineTimesOrHistoryOverflowKeepTheOutcomeUnverified(){
        val t=V3CommitTracker();assertTrue(t.record(goal,action,before.copy(captureFinishedAtUptimeMillis=null),click(),10,30))
        assertFalse(t.canComplete(goal,success(),frame(2,"새 항목")))
        val limited=V3CommitTracker(historyLimit=2)
        assertTrue(limited.record(goal,action,before,click(),10,30))
        assertFalse("Memory limits must not evict old facts and turn them into new evidence",limited.canComplete(goal,success(),frame(2,"새 항목")))
        limited.reset();assertNull(limited.receipt)
    }
    @Test fun aWallClockAdjustmentDoesNotInvalidateAProvenMonotonicRead(){
        assertTrue(tracker().canComplete(goal,success(),frame(2,"새 항목").copy(capturedAtMillis=1)))
    }
    @Test fun everyNewEventReferenceMustBeNewEvenWhenOtherOutcomeEvidenceIsFresh(){
        val mixed=frame(2,"새 항목")
        assertFalse(tracker().canComplete(goal,success().copy(outcomeNodeIds=listOf(1,2),newEventNodeIds=listOf(1)),mixed))
    }
    @Test fun missingSourceGeometryDoesNotPermitDuplicateAdviceOrVerifiedCompletion(){
        val t=V3CommitTracker();val unknown=click().copy(bounds=NodeBounds(0,0,0,0),viewId="",label="확정")
        assertFalse(t.record(goal,action,before,unknown,10,30))
        assertTrue(t.recordPossible(goal,action,before,unknown,10,30));assertTrue(t.unresolved())
        assertFalse(t.canComplete(goal,success(),frame(2,"새 항목")));assertTrue(t.unresolved())
        t.observe(goal,success(),frame(2,"새 항목"));assertTrue(t.unresolved())
    }
    @Test fun aDifferentIdentifiedButtonIsNotAPossibleCommit(){
        val t=V3CommitTracker();val n=before.allNodes[1].copy(className="android.widget.Button",clickable=true)
        val two=before.copy(allNodes=before.allNodes+n.copy(id=3,path="0/3",bounds=NodeBounds(40,500,450,590)))
        val different=click().copy(bounds=NodeBounds(40,500,450,590),viewId=n.viewIdResourceName.orEmpty(),label=n.text.orEmpty())
        assertFalse(t.recordPossible(goal,action,two,different,10,30));assertFalse(t.unresolved())
    }
    private fun click()=PublicActionEvidence(5,before.allNodes.first().bounds,"android.widget.Button","public:id/commit",
        eventType=AccessibilityEvent.TYPE_VIEW_CLICKED,eventUptimeMillis=20,receivedUptimeMillis=25,packageName=PKG,sourceEpoch=4)
    private fun success()=plan().copy(kind=NextKind.COMPLETE,commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(2),newEventNodeIds=listOf(2),outcomeReceiptId=1)
    private fun plan()=TaskPlan(NextKind.OBSERVE,"공개 작업",GoalKind.NEW_EVENT,emptyList(),emptyList(),emptyList(),Operation.NONE,null,"결과를 확인해주세요.",null,WaitKind.NONE,emptyList(),emptyList())
    private fun frame(id:Long,label:String):ScreenSnapshot {
        val nodes=listOf(node(1,"확정","android.widget.Button",true,"public:id/commit"),node(2,label,"android.widget.TextView",false,"public:id/result"))
        val start=if(id<=1)1 else (id-1)*30+1
        return ScreenSnapshot(id,PKG,5,1000+id,nodes,nodes,false,NodeBounds(0,0,500,1000),sourceEpoch=4,
            captureStartedAtUptimeMillis=start,captureFinishedAtUptimeMillis=start+4)
    }
    private fun node(id:Int,label:String,role:String,clickable:Boolean,resource:String)=AccessibilityNodeModel(id,"0/$id","0",role,label,null,resource,
        clickable,true,true,false,true,false,NodeBounds(40,id*120,450,id*120+90),emptyList())
    companion object{private const val PKG="public.forms.fixture"}
}
