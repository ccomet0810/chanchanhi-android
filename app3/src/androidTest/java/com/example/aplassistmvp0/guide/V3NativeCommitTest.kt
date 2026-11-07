package com.example.aplassistmvp0.guide

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in owned-emulator proof of the real native click -> pending -> result path. No model request. */
@RunWith(AndroidJUnit4::class)
class V3NativeCommitTest {
    @Test fun aRealClickCreatesOneReceiptAndAnUnknownResultDoesNotRepeatIt()=runFixture(false)
    @Test fun twoDistinctNativeCommitsKeepTheirIssuedReceiptsAndTheEarlierSuccess()=runFixture(true)
    @Test fun anOmittedOwnedConditionCannotFinishTheNativeTaskBeforeRechecking()=runFixture(false,true)
    @Test fun anActualUnselectedPreparationBlocksCommitUntilItIsSelected()=runFixture(false,partialPreparation=true)
    @Test fun aRejectedGraphIsCorrectedBeforeAnyNativeInstruction()=runFixture(false,repairInvalidOnce=true)
    @Test fun aFalseSatisfiedClaimCannotBypassAnActualUncheckedValue()=runFixture(false,partialPreparation=true,lieAboutPreparation=true)
    @Test fun aKnownCommitClickedWhileReplanningIsNotOfferedAgain()=runFixture(false,replanDuringClick=true)
    @Test fun androidAndServerShareTheSameConditionContractCases(){
        val cases=org.json.JSONArray(InstrumentationRegistry.getInstrumentation().context.assets
            .open("condition-contract-cases.json").bufferedReader().use{it.readText()})
        for(index in 0 until cases.length()){
            val row=cases.getJSONObject(index);val input=row.getJSONObject("input")
            val answers=mutableMapOf<String,String>();input.getJSONObject("answers").let{o->o.keys().forEach{answers[it]=o.getString(it)}}
            val authorities=mutableMapOf<String,AnswerAuthority>();input.getJSONObject("answerAuthorities").let{o->o.keys().forEach{key->
                val a=o.getJSONObject(key);val ids=a.getJSONArray("requirementIds")
                authorities[key]=AnswerAuthority(a.getLong("revision"),(0 until ids.length()).map{ids.getLong(it)}.toSet())
            }}
            val requirements=input.getJSONArray("requirements").let{a->(0 until a.length()).map{j->val r=a.getJSONObject(j)
                OwnedRequirement(r.getLong("id"),r.getInt("version"),r.getString("description"),r.getBoolean("required"),
                    ConditionBasis(ConditionOrigin.valueOf(r.getString("origin")),quote=input.getString("request"),rationale="공개 계약 시험"),
                    r.getLong("changedAtRevision"),r.getBoolean("retired"),if(r.isNull("staleAnswerKey"))null else r.getString("staleAnswerKey"),phase=ConditionPhase.valueOf(r.optString("phase","OUTCOME")),
                    expectations=V3PlanCodec.parseExpectations(r.optJSONArray("expectations")?:org.json.JSONArray()))
            }}
            val goal=TaskGoal(1,input.getLong("goalRevision"),input.getString("request"),"public.conditions.fixture","공개 계약",answers,
                requirements=requirements,answerAuthorities=authorities,preparationHistory=input.optJSONArray("preparationHistory")?.let{a->
                    (0 until a.length()).map{j->val b=a.getJSONObject(j);val ids=b.getJSONArray("requirementIds")
                        PreparationBinding(b.getString("role"),b.getString("resource"),b.getString("label"),(0 until ids.length()).map{ids.getLong(it)}.toSet())}
                }.orEmpty())
            val nodes=input.getJSONArray("nodes").let{a->(0 until a.length()).map{a.getJSONObject(it).getInt("id")}.toSet()}
            val accepted=runCatching{
                val plan=V3PlanCodec.parse(row.getJSONObject("plan")).copy(groundingComplete=input.optBoolean("observationComplete",true))
                if(TaskPlanValidation.problem(plan,goal,nodes)!=null)false else {
                    val review=GoalConditions.review(goal,plan)
                    fun field(n:org.json.JSONObject,key:String):Any?=if(n.has(key))n.opt(key)else input.optJSONObject("nodeDefaults")?.opt(key)
                    val facts=input.getJSONArray("nodes").let{a->(0 until a.length()).map{j->val n=a.getJSONObject(j);val id=n.getInt("id")
                        com.example.aplassistmvp0.accessibility.AccessibilityNodeModel(id,"0/$id","0",n.optString("role","android.widget.Button"),n.optString("label"),null,
                            n.optString("resource"),true,true,true,false,n.optBoolean("visibleToUser",true),n.optBoolean("password"),com.example.aplassistmvp0.accessibility.NodeBounds(0,id*80,400,id*80+60),emptyList(),
                            checkable=if(n.isNull("checkable"))null else n.get("checkable").also{check(it is Boolean)} as Boolean,
                            checked=if(n.isNull("checked"))null else (n.get("checked").also{check(it is Number)} as Number).toInt(),
                            selected=if(n.isNull("selected"))null else n.get("selected").also{check(it is Boolean)} as Boolean,
                            editable=n.optBoolean("editable"),sensitiveContent=n.optBoolean("sensitiveContent"),
                            hasInput=field(n,"inputPresent")?.takeUnless{it===org.json.JSONObject.NULL}?.also{check(it is Boolean)} as Boolean?,
                            range=n.optJSONObject("range")?.let{r->check(listOf("min","max","current").all{r.get(it) is Number})
                                com.example.aplassistmvp0.accessibility.NodeRange(r.getDouble("min").toFloat(),r.getDouble("max").toFloat(),r.getDouble("current").toFloat(),0)})}}
                    val frame=com.example.aplassistmvp0.state.ScreenSnapshot(1,goal.targetPackage,5,1000,facts,facts,false,com.example.aplassistmvp0.accessibility.NodeBounds(0,0,500,1000))
                    V3PreparationPolicy.review(goal.copy(requirements=review.requirements),review.plan!!,frame).problem==null
                }
            }.getOrDefault(false)
            assertEquals(row.getString("name"),row.getBoolean("accepted"),accepted)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("conditionContractCases",cases.length().toString())})
    }
    @Test fun theAndroidCodecPreservesTheLocallyIssuedReceiptReference(){
        val p=TaskPlan(NextKind.COMPLETE,"새 항목",GoalKind.NEW_EVENT,
            listOf(GoalCondition("created","새 결과",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(2),1,2)),
            emptyList(),emptyList(),Operation.NONE,null,"완료했어요.",null,WaitKind.NONE,listOf(2),listOf(2),
            commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(2),outcomeReceiptId=7)
        val content=org.json.JSONObject().put("type","output_text").put("text",V3PlanCodec.encode(p).toString())
        val response=org.json.JSONObject().put("status","completed").put("model","gpt-6.1-terra")
            .put("output",org.json.JSONArray().put(org.json.JSONObject().put("type","message").put("content",org.json.JSONArray().put(content))))
        assertEquals(p,V3PlanCodec.response(response,GuideBackend.defaultSubscription))
    }
    @Test fun theCodecPreservesDecimalAndFalseExpectationsWithoutCoercion(){
        val values=listOf(ValueExpectation(ValueProperty.RANGE,ValueSubject("android.view.View","public:id/value",""),expectedNumber=0.1),
            ValueExpectation(ValueProperty.CHECKED,ValueSubject("android.view.View","","공개 선택"),expectedBoolean=false))
        assertEquals(values,V3PlanCodec.parseExpectations(V3PlanCodec.expectations(values)))
        for(key in listOf("expectedNumber","expectedBoolean")){
            val json=V3PlanCodec.expectations(values);val index=if(key=="expectedNumber")0 else 1
            json.getJSONObject(index).put(key,if(index==0)"0.1" else "false")
            assertTrue(runCatching{V3PlanCodec.parseExpectations(json)}.isFailure)
        }
    }
    private fun runFixture(twoOperations:Boolean,omitConditionOnce:Boolean=false,partialPreparation:Boolean=false,repairInvalidOnce:Boolean=false,lieAboutPreparation:Boolean=false,replanDuringClick:Boolean=false) {
        check(InstrumentationRegistry.getArguments().getString("qaV3Runtime")=="true")
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "This settings fixture is emulator-only" }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val resolver=context.contentResolver
        val oldServices=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        val prefs=AplPreferences.get(context)
        val previousUi=GuideStateRepository.state.value
        val history=context.getSharedPreferences("guide-history",Context.MODE_PRIVATE)
        val oldHistory=history.getString("entries",null)
        val field=GuideController::class.java.getDeclaredField("engine").apply{isAccessible=true}
        val events=ConcurrentLinkedQueue<String>();val requests=AtomicInteger();val shown=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var original:Any?=null
        var injected=false
        var previousEntries:List<GuideHistoryEntry>?=null
        var lastRecords=emptyList<OperationRecord>()
        var omitted=false
        var lastRequirementCount=0
        val valueMismatches=AtomicInteger()
        val latePlan=CompletableDeferred<TaskPlan>();var deferredPlan:TaskPlan?=null
        val lateReplies=AtomicInteger();val retainedReceipts=AtomicInteger()
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun settings(block:()->Unit){automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);try{block()}finally{automation.dropShellPermissionIdentity()}}
        fun await(label:String,condition:()->Boolean){
            val deadline=SystemClock.uptimeMillis()+12_000
            while(SystemClock.uptimeMillis()<deadline){if(condition())return;SystemClock.sleep(80)}
            val frame=AssistStateRepository.state.value.snapshot
            val publicResults=frame?.allNodes?.filter{it.text in setOf("이전에 만든 항목","처리 중","첫 항목 생성됨","이번에 새 항목 생성됨")}?.map{it.text}
            error("$label: phase=${GuideStateRepository.state.value.phase}, message=${GuideStateRepository.state.value.message}, requests=${requests.get()}, sequence=${frame?.snapshotId}, read=${frame?.captureStartedAtUptimeMillis}, publicResults=$publicResults, events=$events")
        }
        fun find(node:AccessibilityNodeInfo,label:String):AccessibilityNodeInfo? {
            if(node.packageName?.toString()==instrumentation.context.packageName && node.isVisibleToUser && node.text?.toString()==label)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let{find(it,label)?.let{return it}}
            return null
        }
        fun click(label:String){
            if(Build.VERSION.SDK_INT>=33)automation.clearCache()
            val node=automation.windows.asSequence().mapNotNull{it.root}.mapNotNull{find(it,label)}.firstOrNull()
            assertNotNull("Current native button $label",node);assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try{
            main{GuideController.initialize(context);previousEntries=GuideHistory.entries.value;GuideController.stop();AplPreferences.update(context){it.copy(voiceEnabled=false,reduceMotion=true)}}
            val component=ComponentName(context,AssistAccessibilityService::class.java).flattenToString()
            // This owned AVD can contain a legacy overlay; isolate the lab service and restore settings in finally.
            val services=component
            settings{check(Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,services));check(Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,"1"))}
            await("service connected"){AssistStateRepository.state.value.serviceConnected}
            val launched=SystemClock.uptimeMillis()
            instrumentation.context.startActivity(Intent(instrumentation.context,V3FormFixtureActivity::class.java)
                .putExtra("twoOperations",twoOperations).putExtra("deferFirstResult",twoOperations).putExtra("preparationMode",partialPreparation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val pkg=instrumentation.context.packageName
            await("fresh public fixture captured"){AssistStateRepository.state.value.snapshot?.let{
                it.packageName==pkg && it.sourceEpoch>0 && (it.captureStartedAtUptimeMillis?:0)>=launched &&
                    it.allNodes.any{n->n.text=="확정"} && it.allNodes.any{n->n.text=="이전에 만든 항목"}
            }==true}
            AssistStateRepository.state.value.snapshot!!.let{
                assertNotNull("Native read start",it.captureStartedAtUptimeMillis)
                assertNotNull("Native read end",it.captureFinishedAtUptimeMillis)
                assertTrue(it.captureStartedAtUptimeMillis!!<=it.captureFinishedAtUptimeMillis!!)
                assertTrue(it.captureFinishedAtUptimeMillis!!<=SystemClock.uptimeMillis())
            }
            val engine=V3GuideEngine(scope,AssistStateRepository.state,context.packageName,{BridgeStatus(true,"offline native fixture")},
                {goal,screen,_,_,attempt->
                    val call=requests.incrementAndGet()
                    if(repairInvalidOnce && call==2){assertEquals(1,attempt.index);assertEquals("unknown_relation_member",attempt.diagnostic!!.reason)}
                    lastRecords=goal.operationHistory
                    lastRequirementCount=goal.requirements.size
                    val commit=screen.allNodes.single{it.text=="확정" && it.clickable}
                    val result=screen.allNodes.firstOrNull{it.text=="이번에 새 항목 생성됨"}
                    val firstResult=screen.allNodes.firstOrNull{it.text=="첫 항목 생성됨"}
                    fun condition(key:String,description:String,ids:List<Int>,phase:ConditionPhase=ConditionPhase.OUTCOME):GoalCondition {
                        val owned=goal.requirements.singleOrNull{it.description==description}
                        return GoalCondition(key,description,true,if(ids.isEmpty())Truth.UNKNOWN else Truth.SATISFIED,
                            if(ids.isEmpty())EvidenceKind.UNKNOWN else EvidenceKind.OBSERVED,ids,owned?.id,owned?.version,
                            if(owned==null)ConditionBasis(ConditionOrigin.INFERRED,quote=goal.request,rationale="공개 시험 목표의 필요한 조건")else null,phase,
                            if(phase==ConditionPhase.PREPARATION)ids.mapNotNull{id->screen.allNodes.single{it.id==id}.takeIf{it.checkable==true}?.let{n->
                                val resource=n.viewIdResourceName.orEmpty()
                                ValueExpectation(ValueProperty.CHECKED,ValueSubject(n.className.orEmpty(),resource,if(resource.isEmpty())GuidePolicy.label(n)else ""),expectedBoolean=true)
                            }}else emptyList())
                    }
                    val scopeNode=screen.allNodes.first{it.text=="공개 양식 시험"}
                    val ownedConditions=if(omitConditionOnce)listOf(condition("scope","공개 대상 유지",listOf(scopeNode.id)),condition("created","새 항목 결과",emptyList()))else emptyList()
                    val prepared=condition("prepared","확정 대상 준비",listOf(scopeNode.id),ConditionPhase.PREPARATION)
                    val preparations=if(partialPreparation)listOf("준비 A","준비 B").mapIndexed{index,label->
                        val n=screen.allNodes.single{it.text==label}
                        condition("ready_$index",label+" 선택됨",listOf(n.id),ConditionPhase.PREPARATION)
                            .copy(truth=if(lieAboutPreparation || n.checked==1)Truth.SATISFIED else Truth.UNSATISFIED)
                    }else listOf(prepared)
                    val base=TaskPlan(NextKind.GUIDE,"공개 항목 생성",GoalKind.NEW_EVENT,ownedConditions+preparations,listOf(TaskStep("commit","확정",listOf(commit.id),emptyList())),
                        emptyList(),Operation.TAP,commit.id,"확정을 눌러주세요.",null,WaitKind.NONE,listOf(commit.id),emptyList(),effect=ActionEffect.COMMIT,
                        actionGates=listOf(ActionGate(commit.id,preparations.map{it.key})))
                    val value=if(repairInvalidOnce && call==1)base.copy(relations=listOf(TaskRelation(RelationKind.ALL,listOf("not_a_task"))))
                    else if(twoOperations && firstResult!=null){
                        val second=screen.allNodes.single{it.text=="결과 갱신" && it.clickable}
                        base.copy(targetNodeId=second.id,tasks=listOf(TaskStep("second","둘째 확정",listOf(second.id),emptyList())),
                            conditions=listOf(condition("second_ready","결과 갱신 준비",listOf(scopeNode.id),ConditionPhase.PREPARATION)),
                            actionGates=listOf(ActionGate(second.id,listOf("second_ready"))),
                            relevantNodeIds=listOf(second.id),message="결과 갱신을 눌러주세요.",commitOutcome=CommitOutcome.SUCCEEDED,
                            outcomeNodeIds=listOf(firstResult.id),outcomeReceiptId=goal.operationHistory.lastOrNull()?.receiptId)
                    }else if(result==null)base.copy(commitOutcome=if(goal.operationHistory.isNotEmpty())CommitOutcome.UNKNOWN else CommitOutcome.NONE,
                        outcomeReceiptId=goal.operationHistory.lastOrNull()?.receiptId)
                    else {
                        val all=if(omitConditionOnce)listOf(condition("scope","공개 대상 유지",listOf(scopeNode.id)),condition("created","새 항목 결과",listOf(result.id)))
                            else listOf(condition("created","새 항목 결과",listOf(result.id)))
                        val reported=if(omitConditionOnce && !omitted){omitted=true;all.filter{it.key!="scope"}}else all
                        base.copy(kind=NextKind.COMPLETE,operation=Operation.NONE,targetNodeId=null,effect=ActionEffect.NONE,
                            conditions=reported,actionGates=emptyList(),commitOutcome=CommitOutcome.SUCCEEDED,outcomeNodeIds=listOf(result.id),newEventNodeIds=listOf(result.id),message="완료했어요.",
                            outcomeReceiptId=goal.operationHistory.lastOrNull()?.receiptId)
                    }
                    if(replanDuringClick && call==2){
                        deferredPlan=value.copy(message="재분석 전의 늦은 확정 안내")
                        withContext(NonCancellable){latePlan.await().also{lateReplies.incrementAndGet()}}
                    }else value
                },GuideStateRepository::publish,{node->shown.incrementAndGet();AssistAccessibilityService.requestGuideHighlight(node)},
                AssistAccessibilityService::clearHighlight,{},{},AssistAccessibilityService::requestRefresh,
                trace={kind,data->events+=kind;if(kind=="v3_preparation_blocked" && data["reason"]=="value_mismatch")valueMismatches.incrementAndGet()
                    if(kind=="v3_commit_interaction" && data["source"]=="native_click_during_replanning")retainedReceipts.incrementAndGet()
                    if(kind in setOf("v3_native_event_unbound","v3_commit_click_rejected","v3_foreground_invalidated"))
                    instrumentation.sendStatus(0,android.os.Bundle().apply{putString("diagnostic",kind+":"+data)})},nowUptime=SystemClock::uptimeMillis)
            main{original=field.get(null);field.set(null,engine);injected=true}
            runBlocking{withContext(Dispatchers.Main){assertTrue(engine.start("새 항목을 만들고 싶어",pkg,"공개 양식 시험"))}}
            if(partialPreparation){
                await("unready preparation rejected"){engine.ui.phase=="error"}
                assertEquals(0,shown.get());assertTrue(lastRecords.isEmpty())
                if(lieAboutPreparation)assertTrue("Native metadata must contradict the model's false SATISFIED claim",valueMismatches.get()>0)
                click("준비 B")
                await("native second preparation selected"){AssistStateRepository.state.value.snapshot?.allNodes?.any{it.text=="준비 B" && it.checked==1}==true}
                runBlocking{withContext(Dispatchers.Main){assertTrue(engine.retryFromCurrent())}}
            }
            await("validated first instruction"){engine.ui.phase=="guiding" && engine.ui.currentAction=="highlight" && shown.get()==1 && AssistStateRepository.state.value.highlightedNodeId!=null}
            if(replanDuringClick){
                runBlocking{withContext(Dispatchers.Main){assertTrue(engine.retryFromCurrent(rejected=true))}}
                await("replacement plan is still pending"){requests.get()==2 && engine.ui.busy && deferredPlan!=null}
            }
            click("확정")
            if(twoOperations){
                // Publish a distinct public result only after the native click receipt was observed.
                // A synchronous listener update is not guaranteed to have a read time after the click event.
                await("first native receipt awaits its result"){
                    events.count{it=="v3_commit_interaction"}==1 && requests.get()>=2 && engine.ui.currentAction=="wait"
                }
                instrumentation.context.startActivity(Intent(instrumentation.context,V3FormFixtureActivity::class.java)
                    .putExtra("firstResultReady",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                await("first success and a distinct second instruction"){events.count{it=="v3_commit_interaction"}==1 && shown.get()==2 && engine.ui.currentAction=="highlight"}
            }else{
                await("real native click receipt and pending-result instruction"){events.count{it=="v3_commit_interaction"}==1 && engine.ui.currentAction=="wait"}
                assertEquals("Unknown outcome must not show the commit again",1,shown.get())
            }
            if(replanDuringClick){
                assertEquals(3,requests.get());assertEquals(1,retainedReceipts.get());assertEquals(1,lastRecords.size)
                assertTrue(lastRecords.single().interactionVerified)
                assertTrue(latePlan.complete(checkNotNull(deferredPlan)))
                await("superseded noncancellable model reply returned"){lateReplies.get()==1}
                assertEquals(1,shown.get());assertEquals("wait",engine.ui.currentAction)
            }
            click("결과 갱신")
            if(twoOperations){
                await("second native receipt awaits its result"){
                    events.count{it=="v3_commit_interaction"}==2 && requests.get()>=4 && engine.ui.currentAction=="wait"
                }
                instrumentation.context.startActivity(Intent(instrumentation.context,V3FormFixtureActivity::class.java)
                    .putExtra("secondResultReady",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            if(omitConditionOnce){
                await("missing owned condition blocks completion"){engine.ui.phase=="error"}
                assertNotEquals("done",engine.ui.phase);assertEquals(3,lastRequirementCount)
                runBlocking{withContext(Dispatchers.Main){assertTrue(engine.retryFromCurrent())}}
            }
            await("fresh native result completes task"){engine.ui.phase=="done"}
            val expected=if(twoOperations)2 else 1
            assertEquals(expected,events.count{it=="v3_commit_interaction"})
            assertEquals(expected,shown.get())
            if(twoOperations){assertEquals(listOf(1L,2L),lastRecords.map{it.receiptId});assertEquals(CommitOutcome.SUCCEEDED,lastRecords.first().state)}
            if(omitConditionOnce){assertTrue(omitted);assertEquals(3,lastRequirementCount)}
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("v3NativeProof","click_receipts=$expected;commit_highlights=$expected;requests=${requests.get()};omission_guard=$omitConditionOnce;partial_preparation_guard=$partialPreparation;automatic_plan_repair=$repairInvalidOnce;false_value_guard=$lieAboutPreparation;deferred_first_result=$twoOperations;replanning_click=$replanDuringClick;retained_receipts=${retainedReceipts.get()};late_replies=${lateReplies.get()};final=done")})
        }finally{
            main{
                GuideController.stop();if(injected)field.set(null,original);AplPreferences.update(context){prefs};GuideStateRepository.publish(previousUi)
                previousEntries?.let{entries->
                    val historyField=GuideHistory::class.java.getDeclaredField("mutableEntries").apply{isAccessible=true}
                    @Suppress("UNCHECKED_CAST")
                    val flow=historyField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<List<GuideHistoryEntry>>
                    flow.value=entries
                }
            }
            latePlan.cancel();scope.cancel()
            if(oldHistory==null)history.edit().remove("entries").commit() else history.edit().putString("entries",oldHistory).commit()
            settings{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,oldServices);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,oldEnabled)}
        }
    }
}
