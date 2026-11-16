package dev.ccomet.chanchanhi04.qa

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.R
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.launch

/** Explicitly opted-in owner-device test. Only our own permissions change and are restored. */
@RunWith(AndroidJUnit4::class)
class SetupFlowDeviceTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes().toString(Charsets.UTF_8).trim()}}
    private fun await(message:String,millis:Long=20000,condition:()->Boolean){val end=SystemClock.uptimeMillis()+millis;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(150)};capture("failure.png");File(context.getExternalFilesDir(null),"setup-checks/failure-nodes.txt").writeText(nodes().filter{it.packageName?.toString()==context.packageName}.joinToString("\n"){"${it.text} | ${it.contentDescription} | ${it.isVisibleToUser}"});error(message)}
    private fun nodes():List<AccessibilityNodeInfo>{if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){if(n==null)return;result+=n;repeat(n.childCount){visit(n.getChild(it))}};visit(automation.rootInActiveWindow);return result}
    private fun match(text:String)=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()?.split('\n')?.contains(text)==true||it.contentDescription?.toString()==text)}
    private fun click(text:String){Thread.sleep(550);var n=match(text)?:error("Missing public settings control: $text");while(!n.isClickable)n=n.parent?:error("Not clickable: $text");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun scrollTo(text:String){
        repeat(18){if(match(text)!=null){
            // A clipped Compose node can enter the accessibility tree before its label is fully
            // on screen. This is the final content item, so scrolling to the end cannot skip it.
            if(text=="화면 정보 처리 안내 보기")repeat(2){nodes().firstOrNull{it.isVisibleToUser&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(180)}
            return
        }
            if(automation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"){
                val r=android.graphics.Rect();automation.rootInActiveWindow.getBoundsInScreen(r)
                shell("input swipe ${r.centerX()} ${(r.top+r.height()*.80).toInt()} ${r.centerX()} ${(r.top+r.height()*.32).toInt()} 300")
            }else{
                val scroll=nodes().firstOrNull{it.isVisibleToUser&&it.isScrollable}?:error("Scrollable content missing")
                check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            };Thread.sleep(250)
        };error("Content link was not reachable: $text")
    }
    private fun capture(name:String){Thread.sleep(400);val out=File(context.getExternalFilesDir(null),"setup-checks").apply{mkdirs()};automation.takeScreenshot()?.let{bitmap->File(out,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    private fun openMain(){i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))}
    @Test fun settingsHasNoBottomNavigationAndBootstrapContinuesIntoNativeMicPermission(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runSetupUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}}
        ui{AppRuntime.initialize(context);AppRuntime.setup.cancel()}
        val services=shell("settings get secure enabled_accessibility_services")
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val originalConsent=AppRuntime.screenConsentAccepted
        val micOriginallyGranted=SetupPermissions.read(context).microphone
        var screenGrantedInUi=false
        val trace=File(context.getExternalFilesDir(null),"setup-checks/state-trace.txt").apply{parentFile?.mkdirs();writeText("")}
        var traceJob:kotlinx.coroutines.Job?=null
        ui{traceJob=AppRuntime.scope.launch{AppRuntime.setup.state.collect{trace.appendText("$it service=${AppRuntime.service!=null}\n")}}}
        check(!SetupPermissions.read(context).microphone){"Revoke only this test app's microphone before instrumentation; revocation restarts processes"}
        try{
            ui{AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",false).apply()}
            // Bootstrap is intentionally observed through UiAutomation while our own service is off.
            shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"})
            await("Our accessibility service must detach"){!SetupPermissions.read(context).screen}
            openMain();await("Gear missing"){match("설정")!=null};click("설정")
            await("Settings missing"){match("화면 안내")!=null}
            assertNull("Pushed settings must not show a home tab",match("홈"));capture("settings.png")
            click("화면 안내");await("Concrete screen setup instructions missing"){match("화면 안내를 켜주세요")!=null}
            assertNull(match("글자 크기"));assertNull(match("표시한 곳을 눌러주세요"));capture("accessibility-instructions.png")
            assertFalse(AppRuntime.screenConsentAccepted)
            scrollTo("화면 정보 처리 안내 보기");click("화면 정보 처리 안내 보기")
            await("Read-only information page missing"){match("화면 정보 처리 안내")!=null}
            assertFalse("Reading the information must not silently accept it",AppRuntime.screenConsentAccepted)
            capture("access-information.png");click("돌아가기")
            await("Information page did not return to setup"){match("설정 시작하기")!=null}
            click("설정 시작하기")
            await("Accessibility destination missing"){automation.rootInActiveWindow?.packageName?.toString() in SetupPermissions.settingsPackages(context)}
            assertTrue(AppRuntime.screenConsentAccepted)
            assertNull("Start must not show the removed confirmation dialog",match("화면 안내를 켤까요?"))
            // The details destination is protected on this Samsung. These are test actions on its
            // observed public UI, not a production route or fixed button sequence in the app.
            val listHeader=match("설치된 앱")?.let{n->android.graphics.Rect().also{n.getBoundsInScreen(it)}.top<350}==true
            if(match(context.getString(R.string.accessibility_name))==null&&!listHeader){await("Installed apps missing"){match("설치된 앱")!=null};click("설치된 앱")}
            scrollTo(context.getString(R.string.accessibility_name));click(context.getString(R.string.accessibility_name))
            await("Service switch missing"){nodes().any{it.isVisibleToUser&&it.isCheckable&&!it.isChecked}}
            var toggle=nodes().first{it.isVisibleToUser&&it.isCheckable&&!it.isChecked};while(!toggle.isClickable)toggle=toggle.parent?:error("Switch not clickable")
            check(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("System accessibility confirmation missing"){match("허용")!=null};capture("accessibility-confirmation.png");click("허용")
            await("Grant must be verified"){SetupPermissions.read(context).screen}
            screenGrantedInUi=true
            await("Must proceed to microphone without returning to the app"){AppRuntime.setup.state.value.requirement==SetupRequirement.MICROPHONE}
            await("Native microphone confirmation missing"){match("앱 사용 중에만 허용")!=null}
            assertFalse(SetupPermissions.read(context).microphone)
            capture("microphone-permission.png");click("앱 사용 중에만 허용")
            await("All real grants must automatically finish setup"){AppRuntime.setup.state.value.phase==SetupPhase.COMPLETE}
            await("Main must return without an extra back instruction"){match("설정")!=null}
            capture("returned-to-app.png")
        }finally{
            ui{traceJob?.cancel()}
            ui{AppRuntime.setup.cancel();AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",originalConsent).apply()}
            val restored=(services.split(':').filter{it.isNotBlank()&&it!="null"}+if(screenGrantedInUi)listOf(component)else emptyList()).distinct().joinToString(":").ifEmpty{"null"}
            shell("settings put secure enabled_accessibility_services $restored")
            // This opt-in fixture is started with microphone temporarily revoked by the test runner.
            // The owner had granted it before that preparation, so restore usability at the end.
            if(!micOriginallyGranted)shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            openMain()
        }
    }
    @Test fun microphoneCanBeRequestedFromTheAlreadyResumedApp(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runDirectMicUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui{AppRuntime.initialize(context);AppRuntime.setup.cancel();AppRuntime.acceptScreenConsent()}
        check(SetupPermissions.read(context).screen&&!SetupPermissions.read(context).microphone)
        try{
            openMain();await("App must be visible"){match("설정")!=null}
            ui{AppRuntime.beginSetup()}
            await("Resumed app did not show the native request"){match("앱 사용 중에만 허용")!=null}
            click("앱 사용 중에만 허용")
            await("Verified grant did not finish setup"){AppRuntime.setup.state.value.phase==SetupPhase.COMPLETE}
        }finally{
            ui{AppRuntime.setup.cancel()}
            shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        }
    }
    @Test fun firstAccessNoticeAndStartRemainVisibleAtLargeText(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runFirstAccessUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui{AppRuntime.initialize(context);AppRuntime.setup.cancel()}
        val services=shell("settings get secure enabled_accessibility_services")
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val originalConsent=AppRuntime.screenConsentAccepted;val oldScale=shell("settings get system font_scale")
        try{
            shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"})
            ui{AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",false).apply()}
            for(scale in listOf("1.0","2.0")){
                shell("settings put system font_scale $scale");Thread.sleep(500);openMain()
                await("Gear missing"){match("설정")!=null};click("설정")
                await("Screen setup missing"){match("화면 안내")!=null};click("화면 안내")
                await("Start missing"){match("설정 시작하기")!=null}
                for(label in listOf("설정 시작하기")){
                    val node=match(label)?:error("Missing setup action: $label");val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
                    assertTrue("Setup footer clipped at font scale $scale",rect.top>=0&&rect.bottom<=context.resources.displayMetrics.heightPixels)
                }
                assertNull(match("다른 앱의 화면 글자, 버튼 위치와 앱 정보를 AI 서버로 보내 안내해요. 개인 내용이 포함될 수 있어요."))
                val footer=android.graphics.Rect();match("설정 시작하기")!!.getBoundsInScreen(footer)
                scrollTo("화면 정보 처리 안내 보기")
                val link=match("화면 정보 처리 안내 보기")?:error("Content link missing")
                val linkBounds=android.graphics.Rect();link.getBoundsInScreen(linkBounds)
                assertTrue("Details link must remain in scrolling content above the fixed actions",linkBounds.height()>0&&linkBounds.bottom<=footer.top)
                match("설치된 앱 또는 다운로드한 앱을 눌러주세요.")?.let{step->
                    val bounds=android.graphics.Rect();step.getBoundsInScreen(bounds)
                    assertTrue("Scrolling instructions overlap the fixed actions at font scale $scale",bounds.bottom<=footer.top)
                }
                capture("first-access-font-$scale.png");assertFalse(AppRuntime.screenConsentAccepted)
            }
        }finally{
            if(oldScale=="null")shell("settings delete system font_scale")else shell("settings put system font_scale $oldScale")
            shell("settings put secure enabled_accessibility_services $services")
            ui{AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",originalConsent).apply()}
            openMain()
        }
    }
}
