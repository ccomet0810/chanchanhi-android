package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.ScreenSetupHelp
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

/** Production UI with public fixture data; no actual setting, consent or AI call is changed. */
@RunWith(AndroidJUnit4::class)
class PermissionSettingsDeviceTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>();fun visit(node:AccessibilityNodeInfo?){node?:return;result+=node;repeat(node.childCount){visit(node.getChild(it))}};visit(automation.rootInActiveWindow);return result}
    private fun node(text:String)=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()==text||it.contentDescription?.toString()==text)}
    private fun action(text:String):AccessibilityNodeInfo?{var n=node(text)?:return null;while(!n.isClickable)n=n.parent?:return null;return n}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(70)};capture("failure");error(message)}
    private fun click(text:String){repeat(30){val n=action(text);if(n!=null&&n.isEnabled&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;if(n==null)nodes().firstOrNull{it.isVisibleToUser&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(100)};error("Missing action: $text")}
    private fun capture(name:String){Thread.sleep(250);automation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"permission-settings-checks/$name.png").apply{parentFile?.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    @Test fun settingsLessonsHaveAccurateReadyRecoveryAndParticipantNavigation(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPermissionSettings")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui{AppRuntime.initialize(context)}
        check(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE)){"Do not interrupt an active guide"}
        val owner=AppRuntime.study.state.value;val originalPip=ScreenSetupHelp.active.value
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        var host:MainActivity?=null;var revision=0
        try{
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            host=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Main activity missing")
            fun show(content:@Composable ()->Unit){val id=++revision;ui{host.setContent{ChanchanhiTheme{key(id){content()}}}}}
            val profile=JSONObject().put("name","공개 시험").put("age",60).put("gender","응답하지 않음").put("occupation","시험")
            val participant=StudyState(phase=StudyPhase.COMPLETE,participantId="public-participant",profile=profile)
            var details by mutableStateOf(false)
            show{if(details)StudyConsentDetails{details=false}else ParticipantSettingsPage(participant,{},{},{details=true},{})}
            await("Participant page missing"){node("실험 참여 정보")!=null}
            assertNull(node("현재 진행"));click("수집 내용 자세히 보기")
            await("Text link must navigate to the shared details page"){node("수집 내용")!=null}
            click("뒤로가기");await("Back must preserve participant context"){node("실험 참여 정보")!=null};capture("participant")

            show{PermissionSettingsPage(SetupRequirement.SCREEN,true,{},{error("Ready settings must not open a setup request")})}
            await("Ready screen service must be presented as complete"){node("화면 안내가 켜져 있어요.")!=null&&node("설치된 앱을 눌러주세요.")!=null}
            assertNull(node("연결 확인 중"));assertNull(node("앱 권한 설정하기"));assertNotNull(action("확인"))
            click("일시정지");capture("screen-ready")
            click("이전 장면");await("Combined lesson must include moving PiP"){node("버튼을 가리면 끌어서 옮겨요")!=null}
            click("이전 장면");await("Combined lesson must include resizing PiP"){node("두 손가락으로 크기를 바꿔요")!=null}
            click("이전 장면");await("Combined lesson must include enlarging PiP"){node("작은 창을 두 번 누르면 커져요")!=null}
            repeat(6){click("다음 장면");Thread.sleep(50)}
            await("Combined lesson must finish with the protected Allow action"){node("허용을 눌러주세요.")!=null}
            capture("screen-allow")
            assertEquals("Viewing a settings lesson must not launch live PiP",originalPip,ScreenSetupHelp.active.value)

            var ready by mutableStateOf(false);var retries=0
            show{PermissionSettingsPage(SetupRequirement.SCREEN,ready,{},{retries++},connecting=!ready)}
            await("Initial binding must be shown honestly"){node("연결 확인 중")!=null}
            await("Unbound service must offer recovery instead of waiting forever"){action("설정에서 확인하기")?.isEnabled==true}
            capture("screen-recovery");click("설정에서 확인하기");assertEquals(1,retries)
            ui{ready=true}
            await("A real connection must clear stale recovery copy"){node("화면 안내가 켜져 있어요.")!=null&&action("확인")?.isEnabled==true}
            assertNull(node("화면 안내가 켜져 있지만 연결되지 않았어요."))

            show{PermissionSettingsPage(SetupRequirement.ASSISTANT,false,{},{error("Preview must not change the assistant")})}
            await("Assistant lesson must start at settings search"){node("설정에서 어시스턴트를 검색해주세요.")!=null}
            click("일시정지");capture("assistant-search")
            click("다음 장면");await("Search result missing"){node("디지털 어시스턴트 앱을 눌러주세요.")!=null}
            click("다음 장면");await("Default selection step missing"){node("기본 디지털 어시스턴트 앱을 눌러주세요.")!=null}
            click("다음 장면");await("App selection step missing"){node("찬찬히를 눌러주세요.")!=null}
            click("재생");Thread.sleep(1200);capture("assistant-choose")
            assertEquals(owner,AppRuntime.study.state.value);assertEquals(originalPip,ScreenSetupHelp.active.value)
        }finally{ui{host?.setContent{};host?.recreate()};i.removeMonitor(monitor)}
    }
}
