package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.junit.Assert.*
import org.junit.Test

class V3ValueEvidenceTest {
    private fun presence(value:Boolean?)=node(8).copy(className="android.widget.EditText",text=null,editable=true,
        inputHint="공개 설명",hasInput=value)
    private fun presenceCheck(desired:Boolean)=ValueExpectation(ValueProperty.valueOf("INPUT_PRESENT"),
        ValueSubject("android.widget.EditText","public:id/value8",""),expectedBoolean=desired)
    @Test fun inputPresenceComparesTrueFalseAndUnknownWithoutReadingContent(){
        val yes=presenceCheck(true);val no=presenceCheck(false)
        assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(yes,presence(true)))
        assertEquals(V3ValueEvidence.Result.MISMATCH,V3ValueEvidence.evaluate(yes,presence(false)))
        assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(no,presence(false)))
        assertEquals(V3ValueEvidence.Result.UNKNOWN,V3ValueEvidence.evaluate(no,presence(null)))
    }
    @Test fun inputPresenceCannotBeReadFromNoneditableOrHiddenControls(){
        val check=presenceCheck(true)
        for(n in listOf(presence(true).copy(password=true),presence(true).copy(sensitiveContent=true),
            presence(true).copy(visibleToUser=false),node(8).copy(hasInput=true))){
            assertEquals(V3ValueEvidence.Result.UNKNOWN,V3ValueEvidence.evaluate(check,n))
        }
    }
    @Test fun inputPresenceDoesNotPermitOtherPrivateEditorValueComparisons(){
        val n=presence(true).copy(range=NodeRange(0f,60f,30f,0),checkable=true,checked=1,selected=true)
        for(check in listOf(expected(8,ValueProperty.RANGE,30.0),expected(8,ValueProperty.CHECKED,flag=true),expected(8,ValueProperty.SELECTED,flag=true))){
            assertEquals(V3ValueEvidence.Result.UNKNOWN,V3ValueEvidence.evaluate(check,n))
        }
    }
    @Test fun aFalseSatisfiedInputClaimIsRejectedEvenWhenThereWasAnEmptyWritingDeclaration(){
        val n=presence(false);val f=frame.copy(nodes=listOf(n),allNodes=listOf(n))
        assertEquals("value_mismatch",problem(listOf(presenceCheck(true)),listOf(8),f))
        assertNull(problem(listOf(presenceCheck(false)),listOf(8),f))
    }
    @Test fun anInputSubjectStillNeedsUniqueIdentityAndCurrentEvidence(){
        val n=presence(true);val f=frame.copy(nodes=listOf(n),allNodes=listOf(n))
        assertEquals("unverified_value_evidence",problem(listOf(presenceCheck(true)),listOf(5),f))
        assertEquals("unverified_value_evidence",problem(listOf(presenceCheck(true)),listOf(8),f.copy(allNodes=listOf(n,n.copy(id=9,path="0/9")))))
    }
    @Test fun inputPresenceExpectationCannotBeDroppedOrFlippedWithoutAuthority(){
        val s=TaskSession();s.start("원하는 값 30",frame.packageName,"공개 화면")
        val c=condition(listOf(presenceCheck(true)),listOf(8));assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,1,1000)),plan(c)))
        val owned=s.plan!!.conditions.single()
        assertEquals("unexplained_requirement_change",s.review(plan(owned.copy(expectations=emptyList()))).problem)
        assertEquals("unexplained_requirement_change",s.review(plan(owned.copy(expectations=listOf(presenceCheck(false))))).problem)
    }
    private fun node(id:Int)=AccessibilityNodeModel(id,"0/$id","0","android.view.View","공개 값 $id",null,"public:id/value$id",
        true,true,true,false,true,false,NodeBounds(0,id*80,400,id*80+60),emptyList())
    private val nodes=listOf(node(1).copy(range=NodeRange(0f,60f,30f,0)),node(2).copy(checkable=true,checked=1),
        node(3).copy(checkable=true,checked=0),node(4).copy(selected=false),node(5))
    private val frame=ScreenSnapshot(1,"public.values.fixture",5,1000,nodes,nodes,false,NodeBounds(0,0,500,1000))
    private fun expected(id:Int,property:ValueProperty,number:Double?=null,flag:Boolean?=null)=ValueExpectation(property,
        ValueSubject("android.view.View","public:id/value$id",""),number,flag)
    private fun condition(checks:List<ValueExpectation>,ids:List<Int>)=GoalCondition("ready","공개 값 준비",true,Truth.SATISFIED,
        EvidenceKind.OBSERVED,ids,basis=ConditionBasis(ConditionOrigin.REQUEST,quote="원하는 값 30"),phase=ConditionPhase.PREPARATION,expectations=checks)
    private fun plan(c:GoalCondition)=TaskPlan(NextKind.OBSERVE,"공개 값",GoalKind.STATE,listOf(c),emptyList(),emptyList(),Operation.NONE,null,
        "현재 값을 확인해주세요.",null,WaitKind.NONE,emptyList(),emptyList())
    private fun problem(checks:List<ValueExpectation>,ids:List<Int>,screen:ScreenSnapshot=frame)=V3ValueEvidence.problem(plan(condition(checks,ids)),screen)
    @Test fun rangeEqualityUsesObservedMetadataRatherThanTheModelTruth(){
        assertNull(problem(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(1)))
        assertEquals("value_mismatch",problem(listOf(expected(1,ValueProperty.RANGE,29.0)),listOf(1)))
    }
    @Test fun aRangeWithoutAnExpectationCannotCertifyPreparation(){assertEquals("missing_value_expectation",problem(emptyList(),listOf(1)))}
    @Test fun anUnknownDesiredValueDoesNotRequireInventingANumber(){
        val c=condition(emptyList(),listOf(1)).copy(truth=Truth.UNKNOWN,evidence=EvidenceKind.UNKNOWN)
        val p=plan(c).copy(kind=NextKind.ASK,question=PlanQuestion("desired","원하는 값을 알려주세요.",true,true,false,listOf(c.key)))
        assertNull(V3ValueEvidence.problem(p,frame))
    }
    @Test fun allCheckedSubjectsAreComparedInsteadOfOnlyTheFirst(){
        val checks=listOf(expected(2,ValueProperty.CHECKED,flag=true),expected(3,ValueProperty.CHECKED,flag=true))
        assertEquals("value_mismatch",problem(checks,listOf(2,3)))
        val ready=frame.copy(allNodes=nodes.map{if(it.id==3)it.copy(checked=1)else it})
        assertNull(problem(checks,listOf(2,3),ready))
    }
    @Test fun omittingOneCheckedSubjectDoesNotProveTheWholeSet(){
        assertEquals("missing_value_expectation",problem(listOf(expected(2,ValueProperty.CHECKED,flag=true)),listOf(2,3)))
    }
    @Test fun falseAndUnknownCheckboxValuesRemainDifferent(){
        assertNull(problem(listOf(expected(3,ValueProperty.CHECKED,flag=false)),listOf(3)))
        assertEquals("unverified_value_evidence",problem(listOf(expected(3,ValueProperty.CHECKED,flag=false)),listOf(3),
            frame.copy(allNodes=nodes.map{if(it.id==3)it.copy(checked=null)else it})))
    }
    @Test fun selectionHasItsOwnFalseAndUnknownValues(){
        assertNull(problem(listOf(expected(4,ValueProperty.SELECTED,flag=false)),listOf(4)))
        assertEquals("unverified_value_evidence",problem(listOf(expected(4,ValueProperty.SELECTED,flag=false)),listOf(4),
            frame.copy(allNodes=nodes.map{if(it.id==4)it.copy(selected=null)else it})))
    }
    @Test fun selectedStateDoesNotReplaceCheckedState(){
        val f=frame.copy(allNodes=nodes.map{if(it.id==3)it.copy(selected=true)else it})
        assertEquals("missing_value_expectation",problem(listOf(expected(3,ValueProperty.SELECTED,flag=true)),listOf(3),f))
    }
    @Test fun theResolvedSubjectMustAlsoBeACurrentEvidenceReference(){
        assertEquals("unverified_value_evidence",problem(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(5)))
    }
    @Test fun duplicateIdentitiesCannotBeResolvedByChoosingTheValueThatHappensToMatch(){
        val duplicate=nodes[0].copy(id=9,path="0/9",range=NodeRange(0f,60f,0f,0))
        assertEquals("unverified_value_evidence",problem(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(1),frame.copy(allNodes=nodes+duplicate)))
    }
    @Test fun geometryAndSnapshotIdsAreNotDesiredValueIdentity(){
        val moved=nodes.map{if(it.id==1)it.copy(id=99,bounds=NodeBounds(30,500,400,560))else it}
        assertNull(problem(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(99),frame.copy(snapshotId=2,allNodes=moved)))
    }
    @Test fun publicStaticLabelsCanIdentifySubjectsWithoutResources(){
        val changed=nodes.map{if(it.id==2)it.copy(viewIdResourceName=null,text="공개 선택")else it}
        val check=ValueExpectation(ValueProperty.CHECKED,ValueSubject("android.view.View","","공개 선택"),expectedBoolean=true)
        assertNull(problem(listOf(check),listOf(2),frame.copy(allNodes=changed)))
    }
    @Test fun privateOrEditableMetadataCannotBeCertified(){
        for(n in listOf(nodes[0].copy(password=true),nodes[0].copy(sensitiveContent=true),nodes[0].copy(editable=true),nodes[0].copy(visibleToUser=false))){
            assertEquals("unverified_value_evidence",problem(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(1),frame.copy(allNodes=listOf(n))))
        }
    }
    @Test fun unknownAndUnusableRangeValuesDoNotBecomeZero(){
        for(r in listOf<NodeRange?>(null,NodeRange(0f,0f,0f,0),NodeRange(0f,60f,Float.NaN,0))){
            val n=nodes[0].copy(range=r)
            assertEquals(V3ValueEvidence.Result.UNKNOWN,V3ValueEvidence.evaluate(expected(1,ValueProperty.RANGE,0.0),n))
        }
    }
    @Test fun decimalAndNegativeValuesUseTheSameFloatDecimalAsTheWire(){
        assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(expected(1,ValueProperty.RANGE,0.1),nodes[0].copy(range=NodeRange(0f,1f,0.1f,0))))
        assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(expected(1,ValueProperty.RANGE,-5.5),nodes[0].copy(range=NodeRange(-10f,10f,-5.5f,0))))
        assertEquals(V3ValueEvidence.Result.MISMATCH,V3ValueEvidence.evaluate(expected(1,ValueProperty.RANGE,30.000001),nodes[0]))
    }
    @Test fun unsupportedCoercionsAndNonFiniteExpectationsAreRejected(){
        assertFalse(expected(1,ValueProperty.RANGE,Double.NaN).valid());assertFalse(expected(1,ValueProperty.RANGE,30.0,true).valid())
        assertFalse(expected(2,ValueProperty.CHECKED,30.0,true).valid());assertFalse(expected(2,ValueProperty.CHECKED).valid())
        assertFalse(expected(1,ValueProperty.RANGE,30.0).copy(subject=ValueSubject("android.view.View","public:id/value1","현재 30")).valid())
    }
    @Test fun providedOutcomeExpectationsAlsoNeedIndependentProof(){
        val c=condition(listOf(expected(1,ValueProperty.RANGE,29.0)),listOf(1)).copy(phase=ConditionPhase.OUTCOME)
        assertEquals("value_mismatch",V3ValueEvidence.problem(plan(c),frame))
    }
    @Test fun desiredExpectationsCannotBeSilentlyRemovedOrChanged(){
        val s=TaskSession();s.start("원하는 값 30",frame.packageName,"공개 화면")
        val c=condition(listOf(expected(1,ValueProperty.RANGE,30.0)),listOf(1))
        assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,1,1000)),plan(c)))
        val owned=s.plan!!.conditions.single()
        assertEquals("unexplained_requirement_change",s.review(plan(owned.copy(expectations=emptyList()))).problem)
        assertEquals("unexplained_requirement_change",s.review(plan(owned.copy(expectations=listOf(expected(1,ValueProperty.RANGE,0.0))))).problem)
    }
    @Test fun equivalentExpectationOrderingDoesNotRequireAnEdit(){
        val s=TaskSession();s.start("원하는 값 30",frame.packageName,"공개 화면")
        val c=condition(listOf(expected(2,ValueProperty.CHECKED,flag=true),expected(3,ValueProperty.CHECKED,flag=false)),listOf(2,3))
        assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,1,1000)),plan(c)))
        assertNull(s.review(plan(s.plan!!.conditions.single().copy(expectations=c.expectations.reversed(),basis=null))).problem)
    }
    @Test fun negativeZeroDoesNotCreateADifferentDesiredDefinition(){
        assertEquals(0.0.toBits(),expected(1,ValueProperty.RANGE,-0.0).canonical().expectedNumber!!.toBits())
    }
    @Test fun newlyObservableChecksCanStrengthenTheSameExplicitGoalWithoutReaskingItsValue(){
        val s=TaskSession();s.start("원하는 값 30",frame.packageName,"공개 화면")
        val unknown=condition(emptyList(),emptyList()).copy(truth=Truth.UNKNOWN,evidence=EvidenceKind.UNKNOWN)
        assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,1,1000)),plan(unknown)))
        val report=s.plan!!.conditions.single().copy(expectations=listOf(expected(1,ValueProperty.RANGE,30.0)),
            truth=Truth.UNSATISFIED,evidence=EvidenceKind.OBSERVED,evidenceNodeIds=listOf(1))
        val p=plan(report).copy(conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.REPLACE,"새로 관찰한 공개 비교를 추가",
            ConditionBasis(ConditionOrigin.INFERRED,quote="원하는 값 30",rationale="현재 화면에서 수치 메타데이터를 관찰함",evidenceNodeIds=listOf(1)))))
        val review=s.review(p);assertNull(review.problem)
        assertEquals(ConditionOrigin.REQUEST,review.requirements.single().basis.kind)
        assertEquals(2,review.requirements.single().version)
        assertTrue(s.accept(s.issue(ObservationRef(frame.packageName,5,2,1100)),p))
        val weakened=s.plan!!.conditions.single().copy(expectations=listOf(expected(1,ValueProperty.RANGE,0.0)))
        val bad=plan(weakened).copy(conditionEdits=listOf(ConditionEdit(1,2,ConditionEditKind.REPLACE,"현재 값에 맞춰 기대 값 낮추기",
            ConditionBasis(ConditionOrigin.INFERRED,quote="원하는 값 30",rationale="현재 수치 관찰",evidenceNodeIds=listOf(1)))))
        assertEquals("unauthorized_condition_edit",s.review(bad).problem)
    }
}
