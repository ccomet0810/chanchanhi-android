package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.TextFieldValue
import dev.ccomet.chanchanhi04.voice.VoicePhase
import dev.ccomet.chanchanhi04.voice.VoiceState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import dev.ccomet.chanchanhi04.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public illustration QA, without touching real microphone, agent or system settings. */
@RunWith(AndroidJUnit4::class)
class IntroductionSimulationTest{
    private val i=InstrumentationRegistry.getInstrumentation();private val context=i.targetContext
    private lateinit var automation:UiAutomation
    private fun prepare(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runIntroUI")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }
    private fun nodes():List<AccessibilityNodeInfo>{
        automation.clearCache();val all=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?){n?:return;all+=n;repeat(n.childCount){visit(n.getChild(it))}}
        visit(automation.rootInActiveWindow);return all
    }
    private fun phone()=nodes().firstOrNull{it.contentDescription?.startsWith("찬찬히 사용 시연.")==true}
    private fun scene()=phone()?.contentDescription?.toString()
    private fun await(message:String,timeout:Long=10000,condition:()->Boolean){val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(message)}
    private fun click(label:String){var node=nodes().first{it.isVisibleToUser&&it.text?.toString()==label};while(!node.isClickable)node=node.parent?:error("Not clickable: $label");check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private fun tap(label:String){val bounds=android.graphics.Rect();nodes().first{it.isVisibleToUser&&it.text?.toString()==label}.getBoundsInScreen(bounds)
        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{stream->stream.readBytes()}}}
    private fun capture(name:String){Thread.sleep(550);val out=File(context.getExternalFilesDir(null),"introduction-checks").apply{mkdirs()};automation.takeScreenshot()?.let{b->File(out,name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun withHost(block:(MainActivity)->Unit){
        val scenario=androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        var host:MainActivity?=null
        scenario.onActivity{host=it}
        try{block(host!!)}finally{scenario.onActivity{it.setContent{}};scenario.close()}
    }
    private fun onHost(host:MainActivity,block:(MainActivity)->Unit)=i.runOnMainSync{block(host)}
    @Test fun scenesUseRealComponentsAndCannotRunDeviceActions(){
        prepare()
        val frame=mutableStateOf(IntroFrame(IntroStep.INVOKE,0f))
        withHost{activity->
            onHost(activity){host->host.setContent{ChanchanhiTheme{Surface{Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)){
                JourneyHeader("찬찬히")
                Text("하고 싶은 일을 말하면\n누를 곳을 알려드려요.")
                Spacer(Modifier.height(12.dp))
                IntroSimulation(frame.value,Modifier.fillMaxWidth().weight(1f))
                Text(frame.value.step.caption,Modifier.padding(vertical=12.dp))
            }}}}}
            await("Scene not rendered"){scene()!=null}
            val permission=SetupPermissions.read(context)
            val goal=AppRuntime.loop.state.value.goal;val session=AppRuntime.loop.state.value.sessionId
            val font=android.provider.Settings.System.getString(context.contentResolver,"font_scale")
            for(step in IntroStep.entries){
                onHost(activity){frame.value=IntroFrame(step,if(step==IntroStep.FONT_SIZE).6f else .65f)}
                await("Wrong scene: $step"){scene()?.contains(step.caption)==true};capture("${step.ordinal+1}-${step.name.lowercase()}.png")
                assertFalse("Illustrated controls must not become actual controls",nodes().any{it.isEditable})
                assertEquals(context.packageName,automation.rootInActiveWindow?.packageName?.toString())
            }
            onHost(activity){frame.value=IntroFrame(IntroStep.INVOKE,.92f)};capture("invocation-rising.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.LISTENING,0f)};capture("invocation-ready.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.01f)};capture("phone-before-close-up.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.5f)};capture("whole-phone-close-up.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.99f)};capture("phone-returned.png")
            assertEquals(permission,SetupPermissions.read(context));assertEquals(font,android.provider.Settings.System.getString(context.contentResolver,"font_scale"))
            assertEquals(goal,AppRuntime.loop.state.value.goal);assertEquals(session,AppRuntime.loop.state.value.sessionId)
        }
    }
    @Test fun fontSizeGuidanceDocksUpwardThroughIntermediatePositions(){
        prepare();val frame=mutableStateOf(IntroFrame(IntroStep.FONT_SIZE,0f))
        withHost{activity->
            onHost(activity){it.setContent{ChanchanhiTheme{Surface{IntroSimulation(frame.value,Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp))}}}}
            fun messageBounds():android.graphics.Rect?{var result:android.graphics.Rect?=null
                onHost(activity){host->
                    fun find(v:android.view.View){
                        if(v is android.widget.TextView&&v.text.toString()=="오른쪽으로 밀어주세요.")result=android.graphics.Rect().also(v::getGlobalVisibleRect)
                        if(v is android.view.ViewGroup)repeat(v.childCount){find(v.getChildAt(it))}
                    };find(host.window.decorView)
                };return result
            }
            await("Native guide missing"){messageBounds()!=null};Thread.sleep(400)
            val bottom=messageBounds()!!;capture("guide-dock-bottom.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.035f)}
            await("Middle dock position missing"){messageBounds()?.top?.let{it<bottom.top}==true}
            val middle=messageBounds()!!;capture("guide-dock-middle.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.1f)}
            await("Top dock position missing"){messageBounds()?.top?.let{it<middle.top}==true}
            val top=messageBounds()!!;capture("guide-dock-top.png")
            assertTrue("The guide must move upward",bottom.top>middle.top&&middle.top>top.top)
            assertTrue(bottom.top-top.top>100);assertEquals(bottom.width(),top.width())
        }
    }
    @Test fun playerPausesResumesAndReplaysFromTheSameControl(){
        prepare()
        withHost{activity->
            onHost(activity){host->host.setContent{ChanchanhiTheme{Surface{
                AppPage("찬찬히",actions={JourneyActions("다음",{})}){IntroductionPreview(modifier=Modifier.fillMaxSize())}
            }}}}
            await("Intro not visible"){scene()!=null&&nodes().any{it.text?.toString()=="일시정지"}}
            capture("intro-player-layout.png")
            click("일시정지");await("Play control missing"){nodes().any{it.text?.toString()=="재생"}}
            assertFalse(nodes().any{it.contentDescription?.toString()=="재생 위치"})
            assertFalse(nodes().any{it.text?.toString()?.matches(Regex("[0-9]+:[0-9]{2}"))==true})
            click("다음 장면");await("Next must seek to listening"){scene()?.contains(IntroStep.LISTENING.caption)==true}
            click("다음 장면");await("Next must seek to review"){scene()?.contains(IntroStep.REVIEW.caption)==true}
            click("이전 장면");await("Previous must return to listening"){scene()?.contains(IntroStep.LISTENING.caption)==true}
            Thread.sleep(6500)
            assertTrue("Paused scene must not advance across a whole scene duration",scene()?.contains(IntroStep.LISTENING.caption)==true)
            capture("intro-player-paused.png");click("재생")
            await("Settings stage missing",30000){scene()?.contains(IntroStep.SETTINGS.caption)==true};capture("intro-player-settings-caption.png")
            await("Display stage missing",10000){scene()?.contains(IntroStep.DISPLAY.caption)==true};capture("intro-player-display-caption.png")
            await("Guidance stage missing",40000){scene()?.contains(IntroStep.FONT_SIZE.caption)==true};capture("intro-player-guidance.png")
            await("Replay must replace the same player control",15000){nodes().any{it.text?.toString()=="다시 보기"}}
            assertTrue(scene()?.contains(IntroStep.COMPLETE.caption)==true);capture("intro-player-complete.png")
            tap("다시 보기");await("Replay must restart the invocation"){scene()?.contains(IntroStep.INVOKE.caption)==true}
            await("Replay must advance after restart",7000){scene()?.contains(IntroStep.LISTENING.caption)==true}
        }
    }
    @Test fun typingChangesOnlyTheSheetAndGuidancePrecedesTheResult(){
        prepare();val frame=mutableStateOf(IntroFrame(IntroStep.LISTENING,.1f))
        withHost{activity->
            onHost(activity){host->host.setContent{ChanchanhiTheme{Surface{IntroSimulation(frame.value,Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp))}}}}
            await("Phone missing"){phone()!=null};Thread.sleep(500)
            fun rect()=android.graphics.Rect().also{phone()!!.getBoundsInScreen(it)}
            val singleLine=rect();capture("typing-single-line.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.LISTENING,.8f)};Thread.sleep(700)
            assertEquals("Phone must stay fixed when transcript wraps",singleLine,rect());capture("typing-two-lines.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.3f)};Thread.sleep(600);capture("gesture-demonstration.png")
            assertEquals(0f,frame.value.userActionProgress);assertTrue(frame.value.showGestureGuide)
            onHost(activity){frame.value=IntroFrame(IntroStep.FONT_SIZE,.8f)};Thread.sleep(600);capture("user-follows-demonstration.png")
            assertFalse(frame.value.showGestureGuide);assertTrue(frame.value.userActionProgress>0)
        }
    }
    @Test fun submittedDraftClosesBeforeThinkingAndReturningToReviewRestoresIt(){
        prepare();val frame=mutableStateOf(IntroFrame(IntroStep.REVIEW,.2f))
        withHost{activity->
            onHost(activity){host->host.setContent{ChanchanhiTheme{Surface{IntroSimulation(frame.value,Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp))}}}}
            await("Phone missing"){phone()!=null}
            fun nativeText(value:String):Boolean{
                var found=false
                fun visit(v:android.view.View){
                    if(v is android.widget.TextView&&v.text.toString()==value)found=true
                    if(v is android.view.ViewGroup)repeat(v.childCount){visit(v.getChildAt(it))}
                }
                i.runOnMainSync{visit(activity.window.decorView)};return found
            }
            fun homeIconInk():Int{
                val area=android.graphics.Rect();phone()!!.getBoundsInScreen(area)
                val bitmap=checkNotNull(automation.takeScreenshot())
                var count=0
                val from=(area.top+area.height()*.12f).toInt().coerceIn(0,bitmap.height)
                // Upper home icons only; exclude the voice sheet's microphone and buttons.
                val to=(area.top+area.height()*.34f).toInt().coerceIn(from,bitmap.height)
                val shade=1f-AssistantMotion.ScrimAlpha*frame.value.revealProgress
                for(y in from until to)for(x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(bitmap.width)){
                    val c=bitmap.getPixel(x,y)
                    if(kotlin.math.abs(android.graphics.Color.red(c)-20*shade)<8*shade&&kotlin.math.abs(android.graphics.Color.green(c)-83*shade)<8*shade&&kotlin.math.abs(android.graphics.Color.blue(c)-45*shade)<8*shade)count++
                }
                bitmap.recycle();return count
            }
            capture("voice-review-before-send.png")
            val homeInk=homeIconInk();assertTrue("Home icons must be visible behind the voice sheet",homeInk>200)
            assertFalse(nativeText("화면을 살펴보고 있어요."))
            onHost(activity){frame.value=IntroFrame(IntroStep.REVIEW,.7f)};capture("voice-user-taps-send.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.REVIEW,.8f)}
            await("Send did not switch to the native thinking sheet"){nativeText("화면을 살펴보고 있어요.")}
            assertTrue(nativeText(UiCopy.EndGuidance));capture("voice-after-send.png")
            assertTrue("Submission must retain both icons when the scrim clears",kotlin.math.abs(homeInk-homeIconInk())<homeInk*.06f)
            onHost(activity){frame.value=IntroFrame(IntroStep.REVIEW,.2f)}
            await("Seeking back left the thinking sheet over the draft"){!nativeText("화면을 살펴보고 있어요.")}
            capture("voice-review-returned.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.OPEN_SETTINGS,.25f)}
            await("Settings instruction missing"){nativeText("설정을 눌러주세요.")};capture("settings-hand-before-touch.png")
            onHost(activity){frame.value=IntroFrame(IntroStep.COMPLETE,.5f)}
            await("Completion instruction missing"){nativeText("글자가 커졌어요.")}
            assertTrue("Completion must retain the same exit control as the live overlay",nativeText(UiCopy.EndGuidance))
        }
    }
    @Test fun sharedVoiceSheetRetainsTheDraftAndSupportsCorrectionBeforeSend(){
        prepare();val state=mutableStateOf(VoiceState(VoicePhase.LISTENING,""))
        val editor=mutableStateOf(TextFieldValue(state.value.text))
        var submitted:String?=null;var secondary:VoiceSecondary?=null
        val revealValue=java.util.concurrent.atomic.AtomicReference(0f)
        val revealSamples=java.util.concurrent.CopyOnWriteArrayList<Float>()
        withHost{activity->
            onHost(activity){host->host.setContent{ChanchanhiTheme{Surface{
                val reveal=rememberAssistantReveal()
                androidx.compose.runtime.SideEffect{revealValue.set(reveal);revealSamples+=reveal}
                Box(Modifier.fillMaxSize()){
                AssistantScrim(reveal,Modifier.matchParentSize())
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)){
                    VoiceInputPanel(state.value,editor.value.text.isNotBlank(),{},{submitted=editor.value.text},{secondary=it},modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().assistantReveal(reveal)){
                        VoiceDraftEditor(editor.value,{editor.value=it})
                    }
                }
                }
            }}}}
            await("Listening entrance did not settle"){revealValue.get()==1f&&nodes().any{it.text?.toString()=="듣기 중지"}}
            if(android.animation.ValueAnimator.areAnimatorsEnabled()&&!context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled){
                assertTrue("First invocation must animate upward",revealSamples.any{it<.9f})
            }
            fun bounds(label:String)=android.graphics.Rect().also{r->
                var node=nodes().first{it.text?.toString()==label}
                while(!node.isClickable)node=node.parent?:error("Button missing: $label")
                node.getBoundsInScreen(r)
            }
            var previous:android.graphics.Rect?=null;var stable=0
            await("Listening Activity transition must settle"){
                val rect=bounds("전송")
                if(rect==previous&&rect.left>=0&&rect.right<=context.resources.displayMetrics.widthPixels)stable++else stable=0
                previous=rect;stable>=3
            }
            val sendBefore=bounds("전송");val secondaryBefore=bounds("듣기 중지")
            val editorBefore=android.graphics.Rect().also{nodes().first{it.isEditable}.getBoundsInScreen(it)}
            onHost(activity){editor.value=TextFieldValue("글자가 너무 작아요");state.value=state.value.copy(text=editor.value.text)}
            await("First words not rendered"){nodes().any{it.text?.toString()=="글자가 너무 작아요"}}
            val editorSingle=android.graphics.Rect().also{nodes().first{it.isEditable}.getBoundsInScreen(it)}
            assertEquals("Empty-to-one line must not move the panel",editorBefore,editorSingle)
            onHost(activity){editor.value=TextFieldValue(IntroTimeline.request);state.value=state.value.copy(text=IntroTimeline.request,levels=List(22){.8f})}
            await("Partial transcript not rendered"){nodes().any{it.text?.toString()==IntroTimeline.request}}
            Thread.sleep(400)
            val editorAfter=android.graphics.Rect().also{nodes().first{it.isEditable}.getBoundsInScreen(it)}
            assertEquals("One-to-two lines must not move the panel",editorBefore,editorAfter)
            assertEquals(sendBefore,bounds("전송"));assertEquals(1f,revealValue.get(),0f)
            fun waveformInkHeight():Int{
                val r=android.graphics.Rect();nodes().first{it.contentDescription?.toString() in setOf("음성 크기 파형","입력한 음성 파형")}.getBoundsInScreen(r)
                val bitmap=checkNotNull(automation.takeScreenshot());var rows=0
                for(y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(bitmap.height)){
                    var ink=0
                    for(x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(bitmap.width)){
                        val c=bitmap.getPixel(x,y)
                        if(kotlin.math.abs(android.graphics.Color.red(c)-20)<8&&kotlin.math.abs(android.graphics.Color.green(c)-83)<8&&kotlin.math.abs(android.graphics.Color.blue(c)-45)<8)ink++
                    }
                    if(ink>10)rows++
                }
                bitmap.recycle();return rows
            }
            val speakingHeight=waveformInkHeight();assertTrue(speakingHeight>40)
            capture("shared-voice-speaking.png")
            click("듣기 중지");assertEquals(VoiceSecondary.STOP,secondary);assertNull(submitted)
            onHost(activity){state.value=state.value.copy(phase=VoicePhase.PROCESSING)}
            await("Processing must keep a retry control"){nodes().any{it.text?.toString()=="다시 말하기"}}
            assertEquals(sendBefore,bounds("전송"));assertEquals(secondaryBefore,bounds("다시 말하기"))
            assertTrue(nodes().any{it.contentDescription?.toString()=="입력한 음성 파형"})
            capture("shared-voice-processing.png")
            assertTrue("Speech waveform must decay to a quiet baseline",waveformInkHeight()<speakingHeight/3)
            onHost(activity){state.value=state.value.copy(phase=VoicePhase.REVIEW)}
            await("Review must offer retry without losing the request"){nodes().any{it.text?.toString()=="다시 말하기"}&&nodes().any{it.text?.toString()==editor.value.text}}
            assertFalse(nodes().any{it.text?.toString()=="듣기 중지"});assertNull(submitted)
            assertEquals(sendBefore,bounds("전송"));assertEquals(secondaryBefore,bounds("다시 말하기"))
            val field=nodes().first{it.isEditable}
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,IntroTimeline.request)
            }))
            await("Correction missing"){nodes().any{it.text?.toString()==IntroTimeline.request}}
            assertNull(submitted);capture("shared-live-voice-review.png")
            click("전송");assertEquals(IntroTimeline.request,submitted)
        }
    }
    @Test fun reducedMotionKeepsTheFullPhoneAndAccessibleSummary(){
        prepare()
        withHost{activity->
            onHost(activity){host->host.setContent{
                val density=LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density,2f)){ChanchanhiTheme{Surface{
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp).verticalScroll(rememberScrollState())){
                        Text("하고 싶은 일을 말하면 누를 곳을 알려드려요.")
                        IntroSimulation(IntroFrame(IntroStep.FONT_SIZE,.6f),Modifier.fillMaxWidth().height(500.dp),motionEnabled=false)
                    }
                }}}
            }}
            await("Large-text illustration missing"){scene()!=null}
            assertTrue(scene()!!.contains("설정과 디스플레이"))
            assertTrue(scene()!!.contains("오른쪽으로 밀어"))
            capture("reduced-motion-large-text.png")
        }
    }
}
