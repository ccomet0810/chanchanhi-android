package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.research.ResearchConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplayCaptureTest {
    @Test fun publicScreenCaptureReplayOnExplicitlySelectedDevice(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPublicReplay")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Device must be unlocked by owner"}
        val connection=ResearchConnection(context)
        check(connection.enabled&&!connection.imagesEnabled){"Existing research enrollment required; image sharing must initially be off"}
        fun ui(block:()->Unit)=i.runOnMainSync(block)
        fun await(detail:String,p:()->Boolean){val until=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<until){if(p())return;Thread.sleep(100)};error(detail)}
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun shell(command:String):String=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8)}}
        connection.setImagesEnabled(true)
        var session=""
        try{
            ui{AppRuntime.initialize(context);AppRuntime.loop.stop()}
            if(AppRuntime.service==null){
                // Instrumentation kills the target process on Samsung. Rebind only the already
                // enabled service; preserve every other accessibility grant verbatim.
                val component="dev.ccomet.chanchanhi04/dev.ccomet.chanchanhi04.platform.ScreenService"
                val original=shell("settings get secure enabled_accessibility_services").trim()
                check(original.split(':').contains(component)){"Owner must enable the existing accessibility service"}
                val others=original.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"}
                try{shell("settings put secure enabled_accessibility_services $others");Thread.sleep(500)}
                finally{shell("settings put secure enabled_accessibility_services $original")}
            }
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).putExtra("replayFixture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Public fixture not visible"){AppRuntime.service!=null&&automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName}
            ui{AppRuntime.installTestModel(object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{
                val input=request.screen.elements.single{it.editable}
                return ToolCall("public-replay",Tool.GUIDE,"공개 시험 화면의 글자와 설명을 구분합니다.",request.screen.id,input.id,action=Action.INPUT,
                    instruction="시험 입력란을 눌러주세요.",expectedChange="입력란 선택",plan=listOf(PlanStep("공개 화면 캡처 검증","in_progress")))
            }});AppRuntime.start("공개 시험 화면 캡처와 접근성 글자 분리 검증")}
            session=AppRuntime.loop.state.value.sessionId
            await("No stable public screenshot recorded"){AppRuntime.log.rows.value.any{it.kind=="screen_image"}}
            val first=AppRuntime.log.rows.value.last{it.kind=="screen_image"}
            val data=JSONObject(first.json).getJSONObject("data");assertEquals(i.context.packageName,data.getJSONObject("snapshot").getString("packageName"))
            val frame=AppRuntime.log.rows.value.last{it.kind=="screen_frame"}
            val nodes=JSONObject(frame.json).getJSONObject("data").getJSONArray("nodes")
            val values=(0 until nodes.length()).map{nodes.getJSONObject(it)}
            assertTrue(values.any{it.optJSONObject("display")?.optString("text")=="발신번호 표시제한 번호의 전화 차단"})
            assertTrue(values.any{it.optJSONObject("display")?.optString("description")?.contains("사용 안 함")==true&&it.optJSONObject("display")?.isNull("text")==true})
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("showKeyboard",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("Keyboard geometry missing"){AppRuntime.log.rows.value.any{it.kind=="overlay_layout"&&JSONObject(it.json).getJSONObject("data").optJSONArray("ime")!=null}}
            await("Keyboard frame missing"){AppRuntime.log.rows.value.any{it.kind=="screen_image"&&it.sequence>first.sequence}}
            ui{AppRuntime.loop.stop()};AppRuntime.log.awaitWrites()
            check(connection.upload()){connection.status}
            val prefs=context.getSharedPreferences("research-1",0)
            assertTrue(prefs.getLong("ack-$session",0)>0)
            i.sendStatus(0,android.os.Bundle().apply{putString("stream","\nPUBLIC_REPLAY_SESSION=$session\n")})
        }finally{
            ui{if(AppRuntime.initialized)AppRuntime.loop.stop()}
            connection.setImagesEnabled(false)
            i.context.startActivity(Intent(context,dev.ccomet.chanchanhi04.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
