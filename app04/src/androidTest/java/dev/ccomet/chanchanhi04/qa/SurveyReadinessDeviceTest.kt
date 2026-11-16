package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.*
import dev.ccomet.chanchanhi04.study.*
import dev.ccomet.chanchanhi04.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Public fixture UI only: no real participant, permission, server or AI action is changed. */
@RunWith(AndroidJUnit4::class)
class SurveyReadinessDeviceTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private val out get()=File(context.getExternalFilesDir(null),"survey-readiness-checks").apply{mkdirs()}
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}};visit(automation.rootInActiveWindow);return all}
    private fun node(text:String)=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()==text||it.contentDescription?.toString()==text)}
    private fun capture(name:String){Thread.sleep(350);automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};capture("failure.png");error(message+"; "+nodes().mapNotNull{it.text}.joinToString(" | ").take(800))}
    private fun action(text:String):AccessibilityNodeInfo?{var n=node(text)?:return null;while(!n.isClickable)n=n.parent?:return null;return n}
    private fun click(text:String){repeat(20){val n=action(text);if(n!=null&&n.isEnabled&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;if(n==null)nodes().firstOrNull{it.isVisibleToUser&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(150)};error("Action missing: $text")}
    private fun fixture():StudyRepository {
        val suffix=UUID.randomUUID().toString()
        val isolated=object:ContextWrapper(context){
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(context.cacheDir,"survey-readiness-$suffix").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("survey-$suffix-$name",mode)
        }
        val rules=StudyRules(JSONObject(context.assets.open("pilot-7.json").bufferedReader().use{it.readText()}))
        val run=UUID.randomUUID().toString();val rows=mutableListOf<JSONObject>()
        fun add(kind:String,data:JSONObject){rows+=JSONObject().put("seq",rows.size+1).put("at",System.currentTimeMillis()).put("kind",kind).put("data",data)}
        add("consent_given",JSONObject().put("templateVersion",rules.version).put("consentVersion",rules.template.getString("consentVersion")).put("records",true).put("captures",false).put("participantId",UUID.randomUUID().toString()).put("runId",run).put("testFixture",true))
        add("profile_submitted",JSONObject().put("name","공개 시험").put("age",70).put("gender","응답하지 않음").put("occupation","시험 자료"))
        add("baseline_submitted",JSONObject().apply{rules.questions("baseline").forEach{put(it.getString("id"),3)}})
        add("mission_assigned",JSONObject().apply{val preset=rules.defaultPreset()!!;listOf("id","title","description","goal").forEach{put(it,preset.get(it))}})
        File(StudyRepository.directory(isolated),"$run.jsonl").writeText(rows.joinToString("\n",postfix="\n"))
        isolated.getSharedPreferences("study-1",0).edit().putString("active",run).commit()
        return StudyRepository(isolated,{})
    }
    @Test fun pairedSurveySkipsReasonAndUsesTheSharedCompletion(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runSurveyUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui{AppRuntime.initialize(context)}
        check(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE)){"Do not interrupt the owner's guide"}
        val owner=AppRuntime.study.state.value;val repo=fixture()
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        var host:MainActivity?=null
        try{
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            host=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Main missing")
            ui{host.setContent{ChanchanhiTheme{StudyScreen({},{error("No real mission may start")},{},{},SetupSnapshot(true,true,true),{},repository=repo,screenConnected=true)}}}
            val knowledge=repo.rules.defaultPreset()!!.getJSONObject("knowledge").getString("question")
            await("Question ten must ask about the side-button setting"){node(knowledge)!=null&&node("설문 조사 10/19")!=null}
            assertNotNull(node("찬찬히"));assertNotNull(node("설정"));assertNull(node("뒤로가기"));capture("10-knowledge.png")
            click("조금 알지만 도움이 필요해요");click("다음")
            await("Independent confidence must be question eleven"){node("설문 조사 11/19")!=null&&node(repo.rules.mission.getString("confidenceQuestion"))!=null}
            capture("11-confidence.png");click("별로 자신 없어요");click("다음")
            await("Mission must follow confidence without a subjective question"){repo.state.value.phase==StudyPhase.TASK&&node("미션 수행")!=null&&node("설문 조사 12/19")!=null}
            assertNull(repo.state.value.missionReason);assertFalse(nodes().any{it.isEditable});assertFalse(repo.rules.missionFor(repo.state.value).getString("description").contains("마이크"));capture("12-mission.png")
            click("이전");await("Confidence must be editable when returning"){node("설문 조사 11/19")!=null}
            click("매우 자신 있어요");click("다음")
            await("Revised confidence must return to the current mission"){node("미션 수행")!=null&&repo.state.value.confidence==5}
            val missionId=repo.state.value.mission!!.getString("id")
            // Fixture-only result; no phone setting is changed and no real task is claimed complete.
            repo.submit("guidance_linked",JSONObject().put("missionId",missionId).put("sessionId","public-survey-session-0006"))
            repo.submit("mission_result_submitted",JSONObject().put("missionId",missionId).put("outcome","마쳤어요"))
            repo.rules.questions("post").forEachIndexed{index,q->
                await("Post question missing: $index"){node(q.getString("text"))!=null}
                click(repo.rules.options(q)[3]);click(if(index==repo.rules.questions("post").lastIndex)"설문 마치기"else"다음")
            }
            await("Completion must be the final counted screen"){node("설문 조사 19/19")!=null&&node("설문을 마쳤어요")!=null&&node("설문에 참여해주셔서 감사합니다.")!=null}
            assertNotNull(node("찬찬히"));assertNotNull(node("설정"));assertNull(node("잘하셨어요!"));capture("19-thank-you.png")
            assertEquals(2,repo.state.value.initialAnswers.getJSONObject("mission_pre").getInt("confidence"))
            ui{host.setContent{ChanchanhiTheme{PermissionsOnboardingPage(PermissionJourney(PermissionStage.COMPLETE),true,true,MicrophonePermissionStatus.READY,ScreenSetupPresentation("다음",ready=true),{},{},{},{},{},{})}}}
            await("Permissions must use the same completion classification"){node("권한 설정 3/3")!=null&&node("설정을 모두 마쳤어요")!=null}
            assertNull(node("앱 권한 설정하기"));capture("permission-progress.png")
            ui{host.setContent{ChanchanhiTheme{PermissionsOnboardingPage(PermissionJourney(PermissionStage.MICROPHONE),true,true,MicrophonePermissionStatus.AVAILABLE,ScreenSetupPresentation("다음",ready=true),{},{},{},{},{},{})}}}
            await("Microphone permission animation must show the foreground-allow instruction"){node("앱 사용 중에만 허용을 눌러주세요.")!=null&&node("앱 권한 설정하기")!=null}
            Thread.sleep(1800);capture("microphone-permission-animation.png")
            if(node("일시정지")!=null){click("일시정지");await("Animation pause must use the shared player"){node("재생")!=null}}
            assertEquals(owner,AppRuntime.study.state.value)
        }finally{
            ui{host?.setContent{};host?.recreate()};i.removeMonitor(monitor)
        }
    }
}
