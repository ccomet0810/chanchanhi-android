package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import dev.ccomet.chanchanhi04.study.StudyPhase
import dev.ccomet.chanchanhi04.ui.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit owner-device UI QA; no real profile, survey or AI request is submitted. */
@RunWith(AndroidJUnit4::class)
class UiConsistencyDeviceTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private var overlayNodes=false
    private fun prepare(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPolishUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply{serviceInfo=serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}}
        File(context.getExternalFilesDir(null),"ui-consistency-checks").mkdirs()
        ui{AppRuntime.initialize(context)}
    }
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun shell(command:String)=automation.executeShellCommand(command).use{ParcelFileDescriptor.AutoCloseInputStream(it).use{r->r.readBytes().toString(Charsets.UTF_8).trim()}}
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}}
        if(overlayNodes)automation.windows.sortedByDescending{it.layer}.mapNotNull{it.root}.filter{it.packageName?.toString() in setOf(context.packageName,i.context.packageName)}.forEach(::visit)
        else visit(automation.rootInActiveWindow)
        return all}
    private fun node(label:String)=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)}
    private fun has(label:String)=node(label)!=null
    private fun await(message:String,timeout:Long=15000,condition:()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(message)}
    private fun click(label:String){Thread.sleep(250);var n=node(label)?:error("Missing control: $label");while(!n.isClickable)n=n.parent?:error("Not clickable: $label");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun bounds(label:String)=Rect().also{(node(label)?:error("Missing bounds: $label")).getBoundsInScreen(it)}
    private fun actionBounds(label:String):Rect{
        var action=node(label)?:error("Missing action: $label")
        while(!action.isClickable)action=action.parent?:error("Missing clickable action: $label")
        return Rect().also(action::getBoundsInScreen)
    }
    private fun imeBounds():Rect?{
        // Samsung may report the IME window's temporary navigation strip rather than its full
        // keyboard surface. Measure the app's visible IME insets, including secure password input.
        var keyboard:Rect?=null
        ui{
            val host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
            if(host!=null){
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(host.window.decorView)
                if(insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true){
                    val bottom=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                    val window=host.windowManager.maximumWindowMetrics.bounds
                    if(bottom>100)keyboard=Rect(window.left,window.bottom-bottom,window.right,window.bottom)
                }
            }
        }
        return keyboard
    }
    private fun scrollTo(label:String){repeat(8){if(has(label))return;nodes().firstOrNull{it.isScrollable&&it.isVisibleToUser}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(200)};error("Not reachable: $label")}
    private fun capture(name:String){Thread.sleep(400);val out=File(context.getExternalFilesDir(null),"ui-consistency-checks").apply{mkdirs()};automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun main(){
        val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
        try{
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val main=monitor.waitForActivityWithTimeout(12000)as? MainActivity?:error("Fresh Main missing")
            await("Fresh Main has not resumed"){
                var ready=false
                ui{ready=main.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)&&main.window.decorView.hasWindowFocus()}
                ready
            }
            i.waitForIdleSync()
        }finally{i.removeMonitor(monitor)}
    }
    @Test fun settingsHasIndependentDetailsAndRetainsStudyState(){
        prepare();val before=AppRuntime.study.state.value;val draft=AppRuntime.study.draft("intro").toString()
        main();await("Gear missing"){has("설정")};click("설정");await("Settings missing"){has("화면 안내")};assertFalse(has("설정 완료"));assertFalse(has("허용됨"));assertFalse(has("설정 필요"))
        val back=bounds("뒤로가기");val headerTitle=bounds("설정");val density=context.resources.displayMetrics.density
        val window=Rect().also{automation.rootInActiveWindow!!.getBoundsInScreen(it)}
        assertEquals("Navigation button must stay within the shared page margin",Foundation.PagePaddingDp*density,(back.left-window.left).toFloat(),2*density)
        assertTrue("Navigation-to-title gap is too large",headerTitle.left-back.right<=20*density)
        capture("settings.png")
        assertTrue("Participant information must be visible before permission settings",bounds("실험 참여 정보").top<bounds("화면 안내").top)
        for((label,requirement) in listOf("화면 안내" to dev.ccomet.chanchanhi04.setup.SetupRequirement.SCREEN,"마이크" to dev.ccomet.chanchanhi04.setup.SetupRequirement.MICROPHONE,"버튼으로 부르기" to dev.ccomet.chanchanhi04.setup.SetupRequirement.ASSISTANT)){
            scrollTo(label)
            if(SetupPermissions.read(context).granted(requirement)){
                val n=nodes().first{it.text?.toString()==label}
                var parent:AccessibilityNodeInfo?=n
                var disabled=false
                repeat(5){if(parent?.isEnabled==false)disabled=true;parent=parent?.parent}
                assertTrue("Completed setting remains actionable: $label",disabled)
            }
        }
        scrollTo("앱 소개 다시 보기");click("앱 소개 다시 보기");await("Introduction review missing"){has("찬찬히 소개")};capture("introduction-review.png")
        click("뒤로가기");await("Settings did not resume"){has("화면 안내")}
        scrollTo("실험 참여 정보");click("실험 참여 정보");await("Participant settings missing"){has("현재 진행")}
        if(before.profile==null&&before.profileAnswers==null)capture("participant-settings.png")
        click("뒤로가기");await("Settings missing"){has("화면 안내")}
        scrollTo("화면 정보 처리 안내");click("화면 정보 처리 안내");await("Information page missing"){has("개인정보처리방침")};click("뒤로가기")
        assertEquals(before.participantId,AppRuntime.study.state.value.participantId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        assertEquals(draft,AppRuntime.study.draft("intro").toString())
    }

    @Test fun sharedHeaderContainsPressedButtonsAndKeepsBottomPreviousAcrossLayouts(){
        prepare()
        assumeTrue("Must not interrupt active guidance",AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE))
        data class Fixture(val title:String,val back:Boolean,val settings:Boolean,val scale:Float=1f,val rtl:Boolean=false)
        val longTitle="이름이 긴 화면에서도 읽을 수 있는 공통 헤더"
        val fixtures=listOf(Fixture("찬찬히",false,true),Fixture("설정 화면",true,false),Fixture("공통 헤더",true,true),
            Fixture(longTitle,true,true,2f),Fixture(longTitle,false,true,2f),Fixture("찬찬히",false,false),Fixture("설정 화면",true,true,1.3f,true))
        val fixture=mutableStateOf(fixtures.first())
        val body=java.util.concurrent.atomic.AtomicReference<Rect>()
        var calls=0
        val before=AppRuntime.study.state.value
        val scenario=androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java))
        try{
            scenario.onActivity{host->host.setContent{ChanchanhiTheme{
                val current=fixture.value;val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,current.scale),
                    LocalLayoutDirection provides if(current.rtl)LayoutDirection.Rtl else LayoutDirection.Ltr){
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
                        Box(Modifier.width(320.dp).fillMaxHeight()){
                            AppPage(current.title,onBack=if(current.back)({calls++})else null,onSettings=if(current.settings)({calls++})else null,
                                actions={JourneyActions("다음",{calls++},onPrevious={calls++})}){
                                Box(Modifier.fillMaxWidth().height(24.dp).onGloballyPositioned{
                                    val rect=it.boundsInWindow();val origin=IntArray(2);host.window.decorView.getLocationOnScreen(origin)
                                    body.set(Rect(rect.left.toInt()+origin[0],rect.top.toInt()+origin[1],rect.right.toInt()+origin[0],rect.bottom.toInt()+origin[1]))
                                }){Text("공개 헤더 배치 검사")}
                            }
                        }
                    }
                }
            }}}
            for((index,current)in fixtures.withIndex()){
                scenario.onActivity{fixture.value=current}
                await("Header fixture missing: $index"){body.get()!=null&&has(current.title)&&has("이전")&&has("다음")}
                // Accessibility bounds can lag a Compose frame when density/layout direction changes.
                Thread.sleep(250)
                val content=body.get();val title=bounds(current.title);val density=context.resources.displayMetrics.density
                val back=if(current.back)bounds("뒤로가기")else null
                val settings=if(current.settings)bounds("설정")else null
                for(button in listOfNotNull(back,settings)){
                    assertTrue("Header touch target shrank: $current",button.width()>=Foundation.HeaderTouchDp*density-2&&button.height()>=Foundation.HeaderTouchDp*density-2)
                    assertTrue("Button crossed page margins: $current",button.left>=content.left-2&&button.right<=content.right+2)
                    assertEquals("Header title and button centers differ",button.exactCenterY(),title.exactCenterY(),2*density)
                }
                if(back!=null){
                    assertEquals("Back edge differs from body margin",if(current.rtl)content.right.toFloat()else content.left.toFloat(),if(current.rtl)back.right.toFloat()else back.left.toFloat(),2f)
                    val gap=if(current.rtl)back.left-title.right else title.left-back.right
                    assertEquals("Back-to-title gap changed",Foundation.HeaderTitleGapDp*density,gap.toFloat(),2*density)
                }else assertEquals("Root title differs from body margin",if(current.rtl)content.right.toFloat()else content.left.toFloat(),if(current.rtl)title.right.toFloat()else title.left.toFloat(),2f)
                if(settings!=null){
                    assertEquals("Settings edge differs from body margin",if(current.rtl)content.left.toFloat()else content.right.toFloat(),if(current.rtl)settings.left.toFloat()else settings.right.toFloat(),2f)
                    assertTrue("Title overlaps settings",if(current.rtl)title.left>=settings.right+Foundation.GapDp*density-2 else title.right<=settings.left-Foundation.GapDp*density+2)
                }
                assertTrue("Long title crossed page margins",title.left>=content.left-2&&title.right<=content.right+2)
                assertTrue("Onboarding previous moved into header",bounds("이전").top>=content.bottom)
                assertEquals("Peer action heights changed",actionBounds("이전").height(),actionBounds("다음").height())
                capture("header-layout-$index.png")
                if(index==2){
                    assertHeaderPressStaysInsideMargins("뒤로가기",content,"header-back-pressed.png")
                    assertHeaderPressStaysInsideMargins("설정",content,"header-settings-pressed.png")
                }
            }
            assertEquals("Cancelled presses invoked an action",0,calls)
            assertEquals(before.runId,AppRuntime.study.state.value.runId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        }finally{scenario.onActivity{it.setContent{}};scenario.close()}
    }

    private fun assertHeaderPressStaysInsideMargins(label:String,content:Rect,name:String){
        val button=bounds(label);val before=checkNotNull(automation.takeScreenshot())
        val downTime=SystemClock.uptimeMillis()
        fun input(action:Int){val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,button.exactCenterX(),button.exactCenterY(),0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
            try{check(automation.injectInputEvent(event,true))}finally{event.recycle()}}
        try{
            input(MotionEvent.ACTION_DOWN);Thread.sleep(250)
            val pressed=checkNotNull(automation.takeScreenshot())
            try{
                var changed=0;var outside=0
                for(y in button.top.coerceAtLeast(0)until button.bottom.coerceAtMost(pressed.height))for(x in 0 until pressed.width){
                    if(before.getPixel(x,y)!=pressed.getPixel(x,y)){
                        changed++;if(x<content.left-2||x>=content.right+2)outside++
                    }
                }
                assertTrue("Pressed feedback was not visible for $label",changed>20)
                assertEquals("Pressed feedback crossed the shared margin for $label",0,outside)
                File(context.getExternalFilesDir(null),"ui-consistency-checks/$name").outputStream().use{pressed.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally{pressed.recycle()}
        }finally{input(MotionEvent.ACTION_CANCEL);before.recycle();Thread.sleep(350)}
    }
    private fun isolatedStudy(seed:StudyPhase?=null):dev.ccomet.chanchanhi04.study.StudyRepository{
        val suffix=java.util.UUID.randomUUID().toString()
        val wrapper=object:android.content.ContextWrapper(context){
            override fun getApplicationContext():android.content.Context=this
            override fun getFilesDir()=File(context.cacheDir,"qa-study-$suffix").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("qa-$suffix-$name",mode)
        }
        if(seed!=null){
            val rules=dev.ccomet.chanchanhi04.study.StudyRules(JSONObject(wrapper.assets.open("pilot-3.json").bufferedReader().use{it.readText()}))
            val run=java.util.UUID.randomUUID().toString();val rows=mutableListOf<JSONObject>()
            fun add(kind:String,data:JSONObject){rows+=JSONObject().put("seq",rows.size+1).put("at",System.currentTimeMillis()).put("kind",kind).put("data",data)}
            add("consent_given",JSONObject().put("templateVersion",rules.version).put("consentVersion",rules.template.getString("consentVersion")).put("records",true).put("captures",false).put("participantId",java.util.UUID.randomUUID().toString()).put("runId",run).put("testFixture",true))
            if(seed!=StudyPhase.PROFILE){
                add("profile_submitted",JSONObject().put("name","가상 참여자").put("age",60).put("gender",rules.template.getJSONArray("genders").getString(0)).put("occupation","시험 자료"))
                val answers=JSONObject();rules.questions("baseline").forEach{answers.put(it.getString("id"),3)};add("baseline_submitted",answers)
                val preset=rules.defaultPreset()!!;val mission=JSONObject();listOf("id","title","description","goal").forEach{mission.put(it,preset.get(it))};add("mission_assigned",mission)
                if(seed in setOf(StudyPhase.TASK,StudyPhase.COMPLETE)){
                    add("mission_knowledge_answered",JSONObject().put("missionId",preset.getString("id")).put("answer","unknown").put("method",""))
                    add("mission_pre_submitted",JSONObject().put("missionId",preset.getString("id")).put("confidence",3))
                    if(seed==StudyPhase.COMPLETE){
                        add("guidance_linked",JSONObject().put("missionId",preset.getString("id")).put("sessionId",java.util.UUID.randomUUID().toString()))
                        add("mission_result_submitted",JSONObject().put("missionId",preset.getString("id")).put("outcome",rules.mission.getJSONArray("outcomes").getString(0)))
                        val post=JSONObject();rules.questions("post").forEach{post.put(it.getString("id"),3)};add("post_submitted",post)
                    }
                }
            }
            val directory=dev.ccomet.chanchanhi04.study.StudyRepository.directory(wrapper)
            File(directory,"$run.jsonl").writeText(rows.joinToString("\n",postfix="\n"))
            wrapper.getSharedPreferences("study-1",0).edit().putString("active",run).commit()
        }
        return dev.ccomet.chanchanhi04.study.StudyRepository(wrapper,{})
    }
    @Test fun previousPagesSaveRevisionsInIsolatedJournalsWithoutRewindingProgress(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val owner=AppRuntime.study.state.value
        try{
            for(phase in listOf(StudyPhase.PROFILE,StudyPhase.MISSION_PRE,StudyPhase.TASK)){
                val repo=isolatedStudy(phase);val before=repo.state.value;var started=0
                ui{host!!.setContent{ChanchanhiTheme{dev.ccomet.chanchanhi04.study.StudyScreen({},{started++},{},{},dev.ccomet.chanchanhi04.setup.SetupSnapshot(true,false,false),{},repo)}}}
                val heading=when(phase){StudyPhase.PROFILE->"이름을 입력해주세요";StudyPhase.MISSION_PRE->"휴대폰 설정 미션";else->"미션 수행"}
                await("Page missing: $phase"){has(heading)&&has("이전")}
                assertFalse(has("참여 중단"));assertTrue(nodes().any{it.rangeInfo!=null});capture("study-${phase.name.lowercase()}-previous.png")
                click("이전");await("Previous page missing"){!has(heading)&&has("다음")}
                assertEquals(before.phase,repo.state.value.phase);assertEquals(before.runId,repo.state.value.runId)
                if(phase!=StudyPhase.PROFILE){
                    click("매우 그래요")
                }
                click("다음");await("Forward must return to the current page"){has(heading)}
                assertEquals(before.phase,repo.state.value.phase);assertEquals(0,started)
                if(phase==StudyPhase.MISSION_PRE){assertEquals(5,repo.state.value.baseline!!.getInt("recover"));assertEquals(3,repo.state.value.initialAnswers.getJSONObject("baseline").getInt("recover"));assertEquals(1,repo.state.value.answerHistory.length())}
                if(phase==StudyPhase.TASK){assertEquals(5,repo.state.value.confidence);assertEquals(3,repo.state.value.initialAnswers.getJSONObject("mission_pre").getInt("confidence"));assertEquals(1,repo.state.value.answerHistory.length())}
                if(phase==StudyPhase.TASK){click("찬찬히로 미션 시작");await("Mission must start once"){started==1}}
                ui{host!!.setContent{}}
            }
            assertEquals(owner.runId,AppRuntime.study.state.value.runId);assertEquals(owner.phase,AppRuntime.study.state.value.phase)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun completedStudyCanEditAProfileWithoutReopeningTheMissionOrChangingTheOwnerJournal(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val owner=AppRuntime.study.state.value;val repo=isolatedStudy(StudyPhase.COMPLETE);val original=repo.state.value
        try{
            ui{host!!.setContent{ChanchanhiTheme{dev.ccomet.chanchanhi04.study.StudyScreen({},{error("Must not restart completed mission")},{},{},dev.ccomet.chanchanhi04.setup.SetupSnapshot(true,true,true),{},repo,initialReview=StudyPhase.PROFILE,onReviewComplete={})}}}
            await("Editable completed profile missing"){has("이름을 입력해주세요")&&nodes().any{it.isEditable&&it.isEnabled}}
            val input=nodes().first{it.isEditable};val args=android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"공개 수정 참여자")}
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));click("다음")
            await("Completed answer was not revised"){repo.state.value.profile!!.getString("name")=="공개 수정 참여자"}
            assertEquals(StudyPhase.COMPLETE,repo.state.value.phase);assertEquals(original.sessions,repo.state.value.sessions)
            assertEquals("가상 참여자",repo.state.value.initialAnswers.getJSONObject("profile").getString("name"));assertEquals(1,repo.state.value.answerHistory.length())
            assertEquals(owner.runId,AppRuntime.study.state.value.runId);assertEquals(owner.phase,AppRuntime.study.state.value.phase)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun consentShowsOnlyChoicesAndDetailsPreserveUnsubmittedSelection(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val before=AppRuntime.study.state.value;val repo=isolatedStudy()
        try{
            ui{host!!.setContent{ChanchanhiTheme{dev.ccomet.chanchanhi04.study.StudyScreen({},{},{},{},dev.ccomet.chanchanhi04.setup.SetupSnapshot(true,false,false),{},repo)}}}
            await("Introduction missing"){has("찬찬히 소개")};assertFalse(nodes().any{it.text?.startsWith("전체 ")==true});assertFalse(nodes().any{it.rangeInfo!=null});capture("study-introduction.png")
            click("다음");await("Setup missing"){has("화면 안내가 켜졌어요")};assertTrue(has("이전"));assertFalse(has("뒤로가기"));capture("study-setup.png");click("다음");await("Consent missing"){has("수집 내용 확인")}
            assertTrue(has("[필수] 참여 정보와 이용 기록 전송에 동의해요"));assertTrue(has("[선택] 화면 이미지 기록에 동의해요"))
            assertFalse(nodes().any{it.text?.contains("이름, 나이, 성별, 직업, 설문 답변과 미션")==true})
            assertTrue(has("이전"));assertFalse(has("뒤로가기"))
            val prev=bounds("이전");val next=bounds("동의하고 시작")
            File(context.getExternalFilesDir(null),"ui-consistency-checks/consent-footer.json").writeText(JSONObject().put("previous",prev.toShortString()).put("next",next.toShortString()).toString())
            assertTrue("Previous must share the bottom action row",kotlin.math.abs(prev.centerY()-next.centerY())<60)
            capture("compact-consent.png");click("[필수] 참여 정보와 이용 기록 전송에 동의해요");click("자세히 보기")
            await("Full explanation missing"){nodes().any{it.text?.contains("이름, 나이, 성별, 직업, 설문 답변과 미션")==true}}
            capture("collection-details.png");click("뒤로가기");await("Consent did not return"){has("[필수] 참여 정보와 이용 기록 전송에 동의해요")}
            assertTrue("Reading details must retain the unsubmitted checkbox",nodes().any{it.isChecked})
            assertNull(repo.state.value.participantId);assertEquals(before.participantId,AppRuntime.study.state.value.participantId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun screenPreparationUsesOneDirectActionRatherThanASettingsList(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val repo=isolatedStudy();var requested:dev.ccomet.chanchanhi04.setup.SetupRequirement?=null
        try{
            ui{host!!.setContent{ChanchanhiTheme{dev.ccomet.chanchanhi04.study.StudyScreen({},{},{},{},dev.ccomet.chanchanhi04.setup.SetupSnapshot(false,false,false),{requested=it},repo)}}}
            await("Intro missing"){has("찬찬히 소개")};click("다음")
            await("Specific screen instructions missing"){has("앱 설정 안내")}
            assertTrue("Setup must continue the introduction's phone player",nodes().any{it.contentDescription?.startsWith("화면 안내 설정 시연.")==true})
            val information=bounds("화면 정보 처리 안내 보기")
            val phone=Rect().also{r->nodes().first{it.contentDescription?.startsWith("화면 안내 설정 시연.")==true}.getBoundsInScreen(r)}
            assertTrue("The information link belongs below the heading and above the phone",information.bottom<=phone.top)
            assertFalse(has("사용 준비"));assertFalse(nodes().any{it.rangeInfo!=null});capture("screen-permission-direct.png")
            click("설정 이어가기");assertEquals(dev.ccomet.chanchanhi04.setup.SetupRequirement.SCREEN,requested)
            // Render the accessibility tour independently of the phone's actual PiP choice.
            ui{host!!.setContent{ChanchanhiTheme{AppPage("화면 안내"){ScreenSetupPreview(false,androidx.compose.ui.Modifier.weight(1f))}}}}
            await("Tour controls missing"){has("일시정지")};click("일시정지");repeat(3){click("다음 장면")};click("재생");Thread.sleep(1000);click("일시정지")
            assertTrue(has("허용을 눌러주세요"));capture("screen-permission-allow.png")
            assertNull(repo.state.value.participantId)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun homeActionsStayAtBottomAndTextEntryStaysAboveTheKeyboard(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val writing=mutableStateOf(false);val goal=mutableStateOf("")
        try{
            ui{host!!.setContent{ChanchanhiTheme{Surface{HomePage(goal.value,{goal.value=it},writing.value,writing.value,null,false,"",null,{},
                {writing.value=false},{},{writing.value=true},{},{},{},{},{})}}}}
            await("Home missing"){has("무엇을 도와드릴까요?")};capture("home.png")
            assertFalse(has("AI 안내를 사용할 수 있어요."))
            assertTrue(bounds("글로 입력하기").top>context.resources.displayMetrics.heightPixels*.6)
            click("글로 입력하기");await("Keyboard missing"){imeBounds()!=null}
            val ime=imeBounds()!!
            await("Submit overlaps the keyboard"){bounds("안내 시작").bottom<=ime.top}
            val input=nodes().first{it.isEditable&&it.isVisibleToUser};val r=Rect().also{input.getBoundsInScreen(it)}
            assertTrue(r.bottom<=ime.top)
            File(context.getExternalFilesDir(null),"ui-consistency-checks/home-ime.json").writeText("{\"imeTop\":${ime.top},\"submitBottom\":${bounds("안내 시작").bottom},\"inputBottom\":${r.bottom}}")
        }finally{ui{host?.setContent{}};shell("input keyevent 4");main()}
    }
    @Test fun administratorGateUsesHeaderBackAndKeepsItsActionAboveIme(){
        prepare();main();await("Gear missing"){has("설정")};click("설정");scrollTo("관리자 모드");click("관리자 모드")
        await("Admin gate missing"){has("기록과 연결을 관리해요.")}
        assertFalse(has("사용자 화면으로"));assertFalse(has("비밀번호를 입력해주세요."));assertTrue(has("뒤로가기"))
        Thread.sleep(650)
        val field=nodes().first{it.isEditable};val r=Rect().also{field.getBoundsInScreen(it)}
        assertTrue(r.centerY()>context.resources.displayMetrics.heightPixels*.3)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(250)
        shell("input tap ${r.centerX()} ${r.centerY()}")
        await("Admin IME missing"){imeBounds()!=null}
        val ime=imeBounds()!!
        await("Admin action overlaps keyboard"){bounds("관리자 모드 열기").bottom<=ime.top}
        // No password, keyboard content, or administrator screenshot is collected.
        click("뒤로가기");await("Admin Back did not return to Settings"){has("화면 안내")}
    }
    @Test fun missionDescriptionKeepsBottomPreviousWithoutAnAutomaticDemonstration(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val before=AppRuntime.study.state.value
        try{
            ui{host!!.setContent{ChanchanhiTheme{AppPage("찬찬히",actions={JourneyActions("다음",{},onPrevious={})}){
                PageHeading("휴대폰 설정 미션","설정 앱에서 찬찬히를 디지털 어시스턴트 앱으로 선택해주세요.")
            }}}}
            await("Mission scenario missing"){has("휴대폰 설정 미션")};capture("setup-mission.png")
            assertTrue(has("이전"));assertFalse(has("뒤로가기"));assertFalse(has("미션 시작 전"))
            assertFalse(nodes().any{it.contentDescription?.contains("애니메이션")==true})
            assertEquals(before.participantId,AppRuntime.study.state.value.participantId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun permissionNavigationFollowsItsEntryContext(){
        prepare();main();var host:MainActivity?=null
        await("Main missing"){ui{host=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()};host!=null}
        val before=AppRuntime.study.state.value
        try{
            fun show(layout:PageLayout){ui{host!!.setContent{ChanchanhiTheme{Surface{MicrophonePreparationPage(
                dev.ccomet.chanchanhi04.setup.MicrophonePermissionStatus.RETRY,{},{},{},{},layout=layout)
            }}}}}
            show(PageLayout.ONBOARDING)
            await("Onboarding permission missing"){has("마이크를 허용해주세요")&&has("이전")}
            Thread.sleep(600)
            assertFalse("Leaving the screen lesson must cancel a late PiP launch",dev.ccomet.chanchanhi04.ScreenSetupHelp.active.value)
            assertTrue(has("찬찬히"));assertFalse(has("뒤로가기"));assertFalse(has("마이크"))
            capture("microphone-onboarding-shell.png")
            show(PageLayout.SETTINGS)
            await("Settings permission missing"){has("마이크")&&has("뒤로가기")}
            assertFalse(has("이전"));assertFalse(has("찬찬히"))
            capture("microphone-settings-shell.png")
            ui{host!!.setContent{ChanchanhiTheme{Surface{OnboardingPage(actions={JourneyActions("설정 시작하기",{},onPrevious={})}){
                PipGestureTraining(androidx.compose.ui.Modifier.weight(1f))
            }}}}}
            await("PiP gesture lesson missing"){has("앱 권한 설정하기")&&has("일시정지")}
            Thread.sleep(1500);click("일시정지");capture("pip-double-tap.png")
            click("다음 장면");click("재생");Thread.sleep(1200);click("일시정지");capture("pip-pinch.png")
            click("다음 장면");click("재생");Thread.sleep(2000);click("일시정지");capture("pip-drag.png")
            click("재생");await("PiP replay control missing"){has("다시 보기")};click("다시 보기")
            await("Replay must return to playing"){has("일시정지")&&has("작은 창을 두 번 누르면 커져요")}
            ui{host!!.setContent{ChanchanhiTheme{Surface{Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                PipChecklistFrame(Modifier.size(240.dp,360.dp)){PipSetupChecklist(false,Modifier.fillMaxSize())}
            }}}}}
            await("Samsung target illustrations missing"){nodes().any{it.contentDescription?.toString()?.let{text->text.contains("4. 팝업 아래 ‘허용’")&&text.contains(context.getString(dev.ccomet.chanchanhi04.R.string.accessibility_name))}==true}}
            capture("samsung-targets-large.png")
            ui{host!!.setContent{ChanchanhiTheme{Surface{Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                PipChecklistFrame(Modifier.size(132.dp,198.dp)){PipSetupChecklist(false,Modifier.fillMaxSize())}
            }}}}}
            Thread.sleep(350);capture("samsung-targets-small.png")
            ui{host!!.setContent{ChanchanhiTheme{Surface{
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,1.5f)){
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                        PipChecklistFrame(Modifier.size(132.dp,198.dp)){PipSetupChecklist(false,Modifier.fillMaxSize())}
                    }
                }
            }}}}
            Thread.sleep(350);capture("samsung-targets-large-font.png")
            assertEquals(before.runId,AppRuntime.study.state.value.runId);assertEquals(before.phase,AppRuntime.study.state.value.phase)
        }finally{ui{host?.setContent{}};main()}
    }
    @Test fun actualPermissionWindowsExposeOrExplicitlyRefuseScreenData(){
        prepare();main()
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val enabled=shell("settings get secure enabled_accessibility_services");check(component in enabled.split(':'))
        if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+enabled.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(3000)}finally{shell("settings put secure enabled_accessibility_services $enabled")}}
        await("Screen service must be authorized and bound"){AppRuntime.service!=null}
        val service=AppRuntime.service!!
        try{
            await("Main did not resume before settings"){automation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            Thread.sleep(650)
            dev.ccomet.chanchanhi04.setup.SetupPermissions.open(context,dev.ccomet.chanchanhi04.setup.SetupRequirement.MICROPHONE)
            try{await("Own app settings missing"){has("권한")}}catch(e:Exception){capture("permission-destination-failed.png");throw e}
            ui{service.suppressGuidance(false);service.render(AgentState("public-permission-read",Phase.THINKING,"공개 화면 읽기","화면을 확인하고 있어요."))}
            fun observe()=runBlocking{kotlinx.coroutines.withTimeout(8000){service.observe()}}
            val details=observe();assertTrue(details.elements.isNotEmpty());assertTrue(details.packageName!=context.packageName)
            click("권한");await("App permission page missing"){has("마이크")}
            val permissions=observe();assertTrue(permissions.elements.isNotEmpty());assertTrue(permissions.elements.any{it.label.contains("마이크")})
            click("마이크");Thread.sleep(800);capture("permission-microphone-read.png")
            File(context.getExternalFilesDir(null),"ui-consistency-checks/permission-microphone-labels.json").writeText(org.json.JSONArray(nodes().mapNotNull{it.text?.toString()}).toString())
            await("Microphone page missing"){nodes().any{it.text?.toString()?.replace(" ","")?.let{v->v.contains("허용안함")||v.contains("허용하지않음")||v.contains("앱사용중에만허용")}==true}}
            val protectedNodes=if(android.os.Build.VERSION.SDK_INT>=34)nodes().count{it.isAccessibilityDataSensitive}else 0
            val microphone=runCatching{observe()}
            if(microphone.isSuccess){assertTrue(microphone.getOrThrow().elements.any{it.label.contains("허용")})}
            else{assertTrue("The current permission page must be accessible to the test before identifying a protected boundary",has("마이크 액세스 권한"));assertTrue("Unexpected observation error",microphone.exceptionOrNull()?.message?.contains("이 화면의 정보")==true)}
            File(context.getExternalFilesDir(null),"ui-consistency-checks/permission-read.json").writeText(JSONObject().put("detailsNodes",details.elements.size).put("permissionsNodes",permissions.elements.size).put("microphoneNodes",microphone.getOrNull()?.elements?.size).put("protectedNodes",protectedNodes).put("unavailableHandled",microphone.isFailure).put("permissionsChanged",false).toString())
        }finally{ui{service.render(AgentState());service.suppressGuidance(false)};main()}
    }
    @Test fun nativeGuideHasBoxedEndActionAndNoExtraTopInset(){
        prepare();assumeTrue(AppRuntime.loop.state.value.phase in setOf(Phase.IDLE,Phase.STOPPED))
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
        val enabled=shell("settings get secure enabled_accessibility_services");check(component in enabled.split(':'))
        if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+enabled.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(2000)}finally{shell("settings put secure enabled_accessibility_services $enabled")}}
        await("Authorized screen service did not bind"){AppRuntime.service!=null};val service=AppRuntime.service!!
        i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Public fixture missing"){automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName}
        try{
            overlayNodes=true
            ui{service.suppressGuidance(false);service.render(AgentState("public-style",Phase.THINKING,"공개 UI 검증","화면을 확인하고 있어요."))}
            await("Guide missing"){has(UiCopy.EndGuidance)};assertFalse(has("끝내기"));assertFalse(has("잠시 멈춤"));assertTrue(bounds(UiCopy.EndGuidance).top>bounds("화면을 확인하고 있어요.").bottom);capture("guidance-before-snap.png")
            click("안내창 위아래로 이동")
            val top=service.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            await("Top snap retained extra space"){kotlin.math.abs((service.overlayDiagnostics()["top"]as?Int?:Int.MAX_VALUE)-top)<=3}
            File(context.getExternalFilesDir(null),"ui-consistency-checks/guide-top-metrics.json").writeText(JSONObject(service.overlayDiagnostics()).put("expectedTop",top).toString())
            capture("guidance-top.png");click("안내창 위아래로 이동");Thread.sleep(250);capture("guidance-bottom.png")
        }finally{overlayNodes=false;ui{service.render(AgentState());service.suppressGuidance(false)};main()}
    }
}
