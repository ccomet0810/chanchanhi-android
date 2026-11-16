package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.VoiceInputActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.voice.*
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceUiTest {
    private class Engine:VoiceEngine {
        var events:VoiceEngine.Events?=null;var cancelled=0;var starts=0
        override fun start(events:VoiceEngine.Events){this.events=events;starts++}
        override fun stop(){events?.ended()}
        override fun cancel(){cancelled++}
    }
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{
        serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
    }
    private lateinit var engine:Engine
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8)}}
    private fun await(detail:String,block:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(block())return;Thread.sleep(100)};error(detail)}
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}}
        automation.windows.forEach{val root=it.root;root?.refresh();visit(root)};return all
    }
    private fun has(text:String)=nodes().any{it.packageName?.toString()==context.packageName && it.text?.toString()==text}
    private fun click(text:String){var n=nodes().first{it.packageName?.toString()==context.packageName && it.text?.toString()==text}
        while(!n.isClickable)n=n.parent ?: error("No clickable parent")
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Before fun prepare(){
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        ui{AppRuntime.initialize(context);AppRuntime.loop.stop();engine=Engine();VoiceEngines.installForTest{engine}}
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Public fixture missing"){automation.rootInActiveWindow?.packageName?.toString()==instrumentation.context.packageName}
    }
    @After fun clean(){
        if(!android.os.Build.MODEL.startsWith("sdk_"))return
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);Thread.sleep(500)
        ui{VoiceEngines.installForTest(null);if(AppRuntime.initialized)AppRuntime.loop.stop()}
    }
    private fun open(){context.startActivity(Intent(context,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Engine did not start automatically"){engine.events!=null}
        ui{engine.events!!.ready()};await("App-owned listening UI missing"){has("듣고 있어요")}
    }
    private fun residentHome(){
        context.startActivity(Intent(context,dev.ccomet.chanchanhi04.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("User home missing"){has("찬찬히") && has("설정")}
    }
    private fun returnFixture(){
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Public fixture missing"){automation.rootInActiveWindow?.packageName?.toString()==instrumentation.context.packageName}
    }
    @Test fun ownSheetShowsRmsAndPartialTextAndDiscardsCancelledResult(){
        val originalGoal=AppRuntime.loop.state.value.goal
        open();ui{repeat(22){engine.events!!.level((it%12).toFloat())};engine.events!!.partial("공개 음성 시험")}
        await("Partial transcript missing"){has("공개 음성 시험")}
        assertTrue(nodes().any{it.contentDescription?.toString()=="음성 크기 파형"})
        assertEquals(context.packageName,automation.rootInActiveWindow?.packageName?.toString())
        screenshot("v044-listening.png")
        click("닫기");await("Microphone not released"){engine.cancelled>0}
        ui{engine.events!!.result("취소한 요청")};assertEquals(originalGoal,AppRuntime.loop.state.value.goal)
    }
    @Test fun homeReleasesEngineAndIgnoresLateTranscript(){
        val originalGoal=AppRuntime.loop.state.value.goal
        open();automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        await("Background microphone remains active"){engine.cancelled>0}
        ui{engine.events!!.result("뒤늦은 요청")};assertEquals(originalGoal,AppRuntime.loop.state.value.goal)
    }
    @Test fun questionSpeechFinalReturnsToSameGoalOnlyOnce(){
        residentHome();returnFixture()
        if(AppRuntime.service==null){
            shell("settings put secure enabled_accessibility_services null");Thread.sleep(500)
            shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService")
            shell("settings put secure accessibility_enabled 1")
        }
        await("Screen service unavailable"){AppRuntime.service!=null}
        val requests=CopyOnWriteArrayList<AgentRequest>()
        ui{AppRuntime.installTestModel(object:AgentModel{
            override suspend fun next(request:AgentRequest):ToolCall{requests+=request;return ToolCall("q${requests.size}",Tool.ASK_USER,"선택 질문",question="언제 사용할까요?",options=listOf("한 번만","매일"))}
        });AppRuntime.start("질문 답변 음성 시험")}
        await("Question missing"){AppRuntime.loop.state.value.phase==Phase.QUESTION}
        open();ui{engine.events!!.partial("매")};Thread.sleep(300);assertEquals(1,requests.size)
        ui{engine.events!!.ended()};await("Processing state missing"){has("입력을 마무리해요")}
        ui{engine.events!!.result("매일");engine.events!!.result("중복 결과")}
        await("Recognized answer was not retained for review"){has("내용을 확인해주세요") && has("매일")}
        Thread.sleep(400);assertEquals("Recognition submitted without user confirmation",1,requests.size)
        val input=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"한 번만")})
        await("Correction missing"){has("한 번만")}
        click("전송")
        await("Spoken answer not routed"){requests.size==2 && AppRuntime.loop.state.value.phase==Phase.QUESTION}
        assertEquals("질문 답변 음성 시험",requests.last().goal)
        assertEquals("한 번만",requests.last().userAnswers.single().answer)
    }
    @Test fun systemAssistantInvocationOpensMicrophoneWithoutIntermediatePrompt(){
        shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT dev.ccomet.chanchanhi04")
        await("Assistant selection unavailable"){shell("settings get secure voice_interaction_service").contains("dev.ccomet.chanchanhi04")}
        if(AppRuntime.service==null){shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService");shell("settings put secure accessibility_enabled 1")}
        await("Screen service unavailable"){AppRuntime.service!=null}
        // Direct `cmd voiceinteraction show` requires a privileged permission on production images.
        // Dispatch the system assist key as a user would instead of bypassing that permission.
        shell("input keyevent --longpress 219")
        await("Assistant did not start voice input directly"){engine.events!=null}
        ui{engine.events!!.ready()};await("Direct listening screen missing"){has("듣고 있어요")}
        assertFalse(has("말로 요청하기"));screenshot("v044-assistant-direct.png")
    }
    @Test fun sendingReviewedVoiceFromUserHomeReturnsToExternalScreen(){
        if(AppRuntime.service==null){shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService");shell("settings put secure accessibility_enabled 1")}
        await("Screen service unavailable"){AppRuntime.service!=null}
        val requests=CopyOnWriteArrayList<AgentRequest>()
        ui{AppRuntime.installTestModel(object:AgentModel{
            override suspend fun next(request:AgentRequest):ToolCall{requests+=request;return ToolCall("new-question",Tool.ASK_USER,"공개 시험",question="다음 선택은 무엇인가요?")}
        })}
        residentHome()
        shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT dev.ccomet.chanchanhi04")
        await("Assistant unavailable"){shell("settings get secure voice_interaction_service").contains("dev.ccomet.chanchanhi04")}
        shell("input keyevent --longpress 219");await("Voice did not open over home"){engine.events!=null}
        ui{engine.events!!.ready();engine.events!!.result("검토한 새 요청")}
        await("Review missing"){has("검토한 새 요청") && has("전송")}
        assertTrue(requests.isEmpty());click("전송")
        await("Request did not return to underlying app"){requests.isNotEmpty()}
        assertEquals("검토한 새 요청",requests.first().goal)
        assertEquals(instrumentation.context.packageName,requests.first().screen.packageName)
    }
    @Test fun realInstalledSystemEngineBecomesReadyWithoutProviderActivity(){
        open()
        val ready=java.util.concurrent.atomic.AtomicBoolean(false)
        val failure=java.util.concurrent.atomic.AtomicReference<String?>(null)
        var real:AndroidVoiceEngine?=null
        try{
            ui{val actual=AndroidVoiceEngine(context);real=actual;actual.start(object:VoiceEngine.Events {
                override fun ready(){ready.set(true);actual.cancel()}
                override fun level(rms:Float)=Unit
                override fun partial(text:String)=Unit
                override fun ended()=Unit
                override fun result(text:String)=Unit
                override fun error(message:String){if(!ready.get())failure.set(message)}
            })}
            await("System engine not ready: ${failure.get()}"){ready.get() || failure.get()!=null}
            assertTrue("Installed speech engine failed: ${failure.get()}",ready.get())
            assertEquals(context.packageName,automation.rootInActiveWindow?.packageName?.toString())
        }finally{ui{real?.cancel()}}
    }
    @Test fun largeFontTextFallbackFitsAboveKeyboard(){
        shell("settings put system font_scale 2.0")
        try{
            Thread.sleep(500);open()
            await("Text fallback missing"){nodes().any{it.packageName?.toString()==context.packageName && it.isEditable}}
            val input=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("Keyboard missing"){automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"public voice fallback")})
            assertTrue("Send action unreachable",has("전송"))
            val ime=android.graphics.Rect();automation.windows.first{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}.getBoundsInScreen(ime)
            val send=android.graphics.Rect();nodes().first{it.packageName?.toString()==context.packageName && it.text?.toString()=="전송"}.getBoundsInScreen(send)
            assertTrue("Send is hidden by keyboard",send.height()>0 && send.bottom<=ime.top)
            screenshot("v044-text-large.png")
        }finally{shell("input keyevent 4");shell("settings put system font_scale 1.0")}
    }
    @Test fun editingLiveTranscriptCancelsMicAndPreservesCursorAndCorrections(){
        open();ui{engine.events!!.partial("abcde")};await("Live draft missing"){has("abcde")}
        val input=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("Editing did not stop microphone"){engine.cancelled>0}
        input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply{
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,2);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,2)
        })
        shell("input text X");await("Cursor moved or typed correction missing"){has("abXcde")}
        ui{engine.events!!.partial("wrong partial");engine.events!!.result("wrong final")}
        Thread.sleep(300);assertTrue("Late recognition replaced correction",has("abXcde"))
        screenshot("v044-editing.png")
        val currentInput=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
        currentInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"x".repeat(2100))})
        await("Visible text differs from send limit"){nodes().firstOrNull{it.packageName?.toString()==context.packageName && it.isEditable}?.text?.length==2000}
        val old=engine.events
        context.startActivity(Intent(context,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("Recall did not restart recognition"){engine.starts==2}
        ui{engine.events!!.ready();engine.events!!.partial("new spoken draft");old!!.result("obsolete result")}
        await("Keyboard editing prevented new live recognition"){has("new spoken draft") && has("듣고 있어요")}
    }
    private fun screenshot(name:String){automation.takeScreenshot()?.let{bitmap->java.io.File(context.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
}
