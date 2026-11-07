package com.example.aplassistmvp0.guide

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.*
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Public synthetic input only. Real service/parser/events/overlay; the planner is a fixture. */
@RunWith(AndroidJUnit4::class)
class V3NativeInputTest {
    @Test fun nativeNonemptyEditsRevokeFinishedInputAndItsCommitGuidance(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val automation=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val resolver=context.contentResolver;val args=InstrumentationRegistry.getArguments()
        check(args.getString("qaV3Native")=="true" && android.os.Build.MODEL.contains("sdk"))
        val oldServices=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        AplPreferences.initialize(context);val prefs=AplPreferences.get(context);val oldUi=GuideStateRepository.state.value
        val field=GuideController::class.java.getDeclaredField("engine").apply{isAccessible=true}
        var oldEngine:Any?=null;var injected=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val requests=AtomicInteger();val commitShows=AtomicInteger();val inputShows=AtomicInteger();val blocked=AtomicInteger()
        val resultShows=AtomicInteger();val timedReads=AtomicInteger();val offeredResult=java.util.concurrent.atomic.AtomicBoolean()
        var permitResultNavigation=false
        var progress=emptyList<InputProgress>()
        fun main(block:()->Unit)=i.runOnMainSync(block)
        fun secure(block:()->Unit){automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);try{block()}finally{automation.dropShellPermissionIdentity()}}
        fun await(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12_000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(80)};error("$label; phase=${GuideStateRepository.state.value.phase}")}
        fun find(node:AccessibilityNodeInfo,editor:Boolean):AccessibilityNodeInfo? {
            if(node.packageName?.toString()==i.context.packageName && node.isVisibleToUser &&
                (if(editor)node.isEditable else node.text?.toString()=="결과 갱신"))return node
            for(index in 0 until node.childCount)node.getChild(index)?.let{find(it,editor)?.let{return it}}
            return null
        }
        fun native(editor:Boolean):AccessibilityNodeInfo {
            if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
            return automation.windows.asSequence().mapNotNull{it.root}.mapNotNull{find(it,editor)}.firstOrNull() ?:error("Public native control absent")
        }
        fun hasOverlayText(node:AccessibilityNodeInfo,value:String):Boolean {
            if(node.packageName?.toString()==context.packageName && node.isVisibleToUser && node.text?.toString()==value)return true
            for(index in 0 until node.childCount)node.getChild(index)?.let{if(hasOverlayText(it,value))return true}
            return false
        }
        fun blankControl(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.packageName?.toString()==context.packageName && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.text?.toString()==V3InputCompletion.EMPTY_DONE_LABEL)return node
            for(index in 0 until node.childCount)node.getChild(index)?.let{blankControl(it)?.let{return it}}
            return null
        }
        fun resultControl(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.packageName?.toString()==i.context.packageName && node.isVisibleToUser && node.isClickable && node.text?.toString()=="공개 결과 항목 2")return node
            for(index in 0 until node.childCount)node.getChild(index)?.let{resultControl(it)?.let{return it}}
            return null
        }
        try{
            main{GuideController.initialize(context);GuideController.stop();LocalInputRevisions.enable(i.context.packageName);AplPreferences.update(context){it.copy(voiceEnabled=false,reduceMotion=true)}}
            val component=ComponentName(context,AssistAccessibilityService::class.java).flattenToString()
            secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,component);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,"1")}
            await("service"){AssistStateRepository.state.value.serviceConnected}
            i.context.startActivity(Intent(i.context,V3FormFixtureActivity::class.java).putExtra("inputMode",true).putExtra("imeProbe",true).putExtra("inputRecheckProbe",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val pkg=i.context.packageName
            await("public input captured"){AssistStateRepository.state.value.snapshot?.let{it.packageName==pkg && it.allNodes.any{n->n.editable && n.localInputRevision!=null && n.hasInput==true}}==true}
            val engine=V3GuideEngine(scope,AssistStateRepository.state,context.packageName,{BridgeStatus(true,"offline fixture")},
                {goal,screen,_,_,_->
                    val call=requests.incrementAndGet();progress=goal.inputProgress
                    val editor=screen.allNodes.single{it.editable};val commit=screen.allNodes.single{it.text=="확정"}
                    val title=screen.allNodes.single{it.text=="공개 양식 시험"}
                    val owned=goal.requirements.singleOrNull{it.description=="공개 준비"}
                    val condition=GoalCondition("ready","공개 준비",true,Truth.SATISFIED,EvidenceKind.OBSERVED,listOf(title.id),owned?.id,owned?.version,
                        if(owned==null)ConditionBasis(ConditionOrigin.INFERRED,quote=goal.request,rationale="공개 시험 준비")else null,ConditionPhase.PREPARATION)
                    val base=TaskPlan(NextKind.GUIDE,"공개 양식 작성",GoalKind.CHANGE,listOf(condition),listOf(TaskStep("write","입력",listOf(editor.id),emptyList()),TaskStep("save","확정",listOf(commit.id),listOf("write"))),
                        emptyList(),if(call==1)Operation.INPUT else Operation.TAP,if(call==1)editor.id else commit.id,
                        if(call==1)"내용을 입력해주세요."else "확정을 눌러주세요.",null,WaitKind.NONE,listOf(editor.id,commit.id),emptyList(),
                        effect=if(call==1)ActionEffect.PREPARE else ActionEffect.COMMIT,scopeNodeIds=listOf(title.id),actionGates=listOf(ActionGate(commit.id,listOf("ready"))))
                    val result=screen.allNodes.singleOrNull{it.text=="공개 결과 항목 2" && it.clickable}
                    if(permitResultNavigation && result!=null && offeredResult.compareAndSet(false,true))base.copy(
                        operation=Operation.TAP,targetNodeId=result.id,effect=ActionEffect.PREPARE,message="공개 결과 항목 2를 눌러주세요.",
                        tasks=base.tasks+TaskStep("review","공개 결과 선택",listOf(result.id),listOf("write")),relevantNodeIds=base.relevantNodeIds+result.id)
                    else base
                },GuideStateRepository::publish,{n->if(n.editable)inputShows.incrementAndGet()else if(n.text=="확정")commitShows.incrementAndGet()
                    else if(n.text=="공개 결과 항목 2")resultShows.incrementAndGet();AssistAccessibilityService.requestGuideHighlight(n)},
                AssistAccessibilityService::clearHighlight,{},{},AssistAccessibilityService::requestRefresh,
                trace={event,_->if(event=="v3_input_completion_blocked")blocked.incrementAndGet()
                    else if(event=="v3_input_post_action_read")timedReads.incrementAndGet()},nowUptime=SystemClock::uptimeMillis,
                inputObservationVersion={LocalInputRevisions.changeVersion})
            fun declare(){await("current enabled input declaration"){
                var accepted=false
                main{
                    val live=AssistStateRepository.state.value.snapshot
                    if(engine.ui.phase=="guiding" && engine.ui.awaitingControlCompletion && engine.ui.controlCompletionEnabled &&
                        engine.ui.controlCompletionLabel=="입력했어요" && live?.inputObservationVersion==LocalInputRevisions.changeVersion)
                        accepted=engine.confirmControl()
                }
                accepted
            }}
            main{oldEngine=field.get(null);field.set(null,engine);injected=true}
            runBlocking{withContext(Dispatchers.Main){assertTrue(engine.start("공개 양식을 작성하고 싶어",pkg,"공개 양식"))}}
            await("input instruction"){engine.ui.controlCompletionLabel=="입력했어요" && engine.ui.controlCompletionEnabled}
            assertTrue(native(false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("unrelated button is not finished input"){blocked.get()==1 && engine.ui.controlCompletionLabel=="입력했어요"}
            assertEquals(0,commitShows.get());assertFalse(progress.single().userFinished)
            declare()
            await("first declared input permits commit guidance"){commitShows.get()==1 && engine.ui.currentAction=="highlight"}
            assertTrue(progress.single().userFinished);assertEquals(1L,progress.single().receiptId)
            val before=AssistStateRepository.state.value.snapshot!!.allNodes.single{it.editable}.localInputRevision
            val eventVersion=LocalInputRevisions.changeVersion
            assertTrue(native(true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"synthetic revised draft")}))
            await("native nonempty edit revokes commit"){engine.ui.controlCompletionLabel=="입력했어요" && engine.ui.controlCompletionEnabled &&
                AssistStateRepository.state.value.snapshot?.allNodes?.singleOrNull{it.editable}?.localInputRevision!=before}
            // An unfocused programmatic edit may emit CONTENT_CHANGED alone. The local keyed comparison must still revoke it.
            assertTrue(LocalInputRevisions.changeVersion>=eventVersion);assertEquals(1,commitShows.get());assertEquals(3,requests.get())
            val firstEditHadTextEvent=LocalInputRevisions.changeVersion>eventVersion
            main{permitResultNavigation=true}
            assertTrue(native(false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("native app control after mutation advances to a newly observed result item"){
                resultShows.get()==1 && requests.get()==4 && engine.ui.message=="공개 결과 항목 2를 눌러주세요."
            }
            assertFalse(progress.single().userFinished);assertEquals(1,commitShows.get());assertTrue(timedReads.get()>=2)
            automation.takeScreenshot()?.let{bitmap->
                context.openFileOutput("qa-v3-input-result.png",android.content.Context.MODE_PRIVATE).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            var resultButton:AccessibilityNodeInfo?=null
            await("actual public result control"){
                if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
                resultButton=automation.windows.mapNotNull{it.root}.mapNotNull{resultControl(it)}.singleOrNull();resultButton!=null
            }
            assertTrue(resultButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("selecting a result still cannot invent finished writing"){
                blocked.get()==2 && requests.get()==5 && engine.ui.controlCompletionLabel=="입력했어요"
            }
            assertFalse(progress.single().userFinished);assertEquals(1,commitShows.get())
            await("current input instruction and declaration button are actually visible"){
                if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
                val roots=automation.windows.mapNotNull{it.root}
                roots.any{hasOverlayText(it,"내용을 입력해주세요.")} && roots.any{hasOverlayText(it,"입력했어요")}
            }
            automation.takeScreenshot()?.let{bitmap->
                context.openFileOutput("qa-v3-input-edited.png",android.content.Context.MODE_PRIVATE).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            declare()
            await("fresh declaration permits fresh guidance"){commitShows.get()==2}
            assertTrue(progress.single().userFinished);assertEquals(2L,progress.single().receiptId)
            val focusedEventVersion=LocalInputRevisions.changeVersion
            val focusedBefore=AssistStateRepository.state.value.snapshot!!.allNodes.single{it.editable}.localInputRevision
            val editor=native(true);assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            assertTrue(native(true).isFocused)
            assertTrue(automation.windows.any{it.isFocused && it.root?.packageName?.toString()==pkg})
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input text q")).use{it.readBytes()}
            await("focused text event revokes the next declaration"){LocalInputRevisions.changeVersion>focusedEventVersion &&
                engine.ui.controlCompletionLabel=="입력했어요" && engine.ui.controlCompletionEnabled &&
                AssistStateRepository.state.value.snapshot?.allNodes?.singleOrNull{it.editable}?.localInputRevision!=focusedBefore}
            assertEquals(2,commitShows.get())
            declare()
            await("third scoped declaration"){commitShows.get()==3}
            assertTrue(progress.single().userFinished);assertEquals(3L,progress.single().receiptId)
            val beforeClear=AssistStateRepository.state.value.snapshot!!.allNodes.single{it.editable}.localInputRevision
            assertTrue(native(true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")
            }))
            await("known empty writing is a new unconfirmed input state"){
                engine.ui.controlCompletionLabel==V3InputCompletion.EMPTY_DONE_LABEL && engine.ui.controlCompletionEnabled &&
                    AssistStateRepository.state.value.snapshot?.allNodes?.singleOrNull{it.editable}?.let{it.hasInput==false && it.localInputRevision!=beforeClear}==true
            }
            val callsBeforeIme=requests.get()
            val emptyEditor=native(true)
            if(!emptyEditor.isFocused)assertTrue(emptyEditor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            val withIme=native(true)
            assertTrue(withIme.isFocused)
            assertTrue(withIme.actionList.any{it.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id})
            assertTrue(withIme.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            await("the test application's real editor-action listener was invoked"){
                AssistStateRepository.state.value.snapshot?.allNodes?.any{it.text=="입력 동작 관찰됨"}==true
            }
            assertEquals(callsBeforeIme,requests.get());assertEquals(3,commitShows.get())
            assertEquals(V3InputCompletion.EMPTY_DONE_LABEL,engine.ui.controlCompletionLabel)
            await("blank declaration is visible in the actual overlay"){
                if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
                automation.windows.mapNotNull{it.root}.any{hasOverlayText(it,V3InputCompletion.EMPTY_DONE_LABEL)}
            }
            automation.takeScreenshot()?.let{bitmap->
                context.openFileOutput("qa-v3-input-empty.png",android.content.Context.MODE_PRIVATE).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            var button:AccessibilityNodeInfo?=null
            await("current clickable blank declaration"){
                if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
                button=automation.windows.mapNotNull{it.root}.mapNotNull{blankControl(it)}.singleOrNull()
                button!=null
            }
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("blank declaration reaches planning without inventing a saved result"){commitShows.get()==4}
            assertTrue(progress.single().userFinished);assertEquals(4L,progress.single().receiptId)
            val blankFrame=AssistStateRepository.state.value.snapshot!!;val blankNode=blankFrame.allNodes.single{it.editable}
            assertEquals(false,blankNode.hasInput)
            val subject=ValueSubject(blankNode.className.orEmpty(),blankNode.viewIdResourceName.orEmpty(),
                if(blankNode.viewIdResourceName.isNullOrBlank())GuidePolicy.label(blankNode)else "")
            val expectedNonblank=ValueExpectation(ValueProperty.INPUT_PRESENT,subject,expectedBoolean=true)
            assertEquals(V3ValueEvidence.Result.MISMATCH,V3ValueEvidence.evaluate(expectedNonblank,blankNode))
            assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(expectedNonblank.copy(expectedBoolean=false),blankNode))
            assertTrue(native(true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"synthetic after blank")
            }))
            await("typing again revokes the blank declaration"){
                engine.ui.controlCompletionLabel==V3InputCompletion.DONE_LABEL && engine.ui.controlCompletionEnabled
            }
            assertEquals(4,commitShows.get())
            val typedNode=AssistStateRepository.state.value.snapshot!!.allNodes.single{it.editable}
            assertEquals(V3ValueEvidence.Result.MATCH,V3ValueEvidence.evaluate(expectedNonblank,typedNode))
            i.sendStatus(0,Bundle().apply{putString("v3InputProof","native_edit=true;presence_unchanged=true;unfocused_text_event=$firstEditHadTextEvent;focused_text_event=true;unrelated_click_blocked=true;planner_calls=${requests.get()};commit_highlights=${commitShows.get()};declarations=4;known_empty=true;empty_requires_declaration=true;typed_after_empty_revoked=true;ime_listener_observed=true;ime_action_mints_finish=false;native_presence_comparison=true;finish_does_not_change_presence=true;edited_control_recheck=true;result_guides=${resultShows.get()};timed_post_action_reads=${timedReads.get()};actual_commits=0")})
        }finally{
            main{GuideController.stop();if(injected)field.set(null,oldEngine);AplPreferences.update(context){prefs};GuideStateRepository.publish(oldUi)}
            scope.cancel();secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,oldServices);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,oldEnabled)}
        }
    }
}
