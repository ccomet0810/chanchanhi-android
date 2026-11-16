package dev.ccomet.chanchanhi04.qa

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.VoiceInputActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.*
import dev.ccomet.chanchanhi04.voice.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Explicit owner-device permission interval; host restores the original grant/flags afterwards.
 * Public text only, in-memory question loop, no participant or session journal may change. */
@RunWith(AndroidJUnit4::class)
class MicrophoneIntegrationDeviceTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext
    private lateinit var automation:UiAutomation
    private var originalPreferences:Map<String,*>?=null
    private var journals:Map<String,String>?=null
    private val activities=mutableListOf<Activity>()
    private val starts=AtomicInteger()
    private var oldLoop:AgentLoop?=null
    private var oldRequirement:SetupRequirement?=null
    private var fakeLoop:AgentLoop?=null
    private val out get()=File(app.getExternalFilesDir(null),"native-microphone-checks").apply{mkdirs()}
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).use{r->r.readBytes().toString(Charsets.UTF_8).trim()}}
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(80)};error(message)}
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}};automation.windows.sortedByDescending{it.layer}.forEach{visit(it.root)};return all}
    private fun has(label:String)=nodes().any{it.isVisibleToUser&&it.text?.toString()==label}
    private fun clickNode(node:AccessibilityNodeInfo){var n=node;while(!n.isClickable)n=n.parent?:error("No clickable public control");assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun click(label:String){clickNode(nodes().first{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)})}
    private fun permissionButton(suffix:String)=nodes().firstOrNull{it.isVisibleToUser&&it.viewIdResourceName?.endsWith("/"+suffix)==true}
    private fun nativeDialog()=permissionButton("permission_deny_button")!=null||permissionButton("permission_allow_foreground_only_button")!=null
    private fun decidePermission(allow:Boolean){
        fun choice()=if(allow)permissionButton("permission_allow_foreground_only_button")else
            permissionButton("permission_deny_button")?:permissionButton("permission_deny_and_dont_ask_again_button")
        await("Native Android permission choice missing"){choice()!=null}
        capture(if(allow)"native-allow.png"else"native-deny.png");clickNode(choice()!!)
        await("Native permission dialog did not close"){!nativeDialog()}
    }
    private fun capture(name:String){automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun snapshot():Map<String,String> = listOf("v04-studies","v04-sessions").flatMap{folder->File(app.filesDir,folder).listFiles().orEmpty().filter{it.extension=="jsonl"}.map{folder+"/"+it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString(""){b->"%02x".format(b)}}}.toMap()
    private fun controller(activity:Activity)=activity.javaClass.getDeclaredField("microphone").apply{isAccessible=true}.get(activity)as MicrophonePermission
    @Before fun prepare(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runNativeMicUI")=="true")
        check(!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        assumeTrue("Host must configure the isolated native permission interval",app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}}
        journals=snapshot();originalPreferences=app.getSharedPreferences("microphone-preparation-1",0).all.toMap()
        app.getSharedPreferences("microphone-preparation-1",0).edit().clear().commit()
        ui{AppRuntime.initialize(app)}
        check(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE)){"Do not interrupt user guidance"}
        val services=shell("settings get secure enabled_accessibility_services");val own=app.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        check(own in services.split(':')){"Screen access must already be the owner's choice"}
        if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+services.split(':').filter{it!=own}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shell("settings put secure enabled_accessibility_services $services")}}
        await("Authorized screen service did not connect"){AppRuntime.service!=null&&AppRuntime.screenConnected.value}
        ui{VoiceEngines.installForTest{object:VoiceEngine{override fun start(events:VoiceEngine.Events){starts.incrementAndGet()};override fun stop()=Unit;override fun cancel()=Unit}}}
    }
    @After fun restore(){
        if(!::automation.isInitialized)return
        ui{
            fakeLoop?.stop()
            oldLoop?.let{AppRuntime::class.java.getDeclaredField("loop").apply{isAccessible=true}.set(AppRuntime,it);AppRuntime::class.java.getDeclaredField("setupRequirement").apply{isAccessible=true}.set(AppRuntime,oldRequirement)}
            activities.forEach{if(!it.isDestroyed)it.finish()};VoiceEngines.installForTest(null)
        }
        originalPreferences?.let{values->val edit=app.getSharedPreferences("microphone-preparation-1",0).edit().clear();values.forEach{(key,v)->when(v){is Boolean->edit.putBoolean(key,v);is String->edit.putString(key,v);is Int->edit.putInt(key,v);is Long->edit.putLong(key,v);is Float->edit.putFloat(key,v);is Set<*>->edit.putStringSet(key,v.filterIsInstance<String>().toSet())}};edit.commit()}
        if(AppRuntime.initialized)AppRuntime.log.awaitWrites()
        journals?.let{assertTrue("Owner research/session journals changed",it==snapshot())}
    }
    private fun openMain():MainActivity{
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        try{app.startActivity(Intent(app,MainActivity::class.java).putExtra(MicrophonePermission.EXTRA_PREPARE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            val activity=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Native preparation host missing");activities+=activity
            await("Native preparation did not render"){has("마이크 준비")&&has("마이크 허용하기")};return activity
        }finally{i.removeMonitor(monitor)}
    }
    @Test fun nativeGrantDoesNotStartRecordingOrRequestAgainOnResume(){
        val activity=openMain();assertFalse(nativeDialog());assertEquals(0,starts.get())
        click("마이크 허용하기");await("Native request missing"){nativeDialog()};assertTrue(controller(activity).state.pending)
        ui{controller(activity).request()};decidePermission(true)
        await("Native OS grant not reflected"){controller(activity).state.granted&&has("마이크가 켜졌어요")}
        assertEquals(0,starts.get());assertFalse(controller(activity).state.pending)
        ui{controller(activity).refresh();controller(activity).request()};Thread.sleep(500)
        assertFalse(nativeDialog());assertEquals(0,starts.get());capture("grant-preparation-only.png")
    }
    @Test fun pendingRotationDenialRetryAndExplicitSettingsReturnDoNotAutoRequest(){
        var activity=openMain();click("마이크 허용하기");await("Native request missing"){nativeDialog()}
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        try{ui{activity.recreate()};activity=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Recreated native host missing");activities+=activity}
        finally{i.removeMonitor(monitor)}
        assertTrue("Restored native request not pending",controller(activity).state.pending);decidePermission(false)
        await("First denial not reflected"){!controller(activity).state.pending&&controller(activity).state.status==MicrophonePermissionStatus.RETRY}
        assertEquals(0,starts.get());Thread.sleep(500);assertFalse(nativeDialog())
        click("마이크 다시 허용하기");await("Explicit retry did not request"){nativeDialog()};decidePermission(false)
        await("Explicit settings fallback missing"){controller(activity).state.status==MicrophonePermissionStatus.SETTINGS&&has("앱 권한 확인하기")}
        click("앱 권한 확인하기");await("Explicit native app settings did not open"){nodes().any{it.isVisibleToUser&&it.packageName?.toString()!=app.packageName}}
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await("Returning from settings changed the permission flow"){has("앱 권한 확인하기")&&!nativeDialog()}
        assertEquals(0,starts.get());click("이전");await("Text continuation did not close preparation"){!has("마이크 준비")}
        assertEquals(0,starts.get())
    }
    @Test fun nativeVoiceQuestionRetainsDraftAndTargetAcrossRequestRecreationAndDenial(){
        val requests=mutableListOf<AgentRequest>()
        val screen=Observation(1,"public.fixture",7,1,1,1080,2340,true,emptyList())
        val device=object:DevicePort{override suspend fun observe()=screen;override suspend fun capture()=ScreenImage(screen,"",1,1);override fun installedApps()=emptyList<AppEntry>();override fun render(state:AgentState)=Unit;override fun speak(message:String)=Unit;override fun stopSpeech()=Unit;override val imagesAllowed=false}
        val model=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{requests+=request;return ToolCall("public-question-"+requests.size,Tool.ASK_USER,"공개 질문",question="공개 답변을 적어주세요")}}
        check(AppRuntime.log.rows.value.isEmpty()){"Use a fresh runner with no existing session sink"}
        ui{oldLoop=AppRuntime.loop;oldRequirement=AppRuntime.setupRequirement;fakeLoop=AgentLoop(AppRuntime.scope,model,device,object:SessionRecorder{override fun begin(sessionId:String,goal:String)=Unit;override fun event(kind:String,value:Any?)=Unit})
            AppRuntime::class.java.getDeclaredField("loop").apply{isAccessible=true}.set(AppRuntime,fakeLoop);AppRuntime::class.java.getDeclaredField("setupRequirement").apply{isAccessible=true}.set(AppRuntime,SetupRequirement.ASSISTANT);fakeLoop!!.start("공개 native 권한 답변 시험")}
        await("In-memory question not ready"){fakeLoop!!.state.value.phase==Phase.QUESTION}
        val question=fakeLoop!!.state.value.pending!!.callId
        i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val monitor=i.addMonitor(VoiceInputActivity::class.java.name,null,false)
        try{
            app.startActivity(Intent(app,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var activity=monitor.waitForActivityWithTimeout(12000)as? VoiceInputActivity?:error("Voice question activity missing");activities+=activity
            await("Public answer editor missing"){nodes().any{it.isEditable&&it.packageName?.toString()==app.packageName}}
            var input=nodes().first{it.isEditable&&it.packageName?.toString()==app.packageName};input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"공개 답변 ABC")}))
            await("Public draft did not settle"){nodes().any{it.isEditable&&it.text?.toString()=="공개 답변 ABC"}}
            input=nodes().first{it.isEditable&&it.packageName?.toString()==app.packageName}
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply{putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,2);putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,2)}))
            Thread.sleep(200)
            ui{activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)}
            await("Native prep action hidden"){has("마이크 허용하기")};click("마이크 허용하기")
            await("In-place preparation missing"){has("마이크 준비")};click("마이크 허용하기");await("Native voice request missing"){nativeDialog()}
            ui{activity.recreate()};activity=monitor.waitForActivityWithTimeout(12000)as? VoiceInputActivity?:error("Recreated question activity missing");activities+=activity
            decidePermission(false);await("Voice preparation did not restore"){has("마이크 다시 허용하기")}
            assertEquals(question,fakeLoop!!.state.value.pending!!.callId);assertEquals(0,starts.get());click("이전")
            await("Question draft lost after denial"){has("답변을 적어주세요")&&nodes().any{it.isEditable&&it.text?.toString()=="공개 답변 ABC"}}
            input=nodes().first{it.isEditable&&it.packageName?.toString()==app.packageName}
            // Samsung/Compose may omit the selection range in accessibility. Verify the
            // saved caret by inserting a public character through the actual IME instead.
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(350);shell("input text X")
            await("Saved cursor did not receive the real IME character"){nodes().any{it.isEditable&&it.text?.toString()=="공개X 답변 ABC"}}
            capture("question-after-native-denial.png");click("전송")
            await("Public answer did not reach the same in-memory question"){requests.lastOrNull()?.userAnswers?.lastOrNull()?.answer=="공개X 답변 ABC"}
            assertEquals(0,starts.get())
        }finally{i.removeMonitor(monitor)}
    }
}
