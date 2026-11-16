package dev.ccomet.chanchanhi04.qa

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.AdminActivity
import dev.ccomet.chanchanhi04.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in synthetic test on a fresh emulator user; never runs on a participant phone. */
@RunWith(AndroidJUnit4::class)
class PlayReviewBoundaryTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun nodes():List<AccessibilityNodeInfo>{
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?,depth:Int){if(node==null||depth>30)return;result+=node;for(i in 0 until node.childCount)visit(node.getChild(i),depth+1)}
        visit(automation.rootInActiveWindow,0);return result
    }
    private fun has(text:String)=nodes().any{it.isVisibleToUser&&it.text?.toString()==text}
    private fun await(message:String,condition:()->Boolean){val until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(condition())return;Thread.sleep(100)};error(message)}
    private fun openAdmin(){instrumentation.startActivitySync(Intent(context,AdminActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));await("PIN gate missing"){nodes().any{it.isEditable}}}
    private fun enter(pin:String){
        val input=nodes().first{it.isEditable};assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,pin)}))
        await("PIN submit action not ready"){
            var button=nodes().firstOrNull{it.text?.toString()=="관리자 모드 열기"}
            while(button!=null&&!button.isClickable)button=button.parent
            button!=null&&button.isEnabled&&button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    @Test fun separatePinWorksOnFreshTestInstallButNotAnOrdinaryEnrollment(){
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("runPlayReviewBoundary")=="true")
        check(Build.MODEL.contains("sdk",true)||Build.FINGERPRINT.contains("generic")){"Dedicated emulator only"}
        val pin=args.getString("reviewPin").orEmpty();check(pin.length==6)
        val prefs=context.getSharedPreferences("research-1",Context.MODE_PRIVATE)
        check(!prefs.contains("token")){"Fresh, unregistered emulator user required"}
        try{
            openAdmin();enter(pin)
            await("Separate test PIN did not unlock fresh setup"){has("안내 기록")&&has("공개 시험 전용 기기예요. 실제 참여자의 개인정보를 입력하지 마세요.")}
            instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("App did not return to onboarding"){has("휴대폰 사용 안내, 찬찬히")}
            prefs.edit().putString("token","synthetic-ordinary-device-token").putString("label","ordinary participant").putBoolean("enabled",false).commit()
            openAdmin();enter(pin)
            await("Test PIN was not rejected on ordinary enrollment"){has("비밀번호를 확인해주세요.")}
            assertFalse(has("안내 기록"))
        }finally{
            prefs.edit().remove("token").remove("label").remove("enabled").commit()
            instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
