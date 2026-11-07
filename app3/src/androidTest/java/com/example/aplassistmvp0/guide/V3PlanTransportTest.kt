package com.example.aplassistmvp0.guide

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only our opt-in lab emulator and its offline loopback fixture. Existing configuration is restored. */
@RunWith(AndroidJUnit4::class)
class V3PlanTransportTest {
    private fun inputPresenceContext(actual:Boolean,desired:Boolean,finished:Boolean):Pair<TaskGoal,ScreenSnapshot> {
        val source=frame();val ns=source.allNodes.map{if(it.id==1)it.copy(className="android.widget.EditText",text=null,
            editable=true,inputHint="공개 설명",hasInput=actual,localInputRevision=987654321)else it}
        val check=ValueExpectation(ValueProperty.INPUT_PRESENT,ValueSubject("android.widget.EditText",ns[0].viewIdResourceName.orEmpty(),""),expectedBoolean=desired)
        val owned=OwnedRequirement(1,1,"공개 입력 유무",true,ConditionBasis(ConditionOrigin.REQUEST,quote=goal().request),1,
            phase=ConditionPhase.PREPARATION,expectations=listOf(check))
        return goal().copy(requirements=listOf(owned),inputProgress=listOf(InputProgress(1,1,finished,if(finished)7 else null))) to source.copy(nodes=ns,allNodes=ns)
    }
    @Test fun aFinishedBlankDeclarationCannotPassARequiredPresentValue()=withClient{client,_->
        val (g,f)=inputPresenceContext(false,true,true)
        val failure=runCatching{client.plan(g,f,null,"qa_input_presence")}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.INVALID_RESPONSE,failure.kind)
    }
    @Test fun expectedFalseInputPresenceSurvivesTheRealClientAndServer()=withClient{client,_->
        val (g,f)=inputPresenceContext(false,false,true);val result=client.plan(g,f,null,"qa_input_presence")
        assertEquals(g.requirements.single().expectations,result.conditions.single().expectations)
        assertNull(V3ValueEvidence.problem(result,f))
    }
    @Test fun presenceComparisonDoesNotInventAWritingDeclaration()=withClient{client,_->
        val (g,f)=inputPresenceContext(true,true,false);val result=client.plan(g,f,null,"qa_input_presence")
        assertNull(V3ValueEvidence.problem(result,f));assertFalse(g.inputProgress.single().userFinished)
    }
    @Test fun nativeInputDeclarationsTravelWithoutPrivateRevisionOrContent()=withClient{client,context->
        val g=goal().copy(inputProgress=listOf(InputProgress(1,1,true,7)))
        val original=frame();val nodes=original.allNodes.map{if(it.id==1)it.copy(className="android.widget.EditText",text=null,
            editable=true,inputHint="공개 입력",hasInput=false,localInputRevision=987654321)else it}
        val screen=original.copy(nodes=nodes,allNodes=nodes)
        val schema=org.json.JSONObject(context.assets.open("task-plan.schema.json").bufferedReader().use{it.readText()})
        val encoded=V3PlanRequest.encode(g,screen,null,"qa_input_finished",GuideBackend.SUBSCRIPTION_TERRA,schema).bytes.toString(Charsets.UTF_8)
        assertFalse(encoded.contains("987654321"));assertFalse(encoded.contains("localInputRevision"))
        val body=org.json.JSONObject(org.json.JSONObject(encoded).getJSONArray("input").getJSONObject(0).getString("content"))
        val progress=body.getJSONArray("inputProgress").getJSONObject(0)
        assertTrue(progress.getBoolean("userFinished"));assertEquals(7L,progress.getLong("receiptId"))
        val wireNodes=body.getJSONArray("nodes")
        val editor=(0 until wireNodes.length()).map{wireNodes.getJSONObject(it)}.single{it.getInt("id")==1}
        assertFalse(editor.getBoolean("inputPresent"))
        assertEquals(NextKind.GUIDE,client.plan(g,screen,null,"qa_input_finished").kind)
    }
    @Test fun typedDesiredValuesSurviveTheActualClientAndServer()=withClient{client,_->
        val (g,screen)=valueContext(30f)
        val result=client.plan(g,screen,null,"qa_values")
        assertEquals(g.requirements.single().expectations,result.conditions.single().expectations)
        assertNull(V3ValueEvidence.problem(result,screen))
    }
    @Test fun theServerRejectsAModelSatisfiedClaimAgainstTheWrongActualValue()=withClient{client,_->
        val (g,screen)=valueContext(0f)
        val error=runCatching{client.plan(g,screen,null,"qa_values_mismatch")}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.INVALID_RESPONSE,error.kind)
    }
    private fun valueContext(current:Float):Pair<TaskGoal,ScreenSnapshot>{
        val base=frame();val nodes=base.allNodes.map{if(it.id==1)it.copy(range=NodeRange(0f,60f,current,0))else it}
        val expected=ValueExpectation(ValueProperty.RANGE,ValueSubject(nodes[0].className!!,nodes[0].viewIdResourceName!!,""),expectedNumber=30.0)
        val g=goal().copy(request="공개 값을 30으로 맞추고 싶어",requirements=listOf(OwnedRequirement(1,1,"공개 값 30 준비",true,
            ConditionBasis(ConditionOrigin.REQUEST,quote="공개 값을 30으로 맞추고 싶어"),1,phase=ConditionPhase.PREPARATION,expectations=listOf(expected))))
        return g to base.copy(nodes=nodes,allNodes=nodes)
    }
    @Test fun httpFailuresKeepTypedStatusWithoutEchoingUpstreamText()=withClient{client,_->
        for((signal,kind) in listOf("qa_http_auth" to V3PlanFailureKind.AUTHENTICATION,"qa_http_limit" to V3PlanFailureKind.LIMIT,
            "qa_http_busy" to V3PlanFailureKind.BUSY,"qa_http_rate" to V3PlanFailureKind.BUSY,"qa_http_unknown" to V3PlanFailureKind.UNKNOWN_REMOTE_RESULT,
            "qa_http_usage" to V3PlanFailureKind.UNKNOWN_REMOTE_RESULT,"qa_http_mismatch" to V3PlanFailureKind.PROTOCOL_MISMATCH,
            "qa_http_invalid" to V3PlanFailureKind.INVALID_RESPONSE)){
            val error=runCatching{client.plan(goal(),frame(),null,signal)}.exceptionOrNull() as V3PlanFailure
            assertEquals(signal,kind,error.kind);assertFalse(error.userMessage.contains("SENSITIVE"))
            assertEquals(kind==V3PlanFailureKind.INVALID_RESPONSE,error.diagnostic!=null)
        }
    }
    @Test fun theRealClientCarriesOneFrozenDiagnosedCorrection()=withClient{client,_->
        val g=goal();val screen=frame();val attempt=attempt(g,screen)
        val error=runCatching{client.plan(g,screen,null,"qa_repair_contract",attempt)}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.INVALID_RESPONSE,error.kind)
        val result=client.plan(g,screen,null,"qa_repair_contract",attempt.copy(index=1,diagnostic=error.diagnostic))
        assertEquals(NextKind.GUIDE,result.kind);assertEquals(1,result.targetNodeId)
        val repeated=runCatching{client.plan(g,screen,null,"qa_repair_contract",attempt.copy(index=1,diagnostic=error.diagnostic))}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.REQUEST_EXPIRED,repeated.kind)
    }
    @Test fun reauthorizationCannotRebindAnExistingRepairEpisode()=withClient{client,_->
        val g=goal();val screen=frame();val attempt=attempt(g,screen)
        val error=runCatching{client.plan(g,screen,null,"qa_repair_reauthorize",attempt)}.exceptionOrNull() as V3PlanFailure
        assertTrue(client.status().ready)
        val changed=runCatching{client.plan(g,screen,null,"qa_repair_reauthorize",attempt.copy(index=1,diagnostic=error.diagnostic))}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.CONNECTION_CHANGED,changed.kind)
    }
    @Test fun aChangedGoalCannotBorrowTheOriginalRepairTicket()=withClient{client,_->
        val g=goal();val screen=frame();val attempt=attempt(g,screen)
        val error=runCatching{client.plan(g,screen,null,"qa_repair_changed_goal",attempt)}.exceptionOrNull() as V3PlanFailure
        val changed=runCatching{client.plan(g.copy(request="다른 공개 작업"),screen,null,"qa_repair_changed_goal",attempt.copy(index=1,diagnostic=error.diagnostic))}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.REQUEST_EXPIRED,changed.kind)
    }
    @Test fun aChangedCaptureCannotBorrowTheOriginalRepairTicket()=withClient{client,_->
        val g=goal();val screen=frame();val attempt=attempt(g,screen)
        val error=runCatching{client.plan(g,screen,null,"qa_repair_changed_frame",attempt)}.exceptionOrNull() as V3PlanFailure
        val changed=runCatching{client.plan(g,screen.copy(snapshotId=2),null,"qa_repair_changed_frame",attempt.copy(index=1,diagnostic=error.diagnostic))}.exceptionOrNull() as V3PlanFailure
        assertEquals(V3PlanFailureKind.REQUEST_EXPIRED,changed.kind)
    }
    private fun attempt(g:TaskGoal,screen:ScreenSnapshot)=V3PlanningAttempt(PlanTicket(g.sessionId,g.revision,1,
        ObservationRef(screen.packageName,screen.windowId,screen.snapshotId,screen.capturedAtMillis,screen.sourceEpoch)))
    @Test fun theActualClientUsesTheTerraLowSubscriptionContract()=withClient{client,_->
        val result=client.plan(goal(),frame(),null,"qa_simple")
        assertEquals(NextKind.GUIDE,result.kind);assertEquals(1,result.targetNodeId);assertEquals(ActionEffect.PREPARE,result.effect)
    }
    @Test fun ownedRequirementsAnswersAndReceiptsSurviveTheActualClientTransport()=withClient{client,_->
        val desired=ConditionBasis(ConditionOrigin.ANSWER,"desired","값 A")
        val owned=OwnedRequirement(1,2,"값 A 설정",true,desired,2)
        val g=goal().copy(revision=2,answers=mapOf("desired" to "값 A"),requirements=listOf(owned),
            answerAuthorities=mapOf("desired" to AnswerAuthority(2,setOf(1))),
            operationHistory=listOf(OperationRecord(1,7,1,2,CommitOutcome.PENDING,true,"확정","Button","public:id/commit")))
        val prior=observe().copy(conditions=listOf(GoalCondition("owned",owned.description,true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),1,2)),
            commitOutcome=CommitOutcome.PENDING,outcomeReceiptId=7)
        val result=client.plan(g,frame(),prior,"qa_owned")
        assertEquals(1L,result.conditions.single().requirementId);assertEquals(2,result.conditions.single().requirementVersion)
        assertEquals(7L,result.outcomeReceiptId);assertEquals(CommitOutcome.PENDING,result.commitOutcome)
    }
    @Test fun aDensePublicScreenIsPackedRatherThanReportedAsAConnectionFailure()=withClient{client,_->
        val result=client.plan(goal(),frame(dense=true),null,"qa_dense")
        assertEquals(NextKind.OBSERVE,result.kind)
    }
    @Test fun theRealServerRejectsCompletionOfATruncatedObservation()=withClient{client,_->
        val failed=runCatching{client.plan(goal(),frame().copy(truncated=true),null,"qa_bad_partial_completion")}.exceptionOrNull()
        assertTrue(failed is V3PlanFailure);assertEquals(V3PlanFailureKind.INVALID_RESPONSE,(failed as V3PlanFailure).kind)
        assertTrue(failed.userMessage.contains("답변"))
    }
    @Test fun privateInputPasswordPreviewAndActionLabelsAreNotEncoded(){
        val context=testContext();val source=frame();val first=source.allNodes.first()
        val editor=first.copy(className="android.widget.EditText",editable=true,text="PRIVATE_EDIT_VALUE",contentDescription="PRIVATE_DESCRIPTION",
            inputHint="공개 검색",stateDescription="PRIVATE_STATE",actions=listOf(AccessibilityActionModel(1,"PRIVATE_ACTION")))
        val password=first.copy(id=3,path="0/3",password=true,text="PRIVATE_PASSWORD")
        val preview=first.copy(id=4,path="0/4",sensitiveContent=true,text="PRIVATE_PREVIEW")
        val nodes=listOf(editor,source.allNodes.last(),password,preview)
        val packet=packet(context,source.copy(nodes=nodes,allNodes=nodes))
        val wire=packet.bytes.toString(Charsets.UTF_8);assertFalse(wire.contains("PRIVATE_"))
        assertEquals(setOf(1,2),packet.nodeIds)
        val input=org.json.JSONObject(org.json.JSONObject(wire).getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(input.getJSONArray("nodes").getJSONObject(0).getBoolean("editable"))
        assertFalse(input.getJSONArray("nodes").getJSONObject(0).has("state"))
    }
    @Test fun falseStatesArePreservedSeparatelyFromUnknownDefaults(){
        val context=testContext();val source=frame();val nodes=source.allNodes.map{it.copy(enabled=false,checkable=false,checked=0,selected=false,hasInput=false,contentInvalid=false)}
        val packet=packet(context,source.copy(nodes=nodes,allNodes=nodes));val input=org.json.JSONObject(
            org.json.JSONObject(packet.bytes.toString(Charsets.UTF_8)).getJSONArray("input").getJSONObject(0).getString("content"))
        val n=input.getJSONArray("nodes").getJSONObject(0)
        for(key in listOf("enabled","checkable","selected","inputPresent","invalid"))assertFalse(key,n.getBoolean(key))
        assertTrue(input.getJSONObject("nodeDefaults").isNull("inputPresent"))
        assertTrue(input.getJSONObject("nodeDefaults").getBoolean("enabled"))
    }
    @Test fun ambiguousCaptureIdsAreRejectedBeforeEncoding(){
        val source=frame();val nodes=source.allNodes+source.allNodes.first().copy(path="0/99")
        val failure=runCatching{packet(testContext(),source.copy(allNodes=nodes,nodes=nodes))}.exceptionOrNull()
        assertEquals(V3PlanFailureKind.INVALID_OBSERVATION,(failure as V3PlanFailure).kind)
    }
    @Test fun mandatoryContextIsNeverSilentlyDroppedToFitARequest()=withClient{client,_->
        val g=goal().copy(requirements=(1L..128L).map{id->OwnedRequirement(id,1,"공개 조건 $id "+"설정 값 ".repeat(55),true,
            ConditionBasis(ConditionOrigin.INFERRED,quote=goal().request,rationale="공개 조건"),1,retired=id!=1L,lastChangeReason="정정 근거 ".repeat(35))})
        val failed=runCatching{client.plan(g,frame(),null,"qa_oversize_context_must_not_reach_server")}.exceptionOrNull()
        assertEquals(V3PlanFailureKind.CONTEXT_TOO_LARGE,(failed as V3PlanFailure).kind)
    }
    @Test fun aCompromisedResponseCannotCompleteAPartialPacket(){
        val p=packet(testContext(),frame().copy(truncated=true))
        val result=observe().copy(kind=NextKind.COMPLETE,conditions=listOf(GoalCondition("state","공개 상태",true,Truth.SATISFIED,
            EvidenceKind.OBSERVED,listOf(1),basis=ConditionBasis(ConditionOrigin.REQUEST,quote=goal().request))))
        val failed=runCatching{V3PlanRequest.validate(p,goal(),result)}.exceptionOrNull()
        assertEquals(V3PlanFailureKind.INCOMPLETE_OBSERVATION,(failed as V3PlanFailure).kind)
    }
    @Test fun anUnsentNodeCannotBecomeAnActionTarget(){
        val source=frame(dense=true);val p=packet(testContext(),source)
        assertTrue(p.bytes.size<=V3PlanRequest.MAX_BYTES);assertTrue(p.nodeIds.size in 1..179);assertFalse(p.observationComplete)
        val unsent=source.allNodes.last().id;assertFalse(unsent in p.nodeIds)
        val result=observe().copy(kind=NextKind.GUIDE,operation=Operation.TAP,targetNodeId=unsent,effect=ActionEffect.PREPARE)
        val failed=runCatching{V3PlanRequest.validate(p,goal(),result)}.exceptionOrNull()
        assertEquals(V3PlanFailureKind.INVALID_RESPONSE,(failed as V3PlanFailure).kind)
    }
    private fun testContext():Context {
        check(InstrumentationRegistry.getArguments().getString("qaV3Contract")=="true")
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        return InstrumentationRegistry.getInstrumentation().targetContext
    }
    private fun packet(context:Context,screen:ScreenSnapshot)=V3PlanRequest.encode(goal(),screen,null,"public fixture",GuideBackend.SUBSCRIPTION_TERRA,
        org.json.JSONObject(context.assets.open("task-plan.schema.json").bufferedReader().use{it.readText()}))
    private fun observe()=TaskPlan(NextKind.OBSERVE,"공개 관찰",GoalKind.STATE,emptyList(),emptyList(),emptyList(),Operation.NONE,null,
        "화면을 확인해주세요.",null,WaitKind.NONE,emptyList(),emptyList())
    private fun withClient(block:suspend(V3PlanClient,Context)->Unit){
        val context=testContext()
        val files=listOf("bridge-connection.json","bridge-connection.json.bak","bridge-pairing.txt").map{context.filesDir.resolve(it)}
        val before=files.associateWith{if(it.exists())it.readBytes()else null}
        val prefs=context.getSharedPreferences("guide-model",Context.MODE_PRIVATE);val saved=prefs.all.toMap()
        try{
            GuideModelSettings(context).save(GuideBackend.SUBSCRIPTION_TERRA)
            BridgeConnectionStore(context).save(BridgeConnectionPolicy.normalize(BridgeConnectionMode.USB,BridgeConnectionPolicy.USB_ENDPOINT,"c".repeat(43),null))
            runBlocking{val client=V3PlanClient(context);assertTrue(client.status().ready);android.os.SystemClock.sleep(400);block(client,context)}
        }finally{
            for((file,bytes) in before){if(bytes==null){check(!file.exists()||file.delete())}else file.writeBytes(bytes)}
            val edit=prefs.edit().clear();for((key,value) in saved)when(value){
                is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.map{it as String}.toSet())
            };check(edit.commit())
            for((file,bytes) in before)assertTrue("Fixture configuration restored",if(bytes==null)!file.exists()else file.readBytes().contentEquals(bytes))
            assertEquals(saved,prefs.all)
        }
    }
    private fun goal()=TaskGoal(1,1,"공개 항목을 확인하고 싶어",PKG,"공개 시험")
    private fun frame(dense:Boolean=false):ScreenSnapshot {
        val nodes=(1..if(dense)180 else 2).map{id->AccessibilityNodeModel(id,"0/$id","0","android.widget.Button",
            if(dense)"공개 항목 $id "+"상태 설명 ".repeat(20) else "공개 항목 $id",null,
            PKG+":id/public_item_$id",true,true,true,false,true,false,NodeBounds(20,id*80,500,id*80+70),emptyList(),
            stateDescription=if(dense)"공개 상태 ".repeat(30) else null)}
        return ScreenSnapshot(1,PKG,5,1000,nodes,nodes,false,NodeBounds(0,0,600,16000),sourceEpoch=4,
            captureStartedAtUptimeMillis=1,captureFinishedAtUptimeMillis=2)
    }
    companion object{private const val PKG="public.transport.fixture"}
}
