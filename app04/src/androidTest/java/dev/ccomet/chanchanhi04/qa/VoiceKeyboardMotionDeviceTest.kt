package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.ui.*
import dev.ccomet.chanchanhi04.voice.VoicePhase
import dev.ccomet.chanchanhi04.voice.VoiceState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in host-only UI checks with a public draft. Does not run the agent or change OS settings. */
@RunWith(AndroidJUnit4::class)
class VoiceKeyboardMotionDeviceTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext
    private lateinit var automation: UiAutomation
    private val title = "하고 싶은 일을 적어주세요"
    private val draft = "가나다라 마바사아"

    private class HostState {
        val progress = mutableFloatStateOf(0f)
        val editor = mutableStateOf(TextFieldValue("가나다라 마바사아", TextRange(2, 4)))
        val focus = FocusRequester()
        val focused = AtomicBoolean(false)
        val sheet = AtomicReference<androidx.compose.ui.geometry.Rect>()
        var keyboard: SoftwareKeyboardController? = null
        var host: MainActivity? = null
    }

    private fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVoiceKeyboardUI") == "true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Owner must unlock the phone" }
        automation = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo = serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(40)
        }
        error(message)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            all += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        return all
    }

    private fun shown(label: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == label }
    private fun ime() = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "voice-keyboard-checks").apply { mkdirs() }
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun withPanel(nativeInsets: Boolean, fontScale: Float = 1f, maxHeightDp: Int? = null, block: (HostState) -> Unit) {
        prepare()
        val state = HostState()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            scenario.onActivity { host ->
                state.host = host
                host.setContent {
                    ChanchanhiTheme {
                        val keyboard = LocalSoftwareKeyboardController.current
                        SideEffect { state.keyboard = keyboard }
                        val nativeProgress = rememberVoiceKeyboardProgress()
                        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(Foundation.SheetMarginDp.dp), contentAlignment = Alignment.BottomCenter) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            VoiceInputPanel(VoiceState(VoicePhase.TEXT, draft), true, {}, {}, {},
                                modifier = Modifier.widthIn(max = Foundation.MaxWidthDp.dp).fillMaxWidth()
                                    .then(if (maxHeightDp == null) Modifier else Modifier.heightIn(max = maxHeightDp.dp))
                                    .onGloballyPositioned { state.sheet.set(it.boundsInWindow()) },
                                keyboardProgress = if (nativeInsets) nativeProgress else ({ state.progress.floatValue })) {
                                VoiceDraftEditor(state.editor.value, { state.editor.value = it },
                                    modifier = Modifier.focusRequester(state.focus).onFocusChanged { state.focused.set(it.isFocused) }
                                        .semantics { contentDescription = "공개 키보드 시험 입력" })
                            }
                            }
                        }
                    }
                }
            }
            await("Panel did not render") { state.sheet.get() != null && shown(title) }
            block(state)
        } finally {
            scenario.onActivity { state.keyboard?.hide(); it.setContent {} }
            scenario.close()
        }
    }

    @Test fun sampledFoldsShrinkTheSurfaceAndPreserveTitleEditorSelectionAndFocus() = withPanel(false) { state ->
        i.runOnMainSync { state.focus.requestFocus(); state.keyboard?.hide() }
        await("Editor not focused or keyboard still open") { state.focused.get() && ime() == null }
        val expanded = state.sheet.get()
        screenshot("sample-expanded.png")
        var previous = expanded.height
        var collapsedHeight = 0f
        for (progress in listOf(.25f, .5f, .75f, 1f)) {
            i.runOnMainSync { state.progress.floatValue = progress }
            await("Fold did not relinquish measured height at $progress") { state.sheet.get().height < previous - 1f }
            val height = state.sheet.get().height
            assertTrue(shown(title))
            assertTrue("Focus changed at $progress", state.focused.get())
            i.runOnMainSync { assertEquals(draft, state.editor.value.text); assertEquals(TextRange(2, 4), state.editor.value.selection) }
            if (progress == .5f) screenshot("sample-half-folded.png")
            previous = height
            if (progress == 1f) collapsedHeight = height
        }
        assertFalse("Hidden close action remains visible", shown("닫기"))
        assertFalse("Hidden retry action remains visible", shown("다시 말하기"))
        screenshot("sample-collapsed.png")
        for (progress in listOf(.75f, .5f, .25f, 0f)) {
            i.runOnMainSync { state.progress.floatValue = progress }
            await("Reverse fold did not restore height at $progress") { state.sheet.get().height > previous + 1f }
            val height = state.sheet.get().height
            if (progress == .5f) assertEquals("Half folding must release half of both regions", (expanded.height + collapsedHeight) / 2f, height, 2f)
            assertTrue(shown(title))
            assertTrue(state.focused.get())
            previous = height
        }
        assertEquals(expanded.height, state.sheet.get().height, 1f)
        assertTrue(shown("닫기")); assertTrue(shown("다시 말하기"))
    }

    @Test fun actualImeUsesOneInsetAndRestoresControlsWithoutReplacingTheEditor() = withPanel(true) { state ->
        i.runOnMainSync { state.focus.requestFocus(); state.keyboard?.show() }
        await("Keyboard or collapsed panel missing") { ime() != null && !shown("닫기") && !shown("다시 말하기") }
        assertTrue("Title disappeared", shown(title))
        assertTrue("Editing lost focus", state.focused.get())
        // Samsung may expose only the IME navigation strip through accessibility windows.
        // Use the same real root inset that lays out the host, then compare its coverage.
        val reportedBounds = Rect().also { ime()!!.getBoundsInScreen(it) }
        val keyboardBounds = Rect()
        val sendBounds = Rect()
        val density = context.resources.displayMetrics.density
        var previousGeometry="";var stableFrames=0
        await("IME and Compose geometry did not settle above the keyboard") {
            i.runOnMainSync {
                val host=state.host!!
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(host.window.decorView)!!
                val bottom=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val window=host.windowManager.maximumWindowMetrics.bounds
                keyboardBounds.set(window.left,window.bottom-bottom,window.right,window.bottom)
            }
            nodes().firstOrNull { it.text?.toString()=="전송" }?.getBoundsInScreen(sendBounds)
            val geometry="$keyboardBounds $sendBounds"
            if(geometry==previousGeometry&&sendBounds.height()>0&&sendBounds.bottom<=keyboardBounds.top&&keyboardBounds.top-sendBounds.bottom<64*density)stableFrames++ else stableFrames=0
            previousGeometry=geometry
            File(context.getExternalFilesDir(null),"voice-keyboard-checks/ime-coverage.txt").writeText("reported=$reportedBounds coverage=$keyboardBounds send=$sendBounds density=$density stableFrames=$stableFrames")
            stableFrames>=3
        }
        screenshot("actual-ime-open.png")
        assertTrue("Send covered by keyboard", sendBounds.height() > 0 && sendBounds.bottom <= keyboardBounds.top)
        assertTrue("IME was applied twice or a phantom spacer remains", keyboardBounds.top - sendBounds.bottom < 64 * density)
        screenshot("actual-ime-open.png")
        i.runOnMainSync { state.keyboard?.hide() }
        await("Closing the keyboard did not restore controls") { ime() == null && shown("닫기") && shown("다시 말하기") }
        assertTrue(shown(title)); assertTrue(state.focused.get())
        i.runOnMainSync { assertEquals(draft, state.editor.value.text); assertEquals(TextRange(2, 4), state.editor.value.selection) }
        screenshot("actual-ime-closed.png")
    }


    @Test fun actualAssistantActivityKeepsPublicDraftAndSelectionThroughTheRealIme(){
        prepare()
        val runtime=dev.ccomet.chanchanhi04.platform.AppRuntime
        i.runOnMainSync{runtime.initialize(context)}
        // Instrumentation replacement may leave the already-selected owner service binding.
        val services=android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val own=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        check(own in services.split(':'))
        if(runtime.service==null){
            fun shellSetting(value:String)=automation.executeShellCommand("settings put secure enabled_accessibility_services $value").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{r->r.readBytes()}}
            try{shellSetting(services.split(':').filter{it!=own}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shellSetting(services)}
        }
        await("Authorized screen service did not connect"){runtime.service!=null&&runtime.screenConnected.value}
        assumeTrue("Needs the owner's existing microphone grant",context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        assumeTrue("Needs the owner's existing assistant selection",android.service.voice.VoiceInteractionService.isActiveService(context,android.content.ComponentName(context,dev.ccomet.chanchanhi04.platform.AssistantService::class.java)))
        assumeTrue("Must not interrupt active guidance",runtime.loop.state.value.phase in setOf(dev.ccomet.chanchanhi04.agent.Phase.IDLE,dev.ccomet.chanchanhi04.agent.Phase.STOPPED,dev.ccomet.chanchanhi04.agent.Phase.COMPLETE)&&runtime.voiceAllowed())
        val events=AtomicReference<dev.ccomet.chanchanhi04.voice.VoiceEngine.Events>()
        val cancelled=java.util.concurrent.atomic.AtomicInteger()
        val engine=object:dev.ccomet.chanchanhi04.voice.VoiceEngine{
            override fun start(value:dev.ccomet.chanchanhi04.voice.VoiceEngine.Events){events.set(value)}
            override fun stop(){events.get()?.ended()}
            override fun cancel(){cancelled.incrementAndGet()}
        }
        val before=runtime.study.state.value
        val monitor=i.addMonitor(dev.ccomet.chanchanhi04.VoiceInputActivity::class.java.name,null,false)
        var activity:android.app.Activity?=null
        fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{r->r.readBytes()}}
        try{
            i.runOnMainSync{dev.ccomet.chanchanhi04.voice.VoiceEngines.installForTest{engine}}
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            shell("input keyevent 219") // Real system assistant callback, with an in-process public recognition fixture.
            activity=monitor.waitForActivityWithTimeout(12000)
            assertNotNull("System assistant did not open the real input Activity",activity)
            await("Fixture recognizer did not start"){events.get()!=null}
            i.runOnMainSync{events.get().ready();events.get().partial("가나다라")}
            await("Public live draft missing"){nodes().any{it.isEditable&&it.text?.toString()=="가나다라"}}
            var input=nodes().first{it.isEditable}
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("Real editor did not fold controls"){ime()!=null&&shown(title)&&!shown("닫기")&&!shown("다시 말하기")&&cancelled.get()>0}
            input=nodes().first{it.isEditable}
            input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,android.os.Bundle().apply{putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,2);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,2)})
            shell("input text X")
            await("Cursor correction moved or vanished"){nodes().any{it.isEditable&&it.text?.toString()=="가나X다라"}}
            i.runOnMainSync{events.get().partial("늦은 가상 결과");events.get().result("늦은 가상 결과")}
            assertTrue(nodes().any{it.isEditable&&it.text?.toString()=="가나X다라"});screenshot("actual-activity-ime-open.png")
            shell("input keyevent 4")
            await("Real Activity did not restore controls"){ime()==null&&shown("닫기")&&shown("다시 말하기")}
            assertTrue(shown(title));assertTrue(nodes().any{it.isEditable&&it.text?.toString()=="가나X다라"});screenshot("actual-activity-ime-closed.png")
            assertEquals(before.runId,runtime.study.state.value.runId);assertEquals(before.phase,runtime.study.state.value.phase)
        }finally{
            i.runOnMainSync{activity?.finish();dev.ccomet.chanchanhi04.voice.VoiceEngines.installForTest(null)}
            i.removeMonitor(monitor)
        }
    }

    @Test fun largeFontInASmallViewportKeepsTitleEditorAndSendReachable() = withPanel(false, fontScale = 2f, maxHeightDp = 300) { state ->
        i.runOnMainSync { state.progress.floatValue = 1f }
        await("Compact panel not collapsed") { !shown("닫기") && !shown("다시 말하기") }
        assertTrue("Title removed in small viewport", shown(title))
        val input = nodes().first { it.isEditable }
        val inputBounds = Rect().also { input.getBoundsInScreen(it) }
        val sendBounds = Rect().also { bounds -> nodes().first { it.text?.toString() == "전송" }.getBoundsInScreen(bounds) }
        assertTrue("Editor lost all its viewport height", input.isVisibleToUser && inputBounds.height() > 0)
        assertTrue("Send lost its touch area", sendBounds.height() > 0)
        assertTrue("Editor and send overlap", inputBounds.bottom <= sendBounds.top)
        screenshot("small-viewport-font-2.png")
    }
}
