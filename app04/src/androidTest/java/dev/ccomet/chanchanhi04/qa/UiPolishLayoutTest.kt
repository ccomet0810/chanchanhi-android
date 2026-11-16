package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiPolishLayoutTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes().toString(Charsets.UTF_8).trim()}}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(message)}
    private fun nodes():List<AccessibilityNodeInfo>{val result=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){if(n==null)return;result+=n;repeat(n.childCount){visit(n.getChild(it))}};automation.windows.mapNotNull{it.root}.filter{it.packageName?.toString()==context.packageName}.forEach(::visit);return result}
    private fun match(label:String)=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()?.split('\n')?.contains(label)==true||it.contentDescription?.toString()==label)}
    private fun click(label:String){var n=match(label)?:error("Control missing: $label");while(!n.isClickable)n=n.parent?:error("Control not clickable: $label");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun touch(node:AccessibilityNodeInfo){val r=Rect();node.getBoundsInScreen(r);val t=SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val event=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0);event.source=InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(event,true));event.recycle();Thread.sleep(80)}}
    private fun capture(name:String){i.waitForIdleSync();Thread.sleep(450);val out=File(context.getExternalFilesDir(null),"ui-polish-checks").apply{mkdirs()};automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun realSettingsRemainReadableAtTwoFontScalesAndAnswerStaysAboveIme(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPublicUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}}
        val oldScale=shell("settings get system font_scale")
        val oldHardwareIme=shell("settings get secure show_ime_with_hard_keyboard")
        ui{AppRuntime.initialize(context);AppRuntime.loop.stop()}
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService";val services=shell("settings get secure enabled_accessibility_services");check(component in services.split(':'))
        if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shell("settings put secure enabled_accessibility_services $services")}}
        await("Screen service must rebind"){AppRuntime.service!=null};val service=AppRuntime.service!!
        try{
            shell("settings put secure show_ime_with_hard_keyboard 1")
            for(scale in listOf("1.0","2.0")){
                shell("settings put system font_scale $scale");Thread.sleep(400)
                i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                await("Settings entry missing"){match("설정")!=null};click("설정");await("Settings page missing"){match("화면 안내")!=null}
                capture("settings-font-$scale.png")
            }
            shell("settings put system font_scale $oldScale");Thread.sleep(400)
            // System settings may be stacked in the caller's task. Returning must reveal Main,
            // rather than merely bring its task (still showing Settings) to the foreground.
            ui{
                val host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
                host.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))
            }
            await("Public app settings missing"){automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"}
            ui{AppRuntime.openStudy()};await("Returning from setup left Settings on top"){match("찬찬히")!=null||match("돌아가기")!=null}
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Public fixture missing"){automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName};Thread.sleep(400)
            val screen=runBlocking{service.observe()}
            // This direct renderer fixture represents an explicitly resumed guide after settings.
            ui{service.suppressGuidance(false);service.render(AgentState("public-layout",Phase.QUESTION,"공개 입력 배치 검증","사용할 옵션을 알려주세요.",pending=ToolCall("answer",Tool.ASK_USER,"공개 UI 검증",question="사용할 옵션을 알려주세요."),screen=screen))}
            await("Text answer entry missing"){match("글로 답하기")!=null};click("글로 답하기")
            await("Answer editor missing"){nodes().any{it.isEditable&&it.isVisibleToUser}};touch(nodes().first{it.isEditable&&it.isVisibleToUser})
            await("Actual IME missing"){automation.windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            val ime=Rect().also{r->automation.windows.first{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}.getBoundsInScreen(r)}
            await("Answer panel overlaps the IME"){(service.overlayDiagnostics()["bottom"] as? Int?:Int.MAX_VALUE)<=ime.top}
            val submit=match("답변 보내기")?:error("Answer submit must remain reachable");val r=Rect();submit.getBoundsInScreen(r);assertTrue(r.bottom<=ime.top)
            // No text or private keyboard suggestions are stored in evidence.
            val out=File(context.getExternalFilesDir(null),"ui-polish-checks/ime-layout.json");out.writeText("{\"imeTop\":${ime.top},\"panelBottom\":${service.overlayDiagnostics()["bottom"]},\"submitBottom\":${r.bottom}}")
        }finally{
            if(oldHardwareIme in listOf("0","1"))shell("settings put secure show_ime_with_hard_keyboard $oldHardwareIme")
            if(oldScale=="null")shell("settings delete system font_scale")else shell("settings put system font_scale $oldScale")
            shell("input keyevent 4");ui{service.render(AgentState());AppRuntime.loop.stop()}
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
