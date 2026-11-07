package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3PreparationPolicyTest {
    @Test fun aModelSatisfiedClaimDoesNotProveADifferentPublicRangeValue(){
        val desired=ValueExpectation(ValueProperty.RANGE,ValueSubject("android.widget.Button","public:id/control1",""),expectedNumber=30.0)
        val s=session();val p=accept(s,plan(listOf(preparation().copy(description="목표 값 30",expectations=listOf(desired)),outcome())))
        val wrong=frame.copy(allNodes=frame.allNodes.map{if(it.id==1)it.copy(range=NodeRange(0f,60f,0f,0))else it})
        assertEquals("value_mismatch",V3PreparationPolicy.review(s.goal!!,p,wrong).problem)
    }
    private val request="공개 작업을 준비하고 확정해줘"
    private fun node(id:Int,label:String)=AccessibilityNodeModel(id,"0/$id","0","android.widget.Button",label,null,
        "public:id/control$id",true,true,true,false,true,false,NodeBounds(20,id*120,400,id*120+80),emptyList())
    private val frame=listOf(node(1,"값 A"),node(2,"값 B"),node(3,"확정 A"),node(4,"확정 B"))
        .let{ScreenSnapshot(1,"public.prepare.fixture",5,1000,it,it,false,NodeBounds(0,0,500,1000))}
    private fun preparation(key:String="a",truth:Truth=Truth.SATISFIED,node:Int=1)=GoalCondition(key,"준비 $key",true,truth,
        EvidenceKind.OBSERVED,listOf(node),basis=ConditionBasis(ConditionOrigin.INFERRED,quote=request,rationale="확정에 필요한 공개 준비 값"),phase=ConditionPhase.PREPARATION)
    private fun outcome(truth:Truth=Truth.UNKNOWN)=GoalCondition("result","최종 결과",true,truth,
        if(truth==Truth.SATISFIED)EvidenceKind.OBSERVED else EvidenceKind.UNKNOWN,if(truth==Truth.SATISFIED)listOf(4)else emptyList(),
        basis=ConditionBasis(ConditionOrigin.REQUEST,quote=request))
    private fun plan(conditions:List<GoalCondition> = listOf(preparation(),outcome()),target:Int=3)=TaskPlan(NextKind.GUIDE,
        "공개 작업",GoalKind.STATE,conditions,emptyList(),emptyList(),Operation.TAP,target,"확정 항목을 눌러주세요.",null,
        WaitKind.NONE,emptyList(),emptyList(),effect=ActionEffect.COMMIT,actionGates=listOf(ActionGate(target,listOf("a"))))
    private fun session()=TaskSession().apply{start(request,frame.packageName,"공개 시험")}
    private fun accept(s:TaskSession,p:TaskPlan):TaskPlan {
        assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,1,1000)),p));return s.plan!!
    }
    private fun remember(s:TaskSession,p:TaskPlan):V3PreparationPolicy.Review {
        val review=V3PreparationPolicy.review(s.goal!!,p,frame);s.rememberPreparations(review.bindings);return review
    }
    private fun reports(s:TaskSession,truth:Truth=Truth.SATISFIED)=s.plan!!.conditions.map{if(it.phase==ConditionPhase.PREPARATION)it.copy(truth=truth)else it}
    @Test fun anUnreadyPartBlocksTheCommitBeforeItCanBeGuided(){
        val p=plan(listOf(preparation(),preparation("b",Truth.UNSATISFIED,2),outcome())).copy(actionGates=listOf(ActionGate(3,listOf("a","b"))))
        assertEquals("unready_preparation",TaskPlanValidation.problem(p,session().goal!!,setOf(1,2,3,4)))
    }
    @Test fun readyPreparationDoesNotRequireThePostCommitOutcomeToExist(){
        val s=session();val p=plan();assertNull(TaskPlanValidation.problem(p,s.goal!!,setOf(1,2,3,4)))
        assertNull(remember(s,accept(s,p)).problem)
    }
    @Test fun completionOnlyNeedsCurrentOutcomeAndNotTheOldEditor(){
        val s=session();remember(s,accept(s,plan()))
        val final=s.plan!!.conditions.single{it.phase==ConditionPhase.OUTCOME}.copy(truth=Truth.SATISFIED,evidence=EvidenceKind.OBSERVED,evidenceNodeIds=listOf(4))
        val p=plan(listOf(final)).copy(kind=NextKind.COMPLETE,operation=Operation.NONE,targetNodeId=null,effect=ActionEffect.NONE,actionGates=emptyList())
        assertNull(TaskPlanValidation.problem(p,s.goal!!,setOf(4)))
    }
    @Test fun omittingAGateAndChangingEffectCannotEraseAnOwnedPreparation(){
        val s=session();remember(s,accept(s,plan()))
        val p=plan(s.plan!!.conditions.filter{it.phase==ConditionPhase.OUTCOME}).copy(effect=ActionEffect.NONE,actionGates=emptyList())
        assertEquals("missing_preparation_report",remember(s,accept(s,p)).problem)
        assertEquals(1,s.goal!!.preparationHistory.size)
    }
    @Test fun anEarlierSatisfiedValueMustBeReportedFromTheCurrentObservation(){
        val s=session();remember(s,accept(s,plan()))
        val p=plan(reports(s,Truth.UNKNOWN)).copy(effect=ActionEffect.PREPARE,actionGates=emptyList())
        assertEquals("unready_preparation",remember(s,accept(s,p)).problem)
    }
    @Test fun allReadyOwnedChecksRestoreCommitMeaningAfterAnEffectDowngrade(){
        val s=session();remember(s,accept(s,plan()))
        val p=plan(reports(s)).copy(effect=ActionEffect.NONE,actionGates=emptyList())
        val result=remember(s,accept(s,p));assertNull(result.problem);assertEquals(ActionEffect.COMMIT,result.plan.effect)
    }
    @Test fun preparationsAreBoundToTheirOwnControls(){
        val s=session();val p=plan(listOf(preparation(),preparation("b",Truth.UNSATISFIED,2),outcome())).copy(
            actionGates=listOf(ActionGate(3,listOf("a")),ActionGate(4,listOf("b"))))
        assertNull(TaskPlanValidation.problem(p,s.goal!!,setOf(1,2,3,4)));assertNull(remember(s,accept(s,p)).problem)
        assertEquals(2,s.goal!!.preparationHistory.size)
        val other=plan(s.plan!!.conditions,4).copy(effect=ActionEffect.NONE,actionGates=emptyList())
        assertEquals("unready_preparation",remember(s,accept(s,other)).problem)
    }
    @Test fun finalOutcomeReferencesCannotMasqueradeAsPreparation(){
        val p=plan().copy(actionGates=listOf(ActionGate(3,listOf("result"))))
        assertEquals("invalid_preparation_reference",TaskPlanValidation.problem(p,session().goal!!,setOf(1,2,3,4)))
    }
    @Test fun aPhaseCannotBeChangedInAnOwnedReport(){
        val s=session();remember(s,accept(s,plan()))
        val p=plan(reports(s).map{it.copy(phase=ConditionPhase.OUTCOME)}).copy(effect=ActionEffect.NONE,actionGates=emptyList())
        assertEquals("requirement_phase_changed",TaskPlanValidation.problem(p,s.goal!!,setOf(1,2,3,4)))
    }
    @Test fun partialEncodingCannotGuideAKnownCommitWithDowngradedEffect(){
        val s=session();remember(s,accept(s,plan()))
        val p=plan(reports(s)).copy(effect=ActionEffect.NONE,actionGates=emptyList(),groundingComplete=false)
        assertEquals("incomplete_preparation_observation",remember(s,accept(s,p)).problem)
    }
    @Test fun userReportOrInferenceDoesNotProveAPreparedValue(){
        for(kind in listOf(EvidenceKind.USER_REPORTED,EvidenceKind.INFERRED,EvidenceKind.UNKNOWN)){
            val p=plan(listOf(preparation().copy(evidence=kind),outcome()))
            assertEquals("unready_preparation",TaskPlanValidation.problem(p,session().goal!!,setOf(1,2,3,4)))
        }
    }
    @Test fun hidingTheOnlyPreparationEvidenceDoesNotPermitACommit(){
        val s=session();val p=accept(s,plan());val hidden=frame.copy(allNodes=frame.allNodes.map{if(it.id==1)it.copy(visibleToUser=false)else it})
        assertEquals("unavailable_preparation_evidence",V3PreparationPolicy.review(s.goal!!,p,hidden).problem)
    }
    @Test fun ambiguousControlsDoNotShareOnePreparationBinding(){
        val s=session();val p=accept(s,plan())
        val duplicate=frame.allNodes.single{it.id==3}.copy(id=9,path="0/9")
        assertEquals("ambiguous_preparation_control",V3PreparationPolicy.review(s.goal!!,p,frame.copy(allNodes=frame.allNodes+duplicate)).problem)
    }
    @Test fun aNewGoalDoesNotReuseThePreviousPreparationBindings(){
        val s=session();remember(s,accept(s,plan()));s.start(request,frame.packageName,"새 공개 목표")
        assertTrue(s.goal!!.preparationHistory.isEmpty())
    }
    @Test fun aMissingOwnedReferenceCannotBeSilentlyDroppedFromTheGate(){
        val s=session();val p=accept(s,plan());remember(s,p)
        val goal=s.goal!!.copy(preparationHistory=s.goal!!.preparationHistory.map{it.copy(requirementIds=it.requirementIds+999L)})
        assertEquals("missing_owned_preparation",V3PreparationPolicy.review(goal,p,frame).problem)
    }
    @Test fun unrelatedPreparationCanProceedWhileAnotherControlIsNotReady(){
        val s=session();val p=plan(listOf(preparation(truth=Truth.UNSATISFIED),outcome())).copy(targetNodeId=2,effect=ActionEffect.PREPARE)
        assertNull(remember(s,accept(s,p)).problem)
    }
}
