package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.accessibility.TargetResolver
import com.example.aplassistmvp0.state.AssistUiState
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/** One main-thread session owner. Model plans never execute app actions. */
internal class V3GuideEngine(
    private val scope:CoroutineScope,
    private val screens:StateFlow<AssistUiState>,
    private val ownPackage:String,
    private val checkBridge:suspend()->BridgeStatus,
    private val plan:suspend(TaskGoal,ScreenSnapshot,TaskPlan?,String,V3PlanningAttempt)->TaskPlan,
    private val publish:(GuideUiState)->Unit,
    private val highlight:(AccessibilityNodeModel)->Boolean,
    private val clearHighlight:()->Unit,
    private val speak:(String)->Unit,
    private val stopSpeech:()->Unit,
    private val refreshScreen:()->Unit,
    private val trace:(String,Map<String,String>)->Unit={_,_->},
    private val nowUptime:()->Long,
    private val inputObservationVersion:()->Long={screens.value.snapshot?.inputObservationVersion ?:0L},
    private val highlightScoped:(GuideScopedTap)->Boolean={false},
) {
    private val session=TaskSession()
    private var observer:Job?=null
    private var request:Job?=null
    private var displayed:Pair<TaskPlan,ScreenSnapshot>?=null
    private var pendingKey:String?=null
    private var lastKey:String?=null
    private var userSignal=""
    private var paused=false
    private var awaitingNext=false
    private val commits=V3CommitTracker()
    private val commitObservation=V3CommitObservation()
    private var shownAtUptime=0L
    private var identityAnchors=emptyList<AccessibilityNodeModel>()
    private var preparationAnchors=emptyList<AccessibilityNodeModel>()
    private var lastResultObservation:Set<List<Any?>>?=null
    private var resultReads=0
    private var actionObservationFloor:ObservationRef?=null
    private var inputActionReadAfter:Long?=null
    private var inputReadWait:Job?=null
    private var observationWait: Job? = null
    private var requestGeneration=0L
    private data class RepairObservation(
        val ticket:PlanTicket,
        val frame:ScreenSnapshot,
        val scopeProof:GuideScopedTap?,
        val signal:String,
        val canReplanScope:Boolean,
    ) {
        fun matches(live:ScreenSnapshot) =
            V3RepairPolicy.sameObservation(frame,live,scopeProof?.peerButtonPaths.orEmpty()) &&
                (scopeProof==null || scopeProof.current(live)!=null)
    }
    private var repairObservation:RepairObservation?=null
    private val inputs=V3InputCompletion()
    private val inputRecheck=V3InputRecheck()
    private val transitionObservation=V3TransitionObservation()
    private val scopedArrival=V3ScopedArrival()
    private enum class ArrivalStage { RECHECK, READING, MANUAL }
    private data class ArrivalPrompt(
        val title:String, val frame:ScreenSnapshot, val stage:ArrivalStage, val allowsReport:Boolean, val requestedAt:Long=0,
        val awaitingMatch: Boolean = false,
    )
    private var arrivalPrompt:ArrivalPrompt?=null
    private var inputHold:Long?=null
    private var inputHighlight:Pair<Long,com.example.aplassistmvp0.accessibility.NodeBounds>?=null
    private var scopedTap: GuideScopedTap? = null
    private var disabledScope: GuideScopedTap? = null
    private var questionObservationPending=false
    var ui=GuideUiState();private set

    suspend fun start(goal:String,targetPackage:String,label:String,external:GuideGoalRef?=null):Boolean {
        // An external authority must get a real adapter, never be silently dropped.
        if(external!=null)return false
        stop()
        if(!GuidePolicy.isValidTargetPackage(targetPackage) || targetPackage==ownPackage)return false
        val current=session.start(goal.trim(),targetPackage,label)
        emit(GuideUiState(goal=current.request,phase="thinking",message="화면을 확인하고 있어요.",busy=true,
            targetPackageName=targetPackage,targetAppLabel=label))
        val generation=++requestGeneration
        val status=try{checkBridge()}catch(e:CancellationException){throw e}catch(_:Exception){BridgeStatus(false,"AI 연결을 확인해주세요.")}
        if(session.goal!==current || generation!=requestGeneration)return false
        if(!status.ready){pause(status.message);return false}
        observer=scope.launch { screens.collect { observe(it) } }
        refreshScreen();return true
    }

    private fun observe(state:AssistUiState, canReplanScope:Boolean=true) {
        val goal=session.goal ?:return
        if(!state.serviceConnected){onScreenUnavailable("화면 읽기 연결을 확인해주세요.");return}
        val frame=state.snapshot ?:return
        if(state.foregroundPackage!=goal.targetPackage || frame.packageName!=goal.targetPackage)return
        commits.remember(frame)
        if(paused)return
        inputActionReadAfter?.let{after->if(frame.captureStartedAtUptimeMillis?.let{it>=after}!=true || frame.truncated || frame.viewport==null)return}
        actionObservationFloor?.let{floor->
            if(frame.windowId==floor.windowId && frame.sourceEpoch==floor.sourceEpoch && frame.snapshotId<=floor.sequence)return
        }
        if (scopedArrival.awaitingReturn(goal)) {
            if (!inputFresh(frame) || !scopedArrival.confirmReturn(goal, frame)) {
                if (observationWait?.isActive != true && !ui.awaitingControlCompletion) awaitScopedReturn(goal)
                return
            }
            cancelObservationWait()
            lastKey = null
        }
        if (frame.viewport == null || frame.truncated) {
            if (arrivalPrompt?.stage != ArrivalStage.READING) clearArrivalPrompt()
            awaitCompleteObservation(goal)
            return
        }
        commitObservation.current(goal,state)
        if(!inputFresh(frame)){
            if (observationWait?.isActive == true) return
            invalidateRequest();clearGuidance();stopSpeech()
            emit(ui.copy(phase="waiting_screen",busy=false,awaitingControlCompletion=false,message="입력한 내용을 확인하고 있어요."))
            return
        }
        scopedArrival.observeArrival(goal, frame)
        if (observeArrivalPrompt(goal, frame)) return
        cancelObservationWait()
        val displayedReturn = displayed?.first?.takeIf { it.operation == Operation.BACK && ui.decisionSource == "local" }
        if (displayedReturn != null) {
            val wrong = scopedArrival.wrongDestination(goal, frame)
            if (wrong?.draftRisk == true) {
                invalidateRequest()
                stopSpeech()
                identityAnchors = emptyList()
                waitForScopedArrival(displayedReturn, frame, requireNotNull(scopedArrival.ownedTitle(goal)), wrong)
                return
            }
        }
        if(inputs.refresh(goal,frame)){
            invalidateRequest();displayed=null;lastKey=null;inputHold=inputs.unfinished(goal,frame)
            if(showInputHold(frame))return
            pause("입력 화면에서 작성한 내용을 다시 확인해주세요.");return
        }
        repairObservation?.takeUnless { it.matches(frame) }?.let { repair ->
            val meaningfulChange=meaningKey(frame)!=pendingKey
            val retryNotice=repair.canReplanScope && !commits.unresolved() && repair.scopeProof?.publicNoticeChanged(frame)==true
            invalidateRequest();clearGuidance();stopSpeech();displayed=null;awaitingNext=false;lastKey=null
            if(retryNotice){
                replanScope(state,repair.signal,"v3_scoped_notice_replan")
                return
            }
            if(!meaningfulChange){pause("화면 내용이 달라졌어요. 현재 화면에서 다시 확인해주세요.");return}
        }
        if(actionObservationFloor!=null){
            if(inputActionReadAfter!=null)trace("v3_input_post_action_read",mapOf("timed" to "true"))
            actionObservationFloor=null
            inputActionReadAfter=null
            cancelInputReadWait()
        }
        disabledScope?.let { held ->
            if (held.current(frame)?.enabled == false) return
            disabledScope = null
            lastKey = null
        }
        val transitionChanged=transitionObservation.changed(frame)
        val contextChanged=displayed?.second?.let{it.sourceEpoch!=frame.sourceEpoch}==true ||
            scopedTap?.let { it.current(frame) == null } == true ||
            identityAnchors.any{V3PlanGrounding.current(it,frame)==null} || preparationAnchors.any{V3PlanGrounding.current(it,frame)==null}
        if(contextChanged || transitionChanged){
            commitObservation.clear()
            if(transitionChanged)transitionObservation.rebase(frame)else transitionObservation.clear()
            invalidateRequest();clearGuidance();stopSpeech();awaitingNext=false;displayed=null;lastKey=null
            identityAnchors=emptyList();preparationAnchors=emptyList()
            if(transitionChanged)trace("v3_transition_observation_changed",emptyMap())
        }
        val key=meaningKey(frame)
        if(commits.unresolved() && pendingKey==null && resultReads<3 && resultObservation(frame)!=lastResultObservation)lastKey=null
        if(displayed?.second?.windowId!=null && displayed?.second?.windowId!=frame.windowId){
            awaitingNext=false;displayed=null;clearGuidance()
        }
        val current=displayed
        if(current!=null && current.first.kind==NextKind.ASK)return
        if(inputHold!=null){
            if(showInputHold(frame))return
            inputHold=null;inputRecheck.clear();awaitingNext=false;displayed=null;lastKey=null;clearGuidance()
        }
        if(current?.first?.kind==NextKind.WAIT && current.first.waitKind==WaitKind.USER_ACTIVITY && key==lastKey)return
        if(key==pendingKey || key==lastKey)return
        invalidateRequest()
        pendingKey=key
        session.rememberInputs(inputs.progress(goal,frame))
        val ticket=session.issue(ObservationRef(frame.packageName,frame.windowId,frame.snapshotId,frame.capturedAtMillis,frame.sourceEpoch))
        val requestGoal=session.goal!!
        val prior=session.plan
        val signal=userSignal;userSignal=""
        if(commits.unresolved()){resultReads++;lastResultObservation=resultObservation(frame)}
        clearGuidance();stopSpeech()
        emit(ui.copy(phase="thinking",busy=true,currentAction=null,awaitingControlCompletion=false,
            message="화면을 확인하고 있어요."))
        request=scope.launch {
            try {
                delay(240)
                var feedback:V3PlanDiagnostic?=null
                fun repair(attempt:Int,diagnostic:V3PlanDiagnostic?,scopeProof:GuideScopedTap?=null):Boolean {
                    val state=screens.value;val live=state.snapshot
                    val repair=RepairObservation(ticket,frame,scopeProof,signal,canReplanScope)
                    if(!session.current(ticket) || paused || !V3RepairPolicy.allows(attempt,diagnostic,commits.unresolved()) ||
                        !state.serviceConnected || state.foregroundPackage!=requestGoal.targetPackage || live==null ||
                        !repair.matches(live))return false
                    feedback=diagnostic
                    repairObservation=repair
                    trace("v3_plan_repair",mapOf("attempt" to "${attempt+1}","source" to diagnostic!!.source.name,"reason" to diagnostic.reason))
                    emit(ui.copy(phase="thinking",busy=true,currentAction=null,message="안내를 다시 살펴보고 있어요."))
                    return true
                }
                for(attempt in 0..V3RepairPolicy.MAX_REPAIRS){
                if(!session.current(ticket) || paused)return@launch
                if(attempt>0 && screens.value.snapshot?.let{repairObservation?.matches(it)}!=true){
                    session.invalidate();pendingKey=null;pause("화면 내용이 달라졌어요. 현재 화면에서 다시 확인해주세요.");return@launch
                }
                val proposal=try{plan(requestGoal,frame,prior,signal,V3PlanningAttempt(ticket,attempt,feedback))}
                    catch(error:V3PlanFailure){
                        if(!session.current(ticket) || paused)return@launch
                        trace("v3_request_rejected",mapOf("reason" to error.kind.name))
                        if(error.kind==V3PlanFailureKind.INVALID_RESPONSE && repair(attempt,error.diagnostic))continue
                        session.invalidate();pendingKey=null;pause(error.userMessage);return@launch
                    }
                if(!session.current(ticket) || paused)return@launch
                val stateNow=screens.value
                val live=stateNow.snapshot
                if(live==null || !stateNow.serviceConnected || stateNow.foregroundPackage!=requestGoal.targetPackage || live.packageName!=frame.packageName ||
                    live.windowId!=frame.windowId || live.sourceEpoch!=frame.sourceEpoch || live.truncated || meaningKey(live)!=key){
                    session.invalidate();pendingKey=null;refreshScreen();return@launch
                }
                if(repairObservation?.matches(live)==false){
                    observe(stateNow)
                    return@launch
                }
                val problem=TaskPlanValidation.problem(proposal,requestGoal,frame.allNodes.map{it.id}.toSet()) ?: when {
                    proposal.kind == NextKind.GUIDE && proposal.operation == Operation.TAP &&
                        frame.allNodes.none { it.id == proposal.targetNodeId && it.clickable } -> "tap_target_not_clickable"
                    else -> null
                }
                if(problem!=null){
                    trace("v3_plan_rejected",mapOf("reason" to problem))
                    if(repair(attempt,V3PlanDiagnostic(V3DiagnosticSource.VALIDATION,problem)))continue
                    session.invalidate();pendingKey=null
                    pause("안내 계획의 근거를 확인하지 못했어요. 다시 확인해주세요.");return@launch
                }
                val bound=V3PlanGrounding.bind(proposal,frame,live)
                if(bound==null){
                    session.invalidate();pendingKey=null
                    if(commits.unresolved()){
                        if(resultReads<3){lastKey=null;scope.launch{yield();observe(screens.value)}}else showPendingResult()
                        return@launch
                    }
                    if(canReplanScope && V3ScopedPreparation.review(requestGoal,proposal,frame)?.let{
                            it.inputVerified && it.scope.publicNoticeChanged(live)
                        }==true){
                        replanScope(stateNow,signal,"v3_scoped_notice_replan")
                        return@launch
                    }
                    pause("화면 내용이 달라졌어요. 현재 화면에서 다시 확인해주세요.");return@launch
                }
                val review=session.review(bound)
                if(review.problem!=null){
                    if(repair(attempt,V3PlanDiagnostic(V3DiagnosticSource.SESSION,review.problem)))continue
                    session.invalidate();pendingKey=null;pause("목표 조건을 다시 확인해주세요.");return@launch
                }
                val candidate=session.goal!!.copy(requirements=review.requirements)
                val preparation=V3PreparationPolicy.review(candidate,review.plan!!,live)
                if(preparation.problem!=null){
                    trace("v3_preparation_blocked",mapOf("reason" to preparation.problem))
                    if(repair(attempt,V3PlanDiagnostic(V3DiagnosticSource.PREPARATION,preparation.problem)))continue
                    session.invalidate();pendingKey=null;pause("진행하기 전에 아직 확인할 내용이 있어요. 다시 확인해주세요.");return@launch
                }
                val proposedAction = preparation.plan
                if (!commits.unresolved()) {
                    val repeatedNavigation = scopedArrival.repeatsBlockedNavigation(candidate, proposedAction, live)
                    val noAlternative = feedback?.reason == V3PlanDiagnostic.REPEATED_WRONG_NAVIGATION &&
                        proposedAction.kind == NextKind.RECOVER && proposedAction.operation == Operation.NONE && proposedAction.targetNodeId == null
                    if (repeatedNavigation || noAlternative) {
                        if (repeatedNavigation && attempt == 0) {
                            val blocked = scopedArrival.blockedNodeIds(requestGoal, frame)
                            if (repair(attempt, V3PlanDiagnostic(V3DiagnosticSource.VALIDATION,
                                    V3PlanDiagnostic.REPEATED_WRONG_NAVIGATION, blocked))) continue
                        }
                        session.invalidate(); pendingKey = null
                        pause("같은 버튼에서 다른 항목이 두 번 열렸어요. 이 화면에서 다른 방법을 찾지 못했어요.")
                        return@launch
                    }
                    val arrivalToVerify = when {
                        proposedAction.kind == NextKind.COMPLETE -> scopedArrival.unverifiedTitle(candidate, proposedAction, live)
                        scopedArrival.requestsReturn(proposedAction,live) ->
                            scopedArrival.ownedTitle(candidate)
                        else -> null
                    }
                    arrivalToVerify?.let { title ->
                        val wrong=scopedArrival.wrongDestination(candidate,live)
                        session.invalidate(); pendingKey = null
                        val back=wrong?.returnButton
                        if (back == null) waitForScopedArrival(proposedAction, live, title, wrong,
                            awaitingMatch = scopedArrival.hasDuplicateResultTitle(candidate, live))
                        else showScopedReturn(candidate, proposedAction, live, wrong, back)
                        return@launch
                    }
                }
                val scoped = scopedNavigation(candidate, proposedAction, live)
                if (scoped == null && proposedAction.kind == NextKind.GUIDE &&
                    (scopedArrival.continuesNamedControl(candidate, live, proposedAction.targetNodeId) ||
                        scopedArrival.isNamedResultRow(candidate, live, proposedAction.targetNodeId)) && !commits.unresolved()) {
                    session.invalidate(); pendingKey = null
                    waitForScopedArrival(proposedAction, live, requireNotNull(scopedArrival.ownedTitle(candidate)), awaitingMatch = true)
                    return@launch
                }
                val scopedInput = V3ScopedPreparation.review(candidate, proposedAction, live)
                val previousScope = repairObservation?.scopeProof
                val repairingRequiredInputs = feedback?.reason == V3ScopedPreparation.MISSING_REQUIRED_INPUTS
                val missingInputs = scopedInput?.problem
                if (missingInputs != null && repair(attempt, V3PlanDiagnostic(V3DiagnosticSource.PREPARATION, missingInputs), scopedInput.scope)) {
                    continue
                }
                if (scoped?.revealedFromClippedTitle(frame) == true && !commits.unresolved()) {
                    session.invalidate(); pendingKey = null
                    if (canReplanScope) replanScope(stateNow,signal,"v3_scoped_title_replan")
                    else pause("요청하신 항목의 버튼을 다시 확인해주세요.")
                    return@launch
                }
                val mismatchedInputRepair = repairingRequiredInputs &&
                    (scopedInput?.inputVerified != true || previousScope?.current(live) !== scopedInput.scope.node)
                val waitingScope = previousScope?.takeIf { mismatchedInputRepair }
                    ?: scoped?.takeUnless { it.node.enabled } ?: scopedInput?.takeUnless { it.inputVerified }?.scope
                if (waitingScope != null) {
                    session.invalidate(); pendingKey = null
                    if (commits.unresolved()) showPendingResult() else waitForScopedControl(waitingScope)
                    return@launch
                }
                val scopeConfirmed = scoped?.confirms(proposedAction.targetNodeId, proposedAction.scopeNodeIds) == true
                if (previousScope != null && !repairingRequiredInputs) {
                    if (!scopeConfirmed || previousScope.current(live) !== scoped.node) {
                        session.invalidate(); pendingKey = null
                        pause("요청하신 항목의 버튼을 다시 확인해주세요."); return@launch
                    }
                } else if (scoped != null && !scopeConfirmed) {
                    val originalScope = if (frame === live) scoped else scopedNavigation(requestGoal, proposal, frame)
                    if (originalScope?.current(live) === scoped.node &&
                        repair(attempt, V3PlanDiagnostic(V3DiagnosticSource.VALIDATION, "scoped_navigation_mismatch"), originalScope)) {
                        continue
                    }
                    session.invalidate(); pendingKey = null
                    pause("요청하신 항목의 버튼을 다시 확인해주세요."); return@launch
                }
                if (scopeConfirmed && scoped.node.enabled && !commits.unresolved()) {
                    val unfinished = inputs.resumeBeforeScopedNavigation(candidate, live, scoped)
                    if (unfinished != null) {
                        session.invalidate(); pendingKey = null; inputHold = unfinished; displayed = null
                        trace("v3_scoped_navigation_input_pending", emptyMap())
                        if (showInputHold(live)) speak(ui.message)
                        else pause("입력 화면에서 작성한 내용을 다시 확인해주세요.")
                        return@launch
                    }
                }
                if(preparation.plan.effect==ActionEffect.COMMIT || preparation.plan.kind==NextKind.COMPLETE){
                    val unfinished=inputs.blocking(candidate,preparation.plan,live,preparation.bindings)
                    if(unfinished!=null){
                        session.invalidate();pendingKey=null;inputHold=unfinished;displayed=null
                        trace("v3_input_completion_blocked",mapOf("reason" to "unfinished_or_changed_input"))
                        if(!showInputHold(live))pause("입력 화면에서 작성한 내용을 다시 확인해주세요.")
                        return@launch
                    }
                }
                if(!session.accept(ticket,bound)){
                    pendingKey=null;pause("목표 조건을 다시 확인해주세요.");return@launch
                }
                val accepted=session.plan!!
                pendingKey=null
                identityAnchors=accepted.scopeNodeIds.mapNotNull{id->live.allNodes.singleOrNull{it.id==id}}
                session.rememberScope(identityAnchors.map{n->TaskScopeObservation(n.path,n.className.orEmpty(),GuidePolicy.label(n),n.viewIdResourceName.orEmpty(),
                    ObservationRef(live.packageName,live.windowId,live.snapshotId,live.capturedAtMillis,live.sourceEpoch))})
                lastKey=meaningKey(live)
                apply(accepted,live,live,signal,scoped,scopedInput?.scope)
                if(commits.unresolved() && resultReads<3 && resultObservation(live)!=lastResultObservation)
                    scope.launch{yield();observe(screens.value)}
                return@launch
                }
            }catch(_:CancellationException){
                // Cancellation never turns into an old plan or a recovered UI.
            }catch(error:V3PlanFailure){
                if(session.current(ticket)){
                    trace("v3_request_rejected",mapOf("reason" to error.kind.name))
                    session.invalidate();pendingKey=null;pause(error.userMessage)
                }
            }catch(_:Exception){
                if(session.current(ticket)){session.invalidate();pendingKey=null;pause("AI 연결을 확인하고 다시 시도해주세요.")}
            }finally{
                if(repairObservation?.ticket===ticket)repairObservation=null
            }
        }
    }

    private fun replanScope(state:AssistUiState,signal:String,reason:String) {
        trace(reason,emptyMap())
        lastKey=null
        userSignal=signal
        observe(state,canReplanScope=false)
    }

    private fun scopedNavigation(goal: TaskGoal, plan: TaskPlan, frame: ScreenSnapshot): GuideScopedTap? {
        val id = plan.targetNodeId ?: return null
        if (plan.kind != NextKind.GUIDE || plan.operation != Operation.TAP || plan.effect != ActionEffect.NONE ||
            !plan.groundingComplete || goal.requiresNewEvent || plan.goalKind !in setOf(GoalKind.STATE, GoalKind.INSPECT)) return null
        if (scopedArrival.isNamedResultRow(goal, frame, id)) {
            return scopedArrival.namedRowScope(goal, frame)
        }
        return GuideScopedTap.find(frame, id, goal.request, allowSingleControl = scopedArrival.continuesNamedControl(goal, frame, id))
    }

    private fun apply(proposal:TaskPlan,requested:ScreenSnapshot,live:ScreenSnapshot,signal:String,
                      scopedCandidate:GuideScopedTap?,scopedInput:GuideScopedTap?) {
        clearArrivalPrompt()
        commitObservation.clear()
        val goal=session.goal ?:return
        val preparation=V3PreparationPolicy.review(goal,proposal,live)
        session.rememberPreparations(preparation.bindings)
        preparationAnchors=preparation.evidenceNodeIds.mapNotNull{id->live.allNodes.singleOrNull{it.id==id}}
        if(preparation.problem!=null){
            trace("v3_preparation_blocked",mapOf("reason" to preparation.problem))
            displayed=null;awaitingNext=false
            pause("진행하기 전에 아직 확인할 내용이 있어요. 다시 확인해주세요.");return
        }
        val value=preparation.plan
        transitionObservation.begin(value,live)
        inputRecheck.clear()
        displayed=value to requested
        commits.observe(goal,value,live)
        session.rememberOperations(commits.history())
        when(value.kind){
            NextKind.GUIDE->{
                val gate=commits.gate(value,live)
                if(gate!=V3CommitTracker.Gate.ALLOW){showCommitGate(gate);return}
                val original=requested.allNodes.singleOrNull{it.id==value.targetNodeId}
                val unambiguous=original?.let{TargetResolver.resolve(it,live.allNodes,GuidePolicy.label(it))}
                    ?.takeIf{GuidePolicy.resolveDecisionNode(live,it.id)===it}
                val scoped = scopedCandidate?.takeIf { value.effect == ActionEffect.NONE }
                val node = scoped?.node ?: unambiguous
                if(node==null){pause("표시할 항목을 현재 화면에서 다시 확인해주세요.");return}
                awaitingNext=value.operation==Operation.INPUT
                inputHold=if(awaitingNext)inputs.begin(goal,value,live,node,scopedInput)else null
                if(awaitingNext && inputHold==null){pause("입력할 항목을 현재 화면에서 다시 확인해주세요.");return}
                val writing=inputHold?.let{inputs.hold(it,goal,live,inputFresh(live))}
                val instruction = scoped?.instruction ?: value.message
                emit(ui.copy(phase="guiding",busy=false,step=ui.step+1,message=instruction,
                    currentAction="highlight",decisionSource="v3_plan",awaitingControlCompletion=awaitingNext,
                    controlCompletionEnabled=writing?.canFinish==true,
                    controlCompletionLabel=writing?.completionLabel ?: "다음 안내"))
                val reserved = if (scoped != null) highlightScoped(scoped) else highlight(node)
                if(!reserved){pause("항목이 이동했어요. 현재 화면에서 다시 확인해주세요.");return}
                scopedTap = scoped
                if(inputHold!=null)inputHighlight=inputHold!! to node.bounds
                shownAtUptime=nowUptime()
                if (scoped != null) {
                    scopedArrival.show(goal, scoped, live, shownAtUptime)
                    lastKey = meaningKey(live)
                }
                commitObservation.show(goal,value,live,shownAtUptime)
                inputHold?.let{inputRecheck.show(goal,it,live,shownAtUptime)}
                speak(instruction)
            }
            NextKind.ASK->{
                questionObservationPending=false
                emit(ui.copy(phase="guiding",busy=false,message=value.question!!.message,currentAction="ask",
                    awaitingControlCompletion=true,controlCompletionEnabled=true,controlCompletionLabel="답하기"))
                speak(value.question.message)
            }
            NextKind.WAIT->{
                emit(ui.copy(phase="guiding",busy=false,message=value.message,currentAction="wait",
                    awaitingControlCompletion=value.waitKind==WaitKind.USER_INPUT || value.waitKind==WaitKind.USER_ACTIVITY,
                    controlCompletionEnabled=true,controlCompletionLabel="다음 안내"));speak(value.message)
            }
            NextKind.COMPLETE->{
                if(commits.unresolved() || (goal.requiresNewEvent || value.goalKind==GoalKind.NEW_EVENT) && !commits.canComplete(goal,value,live)){
                    showPendingResult();return
                }
                finishGuide(value.message)
            }
            NextKind.OBSERVE,NextKind.RECOVER->{
                emit(ui.copy(phase="guiding",busy=false,message=value.message,currentAction="manual",
                    awaitingControlCompletion=true,controlCompletionEnabled=true,controlCompletionLabel="다시 확인"));speak(value.message)
            }
        }
        trace("v3_plan_applied",mapOf("kind" to value.kind.name,"tasks" to value.tasks.size.toString(),
            "conditions" to value.conditions.size.toString(),"relations" to value.relations.size.toString(),"signal" to signal))
    }

    fun answer(key:String,text:String):Boolean {
        if(paused || questionObservationPending || !ui.showsScopedQuestion || displayed?.first?.kind!=NextKind.ASK)return false
        if (screens.value.snapshot?.let { it.truncated || it.viewport == null } == true) return false
        if(!session.answer(key,text))return false
        commitObservation.clear()
        inputs.invalidate()
        inputRecheck.clear()
        transitionObservation.clear()
        invalidateRequest();displayed=null;lastKey=null;awaitingNext=false;inputHold=null
        userSignal="question_answered";refreshScreen();observe(screens.value);return true
    }
    fun question()=session.question
    fun deferOwnQuestionObservation():Boolean {
        if(paused || !ui.showsScopedQuestion || displayed?.first?.kind!=NextKind.ASK)return false
        questionObservationPending=true;invalidateRequest()
        trace("v3_question_observation_deferred",emptyMap());return true
    }
    fun expireQuestionObservation(question:PlanQuestion):Boolean {
        if(!questionObservationPending || session.question!==question || !ui.showsScopedQuestion)return false
        onScreenUnavailable("화면을 확인하지 못했어요. 현재 화면에서 다시 확인해주세요.");return true
    }
    fun confirmControl():Boolean {
        if(paused || ui.busy || !ui.awaitingControlCompletion || !ui.controlCompletionEnabled)return false
        val state=screens.value;val frame=state.snapshot;val goal=session.goal ?:return false
        if (scopedArrival.awaitingReturn(goal)) {
            if (!state.serviceConnected || state.foregroundPackage != goal.targetPackage) return false
            awaitScopedReturn(goal)
            return true
        }
        if (frame?.let { it.truncated || it.viewport == null } == true) return false
        arrivalPrompt?.let {
            if (frame==null || !state.serviceConnected || state.foregroundPackage!=goal.targetPackage ||
                frame.packageName!=goal.targetPackage || frame.blockingPopup) return false
            return confirmArrivalPrompt(it, goal, frame)
        }
        val holding=inputHold
        if(holding!=null){
            if(frame==null || !state.serviceConnected || state.foregroundPackage!=goal.targetPackage ||
                !inputs.finish(holding,goal,frame,inputFresh(frame)))return false
        }else{
            val current=displayed?.first ?:return false
            if(current.kind==NextKind.ASK)return false
        }
        userSignal=if(holding!=null)"user_finished_input"else "user_requested_next"
        inputRecheck.clear()
        inputHold=null;awaitingNext=false;displayed=null;lastKey=null
        resultReads=0
        refreshScreen();observe(screens.value);return true
    }
    fun confirmCompleted():Boolean { userSignal="user_requested_result_check";lastKey=null;observe(screens.value);return false }
    fun onUserAction(packageName:String,scrolled:Boolean=false,nodeId:Int?=null,direction:ScrollDirection?=null,evidence:PublicActionEvidence?=null) {
        val goal=session.goal ?:return
        val active=screens.value
        if(packageName!=goal.targetPackage || paused || !active.serviceConnected || active.foregroundPackage!=goal.targetPackage)return
        if (scopedArrival.clickReturn(goal, evidence, nowUptime())) {
            awaitScopedReturn(goal)
            return
        }
        val hadArrivalPrompt=arrivalPrompt!=null
        clearArrivalPrompt()
        scopedArrival.click(goal,evidence,active.snapshot,nowUptime())
        if (active.snapshot?.let { it.truncated || it.viewport == null } == true) {
            val candidate = commitObservation.forUncertainInteraction(goal, active)
            if (candidate != null && commits.recordPossible(goal, candidate.plan, candidate.before, evidence, candidate.shownAt, nowUptime())) {
                commitObservation.clear()
                resultReads = 0
                lastResultObservation = null
                session.rememberOperations(commits.history())
                trace("v3_possible_commit_interaction", mapOf("outcome" to "unknown", "instruction" to "do_not_repeat"))
            }
            return
        }
        if(inputRecheck.alreadyObserved(evidence))return
        screens.value.snapshot?.takeIf{it.packageName==packageName}?.let(commits::remember)
        val current=displayed
        val retained=if(current==null && (ui.busy || observationWait?.isActive==true))commitObservation.current(goal,active)else null
        val classification=current ?:retained?.let{it.plan to it.before}
        val classifiedAt=retained?.shownAt ?:shownAtUptime
        if(current==null && evidence?.eventType==android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            trace("v3_native_event_unbound",mapOf("phase" to ui.phase))
        if(classification!=null && commits.record(goal,classification.first,classification.second,evidence,classifiedAt,nowUptime())) {
            commitObservation.clear()
            resultReads=0;lastResultObservation=null
            trace("v3_commit_interaction",mapOf("source" to if(retained!=null)"native_click_during_replanning"else "native_click","outcome" to "pending"))
        } else if(classification!=null && commits.recordPossible(goal,classification.first,classification.second,evidence,classifiedAt,nowUptime())) {
            commitObservation.clear()
            resultReads=0;lastResultObservation=null
            trace("v3_possible_commit_interaction",mapOf("outcome" to "unknown","instruction" to "do_not_repeat"))
        } else if(current?.first?.effect==ActionEffect.COMMIT && evidence?.eventType==android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) {
            trace("v3_commit_click_rejected",mapOf("reason" to (V3InteractionEvidence.rejection(evidence,current.second,shownAtUptime,nowUptime())
                ?:if(commits.unresolved())"already_pending"else "different_target"),
                "event_role" to evidence.role,"event_bounds" to evidence.bounds.toString(),"event_resource" to evidence.viewId,
                "expected_role" to current.second.allNodes.singleOrNull{it.id==current.first.targetNodeId}?.className.orEmpty(),
                "expected_bounds" to current.second.allNodes.singleOrNull{it.id==current.first.targetNodeId}?.bounds.toString(),
                "expected_resource" to current.second.allNodes.singleOrNull{it.id==current.first.targetNodeId}?.viewIdResourceName.orEmpty()))
        }
        session.rememberOperations(commits.history())
        if(awaitingNext){
            if(paused || ui.busy)return
            // The old INPUT plan may have been cleared by typing. Its public observation lease is separate.
            val after=inputRecheck.afterClick(session.goal!!,screens.value,evidence,nowUptime()) ?:return
            inputActionReadAfter=after;inputRecheck.clear()
            awaitPostInputActionRead()
            awaitingNext=false;inputHold=null;displayed=null
            clearGuidance();stopSpeech()
            emit(ui.copy(phase="waiting_screen",busy=false,currentAction=null,awaitingControlCompletion=false,
                controlCompletionEnabled=false,message="바뀐 화면을 확인하고 있어요."))
            trace("v3_input_native_recheck",mapOf("writing_completion" to "not_inferred"))
        }
        transitionObservation.clear()
        if (scopedTap != null || hadArrivalPrompt || current?.first?.operation == Operation.BACK) {
            displayed = null
            clearGuidance(); stopSpeech()
            emit(ui.copy(phase = "waiting_screen", busy = false, currentAction = null, message = "바뀐 화면을 확인하고 있어요.",
                awaitingControlCompletion=false,controlCompletionEnabled=false))
        }
        invalidateRequest()
        screens.value.snapshot?.takeIf{it.packageName==packageName}?.let{
            actionObservationFloor=ObservationRef(it.packageName,it.windowId,it.snapshotId,it.capturedAtMillis,it.sourceEpoch)
        }
        lastKey=null
        userSignal=if(scrolled)"user_scrolled" else if(evidence?.eventType==android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            "native_app_control_clicked" else "user_acted"
        refreshScreen()
    }
    fun onForegroundChanged(packageName:String?) {
        if(questionObservationPending && packageName==session.goal?.targetPackage && packageName!=null)
            trace("v3_question_observation_restored",emptyMap())
        session.goal?.let{questionObservationPending=packageName!=it.targetPackage}
        if(packageName==session.goal?.targetPackage && !paused && inputActionReadAfter!=null)awaitPostInputActionRead()
        if(session.goal!=null && packageName!=session.goal?.targetPackage){
            clearArrivalPrompt()
            if(packageName!=null)scopedArrival.interrupt()
            commitObservation.clear()
            cancelInputReadWait()
            cancelObservationWait()
            trace("v3_foreground_invalidated",mapOf("reason" to if(packageName==null)"unresolved_root"else "other_package"))
            invalidateRequest();clearGuidance();stopSpeech();displayed=null;lastKey=null;awaitingNext=false
            preparationAnchors=emptyList();inputHold=null;inputs.invalidate()
            inputRecheck.clear()
            transitionObservation.clear()
            emit(ui.copy(phase=if(paused)ui.phase else "waiting_screen",busy=false,currentAction=null,
                awaitingControlCompletion=false,controlCompletionEnabled=false,
                message=if(paused)ui.message else "안내하던 화면으로 돌아오면 다시 확인할게요."))
        }
    }
    fun onScreenUnavailable(reason:String){clearArrivalPrompt();commitObservation.clear();cancelInputReadWait();cancelObservationWait();transitionObservation.clear();scopedArrival.interrupt();inputs.invalidate();inputHold=null;inputRecheck.clear();invalidateRequest();clearGuidance();stopSpeech();paused=true;emit(ui.copy(phase="paused",busy=false,currentAction=null,message=reason,awaitingControlCompletion=false,controlCompletionEnabled=false))}
    suspend fun retryFromCurrent(rejected:Boolean=false):Boolean {
        val goal=session.goal ?:return false
        clearArrivalPrompt()
        cancelObservationWait()
        invalidateRequest()
        val generation=requestGeneration
        val status=checkBridge()
        if(session.goal!==goal || generation!=requestGeneration)return false
        if(!status.ready){pause(status.message);return false}
        paused=false;lastKey=null;displayed=null;awaitingNext=false;inputHold=null;disabledScope=null
        resultReads=0
        userSignal=if(rejected)"user_rejected_guidance" else "user_requested_recheck"
        if (scopedArrival.awaitingReturn(goal) && screens.value.foregroundPackage == goal.targetPackage) awaitScopedReturn(goal)
        if(inputActionReadAfter!=null){
            emit(ui.copy(phase="waiting_screen",busy=false,currentAction=null,awaitingControlCompletion=false,
                controlCompletionEnabled=false,message="바뀐 화면을 확인하고 있어요."))
            awaitPostInputActionRead()
        }
        if(observer?.isActive!=true)observer=scope.launch{screens.collect{observe(it)}}
        observe(screens.value);refreshScreen();return true
    }
    fun reportAssistantStartFailure(goal:String,message:String){stop();emit(GuideUiState(goal=goal,phase="error",message=message))}
    fun showVisionProgress(){ /* The vision adapter is a separate, explicitly validated boundary. */ }
    fun shouldIgnoreScroll(evidence:PublicActionEvidence)=false
    fun onInputChanged(packageName:String,windowId:Int){
        val frame=screens.value.snapshot ?:return
        if(paused || packageName!=session.goal?.targetPackage || windowId!=frame.windowId)return
        clearArrivalPrompt()
        commitObservation.clear()
        if (observationWait?.isActive == true) return
        transitionObservation.clear();invalidateRequest();clearGuidance();stopSpeech();lastKey=null;displayed=null
        emit(ui.copy(phase="waiting_screen",busy=false,currentAction=null,awaitingControlCompletion=false,controlCompletionEnabled=false,message="입력한 내용을 확인하고 있어요."))
        refreshScreen()
    }
    fun stop(){clearArrivalPrompt();commitObservation.clear();scopedArrival.clear();cancelInputReadWait();cancelObservationWait();invalidateRequest();observer?.cancel();observer=null;session.close();displayed=null;lastKey=null;pendingKey=null;
        awaitingNext=false;paused=false;questionObservationPending=false;commits.reset();inputs.clear();inputRecheck.reset();transitionObservation.clear();inputHold=null;identityAnchors=emptyList();preparationAnchors=emptyList();shownAtUptime=0;resultReads=0;lastResultObservation=null;actionObservationFloor=null;inputActionReadAfter=null;clearGuidance();stopSpeech();emit(GuideUiState())}
    private fun clearGuidance(){inputHighlight=null;scopedTap=null;disabledScope=null;scopedArrival.withdrawReturn();clearHighlight()}
    private fun showScopedReturn(goal:TaskGoal,plan:TaskPlan,frame:ScreenSnapshot,wrong:V3ScopedArrival.WrongDestination,button:AccessibilityNodeModel) {
        clearArrivalPrompt()
        transitionObservation.clear()
        val location = if (wrong.repeated) "‘${wrong.title}’가 다시 열렸어요." else "‘${wrong.title}’ 화면이에요."
        val message = "$location ‘${GuidePolicy.label(button)}’를 눌러주세요."
        displayed=plan.copy(kind=NextKind.GUIDE,operation=Operation.BACK,targetNodeId=button.id,effect=ActionEffect.NONE,
            conditions=emptyList(),scopeNodeIds=listOf(wrong.pane.id),relevantNodeIds=listOf(button.id),message=message) to frame
        identityAnchors=listOf(wrong.pane,button)
        lastKey=meaningKey(frame)
        if (!highlight(button)) {
            displayed=null
            pause("돌아갈 버튼을 화면에서 다시 확인해주세요.")
            return
        }
        scopedArrival.offerReturn(goal, frame, wrong, button, nowUptime())
        emit(ui.copy(phase="guiding",busy=false,currentAction="highlight",message=message,decisionSource="local",
            awaitingControlCompletion=false,controlCompletionEnabled=false))
        speak(message)
    }
    private fun awaitScopedReturn(goal: TaskGoal) {
        clearArrivalPrompt()
        cancelObservationWait()
        invalidateRequest()
        displayed = null
        identityAnchors = emptyList()
        actionObservationFloor = null
        transitionObservation.clear()
        clearGuidance()
        stopSpeech()
        emit(ui.copy(phase = "waiting_screen", busy = false, currentAction = null,
            message = "이전 화면이 열렸는지 확인하고 있어요.", decisionSource = "local",
            awaitingControlCompletion = false, controlCompletionEnabled = false))
        fun stillWaiting(): Boolean = session.goal?.let(scopedArrival::awaitingReturn) == true &&
            !paused && screens.value.serviceConnected && screens.value.foregroundPackage == goal.targetPackage
        observationWait = scope.launch {
            delay(400)
            if (!stillWaiting()) return@launch
            refreshScreen()
            delay(1_600)
            if (!stillWaiting()) return@launch
            observationWait = null
            val message = "이전 화면이 열렸는지 확인하지 못했어요."
            emit(ui.copy(phase = "guiding", currentAction = "wait", message = message,
                awaitingControlCompletion = true, controlCompletionEnabled = true, controlCompletionLabel = "다시 확인"))
            speak(message)
        }
        refreshScreen()
    }
    private fun waitForScopedArrival(plan:TaskPlan, frame:ScreenSnapshot, title:String,
                                     wrong:V3ScopedArrival.WrongDestination? = null, awaitingMatch: Boolean = false) {
        val message = when {
            wrong != null -> wrongArrivalMessage(wrong, title)
            awaitingMatch && session.goal?.let { scopedArrival.hasDuplicateResultTitle(it, frame) } == true ->
                "‘$title’가 여러 개 보여요. 원하는 항목을 확인해주세요."
            awaitingMatch -> "‘$title’에 해당하는 결과를 아직 확인하지 못했어요."
            else -> "‘$title’가 열렸는지 아직 확인하지 못했어요."
        }
        arrivalPrompt = ArrivalPrompt(title, frame, ArrivalStage.RECHECK,
            allowsReport = !awaitingMatch && wrong==null && plan.conditions.count { it.required && it.phase == ConditionPhase.OUTCOME } == 1,
            awaitingMatch = awaitingMatch)
        val wait = plan.copy(kind = NextKind.WAIT, operation = Operation.NONE, targetNodeId = null,
            waitKind = WaitKind.SCREEN_TRANSITION, message = message,
            relevantNodeIds = (plan.conditions.flatMap { it.evidenceNodeIds } + listOfNotNull(wrong?.pane?.id)).distinct())
        displayed = wait to frame
        lastKey = meaningKey(frame)
        transitionObservation.begin(wait, frame)
        clearGuidance()
        emit(ui.copy(phase = "guiding", busy = false, currentAction = "wait", message = message, decisionSource = "local",
            awaitingControlCompletion = true, controlCompletionEnabled = true, controlCompletionLabel = "다시 확인"))
        speak(message)
    }
    private fun confirmArrivalPrompt(prompt:ArrivalPrompt, goal:TaskGoal, frame:ScreenSnapshot):Boolean {
        if (prompt.stage == ArrivalStage.RECHECK) {
            val reading=prompt.copy(frame=frame,stage=ArrivalStage.READING,requestedAt=nowUptime())
            invalidateRequest(); transitionObservation.clear(); displayed=null
            arrivalPrompt=reading
            emit(ui.copy(phase="waiting_screen", currentAction=null, message="결과 화면을 다시 보고 있어요.",
                awaitingControlCompletion=false, controlCompletionEnabled=false))
            observationWait=scope.launch {
                delay(2_000)
                if (arrivalPrompt !== reading) return@launch
                observationWait=null
                arrivalPrompt=null
                showArrivalManual(reading.title, canReport=false, awaitingMatch=reading.awaitingMatch)
            }
            refreshScreen()
            return true
        }
        if (prompt.stage != ArrivalStage.MANUAL || !prompt.allowsReport || commits.unresolved() ||
            !V3RepairPolicy.sameObservation(prompt.frame,frame) || !scopedArrival.canReport(goal,frame) ||
            scopedArrival.resultEvidence(frame).isNotEmpty()) return false
        trace("v3_scoped_arrival_user_confirmed",emptyMap())
        finishGuide("확인해주셔서 안내를 마칠게요.","user_confirmed_result","user")
        return true
    }
    private fun observeArrivalPrompt(goal:TaskGoal,frame:ScreenSnapshot):Boolean {
        val prompt=arrivalPrompt ?:return false
        if (prompt.stage == ArrivalStage.RECHECK) return false
        val matchAvailable = prompt.awaitingMatch && scopedArrival.continuationPath(goal, frame) != null
        if (prompt.stage == ArrivalStage.READING) {
            if (frame.snapshotId <= prompt.frame.snapshotId || frame.captureStartedAtUptimeMillis?.let { it >= prompt.requestedAt } != true)
                return true
            clearArrivalPrompt()
            val wrong=scopedArrival.wrongDestination(goal,frame)
            if (!matchAvailable && scopedArrival.resultEvidence(frame).isEmpty() && wrong?.returnButton==null) {
                val canReport=prompt.allowsReport && !commits.unresolved() && scopedArrival.canReport(goal,frame)
                arrivalPrompt=prompt.copy(frame=frame,stage=ArrivalStage.MANUAL,allowsReport=canReport)
                lastKey=meaningKey(frame)
                showArrivalManual(prompt.title,canReport,wrong,prompt.awaitingMatch)
                return true
            }
        } else {
            if (!matchAvailable && V3RepairPolicy.sameObservation(prompt.frame,frame) && scopedArrival.resultEvidence(frame).isEmpty()) return true
            clearArrivalPrompt()
        }
        lastKey=null
        return false
    }
    private fun wrongArrivalMessage(wrong: V3ScopedArrival.WrongDestination, title: String): String =
        if (wrong.draftRisk) "‘${wrong.title}’ 화면이에요. 작성 중인 내용이 있는지 확인해주세요."
        else "‘${wrong.title}’ 화면이에요. ‘$title’ 화면을 다시 확인해주세요."

    private fun showArrivalManual(title:String,canReport:Boolean,wrong:V3ScopedArrival.WrongDestination?=null,awaitingMatch:Boolean=false) {
        val message=when {
            canReport -> "‘$title’ 화면이 열렸으면 ‘화면에서 확인했어요’를 눌러주세요."
            wrong!=null -> wrongArrivalMessage(wrong, title)
            awaitingMatch -> "화면에서 ‘$title’에 해당하는 결과를 확인해주세요."
            else -> "화면에서 ‘$title’가 열렸는지 확인해주세요."
        }
        emit(ui.copy(phase="guiding", busy=false, currentAction="wait", message=message, decisionSource="local",
            awaitingControlCompletion=canReport, controlCompletionEnabled=canReport, controlCompletionLabel="화면에서 확인했어요"))
        speak(message)
    }
    private fun clearArrivalPrompt() {
        if (arrivalPrompt?.stage == ArrivalStage.READING) cancelObservationWait()
        arrivalPrompt=null
    }
    private fun finishGuide(message:String,action:String="done",source:String?=ui.decisionSource) {
        clearArrivalPrompt()
        emit(ui.copy(phase="done",busy=false,message=message,currentAction=action,decisionSource=source,
            awaitingControlCompletion=false,controlCompletionEnabled=false))
        speak(message); observer?.cancel(); session.close(); displayed=null
        scopedArrival.clear(); commits.reset(); inputs.clear(); inputHold=null
    }
    private fun waitForScopedControl(proof: GuideScopedTap) {
        displayed = null; awaitingNext = false
        transitionObservation.clear()
        clearGuidance()
        disabledScope = proof
        trace("v3_scoped_control_disabled", emptyMap())
        emit(ui.copy(phase = "guiding", busy = false, currentAction = "wait", message = proof.disabledMessage,
            decisionSource = "local", awaitingControlCompletion = false, controlCompletionEnabled = false))
        speak(proof.disabledMessage)
    }
    private fun inputFresh(frame:ScreenSnapshot)=frame.inputObservationVersion==inputObservationVersion()
    private fun showInputHold(frame:ScreenSnapshot):Boolean {
        val goal=session.goal ?:return false
        val hold=inputs.hold(inputHold ?:return false,goal,frame,inputFresh(frame)) ?:return false
        awaitingNext=true
        if(inputHighlight!=hold.id to hold.node.bounds){
            // The service replaces the highlight when reserving a new target. A queued clear would remove that new reservation.
            if(!highlight(hold.node))return false
            inputHighlight=hold.id to hold.node.bounds
        }
        emit(ui.copy(phase="guiding",busy=false,currentAction="highlight",message=hold.message,
            awaitingControlCompletion=true,controlCompletionEnabled=hold.canFinish,controlCompletionLabel=hold.completionLabel))
        inputRecheck.show(goal,hold.id,frame,nowUptime())
        return true
    }
    private fun showPendingResult(){
        awaitingNext=false
        clearGuidance()
        emit(ui.copy(phase="guiding",busy=false,message="처리 결과를 먼저 확인해주세요.",currentAction="wait",
            awaitingControlCompletion=true,controlCompletionEnabled=true,controlCompletionLabel="다시 확인"))
        speak(ui.message)
    }
    private fun showCommitGate(gate:V3CommitTracker.Gate){
        if(gate==V3CommitTracker.Gate.UNRESOLVED){showPendingResult();return}
        awaitingNext=false;clearGuidance()
        val message=if(gate==V3CommitTracker.Gate.ALREADY_SUCCEEDED)"이미 처리한 단계예요. 결과를 확인해주세요."
            else "처리할 대상을 다시 확인해주세요."
        emit(ui.copy(phase="guiding",busy=false,message=message,currentAction="wait",
            awaitingControlCompletion=true,controlCompletionEnabled=true,controlCompletionLabel="다시 확인"))
        trace("v3_commit_guidance_blocked",mapOf("reason" to gate.name));speak(message)
    }
    private fun pause(message:String){clearArrivalPrompt();commitObservation.clear();cancelInputReadWait();cancelObservationWait();transitionObservation.clear();paused=true;inputRecheck.clear();clearGuidance();emit(ui.copy(phase="error",busy=false,currentAction=null,message=message,awaitingControlCompletion=false,controlCompletionEnabled=false));speak(message)}

    private fun cancelObservationWait() {
        observationWait?.cancel()
        observationWait = null
    }

    private fun awaitCompleteObservation(goal: TaskGoal) {
        if (observationWait?.isActive == true) return
        invalidateRequest()
        displayed = null
        lastKey = null
        awaitingNext = false
        inputRecheck.clear()
        clearGuidance()
        stopSpeech()
        emit(ui.copy(phase = "waiting_screen", busy = false, currentAction = null,
            awaitingControlCompletion = false, controlCompletionEnabled = false, message = "화면을 다시 확인하고 있어요."))

        fun isCurrentTarget(): Boolean {
            val state = screens.value
            val current = session.goal
            return current?.sessionId == goal.sessionId && current.revision == goal.revision &&
                !paused && state.serviceConnected && state.foregroundPackage == goal.targetPackage
        }

        observationWait = scope.launch {
            delay(400)
            if (!isCurrentTarget()) return@launch
            trace("v3_observation_retry", emptyMap())
            refreshScreen()
            delay(1_600)
            if (!isCurrentTarget()) return@launch
            observationWait = null
            trace("v3_observation_timeout", emptyMap())
            pause("화면 정보를 다 읽지 못했어요. 현재 화면에서 다시 시도해주세요.")
        }
    }
    private fun cancelInputReadWait(){inputReadWait?.cancel();inputReadWait=null}
    private fun awaitPostInputActionRead(){
        if(inputReadWait?.isActive==true)return
        val goal=session.goal ?:return;val after=inputActionReadAfter ?:return
        fun current()=session.goal?.let{it.sessionId==goal.sessionId && it.revision==goal.revision}==true &&
            inputActionReadAfter==after && !paused && screens.value.foregroundPackage==goal.targetPackage
        inputReadWait=scope.launch{
            delay(400)
            if(!current())return@launch
            trace("v3_input_post_action_retry",emptyMap());refreshScreen()
            delay(1600)
            if(!current())return@launch
            inputReadWait=null
            trace("v3_input_post_action_timeout",emptyMap())
            onScreenUnavailable("바뀐 화면을 확인하지 못했어요. 현재 화면에서 다시 확인해주세요.")
        }
    }
    private fun invalidateRequest(){requestGeneration++;repairObservation=null;session.invalidate();request?.cancel();request=null;pendingKey=null}
    private fun emit(value:GuideUiState){
        val observation=displayed?.takeIf{value.phase=="guiding" && !value.busy}?.let{(plan,frame)->
            GuideOverlayEvidence.capture(frame,(plan.relevantNodeIds+plan.scopeNodeIds+plan.outcomeNodeIds+plan.newEventNodeIds+
                plan.conditions.flatMap{it.evidenceNodeIds}+listOfNotNull(plan.targetNodeId)).distinct())
        }
        ui=value.copy(overlayObservation=observation);publish(ui)
    }
    private fun resultObservation(frame:ScreenSnapshot)=frame.allNodes.filter{!it.clickable && !it.editable && !it.scrollable}
        .mapNotNull(V3InteractionEvidence::signature).toSet()
    private fun meaningKey(frame:ScreenSnapshot)=buildString {
        append(frame.packageName);append(':');append(frame.windowId);append(':');append(frame.sourceEpoch);append(':');append(frame.blockingPopup)
        append('|');append(frame.platformActionBarBounds)
        for(n in frame.allNodes.filter{it.visibleToUser && (it.clickable || it.editable || it.scrollable || it.range!=null)}) {
            append('|');append(n.path);append(GuidePolicy.label(n));append(n.enabled);append(n.checked);append(n.selected)
            append(n.hasInput);append(n.contentInvalid);append(n.range?.current)
            append(n.localInputRevision)
        }
        for(anchor in identityAnchors){
            append("|context:");append(anchor.path);append(V3PlanGrounding.current(anchor,frame)!=null)
        }
        for (evidence in scopedArrival.resultEvidence(frame)) {
            append("|scoped-result:"); append(evidence.path); append(evidence.heading)
            append(AccessibilityNodePrivacy.publicPaneTitle(evidence))
        }
        session.goal?.let { scopedArrival.wrongDestination(it,frame) }?.let {
            append("|scoped-wrong:"); append(it.pane.path); append(it.title); append(it.returnButton?.path); append(it.draftRisk)
        }
        session.goal?.let { scopedArrival.continuationPath(it,frame) }?.let {
            append("|scoped-continuation:"); append(it)
        }
    }
}
