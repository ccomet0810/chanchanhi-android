package dev.ccomet.chanchanhi04.qa

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.ScreenSetupHelp
import dev.ccomet.chanchanhi04.ScreenSetupHelpActivity
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.MicrophonePermission
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Three changed user journeys only; no participant answers or microphone grant changes. */
class DirectSetupSmokeDeviceTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app get()=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use{it.readText()}
    private fun await(message:String,condition:()->Boolean){val until=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<until){if(condition())return;Thread.sleep(100)};assertTrue(message,condition())}
    private fun texts():List<String>{
        val out=mutableListOf<String>()
        fun visit(node:AccessibilityNodeInfo?){if(node==null)return;node.text?.toString()?.let(out::add);for(n in 0 until node.childCount)visit(node.getChild(n))}
        visit(automation.rootInActiveWindow);return out
    }
    private fun capture(name:String){automation.takeScreenshot()?.let{bitmap->val file=File(app.getExternalFilesDir(null),"direct-037/$name");file.parentFile?.mkdirs();file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    @Before fun prepare(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDirectSetupUI")=="true")
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui{AppRuntime.initialize(app)}
    }
    @Test fun pipPermissionOpensDirectlyWithoutAnotherExplanationPage(){
        assumeTrue(ScreenSetupHelp.supported(app))
        val original=Regex("PICTURE_IN_PICTURE: (\\w+)").find(shell("cmd appops get ${app.packageName} PICTURE_IN_PICTURE"))?.groupValues?.get(1)?:"default"
        val monitor=i.addMonitor(ScreenSetupHelpActivity::class.java.name,null,false)
        try{
            shell("cmd appops set ${app.packageName} PICTURE_IN_PICTURE deny")
            ui{ScreenSetupHelp.open(app)}
            await("Native PiP permission page missing"){texts().any{it.contains("사진 속 사진")}&&automation.rootInActiveWindow?.packageName?.toString()!=app.packageName}
            assertEquals("A duplicate tutorial Activity was opened",0,monitor.hits)
            assertFalse(ScreenSetupHelp.active.value)
            capture("pip-permission-direct.png")
        }finally{
            shell("input keyevent 4")
            shell("cmd appops set ${app.packageName} PICTURE_IN_PICTURE $original")
            i.removeMonitor(monitor)
        }
    }
    @Test fun settingsHomeReplacesAnOldAppDetailsPage(){
        ui{app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        await("App details not opened"){texts().any{it.contains("애플리케이션 정보")}}
        try{
            ui{assertTrue(SetupPermissions.openHome(app))}
            await("Old Settings details survived the new mission entry"){texts().contains("디스플레이")&&texts().contains("연결")}
            assertFalse(texts().any{it.contains("애플리케이션 정보")})
            capture("settings-home.png")
        }finally{ui{app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}}
    }
    @Test fun microphoneGrantShowsTheSharedSuccessAndNoTextBypass(){
        assumeTrue(SetupPermissions.read(app).microphone&&SetupPermissions.read(app).screen)
        // Instrumentation stops the target process; rebind only the already-authorized service.
        if(AppRuntime.service==null){
            val services=shell("settings get secure enabled_accessibility_services").trim()
            val own=app.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
            try{shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=own}.joinToString(":").ifEmpty{"null"});Thread.sleep(350)}
            finally{shell("settings put secure enabled_accessibility_services $services")}
            await("Authorized screen service did not reconnect"){AppRuntime.service!=null&&AppRuntime.screenConnected.value}
        }
        val intent=Intent(app,MainActivity::class.java).putExtra(MicrophonePermission.EXTRA_PREPARE,true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ui{app.startActivity(intent)}
        try{
            await("Shared microphone success screen missing"){texts().contains("마이크가 켜졌어요")&&texts().contains("잘하셨어요!")}
            assertFalse(texts().contains("글로 계속하기"))
            capture("microphone-success.png")
        }finally{shell("input keyevent 4")}
    }
}
