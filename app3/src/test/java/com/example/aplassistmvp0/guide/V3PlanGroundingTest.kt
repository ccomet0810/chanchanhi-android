package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3PlanGroundingTest {
    @Test fun definitionAndCorrectionEvidenceUsesTheSameCurrentNodeBinding(){
        val basis=ConditionBasis(ConditionOrigin.INFERRED,quote="공개 목표",rationale="공개 근거로 정정",evidenceNodeIds=listOf(2))
        val withSource=plan.copy(conditions=plan.conditions.map{it.copy(basis=basis)},
            conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.REPLACE,"추론 정정",basis)))
        val moved=nodes.map{it.copy(id=it.id+10)}
        val bound=V3PlanGrounding.bind(withSource,original,original.copy(snapshotId=2,allNodes=moved,nodes=moved))!!
        assertEquals(listOf(12),bound.conditions.single().basis!!.evidenceNodeIds)
        assertEquals(listOf(12),bound.conditionEdits.single().basis.evidenceNodeIds)
        assertEquals(listOf(2),withSource.conditionEdits.single().basis.evidenceNodeIds)
    }
    @Test fun aHiddenCorrectionSourceCannotBeUsedEvenWhenTheActionItselfIsUnchanged(){
        val basis=ConditionBasis(ConditionOrigin.INFERRED,quote="공개 목표",rationale="현재 근거로 정정",evidenceNodeIds=listOf(2))
        val onlyAction=plan.copy(conditions=emptyList(),scopeNodeIds=emptyList(),relevantNodeIds=listOf(1),
            conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.RETIRE,"정정",basis)))
        val hidden=nodes.map{if(it.id==2)it.copy(sensitiveContent=true)else it}
        assertNull(V3PlanGrounding.bind(onlyAction,original,original.copy(snapshotId=2,allNodes=hidden)))
    }
    private val nodes=listOf(node(1,"확인",true),node(2,"프로필 A",false))
    private val original=ScreenSnapshot(1,"public.scope.fixture",5,1000,nodes,nodes,false,NodeBounds(0,0,500,1000),sourceEpoch=4)
    private val plan=TaskPlan(NextKind.GUIDE,"현재 프로필 작업",GoalKind.STATE,
        listOf(GoalCondition("profile","작업 프로필",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(2))),
        listOf(TaskStep("confirm","확인",listOf(1),emptyList())),emptyList(),Operation.TAP,1,
        "확인을 눌러주세요.",null,WaitKind.NONE,listOf(1,2),emptyList(),scopeNodeIds=listOf(2))
    @Test fun readonlySubjectChangesRejectThePlanEvenWhenEveryButtonLooksTheSame(){
        val changed=nodes.map{if(it.id==2)it.copy(text="프로필 B")else it}
        assertNull(V3PlanGrounding.bind(plan,original,original.copy(snapshotId=2,allNodes=changed,nodes=changed)))
    }
    @Test fun geometryAndCaptureLocalIdsCanChangeWithoutChangingMeaning(){
        val moved=nodes.map{it.copy(id=it.id+10,bounds=it.bounds.copy(top=it.bounds.top+20,bottom=it.bounds.bottom+20))}
        val bound=V3PlanGrounding.bind(plan,original,original.copy(snapshotId=2,allNodes=moved,nodes=moved))!!
        assertEquals(11,bound.targetNodeId);assertEquals(listOf(12),bound.conditions.single().evidenceNodeIds)
        assertEquals(listOf(12),bound.scopeNodeIds);assertEquals(listOf(11,12),bound.relevantNodeIds)
        assertEquals(1,plan.targetNodeId)
    }
    @Test fun anotherSourceOrAnOlderCaptureCannotBeRebound(){
        for(frame in listOf(original.copy(sourceEpoch=5),original.copy(windowId=6),original.copy(snapshotId=0),original.copy(capturedAtMillis=999),original.copy(blockingPopup=true)))
            assertNull(V3PlanGrounding.bind(plan,original,frame))
    }
    @Test fun hiddenOrAmbiguousReferencesCannotBeBound(){
        val hidden=nodes.map{if(it.id==2)it.copy(visibleToUser=false)else it}
        assertNull(V3PlanGrounding.bind(plan,original,original.copy(snapshotId=2,allNodes=hidden)))
        assertNull(V3PlanGrounding.bind(plan,original,original.copy(snapshotId=2,allNodes=nodes+nodes.last().copy(id=4))))
    }
    @Test fun changedValueOrSelectionDoesNotReuseAnOldCondition(){
        val changed=nodes.map{if(it.id==2)it.copy(selected=true)else it}
        assertNull(V3PlanGrounding.bind(plan,original,original.copy(snapshotId=2,allNodes=changed)))
    }
    private fun node(id:Int,text:String,clickable:Boolean)=AccessibilityNodeModel(id,"0/$id","0",if(clickable)"android.widget.Button"else"android.widget.TextView",text,null,
        "public:id/item$id",clickable,true,true,false,true,false,NodeBounds(30,100*id,450,100*id+80),emptyList())
}
