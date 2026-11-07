package com.example.aplassistmvp0.guide

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic scenes only. Exports real encoder packets; does not send or perform app actions. */
@RunWith(AndroidJUnit4::class)
class V3PublicPlanProbeTest {
    private data class Scene(val key:String,val request:String,val screen:ScreenSnapshot,val diagnostic:V3PlanDiagnostic?=null,
        val prior:TaskPlan?=null)

    @Test fun blockedNavigationFeedbackContainsOnlyEncodedPublicNodes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3PublicProbe") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("qaProbeRun"))
        require(Regex("[A-Za-z0-9_-]{1,60}").matches(run))
        val context = instrumentation.targetContext
        val nodes = (1..185).map { id -> node(id, "공개 버튼 $id").copy(sensitiveContent = id == 4) }
        val screen = frame("public.navigation.fixture", nodes)
        val goal = TaskSession().start("공개 자료를 열어줘", screen.packageName, "공개 시험 화면")
        val schema = JSONObject(context.assets.open("task-plan.schema.json").bufferedReader().use { it.readText() })
        val diagnostic = V3PlanDiagnostic(V3DiagnosticSource.VALIDATION, V3PlanDiagnostic.REPEATED_WRONG_NAVIGATION,
            setOf(185, 4, 3, 2, 999))
        val packet = V3PlanRequest.encode(goal, screen, null, "", GuideBackend.SUBSCRIPTION_TERRA, schema, diagnostic)
        assertFalse(packet.observationComplete)
        val payload = JSONObject(packet.bytes.toString(Charsets.UTF_8))
        val input = JSONObject(payload.getJSONArray("input").getJSONObject(0).getString("content"))
        val blocked = input.getJSONObject("planningFeedback").getJSONArray("blockedNodeIds")
        assertEquals(listOf(2, 3), (0 until blocked.length()).map(blocked::getInt))
        assertTrue(packet.nodeIds.containsAll(listOf(2, 3)))
        assertTrue(packet.nodeIds.none { it in setOf(4, 185, 999) })
        val folder = context.filesDir.resolve("qa-v3-public-$run").apply { mkdirs() }
        folder.resolve("blocked-navigation-request.json").writeBytes(packet.bytes)
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("v3ScopedBlockedPacketProof", "encoded_blocked_ids=2;excluded_sensitive_omitted_or_unknown_ids=3;external_requests=0")
        })
    }

    @Test fun exportPublicRequestsAndValidateReturnedPlans(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3PublicProbe")=="true")
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        val run=InstrumentationRegistry.getArguments().getString("qaProbeRun")!!
        val repairCase=InstrumentationRegistry.getArguments().getString("qaProbeRepairCase")?.also{require(Regex("[A-Za-z0-9_]{1,100}").matches(it))}
        require(Regex("[A-Za-z0-9_-]{1,60}").matches(run))
        val context=instrumentation.targetContext
        val folder=context.filesDir.resolve("qa-v3-public-$run").apply{mkdirs()}
        val schema=JSONObject(context.assets.open("task-plan.schema.json").bufferedReader().use{it.readText()})
        val responses=folder.resolve("responses.json").takeIf{it.exists()}?.readText()?.let(::JSONObject) ?:JSONObject()
        val exports=JSONArray();val results=JSONArray()
        var inputReplay=JSONObject().put("available",false).put("realAiRequests",0)
        var presenceReplay=JSONObject().put("available",false).put("realAiRequests",0)
        var helperReplay=JSONObject().put("available",false).put("realAiRequests",0)
        fun export(key:String,goal:TaskGoal,screen:ScreenSnapshot,signal:String,prior:TaskPlan?=null,diagnostic:V3PlanDiagnostic?=null):V3PlanPacket {
            val feedback=diagnostic ?:repairCase?.takeIf{it==key}?.let{V3PlanDiagnostic(V3DiagnosticSource.RESPONSE,"server_invalid_model_result")}
            val packet=V3PlanRequest.encode(goal,screen,prior,signal,GuideBackend.SUBSCRIPTION_TERRA,schema,feedback)
            exports.put(JSONObject().put("case",key).put("payload",JSONObject(packet.bytes.toString(Charsets.UTF_8)))
                .put("bytes",packet.bytes.size).put("complete",packet.observationComplete))
            return packet
        }
        fun apply(key:String,session:TaskSession,packet:V3PlanPacket,screen:ScreenSnapshot,inputs:V3InputCompletion?=null):Boolean {
            if(!responses.has(key))return false
            val result=JSONObject().put("case",key)
            try{
                val plan=V3PlanCodec.response(responses.getJSONObject(key),GuideBackend.SUBSCRIPTION_TERRA)
                result.put("kind",plan.kind.name).put("operation",plan.operation.name).put("targetNodeId",plan.targetNodeId?:JSONObject.NULL)
                val problem=TaskPlanValidation.problem(plan,session.goal!!,packet.nodeIds)
                if(problem!=null)result.put("valid",false).put("problem",problem)
                else {
                    val grounded=V3PlanRequest.validate(packet,session.goal!!,plan)
                    val review=session.review(grounded)
                    check(review.problem==null){review.problem.orEmpty()}
                    val candidate=session.goal!!.copy(requirements=review.requirements)
                    val preparation=V3PreparationPolicy.review(candidate,review.plan!!,screen)
                    val blocked=inputs?.blocking(candidate,preparation.plan,screen,preparation.bindings)!=null
                    val valid=preparation.problem==null && !blocked
                    result.put("valid",valid).put("conditionCount",plan.conditions.size)
                        .put("taskCount",plan.tasks.size).put("requirementCount",session.goal!!.requirements.size)
                        .put("preparationBindingCount",preparation.bindings.size).put("actionGateCount",plan.actionGates.size)
                        .put("effectiveEffect",preparation.plan.effect.name).put("nativeInputBlocked",blocked)
                        .put("questionScopedConditions",JSONArray(plan.question?.conditionKeys.orEmpty()))
                    when{preparation.problem!=null->result.put("problem",preparation.problem)
                        blocked->result.put("problem","unfinished_input")
                        else->{
                            val ticket=session.issue(ObservationRef(screen.packageName,screen.windowId,screen.snapshotId,screen.capturedAtMillis,screen.sourceEpoch))
                            check(session.accept(ticket,grounded));session.rememberPreparations(preparation.bindings)
                            if(plan.kind==NextKind.GUIDE && plan.operation==Operation.INPUT && inputs!=null)
                                check(inputs.begin(session.goal!!,session.plan!!,screen,screen.allNodes.single{it.id==plan.targetNodeId})!=null)
                        }}
                    if(key.startsWith("native_input_state") || key in setOf("native_empty_finished","native_presence_required","native_presence_blank")){
                        val ready=key.endsWith("_after_input_finish") || key in setOf("native_empty_finished","native_presence_blank")
                        val matched=if(ready)plan.kind==NextKind.GUIDE && plan.operation==Operation.TAP && plan.targetNodeId==3 && preparation.plan.effect==ActionEffect.COMMIT
                            else plan.kind==NextKind.GUIDE && plan.operation==Operation.INPUT && plan.targetNodeId==2 || plan.kind==NextKind.WAIT && plan.waitKind==WaitKind.USER_INPUT
                        result.put("expectedPolicy",if(ready)"OFFER_COMMIT"else "KEEP_WRITING").put("modelPolicyMatched",matched)
                    }
                    if(key.startsWith("helper_")){
                        val expected=when(key){"helper_result_ready"->"OPEN_MATCHING_RESULT";"helper_result_loading"->"WAIT_FOR_RESULTS";
                            "helper_next_editor"->"GUIDE_CURRENT_EDITOR";else->"RECHECK_QUERY_OR_RECOVER"}
                        val matched=preparation.plan.effect!=ActionEffect.COMMIT && when(expected){
                            "OPEN_MATCHING_RESULT"->plan.kind==NextKind.GUIDE && plan.operation==Operation.TAP && plan.targetNodeId==4
                            "WAIT_FOR_RESULTS"->plan.kind==NextKind.WAIT && plan.waitKind==WaitKind.SCREEN_TRANSITION
                            "GUIDE_CURRENT_EDITOR"->plan.kind==NextKind.GUIDE && plan.operation==Operation.INPUT && plan.targetNodeId==5
                            else->plan.kind in setOf(NextKind.OBSERVE,NextKind.RECOVER) ||
                                plan.kind==NextKind.GUIDE && plan.operation in setOf(Operation.INPUT,Operation.TAP) && plan.targetNodeId==2 ||
                                plan.kind==NextKind.WAIT && plan.waitKind==WaitKind.USER_INPUT
                        }
                        result.put("expectedPolicy",expected).put("modelPolicyMatched",matched)
                        result.put("relevantNodeIds",JSONArray(plan.relevantNodeIds)).put("scopeNodeIds",JSONArray(plan.scopeNodeIds))
                    }
                    if (key.startsWith("blocked_route_")) {
                        val expected = when (key) {
                            "blocked_route_search" -> "OFFER_VISIBLE_SEARCH"
                            "blocked_route_input" -> "GUIDE_SEARCH_PREPARATION"
                            else -> "HOLD_WITHOUT_TARGET"
                        }
                        val matched = preparation.plan.effect != ActionEffect.COMMIT && when (expected) {
                            "OFFER_VISIBLE_SEARCH" -> plan.kind == NextKind.GUIDE && plan.operation == Operation.TAP && plan.targetNodeId == 10
                            "GUIDE_SEARCH_PREPARATION" -> plan.kind == NextKind.GUIDE && plan.operation == Operation.INPUT && plan.targetNodeId == 11
                            else -> plan.kind in setOf(NextKind.OBSERVE, NextKind.RECOVER) && plan.operation == Operation.NONE && plan.targetNodeId == null
                        }
                        result.put("expectedPolicy", expected).put("modelPolicyMatched", matched)
                            .put("blockedTargetOffered", plan.targetNodeId in setOf(4, 5))
                            .put("otherCardOffered", plan.targetNodeId in setOf(8, 9))
                    }
                    if (key.startsWith("named_rows_")) {
                        val expected = when (key) {
                            "named_rows_duplicate" -> "HOLD_OR_ASK_WITHOUT_PICKING"
                            "named_rows_unique" -> "OFFER_THE_MATCHING_ROW"
                            else -> "VERIFY_NAMED_DESTINATION"
                        }
                        val matched = when (expected) {
                            "HOLD_OR_ASK_WITHOUT_PICKING" -> plan.kind in setOf(NextKind.OBSERVE, NextKind.RECOVER, NextKind.ASK, NextKind.WAIT) && plan.targetNodeId == null
                            "OFFER_THE_MATCHING_ROW" -> plan.kind == NextKind.GUIDE && plan.operation == Operation.TAP && plan.targetNodeId == 2
                            else -> plan.kind == NextKind.COMPLETE && plan.conditions.any {
                                it.required && it.phase == ConditionPhase.OUTCOME && it.evidenceNodeIds.any { id -> id in setOf(1, 2) }
                            }
                        }
                        result.put("expectedPolicy", expected).put("modelPolicyMatched", matched)
                    }
                }
            }catch(e:V3PlanFailure){result.put("valid",false).put("problem",e.kind.name)}
            catch(_:Exception){result.put("valid",false).put("problem","codec_or_session_rejection")}
            results.put(result);return result.optBoolean("valid")
        }
        val arguments = InstrumentationRegistry.getArguments()
        val publicScenes = scenes() +
            (if (arguments.getString("qaV3BlockedRoutes") == "true") blockedRouteScenes() else emptyList()) +
            (if (arguments.getString("qaV3NamedRows") == "true") namedRowScenes() else emptyList())
        for(scene in publicScenes){
            val session=TaskSession();session.start(scene.request,scene.screen.packageName,"공개 시험 화면")
            val packet=export(scene.key,session.goal!!,scene.screen,"",prior=scene.prior,diagnostic=scene.diagnostic)
            val accepted=apply(scene.key,session,packet,scene.screen)
            if(accepted && scene.key=="alarm_partial_ready"){
                // Public scene replay: simulate newly observed prepared values, never fabricate a commit receipt.
                val ready=scenes().single{it.key=="alarm_ready"}.screen.copy(snapshotId=2,capturedAtMillis=2000,
                    captureStartedAtUptimeMillis=3,captureFinishedAtUptimeMillis=4)
                val key=scene.key+"_after_preparation"
                val follow=export(key,session.goal!!,ready,"user_acted",session.plan)
                apply(key,session,follow,ready)
            }
            if(accepted && scene.key in setOf("alarm_unknown","alarm_precise_missing") && session.question!=null){
                // This is an explicitly synthetic test answer, not the user's real alarm setting.
                val question=session.question!!
                check(session.answer(question.key,"평일 오전 7시 30분"))
                val key=scene.key+"_after_answer"
                val follow=export(key,session.goal!!,scene.screen,"question_answered")
                apply(key,session,follow,scene.screen)
            }
        }
        // A helper query remains unfinished; a public app action can still reveal a result or a new editor.
        for(key in listOf("helper_result_ready","helper_result_loading","helper_next_editor")){
            val nextEditor=key=="helper_next_editor"
            val request=if(nextEditor)"공개 항목을 찾아 설명을 적고 저장하고 싶어."else "파란 폴더를 열고 싶어."
            val before=frame("public.folder.fixture",listOf(node(1,"항목 검색","android.widget.LinearLayout",false),
                node(2,"","android.widget.EditText").copy(text=null,editable=true,inputHint="폴더 검색",hasInput=true,localInputRevision=1),node(3,"검색")))
            val session=TaskSession();session.start(request,before.packageName,"공개 항목 찾기")
            val seed=TaskPlan(NextKind.GUIDE,"항목 검색과 목표 화면",if(nextEditor)GoalKind.NEW_EVENT else GoalKind.STATE,
                listOf(GoalCondition("outcome",if(nextEditor)"이번 설명 저장 결과"else "요청한 폴더 화면 열림",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),
                    basis=ConditionBasis(ConditionOrigin.REQUEST,quote=request))),
                listOf(TaskStep("search","앱에서 항목 찾기",listOf(2,3),emptyList()),TaskStep("open","검색 결과 열기",emptyList(),listOf("search")),
                    TaskStep("verify","원하는 결과 확인",emptyList(),listOf("open"))),emptyList(),Operation.INPUT,2,"검색어를 입력해주세요.",
                null,WaitKind.NONE,listOf(1,2,3),emptyList(),effect=ActionEffect.PREPARE,scopeNodeIds=listOf(1))
            check(session.accept(session.issue(ObservationRef(before.packageName,5,1,1000,4)),seed))
            val inputs=V3InputCompletion();check(inputs.begin(session.goal!!,session.plan!!,before,before.allNodes[1])!=null)
            val nodes=when{
                nextEditor->listOf(node(1,"설명 입력","android.widget.LinearLayout",false),
                    node(5,"","android.widget.EditText").copy(text=null,editable=true,inputHint="설명",hasInput=false,localInputRevision=2),node(6,"저장"))
                key=="helper_result_ready"->before.allNodes+node(4,"파란 폴더")
                else->before.allNodes+node(4,"검색 중","android.widget.TextView",false)
            }
            val screen=before.copy(snapshotId=2,capturedAtMillis=2000,nodes=nodes,allNodes=nodes,captureStartedAtUptimeMillis=3,captureFinishedAtUptimeMillis=4)
            session.rememberInputs(inputs.progress(session.goal!!,screen))
            val packet=export(key,session.goal!!,screen,"native_app_control_clicked",session.plan)
            val accepted=apply(key,session,packet,screen,inputs)
            if(key=="helper_result_loading" && accepted){
                val changed=screen.allNodes.map{if(it.id==4)it.copy(text="검색 결과 없음")else it}
                val noResult=screen.copy(snapshotId=3,capturedAtMillis=3000,nodes=changed,allNodes=changed,captureStartedAtUptimeMillis=5,captureFinishedAtUptimeMillis=6)
                val wait=session.plan!!
                if(wait.kind==NextKind.WAIT && wait.waitKind==WaitKind.SCREEN_TRANSITION){
                    val checks=JSONArray();val watch=V3TransitionObservation();watch.begin(wait,screen)
                    fun verify(name:String,expected:Boolean,actual:Boolean){
                        check(expected==actual){"$name: expected $expected but got $actual"}
                        checks.put(JSONObject().put("case",name).put("expected",expected).put("actual",actual))
                    }
                    verify("unchanged_public_capture",false,watch.changed(screen.copy(snapshotId=3)))
                    val inputOnly=screen.allNodes.map{if(it.id==2)it.copy(localInputRevision=9)else it}
                    verify("private_input_revision_is_not_watched",false,watch.changed(screen.copy(nodes=inputOnly,allNodes=inputOnly)))
                    verify("actual_wait_observes_no_result_text",true,watch.changed(noResult))
                    watch.rebase(noResult)
                    verify("rebased_same_status_is_stable",false,watch.changed(noResult))
                    val later=changed.map{if(it.id==4)it.copy(text="검색 오류")else it}
                    verify("later_status_invalidates_pending_replan",true,watch.changed(noResult.copy(nodes=later,allNodes=later)))
                    watch.begin(wait.copy(waitKind=WaitKind.USER_ACTIVITY),screen)
                    verify("user_activity_does_not_watch_status_ticks",false,watch.changed(noResult))
                    helperReplay=JSONObject().put("available",true).put("passed",true).put("realAiRequests",0)
                        .put("sourceCase",key).put("relevantNodeIds",JSONArray(wait.relevantNodeIds))
                        .put("scopeNodeIds",JSONArray(wait.scopeNodeIds)).put("cases",checks)
                }
                session.rememberInputs(inputs.progress(session.goal!!,noResult))
                val followKey=key+"_after_no_results"
                val follow=export(followKey,session.goal!!,noResult,"",session.plan)
                apply(followKey,session,follow,noResult,inputs)
            }
        }
        // Native owner and real encoder, with explicitly synthetic observations and writing declarations.
        // No widget action or commit receipt is fabricated by this public-model probe.
        for(mode in listOf("writing","optional_blank","required_presence","blank_presence")) {
            val emptyFinished=mode!="writing"
            val desiredPresence=when(mode){"required_presence"->true;"blank_presence"->false;else->null}
            val key=when(mode){"writing"->"native_input_state";"optional_blank"->"native_empty_finished";"required_presence"->"native_presence_required";else->"native_presence_blank"}
            val request=when(mode){"writing"->"메모를 작성해서 저장하고 싶어. 내용은 앱에서 쓸게.";"required_presence"->"필수 설명에 내용을 입력하고 저장하고 싶어.";else->"선택 사항인 메모 내용을 비워두고 저장하고 싶어."}
            val screen=frame("public.note.fixture",listOf(node(1,"메모 작성","android.widget.TextView",false),
                node(2,"","android.widget.EditText").copy(text=null,editable=true,inputHint=if(desiredPresence==true)"필수 설명"else "메모 내용",hasInput=!emptyFinished,contentInvalid=false,localInputRevision=1),node(3,"저장")))
            val session=TaskSession();session.start(request,screen.packageName,"공개 메모 화면")
            val checks=desiredPresence?.let{listOf(ValueExpectation(ValueProperty.INPUT_PRESENT,
                ValueSubject("android.widget.EditText",screen.allNodes[1].viewIdResourceName.orEmpty(),""),expectedBoolean=it))}.orEmpty()
            val seed=TaskPlan(NextKind.GUIDE,"메모 작성과 저장",GoalKind.NEW_EVENT,listOf(
                GoalCondition("draft",when(desiredPresence){true->"필수 설명의 비공백 입력과 작성 종료";false->"선택 메모의 비공백 입력 없음과 작성 종료";null->if(emptyFinished)"선택 메모를 비워두고 작성 종료"else "메모 작성 종료"},true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,listOf(2),basis=ConditionBasis(ConditionOrigin.REQUEST,quote=request),phase=ConditionPhase.PREPARATION,expectations=checks),
                GoalCondition("saved","이번 메모 저장 결과",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),basis=ConditionBasis(ConditionOrigin.REQUEST,quote=request))),
                listOf(TaskStep("write","메모 작성",listOf(2),emptyList()),TaskStep("save","저장",listOf(3),listOf("write")),TaskStep("verify","저장 결과 확인",emptyList(),listOf("save"))),
                listOf(TaskRelation(RelationKind.BEFORE,listOf("write","save","verify"))),Operation.INPUT,2,if(emptyFinished && desiredPresence!=true)"메모를 비워두세요."else "내용을 입력해주세요.",null,WaitKind.NONE,listOf(1,2,3),emptyList(),
                effect=ActionEffect.PREPARE,scopeNodeIds=listOf(1),actionGates=listOf(ActionGate(3,listOf("draft"))))
            check(session.accept(session.issue(ObservationRef(screen.packageName,5,1,1000,4)),seed))
            val preparation=V3PreparationPolicy.review(session.goal!!,session.plan!!,screen);check(preparation.problem==null);session.rememberPreparations(preparation.bindings)
            val initialInputPlan=session.plan!!
            val inputs=V3InputCompletion();val inputId=inputs.begin(session.goal!!,session.plan!!,screen,screen.allNodes[1])!!
            if(emptyFinished)check(inputs.finish(inputId,session.goal!!,screen,true))
            session.rememberInputs(inputs.progress(session.goal!!,screen))
            val packet=export(key,session.goal!!,screen,if(emptyFinished)"user_finished_input"else "native_app_control_clicked",session.plan)
            val accepted=apply(key,session,packet,screen,inputs)
            if(desiredPresence==false && accepted){
                val current=session.plan!!;val condition=current.conditions.single{c->c.expectations.any{it.property==ValueProperty.INPUT_PRESENT}}
                val checks=JSONArray()
                fun verify(name:String,expected:String?,actual:String?){
                    check(expected==actual){"$name: expected $expected but got $actual"}
                    checks.put(JSONObject().put("case",name).put("expectedProblem",expected?:JSONObject.NULL).put("observedProblem",actual?:JSONObject.NULL))
                }
                fun changed(node:AccessibilityNodeModel)=screen.allNodes.map{if(it.id==2)node else it}.let{screen.copy(nodes=it,allNodes=it)}
                verify("current_declared_blank_matches",null,V3ValueEvidence.problem(current,screen))
                verify("typed_nonblank_is_not_desired_blank","value_mismatch",V3ValueEvidence.problem(current,changed(screen.allNodes[1].copy(hasInput=true,localInputRevision=2))))
                verify("unreadable_is_not_blank","unverified_value_evidence",V3ValueEvidence.problem(current,changed(screen.allNodes[1].copy(hasInput=null))))
                verify("private_input_is_not_public_evidence","unverified_value_evidence",V3ValueEvidence.problem(current,changed(screen.allNodes[1].copy(password=true))))
                verify("duplicate_input_subject_is_ambiguous","unverified_value_evidence",V3ValueEvidence.problem(current,screen.copy(allNodes=screen.allNodes+screen.allNodes[1].copy(id=4,path="0/4"))))
                val noEvidence=current.copy(conditions=current.conditions.map{if(it.key==condition.key)it.copy(evidenceNodeIds=emptyList())else it})
                verify("presence_subject_needs_current_evidence","unverified_value_evidence",V3ValueEvidence.problem(noEvidence,screen))
                val flipped=current.copy(conditions=current.conditions.map{if(it.key==condition.key)it.copy(expectations=it.expectations.map{v->if(v.property==ValueProperty.INPUT_PRESENT)v.copy(expectedBoolean=true)else v})else it})
                verify("owned_false_cannot_follow_new_true","unexplained_requirement_change",session.review(flipped).problem)
                val omitted=current.copy(conditions=current.conditions.map{if(it.key==condition.key)it.copy(expectations=emptyList())else it})
                verify("owned_presence_cannot_be_omitted","unexplained_requirement_change",session.review(omitted).problem)
                presenceReplay=JSONObject().put("available",true).put("passed",true).put("realAiRequests",0).put("cases",checks)
            }
            if(emptyFinished)continue
            if(responses.has(key)){
                val ready=screen.copy(snapshotId=2,capturedAtMillis=2000,captureStartedAtUptimeMillis=3,captureFinishedAtUptimeMillis=4)
                check(inputs.finish(inputId,session.goal!!,ready,true));session.rememberInputs(inputs.progress(session.goal!!,ready))
                val readyKey=key+"_after_input_finish"
                val readyPacket=export(readyKey,session.goal!!,ready,"user_finished_input",session.plan)
                val readyAccepted=apply(readyKey,session,readyPacket,ready,inputs)
                if(readyAccepted && session.plan?.effect==ActionEffect.COMMIT){
                    val goal=session.goal!!;val prepared=V3PreparationPolicy.review(goal,session.plan!!,ready)
                    val counter=V3InputCompletion();val id=counter.begin(goal,initialInputPlan,ready,ready.allNodes[1])!!
                    val checks=JSONArray()
                    fun expect(name:String,blocked:Boolean,g:TaskGoal=goal,f:ScreenSnapshot=ready){
                        val observed=counter.blocking(g,prepared.plan,f,prepared.bindings)!=null
                        checks.put(JSONObject().put("case",name).put("expectedBlocked",blocked).put("blocked",observed));check(blocked==observed)
                    }
                    expect("no_native_writing_declaration",true)
                    expect("wire_flag_cannot_mint_native_receipt",true,goal.copy(inputProgress=listOf(InputProgress(id,2,true,999))))
                    check(counter.finish(id,goal,ready,true));expect("current_native_declaration",false)
                    expect("new_goal_revision",true,goal.copy(revision=goal.revision+1))
                    expect("different_source",true,f=ready.copy(sourceEpoch=ready.sourceEpoch+1))
                    val missing=ready.allNodes.map{if(it.id==2)it.copy(localInputRevision=null)else it}
                    expect("missing_private_local_revision",true,f=ready.copy(nodes=missing,allNodes=missing))
                    val changed=ready.allNodes.map{if(it.id==2)it.copy(localInputRevision=99)else it}
                    expect("changed_private_local_revision",true,f=ready.copy(nodes=changed,allNodes=changed))
                    inputReplay=JSONObject().put("available",true).put("passed",true).put("realAiRequests",0).put("cases",checks)
                }
                if(responses.has(readyKey)){
                    val nodes=ready.allNodes.map{if(it.id==2)it.copy(localInputRevision=2)else it}
                    val edited=ready.copy(snapshotId=3,capturedAtMillis=3000,nodes=nodes,allNodes=nodes,captureStartedAtUptimeMillis=5,captureFinishedAtUptimeMillis=6)
                    check(inputs.refresh(session.goal!!,edited));session.rememberInputs(inputs.progress(session.goal!!,edited))
                    val editedKey=key+"_after_input_edit"
                    val editedPacket=export(editedKey,session.goal!!,edited,"user_requested_recheck",session.plan)
                    apply(editedKey,session,editedPacket,edited,inputs)
                }
            }
        }
        folder.resolve("requests.json").writeText(exports.toString(2),Charsets.UTF_8)
        folder.resolve("validation.json").writeText(results.toString(2),Charsets.UTF_8)
        folder.resolve("input-replay.json").writeText(inputReplay.toString(2),Charsets.UTF_8)
        folder.resolve("presence-replay.json").writeText(presenceReplay.toString(2),Charsets.UTF_8)
        folder.resolve("helper-replay.json").writeText(helperReplay.toString(2),Charsets.UTF_8)
        assertTrue(exports.length()>=5)
        instrumentation.sendStatus(0,android.os.Bundle().apply{putString("publicProbeExportCount",exports.length().toString());putString("nativeValidatedResponses",results.length().toString())})
    }
    private fun node(id:Int,label:String,role:String="android.widget.Button",clickable:Boolean=true,scrollable:Boolean=false)=
        AccessibilityNodeModel(id,if(id==1)"0"else"0/${id-2}",if(id==1)null else"0",role,label,null,
            "public.fixture:id/element_$id",clickable,true,true,scrollable,true,false,NodeBounds(20,40+id*80,1040,110+id*80),
            if(scrollable)listOf(AccessibilityActionModel(4096,null),AccessibilityActionModel(8192,null))else if(clickable)listOf(AccessibilityActionModel(16,null))else emptyList())
    private fun frame(pkg:String,nodes:List<AccessibilityNodeModel>)=ScreenSnapshot(1,pkg,5,1000,nodes,nodes,false,
        NodeBounds(0,0,1080,2400),sourceEpoch=4,captureStartedAtUptimeMillis=1,captureFinishedAtUptimeMillis=2)

    private fun blockedRouteScenes(): List<Scene> {
        fun card(id: Int, title: String, row: Int): List<AccessibilityNodeModel> {
            val path = "0/$row"
            val top = 120 + row * 330
            return listOf(
                node(id, "", "android.widget.LinearLayout", false).copy(path = path, parentPath = "0",
                    bounds = NodeBounds(20, top, 1040, top + 300)),
                node(id + 1, title, "android.widget.TextView", false).copy(path = "$path/0", parentPath = path,
                    bounds = NodeBounds(40, top + 10, 1000, top + 80)),
                node(id + 2, "열기").copy(path = "$path/1", parentPath = path,
                    viewIdResourceName = "public.navigation.fixture:id/open", bounds = NodeBounds(40, top + 90, 1000, top + 170)),
                node(id + 3, "보기").copy(path = "$path/2", parentPath = path,
                    viewIdResourceName = "public.navigation.fixture:id/view", bounds = NodeBounds(40, top + 180, 1000, top + 260)),
            )
        }
        val root = node(1, "자료 목록", "android.widget.LinearLayout", false)
            .copy(paneTitle = "자료 목록", bounds = NodeBounds(0, 0, 1080, 2400))
        val cards = listOf(root) + card(2, "회의 자료", 0) + card(6, "여행 사진", 1)
        val search = node(10, "자료 검색")
        val editor = node(11, "", "android.widget.EditText").copy(text = null, inputHint = "자료 이름", editable = true, hasInput = false)
        val prior = TaskPlan(NextKind.GUIDE, "요청한 자료 열기", GoalKind.INSPECT, emptyList(), emptyList(), emptyList(),
            Operation.TAP, 4, "열기를 눌러주세요.", null, WaitKind.NONE, listOf(4), emptyList(), scopeNodeIds = listOf(3))
        val blocked = V3PlanDiagnostic(V3DiagnosticSource.VALIDATION, V3PlanDiagnostic.REPEATED_WRONG_NAVIGATION, setOf(4, 5))
        return listOf(
            "blocked_route_search" to cards + search,
            "blocked_route_input" to cards + listOf(search.copy(enabled = false, stateDescription = "자료 이름을 입력하면 검색할 수 있어요."), editor),
            "blocked_route_none" to cards,
        ).map { (key, nodes) -> Scene(key, "회의 자료를 열어줘", frame("public.navigation.fixture", nodes), blocked, prior) }
    }

    private fun namedRowScenes(): List<Scene> {
        val request = "회의 자료를 열어줘"
        val packageName = "public.navigation.fixture"
        val results = node(1, "검색 결과", "android.widget.LinearLayout", false).copy(paneTitle = "검색 결과")
        val target = node(2, "회의 자료").copy(viewIdResourceName = "$packageName:id/result_row")
        val peer = node(3, "여행 사진").copy(viewIdResourceName = target.viewIdResourceName)
        val search = TaskPlan(NextKind.GUIDE, "자료 검색", GoalKind.INSPECT, emptyList(), emptyList(), emptyList(),
            Operation.TAP, 8, "검색을 눌러주세요.", null, WaitKind.NONE, listOf(8), emptyList())
        val opened = listOf(
            node(1, "", "android.widget.LinearLayout", false).copy(paneTitle = "회의 자료"),
            node(2, "회의 자료", "android.widget.TextView", false).copy(heading = true),
            node(3, "공개 회의 자료 본문", "android.widget.TextView", false),
        )
        return listOf(
            Scene("named_rows_duplicate", request, frame(packageName, listOf(results, target, peer.copy(text = "회의 자료"))), prior = search),
            Scene("named_rows_unique", request, frame(packageName, listOf(results, target, peer)), prior = search),
            Scene("named_rows_opened", request, frame(packageName, opened), prior = search.copy(targetNodeId = 2,
                relevantNodeIds = listOf(2), message = "회의 자료를 눌러주세요.")),
        )
    }

    private fun scenes():List<Scene>{
        val days=listOf("월","화","수","목","금","토","일")
        val alarm=listOf(node(1,"알람 설정","android.widget.LinearLayout",false),node(2,"오전","android.widget.Spinner"),
            node(3,"시 9","android.widget.NumberPicker",false,true).copy(range=NodeRange(1f,12f,9f,0)),
            node(4,"분 00","android.widget.NumberPicker",false,true).copy(range=NodeRange(0f,59f,0f,0)))+
            days.mapIndexed{i,day->node(5+i,day,"android.widget.CheckBox").copy(checkable=true,checked=0,selected=false)}+
            listOf(node(12,"저장"),node(13,"취소"))
        val search=listOf(node(1,"받는 사람 찾기","android.widget.LinearLayout",false),
            node(2,"","android.widget.EditText").copy(text=null,editable=true,inputHint="이름 또는 전화번호",hasInput=false),
            node(3,"검색","android.widget.ImageButton"),node(4,"검색어를 입력하세요","android.widget.TextView",false))
        val activity=listOf(node(1,"시간 측정","android.widget.LinearLayout",false),node(2,"00:05.00","android.widget.TextView",false),
            node(3,"측정 중","android.widget.TextView",false),node(4,"정지"),node(5,"기록"))
        val list=listOf(node(1,"선택 목록","android.widget.ScrollView",false,true))+(2..202).map{node(it,"목록 항목 ${it-1}")}
        return listOf(
            Scene("alarm_known","평일 오전 7시 30분에 알람이 울리게 하고 싶어",frame("public.alarm.fixture",alarm)),
            Scene("alarm_partial_ready","평일 오전 7시 30분에 알람이 울리게 하고 싶어",frame("public.alarm.fixture",alarm.map{
                if(it.id==3)it.copy(text="시 7",range=NodeRange(1f,12f,7f,0))else it})),
            Scene("alarm_ready","평일 오전 7시 30분에 알람이 울리게 하고 싶어",frame("public.alarm.fixture",alarm.map{
                when(it.id){3->it.copy(text="시 7",range=NodeRange(1f,12f,7f,0));4->it.copy(text="분 30",range=NodeRange(0f,59f,30f,0));
                    in 5..9->it.copy(checked=1,selected=true);else->it}})),
            Scene("alarm_unknown","알람을 맞추고 싶은데 어떻게 해야 해?",frame("public.alarm.fixture",alarm)),
            Scene("alarm_precise_missing","알람 시간을 바꾸려고 해. 어떤 값을 골라야 하는지 하나씩 알려줘.",frame("public.alarm.fixture",alarm)),
            Scene("recipient_search","친구한테 문자를 보내고 싶어",frame("public.message.fixture",search)),
            Scene("recipient_repair","친구한테 문자를 보내고 싶어",frame("public.message.fixture",search),
                V3PlanDiagnostic(V3DiagnosticSource.VALIDATION,"unknown_relation_member")),
            Scene("ongoing_activity","30초 동안 시간을 재고 싶어",frame("public.activity.fixture",activity)),
            Scene("partial_list","선택 목록에서 화면 아래에 있는 공개 대상 항목을 찾고 싶어",frame("public.list.fixture",list)))
    }
}
