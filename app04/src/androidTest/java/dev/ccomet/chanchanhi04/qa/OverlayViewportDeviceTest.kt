package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual accessibility window over a public fixture; no AI action or participant answer. */
@RunWith(AndroidJUnit4::class)
class OverlayViewportDeviceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun await(message:String,condition:()->Boolean){val until=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<until){if(condition())return;Thread.sleep(100)};error(message)}
    private fun nodes():List<AccessibilityNodeInfo>{
        automation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?){node?:return;result+=node;repeat(node.childCount){visit(node.getChild(it))}}
        automation.windows.forEach{visit(it.root)};return result
    }
    private fun bounds(node:AccessibilityNodeInfo)=Rect().also{node.getBoundsInScreen(it)}
    private fun capture(name:String){Thread.sleep(250);automation.takeScreenshot()?.let{bitmap->
        File(context.getExternalFilesDir(null),"overlay-viewport-checks/$name.png").apply{parentFile?.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }}
    @Test fun shortenedGuidanceAndQuestionWindowsKeepTheirButtonsReachable(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runOverlayBounds")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        ui{AppRuntime.initialize(context)}
        check(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE)){"Do not interrupt an active guide"}
        fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes().toString(Charsets.UTF_8).trim()}}
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val enabled=shell("settings get secure enabled_accessibility_services")
        check(component in enabled.split(':')){"Screen service must already be approved"}
        // Instrumentation restarts the app process, which can leave an approved
        // service unbound. Restore the exact existing grant list after rebinding.
        if(AppRuntime.service==null){
            try{shell("settings put secure enabled_accessibility_services "+enabled.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(350)}
            finally{shell("settings put secure enabled_accessibility_services $enabled")}
        }
        await("Approved screen service must be connected"){AppRuntime.service!=null}
        val service=AppRuntime.service!!;val owner=AppRuntime.study.state.value
        try{
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Public fixture missing"){automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName}
            val screen=runBlocking{service.observe()}
            val display=context.resources.displayMetrics
            val target=NodeBounds(0,display.heightPixels/2,display.widthPixels,display.heightPixels/2+display.heightPixels/10)
            val question="‘디지털 어시스턴트 앱’ 아래에 Google이 보이는 항목을 눌렀을 때 어떤 화면이 나타나나요?"
            for(phase in listOf(Phase.GUIDING,Phase.QUESTION)){
                val call=if(phase==Phase.QUESTION)ToolCall("public-question",Tool.ASK_USER,"공개 화면 배치 검사",question=question,options=listOf("앱 선택 목록이 열림","화면이 바뀌지 않음","다른 화면이 열림"))
                    else ToolCall("public-guide",Tool.GUIDE,"공개 화면 배치 검사",action=Action.TAP,instruction="‘디지털 어시스턴트 앱’을 눌러주세요.")
                ui{service.suppressGuidance(false);service.render(AgentState("public-viewport",phase,"공개 화면 배치 검사",if(phase==Phase.QUESTION)question else call.instruction!!,pending=call,screen=screen,target=target))}
                await("Overlay instruction missing"){nodes().any{it.isVisibleToUser&&it.text?.toString()==if(phase==Phase.QUESTION)question else call.instruction}}
                capture("${phase.name.lowercase()}-start")
                val handle=nodes().first{it.contentDescription?.toString()=="안내창 위아래로 이동"&&it.isVisibleToUser}
                val fixedHandle=bounds(handle)
                assertTrue("Fixed handle must remain an interactive movement control",handle.isClickable)
                val diagnostics=service.overlayDiagnostics();val geometry=diagnostics["viewport"]as Map<*,*>
                assertEquals("Viewport escapes the overlay's measured border",true,geometry["withinWindow"])
                File(context.getExternalFilesDir(null),"overlay-viewport-checks/${phase.name.lowercase()}-geometry.json").apply{parentFile?.mkdirs()}.writeText(org.json.JSONObject(diagnostics).toString(2))
                repeat(8){
                    nodes().firstOrNull{it.isScrollable&&it.packageName?.toString()==context.packageName}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(70)
                }
                await("End action must be visible after scrolling"){nodes().any{it.text?.toString()=="안내 종료"&&it.isVisibleToUser}}
                capture("${phase.name.lowercase()}-end")
                val finalHandle=nodes().first{it.contentDescription?.toString()=="안내창 위아래로 이동"&&it.isVisibleToUser}
                assertEquals("Body scrolling moved the handle",fixedHandle,bounds(finalHandle))
                val finalState=service.overlayDiagnostics();val finalGeometry=finalState["viewport"]as Map<*,*>
                assertEquals(geometry["headerTop"],finalGeometry["headerTop"])
                assertEquals(geometry["headerBottom"],finalGeometry["headerBottom"])
                assertEquals(true,finalGeometry["withinWindow"])
                val top=finalState["top"]as Int
                val viewport=Rect(0,top+(finalGeometry["top"]as Int),display.widthPixels,top+(finalGeometry["bottom"]as Int))
                val end=bounds(nodes().first{it.text?.toString()=="안내 종료"&&it.isVisibleToUser})
                assertTrue("Action escapes the viewport",end.top>=viewport.top&&end.bottom<=viewport.bottom)
            }
            assertEquals(owner,AppRuntime.study.state.value)
        }finally{
            ui{service.render(AgentState())}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
