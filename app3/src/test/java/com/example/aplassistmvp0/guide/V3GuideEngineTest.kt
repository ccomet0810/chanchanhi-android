package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.*
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V3GuideEngineTest {
    @Test fun aKnownCommitClickedDuringReplanningGetsAReceiptAndCannotBeGuidedAgain()=runTest {
        val late=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->when(call){1->commit();2->withContext(NonCancellable){late.await()};else->commit().copy(commitOutcome=CommitOutcome.UNKNOWN)}})
        f.begin();f.frame(tick="이전 기록");settle();assertEquals(1,f.highlights.size)
        assertTrue(f.engine.retryFromCurrent(rejected=true));settle();assertEquals(2,f.calls)
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="처리 중");settle()
        assertEquals("A real click on the retained known commit must reach the next goal",1,f.lastGoal!!.operationHistory.size)
        assertTrue(f.lastGoal!!.operationHistory.single().interactionVerified)
        assertEquals(1,f.highlights.size);assertEquals("wait",f.engine.ui.currentAction)
        late.complete(commit().copy(message="재분석 전의 늦은 확정 안내"));settle()
        assertEquals(1,f.highlights.size);assertEquals("wait",f.engine.ui.currentAction);f.engine.stop()
    }
    @Test fun aPausedTaskCannotIssueAReceiptForAnIndependentAppClick()=runTest {
        val f=Fixture(this,custom={commit()});f.begin();f.frame(tick="이전 기록");settle()
        f.engine.onScreenUnavailable("안내를 잠시 멈췄어요.")
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="독립적인 앱 조작");settle()
        assertEquals("paused",f.engine.ui.phase)
        assertTrue(f.engine.retryFromCurrent());settle()
        assertTrue("A paused interaction must not silently become this task's receipt",f.lastGoal!!.operationHistory.isEmpty());f.engine.stop()
    }
    @Test fun aDifferentButtonDuringReplanningIsNotInventedAsTheKnownCommit()=runTest {
        val late=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==2)withContext(NonCancellable){late.await()}else commit()})
        f.begin();f.frame();settle();assertTrue(f.engine.retryFromCurrent());settle()
        f.engine.onUserAction(PKG,evidence=f.click(4));f.frame();settle()
        assertTrue(f.lastGoal!!.operationHistory.isEmpty());late.complete(commit());settle();f.engine.stop()
    }
    @Test fun aManualPlanPublishesItsReadonlyEvidenceForPanelPlacement()=runTest {
        val f=Fixture(this,custom={transitionWait().copy(kind=NextKind.OBSERVE,waitKind=WaitKind.NONE)})
        f.begin();f.frame(tick="검색 결과 없음");settle()
        val frame=f.screens.value.snapshot!!
        assertEquals(listOf(frame.allNodes.single{it.id==3}.bounds),GuideOverlayEvidence.regions(f.engine.ui,frame))
        f.engine.stop()
    }
    @Test fun loadingEvidenceExpiresDuringReplanningAndStop()=runTest {
        val pending=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==1)transitionWait()else withContext(NonCancellable){pending.await()}})
        f.begin();f.frame(tick="검색 중");settle();assertNotNull(f.engine.ui.overlayObservation)
        f.frame(tick="검색 결과 없음");runCurrent()
        assertTrue(f.engine.ui.busy);assertNull(f.engine.ui.overlayObservation)
        f.engine.stop();pending.complete(transitionWait());settle()
        assertNull(f.engine.ui.overlayObservation);assertEquals("idle",f.engine.ui.phase)
    }
    @Test fun aPausedGuideDoesNotKeepActivePanelEvidence()=runTest {
        val f=Fixture(this,custom={transitionWait()});f.begin();f.frame(tick="검색 중");settle()
        assertNotNull(f.engine.ui.overlayObservation)
        f.engine.onScreenUnavailable("화면 읽기 연결을 확인해주세요.")
        assertNull(f.engine.ui.overlayObservation);f.engine.stop()
    }
    private fun transitionWait()=guide().copy(kind=NextKind.WAIT,operation=Operation.NONE,targetNodeId=null,
        waitKind=WaitKind.SCREEN_TRANSITION,message="결과를 기다려주세요.",relevantNodeIds=listOf(3))
    @Test fun aRelevantReadonlyLoadingStatusCanWakePlanningWithoutAButtonChange()=runTest {
        val f=Fixture(this,custom={if(it==1)transitionWait()else guide()})
        f.begin();f.frame(tick="검색 중");settle();assertEquals(1,f.calls)
        f.frame(tick="검색 결과 없음");settle()
        assertEquals("The relevant public result changed even though buttons and input stayed the same",2,f.calls);f.engine.stop()
    }
    @Test fun repeatedOrLayoutOnlyLoadingCapturesDoNotCreateMorePlans()=runTest {
        val f=Fixture(this,custom={transitionWait()});f.begin();f.frame(tick="검색 중");settle()
        repeat(4){
            f.frame(tick="검색 중")
            val state=f.screens.value;val nodes=state.snapshot!!.allNodes.map{if(it.id==3)it.copy(bounds=it.bounds.copy(top=it.bounds.top+10,bottom=it.bounds.bottom+10),focused=it.focused!=true)else it}
            f.screens.value=state.copy(snapshot=state.snapshot.copy(nodes=nodes,allNodes=nodes));settle()
        }
        assertEquals(1,f.calls);f.engine.stop()
    }
    @Test fun unrelatedPrivateReadonlyContentDoesNotWakeAPlannedLoadingWait()=runTest {
        val f=Fixture(this,custom={transitionWait().copy(relevantNodeIds=listOf(2))})
        f.begin();f.frame(tick="숨긴 내용 A")
        f.screens.value=f.screens.value.let{s->val ns=s.snapshot!!.allNodes.map{if(it.id==3)it.copy(sensitiveContent=true)else it};s.copy(snapshot=s.snapshot.copy(nodes=ns,allNodes=ns))}
        settle();f.frame(tick="숨긴 내용 B")
        f.screens.value=f.screens.value.let{s->val ns=s.snapshot!!.allNodes.map{if(it.id==3)it.copy(sensitiveContent=true)else it};s.copy(snapshot=s.snapshot.copy(nodes=ns,allNodes=ns))}
        settle();assertEquals(1,f.calls);f.engine.stop()
    }
    @Test fun activityWaitStillIgnoresAContinuouslyChangingRelevantTimer()=runTest {
        val f=Fixture(this,custom={transitionWait().copy(waitKind=WaitKind.USER_ACTIVITY)})
        f.begin();f.frame(tick="00:01");settle();repeat(6){f.frame(tick="00:0${it+2}");settle()}
        assertEquals(1,f.calls);f.engine.stop()
    }
    @Test fun anotherRelevantStatusChangeInvalidatesAPlanStillArrivingForThePreviousStatus()=runTest {
        val late=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->when(call){1->transitionWait();2->withContext(NonCancellable){late.await()};else->guide()}})
        f.begin();f.frame(tick="검색 중");settle();f.frame(tick="검색 결과 없음");settle();assertEquals(2,f.calls)
        f.frame(tick="새 결과가 준비됨");settle()
        assertEquals("The related status must remain observed while its replan is in flight",3,f.calls)
        late.complete(guide().copy(message="이전 상태의 늦은 안내"));settle()
        assertNotEquals("이전 상태의 늦은 안내",f.engine.ui.message);f.engine.stop()
    }
    @Test fun actionBarMovementInvalidatesAnInFlightPlanEvenWhenThePublicNodesMatch() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) withContext(NonCancellable) { late.await() }
            else guide().copy(operation = Operation.TAP, targetNodeId = 2, message = "다음 항목을 눌러주세요.")
        })
        f.begin()
        f.frame()
        settle()
        assertEquals(1, f.calls)

        val state = f.screens.value
        val frame = requireNotNull(state.snapshot)
        f.screens.value = state.copy(snapshot = frame.copy(snapshotId = frame.snapshotId + 1,
            platformActionBarBounds = NodeBounds(0, 0, 500, 170)))
        settle()
        late.complete(guide())
        settle()

        assertEquals(2, f.calls)
        assertEquals(listOf(2), f.highlights.map { it.id })
        assertEquals("다음 항목을 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }
    @Test fun partialLoadingReadsDoNotConsumeARelevantStatusChange()=runTest {
        val f=Fixture(this,custom={if(it==1)transitionWait()else guide()})
        f.begin();f.frame(tick="검색 중");settle();f.frame(tick="검색 결과 없음")
        f.screens.value=f.screens.value.let{it.copy(snapshot=it.snapshot!!.copy(truncated=true))};settle();assertEquals(1,f.calls)
        f.frame(tick="검색 결과 없음");settle();assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun repeatedPartialCapturesHaveOneRetryAndEndInManualRecovery() = runTest {
        val f = Fixture(this)
        f.begin()
        f.frame(partial = true)
        runCurrent()
        val refreshesAtFirstPartial = f.refreshes
        repeat(8) {
            f.frame(partial = true)
            advanceTimeBy(300)
            runCurrent()
        }
        assertEquals(0, f.calls)
        assertEquals(refreshesAtFirstPartial + 1, f.refreshes)
        assertEquals("error", f.engine.ui.phase)
        f.frame()
        settle()
        assertEquals(0, f.calls)
        assertTrue(f.engine.retryFromCurrent())
        settle()
        assertEquals(1, f.calls)
        f.engine.stop()
    }

    @Test fun partialCaptureRevokesOldControlAndIdenticalCompleteFrameRestoresGuidance() = runTest {
        val f = Fixture(this, custom = { transitionWait().copy(waitKind = WaitKind.USER_INPUT) })
        f.begin()
        f.frame()
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        f.frame(partial = true)
        assertFalse(f.engine.confirmControl())
        runCurrent()
        assertNull(f.engine.ui.currentAction)
        assertFalse(f.engine.confirmControl())
        f.frame()
        settle()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, f.calls)
        assertEquals("wait", f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun anIncompleteReadCannotPauseAfterLeavingTheTargetApp() = runTest {
        val f = Fixture(this)
        f.begin()
        f.frame(partial = true)
        runCurrent()
        val refreshesAtFirstPartial = f.refreshes
        f.screens.value = f.screens.value.copy(foregroundPackage = "other.app")
        f.engine.onForegroundChanged("other.app")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(refreshesAtFirstPartial, f.refreshes)
        assertEquals("waiting_screen", f.engine.ui.phase)
        assertEquals(0, f.calls)
        f.engine.stop()
    }

    @Test fun aCommitClickedDuringAnIncompleteReadRemainsUncertainAndCannotBeRepeated() = runTest {
        val f = Fixture(this, custom = { commit() })
        f.begin()
        f.frame()
        settle()
        assertEquals(1, f.highlights.size)
        f.frame(partial = true)
        runCurrent()
        f.engine.onUserAction(PKG, evidence = f.click(2))
        f.frame()
        settle()
        val receipt = f.lastGoal!!.operationHistory.single()
        assertFalse(receipt.interactionVerified)
        assertEquals(CommitOutcome.UNKNOWN, receipt.state)
        assertEquals(1, f.highlights.size)
        assertEquals("wait", f.engine.ui.currentAction)
        f.engine.stop()
    }
    private fun Fixture.editedInput(){
        nativeInputVersion++;engine.onInputChanged(PKG,5);frame(input=true,editRevision=2)
    }
    @Test fun aNativeAppControlAfterTextMutationRequestsFreshPlanningWithoutFinishingWriting()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle()
        assertEquals(1,f.calls);f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(input=true,editRevision=2);settle()
        assertEquals("A real app click must still wake planning after the old INPUT display was cleared",2,f.calls)
        assertFalse(f.lastGoal!!.inputProgress.single().userFinished);assertEquals(2,f.highlights.last().id);f.engine.stop()
    }
    @Test fun aConsumedInputClickCannotReplanTheNextInstructionAgain()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle()
        val click=f.click(2);f.engine.onUserAction(PKG,evidence=click);f.frame(input=true,editRevision=2);settle();assertEquals(2,f.calls)
        f.engine.onUserAction(PKG,evidence=click.copy(receivedUptimeMillis=testScheduler.currentTime));f.frame(input=true,editRevision=2);settle()
        assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun aPublicControlCanRequestReobservationWhileTheNewInputCaptureIsStillPending()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle()
        f.nativeInputVersion++;f.engine.onInputChanged(PKG,5)
        f.engine.onUserAction(PKG,evidence=f.click(2))
        f.frame(input=true,editRevision=2);settle()
        assertEquals(2,f.calls);assertFalse(f.lastGoal!!.inputProgress.single().userFinished);f.engine.stop()
    }
    @Test fun aMissingPostActionCaptureHasOneBoundedPauseAndCanResumeFromAFreshRead()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));advanceTimeBy(2101);runCurrent()
        assertEquals("paused",f.engine.ui.phase);assertEquals(1,f.calls)
        repeat(4){f.frame(input=true,editRevision=2);settle()};assertEquals(1,f.calls)
        assertTrue(f.engine.retryFromCurrent());settle();assertEquals(2,f.calls)
        advanceTimeBy(2101);runCurrent();assertNotEquals("paused",f.engine.ui.phase);f.engine.stop()
    }
    @Test fun partialOrUntimedReadsCannotConsumeThePostInputActionBoundary()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle();f.engine.onUserAction(PKG,evidence=f.click(2))
        for(kind in 0..2){
            f.frame(input=true,editRevision=2)
            f.screens.value=f.screens.value.let{val s=it.snapshot!!;it.copy(snapshot=when(kind){
                0->s.copy(truncated=true);1->s.copy(viewport=null);else->s.copy(captureStartedAtUptimeMillis=null)})}
            settle();assertEquals(1,f.calls);assertEquals("waiting_screen",f.engine.ui.phase)
        }
        f.frame(input=true,editRevision=2);settle();assertEquals(2,f.calls)
        advanceTimeBy(2101);runCurrent();assertNotEquals("paused",f.engine.ui.phase);f.engine.stop()
    }
    @Test fun anOldPostInputReadDeadlineCannotPauseANewSession()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle();f.engine.onUserAction(PKG,evidence=f.click(2))
        assertTrue(f.engine.start("새 작업을 안내해줘",PKG,"공개 시험 앱"));settle()
        advanceTimeBy(2101);runCurrent();assertNotEquals("paused",f.engine.ui.phase)
        assertEquals("새 작업을 안내해줘",f.engine.ui.goal);f.engine.stop()
    }
    @Test fun aNativeClickAfterEditingStillCannotCertifyACommit()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame();settle();f.editedInput();settle();f.engine.onUserAction(PKG,evidence=f.click(4));f.frame(input=true,editRevision=2);settle()
        assertEquals(2,f.calls);assertFalse(f.lastGoal!!.inputProgress.single().userFinished)
        assertTrue(f.highlights.all{it.id==1});assertEquals("입력했어요",f.engine.ui.controlCompletionLabel);f.engine.stop()
    }
    @Test fun anInputControlRecheckWaitsForATreeReadStartedAfterTheClick()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle()
        val click=f.click(2);f.engine.onUserAction(PKG,evidence=click)
        f.frame(input=true,editRevision=2)
        f.screens.value=f.screens.value.let{it.copy(snapshot=it.snapshot!!.copy(captureStartedAtUptimeMillis=click.eventUptimeMillis!!-1))}
        settle();assertEquals(1,f.calls)
        f.frame(input=true,editRevision=2);settle();assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun untrustedOrEditableEventsAfterMutationDoNotReleaseTheInputHold()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.editedInput();settle()
        val click=f.click(2)
        for(event in listOf(f.click(1),click.copy(eventType=android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED),
            click.copy(sourceEpoch=999),click.copy(windowId=999),click.copy(packageName="public.other.fixture"),
            click.copy(eventUptimeMillis=0))){
            f.engine.onUserAction(PKG,evidence=event);f.frame(input=true,editRevision=2);settle()
            assertEquals(1,f.calls)
        }
        f.engine.onScreenUnavailable("시험 중단");f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(input=true,editRevision=2);settle()
        assertEquals(1,f.calls);assertEquals("paused",f.engine.ui.phase);f.engine.stop()
    }
    private fun presencePlan(first:Boolean,desired:Boolean):TaskPlan {
        val check=ValueExpectation(ValueProperty.INPUT_PRESENT,ValueSubject("android.widget.EditText","public:id/item1",""),expectedBoolean=desired)
        return (if(first)guide()else commit()).copy(conditions=listOf(GoalCondition("prepared","입력 유무 조건",true,
            if(first)Truth.UNSATISFIED else Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(1),phase=ConditionPhase.PREPARATION,expectations=listOf(check))),
            actionGates=listOf(ActionGate(2,listOf("prepared"))))
    }
    @Test fun declaringBlankCannotSatisfyAnOwnedNonblankInputRequirement()=runTest {
        val f=Fixture(this,custom={presencePlan(it==1,true)})
        f.begin();f.frame(input=false);settle();assertTrue(f.engine.confirmControl());settle();advanceTimeBy(401);runCurrent()
        assertTrue(f.lastGoal!!.inputProgress.single().userFinished)
        assertEquals(true,f.lastGoal!!.requirements.single().expectations.single().expectedBoolean)
        assertTrue(f.highlights.all{it.id==1});assertEquals("error",f.engine.ui.phase);assertEquals(3,f.calls);f.engine.stop()
    }
    @Test fun matchingNonblankInputStillNeedsItsOwnWritingDeclaration()=runTest {
        val f=Fixture(this,custom={presencePlan(it==1,true)})
        f.begin();f.frame(input=true);settle();f.engine.onUserAction(PKG,evidence=f.click(4));f.frame(input=true);settle()
        assertFalse(f.lastGoal!!.inputProgress.single().userFinished);assertTrue(f.highlights.all{it.id==1})
        assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.highlights.last().id);f.engine.stop()
    }
    @Test fun aDesiredBlankStateAndAnExplicitBlankDeclarationCanBothBeVerified()=runTest {
        val f=Fixture(this,custom={presencePlan(it==1,false)})
        f.begin();f.frame(input=false);settle();assertTrue(f.engine.confirmControl());settle()
        assertTrue(f.lastGoal!!.inputProgress.single().userFinished);assertEquals(2,f.highlights.last().id)
        assertEquals(false,f.lastGoal!!.requirements.single().expectations.single().expectedBoolean);f.engine.stop()
    }
    @Test fun emptyWritingOffersAnExplicitBlankDeclarationInsteadOfADeadButton()=runTest {
        val f=Fixture(this);f.begin();f.frame(input=false);settle()
        assertEquals("비워두었어요",f.engine.ui.controlCompletionLabel);assertTrue(f.engine.ui.controlCompletionEnabled)
        assertEquals(1,f.calls);assertTrue(f.engine.confirmControl());settle()
        assertEquals(2,f.calls);assertTrue(f.lastGoal!!.inputProgress.single().userFinished);f.engine.stop()
    }
    @Test fun clearingADeclaredValueRemovesCommitGuidanceAndRequiresTheBlankDeclaration()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.highlights.last().id)
        f.frame(input=false,editRevision=2);settle()
        assertEquals(1,f.highlights.last().id);assertEquals("비워두었어요",f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.ui.controlCompletionEnabled);assertEquals(2,f.calls)
        assertTrue(f.engine.confirmControl());settle()
        assertEquals(2L,f.lastGoal!!.inputProgress.single().receiptId);assertEquals(2,f.highlights.last().id);f.engine.stop()
    }
    @Test fun aTransientOwnQuestionObservationKeepsTheWindowButDoesNotAcceptAnAnswer()=runTest {
        val f=Fixture(this,ask=true);f.begin();f.frame();settle()
        val question=f.engine.question()!!
        assertTrue(f.engine.deferOwnQuestionObservation());assertTrue(f.engine.ui.showsScopedQuestion)
        assertFalse(f.engine.answer("desired","관찰 중 답변"));assertEquals(1,f.calls)
        f.engine.onForegroundChanged(PKG);assertFalse(f.engine.expireQuestionObservation(question))
        assertTrue(f.engine.answer("desired","새 관찰 후 답변"));settle()
        assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun anUnresolvedQuestionObservationTimeoutPausesWithoutRepeatingPlans()=runTest {
        val f=Fixture(this,ask=true);f.begin();f.frame();settle();val question=f.engine.question()!!
        assertTrue(f.engine.deferOwnQuestionObservation());assertTrue(f.engine.expireQuestionObservation(question))
        repeat(8){f.frame();settle()}
        assertEquals(1,f.calls);assertEquals("paused",f.engine.ui.phase);assertFalse(f.engine.answer("desired","늦은 답변"));f.engine.stop()
    }
    @Test fun anAnswerAfterFinishedWritingReplansWithoutPausingOnTheExpiredDeclaration()=runTest {
        val f=Fixture(this,custom={call->if(call==2)guide().copy(kind=NextKind.ASK,operation=Operation.NONE,targetNodeId=null,
            question=PlanQuestion("desired","어떤 방식으로 안내할까요?",true,true,false))else guide()})
        f.begin();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle()
        assertEquals("ask",f.engine.ui.currentAction);assertTrue(f.engine.answer("desired","천천히"));settle()
        assertEquals("A current answer must reach planning even though old writing authority expires",3,f.calls)
        assertEquals("guiding",f.engine.ui.phase);assertFalse(f.lastGoal!!.inputProgress.single().userFinished);f.engine.stop()
    }
    @Test fun aPausedQuestionRejectsItsLateAnswerAndClosesItsPresentation()=runTest {
        val f=Fixture(this,ask=true);f.begin();f.frame();settle();f.engine.onScreenUnavailable("화면 잠김")
        assertFalse("A late voice/text answer must not revise a paused goal",f.engine.answer("desired","늦은 답변"))
        assertNull(f.engine.ui.currentAction);f.engine.stop()
    }
    @Test fun leavingTheAskedScreenImmediatelyRejectsEvenAnAnswerBeforeTheNextCapture()=runTest {
        val f=Fixture(this,ask=true);f.begin();f.frame();settle();f.engine.onForegroundChanged("public.other.fixture")
        assertFalse("The displayed question was invalidated before the screen flow updated",f.engine.answer("desired","늦은 답변"))
        assertNull(f.engine.ui.currentAction);f.engine.stop()
    }
    @Test fun aQueuedInputCompletionAfterPauseDoesNotAdvance()=runTest {
        val f=Fixture(this);f.begin();f.frame(input=true);settle();f.engine.onScreenUnavailable("화면 잠김")
        assertFalse(f.engine.confirmControl());assertEquals(1,f.calls);f.engine.stop()
    }
    @Test fun focusingAnEditorWithoutChangingItsValueDoesNotReplanACommit()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true,focused=false,buttonFocused=true);settle();assertTrue(f.engine.confirmControl());settle()
        assertEquals(2,f.calls)
        f.frame(input=true,focused=true,buttonFocused=false);settle()
        assertEquals("Input focus alone is not new task progress",2,f.calls);f.engine.stop()
    }
    @Test fun aNativeAppButtonDoesNotCertifyThatFreeInputIsFinished()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true);settle()
        f.engine.onUserAction(PKG,evidence=f.click(4));f.frame(input=true);settle()
        assertFalse("An unrelated native button is not a scoped declaration of finished input",f.highlights.any{it.id==2})
        f.engine.stop()
    }
    @Test fun editingAfterFinishingRevokesDisplayedCommitAndRequiresAFreshDeclaration()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true);settle();assertEquals("입력했어요",f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.highlights.last().id)
        assertTrue(f.lastGoal!!.inputProgress.single().userFinished)
        f.frame(input=true,editRevision=2);settle()
        assertEquals(1,f.highlights.last().id);assertEquals(2,f.calls)
        assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.highlights.last().id)
        assertEquals(2L,f.lastGoal!!.inputProgress.single().receiptId);f.engine.stop()
    }
    @Test fun anInputEventBeforeTheFreshCaptureCannotCertifyTheOldText()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle()
        f.nativeInputVersion++;f.engine.onInputChanged(PKG,5)
        assertFalse(f.engine.confirmControl());assertEquals("waiting_screen",f.engine.ui.phase)
        f.frame(input=true,editRevision=2);settle();assertEquals(1,f.highlights.last().id)
        assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.highlights.last().id);f.engine.stop()
    }
    @Test fun aLateCommitPlanCannotReuseADeclarationFromBeforeAnEdit()=runTest {
        val result=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==1)guide()else withContext(NonCancellable){result.await()}})
        f.begin();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.calls)
        f.frame(input=true,editRevision=2);settle();result.complete(commit());settle()
        assertTrue(f.highlights.all{it.id==1});assertEquals("입력했어요",f.engine.ui.controlCompletionLabel);f.engine.stop()
    }
    @Test fun changingTheRecipientScopeRevokesFinishedInput()=runTest {
        val f=Fixture(this,custom={if(it==1)guide().copy(scopeNodeIds=listOf(3))else commit()})
        f.begin();f.frame(input=true,tick="받는 대상 A");settle();assertTrue(f.engine.confirmControl());settle()
        f.frame(input=true,tick="받는 대상 B");settle()
        assertEquals("error",f.engine.ui.phase);assertFalse(f.engine.confirmControl());f.engine.stop()
    }
    @Test fun leavingTheAppInvalidatesAnInputDeclarationEvenIfTheTextLooksUnchanged()=runTest {
        val f=Fixture(this,custom={if(it==1)guide()else commit()})
        f.begin();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle()
        f.engine.onForegroundChanged("public.other.fixture");f.frame(input=true);settle()
        assertEquals(1,f.highlights.last().id);assertEquals("입력했어요",f.engine.ui.controlCompletionLabel);f.engine.stop()
    }
    @Test fun oneInvalidReferencePlanIsRepairedBeforeAskingTheUserToRetry()=runTest {
        val f=Fixture(this,custom={call->if(call==1)guide().copy(relations=listOf(TaskRelation(RelationKind.ALL,listOf("not_a_task"))))else guide()})
        f.begin();f.frame();settle()
        assertEquals("A rejected read-only plan gets one diagnosed correction",2,f.calls)
        assertEquals(listOf(0,1),f.attempts.map{it.index});assertSame(f.attempts[0].ticket,f.attempts[1].ticket)
        assertSame(f.goals[0],f.goals[1]);assertEquals("unknown_relation_member",f.attempts[1].diagnostic!!.reason)
        assertEquals("guiding",f.engine.ui.phase);assertEquals(1,f.highlights.size);f.engine.stop()
    }
    @Test fun aTapOnAReadOnlyNodeIsRepairedBeforeTheUserSeesAnInstruction() = runTest {
        val f = Fixture(this, custom = { attempt ->
            val id = if (attempt == 1) 3 else 2
            guide().copy(operation = Operation.TAP, targetNodeId = id, relevantNodeIds = listOf(id),
                message = if (id == 3) "장식 그림을 눌러주세요." else "다음 항목을 눌러주세요.")
        })
        f.begin()
        f.frame(tick = "장식 그림")
        settle()

        assertEquals(listOf(0, 1), f.attempts.map { it.index })
        assertSame(f.attempts[0].ticket, f.attempts[1].ticket)
        assertEquals("tap_target_not_clickable", f.attempts[1].diagnostic?.reason)
        assertEquals(listOf(2), f.highlights.map { it.id })
        assertEquals("다음 항목을 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }
    @Test fun aNamedCardScopesAnOtherwiseDuplicatedOpenButton() = runTest {
        val f = Fixture(this, custom = { openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.cards()
        settle()

        assertEquals(listOf(4), f.highlights.map { it.id })
        assertEquals("highlight", f.engine.ui.currentAction)
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.cards(firstTitle = "새 자료")
        settle()
        assertEquals(listOf(4), f.highlights.map { it.id })
        assertNull(f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aDifferentCardOrANegatedTitleCannotDisambiguateAnOpenButton() = runTest {
        for ((request, plan) in listOf(
            "회의 자료를 열어줘" to openCard(7, 3),
            "회의 자료를 열어줘" to openCard(4, 6),
            "회의 자료랑 여행 사진을 열어줘" to openCard(4, 3),
            "회의 자료 말고 다른 자료를 열어줘" to openCard(4, 3),
            "회의 자료집을 열어줘" to openCard(4, 3),
        )) {
            val f = Fixture(this, custom = { plan })
            f.begin(request)
            f.cards()
            settle()
            assertTrue(request, f.highlights.isEmpty())
            f.engine.stop()
        }
    }

    @Test fun aNestedCardCanScopeATapUntilAnInterveningBranchBecomesScrollable() = runTest {
        val f = Fixture(this, custom = { openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        assertEquals(listOf(4), f.highlights.map { it.id })

        f.nestedCards(scrollableFooter = true)
        settle()
        assertEquals(listOf(4), f.highlights.map { it.id })
        assertNull(f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aTitleOutsideTheNestedCardCannotScopeEitherRepeatedButton() = runTest {
        val f = Fixture(this, custom = { openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(detachedTitle = true)
        settle()
        assertTrue(f.highlights.isEmpty())
        assertEquals(1, f.calls)
        f.engine.stop()
    }

    @Test fun aWrongRepeatedButtonCanBeCorrectedOnceWithoutAskingForTheKnownTitle() = runTest {
        for (wrong in listOf(openCard(7, 6), openCard(4, 6), openCard(4, 3).copy(scopeNodeIds = emptyList()))) {
            val f = Fixture(this, custom = { call -> if (call == 1) wrong else openCard(4, 3) })
            f.begin("회의 자료를 열어줘")
            f.nestedCards()
            settle()

            assertEquals(listOf(0, 1), f.attempts.map { it.index })
            assertSame(f.attempts[0].ticket, f.attempts[1].ticket)
            assertSame(f.goals[0], f.goals[1])
            assertEquals("scoped_navigation_mismatch", f.attempts[1].diagnostic?.reason)
            assertEquals(listOf(4), f.highlights.map { it.id })
            assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
            f.engine.stop()
        }
    }

    @Test fun aNamedTitleComingIntoViewStartsFreshPlanningInsteadOfRepairingTheClippedFrame() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) withContext(NonCancellable) { late.await() } else openCard(4, 3)
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(horizontalOffset = 25)
        settle()
        f.nestedCards()
        settle()
        assertEquals(1, f.calls)
        assertTrue(f.highlights.isEmpty())
        late.complete(openCard(7, 6))
        settle()

        assertEquals(listOf(0, 0), f.attempts.map { it.index })
        assertNotEquals(f.attempts[0].ticket.requestId, f.attempts[1].ticket.requestId)
        assertEquals(f.attempts[0].ticket.goalRevision, f.attempts[1].ticket.goalRevision)
        assertEquals(listOf(4), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun aClippedTitleMovingIntoADifferentGroupDoesNotAuthorizeTheAutomaticReplan() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { _, _ -> withContext(NonCancellable) { late.await() } })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(detachedTitle = true, horizontalOffset = 25)
        settle()
        f.nestedCards()
        late.complete(openCard(7, 6))
        settle()

        assertEquals(1, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertEquals("error", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun theNamedButtonsNoticeCanRestartPlanningBeforeItsOldRepairReturns() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            when (call) {
                1 -> openCard(7, 6)
                2 -> withContext(NonCancellable) { late.await() }
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        assertEquals(listOf(0, 1), f.attempts.map { it.index })

        f.nestedCards(firstNotice = "현재 문서를 열 수 있어요.")
        settle()
        assertEquals(listOf(0, 1, 0), f.attempts.map { it.index })
        assertNotEquals(f.attempts[1].ticket.requestId, f.attempts[2].ticket.requestId)
        assertEquals(f.attempts[1].ticket.goalRevision, f.attempts[2].ticket.goalRevision)
        assertEquals(listOf(4), f.highlights.map { it.id })

        late.complete(openCard(7, 6))
        settle()
        assertEquals(listOf(4), f.highlights.map { it.id })
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }

    @Test fun anotherRepeatedButtonsNoticeDoesNotCancelAnAlreadyScopedRepair() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) openCard(7, 6) else withContext(NonCancellable) { late.await() }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.nestedCards(secondNotice = "사진을 불러왔어요.")
        settle()
        late.complete(openCard(4, 3))
        settle()

        assertEquals(listOf(0, 1), f.attempts.map { it.index })
        assertSame(f.attempts[0].ticket, f.attempts[1].ticket)
        assertEquals(listOf(4), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun aRepairStillCannotUseTheChangedPeerAsEvidence() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) openCard(7, 6) else withContext(NonCancellable) { late.await() }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.nestedCards(secondNotice = "사진을 불러왔어요.")
        settle()
        assertTrue(f.engine.ui.busy)
        late.complete(openCard(4, 3).copy(relevantNodeIds = listOf(4, 7)))
        settle()

        assertEquals(2, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertEquals("error", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aScopeRepairCannotReplaceTheNamedActionWithAnUnrelatedUniqueButton() = runTest {
        val f = Fixture(this, custom = { call -> if (call == 1) openCard(7, 6) else openCard(8, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(unrelatedAction = true)
        settle()

        assertEquals(2, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertNull(f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aCorrectedScopeCannotBeAppliedAfterTheGroupStopsContainingItsControls() = runTest {
        val delayed = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) openCard(7, 6) else withContext(NonCancellable) { delayed.await() }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        assertEquals(2, f.calls)

        val state = f.screens.value
        val frame = requireNotNull(state.snapshot)
        val nodes = frame.allNodes.map { if (it.id == 2) it.copy(bounds = NodeBounds(0, 100, 500, 145)) else it }
        val moved = frame.copy(snapshotId = frame.snapshotId + 1, nodes = nodes, allNodes = nodes)
        assertTrue(V3RepairPolicy.sameObservation(frame, moved))
        f.screens.value = state.copy(snapshot = moved)
        delayed.complete(openCard(4, 3))
        settle()

        assertEquals(2, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertNull(f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun theNamedDisabledButtonWaitsWithoutSubstitutingAnEnabledButtonInAnotherCard() = runTest {
        val f = Fixture(this, custom = { call -> if (call == 1) openCard(7, 6) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(firstButtonEnabled = false)
        settle()

        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("회의 자료"))
        assertTrue(f.highlights.isEmpty())
        assertEquals(1, f.calls)

        f.nestedCards(firstButtonEnabled = false, unrelatedAction = true)
        settle()
        assertEquals(1, f.calls)
        assertEquals("wait", f.engine.ui.currentAction)

        f.nestedCards(unrelatedAction = true)
        settle()
        assertEquals(listOf(0, 0), f.attempts.map { it.index })
        assertEquals(listOf(4), f.highlights.map { it.id })
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }

    @Test fun aDisabledButtonInAnotherCardDoesNotBlockTheNamedEnabledButton() = runTest {
        val f = Fixture(this, custom = { openCard(7, 6) })
        f.begin("여행 사진을 열어줘")
        f.nestedCards(firstButtonEnabled = false)
        settle()

        assertEquals(1, f.calls)
        assertEquals(listOf(7), f.highlights.map { it.id })
        assertEquals("‘여행 사진’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }

    @Test fun preparationForANamedDisabledButtonCannotHighlightAnEditorInAnotherCard() = runTest {
        val f = Fixture(this, custom = { prepareCard(9) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()

        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("문서 이름을 입력해야 열 수 있어요"))
        assertTrue(f.highlights.isEmpty())
        assertEquals(1, f.calls)
        f.engine.stop()
    }

    @Test fun anObservedEmptyEditorInTheNamedCardCanBeGuidedWithoutWaitingForItsOpenButton() = runTest {
        val f = Fixture(this, custom = { prepareCard(8) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()

        assertEquals(listOf(8), f.highlights.map { it.id })
        assertTrue(f.engine.ui.awaitingControlCompletion)
        assertEquals(1, f.calls)
        f.engine.stop()
    }

    @Test fun anExplicitListOfRequiredEditorsRepairsAnOmittedFieldBeforeShowingOne() = runTest {
        val f = Fixture(this, custom = { if (it == 1) prepareCard(8) else prepareBoth(8, firstPresent = false) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(notice = bothRequiredNotice, secondEditor = true)
        settle()

        assertEquals(listOf(0, 1), f.attempts.map { it.index })
        assertEquals("missing_scoped_required_inputs", f.attempts.last().diagnostic?.reason)
        assertSame(f.attempts.first().ticket, f.attempts.last().ticket)
        assertEquals(listOf(8), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun anAlreadyFilledRequiredEditorDoesNotNeedToBeTypedAgain() = runTest {
        val f = Fixture(this, custom = { prepareBoth(10, firstPresent = true) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(input = true, notice = bothRequiredNotice, secondEditor = true)
        settle()

        assertEquals(1, f.calls)
        assertEquals(listOf(10), f.highlights.map { it.id })
        assertFalse(f.engine.ui.controlCompletionEnabled)
        f.engine.stop()
    }

    @Test fun anUnobservedRequiredEditorKeepsTheLocalWait() = runTest {
        val f = Fixture(this, custom = { prepareCard(8) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(notice = bothRequiredNotice)
        settle()

        assertEquals(1, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains(bothRequiredNotice))
        f.engine.stop()
    }

    @Test fun anIncompleteCorrectionCannotReplaceTheNamedInputWithAnUnrelatedAction() = runTest {
        val f = Fixture(this, custom = { if (it == 1) prepareCard(8) else openCard(7, 6) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(notice = bothRequiredNotice, secondEditor = true)
        settle()

        assertEquals(listOf(0, 1), f.attempts.map { it.index })
        assertTrue(f.highlights.isEmpty())
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("회의 자료"))
        f.engine.stop()
    }

    @Test fun twoRequiredEditorsProgressFromExplicitDeclarationsToNamedNavigation() = runTest {
        val f = Fixture(this, custom = { call -> when (call) {
            1 -> prepareBoth(8, firstPresent = false)
            2 -> prepareBoth(10, firstPresent = true)
            else -> openPreparedCard()
        } })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(notice = bothRequiredNotice, secondEditor = true)
        settle()
        assertEquals(listOf(8), f.highlights.map { it.id })

        f.scopedForm(input = true, notice = bothRequiredNotice, secondEditor = true)
        settle()
        assertEquals(1, f.calls)
        assertTrue(f.engine.confirmControl())
        settle()
        assertEquals(10, f.highlights.last().id)
        assertTrue(f.lastGoal!!.inputProgress.single().userFinished)

        f.scopedForm(input = true, notice = null, buttonEnabled = true, secondEditor = true, secondInput = true)
        settle()
        assertEquals(2, f.calls)
        assertTrue(f.engine.confirmControl())
        settle()
        assertEquals(listOf(8, 10, 4), f.highlights.map { it.id }.distinct())
        assertEquals(listOf(8, 10), f.lastGoal!!.inputProgress.filter { it.userFinished }.map { it.nodeId })
        assertEquals("guiding", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun interruptedSecondEditorMustBeFinishedBeforeTheEnabledNamedButtonIsGuided() = runTest {
        val f = Fixture(this, custom = { call -> when (call) {
            1 -> prepareBoth(8, firstPresent = false)
            2 -> prepareBoth(10, firstPresent = true)
            else -> openPreparedCard()
        } })
        f.begin("회의 자료를 열어줘")
        f.scopedForm(notice = bothRequiredNotice, secondEditor = true)
        settle()
        f.scopedForm(input = true, notice = bothRequiredNotice, secondEditor = true)
        settle()
        assertTrue(f.engine.confirmControl())
        settle()
        assertEquals(10, f.highlights.last().id)

        f.scopedForm(input = true, notice = bothRequiredNotice, secondEditor = true, secondInput = true)
        settle()
        f.scopedForm(notice = bothRequiredNotice, secondEditor = true, secondInput = true)
        settle()
        assertEquals(8, f.highlights.last().id)
        assertFalse(f.engine.confirmControl())

        f.scopedForm(input = true, notice = null, buttonEnabled = true, secondEditor = true, secondInput = true)
        settle()
        assertTrue(f.engine.confirmControl())
        settle()
        assertEquals(3, f.calls)
        assertEquals(10, f.highlights.last().id)
        assertTrue(f.highlights.none { it.id == 4 })
        assertEquals(listOf(8), f.lastGoal!!.inputProgress.filter { it.userFinished }.map { it.nodeId })
        assertEquals("입력했어요", f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl())
        settle()
        assertEquals(4, f.highlights.last().id)
        assertEquals(listOf(8, 10), f.lastGoal!!.inputProgress.filter { it.userFinished }.map { it.nodeId })
        f.engine.stop()
    }

    @Test fun namedNavigationWaitsThroughLoadingAndWrongTitleUntilTheRequestedHeadingAppears() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("열고 있어요", heading = false)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertEquals(2, f.calls)

        f.openedScreen("여행 사진")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertNotEquals("done", f.engine.ui.phase)

        f.openedScreen("회의 자료")
        settle()
        assertEquals("done", f.engine.ui.phase)
        assertTrue(f.calls in 3..4)
        f.engine.stop()
    }

    @Test fun aVerifiedWrongCardCanGuideTheNamedReturnToTheOriginalList() = runTest {
        val f = Fixture(this, custom = { if (it == 1 || it >= 3) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()

        assertEquals("highlight", f.engine.ui.currentAction)
        assertEquals(listOf(4, 11), f.highlights.map { it.id })
        assertTrue(f.engine.ui.message.contains("자료 목록으로 돌아가기"))
        assertFalse(f.engine.ui.awaitingControlCompletion)
        f.engine.onUserAction(PKG, evidence = f.click(11))
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }

    @Test fun theSameWrongRouteCanReturnButCannotBeOfferedForAThirdTime() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4 -> completedOpen(2)
                6 -> openCard(4, 3).copy(scopeNodeIds = emptyList(), message = "항목을 다시 눌러주세요.")
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        repeat(2) { round ->
            assertEquals(4, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            assertEquals(11, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", firstNotice = if (round == 1) "목록이 갱신됐어요." else null)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()

        assertNull(f.currentHighlight)
        assertEquals(listOf(4, 11, 4, 11), f.highlights.map { it.id })
        assertEquals(6, f.calls)
        assertEquals("repeated_wrong_navigation", f.attempts.last().diagnostic?.reason)
        assertTrue(f.engine.ui.message.contains("다른 방법"))
        f.engine.stop()
    }

    @Test fun repeatedWrongNavigationCanRepairToADifferentObservedControl() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4 -> completedOpen(2)
                6 -> openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "검색을 눌러주세요.")
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            assertEquals(11, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(8, f.currentHighlight)
        assertEquals("repeated_wrong_navigation", f.attempts.last().diagnostic?.reason)
        assertEquals("회의 자료를 열어줘", f.lastGoal?.request)
        f.engine.stop()
    }

    @Test fun recoverySearchWaitsForTheNamedSingleResultAndVerifiesItAfterOpening() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4, 10, 11 -> completedOpen(2)
                6 -> openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "검색을 눌러주세요.")
                7 -> completedOpen(2).copy(kind = NextKind.WAIT, conditions = emptyList(), waitKind = WaitKind.SCREEN_TRANSITION,
                    message = "검색 결과를 기다리고 있어요.")
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(8, f.currentHighlight)
        f.engine.onUserAction(PKG, evidence = f.click(8))
        f.openedScreen("검색 중", heading = false)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertNull(f.currentHighlight)

        f.singleSearchResult("여행 사진")
        settle()
        assertNull(f.currentHighlight)
        assertTrue(f.engine.ui.message.contains("해당하는 결과"))
        assertTrue(f.engine.confirmControl())
        f.singleSearchResult("회의 자료")
        settle()
        assertEquals(4, f.currentHighlight)
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        f.openedScreen("", heading = false, paneTitle = "회의 자료")
        settle()
        assertEquals("done", f.engine.ui.phase)
        assertEquals(11, f.calls)
        f.engine.stop()
    }

    @Test fun duplicateNamedRowsWaitAndTheUniqueNamedRowNeedsAnObservedClickToFinish() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4, 10 -> completedOpen(2)
                6 -> openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "검색을 눌러주세요.")
                7 -> openCard(4, 4).copy(kind = NextKind.RECOVER, operation = Operation.NONE, targetNodeId = null,
                    tasks = emptyList(), relevantNodeIds = emptyList(), scopeNodeIds = emptyList())
                9 -> openCard(4, 4).copy(scopeNodeIds = emptyList())
                8 -> openCard(7, 7).copy(scopeNodeIds = emptyList())
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(8, f.currentHighlight)
        f.engine.onUserAction(PKG, evidence = f.click(8))
        f.namedSearchRows("회의 자료", "회의 자료")
        settle()
        assertNull(f.currentHighlight)
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("여러 개"))

        f.namedSearchRows("회의 자료", "여행 사진")
        settle()
        advanceTimeBy(800)
        runCurrent()
        assertEquals("scoped_navigation_mismatch", f.attempts.last().diagnostic?.reason)
        assertEquals(4, f.currentHighlight)
        assertEquals("‘회의 자료’를 눌러주세요.", f.engine.ui.message)
        assertTrue(f.highlights.none { it.id == 7 })
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "회의 자료")
        settle()
        assertEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aRequestedDateDisambiguatesRowsAndMustAlsoMatchTheOpenedPane() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4, 11, 12 -> completedOpen(2)
                6 -> openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "검색을 눌러주세요.")
                7, 8, 9 -> openCard(4, 4).copy(scopeNodeIds = emptyList())
                10 -> openCard(7, 7).copy(scopeNodeIds = emptyList())
                else -> openCard(4, 3)
            }
        })
        f.begin("2026년 회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(8, f.currentHighlight)
        f.engine.onUserAction(PKG, evidence = f.click(8))
        f.qualifiedSearchRows("회의", "다른 분류")
        settle()
        assertNull(f.currentHighlight)
        f.qualifiedSearchRows("2026년", "2026년")
        settle()
        assertNull(f.currentHighlight)
        assertTrue(f.engine.ui.message.contains("여러 개"))

        f.qualifiedSearchRows("2025년", "2026년")
        settle()
        advanceTimeBy(800)
        runCurrent()
        assertEquals(7, f.currentHighlight)
        assertTrue(f.engine.ui.message.contains("2026년"))
        f.engine.onUserAction(PKG, evidence = f.click(7))
        f.openedScreen("", heading = false, paneTitle = "회의 자료", paneQualifier = "2025년")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.confirmControl())
        f.openedScreen("", heading = false, paneTitle = "회의 자료", paneQualifier = "2025년")
        settle()
        assertFalse(f.engine.confirmControl())
        f.openedScreen("", heading = false, paneTitle = "회의 자료", paneQualifier = "2026년")
        settle()
        assertEquals("done", f.engine.ui.phase)
        assertEquals(12, f.calls)
        f.engine.stop()
    }

    @Test fun noAlternativeAfterVerifiedReturnDoesNotAskToVerifyArrivalAgain() = runTest {
        val f = Fixture(this, custom = {
            when (it) {
                2, 4 -> completedOpen(2)
                6 -> openCard(4, 3).copy(kind = NextKind.RECOVER, operation = Operation.NONE, targetNodeId = null,
                    tasks = emptyList(), scopeNodeIds = emptyList(), relevantNodeIds = emptyList(), message = "다른 경로를 찾지 못했어요.")
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록")
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(6, f.calls)
        assertNull(f.currentHighlight)
        assertFalse(f.engine.confirmControl())
        assertTrue(f.engine.ui.message.contains("이 화면에서 다른 방법을 찾지 못했어요"))
        f.engine.stop()
    }

    @Test fun alternatingWrongRoutesKeepTheirOwnFailuresAndBlocks() = runTest {
        val f = Fixture(this, custom = { call ->
            when (call) {
                2, 4, 6, 9 -> completedOpen(2)
                3, 8, 11 -> openCard(24, 3)
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", secondaryRoute = true)
        settle()
        for (button in listOf(4, 24, 4, 24)) {
            assertEquals(button, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(button))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            assertEquals(11, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", secondaryRoute = true)
            settle()
            advanceTimeBy(800)
            runCurrent()
        }
        assertNull(f.currentHighlight)
        assertEquals(listOf(4, 11, 24, 11, 4, 11, 24, 11), f.highlights.map { it.id })
        assertEquals(listOf(setOf(4), setOf(4, 24)), f.attempts.mapNotNull { it.diagnostic?.blockedNodeIds })
        assertEquals(11, f.calls)
        f.engine.stop()
    }

    @Test fun anObservedSourceRestorationRecordsFailureWithoutAClickReceiptForReturn() = runTest {
        val f = Fixture(this, custom = { if (it in listOf(2, 4)) completedOpen(2) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        repeat(2) {
            assertEquals(4, f.currentHighlight)
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진")
            settle()
            assertEquals("wait", f.engine.ui.currentAction)
            assertNull(f.currentHighlight)
            f.nestedCards(sourcePaneTitle = "자료 목록")
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertNull(f.currentHighlight)
        assertEquals(listOf(4, 4), f.highlights.map { it.id })
        assertEquals(setOf(4), f.attempts.last().diagnostic?.blockedNodeIds)
        f.engine.stop()
    }

    @Test fun aLateAlternativeCannotHighlightTheOldControlAfterTheListChanges() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            when (call) {
                2, 4 -> completedOpen(2)
                6 -> withContext(NonCancellable) { late.await() }
                7 -> openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "자료 찾기를 눌러주세요.")
                else -> openCard(4, 3)
            }
        })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
        settle()
        repeat(2) {
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(11))
            f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true)
            settle()
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(6, f.calls)
        assertEquals("repeated_wrong_navigation", f.attempts.last().diagnostic?.reason)
        assertNull(f.currentHighlight)

        f.nestedCards(sourcePaneTitle = "자료 목록", unrelatedAction = true, actionLabel = "자료 찾기")
        settle()
        assertEquals("자료 찾기를 눌러주세요.", f.engine.ui.message)
        assertEquals(8, f.currentHighlight)
        val latest = f.attempts.last().ticket
        assertNotEquals(f.attempts[5].ticket.requestId, latest.requestId)
        late.complete(openCard(8, 3).copy(scopeNodeIds = emptyList(), message = "검색을 눌러주세요."))
        settle()
        assertEquals(7, f.calls)
        assertEquals("자료 찾기를 눌러주세요.", f.engine.ui.message)
        assertEquals(listOf("자료 찾기"), f.highlights.filter { it.id == 8 }.map { it.text })
        f.engine.stop()
    }

    @Test fun aManualReturnFromTheRepeatedWrongCardAlsoBlocksTheSameRoute() = runTest {
        val f = Fixture(this, custom = { if (it in listOf(2, 4)) completedOpen(2) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()
        assertEquals(11, f.currentHighlight)
        f.engine.onUserAction(PKG, evidence = f.click(11))
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진")
        settle()
        assertNull(f.currentHighlight)

        f.engine.onUserAction(PKG)
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        advanceTimeBy(800)
        runCurrent()
        assertNull(f.currentHighlight)
        assertEquals("repeated_wrong_navigation", f.attempts.last().diagnostic?.reason)
        f.engine.stop()
    }

    @Test fun aReturnClickThatLeavesTheSameWrongCardDoesNotRepeatTheInstruction() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()
        assertEquals(11, f.currentHighlight)
        f.engine.onUserAction(PKG, evidence = f.click(11))
        repeat(2) {
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()
            assertNull(f.currentHighlight)
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("이전 화면이 열렸는지 확인하지 못했어요"))
        assertTrue(f.engine.confirmControl())
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(2, f.calls)
        assertEquals(listOf(4, 11), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun returnWaitRequiresTheNamedSourceWithTheOriginalScopedControl() = runTest {
        val f = Fixture(this, custom = { if (it == 2) completedOpen(2) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록", sharedButtonResource = true)
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()
        val click = f.click(11)
        f.engine.onUserAction(PKG, evidence = click)

        f.openedScreen("", heading = false, paneTitle = "자료 목록")
        settle()
        f.nestedCards(sourcePaneTitle = "다른 목록", sharedButtonResource = true)
        settle()
        f.nestedCards(sourcePaneTitle = "자료 목록", sharedButtonResource = true)
        f.screens.value = f.screens.value.let { state ->
            state.copy(snapshot = state.snapshot!!.copy(captureStartedAtUptimeMillis = click.eventUptimeMillis!! - 1))
        }
        settle()
        assertNull(f.currentHighlight)
        assertEquals(2, f.calls)

        f.nestedCards(sourcePaneTitle = "자료 목록", sharedButtonResource = true)
        settle()
        assertEquals(4, f.currentHighlight)
        assertEquals(3, f.calls)
        f.engine.stop()
    }

    @Test fun theVerifiedTargetCanStillCompleteWhileWaitingForReturn() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(11))
        f.openedScreen("", heading = false, paneTitle = "회의 자료")
        settle()

        assertEquals("done", f.engine.ui.phase)
        assertEquals(listOf(4, 11), f.highlights.map { it.id })
        assertEquals(3, f.calls)
        f.engine.stop()
    }

    @Test fun aVisibleEditorPreventsReturnGuidanceEvenWhenTheWrongCardIsKnown() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기", draft = true)
        settle()

        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("여행 사진"))
        assertEquals(listOf(4), f.highlights.map { it.id })
        assertTrue(f.engine.confirmControl())
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기", draft = true)
        settle()
        assertFalse(f.engine.ui.awaitingControlCompletion)
        assertFalse(f.engine.confirmControl())
        f.engine.stop()
    }

    @Test fun returnGuidanceRequiresAnyVisibleSourceEditorToBeKnownEmpty() = runTest {
        for (hasInput in listOf(true, null, false)) {
            val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
            f.begin("회의 자료를 열어줘")
            f.nestedCards(sourcePaneTitle = "자료 목록", sourceEditor = true, sourceHasInput = hasInput)
            settle()
            f.engine.onUserAction(PKG, evidence = f.click(4))
            f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
            settle()

            if (hasInput == false) {
                assertEquals(11, f.currentHighlight)
            } else {
                assertNull(f.currentHighlight)
                assertEquals("wait", f.engine.ui.currentAction)
                assertTrue(f.engine.ui.message.contains("작성 중인 내용"))
            }
            f.engine.stop()
        }
    }

    @Test fun aLateEditorWithdrawsTheDisplayedReturnWithoutAnotherPlan() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기")
        settle()
        assertEquals(11, f.currentHighlight)
        assertEquals(2, f.calls)

        f.openedScreen("", heading = false, paneTitle = "여행 사진", returnLabel = "자료 목록으로 돌아가기", draft = true)
        runCurrent()
        assertNull(f.currentHighlight)
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("작성 중인 내용"))
        settle()
        assertEquals(2, f.calls)
        f.engine.stop()
    }

    @Test fun anUnlabeledDestinationDoesNotAuthorizeAVisibleReturnButton() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false, returnLabel = "자료 목록으로 돌아가기")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertEquals(listOf(4), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun namingTheBackActionAsATapDoesNotBypassTheScopedRecoveryCheck() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else openCard(11, 2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(sourcePaneTitle = "자료 목록")
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false, returnLabel = "뒤로가기")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertEquals(listOf(4), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun aRecheckOfTheSameUnlabeledDestinationCanEndByUserReportWithoutAnotherPlan() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertEquals("다시 확인", f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl())
        assertFalse(f.engine.confirmControl())
        f.openedScreen("자료 본문", heading = false)
        settle()

        assertEquals(2, f.calls)
        assertEquals("화면에서 확인했어요", f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl())
        assertEquals("user_confirmed_result", f.engine.ui.currentAction)
        assertEquals("user", f.engine.ui.decisionSource)
        assertEquals(2, f.calls)
    }

    @Test fun aManualReportCannotUseThePromptFromBeforeTheScreenChanged() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertTrue(f.engine.confirmControl())
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertEquals("화면에서 확인했어요", f.engine.ui.controlCompletionLabel)

        f.openedScreen("다른 자료", heading = false)
        assertFalse(f.engine.confirmControl())
        settle()
        assertNotEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun scopedResultRecheckExpiresWithoutAllowingAReportFromAnOldFrame() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertTrue(f.engine.confirmControl())
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, f.calls)
        assertFalse(f.engine.ui.awaitingControlCompletion)
        assertFalse(f.engine.confirmControl())
        f.engine.stop()
    }

    @Test fun aRecheckWithoutTheNamedClickShowsInstructionsWithoutAResultReportButton() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(7))
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertTrue(f.engine.confirmControl())
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertEquals(2, f.calls)
        assertFalse(f.engine.ui.awaitingControlCompletion)
        assertFalse(f.engine.confirmControl())
        f.engine.stop()
    }

    @Test fun newlyAvailableDestinationEvidenceStillUsesDeviceVerificationAfterARecheck() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("자료 본문", heading = false)
        settle()
        assertTrue(f.engine.confirmControl())
        f.openedScreen("회의 자료")
        settle()
        assertEquals(3, f.calls)
        assertEquals("done", f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aNamedDestinationPaneCanProveArrivalWithoutAHeading() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("열고 있어요", heading = false)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)

        f.openedScreen("", heading = false, paneTitle = "회의 자료")
        settle()
        assertEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aNamedClickCanBindToTheLatestPreClickGeometryOfTheSameCard() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.nestedCards(horizontalOffset = 8)
        settle()
        assertEquals(1, f.calls)
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("회의 자료")
        settle()
        assertEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aUniquePaneCanDisambiguateDuplicateContentHeadings() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "회의 자료", duplicateHeadings = true)
        settle()
        assertEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aMatchingTextHeadingInsideADifferentNamedPaneDoesNotComplete() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("회의 자료", paneTitle = "여행 사진")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun twoMatchingPaneTitlesRemainAmbiguous() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("회의 자료", paneTitle = "회의 자료", duplicatePanes = true)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aPartiallyVisibleMatchingPeerPaneStillMakesArrivalAmbiguous() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.openedScreen("", heading = false, paneTitle = "회의 자료", duplicatePanes = true, clippedPeer = true)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        f.engine.stop()
    }

    @Test fun aDifferentCardsClickDoesNotConfirmTheNamedNavigation() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(2) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards()
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(7))
        f.openedScreen("회의 자료")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertNotEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun theOldCardsVisibleHeadingCannotBeUsedAsAnArrivalResult() = runTest {
        val f = Fixture(this, custom = { if (it == 1) openCard(4, 3) else completedOpen(3) })
        f.begin("회의 자료를 열어줘")
        f.nestedCards(firstHeading = true)
        settle()
        f.engine.onUserAction(PKG, evidence = f.click(4))
        f.nestedCards(firstHeading = true)
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertNotEquals("done", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aChangedPublicReasonWithdrawsTheOldPreparationWithoutWaitingForAnInputClick() = runTest {
        val f = Fixture(this, custom = { if (it == 1) prepareCard(8) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()
        assertEquals("highlight", f.engine.ui.currentAction)

        f.scopedForm(input = true)
        settle()
        assertEquals("highlight", f.engine.ui.currentAction)
        assertEquals(1, f.calls)

        f.scopedForm(input = true, notice = "검토가 끝날 때까지 기다려주세요.")
        settle()
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("검토가 끝날 때까지"))
        assertFalse(f.engine.confirmControl())
        assertEquals(2, f.calls)
        f.engine.stop()
    }

    @Test fun anExplicitlyRequiredEditorCannotFinishEmptyAndEnablingOpenDoesNotFinishTyping() = runTest {
        val f = Fixture(this, custom = { if (it == 1) prepareCard(8) else openCard(4, 3) })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()
        assertFalse(f.engine.ui.controlCompletionEnabled)
        assertFalse(f.engine.confirmControl())

        f.scopedForm(input = true, notice = null, buttonEnabled = true)
        settle()
        assertEquals(1, f.calls)
        assertEquals("입력했어요", f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl())
        settle()

        assertEquals(2, f.calls)
        assertEquals(listOf(8, 4), f.highlights.map { it.id }.distinct())
        assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", f.engine.ui.message)
        f.engine.stop()
    }
    @Test fun aLatePreparationResponseRecoversFromAChangedPublicButtonNotice() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { call, _ ->
            if (call == 1) withContext(NonCancellable) { late.await() } else openCard(4, 3)
        })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()
        assertEquals(1, f.calls)

        f.scopedForm(notice = "검토가 끝날 때까지 기다려주세요.")
        settle()
        late.complete(prepareCard(8))
        settle()

        assertEquals(2, f.calls)
        assertEquals(listOf(0, 0), f.attempts.map { it.index })
        assertNotEquals(f.attempts.first().ticket.requestId, f.attempts.last().ticket.requestId)
        assertEquals(f.attempts.first().ticket.goalRevision, f.attempts.last().ticket.goalRevision)
        assertTrue(f.highlights.isEmpty())
        assertEquals("wait", f.engine.ui.currentAction)
        assertTrue(f.engine.ui.message.contains("검토가 끝날 때까지"))
        f.engine.stop()
    }

    @Test fun anotherCardsNoticeChangeDoesNotRestartThePendingPreparation() = runTest {
        val late = CompletableDeferred<TaskPlan>()
        val f = Fixture(this, asynchronous = { _, _ -> withContext(NonCancellable) { late.await() } })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()
        f.scopedForm(otherNotice = "새 사진을 불러오는 중이에요.")
        settle()
        late.complete(prepareCard(8))
        settle()

        assertEquals(1, f.calls)
        assertEquals(listOf(8), f.highlights.map { it.id })
        f.engine.stop()
    }

    @Test fun repeatedlyChangingTheScopedNoticeDoesNotKeepRestartingThePlanner() = runTest {
        val replies = List(2) { CompletableDeferred<TaskPlan>() }
        val f = Fixture(this, asynchronous = { call, _ -> withContext(NonCancellable) { replies[call - 1].await() } })
        f.begin("회의 자료를 열어줘")
        f.scopedForm()
        settle()
        f.scopedForm(notice = "먼저 문서 이름을 입력해 주세요.")
        replies[0].complete(prepareCard(8))
        settle()
        assertEquals(2, f.calls)

        f.scopedForm(notice = "검토가 끝날 때까지 기다려주세요.")
        replies[1].complete(prepareCard(8))
        settle()
        assertEquals(2, f.calls)
        assertTrue(f.highlights.isEmpty())
        assertEquals("error", f.engine.ui.phase)
        f.engine.stop()
    }

    @Test fun aReadonlyPreparationChangeInvalidatesAnAlreadyDisplayedCommit()=runTest {
        val f=Fixture(this,custom={commit().copy(conditions=commit().conditions.map{it.copy(evidenceNodeIds=listOf(3))})})
        f.begin();f.frame(tick="준비 값 A");settle();assertEquals(1,f.calls)
        f.frame(tick="바뀐 준비 값 B");settle()
        assertEquals("A changed read-only prerequisite needs a fresh plan before committing",2,f.calls)
        f.engine.stop()
    }
    @Test fun oneReadyValueDoesNotPermitCommitWhileAnotherPreparationIsUnsatisfied()=runTest {
        val f=Fixture(this,custom={commit().copy(conditions=listOf(
            GoalCondition("first","첫 번째 준비 값이 맞음",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(3),phase=ConditionPhase.PREPARATION),
            GoalCondition("second","두 번째 준비 값이 맞음",true,Truth.UNSATISFIED,EvidenceKind.OBSERVED,listOf(4),phase=ConditionPhase.PREPARATION),
            GoalCondition("saved","최종 결과가 저장됨",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList())),
            actionGates=listOf(ActionGate(2,listOf("first","second"))))})
        f.begin();f.frame();settle()
        assertTrue("A partially prepared form must not highlight its commit control",f.highlights.isEmpty())
        f.engine.stop()
    }
    @Test fun aLocalRequestLimitIsNotMisreportedAsANetworkFailure()=runTest {
        val message="현재 할 일로 다시 시작해주세요."
        val f=Fixture(this,custom={throw V3PlanFailure(V3PlanFailureKind.CONTEXT_TOO_LARGE,message)})
        f.begin();f.frame();settle()
        assertEquals("error",f.engine.ui.phase);assertEquals(message,f.engine.ui.message)
        assertTrue(f.highlights.isEmpty());f.engine.stop()
    }
    @Test fun aLaterPlanCannotCompleteByOmittingAnEarlierRequiredCondition()=runTest {
        val a=GoalCondition("desired","원하는 값 유지",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList())
        val b=GoalCondition("scope","선택한 범위 유지",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(3))
        val f=Fixture(this,custom={call->if(call==1)guide().copy(operation=Operation.TAP,targetNodeId=2,conditions=listOf(a,b))
            else complete().copy(goalKind=GoalKind.STATE,commitOutcome=CommitOutcome.NONE,outcomeNodeIds=emptyList(),newEventNodeIds=emptyList(),conditions=listOf(b))})
        f.begin();f.frame();settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="바뀐 표시");settle()
        assertNotEquals("Dropping a required condition from the next proposal must not complete the owned goal","done",f.engine.ui.phase)
        f.engine.stop()
    }
    @Test fun anUnresolvedCommitAlsoBlocksAStateCompletionClaim()=runTest {
        val f=Fixture(this,custom={call->if(call==1)commit().copy(goalKind=GoalKind.STATE)
            else complete().copy(goalKind=GoalKind.STATE,commitOutcome=CommitOutcome.NONE,outcomeNodeIds=emptyList(),newEventNodeIds=emptyList())})
        f.begin();f.frame(tick="이전 상태");settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="바뀐 표시");settle()
        assertNotEquals("done",f.engine.ui.phase);assertEquals("wait",f.engine.ui.currentAction);f.engine.stop()
    }
    @Test fun anAlreadySatisfiedStateCanStillFinishWithoutAnUnnecessaryCommit()=runTest {
        val f=Fixture(this,custom={complete().copy(goalKind=GoalKind.STATE,commitOutcome=CommitOutcome.NONE,
            outcomeNodeIds=emptyList(),newEventNodeIds=emptyList())})
        f.begin();f.frame(tick="이미 원하는 상태");settle()
        assertEquals("done",f.engine.ui.phase);assertEquals(1,f.calls);assertTrue(f.highlights.isEmpty())
    }
    @Test fun changingTheModelGoalKindCannotEraseANewEventRequirement()=runTest {
        val f=Fixture(this,custom={call->if(call==1)commit().copy(effect=ActionEffect.PREPARE)
            else complete().copy(goalKind=GoalKind.STATE,commitOutcome=CommitOutcome.NONE,outcomeNodeIds=emptyList(),newEventNodeIds=emptyList())})
        f.begin();f.frame(tick="기존 내역");settle()
        f.engine.onUserAction(PKG,scrolled=true);f.frame(tick="그대로 있는 내역");settle()
        assertNotEquals("A later model response must not downgrade the owned goal to avoid event evidence","done",f.engine.ui.phase)
        f.engine.stop()
    }
    @Test fun aModelCannotRepeatASuccessByRelabelingTheActionAsPreparation()=runTest {
        for(effect in listOf(ActionEffect.NONE,ActionEffect.PREPARE,ActionEffect.START_ACTIVITY)){
            val f=Fixture(this,custom={call->if(call==1)commit()else commit().copy(effect=effect,
                commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(3))})
            f.begin();f.frame(tick="이전 기록");settle()
            f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="새 확정 결과");settle()
            assertEquals("Changing effect to $effect must not bypass local history",1,f.highlights.size)
            f.engine.stop()
        }
    }
    @Test fun theEngineCanCompleteTwoDistinctNativeOperationsWithoutForgettingTheFirst()=runTest {
        val f=Fixture(this,custom={call->when(call){
            1->commit()
            2->commit().copy(targetNodeId=4,tasks=listOf(TaskStep("second","둘째 확정",listOf(4),emptyList())),
                conditions=commit().conditions.map{it.copy(evidenceNodeIds=listOf(4))},actionGates=listOf(ActionGate(4,listOf("prepared"))),
                relations=emptyList(),relevantNodeIds=listOf(4),commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(3))
            else->complete()
        }})
        f.begin();f.frame(tick="이전 기록");settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="첫 확정 결과");settle()
        assertEquals(4,f.highlights.last().id)
        f.engine.onUserAction(PKG,evidence=f.click(4));f.frame(tick="둘째 확정 결과");settle()
        assertEquals("done",f.engine.ui.phase)
        assertEquals(listOf(1L,2L),f.lastGoal!!.operationHistory.map{it.receiptId})
        assertEquals(CommitOutcome.SUCCEEDED,f.lastGoal!!.operationHistory.first().state)
    }
    @Test fun renamingAModelTaskDoesNotPermitRepeatingAnAlreadySuccessfulCommit()=runTest {
        val f=Fixture(this,custom={call->if(call==1)commit()else commit().copy(
            tasks=listOf(TaskStep("renamed_by_model","다른 이름",listOf(2),emptyList())),relations=emptyList(),
            commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(3))})
        f.begin();f.frame(tick="이전 기록");settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="새로 생성됨");settle()
        assertEquals("A model task rename must not issue the successful commit again",1,f.highlights.size)
        assertNotEquals("highlight",f.engine.ui.currentAction);f.engine.stop()
    }
    @Test fun aResultAlreadyObservedBeforeTheClickIsNotANewOutcome()=runTest {
        val f=Fixture(this,custom={call->if(call==1)commit()else complete()})
        f.begin();f.frame(tick="이전 기록");settle()
        f.frame(tick="클릭 전에 이미 보인 결과");settle()
        assertEquals(1,f.calls)
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="클릭 전에 이미 보인 결과");settle()
        assertNotEquals("The displayed instruction's older baseline must not turn an existing result into a new event","done",f.engine.ui.phase)
        f.engine.stop()
    }
    @Test fun onlyTheBoundCommitClickAndANewOutcomeCanCompleteANewEvent()=runTest {
        val f=Fixture(this,custom={call->if(call==1)commit()else complete()})
        f.begin();f.frame(tick="이전 기록");settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="이번에 새로 생성됨");settle()
        assertEquals("done",f.engine.ui.phase)
    }
    @Test fun anUnknownCommitIsNotRepeatedAndAReadonlyResultCanWakeThePlanner()=runTest {
        val f=Fixture(this,custom={call->when{
            call==1->commit()
            call<4->commit().copy(commitOutcome=CommitOutcome.UNKNOWN)
            else->complete()
        }})
        f.begin();f.frame(tick="이전 기록");settle()
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="처리 중");settle()
        assertEquals("wait",f.engine.ui.currentAction);assertEquals(1,f.highlights.size)
        assertTrue(f.engine.confirmControl());settle();assertEquals("wait",f.engine.ui.currentAction);assertEquals(1,f.highlights.size)
        f.frame(tick="이번에 새로 생성됨");settle();assertEquals("done",f.engine.ui.phase)
    }
    @Test fun nativeControlsAdvanceAnEditorButFocusDoesNot()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();f.frame(input=true);settle()
        f.engine.onUserAction(PKG,evidence=f.click(2,android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED));f.frame(input=true);settle()
        assertEquals(1,f.calls)
        f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(input=true);settle();assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun aReadonlyScopeChangeInterruptsInputAndPreservesItsObservationHistory()=runTest {
        val f=Fixture(this,custom={guide().copy(scopeNodeIds=listOf(3))});f.begin();f.frame(tick="프로필 A");settle()
        f.frame(input=true,tick="프로필 B");settle()
        assertEquals(2,f.calls);assertTrue(f.lastGoal!!.scopeHistory.any{it.publicLabel=="프로필 A"});f.engine.stop()
    }
    @Test fun scrollingAndOldHistoryNeverProveThatANewEventHappened()=runTest {
        val f=Fixture(this,custom={call->
            if(call==1)guide().copy(goalKind=GoalKind.NEW_EVENT,operation=Operation.TAP,targetNodeId=2)
            else guide().copy(kind=NextKind.COMPLETE,goalKind=GoalKind.NEW_EVENT,operation=Operation.NONE,targetNodeId=null,
                conditions=listOf(GoalCondition("created","새 항목 생성",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(3))),
                newEventNodeIds=listOf(3))
        })
        f.begin();f.frame();settle()
        f.engine.onUserAction(PKG,scrolled=true);f.frame(input=true);settle()
        assertNotEquals("A scroll and existing history must never complete a new event","done",f.engine.ui.phase)
        f.engine.stop()
    }
    @Test fun typingDoesNotAdvanceUntilTheUserRequestsTheNextInstruction()=runTest {
        val f=Fixture(this);f.begin();f.frame(input=false);settle()
        assertTrue(f.engine.ui.toString(),f.engine.ui.awaitingControlCompletion)
        assertTrue(f.engine.ui.controlCompletionEnabled);assertEquals("비워두었어요",f.engine.ui.controlCompletionLabel)
        repeat(8){f.engine.onUserAction(PKG);f.frame(input=true);settle()}
        assertEquals(1,f.calls);assertTrue(f.engine.ui.controlCompletionEnabled);assertEquals("입력했어요",f.engine.ui.controlCompletionLabel)
        assertTrue(f.engine.confirmControl());settle();assertEquals(2,f.calls);assertEquals(2,f.highlights.last().id);f.engine.stop()
    }
    @Test fun anActivityDoesNotChooseTheStopButtonWhileItShouldKeepRunning()=runTest {
        val f=Fixture(this,wait=true);f.begin();f.frame();settle()
        repeat(8){f.frame(tick=it.toString());settle()}
        assertEquals(1,f.calls);assertEquals("wait",f.engine.ui.currentAction);assertTrue(f.highlights.isEmpty());f.engine.stop()
    }
    @Test fun anAnswerChangesOnlyTheCurrentGoalRevisionAndReplans()=runTest {
        val f=Fixture(this,ask=true);f.begin();f.frame();settle()
        assertEquals("ask",f.engine.ui.currentAction);assertFalse(f.engine.answer("other","원하는 값"))
        assertTrue(f.engine.answer("desired","원하는 값"));settle()
        assertEquals(2,f.calls);assertEquals("원하는 값",f.lastGoal!!.answers["desired"]);assertEquals(2L,f.lastGoal!!.revision);f.engine.stop()
    }
    @Test fun closingTheSessionRejectsAResultEvenIfTheTransportIgnoresCancellation()=runTest {
        val deferred=CompletableDeferred<TaskPlan>();val f=Fixture(this,pending=deferred)
        f.begin();f.frame();settle();assertEquals(1,f.calls)
        f.engine.stop();deferred.complete(guide());settle()
        assertEquals("idle",f.engine.ui.phase);assertTrue(f.highlights.isEmpty())
    }
    @Test fun leavingAndReturningToTheAppRequiresFreshGuidance()=runTest {
        val f=Fixture(this);f.begin();f.frame();settle();assertEquals(1,f.calls)
        f.engine.onForegroundChanged("other.app")
        f.screens.value=f.screens.value.copy(foregroundPackage="other.app")
        f.frame();settle();assertEquals(2,f.calls);f.engine.stop()
    }
    @Test fun anUnnecessaryQuestionIsRepairedToDirectInputGuidance()=runTest {
        val f=Fixture(this,custom={if(it==1)guide().copy(kind=NextKind.ASK,operation=Operation.NONE,targetNodeId=null,
            question=PlanQuestion("desired","누구에게 보낼까요?",true,true,true))else guide()});f.begin();f.frame();settle()
        assertEquals("guiding",f.engine.ui.phase);assertEquals(2,f.calls);assertEquals(1,f.highlights.single().id);assertNull(f.engine.question());f.engine.stop()
    }
    @Test fun repeatedInvalidPlansUseOnlyOneAutomaticCorrection()=runTest {
        val f=Fixture(this,custom={guide().copy(targetNodeId=999)})
        f.begin();f.frame();settle();assertEquals(2,f.calls);assertEquals("error",f.engine.ui.phase)
        repeat(8){f.frame(tick="$it");settle()};assertEquals(2,f.calls);assertTrue(f.highlights.isEmpty())
        assertTrue(f.engine.retryFromCurrent());settle();assertEquals(4,f.calls)
        assertNotSame(f.attempts[0].ticket,f.attempts[2].ticket);f.engine.stop()
    }
    @Test fun typedNonPlanningFailuresNeverBecomeAutomaticCorrection()=runTest {
        for(kind in listOf(V3PlanFailureKind.AUTHENTICATION,V3PlanFailureKind.LIMIT,V3PlanFailureKind.BUSY,V3PlanFailureKind.REMOTE,
            V3PlanFailureKind.CONNECTION_CHANGED,V3PlanFailureKind.PROTOCOL_MISMATCH,V3PlanFailureKind.UNKNOWN_REMOTE_RESULT,V3PlanFailureKind.MODEL_REFUSAL,V3PlanFailureKind.REQUEST_EXPIRED)){
            val f=Fixture(this,custom={throw V3PlanFailure(kind,"연결 확인",diagnostic=V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result"))})
            f.begin();f.frame();settle();assertEquals(kind.name,1,f.calls);assertEquals("error",f.engine.ui.phase);f.engine.stop()
        }
    }
    @Test fun aTypedResponseContractFailureCarriesItsDiagnosticToOneCorrection()=runTest {
        val f=Fixture(this,custom={if(it==1)throw V3PlanFailure(V3PlanFailureKind.INVALID_RESPONSE,"응답 확인",
            diagnostic=V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result"))else guide()})
        f.begin();f.frame();settle();assertEquals(2,f.calls);assertEquals("guiding",f.engine.ui.phase)
        assertEquals("server_invalid_model_result",f.attempts[1].diagnostic!!.reason);f.engine.stop()
    }
    @Test fun anUnresolvedNativeCommitDoesNotStartAutomaticPlanRepair()=runTest {
        val f=Fixture(this,custom={if(it==1)commit()else throw V3PlanFailure(V3PlanFailureKind.INVALID_RESPONSE,"답변 확인",
            diagnostic=V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result"))})
        f.begin();f.frame();settle();f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="처리 중");settle()
        assertEquals(2,f.calls);assertEquals(1,f.lastGoal!!.operationHistory.size);assertEquals(1,f.highlights.size);f.engine.stop()
    }
    @Test fun aLateCorrectionCannotReviveAStoppedSession()=runTest {
        val pending=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==1)guide().copy(targetNodeId=999)else withContext(NonCancellable){pending.await()}})
        f.begin();f.frame();settle();assertEquals(2,f.calls);assertTrue(f.highlights.isEmpty())
        f.engine.stop();pending.complete(guide());settle();assertEquals("idle",f.engine.ui.phase);assertTrue(f.highlights.isEmpty())
    }
    @Test fun aForegroundChangeDiscardsTheLateCorrection()=runTest {
        val pending=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==1)guide().copy(targetNodeId=999)else withContext(NonCancellable){pending.await()}})
        f.begin();f.frame();settle();assertEquals(2,f.calls)
        f.engine.onForegroundChanged("other.app");f.screens.value=f.screens.value.copy(foregroundPackage="other.app")
        pending.complete(guide());settle();assertTrue(f.highlights.isEmpty());f.engine.stop()
    }
    @Test fun aReadonlyChangeDuringCorrectionInvalidatesItsObservationScope()=runTest {
        val pending=CompletableDeferred<TaskPlan>()
        val f=Fixture(this,asynchronous={call,_->if(call==1)guide().copy(targetNodeId=999)else withContext(NonCancellable){pending.await()}})
        f.begin();f.frame(tick="A");settle();assertEquals(2,f.calls)
        f.frame(tick="B");settle();pending.complete(guide());settle()
        assertTrue("A correction against a superseded observation must never highlight",f.highlights.isEmpty())
        assertEquals("error",f.engine.ui.phase);f.engine.stop()
    }
    @Test fun aReadonlyChangeBeforeRejectionPreventsRepairAgainstTheOldObservation()=runTest {
        val pending=CompletableDeferred<Unit>()
        val f=Fixture(this,asynchronous={_,_->pending.await();throw V3PlanFailure(V3PlanFailureKind.INVALID_RESPONSE,"답변 확인",
            diagnostic=V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result"))})
        f.begin();f.frame(tick="A");settle();f.frame(tick="B");settle();pending.complete(Unit);settle()
        assertEquals(1,f.calls);assertTrue(f.highlights.isEmpty());assertEquals("error",f.engine.ui.phase);f.engine.stop()
    }
    @Test fun aRejectedDefinitionChangeIsNotCommittedBeforeCorrection()=runTest {
        val condition=GoalCondition("kept","원래 정의 유지",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList())
        val f=Fixture(this,custom={call->guide().copy(operation=Operation.TAP,targetNodeId=2,
            conditions=listOf(if(call==2)condition.copy(description="잘못 바꾼 정의")else condition))})
        f.begin();f.frame();settle();f.engine.onUserAction(PKG,evidence=f.click(2));f.frame(tick="변경");settle()
        assertEquals(3,f.calls);assertEquals("guiding",f.engine.ui.phase)
        assertEquals("원래 정의 유지",f.goals[2].requirements.single().description);assertEquals(1,f.goals[2].requirements.single().version);f.engine.stop()
    }
    @Test fun aRetainedPreparationRejectionIsRepairedBeforeAnyCommitHighlight()=runTest {
        val prepared=commit().conditions
        val f=Fixture(this,custom={call->when(call){
            1->guide().copy(conditions=prepared,actionGates=commit().actionGates)
            2->guide().copy(operation=Operation.TAP,targetNodeId=2)
            else->guide().copy(conditions=prepared,actionGates=commit().actionGates)
        }})
        f.begin();f.frame();settle();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle()
        assertEquals(3,f.calls);assertEquals(V3DiagnosticSource.PREPARATION,f.attempts.last().diagnostic!!.source)
        assertEquals("missing_preparation_report",f.attempts.last().diagnostic!!.reason)
        assertTrue(f.highlights.all{it.id==1});f.engine.stop()
    }
    @Test fun aDefinitionEditIsNotConsumedWhenItsPreparationFailsPreflight()=runTest {
        val prepared=commit().conditions
        val f=Fixture(this,custom={call->when(call){
            1->guide().copy(conditions=prepared,actionGates=commit().actionGates)
            2->guide().copy(operation=Operation.TAP,targetNodeId=2,conditions=prepared.map{it.copy(description="정정한 준비",truth=Truth.UNKNOWN)},
                conditionEdits=listOf(ConditionEdit(1,1,ConditionEditKind.REPLACE,"공개 근거로 추론 정정",
                    ConditionBasis(ConditionOrigin.INFERRED,quote="작업을 설정하고 싶어",rationale="공개 준비의 의미 정정",evidenceNodeIds=listOf(3)))))
            else->guide().copy(conditions=prepared,actionGates=commit().actionGates)
        }})
        f.begin();f.frame();settle();f.frame(input=true);settle();assertTrue(f.engine.confirmControl());settle()
        assertEquals(3,f.calls);assertEquals("unready_preparation",f.attempts.last().diagnostic!!.reason)
        assertEquals("공개 작업 준비",f.goals[2].requirements.single().description);assertEquals(1,f.goals[2].requirements.single().version)
        assertTrue(f.highlights.all{it.id==1});f.engine.stop()
    }
    private fun TestScope.settle(){runCurrent();advanceTimeBy(301);runCurrent()}
    private class Fixture(private val scope:TestScope,val wait:Boolean=false,val ask:Boolean=false,val unnecessary:Boolean=false,
        val pending:CompletableDeferred<TaskPlan>?=null,val custom:((Int)->TaskPlan)?=null,
        val asynchronous:(suspend (Int,V3PlanningAttempt)->TaskPlan)?=null) {
        val screens=MutableStateFlow(AssistUiState(serviceConnected=true,foregroundPackage=PKG))
        var sequence=0L;var calls=0;var lastGoal:TaskGoal?=null;val highlights=mutableListOf<AccessibilityNodeModel>()
        var currentHighlight: Int? = null
        var refreshes = 0
        var nativeInputVersion=0L
        val attempts=mutableListOf<V3PlanningAttempt>();val goals=mutableListOf<TaskGoal>()
        val engine=V3GuideEngine(scope.backgroundScope,screens,"assistant.fixture",{BridgeStatus(true,"ready")},
            {goal,_,prior,_,attempt->calls++;lastGoal=goal;attempts+=attempt;goals+=goal
                val proposal=when {
                    asynchronous!=null->asynchronous(calls,attempt)
                    custom!=null->custom(calls)
                    pending!=null->withContext(NonCancellable){pending.await()}
                    wait->guide().copy(kind=NextKind.WAIT,operation=Operation.NONE,targetNodeId=null,waitKind=WaitKind.USER_ACTIVITY,message="진행 중이에요.")
                    ask && calls==1->guide().copy(kind=NextKind.ASK,operation=Operation.NONE,targetNodeId=null,
                        question=PlanQuestion("desired","원하는 값을 알려주세요.",true,true,unnecessary))
                    calls==1->guide()
                    else->guide().copy(operation=Operation.TAP,targetNodeId=2,message="다음 항목을 눌러주세요.")
                }
                // Valid mock proposals explicitly carry references that the real model receives in its input.
                val referenced=proposal.copy(conditions=proposal.conditions.map{c->
                    val id=c.requirementId ?:prior?.conditions?.singleOrNull{it.key==c.key}?.requirementId
                        ?:goal.requirements.singleOrNull{it.description==c.description}?.id
                    val owned=goal.requirements.singleOrNull{it.id==id}
                    if(owned!=null)c.copy(requirementId=owned.id,requirementVersion=owned.version,basis=null)
                    else c.copy(basis=c.basis ?:ConditionBasis(ConditionOrigin.INFERRED,quote=goal.request,rationale="공개 시험 목표의 필요한 조건"))
                })
                if(referenced.commitOutcome==CommitOutcome.NONE)referenced else referenced.copy(outcomeReceiptId=goal.operationHistory.lastOrNull()?.receiptId)
            },{}, {highlights+=it;currentHighlight=it.id;true},{currentHighlight=null},{},{},{refreshes++},nowUptime={scope.testScheduler.currentTime},inputObservationVersion={nativeInputVersion},
            highlightScoped={highlights+=it.node;currentHighlight=it.node.id;true})
        suspend fun begin(request: String = "작업을 설정하고 싶어") {
            assertTrue(engine.start(request, PKG, "공개 시험 앱"))
        }
        fun cards(firstTitle: String = "회의 자료", secondTitle: String = "여행 사진") {
            frame()
            fun group(id: Int, title: String, top: Int): List<AccessibilityNodeModel> {
                val path = "0/$id"
                return listOf(
                    node(id, "").copy(path = path, clickable = false, className = "android.widget.LinearLayout",
                        bounds = NodeBounds(0, top, 500, top + 180)),
                    node(id + 1, title).copy(path = "$path/0", parentPath = path, clickable = false,
                        bounds = NodeBounds(10, top + 5, 490, top + 65)),
                    node(id + 2, "열기").copy(path = "$path/1", parentPath = path,
                        bounds = NodeBounds(10, top + 80, 490, top + 165)),
                )
            }
            val nodes = group(2, firstTitle, 100) + group(5, secondTitle, 310)
            val state = screens.value
            screens.value = state.copy(snapshot = state.snapshot!!.copy(nodes = nodes, allNodes = nodes))
        }
        fun nestedCards(scrollableFooter: Boolean = false, detachedTitle: Boolean = false, unrelatedAction: Boolean = false,
                        firstButtonEnabled: Boolean = true, firstNotice: String? = null, secondNotice: String? = null,
                        horizontalOffset: Int = 0, firstHeading: Boolean = false, sourcePaneTitle: String? = null,
                        sourceEditor: Boolean = false, sourceHasInput: Boolean? = null, sharedButtonResource: Boolean = false,
                        secondaryRoute: Boolean = false, actionLabel: String = "검색") {
            cards()
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            val nodes = frame.allNodes.flatMap { child ->
                if (child.id !in setOf(3, 4, 6, 7)) listOf(child)
                else {
                    val detach = detachedTitle && child.id == 3
                    val wrapperPath = if (detach) "0/8" else child.path
                    val wrapper = child.copy(id = child.id + 10, path = wrapperPath,
                        parentPath = if (detach) "0" else child.parentPath, className = "android.widget.LinearLayout",
                        text = null, clickable = false, scrollable = scrollableFooter && child.id == 4)
                    val control = child.copy(path = "$wrapperPath/0", parentPath = wrapperPath,
                        viewIdResourceName = if (sharedButtonResource && child.id in setOf(4, 7)) "public:id/open" else child.viewIdResourceName,
                        heading = child.id == 3 && firstHeading,
                        enabled = child.id != 4 || firstButtonEnabled,
                        stateDescription = when (child.id) { 4 -> firstNotice; 7 -> secondNotice; else -> null })
                    if (secondaryRoute && child.id in setOf(4, 7)) {
                        val midpoint = (child.bounds.top + child.bounds.bottom) / 2
                        listOf(wrapper, control.copy(bounds = child.bounds.copy(bottom = midpoint)),
                            control.copy(id = child.id + 20, path = "$wrapperPath/1", text = "보기",
                                viewIdResourceName = "public:id/view", bounds = child.bounds.copy(top = midpoint)))
                    } else listOf(wrapper, control)
                }
            }
            val sourcePane = sourcePaneTitle?.let { node(20, "").copy(path = "0", parentPath = null,
                bounds = requireNotNull(frame.viewport), paneTitle = it, clickable = false) }
            val editor = if (sourceEditor) node(21, "").copy(path = "1", parentPath = null,
                editable = true, hasInput = sourceHasInput, bounds = NodeBounds(30, 740, 450, 805)) else null
            val controls = ((if (unrelatedAction) nodes + node(8, actionLabel).copy(bounds = NodeBounds(10, 610, 490, 680)) else nodes) +
                listOfNotNull(sourcePane, editor))
                .map { it.copy(bounds = it.bounds.copy(left = it.bounds.left + horizontalOffset, right = it.bounds.right + horizontalOffset)) }
            screens.value = state.copy(snapshot = frame.copy(nodes = controls, allNodes = controls))
        }
        fun scopedForm(input: Boolean = false, notice: String? = "문서 이름을 입력해야 열 수 있어요.",
                       buttonEnabled: Boolean = false, otherNotice: String? = null, secondEditor: Boolean = false,
                       secondInput: Boolean = false) {
            nestedCards(firstButtonEnabled = buttonEnabled)
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            fun editor(id: Int, parent: String, top: Int, hint: String, slot: Int = 2): AccessibilityNodeModel {
                val present = id == 8 && input || id == 10 && secondInput
                return node(id, "").copy(path = "$parent/$slot", parentPath = parent,
                    className = "android.widget.EditText", text = null, editable = true, inputHint = hint, hasInput = present,
                    localInputRevision = if (present) 2 else 1, bounds = NodeBounds(10, top, 490, top + 38))
            }
            val nodes = frame.allNodes.map {
                when (it.id) {
                    2 -> it.copy(bounds = it.bounds.copy(bottom = it.bounds.bottom + if (secondEditor) 70 else 30))
                    5 -> it.copy(bounds = it.bounds.copy(bottom = it.bounds.bottom + 30))
                    4 -> it.copy(stateDescription = notice)
                    7 -> it.copy(stateDescription = otherNotice)
                    else -> it
                }
            } + listOf(editor(8, "0/2", 270, "문서 이름"), editor(9, "0/5", 480, "여행 이름")) +
                listOfNotNull(editor(10, "0/2", 310, "분류 코드", 3).takeIf { secondEditor })
            screens.value = state.copy(snapshot = frame.copy(nodes = nodes, allNodes = nodes))
        }
        fun singleSearchResult(title: String) {
            nestedCards(sourcePaneTitle = "검색 결과")
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            val nodes = frame.allNodes.filter { it.id !in setOf(5, 6, 7, 16, 17) }
                .map { if (it.id == 3) it.copy(text = title) else it }
            screens.value = state.copy(snapshot = frame.copy(nodes = nodes, allNodes = nodes))
        }
        fun namedSearchRows(firstTitle: String, secondTitle: String) {
            frame()
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            val nodes = listOf(
                node(20, "").copy(path = "0", parentPath = null, paneTitle = "검색 결과", clickable = false,
                    bounds = requireNotNull(frame.viewport)),
                node(4, firstTitle).copy(path = "0/0", viewIdResourceName = "public:id/result_row", bounds = NodeBounds(30, 200, 450, 280)),
                node(7, secondTitle).copy(path = "0/1", viewIdResourceName = "public:id/result_row", bounds = NodeBounds(30, 290, 450, 370)),
            )
            screens.value = state.copy(snapshot = frame.copy(nodes = nodes, allNodes = nodes))
        }
        fun qualifiedSearchRows(firstQualifier: String, secondQualifier: String) {
            namedSearchRows("회의 자료", "회의 자료")
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            val nodes = frame.allNodes.flatMap { row ->
                val qualifier = when (row.id) { 4 -> firstQualifier; 7 -> secondQualifier; else -> null }
                if (qualifier == null) listOf(row) else {
                    val mid = (row.bounds.top + row.bounds.bottom) / 2
                    val heading = node(row.id + 30, "회의 자료").copy(path = "${row.path}/0", parentPath = row.path,
                        clickableAncestorPath = row.path, clickable = false, className = "android.widget.TextView",
                        bounds = row.bounds.copy(bottom = mid))
                    val detail = heading.copy(id = row.id + 40, path = "${row.path}/1", text = qualifier, bounds = row.bounds.copy(top = mid))
                    listOf(row.copy(text = null, descendantLabels = listOf("회의 자료", qualifier)), heading, detail)
                }
            }
            screens.value = state.copy(snapshot = frame.copy(nodes = nodes, allNodes = nodes))
        }
        fun click(id:Int,type:Int=android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED):PublicActionEvidence {
            val frame=screens.value.snapshot!!;val node=frame.allNodes.single{it.id==id};val at=scope.testScheduler.currentTime
            return PublicActionEvidence(frame.windowId,node.bounds,node.className.orEmpty(),node.viewIdResourceName.orEmpty(),
                eventType=type,eventUptimeMillis=at,receivedUptimeMillis=at,packageName=PKG,sourceEpoch=frame.sourceEpoch)
        }
        fun openedScreen(title: String, heading: Boolean = true, paneTitle: String? = null,
                         duplicateHeadings: Boolean = false, duplicatePanes: Boolean = false, clippedPeer: Boolean = false,
                         returnLabel: String? = null, draft: Boolean = false, paneQualifier: String? = null) {
            frame()
            val state = screens.value
            val frame = requireNotNull(state.snapshot)
            val result = node(2, title).copy(className = "android.widget.TextView", viewIdResourceName = "public:id/result_title",
                heading = heading, clickable = false, paneTitle = paneTitle,
                bounds = if (paneQualifier == null) node(2, title).bounds else NodeBounds(20, 130, 480, 480))
            val duplicates = when {
                duplicateHeadings -> (3..4).map { node(it, "회의 자료").copy(heading = true, clickable = false) }
                duplicatePanes -> listOf(node(3, "").let {
                    it.copy(paneTitle = paneTitle, clickable = false, bounds = if (clippedPeer) it.bounds.copy(right = 520) else it.bounds)
                })
                else -> emptyList()
            }
            val returnButton = returnLabel?.let { node(11, it).copy(path = "0/2/0", parentPath = "0/2",
                bounds = NodeBounds(30, 180, 450, 245)) }
            val editor = if (draft) node(12, "").copy(path = "0/2/1", parentPath = "0/2",
                editable = true, hasInput = true, bounds = NodeBounds(30, 360, 450, 425)) else null
            val qualifier = paneQualifier?.let { node(19, it).copy(path = "0/2/2", parentPath = "0/2", clickable = false,
                className = "android.widget.TextView", bounds = NodeBounds(40, 290, 460, 340)) }
            val nodes = listOf(result) + duplicates + listOfNotNull(returnButton, editor, qualifier)
            screens.value = state.copy(snapshot = frame.copy(nodes = nodes, allNodes = nodes))
        }
        fun frame(input:Boolean=false,tick:String="0",editRevision:Long?=1,focused:Boolean=true,buttonFocused:Boolean?=null,partial:Boolean=false){
            scope.testScheduler.advanceTimeBy(1)
            val captured=scope.testScheduler.currentTime
            val nodes=listOf(node(1,"원하는 값").copy(className="android.widget.EditText",text=null,editable=true,
                inputHint="원하는 값",hasInput=input,focused=focused,localInputRevision=editRevision),node(2,"다음 항목").copy(focused=buttonFocused),node(3,tick).copy(clickable=false),node(4,"둘째 확정"))
            screens.value=AssistUiState(serviceConnected=true,foregroundPackage=PKG,snapshot=ScreenSnapshot(++sequence,PKG,5,1000+sequence,nodes,nodes,partial,NodeBounds(0,0,500,1000),
                captureStartedAtUptimeMillis=captured,captureFinishedAtUptimeMillis=captured,inputObservationVersion=nativeInputVersion))
        }
    }
    companion object {
        private const val PKG="public.task.fixture"
        private const val bothRequiredNotice = "문서 이름과 분류 코드를 모두 입력해야 열 수 있어요."
        private fun node(id:Int,label:String)=AccessibilityNodeModel(id,"0/$id","0","android.widget.Button",label,null,
            "public:id/item$id",true,true,true,false,true,false,NodeBounds(30,id*130,450,id*130+100),emptyList())
        private fun guide()=TaskPlan(NextKind.GUIDE,"현재 관련 작업",GoalKind.STATE,emptyList(),
            listOf(TaskStep("input","값 입력",listOf(1),emptyList()),TaskStep("next","다음 작업",listOf(2),listOf("input"))),
            listOf(TaskRelation(RelationKind.BEFORE,listOf("input","next"))),Operation.INPUT,1,
            "원하는 값을 입력해주세요.",null,WaitKind.NONE,listOf(1,2),emptyList())
        private fun commit()=guide().copy(goalKind=GoalKind.NEW_EVENT,operation=Operation.TAP,targetNodeId=2,effect=ActionEffect.COMMIT,
            conditions=listOf(GoalCondition("prepared","공개 작업 준비",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(2),phase=ConditionPhase.PREPARATION)),
            actionGates=listOf(ActionGate(2,listOf("prepared"))))
        private fun openCard(target: Int, heading: Int) = guide().copy(operation = Operation.TAP, targetNodeId = target,
            tasks = listOf(TaskStep("open", "자료 열기", listOf(target), emptyList())), relations = emptyList(),
            relevantNodeIds = listOf(target), scopeNodeIds = listOf(heading), message = "열기를 눌러주세요.")
        private fun prepareCard(editor: Int) = openCard(4, 3).copy(operation = Operation.INPUT, targetNodeId = editor,
            effect = ActionEffect.PREPARE, message = "이름을 입력해주세요.",
            tasks = listOf(TaskStep("write", "이름 입력", listOf(editor), emptyList()), TaskStep("open", "자료 열기", listOf(4), listOf("write"))),
            relations = listOf(TaskRelation(RelationKind.BEFORE, listOf("write", "open"))),
            conditions = listOf(GoalCondition("ready", "이름 입력 필요", true, Truth.UNSATISFIED, EvidenceKind.OBSERVED,
                listOf(editor, 4), phase = ConditionPhase.PREPARATION,
                expectations = listOf(ValueExpectation(ValueProperty.INPUT_PRESENT,
                    ValueSubject("android.widget.EditText", "public:id/item$editor", ""), expectedBoolean = true)))))
        private fun prepareBoth(editor: Int, firstPresent: Boolean): TaskPlan {
            val first = prepareCard(8)
            val second = prepareCard(10)
            val next = if (editor == 8) first else second
            return next.copy(
                conditions = listOf(first.conditions.single().copy(key = "name", description = "문서 이름 입력",
                    truth = if (firstPresent) Truth.SATISFIED else Truth.UNSATISFIED),
                    second.conditions.single().copy(key = "code", description = "분류 코드 입력")),
                tasks = listOf(first.tasks.first(), second.tasks.first().copy(key = "code"), first.tasks.last()),
                relations = first.relations + TaskRelation(RelationKind.BEFORE, listOf("code", "open")),
            )
        }
        private fun openPreparedCard() = openCard(4, 3).copy(
            conditions = prepareBoth(10, firstPresent = true).conditions.map { it.copy(truth = Truth.SATISFIED) })
        private fun completedOpen(evidenceId: Int) = openCard(4, 3).copy(kind = NextKind.COMPLETE, operation = Operation.NONE,
            targetNodeId = null, tasks = emptyList(), relations = emptyList(), scopeNodeIds = emptyList(), relevantNodeIds = listOf(evidenceId),
            conditions = listOf(GoalCondition("opened", "회의 자료 열림", true, Truth.SATISFIED, EvidenceKind.OBSERVED, listOf(evidenceId))),
            message = "회의 자료가 열렸어요.")
        private fun complete()=guide().copy(kind=NextKind.COMPLETE,goalKind=GoalKind.NEW_EVENT,operation=Operation.NONE,targetNodeId=null,
            conditions=listOf(GoalCondition("created","새 항목",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(3))),
            newEventNodeIds=listOf(3),outcomeNodeIds=listOf(3),commitOutcome=CommitOutcome.SUCCEEDED)
    }
}
