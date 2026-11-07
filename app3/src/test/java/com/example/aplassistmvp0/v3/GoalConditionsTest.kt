package com.example.aplassistmvp0.v3

import org.junit.Assert.*
import org.junit.Test

class GoalConditionsTest {
    private val text="범위 A에서 원하는 값과 반복 조건을 설정해줘"
    private val observation=ObservationRef("public.conditions.fixture",5,1,1000)
    private val explicit=ConditionBasis(ConditionOrigin.REQUEST,quote="범위 A")
    private val inferred=ConditionBasis(ConditionOrigin.INFERRED,quote="설정",rationale="목표를 위해 필요하다고 추론",evidenceNodeIds=listOf(1))
    private fun condition(key:String="scope",description:String="범위 A 유지",basis:ConditionBasis=explicit)=
        GoalCondition(key,description,true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),basis=basis)
    private fun plan(conditions:List<GoalCondition>,kind:NextKind=NextKind.OBSERVE)=TaskPlan(kind,"공개 목표",GoalKind.STATE,
        conditions,emptyList(),emptyList(),Operation.NONE,null,"화면을 확인해주세요.",null,WaitKind.NONE,emptyList(),emptyList())
    private fun session(extra:Boolean=false)=TaskSession().apply{
        start(text,observation.packageName,"공개 시험")
        val conditions=mutableListOf(condition())
        if(extra)conditions+=condition("repeat","반복 조건 유지",ConditionBasis(ConditionOrigin.REQUEST,quote="반복 조건"))
        assertTrue(accept(issue(observation),plan(conditions)))
    }
    private fun report(s:TaskSession,id:Long=1,key:String="r$id"):GoalCondition {
        val r=s.goal!!.requirements.single{it.id==id}
        return GoalCondition(key,r.description,r.required,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(1),r.id,r.version)
    }
    private fun review(s:TaskSession,p:TaskPlan)=GoalConditions.review(s.goal!!,p)
    private fun ask(s:TaskSession,key:String="scope_change",target:Long=1){
        val c=report(s,target,"selected")
        assertTrue(s.accept(s.issue(observation),plan(listOf(c),NextKind.ASK).copy(
            question=PlanQuestion(key,"어떤 범위로 바꿀까요?",true,true,false,listOf(c.key)))))
    }
    private fun answerBasis(key:String="scope_change",quote:String="범위 B")=ConditionBasis(ConditionOrigin.ANSWER,key,quote)
    private fun replace(s:TaskSession,description:String="범위 B 유지",basis:ConditionBasis=answerBasis(),id:Long=1)=
        plan(listOf(report(s,id).copy(description=description))).copy(conditionEdits=listOf(
            ConditionEdit(id,s.goal!!.requirements.single{it.id==id}.version,ConditionEditKind.REPLACE,"사용자의 범위 변경",basis)))

    @Test fun definitionsReceiveLocalIdsAndReturnCanonicalReferences(){
        val s=session(true)
        assertEquals(listOf(1L,2L),s.goal!!.requirements.map{it.id})
        assertTrue(s.plan!!.conditions.all{it.requirementVersion==1 && it.basis==null})
        assertEquals("범위 A 유지",s.goal!!.requirements.first().description)
    }
    @Test fun anIntermediateOmissionDoesNotDeleteTheRequirement(){
        val s=session(true);assertTrue(s.accept(s.issue(observation),plan(listOf(report(s,2)))))
        assertEquals(2,s.goal!!.requirements.size)
        assertEquals("missing_owned_requirement",review(s,plan(listOf(report(s,2)),NextKind.COMPLETE)).problem)
    }
    @Test fun changingAKeyDoesNotChangeRequirementIdentity(){
        val s=session(true);val p=plan(listOf(report(s,1,"new_key"),report(s,2,"another_key")),NextKind.COMPLETE)
        assertNull(review(s,p).problem)
    }
    @Test fun descriptionsAndRequiredFlagsCannotChangeSilently(){
        val s=session()
        for(c in listOf(report(s).copy(description="범위 B 유지"),report(s).copy(required=false)))
            assertEquals("unexplained_requirement_change",review(s,plan(listOf(c))).problem)
    }
    @Test fun fabricatedPartialDuplicateAndStaleReferencesAreRejected(){
        val s=session();val c=report(s)
        for((changed,problem) in listOf(c.copy(requirementId=99) to "unknown_requirement",
            c.copy(requirementVersion=2) to "stale_requirement_version",c.copy(requirementVersion=null) to "partial_requirement_reference"))
            assertEquals(problem,review(s,plan(listOf(changed))).problem)
        assertEquals("duplicate_requirement_report",review(s,plan(listOf(c,c.copy(key="second")))).problem)
        assertEquals("duplicate_requirement_definition",review(s,plan(listOf(condition("new_key")))).problem)
    }
    @Test fun aPastSatisfiedObservationDoesNotCompleteTheCurrentObservation(){
        val s=session();assertTrue(s.accept(s.issue(observation),plan(listOf(report(s)))))
        assertEquals("missing_owned_requirement",review(s,plan(emptyList(),NextKind.COMPLETE)).problem)
        assertEquals("unverified_owned_requirement",review(s,plan(listOf(report(s).copy(truth=Truth.UNKNOWN)),NextKind.COMPLETE)).problem)
    }
    @Test fun explicitRequirementsCannotBeRetiredByModelReinterpretation(){
        val s=session()
        for(b in listOf(inferred,explicit.copy(rationale="화면에 없으므로 제외",evidenceNodeIds=listOf(2)))){
            val p=plan(emptyList()).copy(conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.RETIRE,"모델이 판단 변경",b)))
            assertEquals("unauthorized_condition_edit",review(s,p).problem)
        }
    }
    @Test fun aWrongInferredRequirementCanBeExplicitlyCorrectedWithoutAUserRoundTrip(){
        val s=session();assertTrue(s.accept(s.issue(observation),plan(listOf(condition("inference","추론한 추가 조건",inferred)))))
        val p=plan(listOf(report(s))).copy(conditionEdits=listOf(ConditionEdit(2,1,ConditionEditKind.RETIRE,"현재 근거로 불필요함을 확인",inferred)))
        assertTrue(s.accept(s.issue(observation),p))
        assertTrue(s.goal!!.requirements.last().retired);assertEquals(2,s.goal!!.requirements.last().version)
        assertNull(review(s,plan(listOf(report(s)),NextKind.COMPLETE)).problem)
        assertTrue(s.plan!!.conditionEdits.isEmpty())
    }
    @Test fun inferenceCorrectionsNeedCurrentEvidenceAndAnExplanation(){
        val s=TaskSession().apply{start(text,observation.packageName,"시험");accept(issue(observation),plan(listOf(condition(basis=inferred))))}
        for(b in listOf(inferred.copy(evidenceNodeIds=emptyList()),inferred.copy(rationale=""))){
            val p=plan(emptyList()).copy(conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.RETIRE,"정정",b)))
            assertNotNull(review(s,p).problem)
        }
    }
    @Test fun sourcesMustBeAnchoredToAnExistingRequestOrAnswer(){
        val g=TaskGoal(1,1,text,observation.packageName,"시험")
        for(b in listOf(explicit.copy(quote="요청에 없는 말"),explicit.copy(quote=""),explicit.copy(key="invented"),
            answerBasis(),inferred.copy(rationale="")))assertNotNull(GoalConditions.review(g,plan(listOf(condition(basis=b)))).problem)
    }
    @Test fun aQuestionAnswerOnlyAuthorizesItsDeclaredConditionScope(){
        val s=session(true);ask(s);assertTrue(s.answer("scope_change","범위 B"))
        assertEquals(setOf(1L),s.goal!!.answerAuthorities.getValue("scope_change").requirementIds)
        assertEquals("scope_change",s.goal!!.requirements.first().staleAnswerKey)
        assertNull(s.goal!!.requirements.last().staleAnswerKey)
        val wrong=replace(s,description="다른 반복",id=2)
        assertEquals("unauthorized_condition_edit",review(s,wrong).problem)
    }
    @Test fun anAnswerMustBeReconciledBeforeOldDesiredValuesCanComplete(){
        val s=session();ask(s);s.answer("scope_change","범위 B")
        assertEquals("answer_requires_reconciliation",review(s,plan(listOf(report(s)),NextKind.COMPLETE)).problem)
        assertTrue(s.accept(s.issue(observation),replace(s)))
        val r=s.goal!!.requirements.single()
        assertEquals(2,r.version);assertEquals("범위 B 유지",r.description);assertNull(r.staleAnswerKey)
        assertEquals(ConditionOrigin.ANSWER,r.basis.kind)
    }
    @Test fun theSameAnswerCannotAuthorizeRepeatedDifferentEdits(){
        val s=session();ask(s);s.answer("scope_change","범위 B");s.accept(s.issue(observation),replace(s))
        val p=plan(emptyList()).copy(conditionEdits=listOf(ConditionEdit(1,2,ConditionEditKind.RETIRE,"다시 바꿈",answerBasis())))
        assertEquals("unauthorized_condition_edit",review(s,p).problem)
    }
    @Test fun aScopedAnswerEditInvalidatesOldTicketsAndCanIssueANewConditionVersion(){
        val s=session();ask(s);s.answer("scope_change","범위 B");s.accept(s.issue(observation),replace(s))
        val old=s.issue(observation);assertTrue(s.beginAnswerEdit("scope_change"));assertFalse(s.accept(old,replace(s)))
        assertTrue(s.answer("scope_change","범위 C"))
        assertTrue(s.accept(s.issue(observation),replace(s,"범위 C 유지",answerBasis(quote="범위 C"))))
        assertEquals(3,s.goal!!.requirements.single().version);assertEquals(3L,s.goal!!.revision)
    }
    @Test fun onlyAFreshScopedAnswerCanRelaxAnExplicitRequirement(){
        val s=session(true);ask(s);s.answer("scope_change","아무 범위나 괜찮아")
        val p=replace(s,basis=answerBasis(quote="아무 범위나 괜찮아")).let{it.copy(conditions=it.conditions.map{c->c.copy(required=false)}+report(s,2))}
        assertTrue(s.accept(s.issue(observation),p));assertFalse(s.goal!!.requirements.first().required)
        assertNull(review(s,plan(listOf(report(s,2)),NextKind.COMPLETE)).problem)
    }
    @Test fun retiredDefinitionsRemainTombstonedInsteadOfBeingReaddedAsNew(){
        val s=TaskSession().apply{start(text,observation.packageName,"시험");accept(issue(observation),plan(listOf(condition(basis=inferred))))}
        val retire=plan(emptyList()).copy(conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.RETIRE,"잘못된 추론 정정",inferred)))
        assertTrue(s.accept(s.issue(observation),retire))
        assertEquals("duplicate_requirement_definition",review(s,plan(listOf(condition(basis=inferred)))).problem)
    }
    @Test fun reactivatingARetiredExplicitRequirementNeedsANewScopedAnswerAndVersion(){
        val s=session();ask(s);s.answer("scope_change","이 조건은 빼줘")
        val retire=plan(emptyList()).copy(conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.RETIRE,"사용자 해제",answerBasis(quote="이 조건은 빼줘"))))
        assertTrue(s.accept(s.issue(observation),retire));assertTrue(s.goal!!.requirements.single().retired)
        assertTrue(s.beginAnswerEdit("scope_change"));s.answer("scope_change","범위 A로 다시")
        assertTrue(s.accept(s.issue(observation),replace(s,"범위 A 유지",answerBasis(quote="범위 A로 다시"))))
        assertEquals(3,s.goal!!.requirements.single().version);assertFalse(s.goal!!.requirements.single().retired)
    }
    @Test fun aRejectedBatchDoesNotPartiallyAddOrChangeRequirements(){
        val s=session();val before=s.goal!!.requirements
        val p=plan(listOf(condition("new","추가 조건",inferred),report(s).copy(required=false)))
        assertFalse(s.accept(s.issue(observation),p));assertEquals(before,s.goal!!.requirements);assertNull(s.ticket)
        assertTrue(s.accept(s.issue(observation),plan(listOf(condition("new","추가 조건",inferred)))))
        assertEquals(2L,s.goal!!.requirements.last().id)
    }
    @Test fun fabricatedSourceNodesAreRejectedByTheSameGroundingBoundary(){
        val s=session()
        val p=plan(listOf(condition("new","추론 조건",inferred.copy(evidenceNodeIds=listOf(999)))))
        assertEquals("invented_node",TaskPlanValidation.problem(p,s.goal!!,setOf(1,2)))
    }
    @Test fun questionScopesCannotMentionConditionsAbsentFromTheProposal(){
        val s=session();val p=plan(listOf(report(s)),NextKind.ASK).copy(question=PlanQuestion("choice","어떤 값인가요?",true,true,false,listOf("invented")))
        assertEquals("unknown_question_condition",review(s,p).problem)
    }
    @Test fun anExistingQuestionKeyCannotCaptureAnAnswerForADifferentCondition(){
        val s=session(true);ask(s)
        val old=s.question!!
        val c=report(s,2,"selected")
        val changed=plan(listOf(c),NextKind.ASK).copy(question=old)
        assertFalse(s.accept(s.issue(observation),changed));assertEquals(old,s.question)
        assertTrue(s.answer("scope_change","범위 B"))
        assertEquals(setOf(1L),s.goal!!.answerAuthorities.getValue("scope_change").requirementIds)
    }
    @Test fun aNewGoalRetiresAllOldConditionAndAnswerAuthority(){
        val s=session();ask(s);s.answer("scope_change","범위 B");val old=s.issue(observation)
        s.start("새 목표",observation.packageName,"시험")
        assertTrue(s.goal!!.requirements.isEmpty());assertTrue(s.goal!!.answerAuthorities.isEmpty());assertFalse(s.current(old))
        assertEquals("unknown_requirement",review(s,plan(listOf(GoalCondition("old","범위 A 유지",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),1,1)))).problem)
    }
}
