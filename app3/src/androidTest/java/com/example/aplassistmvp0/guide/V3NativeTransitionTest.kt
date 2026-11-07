package com.example.aplassistmvp0.guide

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicInteger

/** Same-window public text mutation, real service and engine; no click or network completion is fabricated. */
@RunWith(AndroidJUnit4::class)
class V3NativeTransitionTest {
    @Test fun aReadonlyRelevantStatusChangeWakesAWaitingPlanWithoutAnAppButton(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        check(InstrumentationRegistry.getArguments().getString("qaV3Native")=="true" && Build.HARDWARE in setOf("ranchu","goldfish"))
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val resolver=context.contentResolver
        val oldServices=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        AplPreferences.initialize(context);val prefs=AplPreferences.get(context);val oldUi=GuideStateRepository.state.value
        val field=GuideController::class.java.getDeclaredField("engine").apply{isAccessible=true};var oldEngine:Any?=null;var injected=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val calls=AtomicInteger();val changes=AtomicInteger()
        val readRetries = AtomicInteger()
        val readTimeouts = AtomicInteger()
        var writingDeclarations=false;var outcomeClaimed=false
        fun main(block:()->Unit)=i.runOnMainSync(block)
        fun secure(block:()->Unit){automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);try{block()}finally{automation.dropShellPermissionIdentity()}}
        fun await(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12_000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(80)};error("$label; phase=${GuideStateRepository.state.value.phase}")}
        val geometryProof=org.json.JSONArray()
        fun hasCaption(node:android.view.accessibility.AccessibilityNodeInfo,text:String):Boolean {
            if(node.isVisibleToUser && node.text?.toString()==text)return true
            return (0 until node.childCount).any{index->node.getChild(index)?.let{hasCaption(it,text)}==true}
        }
        fun buttons(node:android.view.accessibility.AccessibilityNodeInfo):List<android.graphics.Rect> {
            val own=if(node.isVisibleToUser && node.className?.toString()=="android.widget.Button")
                listOf(android.graphics.Rect().also{node.getBoundsInScreen(it)})else emptyList()
            return own+(0 until node.childCount).flatMap{index->node.getChild(index)?.let(::buttons).orEmpty()}
        }
        data class NativePanel(val bounds:android.graphics.Rect,val controls:List<android.graphics.Rect>,val safe:android.graphics.Rect)
        fun panelSnapshot(caption:String):NativePanel? {
            if(Build.VERSION.SDK_INT>=33)automation.clearCache()
            val window=automation.windows.singleOrNull{it.title?.toString()=="APL2 행동 안내"} ?:return null
            val root=window.root ?:return null
            if(!hasCaption(root,caption))return null
            val bounds=android.graphics.Rect().also{window.getBoundsInScreen(it)}.takeUnless{it.isEmpty} ?:return null
            val metrics=context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            val insets=metrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val safe=android.graphics.Rect(metrics.bounds).apply{left+=insets.left;top+=insets.top;right-=insets.right;bottom-=insets.bottom}
            return NativePanel(bounds,buttons(root),safe)
        }
        fun uncovered(label:String){
            var matched:NativePanel?=null;var matchedTarget:com.example.aplassistmvp0.accessibility.NodeBounds?=null
            var unsafeSamples=0
            await("current caption, safe buttons and exposed '$label' must settle together"){
                val node=AssistStateRepository.state.value.snapshot?.allNodes?.singleOrNull{it.text==label}
                val panel=panelSnapshot(GuideStateRepository.state.value.message)
                val safe=panel!=null && panel.controls.size>=2 && panel.controls.all{panel.safe.contains(it)}
                if(panel!=null && !safe)unsafeSamples++
                val ready=node!=null && panel!=null && safe && !android.graphics.Rect.intersects(panel.bounds,
                    android.graphics.Rect(node.bounds.left,node.bounds.top,node.bounds.right,node.bounds.bottom))
                if(ready){matched=panel;matchedTarget=node!!.bounds};ready
            }
            val sample=matched!!;val panel=sample.bounds;val target=matchedTarget!!;val safe=sample.safe;val controls=sample.controls
            geometryProof.put(org.json.JSONObject().put("stage",geometryProof.length()+1).put("nativeCaptionVisible",true)
                .put("unsafeGeometrySamplesBeforeSettling",unsafeSamples).put("endStateOnlyNotContinuousFrames",true)
                .put("panel",org.json.JSONArray(listOf(panel.left,panel.top,panel.right,panel.bottom)))
                .put("target",org.json.JSONArray(listOf(target.left,target.top,target.right,target.bottom))).put("overlapArea",0)
                .put("systemSafeBounds",org.json.JSONArray(listOf(safe.left,safe.top,safe.right,safe.bottom)))
                .put("buttonBounds",org.json.JSONArray(controls.map{org.json.JSONArray(listOf(it.left,it.top,it.right,it.bottom))})))
        }
        fun screenshot(name:String){automation.takeScreenshot()?.let{bitmap->
            context.openFileOutput(name,android.content.Context.MODE_PRIVATE).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        try{
            main{GuideController.initialize(context);GuideController.stop();AplPreferences.update(context){it.copy(voiceEnabled=false,reduceMotion=true)}}
            val component=ComponentName(context,AssistAccessibilityService::class.java).flattenToString()
            secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,component);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,"1")}
            await("service"){AssistStateRepository.state.value.serviceConnected}
            val launched=SystemClock.uptimeMillis();val pkg=i.context.packageName
            i.context.startActivity(Intent(i.context,V3FormFixtureActivity::class.java).putExtra("transitionProbe",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("fresh public loading screen"){
                AssistStateRepository.state.value.let{s->s.serviceConnected && s.foregroundPackage==pkg && s.snapshot?.let{
                    it.packageName==pkg && (it.captureStartedAtUptimeMillis?:0)>=launched && it.allNodes.any{n->n.text=="검색 중"} && it.allNodes.none{n->n.editable}
                }==true}
            }
            val engine=V3GuideEngine(scope,AssistStateRepository.state,context.packageName,{BridgeStatus(true,"offline transition fixture")},
                {goal,frame,_,_,_->
                    calls.incrementAndGet();writingDeclarations=writingDeclarations || goal.inputProgress.any{it.userFinished}
                    val loading=frame.allNodes.any{it.text=="검색 중"}
                    val status=frame.allNodes.single{it.text==if(loading)"검색 중"else "검색 결과 없음"}
                    val owned=goal.requirements.singleOrNull()
                    val condition=GoalCondition("result","원하는 결과 확인",true,Truth.UNKNOWN,EvidenceKind.UNKNOWN,emptyList(),owned?.id,owned?.version,
                        if(owned==null)ConditionBasis(ConditionOrigin.REQUEST,quote=goal.request)else null)
                    val value=TaskPlan(if(loading)NextKind.WAIT else NextKind.OBSERVE,"현재 검색 상태",GoalKind.STATE,listOf(condition),emptyList(),emptyList(),Operation.NONE,null,
                        if(loading)"검색이 끝날 때까지 기다려주세요."else "현재 검색 결과를 확인해주세요.",null,
                        if(loading)WaitKind.SCREEN_TRANSITION else WaitKind.NONE,listOf(status.id),emptyList())
                    outcomeClaimed=outcomeClaimed || value.kind==NextKind.COMPLETE;value
                },GuideController::publishState,{false},AssistAccessibilityService::clearHighlight,{},{},AssistAccessibilityService::requestRefresh,
                trace = { event, _ ->
                    when (event) {
                        "v3_transition_observation_changed" -> changes.incrementAndGet()
                        "v3_observation_retry" -> readRetries.incrementAndGet()
                        "v3_observation_timeout" -> readTimeouts.incrementAndGet()
                    }
                }, nowUptime=SystemClock::uptimeMillis)
            main{oldEngine=field.get(null);field.set(null,engine);injected=true}
            runBlocking{withContext(Dispatchers.Main){assertTrue(engine.start("검색 상태를 확인하고 싶어",pkg,"공개 검색"))}}
            await("loading wait"){calls.get()==1 && engine.ui.currentAction=="wait"}
            uncovered("검색 중")
            repeat(3){
                val before=AssistStateRepository.state.value.snapshot!!.snapshotId
                main{AssistAccessibilityService.requestRefresh()}
                await("repeated unchanged capture"){(AssistStateRepository.state.value.snapshot?.snapshotId?:0)>before}
                assertEquals(1,calls.get())
            }
            val before=AssistStateRepository.state.value.snapshot!!
            i.context.startActivity(Intent(i.context,V3FormFixtureActivity::class.java).putExtra("finishTransition",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("readonly result text wakes the current plan"){
                calls.get()==2 && engine.ui.currentAction=="manual" && engine.ui.message=="현재 검색 결과를 확인해주세요."
            }
            val after=AssistStateRepository.state.value.snapshot!!
            assertEquals(before.windowId,after.windowId);assertEquals(before.sourceEpoch,after.sourceEpoch)
            assertTrue(after.allNodes.any{it.text=="검색 결과 없음"});assertTrue(after.allNodes.none{it.clickable || it.editable})
            assertEquals(1,changes.get());assertFalse(writingDeclarations);assertFalse(outcomeClaimed)
            uncovered("검색 결과 없음");screenshot("qa-v3-transition-status.png")
            i.context.startActivity(Intent(i.context,V3FormFixtureActivity::class.java).putExtra("statusAtBottom",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("same public status moves to the lower screen"){
                AssistStateRepository.state.value.snapshot?.allNodes?.singleOrNull{it.text=="검색 결과 없음"}?.bounds?.top?.let{it>after.viewport!!.bottom/2}==true
            }
            uncovered("검색 결과 없음");assertEquals(2,calls.get());assertEquals(1,changes.get())
            screenshot("qa-v3-transition-status-moved.png")
            context.openFileOutput("qa-v3-overlay-proof.json",android.content.Context.MODE_PRIVATE).bufferedWriter().use{it.write(geometryProof.toString(2))}

            fun reportMissingChild(missing: Boolean) {
                i.context.startActivity(Intent(i.context, V3FormFixtureActivity::class.java)
                    .putExtra("reportMissingChild", missing)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            val traceStarted = System.currentTimeMillis()
            reportMissingChild(true)
            await("missing native child holds the current instruction") {
                AssistStateRepository.state.value.snapshot?.truncated == true && engine.ui.phase == "waiting_screen"
            }
            assertNull(engine.ui.currentAction)
            await("the bounded wait ends in manual recovery") { engine.ui.phase == "error" }
            assertEquals(2, calls.get())
            assertEquals(1, readRetries.get())
            assertEquals(1, readTimeouts.get())
            val incompleteCaptures = GuideTrace.events.value.filter { entry ->
                entry.time >= traceStarted && entry.stage == "capture_complete" && entry.fields["accepted"] == "true" &&
                    entry.fields["truncated"] == "true"
            }
            assertTrue("unexpected read diagnostics: $incompleteCaptures", incompleteCaptures.any { entry ->
                    entry.fields["missingChildren"] == "1" && entry.fields["childQueryFailures"] == "0" &&
                    entry.fields["propertyFailures"] == "0" && entry.fields["nodeBudgetReached"] == "false" &&
                    entry.fields["depthLimitReached"] == "false"
            })
            reportMissingChild(false)
            await("the complete native observation is available again") {
                AssistStateRepository.state.value.snapshot?.truncated == false
            }
            assertEquals(2, calls.get())
            var retryButton: android.view.accessibility.AccessibilityNodeInfo? = null
            await("the overlay shows its enabled retry button") {
                val root = automation.windows.singleOrNull { it.title?.toString() == "APL2 행동 안내" }?.root
                retryButton = root?.findAccessibilityNodeInfosByText("다시 시도")?.singleOrNull {
                    it.isVisibleToUser && it.isEnabled && it.isClickable && it.text?.toString() == "다시 시도"
                }
                retryButton != null
            }
            assertTrue(retryButton!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("manual retry restores the same public guidance") { calls.get() == 3 && engine.ui.currentAction == "manual" }
            i.sendStatus(0, Bundle().apply {
                putString("v3IncompleteReadProof", "missing_children=1;automatic_retries=1;manual_recovery=true;planner_calls_during_partial=0;native_retry_button=true;manual_retry_restored_guidance=true")
            })
            i.sendStatus(0,Bundle().apply{putString("v3TransitionProof","same_window=true;same_source=true;readonly_text_changed=true;button_clicks=0;unchanged_captures=3;planner_calls=2;change_signals=1;writing_declarations=0;actual_commits=0;fixture_intent_update=true;native_panel_bounds=true;actual_caption_verified=true;loading_status_exposed=true;result_status_exposed=true;moved_status_exposed=true;layout_move_replans=0")})
        }finally{
            main{GuideController.stop();if(injected)field.set(null,oldEngine);AplPreferences.update(context){prefs};GuideStateRepository.publish(oldUi)}
            scope.cancel();secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,oldServices);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,oldEnabled)}
        }
    }
}
