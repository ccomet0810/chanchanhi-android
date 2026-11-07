package com.example.aplassistmvp0.guide

import android.Manifest
import android.app.AlertDialog
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.accessibility.LocalInputRevisions
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/** Real question windows and controls, synthetic choice only; no microphone, network or app commit. */
@RunWith(AndroidJUnit4::class)
class V3NativeQuestionTest {
    @Test fun interruptionClosesTheQuestionAndAnOldSubmitCannotAnswerItsReplacement(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        check(InstrumentationRegistry.getArguments().getString("qaV3Native")=="true" && Build.HARDWARE in setOf("ranchu","goldfish"))
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=context.contentResolver
        val oldServices=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        AplPreferences.initialize(context);val prefs=AplPreferences.get(context);val oldUi=GuideStateRepository.state.value
        val history=context.getSharedPreferences("guide-history",Context.MODE_PRIVATE);val oldHistory=history.getString("entries",null)
        val oldEntries=GuideHistory.entries.value
        val engineField=GuideController::class.java.getDeclaredField("engine").apply{isAccessible=true}
        val panelField=GuideController::class.java.getDeclaredField("questionPanel").apply{isAccessible=true}
        val dialogField=V3QuestionPanel::class.java.getDeclaredField("dialog").apply{isAccessible=true}
        var oldEngine:Any?=null;var injected=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val calls=AtomicInteger()
        val events=ConcurrentLinkedQueue<String>()
        var shownAfterSequence=0L
        var acceptedAnswer:String?=null
        fun main(block:()->Unit)=i.runOnMainSync(block)
        fun secure(block:()->Unit){automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);try{block()}finally{automation.dropShellPermissionIdentity()}}
        fun await(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12_000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(80)};error("$label: phase=${GuideStateRepository.state.value.phase}, foreground=${AssistStateRepository.state.value.foregroundPackage}, sequence=${AssistStateRepository.state.value.snapshot?.snapshotId}, floor=$shownAfterSequence, events=$events")}
        fun dialog():AlertDialog? {var found:AlertDialog?=null;main{panelField.get(null)?.let{found=dialogField.get(it) as?AlertDialog}};return found}
        fun editor(view:View):EditText? {
            if(view is EditText)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)editor(view.getChildAt(index))?.let{return it}
            return null
        }
        try{
            main{GuideController.initialize(context);GuideController.stop();AplPreferences.update(context){it.copy(voiceEnabled=false,reduceMotion=true)}}
            val component=ComponentName(context,AssistAccessibilityService::class.java).flattenToString()
            secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,component);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,"1")}
            await("service connected"){AssistStateRepository.state.value.serviceConnected}
            val launchedAt=SystemClock.uptimeMillis()
            i.context.startActivity(Intent(i.context,V3FormFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val pkg=i.context.packageName
            await("fresh public fixture without the previous test's editor"){
                AssistStateRepository.state.value.let{state->state.serviceConnected && state.foregroundPackage==pkg && state.snapshot?.let{
                    it.packageName==pkg && (it.captureStartedAtUptimeMillis?:0)>=launchedAt && it.allNodes.none{node->node.editable} &&
                        it.allNodes.any{node->node.text=="공개 양식 시험"}
                }==true}
            }
            val engine=V3GuideEngine(scope,AssistStateRepository.state,context.packageName,{BridgeStatus(true,"offline question fixture")},
                {goal,_,_,_,_->calls.incrementAndGet();acceptedAnswer=goal.answers["choice"]
                    if(acceptedAnswer==null)TaskPlan(NextKind.ASK,"안내 방식 선택",GoalKind.STATE,emptyList(),emptyList(),emptyList(),Operation.NONE,null,
                        "어떤 방식으로 안내할까요?",PlanQuestion("choice","어떤 방식으로 안내할까요?",true,true,false),WaitKind.NONE,emptyList(),emptyList())
                    else TaskPlan(NextKind.OBSERVE,"선택한 안내 유지",GoalKind.STATE,emptyList(),emptyList(),emptyList(),Operation.NONE,null,
                        "현재 화면을 확인해주세요.",null,WaitKind.NONE,emptyList(),emptyList())
                },{state->if(state.showsScopedQuestion && !GuideStateRepository.state.value.showsScopedQuestion)shownAfterSequence=AssistStateRepository.state.value.snapshot?.snapshotId?:0
                    events+="ui:${state.phase}:${state.currentAction}";GuideController.publishState(state)},{true},{},{},{},AssistAccessibilityService::requestRefresh,
                trace={kind,data->if(kind=="v3_foreground_invalidated" || kind.startsWith("v3_question_observation_"))events+="$kind:$data"},nowUptime=SystemClock::uptimeMillis,
                inputObservationVersion={LocalInputRevisions.changeVersion})
            main{oldEngine=engineField.get(null);engineField.set(null,engine);injected=true}
            runBlocking{withContext(Dispatchers.Main){assertTrue(engine.start("공개 양식의 안내 방식을 고르고 싶어",pkg,"공개 양식"))}}
            await("first actual question window"){dialog()?.let{it.isShowing && it.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled}==true && engine.ui.showsScopedQuestion &&
                AssistStateRepository.state.value.let{it.foregroundPackage==pkg && (it.snapshot?.snapshotId?:0)>shownAfterSequence}}
            val first=checkNotNull(dialog()){"Question disappeared after showing: $events"};val firstQuestion=engine.question();lateinit var oldSubmit:Button
            main{editor(first.window!!.decorView)!!.setText("지연된 합성 답변");oldSubmit=first.getButton(AlertDialog.BUTTON_POSITIVE)
                GuideController.onScreenUnavailable("공개 시험 중단")}
            await("question hidden after interruption"){dialog()?.isShowing!=true && !engine.ui.showsScopedQuestion}
            main{assertFalse(engine.answer("choice","지연된 합성 답변"));assertFalse(engine.confirmControl());GuideController.confirmControl()}
            assertEquals(1,calls.get());assertNull(acceptedAnswer);assertTrue(dialog()?.isShowing!=true)
            runBlocking{withContext(Dispatchers.Main){assertTrue(engine.retryFromCurrent())}}
            await("replacement actual question"){dialog()?.let{it.isShowing && it.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled}==true && engine.ui.showsScopedQuestion && calls.get()==2 &&
                AssistStateRepository.state.value.let{it.foregroundPackage==pkg && (it.snapshot?.snapshotId?:0)>shownAfterSequence}}
            val second=dialog()!!;assertNotSame(first,second);assertNotSame(firstQuestion,engine.question())
            main{oldSubmit.performClick();assertTrue(second.isShowing)}
            assertNull(acceptedAnswer);assertEquals(2,calls.get())
            main{editor(second.window!!.decorView)!!.setText("글로");second.getButton(AlertDialog.BUTTON_POSITIVE).performClick()}
            await("current answer accepted once"){calls.get()==3 && acceptedAnswer=="글로" && engine.ui.currentAction=="manual"}
            assertTrue(dialog()?.isShowing!=true)
            i.sendStatus(0,Bundle().apply{putString("v3QuestionProof","actual_dialogs=2;simulated_interrupt=true;late_answer_rejected=true;old_submit_rejected=true;current_answer_accepted_once=true;planner_calls=3;microphone_used=false;actual_commits=0")})
        }finally{
            main{GuideController.stop();if(injected)engineField.set(null,oldEngine);AplPreferences.update(context){prefs};GuideStateRepository.publish(oldUi)
                val f=GuideHistory::class.java.getDeclaredField("mutableEntries").apply{isAccessible=true}
                @Suppress("UNCHECKED_CAST") val flow=f.get(null) as kotlinx.coroutines.flow.MutableStateFlow<List<GuideHistoryEntry>>;flow.value=oldEntries}
            scope.cancel()
            if(oldHistory==null)history.edit().remove("entries").commit()else history.edit().putString("entries",oldHistory).commit()
            secure{Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,oldServices);Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,oldEnabled)}
        }
    }
}
