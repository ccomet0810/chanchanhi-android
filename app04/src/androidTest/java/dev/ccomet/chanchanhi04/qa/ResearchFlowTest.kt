package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.platform.SessionLog
import dev.ccomet.chanchanhi04.research.ResearchConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ResearchFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun ui(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(detail:String,predicate:()->Boolean){val until=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<until){if(predicate())return;Thread.sleep(100)};error(detail)}
    @Test fun lateTraceRemainsWithItsOriginalSession(){
        val log=SessionLog(instrumentation.targetContext);val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        log.begin(a,"공개 기록 경계 시험 A");val late=log.boundEvent();log.event("stopped",null)
        log.begin(b,"공개 기록 경계 시험 B");late("model_response",mapOf("public" to "late diagnostic"));log.awaitWrites()
        val first=log.files().single{it.name=="$a.jsonl"}.readLines().map(::JSONObject)
        val second=log.files().single{it.name=="$b.jsonl"}.readLines().map(::JSONObject)
        assertEquals(listOf(1L,2L,3L),first.map{it.getLong("seq")});assertTrue(first.any{it.getString("kind")=="model_response"})
        assertFalse(second.any{it.getString("kind")=="model_response"})
    }
    @Test fun consentNewSessionMeasuredGeometryAndRemoteAcknowledgement(){
        // This fixture never runs against a participant's phone or their personal apps.
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val code=InstrumentationRegistry.getArguments().getString("researchEnrollment")
        val connection=ResearchConnection(instrumentation.targetContext)
        assumeTrue(connection.enrolled||!code.isNullOrBlank())
        connection.setEnabled(false)
        val old=UUID.randomUUID().toString();val before=SessionLog(instrumentation.targetContext)
        before.begin(old,"공유 전 공개 시험 - 원격 전송 제외");before.event("stopped",null);before.awaitWrites()
        if(!connection.enrolled)connection.enroll(ResearchConnection.DEFAULT_ORIGIN,code!!)
        assertFalse(connection.enabled)
        connection.setEnabled(true)
        val prefs=instrumentation.targetContext.getSharedPreferences("research-1",0)
        assertFalse(prefs.getStringSet("eligible",emptySet()).orEmpty().contains(old))
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{
            serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
        }
        fun shell(command:String){automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        ui{AppRuntime.initialize(instrumentation.targetContext);AppRuntime.loop.stop()}
        if(AppRuntime.service==null){shell("settings put secure enabled_accessibility_services null");Thread.sleep(500);shell("settings put secure enabled_accessibility_services dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService");shell("settings put secure accessibility_enabled 1")}
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("fixture service missing"){AppRuntime.service!=null&&automation.rootInActiveWindow?.packageName?.toString()==instrumentation.context.packageName}
        ui{AppRuntime.installTestModel(object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{
            val input=request.screen.elements.single{it.editable}
            return ToolCall("research-guide",Tool.GUIDE,"공개 입력란을 확인했습니다.",request.screen.id,input.id,action=Action.INPUT,instruction="시험 내용을 입력해주세요.",expectedChange="사용자 입력 완료",plan=listOf(PlanStep("입력 기다림","in_progress"),PlanStep("결과 확인","pending")))
        }});AppRuntime.start("공개 시험 안내 기록과 키보드 위치 확인")}
        await("guide missing"){AppRuntime.loop.state.value.phase==Phase.GUIDING}
        shell("settings put secure show_ime_with_hard_keyboard 1")
        instrumentation.context.startActivity(Intent(instrumentation.context,PublicFixtureActivity::class.java).putExtra("showKeyboard",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("keyboard missing"){automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
        await("visible measured guide layout missing"){AppRuntime.log.rows.value.any{
            if(it.kind!="overlay_layout")false else JSONObject(it.json).getJSONObject("data").let{data->
                val panel=data.optJSONArray("panel")
                data.optString("phase")=="guiding"&&data.optBoolean("visible")&&data.optJSONArray("ime")!=null&&panel!=null&&panel.optInt(3)>panel.optInt(1)
            }
        }}
        assertTrue(AppRuntime.log.rows.value.any{it.kind=="screen_frame"})
        assertTrue(AppRuntime.log.rows.value.any{it.kind=="observation_timing"})
        assertTrue(AppRuntime.log.rows.value.any{it.kind=="plan_updated"})
        val id=AppRuntime.loop.state.value.sessionId
        ui{AppRuntime.loop.stop()};AppRuntime.log.awaitWrites()
        assertTrue(connection.upload())
        assertTrue(prefs.getLong("ack-$id",0)>0)
        assertFalse(prefs.contains("ack-$old"))
        connection.setEnabled(false);shell("input keyevent 4");shell("settings put secure show_ime_with_hard_keyboard 0")
        assertFalse(connection.enabled)
        assertTrue(prefs.getStringSet("eligible",emptySet()).orEmpty().isEmpty())
        val out=android.os.Bundle().apply{putString("stream","\nResearch fixture uploaded: $id; pre-consent session excluded: $old\n")}
        instrumentation.sendStatus(0,out)
    }
}
