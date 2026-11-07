package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.*
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3CommitObservationTest {
    private val goal=TaskGoal(1,1,"공개 항목 만들기","public.commit.fixture","공개 양식")
    private fun node(id:Int,label:String,clickable:Boolean)=AccessibilityNodeModel(id,"0/$id","0","android.widget.Button",label,null,
        "public:id/item$id",clickable,true,true,false,true,false,NodeBounds(20,id*100,480,id*100+80),emptyList())
    private val nodes=listOf(node(1,"공개 대상 A",false),node(2,"확정",true))
    private val frame=ScreenSnapshot(3,goal.targetPackage,5,1000,nodes,nodes,false,NodeBounds(0,0,500,1000),sourceEpoch=4,
        captureStartedAtUptimeMillis=10,captureFinishedAtUptimeMillis=11)
    private val plan=TaskPlan(NextKind.GUIDE,"공개 생성",GoalKind.NEW_EVENT,emptyList(),emptyList(),emptyList(),Operation.TAP,2,
        "확정을 눌러주세요.",null,WaitKind.NONE,listOf(2),emptyList(),effect=ActionEffect.COMMIT,scopeNodeIds=listOf(1))
    private fun state(f:ScreenSnapshot=frame)=AssistUiState(serviceConnected=true,foregroundPackage=goal.targetPackage,snapshot=f)
    private fun armed()=V3CommitObservation().apply{show(goal,plan,frame,12)}

    @Test fun onlyAnExplicitShownCommitClassificationCanBeRetained(){
        for(other in listOf(plan.copy(effect=ActionEffect.PREPARE),plan.copy(kind=NextKind.WAIT,operation=Operation.NONE),
            plan.copy(operation=Operation.INPUT),plan.copy(targetNodeId=99),plan.copy(scopeNodeIds=listOf(99)))){
            val watch=V3CommitObservation();watch.show(goal,other,frame,12);assertNull(watch.current(goal,state()))
        }
        assertNotNull(armed().current(goal,state()))
    }
    @Test fun movingTheSamePublicControlDoesNotRewriteTheOriginalBeforeObservation(){
        val moved=nodes.map{if(it.id==2)it.copy(id=8,bounds=NodeBounds(20,700,480,780),focused=true)else it}
        val current=armed().current(goal,state(frame.copy(snapshotId=4,nodes=moved,allNodes=moved)))!!
        assertSame(frame,current.before);assertEquals(3L,current.before.snapshotId);assertEquals(2,current.plan.targetNodeId)
    }
    @Test fun scopeTargetOrSourceChangesExpireTheClassificationPermanently(){
        val changes=listOf(frame.copy(windowId=6),frame.copy(sourceEpoch=5),frame.copy(blockingPopup=true),
            frame.copy(nodes=nodes.map{it.copy(text=it.text+" 변경")},allNodes=nodes.map{it.copy(text=it.text+" 변경")}))
        for(changed in changes){val watch=armed();assertNull(watch.current(goal,state(changed)));assertNull(watch.current(goal,state()))}
        for(changed in listOf(goal.copy(sessionId=2),goal.copy(revision=2))){val watch=armed();assertNull(watch.current(changed,state()));assertNull(watch.current(goal,state()))}
    }
    @Test fun incompleteUnavailableOrOlderObservationsCannotRearmAClassification(){
        val changes=listOf(frame.copy(truncated=true),frame.copy(viewport=null),frame.copy(snapshotId=2),frame.copy(capturedAtMillis=999),
            frame.copy(allNodes=nodes+nodes[1].copy(id=9)),frame.copy(allNodes=nodes.map{it.copy(sensitiveContent=true)}))
        for(changed in changes){val watch=armed();assertNull(watch.current(goal,state(changed)));assertNull(watch.current(goal,state()))}
        for(changed in listOf(state().copy(serviceConnected=false),state().copy(foregroundPackage=null),state().copy(snapshot=null))){
            val watch=armed();assertNull(watch.current(goal,changed));assertNull(watch.current(goal,state()))
        }
    }
    @Test fun aNewNoncommitPlanCannotLeaveAnEarlierCommitClassificationAlive(){
        val watch=armed();watch.show(goal,plan.copy(effect=ActionEffect.NONE),frame,20);assertNull(watch.current(goal,state()))
        watch.show(goal,plan,frame,21);watch.clear();assertNull(watch.current(goal,state()))
    }
}
