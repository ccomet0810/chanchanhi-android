package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.R
import dev.ccomet.chanchanhi04.ScreenSetupHelpActivity
import dev.ccomet.chanchanhi04.ScreenSetupHelp
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owner-device opt-in. Changes only our service and restores it; no study answers are submitted. */
@RunWith(AndroidJUnit4::class)
class ScreenSetupHelpDeviceTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private var displayHeight=0
    private val out get()=File(context.getExternalFilesDir(null),"screen-setup-tour-checks").apply{mkdirs()}
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).use{r->r.readBytes().toString(Charsets.UTF_8).trim()}}
    private fun prepare(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenSetup")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}}
        displayHeight=automation.takeScreenshot()!!.let{b->b.height.also{b.recycle()}}
        ui{AppRuntime.initialize(context)}
        check(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.COMPLETE,Phase.STOPPED)){"Do not interrupt a user's guide"}
        check(AppRuntime.setup.state.value.phase!=SetupPhase.RUNNING){"Do not interrupt a user's setup"}
    }
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}};automation.windows.sortedByDescending{it.layer}.forEach{visit(it.root)};return all}
    private fun node(label:String):AccessibilityNodeInfo?{
        automation.clearCache()
        fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{
            n?:return null
            if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n
            repeat(n.childCount){find(n.getChild(it))?.let{return it}}
            return null
        }
        // PiP menus are transient. Return the topmost matching control without reading
        // every Settings node behind it until the menu has already timed out.
        automation.windows.sortedByDescending{it.layer}.forEach{find(it.root)?.let{return it}}
        return null
    }
    private fun capture(name:String){automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun await(message:String,timeout:Long=20000,condition:()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(150)};capture("failure.png");File(out,"failure-nodes.txt").writeText(nodes().joinToString("\n"){"${it.packageName} | ${it.text} | ${it.contentDescription} | ${it.isVisibleToUser}"});error(message)}
    private fun click(label:String){var n=node(label)?:error("Missing control: $label");while(!n.isClickable)n=n.parent?:error("Not clickable: $label");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun stableBounds(label:String):Rect{
        var previous:Rect?=null;var stable=0
        repeat(30){
            val current=node(label)?.let{Rect().also(it::getBoundsInScreen)}
            if(current!=null&&!current.isEmpty&&current==previous)stable++ else stable=0
            if(stable>=3)return current!!
            previous=current;Thread.sleep(100)
        }
        error("Setting did not finish its transition: $label")
    }
    private fun scrollTo(label:String){repeat(16){if(node(label)!=null)return;nodes().firstOrNull{it.packageName?.toString()=="com.android.settings"&&it.isVisibleToUser&&it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(250)};error("Missing setting: $label")}
    private fun freshMain(){
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        try{
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val main=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Fresh Main missing")
            await("Fresh Main has not resumed"){
                var ready=false
                ui{ready=main.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)&&main.window.decorView.hasWindowFocus()}
                ready
            }
            i.waitForIdleSync()
        }finally{i.removeMonitor(monitor)}
    }
    private fun startFromUserSettings(beforeOpen:()->Unit={}){
        freshMain()
        await("Main settings button missing"){node("설정")!=null};click("설정")
        await("Screen setting missing"){node("화면 안내")!=null};click("화면 안내")
        await("The one setup action is missing"){node("설정 이어가기")!=null}
        assertNull("PiP must not require a separate choice",node("보면서 따라하기"))
        capture("setup-one-action.png");beforeOpen();click("설정 이어가기")
    }
    private fun pipBounds():Rect?{
        automation.clearCache()
        val candidates=automation.windows.filter{it.isInPictureInPictureMode}.mapNotNull{w->Rect().also(w::getBoundsInScreen).takeIf{!it.isEmpty}?.let{w to it}}
        return candidates.firstOrNull{it.first.root?.packageName?.toString()==context.packageName}?.second
            ?:candidates.minByOrNull{it.second.width().toLong()*it.second.height()}?.second
    }
    private fun stablePipBounds():Rect{
        var previous:Rect?=null;var stable=0
        repeat(25){val current=pipBounds();if(current!=null&&current==previous)stable++else stable=0;if(stable>=3)return current!!;previous=current;Thread.sleep(100)}
        return pipBounds()?:error("PiP window missing")
    }
    private fun movePipUp(){
        val bounds=stablePipBounds()
        shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${maxOf(bounds.height()/2+80,displayHeight/6)} 650")
        Thread.sleep(900)
    }
    @Test fun unifiedOnboardingRunsThreePermissionsThenStartsParticipationAtOne(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runUnifiedPermissions")=="true")
        prepare();assumeTrue(ScreenSetupHelp.supported(context))
        check(!SetupPermissions.read(context).microphone){"Host must save and revoke only this test's microphone grant first"}
        val originalStudy=AppRuntime.study
        val originalState=originalStudy.state.value
        val suffix=java.util.UUID.randomUUID().toString()
        val isolated=object:android.content.ContextWrapper(context){
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir()=File(context.cacheDir,"permissions-onboarding-$suffix").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("permissions-$suffix-$name",mode)
        }
        val fixture=dev.ccomet.chanchanhi04.study.StudyRepository(isolated,{})
        val studyField=AppRuntime::class.java.getDeclaredField("study").apply{isAccessible=true}
        val before=fixture.state.value
        val oldDraft=fixture.draft("intro")
        val studyPrefs=isolated.getSharedPreferences("study-1",0)
        val hadDraft=studyPrefs.contains("draft-${before.runId}-intro")
        val micPrefs=context.getSharedPreferences("microphone-preparation-1",0)
        val oldMicPrefs=micPrefs.all.toMap()
        val services=shell("settings get secure enabled_accessibility_services")
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val originalConsent=AppRuntime.screenConsentAccepted
        val originalPip=Regex("PICTURE_IN_PICTURE: (\\w+)").find(shell("cmd appops get ${context.packageName} PICTURE_IN_PICTURE"))?.groupValues?.get(1)?:"default"
        val monitor=i.addMonitor(ScreenSetupHelpActivity::class.java.name,null,false)
        var helper:ScreenSetupHelpActivity?=null
        fun assertOnboarding(){assertNotNull(node("찬찬히"));assertNotNull(node("설정"));assertNull(node("뒤로가기"));assertNotNull(node("이전"));assertNotNull(node("앱 권한 설정하기"))}
        fun buttonWithId(suffix:String)=nodes().firstOrNull{it.isVisibleToUser&&it.viewIdResourceName?.endsWith("/$suffix")==true}
        fun checklistReady()=nodes().any{it.isVisibleToUser&&it.packageName?.toString()==context.packageName&&it.contentDescription?.toString()?.let{text->text.contains("1. 목록에서")&&text.contains("4. 팝업 아래 ‘허용’")}==true}
        try{
            ui{studyField.set(AppRuntime,fixture)}
            micPrefs.edit().clear().commit()
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE deny")
            shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"})
            await("Own service must detach before this opt-in journey"){!SetupPermissions.read(context).screen&&AppRuntime.service==null}
            AppRuntime.study.saveDraft("intro",org.json.JSONObject(oldDraft.toString()).put("_index",1))
            freshMain()
            await("First permission step missing"){node("권한 설정 1/3")!=null&&node("작은 창 허용하기")!=null}
            assertOnboarding();assertNull(node("잘하셨어요!"))
            val header=stableBounds("찬찬히");capture("unified-pip-permission.png")
            click("작은 창 허용하기")
            await("Native PiP permission missing"){node("권한 허용")!=null};click("권한 허용")
            await("Native PiP permission not granted"){ScreenSetupHelp.allowed(context)};shell("input keyevent 4")
            helper=monitor.waitForActivityWithTimeout(12000)as? ScreenSetupHelpActivity
            await("Second step must preserve onboarding with a real PiP"){node("권한 설정 2/3")!=null&&pipBounds()!=null}
            assertNotNull(helper);await("PiP must draw the actual four instructions"){checklistReady()};stablePipBounds();Thread.sleep(700)
            assertOnboarding();assertEquals(header,stableBounds("찬찬히"));assertNull(node("잘하셨어요!"))
            capture("unified-accessibility.png");movePipUp()
            click(if(node("설정 시작하기")!=null)"설정 시작하기"else"설정 이어가기")
            await("Accessibility Settings missing"){node("설치된 앱")!=null}
            scrollTo("설치된 앱");click("설치된 앱")
            await("Own service name missing"){node(context.getString(R.string.accessibility_name))!=null};click(context.getString(R.string.accessibility_name))
            await("Service switch missing"){node("사용 안 함")!=null}
            stableBounds("사용 안 함");click("사용 안 함")
            await("Native accessibility confirmation missing"){node("허용")!=null}
            stableBounds("허용");click("허용")
            await("Connected accessibility must advance directly to microphone"){
                SetupPermissions.read(context).screen&&AppRuntime.service!=null&&node("권한 설정 3/3")!=null&&node("마이크 허용하기")?.isEnabled==true
            }
            await("Accessibility completion must close its PiP"){helper?.isDestroyed==true&&!ScreenSetupHelp.active.value}
            assertOnboarding();assertEquals(header,stableBounds("찬찬히"));assertNull(node("잘하셨어요!"));assertNull(node("화면 안내가 켜졌어요"))
            capture("unified-microphone.png");click("마이크 허용하기")
            await("Native microphone dialog missing"){buttonWithId("permission_deny_button")!=null}
            check(buttonWithId("permission_deny_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Denial must remain in the same third step"){node("권한 설정 3/3")!=null&&node("마이크 다시 허용하기")!=null}
            assertOnboarding();assertNull(node("잘하셨어요!"));click("마이크 다시 허용하기")
            await("Native microphone retry missing"){buttonWithId("permission_allow_foreground_only_button")!=null}
            check(buttonWithId("permission_allow_foreground_only_button")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Only all three grants may show the combined completion"){SetupPermissions.read(context).microphone&&node("설정을 모두 마쳤어요")!=null}
            assertOnboarding();assertEquals(header,stableBounds("찬찬히"));assertEquals(1,nodes().count{it.isVisibleToUser&&it.text?.toString()=="잘하셨어요!"})
            capture("unified-complete.png");click("다음")
            val total=dev.ccomet.chanchanhi04.study.SurveyProgressInfo.forScreen(dev.ccomet.chanchanhi04.study.StudyPhase.INTRO,2,AppRuntime.study.rules.template)!!.total
            await("Consent must start a separate survey process at one"){node("수집 내용 확인")!=null&&node("설문 조사 1/$total")!=null}
            assertEquals(header,stableBounds("찬찬히"));assertNotNull(node("설정"));assertNull(node("권한 설정 3/3"))
            capture("participation-consent.png");click("이전")
            await("Previous must return to the same combined completion"){node("권한 설정 3/3")!=null&&node("설정을 모두 마쳤어요")!=null}
            // Retain the previous real-device regression in the new shared onboarding shell.
            ui{check(SetupPermissions.open(context,SetupRequirement.SCREEN))}
            await("Accessibility Settings missing before revocation"){node("설치된 앱")!=null}
            scrollTo("설치된 앱");click("설치된 앱")
            await("Own enabled service missing"){node(context.getString(R.string.accessibility_name))!=null};click(context.getString(R.string.accessibility_name))
            await("Enabled switch missing"){node("사용 중")!=null||node("사용함")!=null}
            click(if(node("사용 중")!=null)"사용 중"else"사용함")
            await("Native disable confirmation missing"){node("끄기")!=null};stableBounds("끄기");click("끄기")
            await("Native accessibility revocation not observed"){!SetupPermissions.read(context).screen&&AppRuntime.service==null}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            val previousHelper=helper
            await("A fresh helper must be created after revocation"){
                val candidate=monitor.lastActivity as? ScreenSetupHelpActivity
                if(candidate!=null&&candidate!==previousHelper&&!candidate.isDestroyed){helper=candidate;true}else false
            }
            await("Revocation must return to step two with a real PiP"){node("권한 설정 2/3")!=null&&pipBounds()!=null}
            await("Restored PiP instructions missing"){checklistReady()};stablePipBounds();Thread.sleep(700)
            assertOnboarding();assertEquals(header,stableBounds("찬찬히"));assertNull(node("잘하셨어요!"))
            capture("unified-revoked-return.png")
            assertOnboarding();assertEquals(before.runId,AppRuntime.study.state.value.runId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        }finally{
            ui{helper?.finish();ScreenSetupHelp.close();AppRuntime.setup.cancel();AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",originalConsent).apply()}
            if(hadDraft)fixture.saveDraft("intro",oldDraft)else fixture.clearDraft("intro")
            micPrefs.edit().clear().apply{oldMicPrefs.forEach{(key,value)->when(value){is Boolean->putBoolean(key,value);is String->putString(key,value);is Int->putInt(key,value);is Long->putLong(key,value);is Float->putFloat(key,value)}}}.commit()
            shell("settings put secure enabled_accessibility_services $services")
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE $originalPip")
            ui{studyField.set(AppRuntime,originalStudy)}
            assertEquals("The participant's study must remain untouched",originalState,originalStudy.state.value)
            i.removeMonitor(monitor);freshMain();Thread.sleep(400);ui{ScreenSetupHelp.dismissPreview()}
        }
    }
    @Test fun onboardingReturnKeepsItsPageHeaderSettingsAndDraft(){
        prepare();assumeTrue(ScreenSetupHelp.supported(context))
        val before=AppRuntime.study.state.value
        assumeTrue("This owner journey must still be in onboarding",before.phase==dev.ccomet.chanchanhi04.study.StudyPhase.INTRO)
        val oldDraft=AppRuntime.study.draft("intro")
        val prefs=context.getSharedPreferences("study-1",android.content.Context.MODE_PRIVATE)
        val hadDraft=prefs.contains("draft-${before.runId}-intro")
        val services=shell("settings get secure enabled_accessibility_services")
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val originalConsent=AppRuntime.screenConsentAccepted
        val originalPip=Regex("PICTURE_IN_PICTURE: (\\w+)").find(shell("cmd appops get ${context.packageName} PICTURE_IN_PICTURE"))?.groupValues?.get(1)?:"default"
        val monitor=i.addMonitor(ScreenSetupHelpActivity::class.java.name,null,false)
        var helper:ScreenSetupHelpActivity?=null
        try{
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE allow")
            shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"})
            await("Own service did not detach"){!SetupPermissions.read(context).screen&&AppRuntime.service==null}
            val pages=AppRuntime.study.rules.template.getJSONArray("onboarding")
            val index=(0 until pages.length()).single{pages.getJSONObject(it).optString("id")=="setup"}
            // Change only the local page cursor; never submit survey answers or create a study run.
            AppRuntime.study.saveDraft("intro",org.json.JSONObject(oldDraft.toString()).put("_index",index))
            freshMain()
            await("Original onboarding page missing"){node("찬찬히")!=null&&node("앱 권한 설정하기")!=null&&node("설정")!=null&&(node("설정 시작하기")!=null||node("설정 이어가기")!=null)}
            helper=monitor.waitForActivityWithTimeout(12000)as? ScreenSetupHelpActivity
            assertNotNull(helper);await("Real PiP missing"){helper!!.isInPictureInPictureMode}
            movePipUp();click(if(node("설정 시작하기")!=null)"설정 시작하기"else"설정 이어가기")
            await("Settings did not open"){helper!!.settingsStarted()&&nodes().any{it.packageName?.toString() in SetupPermissions.settingsPackages(context)&&it.isVisibleToUser}}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("Incomplete return must preserve onboarding and its settings action"){
                node("찬찬히")!=null&&node("설정")!=null&&node("앱 권한 설정하기")!=null&&node("설정 이어가기")!=null&&node("화면 안내가 아직 꺼져 있어요.")!=null
            }
            assertNull(node("뒤로가기"));assertNull(node("화면 정보 처리 안내 보기"))
            assertEquals(1,monitor.hits);assertEquals(index,AppRuntime.study.draft("intro").getInt("_index"))
            assertEquals(before.runId,AppRuntime.study.state.value.runId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
            stableBounds("찬찬히");Thread.sleep(600);capture("incomplete-return-to-onboarding.png")
            click("이전")
            await("Previous must close the guide and return to the original introduction"){
                !ScreenSetupHelp.active.value&&node("찬찬히 소개")!=null&&node("설정")!=null
            }
            assertEquals(index-1,AppRuntime.study.draft("intro").getInt("_index"))
        }finally{
            ui{helper?.finish();AppRuntime.setup.cancel();AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",originalConsent).apply()}
            if(hadDraft)AppRuntime.study.saveDraft("intro",oldDraft)else AppRuntime.study.clearDraft("intro")
            shell("settings put secure enabled_accessibility_services $services")
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE $originalPip")
            i.removeMonitor(monitor);freshMain();Thread.sleep(400);ui{ScreenSetupHelp.dismissPreview()}
        }
    }
    @Test fun portraitChecklistFollowsPermissionAndCompletesWithoutNextActions(){
        prepare();assumeTrue(ScreenSetupHelp.supported(context))
        val services=shell("settings get secure enabled_accessibility_services")
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val originalConsent=AppRuntime.screenConsentAccepted
        val originalPip=Regex("PICTURE_IN_PICTURE: (\\w+)").find(shell("cmd appops get ${context.packageName} PICTURE_IN_PICTURE"))?.groupValues?.get(1)?:"default"
        val before=AppRuntime.study.state.value
        val monitor=i.addMonitor(ScreenSetupHelpActivity::class.java.name,null,false)
        val createdModes=java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val lifecycleRegistry=ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycleCallback=ActivityLifecycleCallback{activity,stage->
            if(activity is ScreenSetupHelpActivity&&stage==Stage.CREATED)createdModes.add(activity.isInPictureInPictureMode)
        }
        ui{lifecycleRegistry.addLifecycleCallback(lifecycleCallback)}
        var helper:ScreenSetupHelpActivity?=null
        fun lastSpeech()=AppRuntime::class.java.getDeclaredField("lastSpeech").apply{isAccessible=true}.get(AppRuntime)
        var speechBefore:Any?=null
        try{
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE deny")
            shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"})
            await("Own service did not detach"){!SetupPermissions.read(context).screen&&AppRuntime.service==null}
            startFromUserSettings{speechBefore=lastSpeech()}
            await("Native PiP permission missing"){node("권한 허용")!=null}
            assertEquals(0,monitor.hits)
            shell("input keyevent 4")
            await("Denied permission must return to the same page"){node("앱 권한 설정하기")!=null&&node("설정 이어가기")!=null}
            assertFalse(ScreenSetupHelp.allowed(context));assertEquals(0,monitor.hits)
            capture("pip-permission-first-page.png");click("설정 이어가기")
            await("Native permission retry missing"){node("권한 허용")!=null};click("권한 허용")
            await("PiP permission not granted"){ScreenSetupHelp.allowed(context)}
            shell("input keyevent 4")
            await("Gesture lesson did not open"){node("앱 권한 설정하기")!=null&&(node("설정 시작하기")!=null||node("설정 이어가기")!=null)&&node("일시정지")!=null}
            helper=monitor.waitForActivityWithTimeout(15000)as? ScreenSetupHelpActivity
            assertNotNull("Lesson must open the real PiP",helper)
            await("PiP must already be visible on the lesson"){helper!!.isInPictureInPictureMode&&ScreenSetupHelp.active.value}
            ui{assertNotEquals("The helper must not take over Main's task",helper!!.taskId,
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().taskId)}
            File(out,"created-pip-modes.txt").writeText(createdModes.toString())
            assertEquals(1,monitor.hits)
            assertFalse("Settings must wait for the user's start action",helper!!.settingsStarted())
            assertNotEquals(SetupPhase.RUNNING,AppRuntime.setup.state.value.phase)
            stablePipBounds();Thread.sleep(1500)
            capture("live-pip-on-gesture-lesson.png")
            // Practice moving the real window so that the main start action is uncovered.
            movePipUp()
            click(if(node("설정 시작하기")!=null)"설정 시작하기"else"설정 이어가기")
            await("Main start must open PiP and the real Settings page"){
                helper!!.isInPictureInPictureMode&&helper!!.settingsStarted()&&nodes().any{it.packageName?.toString() in SetupPermissions.settingsPackages(context)&&it.isVisibleToUser}
            }
            assertEquals("Starting Settings must retain the existing PiP activity",1,monitor.hits)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("Incomplete return must keep the original Settings page"){
                node("화면 안내")!=null&&node("뒤로가기")!=null&&node("앱 권한 설정하기")!=null&&node("설정 이어가기")!=null&&node("화면 안내가 아직 꺼져 있어요.")!=null
            }
            assertNull(node("설정이 아직 남아 있어요. 이어서 설정해주세요."))
            assertNull(node("화면 정보 처리 안내 보기"))
            stableBounds("화면 안내");Thread.sleep(600);capture("incomplete-return-to-settings.png")
            click("설정 이어가기")
            await("Continue must return to the actual Settings page"){
                nodes().any{it.packageName?.toString() in SetupPermissions.settingsPackages(context)&&it.isVisibleToUser}
            }
            assertEquals("Incomplete return must not create a second PiP activity",1,monitor.hits)
            val bounds=stablePipBounds();val ratio=bounds.height().toFloat()/bounds.width()
            assertTrue("Checklist must use 2:3 proportions",ratio in 1.4f..1.6f)
            await("All setup actions must remain available together"){
                nodes().any{it.packageName?.toString()==context.packageName&&it.contentDescription?.toString()?.let{text->text.contains("1. 목록에서")&&text.contains(context.getString(R.string.accessibility_name))&&text.contains("3. ‘사용 안 함’")&&text.contains("4. 팝업 아래 ‘허용’")}==true}
            }
            assertNull(node("다음 안내"));assertNull(node("이전 안내"))
            capture("samsung-targets-in-pip.png")
            val small=stablePipBounds()
            shell("input tap ${small.centerX()} ${small.centerY()}")
            shell("input tap ${small.centerX()} ${small.centerY()}")
            await("Double tap must enlarge PiP"){pipBounds()?.width()?.let{it>small.width()*1.15}==true}
            stablePipBounds();Thread.sleep(600);capture("samsung-targets-enlarged.png")
            movePipUp();capture("portrait-checklist-in-settings.png")
            scrollTo("설치된 앱");click("설치된 앱")
            await("Own service missing"){node(context.getString(R.string.accessibility_name))!=null}
            click(context.getString(R.string.accessibility_name))
            await("Service switch missing"){node("사용 안 함")!=null}
            val toggle=stableBounds("사용 안 함");val exposedX=toggle.left+minOf(48,toggle.width()/4)
            assertFalse(pipBounds()?.contains(exposedX,toggle.centerY())==true)
            shell("input tap $exposedX ${toggle.centerY()}")
            await("Actual confirmation missing"){node("허용")!=null}
            val allow=stableBounds("허용");pipBounds()?.let{assertFalse(Rect.intersects(it,allow))}
            capture("portrait-checklist-permission-popup.png")
            assertFalse(SetupPermissions.read(context).screen)
            shell("input tap ${allow.centerX()} ${allow.centerY()}")
            await("Real grant and service connection missing"){SetupPermissions.read(context).screen&&AppRuntime.service!=null}
            await("Verified completion did not close PiP"){helper!!.isDestroyed&&!ScreenSetupHelp.active.value}
            val completedHelper=helper
            await("Setup completion must return to Main before a new Settings visit"){
                var resumed=false
                ui{resumed=lifecycleRegistry.getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any{it.window.decorView.hasWindowFocus()}}
                AppRuntime.setup.state.value.phase!=SetupPhase.RUNNING&&resumed&&node("앱 권한 설정하기")!=null
            }
            i.waitForIdleSync()
            // Repeat the owner's reported path: enable, go back into Settings, disable,
            // then return to the same Main instance without resetting its saved UI state.
            ui{check(SetupPermissions.open(context,SetupRequirement.SCREEN))}
            await("Accessibility landing page missing after completion"){node("설치된 앱")!=null}
            scrollTo("설치된 앱");click("설치된 앱")
            await("Own service missing after completion"){node(context.getString(R.string.accessibility_name))!=null}
            click(context.getString(R.string.accessibility_name))
            await("Enabled service switch missing"){node("사용 중")!=null||node("사용함")!=null}
            click(if(node("사용 중")!=null)"사용 중"else"사용함")
            await("Native revocation confirmation missing"){node("끄기")!=null}
            stableBounds("끄기");click("끄기")
            await("Own service did not detach after native revocation"){!SetupPermissions.read(context).screen&&AppRuntime.service==null}
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            helper=monitor.waitForActivityWithTimeout(15000)as? ScreenSetupHelpActivity
            assertNotNull("Revocation must create a fresh guide",helper)
            assertNotSame(completedHelper,helper)
            await("Revoked permission must show a real PiP, not a stuck full-screen guide"){
                helper!!.isInPictureInPictureMode&&pipBounds()!=null&&node("앱 권한 설정하기")!=null
            }
            assertEquals(2,monitor.hits)
            stablePipBounds();Thread.sleep(700);capture("revoked-return-pip.png")
            assertEquals(before.runId,AppRuntime.study.state.value.runId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
            assertEquals("Screen setup must not speak",speechBefore,lastSpeech())
        }finally{
            ui{helper?.finish();AppRuntime.setup.cancel();AppRuntime.preferences.edit().putBoolean("screen-disclosure-accepted",originalConsent).apply()}
            shell("settings put secure enabled_accessibility_services $services")
            shell("cmd appops set ${context.packageName} PICTURE_IN_PICTURE $originalPip")
            i.removeMonitor(monitor)
            ui{lifecycleRegistry.removeLifecycleCallback(lifecycleCallback)}
            freshMain();Thread.sleep(400);ui{ScreenSetupHelp.dismissPreview()}
        }
    }
}
