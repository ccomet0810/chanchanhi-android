package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceLoopTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo=serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
    }
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(detail:String,predicate:()->Boolean) {val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(predicate())return;Thread.sleep(100)};error(detail)}
    private fun launchFixture(dynamic:Boolean=true) {
        ui {AppRuntime.initialize(instrumentation.targetContext)}
        ui {AppRuntime.loop.stop()}
        if(AppRuntime.service == null) {
            fun shell(command:String) {automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }}
            // Instrumentation force-stops the target at startup; rebind only on the test emulator.
            shell("settings put secure enabled_accessibility_services null")
            Thread.sleep(500)
            shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService")
            shell("settings put secure accessibility_enabled 1")
        }
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",dynamic).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("0.4 접근성 서비스 연결 필요") {AppRuntime.initialized && AppRuntime.service != null}
        await("공개 시험 화면이 앞에 없음") {automation.rootInActiveWindow?.packageName?.toString()==instrumentation.context.packageName}
        Thread.sleep(700)
    }
    @Test fun dynamicBannerInputQuestionAndResultUseRealAccessibilityAndOverlay() {
        launchFixture()
        val requests=CopyOnWriteArrayList<AgentRequest>()
        ui { AppRuntime.installTestModel(object:AgentModel {
            override suspend fun next(request:AgentRequest):ToolCall {
                requests+=request
                return when(requests.size) {
                    1->{delay(1500);val input=request.screen.elements.single{it.editable && it.label=="시험 입력란"}
                        ToolCall("input",Tool.GUIDE,"입력란 안내",request.screen.id,input.id,action=Action.INPUT,instruction="시험 내용을 입력해주세요.",expectedChange="사용자 입력 완료",
                            plan=listOf(PlanStep("입력","in_progress"),PlanStep("결과 확인","pending")))}
                    2->{val button=request.screen.elements.single{it.label.replace(Regex("\\s+")," ")=="다음 단계" && it.clickable}
                        ToolCall("tap",Tool.GUIDE,"다음 화면 확인",request.screen.id,button.id,action=Action.TAP,instruction="다음 단계를 눌러주세요.",expectedChange="시험 결과 화면")}
                    else->{val result=request.screen.elements.single{it.label=="시험 결과 화면"}
                        ToolCall("done",Tool.FINISH,"실제 결과 문구 확인",request.screen.id,instruction="시험 안내를 마쳤어요.",evidenceNodeIds=listOf(result.id))}
                }
            }
        });AppRuntime.start("공개 시험 내용을 입력하고 결과 확인") }
        await("입력 안내가 나오지 않음"){AppRuntime.loop.state.value.phase==Phase.GUIDING}
        assertEquals(1,requests.size)
        assertEquals(2,AppRuntime.loop.state.value.plan.size)
        fun editor(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            node ?: return null
            if(node.isEditable)return node
            repeat(node.childCount){index->editor(node.getChild(index))?.let{return it}}
            return null
        }
        val input=editor(automation.rootInActiveWindow)
        assertNotNull(input)
        ui { input!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"a")}) }
        Thread.sleep(900)
        assertEquals(Phase.GUIDING,AppRuntime.loop.state.value.phase);assertEquals(1,requests.size)
        ui {AppRuntime.loop.userFinished()}
        await("다음 안내가 나오지 않음"){AppRuntime.loop.state.value.pending?.callId=="tap"}
        val root=automation.rootInActiveWindow
        val next=root.findAccessibilityNodeInfosByText("다음").firstOrNull{it.isClickable}
        assertNotNull(next)
        next!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        await("실제 클릭 뒤 결과 검증이 완료되지 않음"){AppRuntime.loop.state.value.phase==Phase.COMPLETE}
        assertEquals("공개 시험 내용을 입력하고 결과 확인",requests.last().goal)
        assertEquals("action_observed",requests.last().history.last().result.status)
        assertTrue(requests.last().screen.elements.any{it.label=="시험 결과 화면"})
        ui {AppRuntime.loop.stop()}
    }
    @Test fun pauseAndUserQuestionKeepTheSameGoalOnDevice() {
        launchFixture();val requests=CopyOnWriteArrayList<AgentRequest>()
        ui {AppRuntime.installTestModel(object:AgentModel {
            override suspend fun next(request:AgentRequest):ToolCall {requests+=request;return ToolCall("q${requests.size}",Tool.ASK_USER,"필요한 선택",question="어떤 옵션으로 할까요?",options=listOf("한 번만","매일"))}
        });AppRuntime.start("선택 질문 시험")}
        await("질문이 안 나타남"){AppRuntime.loop.state.value.phase==Phase.QUESTION}
        ui {AppRuntime.loop.pause();AppRuntime.loop.resume()}
        await("재개 안 됨"){requests.size==2 && AppRuntime.loop.state.value.phase==Phase.QUESTION}
        ui {AppRuntime.loop.answer("한 번만")}
        await("답변 안 전달됨"){requests.size==3}
        assertEquals("선택 질문 시험",requests.last().goal)
        assertEquals("한 번만",requests.last().history.last().result.answer)
        ui {AppRuntime.loop.stop()}
    }
    @Test fun screenshotCaptureReturnsRealScaledCoordinatesAndRestoresOverlay() {
        launchFixture(false)
        ui {AppRuntime.installTestModel(object:AgentModel {
            override suspend fun next(request:AgentRequest)=ToolCall("q",Tool.ASK_USER,"이미지 시험",question="화면을 확인할까요?")
        });AppRuntime.start("공개 이미지 캡처 시험")}
        await("질문이 안 나타남"){AppRuntime.loop.state.value.phase==Phase.QUESTION}
        Thread.sleep(700)
        val image=kotlinx.coroutines.runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {AppRuntime.service!!.capture()} }
        assertTrue(image.imageWidth in 1..1280);assertTrue(image.imageHeight in 1..1280)
        assertEquals(720,image.observation.width);assertEquals(1280,image.observation.height)
        assertTrue(android.util.Base64.decode(image.base64,android.util.Base64.DEFAULT).size<=122880)
        assertEquals(Phase.QUESTION,AppRuntime.loop.state.value.phase)
        ui {AppRuntime.loop.stop()}
    }
    @Test fun guideStaysVisibleAboveTheRealKeyboard() {
        launchFixture(false)
        fun shell(command:String) {automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        shell("settings put secure show_ime_with_hard_keyboard 1")
        ui {AppRuntime.installTestModel(object:AgentModel {
            var count=0
            override suspend fun next(request:AgentRequest):ToolCall {val input=request.screen.elements.single{it.editable}
                return ToolCall("i${++count}",Tool.GUIDE,"입력 시험",request.screen.id,input.id,action=Action.INPUT,instruction="시험 내용을 입력해주세요.",expectedChange="사용자 입력 완료")}
        });AppRuntime.start("키보드와 안내창 시험")}
        await("입력 안내 없음"){AppRuntime.loop.state.value.phase==Phase.GUIDING}
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("showKeyboard",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        Thread.sleep(1100)
        shell("screencap -p /sdcard/Download/v04-ime-attempt.png")
        await("실제 키보드가 나타나지 않음"){automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
        Thread.sleep(700)
        val ime=automation.windows.first{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        val bounds=android.graphics.Rect();ime.getBoundsInScreen(bounds)
        var panel:Map<String,Any?> = emptyMap();ui {panel=AppRuntime.service!!.overlayDiagnostics()}
        assertTrue(panel.toString()+" IME="+bounds,(panel["bottom"] as Int)<=bounds.top)
        assertEquals(Phase.GUIDING,AppRuntime.loop.state.value.phase)
        shell("screencap -p /sdcard/Download/v04-keyboard-shell.png")
        ui {AppRuntime.loop.stop()};shell("input keyevent 4");shell("settings put secure show_ime_with_hard_keyboard 0")
    }
    @Test fun enteringUserAppHidesGuidanceUntilExplicitResumeEvenAfterCapture() {
        launchFixture(false)
        ui {AppRuntime.installTestModel(object:AgentModel {
            private var count=0
            override suspend fun next(request:AgentRequest)=ToolCall("q${++count}",Tool.ASK_USER,"설정 이동 시험",question="어떤 옵션으로 할까요?")
        });AppRuntime.start("앱에서 멈추고 다시 이어가기")}
        await("질문 대기 없음"){AppRuntime.loop.state.value.phase==Phase.QUESTION}
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,dev.ccomet.chanchanhi04.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("사용자 앱 진입이 안내를 멈추지 않음"){AppRuntime.loop.state.value.phase==Phase.PAUSED}
        var diagnostic:Map<String,Any?> = emptyMap()
        ui{diagnostic=AppRuntime.service!!.overlayDiagnostics()};assertEquals(true,diagnostic["hidden"])
        kotlinx.coroutines.runBlocking {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){runCatching{AppRuntime.service!!.capture()}}}
        ui{diagnostic=AppRuntime.service!!.overlayDiagnostics()};assertEquals(true,diagnostic["hidden"])
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("시험 앱으로 돌아오지 않음"){automation.rootInActiveWindow?.packageName?.toString()==instrumentation.context.packageName}
        ui{diagnostic=AppRuntime.service!!.overlayDiagnostics()};assertEquals(true,diagnostic["hidden"])
        ui{AppRuntime.resumeGuidance()}
        await("명시적 이어가기 뒤 질문 없음"){AppRuntime.loop.state.value.phase==Phase.QUESTION}
        ui{diagnostic=AppRuntime.service!!.overlayDiagnostics()};assertEquals(false,diagnostic["hidden"])
        assertEquals("앱에서 멈추고 다시 이어가기",AppRuntime.loop.state.value.goal)
        ui{AppRuntime.loop.stop()}
    }
}
