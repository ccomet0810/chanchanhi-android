package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.AdminActivity
import dev.ccomet.chanchanhi04.platform.AppRuntime
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** UI boundary checks on a dedicated emulator. Real PIN is supplied ephemerally, never committed. */
@RunWith(AndroidJUnit4::class)
class UserAdminUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{
        serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        automation.clearCache()
        val all=mutableListOf<AccessibilityNodeInfo>()
        fun walk(node:AccessibilityNodeInfo?){node ?: return;all+=node;repeat(node.childCount){walk(node.getChild(it))}}
        automation.windows.forEach{window->val root=window.root;root?.refresh();walk(root)};return all
    }
    private fun has(text:String)=nodes().any{it.packageName?.toString()==context.packageName && it.text?.toString()==text}
    private fun await(detail:String,block:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(block())return;Thread.sleep(100)};error(detail)}
    private fun launch(admin:Boolean=false) {
        instrumentation.runOnMainSync {AppRuntime.initialize(context);AppRuntime.loop.stop()}
        context.startActivity(Intent(context,if(admin)AdminActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("App screen missing"){has(if(admin)"기록과 연결을 관리해요." else "무엇을 도와드릴까요?")}
    }
    private fun click(text:String) {
        repeat(15){
            var node=nodes().firstOrNull{it.packageName?.toString()==context.packageName && it.text?.toString()==text}
            if(node!=null){while(node!=null && !node.isClickable)node=node.parent
                if(node?.isEnabled==true){assertTrue("Control not clickable: $text",node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(300);return}
            }
            nodes().firstOrNull{it.packageName?.toString()==context.packageName && it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(200)
        };error("Control missing: $text")
    }
    private fun enterPin(value:String) {
        val input=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
        assertTrue(input.isPassword)
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
        click("관리자 모드 열기")
    }
    @Test fun userNavigationKeepsRecordsAndConnectionBehindPin() {
        launch();assertFalse(has("안내 기록"));assertFalse(has("AI 연결 설정"))
        click("설정");await("User permission controls missing"){has("화면 안내") && has("마이크")}
        click("관리자 모드");await("PIN gate missing"){has("비밀번호")}
        assertFalse(has("안내 기록"));assertFalse(has("개인 연결 키"))
        enterPin("000000");await("Wrong PIN did not show retry"){has("비밀번호를 확인해주세요.")}
        assertFalse(has("안내 기록"));assertFalse(has("AI 연결 설정"))
    }
    @Test fun correctPinUnlocksAndBackgroundingRequiresPinAgain() {
        val pin=InstrumentationRegistry.getArguments().getString("adminPin").orEmpty();assumeTrue(pin.length==6)
        launch(true);enterPin(pin)
        await("Configured PIN did not unlock admin"){has("안내 기록") && has("AI 연결 설정")}
        click("안내 기록");await("Admin log page missing"){has("안내 기록")}
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(700)
        context.startActivity(Intent(context,AdminActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("Admin did not relock on background"){has("기록과 연결을 관리해요.")}
        assertFalse(has("AI 연결 설정"));assertFalse(has("전체 기록 파일 저장"))
    }
    @Test fun largeFontWritingKeepsUserControlsReachable() {
        fun shell(value:String){automation.executeShellCommand(value).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        shell("settings put system font_scale 2.0")
        try {
            Thread.sleep(600);launch();click("글로 입력하기")
            await("Large-font text input missing"){nodes().any{it.packageName?.toString()==context.packageName && it.isEditable}}
            val input=nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("Real keyboard missing"){automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            val ime=Rect();automation.windows.first{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}.getBoundsInScreen(ime)
            await("Navigation must give typing more room"){!has("홈")}
            val bounds=Rect();nodes().first{it.packageName?.toString()==context.packageName && it.isEditable}.getBoundsInScreen(bounds)
            assertTrue("Text field clipped above keyboard",bounds.height()>=context.resources.displayMetrics.density*64 && bounds.bottom<=ime.top)
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"public test request")})
            await("Start action missing while typing"){has("안내 시작")}
            val start=nodes().first{it.packageName?.toString()==context.packageName && it.text?.toString()=="안내 시작"}
            start.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            Thread.sleep(300)
            nodes().first{it.packageName?.toString()==context.packageName && it.text?.toString()=="안내 시작"}.getBoundsInScreen(bounds)
            assertTrue("Start action covered by keyboard",bounds.bottom<=ime.top && bounds.height()>0)
            val file=java.io.File(context.getExternalFilesDir(null),"v041-large-font-keyboard.png")
            automation.takeScreenshot()?.let{bitmap->file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        } finally {shell("input keyevent 4");shell("settings put system font_scale 1.0")}
    }
    @Test fun newEnrollmentAppearsAfterReturningFromAdministrator() {
        val prefs=context.getSharedPreferences("research-1",0);val original=prefs.all.toMap()
        try {
            prefs.edit().remove("token").putBoolean("enabled",false).commit()
            launch();click("설정");assertFalse(has("테스트 기록 공유"))
            context.startActivity(Intent(context,AdminActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Admin gate missing"){has("기록과 연결을 관리해요.")}
            // Simulate the enrollment store update; never use this placeholder for network traffic.
            prefs.edit().putString("token","fixture-not-a-network-credential").commit()
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await("Main settings did not return"){has("설정") && !has("기록과 연결을 관리해요.")}
            repeat(5){if(!has("테스트 기록 공유")){nodes().firstOrNull{it.packageName?.toString()==context.packageName&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(300)}}
            assertTrue("Enrollment must refresh the cached settings list",has("테스트 기록 공유"))
        }finally{
            val edit=prefs.edit().clear()
            original.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Long->edit.putLong(k,v);is Int->edit.putInt(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}}
            edit.commit()
        }
    }
}
