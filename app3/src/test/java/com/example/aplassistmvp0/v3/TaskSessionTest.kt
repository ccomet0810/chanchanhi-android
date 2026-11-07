package com.example.aplassistmvp0.v3

import org.junit.Assert.*
import org.junit.Test

class TaskSessionTest {
    private val observation=ObservationRef("public.fixture",5,1,1000)
    private val question=PlanQuestion("alarmTime","언제 울리게 할까요?",true,true,false)
    private fun session()=TaskSession().apply{start("알람 맞추고 싶어","public.fixture","시험 앱")}
    private fun proposal(kind:NextKind=NextKind.ASK,q:PlanQuestion?=question)=TaskPlan(kind,"알람 설정",GoalKind.NEW_EVENT,
        emptyList(),emptyList(),emptyList(),Operation.NONE,null,"언제 울리게 할까요?",q,WaitKind.NONE,emptyList(),emptyList())
    @Test fun closedSessionRejectsLateResponse(){val s=session();val ticket=s.issue(observation);s.close();assertFalse(s.accept(ticket,proposal()));assertNull(s.plan)}
    @Test fun newSessionDoesNotReuseAnOldTicket(){val s=session();val t=s.issue(observation);s.start("문자 보내고 싶어","public.fixture","시험 앱");assertFalse(s.accept(t,proposal()))}
    @Test fun onlyTheCurrentRequestCanReturn(){val s=session();val old=s.issue(observation);val current=s.issue(observation.copy(sequence=2));assertFalse(s.accept(old,proposal()));assertTrue(s.accept(current,proposal()))}
    @Test fun duplicateResponseCannotBeApplied(){val s=session();val t=s.issue(observation);assertTrue(s.accept(t,proposal()));assertFalse(s.accept(t,proposal()))}
    @Test fun answersBelongToTheCurrentQuestion(){val s=session();assertTrue(s.accept(s.issue(observation),proposal()));assertFalse(s.answer("other","오전 7시"));assertTrue(s.answer(question.key,"평일 오전 7시 반"));assertEquals(2,s.goal!!.revision);assertEquals("평일 오전 7시 반",s.goal!!.answers[question.key]);assertNull(s.question)}
    @Test fun answeringRetiresEveryPendingPlan(){val s=session();s.accept(s.issue(observation),proposal());val late=s.issue(observation);assertTrue(s.answer(question.key,"오후 7시"));assertFalse(s.accept(late,proposal()))}
    @Test fun questionsAreNotRequiredForDirectAppInput(){val s=session();val p=proposal(q=question.copy(key="recipient",canEnterInApp=true));assertEquals("unnecessary_question",TaskPlanValidation.problem(p,s.goal!!,emptySet()))}
    @Test fun optionalAndFutureChoicesDoNotTriggerQuestions(){val s=session();for(q in listOf(question.copy(neededNow=false),question.copy(changesGuidance=false)))assertEquals("unnecessary_question",TaskPlanValidation.problem(proposal(q=q),s.goal!!,emptySet()))}
    @Test fun alreadyAnsweredQuestionIsNotRepeated(){val s=session();s.accept(s.issue(observation),proposal());s.answer(question.key,"오전 7시");assertEquals("repeated_question",TaskPlanValidation.problem(proposal(),s.goal!!,emptySet()))}
    @Test fun unsupportedCompletionDoesNotPass(){val s=session();val p=proposal(NextKind.COMPLETE,null);assertEquals("unverified_completion",TaskPlanValidation.problem(p,s.goal!!,emptySet()))}
    @Test fun inferredOrReportedFactsAreNotIndependentObservation(){val s=session();for(source in listOf(EvidenceKind.INFERRED,EvidenceKind.USER_REPORTED,EvidenceKind.UNKNOWN)){
        val p=proposal(NextKind.COMPLETE,null).copy(conditions=listOf(GoalCondition("saved","저장됨",true,Truth.SATISFIED,source,listOf(1))),newEventNodeIds=listOf(1));assertEquals("unverified_completion",TaskPlanValidation.problem(p,s.goal!!,setOf(1)))}}
    @Test fun aNewEventNeedsCurrentResultEvidence(){val s=session();val p=proposal(NextKind.COMPLETE,null).copy(conditions=listOf(GoalCondition("saved","저장됨",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(1))));assertEquals("missing_new_event_evidence",TaskPlanValidation.problem(p,s.goal!!,setOf(1)))}
    @Test fun fabricatedNodesAreRejected(){val s=session();val p=proposal(NextKind.GUIDE,null).copy(operation=Operation.TAP,targetNodeId=99);assertEquals("invented_node",TaskPlanValidation.problem(p,s.goal!!,setOf(1)))}
    @Test fun cyclicDependenciesCannotBecomeAPlan(){val s=session();val p=proposal().copy(tasks=listOf(TaskStep("a","첫 작업",emptyList(),listOf("b")),TaskStep("b","둘째 작업",emptyList(),listOf("a"))));assertEquals("dependency_cycle",TaskPlanValidation.problem(p,s.goal!!,emptySet()))}
    @Test fun realPublicModelConditionKeysCannotBecomeTaskRelationMembers(){
        val s=session()
        val p=proposal(NextKind.OBSERVE,null).copy(tasks=listOf(TaskStep("find_recipient","받는 사람 찾기",emptyList(),emptyList())),
            relations=listOf(TaskRelation(RelationKind.ALL,listOf("recipient","message_sent"))))
        assertEquals("unknown_relation_member",TaskPlanValidation.problem(p,s.goal!!,emptySet()))
        assertNull(TaskPlanValidation.problem(p.copy(relations=listOf(TaskRelation(RelationKind.ALL,listOf("find_recipient")))),s.goal!!,emptySet()))
    }
    @Test fun taskRelationsRejectEmptyRepeatedAndAbsentMembers(){
        val s=session();val p=proposal(NextKind.OBSERVE,null).copy(tasks=listOf(TaskStep("one","공개 작업",emptyList(),emptyList())))
        for(members in listOf(emptyList(),listOf("one","one"),listOf("absent")))
            assertEquals("unknown_relation_member",TaskPlanValidation.problem(p.copy(relations=listOf(TaskRelation(RelationKind.SET,members))),s.goal!!,emptySet()))
    }
    @Test fun multipleSelectionsRemainASetOfTasks(){val s=session();val p=proposal(NextKind.GUIDE,null).copy(tasks=listOf(TaskStep("mon","월",listOf(1),emptyList()),TaskStep("tue","화",listOf(2),emptyList())),relations=listOf(TaskRelation(RelationKind.SET,listOf("mon","tue"))),operation=Operation.SELECT,targetNodeId=1);assertNull(TaskPlanValidation.problem(p,s.goal!!,setOf(1,2)))}
    @Test fun activityWaitingNeedsAnExplicitReason(){val s=session();assertEquals("missing_wait_reason",TaskPlanValidation.problem(proposal(NextKind.WAIT,null),s.goal!!,emptySet()));assertNull(TaskPlanValidation.problem(proposal(NextKind.WAIT,null).copy(waitKind=WaitKind.USER_ACTIVITY),s.goal!!,emptySet()))}
    @Test fun aModelCannotInventAnOperationReceipt(){
        val s=session();val p=proposal(NextKind.OBSERVE,null).copy(commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(1),outcomeReceiptId=7)
        assertEquals("unknown_receipt",TaskPlanValidation.problem(p,s.goal!!,setOf(1)))
        s.rememberOperations(listOf(OperationRecord(1,7,1,1,CommitOutcome.PENDING,true,"확정","Button","public:id/commit")))
        assertNull(TaskPlanValidation.problem(p,s.goal!!,setOf(1)))
        assertEquals("missing_or_unexpected_receipt",TaskPlanValidation.problem(p.copy(commitOutcome=CommitOutcome.NONE),s.goal!!,setOf(1)))
    }
    @Test fun answeringAQuestionRetainsLocalExecutionHistoryButANewGoalClearsIt(){
        val s=session();val records=listOf(OperationRecord(1,7,1,1,CommitOutcome.SUCCEEDED,true,"확정","Button","public:id/commit"))
        s.rememberOperations(records);s.accept(s.issue(observation),proposal());assertTrue(s.answer(question.key,"오후 7시"))
        assertEquals(records,s.goal!!.operationHistory)
        s.start("다른 요청","public.fixture","시험 앱");assertTrue(s.goal!!.operationHistory.isEmpty())
    }
    @Test fun aLaterProposalCannotDropTheOwnedNewEventRequirement(){
        val s=session();s.accept(s.issue(observation),proposal())
        assertTrue(s.goal!!.requiresNewEvent)
        val completed=proposal(NextKind.COMPLETE,null).copy(goalKind=GoalKind.STATE,
            conditions=listOf(GoalCondition("state","보이는 상태",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(1))))
        assertEquals("missing_new_event_evidence",TaskPlanValidation.problem(completed,s.goal!!,setOf(1)))
        s.accept(s.issue(observation),proposal(NextKind.OBSERVE,null).copy(goalKind=GoalKind.STATE))
        assertTrue(s.goal!!.requiresNewEvent)
        s.start("새 상태 확인","public.fixture","시험 앱");assertFalse(s.goal!!.requiresNewEvent)
    }
}
