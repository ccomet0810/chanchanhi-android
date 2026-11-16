package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in. Uses only synthetic public text through the existing approved subscription. */
@RunWith(AndroidJUnit4::class)
class LiveModelTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo=serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
    }
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(detail:String,predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+120000
        while(SystemClock.uptimeMillis()<end) {
            if(predicate())return
            if(AppRuntime.loop.state.value.phase==Phase.ERROR)error(AppRuntime.loop.state.value.message)
            Thread.sleep(150)
        }
        error(detail+": "+AppRuntime.loop.state.value.phase)
    }
    private fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        node ?: return null;if(predicate(node))return node
        repeat(node.childCount){find(node.getChild(it),predicate)?.let{return it}}
        return null
    }
    @Test fun realTerraGuidesPublicInputAndChecksActualResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLive")=="true")
        ui {AppRuntime.initialize(instrumentation.targetContext)}
        fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        shell("settings put secure enabled_accessibility_services null")
        Thread.sleep(500)
        shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService")
        shell("settings put secure accessibility_enabled 1")
        ui {AppRuntime.loop.stop()}
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val serviceEnd=SystemClock.uptimeMillis()+10000
        while(AppRuntime.service==null && SystemClock.uptimeMillis()<serviceEnd)Thread.sleep(100)
        assertNotNull(AppRuntime.service)
        val fixtureEnd=SystemClock.uptimeMillis()+12000
        while(automation.rootInActiveWindow?.packageName?.toString()!=instrumentation.context.packageName && SystemClock.uptimeMillis()<fixtureEnd)Thread.sleep(100)
        assertEquals(instrumentation.context.packageName,automation.rootInActiveWindow?.packageName?.toString());Thread.sleep(700)
        ui {AppRuntime.installTestModel(AppRuntime.model);AppRuntime.start("이 화면의 시험 입력란에 내용을 적고 다음 단계로 넘어가는 방법을 알려줘")}
        await("입력 안내를 받지 못함"){AppRuntime.loop.state.value.phase in setOf(Phase.GUIDING,Phase.QUESTION)}
        assertEquals(Phase.GUIDING,AppRuntime.loop.state.value.phase)
        assertEquals(Action.INPUT,AppRuntime.loop.state.value.pending?.action)
        Thread.sleep(350)
        shell("screencap -p /sdcard/Download/v04-guiding-shell.png")
        var diagnostics:Map<String,Any?> = emptyMap()
        ui {diagnostics=AppRuntime.service!!.overlayDiagnostics()}
        assertTrue(diagnostics.toString(),diagnostics["shown"]==true && (diagnostics["height"] as Int)>0)
        automation.takeScreenshot()?.let { bitmap -> instrumentation.targetContext.openFileOutput("v04-guiding.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
        val turns=AppRuntime.loop.state.value.turns
        val editor=find(automation.rootInActiveWindow){it.isEditable}!!
        editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"a")})
        Thread.sleep(1000)
        assertEquals(turns,AppRuntime.loop.state.value.turns)
        assertEquals(Phase.GUIDING,AppRuntime.loop.state.value.phase)
        ui {AppRuntime.loop.userFinished()}
        await("다음 행동 안내를 받지 못함"){AppRuntime.loop.state.value.phase in setOf(Phase.GUIDING,Phase.QUESTION)}
        assertEquals(Phase.GUIDING,AppRuntime.loop.state.value.phase)
        assertEquals(Action.TAP,AppRuntime.loop.state.value.pending?.action)
        val next=find(automation.rootInActiveWindow){it.isClickable && it.text?.toString()?.replace(Regex("\\s+")," ")=="다음 단계"}!!
        next.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("결과 확인을 마치지 못함"){AppRuntime.loop.state.value.phase==Phase.COMPLETE}
        assertTrue(AppRuntime.log.rows.value.any{it.kind=="model_response"})
        assertTrue(AppRuntime.loop.state.value.screen!!.elements.any{it.label=="시험 결과 화면"})
        automation.takeScreenshot()?.let { bitmap -> instrumentation.targetContext.openFileOutput("v04-complete.png",0).use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
        ui {AppRuntime.loop.stop()}
    }
    @Test fun realTerraAcceptsTheActualPublicScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLive")=="true")
        ui {AppRuntime.initialize(instrumentation.targetContext)}
        fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        if(AppRuntime.service==null) {
            shell("settings put secure enabled_accessibility_services null");Thread.sleep(500)
            shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService")
            shell("settings put secure accessibility_enabled 1")
        }
        ui {AppRuntime.loop.stop()}
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val until=SystemClock.uptimeMillis()+10000
        while(AppRuntime.service==null && SystemClock.uptimeMillis()<until)Thread.sleep(100)
        assertNotNull(AppRuntime.service)
        val fixtureEnd=SystemClock.uptimeMillis()+12000
        while(automation.rootInActiveWindow?.packageName?.toString()!=instrumentation.context.packageName && SystemClock.uptimeMillis()<fixtureEnd)Thread.sleep(100)
        assertEquals(instrumentation.context.packageName,automation.rootInActiveWindow?.packageName?.toString());Thread.sleep(700)
        val previous=AppRuntime.preferences.getBoolean("images",false)
        AppRuntime.preferences.edit().putBoolean("images",true).apply()
        try {
            AppRuntime.log.begin(java.util.UUID.randomUUID().toString(),"공개 스크린샷 AI 전송 시험")
            val image=kotlinx.coroutines.runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){AppRuntime.service!!.capture()} }
            val call=kotlinx.coroutines.runBlocking {AppRuntime.model.next(AgentRequest("이 공개 시험 화면의 다음 단계 버튼을 누르는 방법을 알려줘",emptyList(),image.observation,emptyList(),0,image))}
            assertTrue(call.callId.isNotBlank())
            val transport=AppRuntime.log.rows.value.last{it.kind=="model_transport"}.let{org.json.JSONObject(it.json).getJSONObject("data")}
            assertTrue(transport.getBoolean("hasImage"));assertTrue(transport.getInt("payloadBytes")<=250000)
            AppRuntime.log.event("public_image_check_passed",mapOf("tool" to call.tool.name,"imageWidth" to image.imageWidth,"imageHeight" to image.imageHeight))
        } finally {AppRuntime.preferences.edit().putBoolean("images",previous).apply()}
    }
}
