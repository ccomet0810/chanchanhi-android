package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.research.ResearchConnection
import dev.ccomet.chanchanhi04.study.StudyPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in public-data fixture. Never fills a real participant's name, contacts or messages. */
@RunWith(AndroidJUnit4::class)
class StudyFlowTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun ui(action:()->Unit)=i.runOnMainSync(action)
    private fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8)}}
    private fun nodes():List<AccessibilityNodeInfo>{automation.clearCache();val result=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>30)return;result+=n;for(c in 0 until n.childCount)visit(n.getChild(c),depth+1)}
        val allowed=setOf(context.packageName,i.context.packageName)
        val roots=automation.windows.sortedByDescending{it.layer}.mapNotNull{it.root}.filter{it.packageName?.toString() in allowed}
        roots.forEach{visit(it,0)};return result}
    private fun text(n:AccessibilityNodeInfo)=n.text?.toString().orEmpty()+" "+n.contentDescription?.toString().orEmpty()
    private fun await(detail:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+18000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(detail+"; visible="+nodes().map{it.text?.toString()}.filterNotNull().joinToString(" | ").take(1000))}
    private fun visible(value:String)=nodes().any{it.isVisibleToUser&&text(it).trim()==value}
    private fun click(value:String){
        val deadline=SystemClock.uptimeMillis()+18000
        while(SystemClock.uptimeMillis()<deadline){
            i.waitForIdleSync()
            val found=nodes().firstOrNull{it.isVisibleToUser&&(it.text?.toString()==value||it.contentDescription?.toString()==value)}
            if(found!=null){var node:AccessibilityNodeInfo?=found;repeat(5){val current=node;if(current!=null&&current.isClickable&&current.isEnabled){check(current.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(150);return};node=current?.parent}}
            // A visible disabled action is saving, not an offscreen action to scroll toward.
            if(found==null)nodes().firstOrNull{it.isScrollable&&it.isVisibleToUser}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        };error("UI control missing: $value")
    }
    private fun enter(value:String){await("Editable profile field missing"){nodes().any{it.isEditable&&it.isVisibleToUser}};val node=nodes().first{it.isEditable&&it.isVisibleToUser};node.performAction(AccessibilityNodeInfo.ACTION_CLICK);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));Thread.sleep(150)}
    private fun stage(value:String)=i.sendStatus(0,Bundle().apply{putString("stream","\nSTUDY_UI=$value\n")})

    @Test fun mandatoryPublicFlowResumeBlankMissionAndServerLink(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPublicStudy")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        ui{AppRuntime.initialize(context)}
        val repo=AppRuntime.study;val connection=ResearchConnection(context)
        val previousGoal=AppRuntime.preferences.getString("last_goal",null)
        check(connection.enabled){"Administrator enrollment and upload must already be configured"}
        check(repo.state.value.phase==StudyPhase.INTRO){"Never overwrite an existing participant's study"}
        repo.nextParticipant() // Fresh anonymous onboarding for this explicitly requested public fixture.
        repo.testRun=true
        val hardwareIme=shell("settings get secure show_ime_with_hard_keyboard").trim()
        var run="";var participant=""
        try{
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService"
            val original=shell("settings get secure enabled_accessibility_services").trim();check(component in original.split(':'))
            if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+original.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shell("settings put secure enabled_accessibility_services $original")}}
            await("Screen service did not rebind"){AppRuntime.service!=null}
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Onboarding missing"){visible("휴대폰 사용 안내, 찬찬히")}
            fun capture(name:String){i.waitForIdleSync();Thread.sleep(900);val out=java.io.File(context.getExternalFilesDir(null),"onboarding-checks").apply{mkdirs()};automation.takeScreenshot()?.let{b->java.io.File(out,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
            capture("introduction.png")
            assertFalse(AppRuntime.voiceAllowed());val count=AppRuntime.log.rows.value.size
            ui{AppRuntime.start("설문을 건너뛰려는 공개 시험 요청")};Thread.sleep(200);assertEquals(count,AppRuntime.log.rows.value.size)
            click("다음");await("Setup page missing"){visible("사용 준비")};capture("setup.png")
            check(dev.ccomet.chanchanhi04.setup.SetupPermissions.read(context).complete){"Owner must complete the three real capabilities; the fixture must not bypass setup"}
            click("다음")
            await("Consent missing"){visible("수집 내용 확인")}
            assertEquals(StudyPhase.INTRO,repo.state.value.phase)
            click("전송에 동의해요");click("동의하고 시작")
            await("Name form missing"){visible("이름을 입력해주세요")};run=repo.state.value.runId!!;participant=repo.state.value.participantId!!;stage("profile")
            enter("공개 시험 참여자")
            await("Soft keyboard missing"){automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            val ime=android.graphics.Rect().also{r->automation.windows.first{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}.getBoundsInScreen(r)}
            val nextBounds=android.graphics.Rect().also{r->nodes().first{it.text?.toString()=="다음"&&it.isVisibleToUser}.getBoundsInScreen(r)}
            assertTrue("Next action must remain above the keyboard",nextBounds.bottom<=ime.top)
            click("다음");await("Age form missing"){visible("나이를 입력해주세요")}
            // Recreate the actual app screen: confirmed name and current page must survive.
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Profile page lost on reopen"){visible("나이를 입력해주세요")};assertEquals("공개 시험 참여자",repo.state.value.profileAnswers?.getString("name"))
            enter("70");click("다음");await("Gender form missing"){visible("성별을 선택해주세요")};click("응답하지 않음");click("다음")
            await("Occupation form missing"){visible("직업을 선택해주세요")};click("은퇴");click("다음")
            await("Baseline missing"){repo.state.value.phase==StudyPhase.BASELINE};stage("baseline")
            for(q in repo.rules.questions("baseline")){
                await("Baseline question missing"){visible(q.getString("text"))}
                click(repo.rules.options(q)[1]);click("다음")
            }
            await("Scenario mission missing"){repo.state.value.mission?.optString("id")==repo.rules.defaultPreset()?.getString("id")};stage("scenario_assigned")
            await("Scenario title missing"){visible(repo.rules.defaultPreset()!!.getString("title"))};capture("mission-scenario.png");click("다음")
            await("Prior knowledge question missing"){visible(repo.rules.defaultPreset()!!.getJSONObject("knowledge").getString("question"))};capture("mission-knowledge.png")
            click("어떻게 하는지 모르겠어요");click("다음")
            await("Confidence page missing"){visible(repo.rules.mission.getString("confidenceQuestion"))};assertEquals("unknown",repo.state.value.missionKnowledge)
            click(repo.rules.strings(repo.rules.template.getJSONArray("likert"))[1]);click("찬찬히와 시작하기")
            await("Mission not unlocked"){repo.state.value.phase==StudyPhase.TASK}
            ui{AppRuntime.installTestModel(object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{
                val id=UUID.randomUUID().toString();val result=request.screen.elements.find{it.label=="시험 결과 화면"}
                if(result!=null)return ToolCall(id,Tool.FINISH,"공개 시험 결과를 확인했어요.",request.screen.id,instruction="미션을 마쳤어요.",evidenceNodeIds=listOf(result.id))
                val next=request.screen.elements.find{it.label.replace("\n"," ")=="다음 단계"}
                return if(next==null)ToolCall(id,Tool.OBSERVE,"공개 시험 화면을 다시 확인합니다.")else ToolCall(id,Tool.GUIDE,"공개 시험 버튼을 확인했어요.",request.screen.id,next.id,action=Action.TAP,instruction="다음 단계 버튼을 눌러주세요.",expectedChange="시험 결과 화면 표시")
            }})}
            // Keep the public Activity directly behind the study screen, never a private app.
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(250)
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("Mission launch missing"){visible("찬찬히로 미션 시작")};click("찬찬히로 미션 시작")
            await("Public guide missing"){AppRuntime.loop.state.value.phase==Phase.GUIDING};stage("guidance")
            click("다음\n단계")
            await("AI evidence completion missing"){AppRuntime.loop.state.value.phase==Phase.COMPLETE}
            await("Completion overlay missing: "+AppRuntime.service?.overlayDiagnostics()){visible("설문으로 돌아가기")}
            click("설문으로 돌아가기");await("Return to study missing"){visible("미션 결과를 알려주세요")||visible("미션 마치기")}
            if(!visible("미션 결과를 알려주세요"))click("미션 마치기")
            click("마쳤어요");click("사용 후 설문으로")
            await("Post survey missing"){repo.state.value.phase==StudyPhase.POST};assertFalse(AppRuntime.voiceAllowed());stage("post")
            val questions=repo.rules.questions("post")
            questions.forEachIndexed{index,q->await("Post question missing"){visible(q.getString("text"))};click(repo.rules.options(q)[3]);click(if(index==questions.lastIndex)"설문 마치기"else"다음")}
            await("Completion missing"){visible("설문을 마쳤어요")};assertEquals(StudyPhase.COMPLETE,repo.state.value.phase);assertEquals(participant,repo.state.value.participantId);assertTrue(AppRuntime.voiceAllowed())
            click("찬찬히 사용하기");await("Normal home must remain gated until the full study completes"){visible("무엇을 도와드릴까요?")}
            ui{AppRuntime.loop.stop();AppRuntime.installTestModel(AppRuntime.model)}
            click("설정");await("User settings missing"){visible("화면 안내")}
            assertFalse(visible("음성으로 안내"));assertFalse(visible("화면 이미지 사용"));assertFalse(visible("개인정보처리방침과 삭제 문의"));assertFalse(visible("수집 내용 자세히 보기"))
            capture("user-settings.png");click("관리자 모드")
            await("Password gate missing"){visible("기록과 연결을 관리해요.")};assertFalse(visible("PIN 6자리"));assertFalse(visible("관리자 PIN을 입력해주세요."))
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Home did not return"){visible("무엇을 도와드릴까요?")}
            if(InstrumentationRegistry.getArguments().getString("captureUiPolish")=="true"){
                val originalScale=shell("settings get system font_scale").trim()
                val output=java.io.File(context.getExternalFilesDir(null),"ui-polish-checks").apply{mkdirs()}
                try{
                    for(scale in listOf("1.0","2.0")){
                        shell("settings put system font_scale $scale");Thread.sleep(350)
                        i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        await("Home missing at font $scale"){visible("무엇을 도와드릴까요?")}
                        i.waitForIdleSync();Thread.sleep(450) // Wait for the system Activity transition before taking evidence.
                        automation.takeScreenshot()?.let{b->java.io.File(output,"home-font-$scale.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
                    }
                }finally{if(originalScale=="null")shell("settings delete system font_scale")else shell("settings put system font_scale $originalScale")}
            }
            AppRuntime.log.awaitWrites();assertTrue(connection.upload());assertTrue(context.getSharedPreferences("research-1",0).getLong("study-ack-$run",0)>0)
            stage("complete_and_acknowledged");i.sendStatus(0,Bundle().apply{putString("stream","\nPUBLIC_STUDY_RUN=$run\nPUBLIC_PARTICIPANT=$participant\n")})
        }finally{
            ui{AppRuntime.loop.stop();AppRuntime.installTestModel(AppRuntime.model)}
            if(repo.state.value.testFixture){repo.nextParticipant();repo.testRun=false}
            AppRuntime.preferences.edit().apply{if(previousGoal==null)remove("last_goal")else putString("last_goal",previousGoal)}.commit()
            if(hardwareIme in listOf("0","1"))shell("settings put secure show_ime_with_hard_keyboard $hardwareIme")
            AppRuntime.log.awaitWrites();connection.upload()
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
