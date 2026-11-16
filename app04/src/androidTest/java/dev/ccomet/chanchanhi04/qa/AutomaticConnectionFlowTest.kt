package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.AutomaticAiConnection
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import com.example.aplassistmvp0.guide.BridgeConnectionStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live opt-in on our dedicated emulator. No manually supplied credential, account, enrollment, or mission. */
@RunWith(AndroidJUnit4::class)
class AutomaticConnectionFlowTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context=i.targetContext
    private val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(150)};error(message)}
    @Test fun freshUserHasAutomaticAiAndCanGuideWithoutResearch(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAutomaticConnection")=="true")
        check(Build.MODEL.contains("sdk",true)||Build.FINGERPRINT.contains("generic"))
        i.runOnMainSync{AppRuntime.initialize(context)}
        assertFalse(AppRuntime.researchMode)
        assertTrue(AppRuntime.screenConsentAccepted)
        assertTrue(SetupPermissions.read(context).screen)
        assertFalse(AppRuntime.showStudy)
        val configured=runBlocking{AutomaticAiConnection.ensure(context)}
        assertEquals(43,configured.pairingToken.length)
        assertTrue(File(context.filesDir,"app-access-seed").exists())
        assertEquals(configured,BridgeConnectionStore(context).load())
        runBlocking{AppRuntime.model.check()}
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        // Instrumentation restarts the app process. Rebind only the service already granted in the UI.
        if(AppRuntime.service==null){
            fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8).trim()}}
            val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
            val enabled=shell("settings get secure enabled_accessibility_services")
            check(component in enabled.split(':'))
            try{shell("settings put secure enabled_accessibility_services "+enabled.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shell("settings put secure enabled_accessibility_services $enabled")}
        }
        await("Screen service must be connected"){AppRuntime.service!=null}
        i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Synthetic public fixture must be visible"){automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName}
        Thread.sleep(600)
        i.runOnMainSync{AppRuntime.start("이 공개 시험 화면의 다음 단계 버튼을 누르는 방법을 알려줘")}
        await("Live AI must return guidance without any assigned mission"){
            val state=AppRuntime.loop.state.value
            check(state.phase!=Phase.ERROR){state.message}
            state.phase in setOf(Phase.GUIDING,Phase.QUESTION)
        }
        assertNotNull(AppRuntime.loop.state.value.pending)
        automation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"auto-ai-guiding.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        if(InstrumentationRegistry.getArguments().getString("recordDemo")=="true")Thread.sleep(6000)
        i.runOnMainSync{AppRuntime.loop.stop()}
        i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
}
