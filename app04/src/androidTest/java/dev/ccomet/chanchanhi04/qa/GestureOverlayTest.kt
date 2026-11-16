package dev.ccomet.chanchanhi04.qa

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureOverlayTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context=i.targetContext
    private fun ui(block:()->Unit)=i.runOnMainSync(block)
    private fun await(message:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)};error(message)}
    @Test fun allElevenRetargetedAssetsRenderDifferentNonemptyFrames(){
        val out=File(context.getExternalFilesDir(null),"gesture-checks").apply{mkdirs()}
        for(asset in GestureMapping.assets){
            val source=context.assets.open("gestures/$asset.json").bufferedReader().use{it.readText()}
            val cue=GestureCue(asset,ContactPoint(240f,220f),if(asset.startsWith("swipe")||asset in setOf("drag_drop","hold_drag"))ContactPoint(360f,420f)else ContactPoint(240f,220f))
            val parsed=LottieCompositionFactory.fromJsonStringSync(GestureRetargeter.build(source,cue,720,1280,2f),null)
            assertNull("$asset parse failed",parsed.exception);val drawable=LottieDrawable();drawable.setComposition(requireNotNull(parsed.value));drawable.setBounds(0,0,720,1280)
            val hashes=listOf(.18f,.5f,.74f).mapIndexed{index,progress->
                val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);drawable.progress=progress;drawable.draw(Canvas(bitmap))
                val pixels=IntArray(720*1280);bitmap.getPixels(pixels,0,720,0,0,720,1280);assertTrue("$asset blank",pixels.count{it ushr 24>20}>200)
                if(index==1)File(out,"$asset.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                pixels.contentHashCode().also{bitmap.recycle()}
            };assertTrue("$asset did not animate",hashes.distinct().size>1)
        }
    }
    @Test fun physicalTouchPassesThroughAndStopsOnlyTheBoundDemonstration(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPublicGestures")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Owner must unlock the phone"}
        val automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes().toString(Charsets.UTF_8).trim()}}
        ui{AppRuntime.initialize(context);AppRuntime.loop.stop()}
        val component=context.packageName+"/dev.ccomet.chanchanhi04.platform.ScreenService";val enabled=shell("settings get secure enabled_accessibility_services");check(component in enabled.split(':'))
        if(AppRuntime.service==null){try{shell("settings put secure enabled_accessibility_services "+enabled.split(':').filter{it!=component}.joinToString(":").ifEmpty{"null"});Thread.sleep(500)}finally{shell("settings put secure enabled_accessibility_services $enabled")}}
        await("Approved service must rebind"){AppRuntime.service!=null}
        val service=AppRuntime.service!!
        try{
            i.context.startActivity(Intent(i.context,PublicFixtureActivity::class.java).putExtra("dynamic",false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("Public fixture must be foreground"){automation.rootInActiveWindow?.packageName?.toString()==i.context.packageName};Thread.sleep(400)
            // Direct rendering stands in for an explicitly resumed guide. Settings suppression
            // intentionally survives leaving MainActivity and must not depend on test ordering.
            ui{service.suppressGuidance(false)}
            val screen=runBlocking{service.observe()};val button=screen.elements.first{it.label.replace("\n"," ")=="다음 단계"}
            fun show(action:Action,id:String){val call=ToolCall(id,Tool.GUIDE,"공개 손동작 검증",screen.id,button.id,action=action,instruction=if(action==Action.LONG_PRESS)"버튼을 길게 눌러주세요."else"다음 단계 버튼을 눌러주세요.",expectedChange="시험 결과 화면");ui{service.render(AgentState("public-gestures",Phase.GUIDING,"공개 손동작 검증",call.instruction!!,pending=call,screen=screen,target=button.bounds))}}
            fun motion()=service.overlayDiagnostics()["gesture"] as? Map<*,*>
            show(Action.LONG_PRESS,"hold");await("Long press Lottie must run"){motion()?.get("ready")==true&&motion()?.get("running")==true}
            Thread.sleep(800) // Capture the contact-hold segment, rather than the initial transparent frame.
            automation.takeScreenshot()?.let{b->File(context.getExternalFilesDir(null),"gesture-checks/overlay-long-press.png").apply{parentFile?.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            ui{service.hostVisible(true)};await("Hidden overlays must stop consuming animation frames"){motion()?.get("running")==false};ui{service.hostVisible(false)}
            show(Action.TAP,"tap");await("Tap Lottie must run"){motion()?.get("asset")=="tap"&&motion()?.get("running")==true}
            // A different public row activation may not dismiss this button's demonstration.
            val row=screen.elements.first{it.label=="공개 목록 1"};ui{service.render(AgentState("public-gestures",Phase.GUIDING,"공개 손동작 검증","다음 단계 버튼을 눌러주세요.",pending=ToolCall("tap",Tool.GUIDE,"공개 검증",action=Action.TAP),screen=screen,target=button.bounds))}
            fun touch(x:Float,y:Float){val time=SystemClock.uptimeMillis();for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(e,true));e.recycle();Thread.sleep(60)}}
            touch((row.bounds.left+row.bounds.right)/2f,(row.bounds.top+row.bounds.bottom)/2f);Thread.sleep(300);assertEquals(false,motion()?.get("dismissed"))
            touch((button.bounds.left+button.bounds.right)/2f,(button.bounds.top+button.bounds.bottom)/2f)
            await("Bound activation must dismiss hand animation"){motion()?.get("dismissed")==true};assertEquals(false,motion()?.get("running"))
            assertEquals("Animation must not advance agent state",Phase.STOPPED,AppRuntime.loop.state.value.phase)
            await("Real fixture must receive touch through the overlay"){runBlocking{service.observe()}.elements.any{it.label=="시험 결과 화면"}}
            val inputScreen=runBlocking{service.observe()};val input=inputScreen.elements.first{it.editable}
            ui{service.render(AgentState("public-input",Phase.GUIDING,"공개 입력 포커스 검증","내용을 입력해주세요.",pending=ToolCall("input",Tool.GUIDE,"공개 검증",action=Action.INPUT),screen=inputScreen,target=input.bounds))}
            await("Input field must initially show a tap cue"){motion()?.get("asset")=="tap"&&motion()?.get("running")==true}
            touch((input.bounds.left+input.bounds.right)/2f,(input.bounds.top+input.bounds.bottom)/2f)
            await("Editing must stop repeated tapping over the input"){motion()?.get("dismissed")==true}
            assertEquals(Phase.STOPPED,AppRuntime.loop.state.value.phase);shell("input keyevent 4")
            ui{service.render(AgentState(phase=Phase.PAUSED))};assertEquals(false,service.overlayDiagnostics()["highlightAttached"])
        }finally{
            ui{service.render(AgentState());AppRuntime.loop.stop()}
            i.context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
