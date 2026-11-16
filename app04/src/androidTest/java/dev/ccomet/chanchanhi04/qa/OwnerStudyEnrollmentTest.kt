package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.research.ResearchConnection
import dev.ccomet.chanchanhi04.study.StudyPhase
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit owner enrollment only. Never submits consent, a profile, a survey or a mission result. */
@RunWith(AndroidJUnit4::class)
class OwnerStudyEnrollmentTest{
    @Test fun enrollAndRestoreTheRealStudyEntryWithoutInventingAnswers(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("enrollOwnerStudy")=="true")
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val request=File(context.filesDir,"study-enrollment-request.json")
        val connection=ResearchConnection(context)
        if(!connection.enrolled){
            val value=JSONObject(request.readText())
            try{connection.enroll(value.getString("origin"),value.getString("code"))}finally{request.delete()}
        }else request.delete()
        connection.setEnabled(true)
        i.runOnMainSync{AppRuntime.initialize(context)}
        val state=AppRuntime.study.state.value
        assertEquals("Do not reset an existing participant",StudyPhase.INTRO,state.phase)
        assertNull(state.participantId)
        assertTrue(AppRuntime.researchMode);assertTrue(AppRuntime.showStudy)
        assertEquals("scenario-kakao-photo-permission-1",AppRuntime.study.rules.defaultPreset()!!.getString("id"))
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}};visit(automation.rootInActiveWindow);return all}
        fun await(text:String){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(nodes().any{it.isVisibleToUser&&it.text?.contains(text)==true})return;Thread.sleep(100)};error("Missing study entry: $text")}
        fun click(text:String){Thread.sleep(400);var node=nodes().first{it.isVisibleToUser&&it.text?.toString()==text};while(!node.isClickable)node=node.parent?:error("Not clickable");check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
        fun capture(name:String){Thread.sleep(350);val out=File(context.getExternalFilesDir(null),"study-entry-checks").apply{mkdirs()};automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun open(){i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
        val original=AppRuntime.study.draft("intro")
        try{
            AppRuntime.study.saveDraft("intro",JSONObject().put("_index",0));open()
            await("하고 싶은 일을 말하면");capture("research-introduction.png")
            click("다음");await("사용 준비");capture("research-setup.png")
            assertFalse(nodes().any{it.text?.contains("다른 앱의 화면 글자, 버튼 위치와 앱 정보를 AI 서버로 보내")==true})
            if(dev.ccomet.chanchanhi04.setup.SetupPermissions.read(context).complete){
                click("다음");await("수집 내용 확인");capture("research-consent.png")
            }
            assertNull("Real consent must still be unanswered",AppRuntime.study.state.value.participantId)
        }finally{AppRuntime.study.saveDraft("intro",original);open()}
    }
}
