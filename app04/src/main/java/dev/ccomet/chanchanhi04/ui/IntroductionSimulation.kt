package dev.ccomet.chanchanhi04.ui

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.*
import dev.ccomet.chanchanhi04.agent.ContactPoint
import dev.ccomet.chanchanhi04.agent.GestureCue
import dev.ccomet.chanchanhi04.agent.GestureRetargeter
import dev.ccomet.chanchanhi04.voice.VoiceState
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*
import androidx.compose.ui.graphics.Color
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

private object IntroPresentation{
    const val ScreenWidth=SimulationPhoneStyle.ScreenWidth
    const val Aspect=SimulationPhoneStyle.Aspect
    val MaxPhoneWidth=SimulationPhoneStyle.MaxWidth
    const val PhoneBackdrop=0xFFDBE3E6.toInt()
    val Description="하고 싶은 일을 말하면\n누를 곳을 알려드려요."
    const val AccessibleSummary="찬찬히 사용 시연. 옆 버튼을 길게 눌러 부르고, 말한 내용을 확인한 뒤 전송해요. 설정과 디스플레이에서 글자 크기 조절 위치를 안내하고 오른쪽으로 밀어 글자를 키워요. 실제 휴대폰 설정은 변경하지 않아요."
}

/** A single player owns the clock. Pausing freezes captions, artwork and the phone together. */
@Composable fun IntroductionPreview(showTitle:Boolean=true,modifier:Modifier=Modifier){
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context=LocalContext.current
    val accessibility=remember(context){context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)}
    var exploring by remember{mutableStateOf(accessibility.isTouchExplorationEnabled)}
    DisposableEffect(accessibility){
        val listener=android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener{exploring=it}
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose{accessibility.removeTouchExplorationStateChangeListener(listener)}
    }
    val reducedMotion=!ValueAnimator.areAnimatorsEnabled()||exploring
    var elapsed by rememberSaveable{mutableLongStateOf(0)}
    var playing by rememberSaveable{mutableStateOf(!reducedMotion)}
    LaunchedEffect(reducedMotion){if(reducedMotion)playing=false}
    LaunchedEffect(lifecycle,playing){
        if(!playing||!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        var previous=withFrameMillis{it}
        while(elapsed<IntroTimeline.durationMs){
            val now=withFrameMillis{it}
            elapsed=(elapsed+(now-previous).coerceAtLeast(0)).coerceAtMost(IntroTimeline.durationMs)
            previous=now
        }
        playing=false
    }
    val frame=IntroTimeline.at(elapsed)
    val complete=elapsed>=IntroTimeline.durationMs
    SimulationPlayerLayout(modifier,heading={
        if(showTitle)PageHeading("찬찬히 소개",IntroPresentation.Description)
        else Text(IntroPresentation.Description,style=MaterialTheme.typography.bodyLarge)
    },scene={IntroSimulation(frame,it,motionEnabled=!reducedMotion)},caption=frame.caption,controls={
        SimulationTransport(playing,complete,frame.step!=IntroStep.INVOKE,frame.step!=IntroStep.COMPLETE,
            onPrevious={elapsed=IntroTimeline.previous(elapsed)},onNext={elapsed=IntroTimeline.next(elapsed)},
            onToggle={if(complete)elapsed=0;playing=if(complete)true else !playing})
    })
}

/** A locally rendered scene. It has no speech recognizer, network, permission or Settings action. */
@Composable fun IntroSimulation(frame:IntroFrame,modifier:Modifier=Modifier,motionEnabled:Boolean=true){
    BoxWithConstraints(modifier.clipToBounds().semantics{
        contentDescription=if(motionEnabled)"찬찬히 사용 시연. ${frame.step.caption}. 실제 휴대폰 설정은 변경하지 않아요."else IntroPresentation.AccessibleSummary
    },contentAlignment=Alignment.Center){
        val width=minOf(maxWidth-16.dp,maxHeight*IntroPresentation.Aspect,IntroPresentation.MaxPhoneWidth)
        var root by remember{mutableStateOf<LayoutCoordinates?>(null)}
        var size by remember{mutableStateOf(IntSize.Zero)}
        var focus by remember(frame.step){mutableStateOf<Rect?>(null)}
        var power by remember(frame.step){mutableStateOf<Rect?>(null)}
        fun measure(c:LayoutCoordinates):Rect?=root?.takeIf{it.isAttached}?.localBoundingBoxOf(c,false)?.let{Rect(round(it.left),round(it.top),round(it.right),round(it.bottom))}
        Box(Modifier.fillMaxSize().graphicsLayer{
            val camera=IntroCamera.at(frame,focus,size.width.toFloat(),size.height.toFloat(),motionEnabled)
            scaleX=camera.scale;scaleY=camera.scale;translationX=camera.x;translationY=camera.y;transformOrigin=TransformOrigin(0f,0f)
        }){
         Box(Modifier.fillMaxSize().onGloballyPositioned{root=it;size=it.size},contentAlignment=Alignment.Center){
          SimulationPhoneShell(width,powerActive=frame.step==IntroStep.INVOKE&&frame.progress in .2f.. .85f,onPowerPositioned={power=measure(it);if(frame.step==IntroStep.INVOKE)focus=power}){
            IntroScreen(frame,Modifier.fillMaxSize(),motionEnabled){focus=measure(it)}
          }
          if(frame.step==IntroStep.INVOKE&&frame.progress<.94f)power?.let{DemoHand(frame,it,null,size)}
         }
        }
    }
}

@Composable private fun IntroScreen(frame:IntroFrame,modifier:Modifier,motionEnabled:Boolean,onFocus:(LayoutCoordinates)->Unit){
    val context=LocalContext.current;val density=LocalDensity.current
    val configuration=LocalConfiguration.current
    BoxWithConstraints(modifier.background(if(frame.page==IntroPage.HOME)Color(IntroPresentation.PhoneBackdrop)else SamsungPreviewStyle.Background)){
        val scale=maxWidth.value/IntroPresentation.ScreenWidth
        val sceneContext=remember(context,configuration,scale){context.createConfigurationContext(Configuration(configuration).apply{fontScale=1f;densityDpi=(configuration.densityDpi*scale).roundToInt()})}
        CompositionLocalProvider(LocalContext provides sceneContext,LocalDensity provides Density(density.density*scale,1f)){
            var root by remember{mutableStateOf<LayoutCoordinates?>(null)}
            var size by remember{mutableStateOf(IntSize.Zero)}
            var target by remember(frame.step){mutableStateOf<Rect?>(null)}
            var focus by remember(frame.step){mutableStateOf<Rect?>(null)}
            var thumb by remember(frame.step){mutableStateOf<ContactPoint?>(null)}
            fun measured(c:LayoutCoordinates):Rect?=root?.takeIf{it.isAttached}?.localBoundingBoxOf(c,false)?.let{Rect(round(it.left),round(it.top),round(it.right),round(it.bottom))}
            fun targetModifier()=Modifier.onGloballyPositioned{target=measured(it);if(frame.step in setOf(IntroStep.REVIEW,IntroStep.FONT_SIZE))onFocus(it)}
            fun focusModifier()=Modifier.onGloballyPositioned{focus=measured(it);if(!frame.step.navigationStep)onFocus(it)}
            val time=currentStatusTime()
            val reveal=if(!motionEnabled&&frame.revealProgress>0f)1f else frame.revealProgress
            Column(Modifier.fillMaxSize()){
              Box{DemoStatusBar(time);AssistantScrim(reveal,Modifier.matchParentSize())}
              Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))){
                Box(Modifier.fillMaxSize().onGloballyPositioned{root=it;size=it.size}){
                val stage=frame.overlayStep
                // A voice/agent overlay transition never replaces the underlying app page.
                Box(Modifier.fillMaxSize().graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}){
                key("app-page",frame.page){
                    when(frame.page){
                        IntroPage.SETTINGS,IntroPage.DISPLAY->SamsungSettingsScene(frame,::targetModifier)
                        IntroPage.FONT_SIZE->SamsungFontSizeScene(frame,targetModifier()){c->measured(c)?.let{b->thumb=ContactPoint(b.center.x,b.center.y)}}
                        IntroPage.HOME->DemoHome(time,if(stage==IntroStep.OPEN_SETTINGS)targetModifier()else Modifier,stage==IntroStep.OPEN_SETTINGS&&frame.showUserTouch)
                    }
                }
                }
                AssistantScrim(reveal,Modifier.matchParentSize())
                if(frame.voiceVisible){
                    DemoListening(frame,Modifier.align(Alignment.BottomCenter).padding(12.dp).assistantReveal(reveal),if(stage==IntroStep.REVIEW)targetModifier()else Modifier,if(stage==IntroStep.LISTENING)focusModifier()else Modifier)
                }else if(stage!=IntroStep.INVOKE){
                    val availableHeight=size.height
                    var sheetHeight by remember{mutableIntStateOf(0)}
                    key("guidance-sheet",stage){AndroidView(factory={ctx->nativeDemoGuidance(ctx,frame)},modifier=Modifier.align(Alignment.BottomCenter).padding((Foundation.SheetMarginDp-Foundation.PanelShadowInsetDp).dp).fillMaxWidth().onGloballyPositioned{sheetHeight=it.size.height}.graphicsLayer{translationY=-(availableHeight-sheetHeight).coerceAtLeast(0).toFloat()*frame.dockProgress}.then(if(stage==IntroStep.THINKING)focusModifier()else Modifier))}
                }
                if(stage in setOf(IntroStep.REVIEW,IntroStep.FONT_SIZE)||stage.navigationStep){
                    target?.let{r->
                        if(stage!=IntroStep.REVIEW)Box(Modifier.offset{IntOffset(r.left.roundToInt(),r.top.roundToInt())}.size(with(LocalDensity.current){r.width.toDp()},with(LocalDensity.current){r.height.toDp()})
                            .border(Foundation.FocusStrokeDp.dp,Color(Foundation.Green),RoundedCornerShape(Foundation.FocusRadiusDp.dp)))
                        if(frame.showGestureGuide)DemoHand(frame,r,thumb,size)
                        if(frame.showUserTouch){
                            val point=if(stage==IntroStep.FONT_SIZE)thumb?:ContactPoint(r.center.x,r.center.y)else ContactPoint(r.center.x,r.center.y)
                            Canvas(Modifier.matchParentSize()){
                                drawCircle(Color.White.copy(alpha=.8f),14.dp.toPx(),Offset(point.x,point.y))
                                drawCircle(Color(Foundation.Ink).copy(alpha=.8f),14.dp.toPx(),Offset(point.x,point.y),style=Stroke(2.dp.toPx()))
                            }
                        }
                    }
                }
                }
              }
              Box{DemoGalaxyNavigation();AssistantScrim(reveal,Modifier.matchParentSize())}
            }
            // Intercept illustrated controls while allowing the surrounding page to scroll.
            Box(Modifier.matchParentSize().pointerInput(Unit){detectTapGestures{}})
        }
    }
}

@Composable internal fun currentStatusTime():String{
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    fun read()=SimpleDateFormat(if(android.text.format.DateFormat.is24HourFormat(context))"HH:mm"else"h:mm",Locale.getDefault()).format(Date())
    var time by remember(context){mutableStateOf(read())}
    LaunchedEffect(context,lifecycle){
        if(!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        while(true){time=read();delay(60000-System.currentTimeMillis()%60000)}
    }
    return time
}

/** All dimensions belong to the same 400dp screen space as the simulated Android UI. */
@Composable internal fun DemoStatusBar(time:String){
    Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal=20.dp)){
        Text(time,Modifier.align(Alignment.CenterStart),fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
        Box(Modifier.align(Alignment.Center).size(12.dp).background(Color(Foundation.Ink),RoundedCornerShape(6.dp)))
    }
}

/** Samsung's decorative three-button system bar; it never changes the device's navigation settings. */
@Composable internal fun DemoGalaxyNavigation(){
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=68.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
        repeat(3){button->Canvas(Modifier.size(24.dp)){
            val ink=Color(Foundation.Ink);val stroke=1.8.dp.toPx()
            fun point(x:Float,y:Float)=Offset(x.dp.toPx(),y.dp.toPx())
            when(button){
                0->listOf(6f,12f,18f).forEach{x->drawLine(ink,point(x,5f),point(x,19f),stroke,StrokeCap.Round)}
                1->drawRoundRect(ink,point(5f,5f),Size(14.dp.toPx(),14.dp.toPx()),CornerRadius(3.dp.toPx()),style=Stroke(stroke))
                else->{drawLine(ink,point(15f,5f),point(8f,12f),stroke,StrokeCap.Round);drawLine(ink,point(8f,12f),point(15f,19f),stroke,StrokeCap.Round)}
            }
        }}
    }
}

@Composable private fun DemoHome(time:String,settings:Modifier,pressed:Boolean=false){
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
        Text(time,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(top=16.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(24.dp)){
            Column(settings,horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=RoundedCornerShape(20.dp),color=Color(if(pressed)Foundation.Selected else Foundation.Surface)){Box(Modifier.size(64.dp),contentAlignment=Alignment.Center){AppGlyph(Glyph.SETTINGS,Modifier.size(36.dp))}};Text("설정",style=MaterialTheme.typography.bodySmall)}
            Column(horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=RoundedCornerShape(20.dp),color=Color.White){Box(Modifier.size(64.dp),contentAlignment=Alignment.Center){AppGlyph(Glyph.PHOTOS,Modifier.size(36.dp))}};Text("갤러리",style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable private fun DemoListening(frame:IntroFrame,modifier:Modifier,send:Modifier,transcript:Modifier){
    val state=VoiceState(frame.voicePhase,frame.transcript,levels=frame.waveformLevels)
    VoiceInputPanel(state,state.text.isNotBlank(),{},{},{},modifier=modifier.fillMaxWidth(),smoothWaveform=false,waveformGain=frame.waveformGain,sendModifier=send){
        VoiceDraftEditor(TextFieldValue(state.text),{},modifier=transcript,readOnly=true)
    }
}

private fun nativeDemoGuidance(context:android.content.Context,frame:IntroFrame):View{
    val box=nativeGuidancePanel(context)
    val header=NativeSheetHeader(context,{},{})
    box.addView(nativeGuidanceMessage(context,frame.instruction))
    if(frame.overlayStep !in setOf(IntroStep.THINKING,IntroStep.COMPLETE)){
        box.addView(NativeActionButton(context,"했어요",Glyph.CHECK,ActionStyle.PRIMARY){})
        box.addView(NativeActionGroup(context,NativeActionButton(context,"다시 듣기",Glyph.REPEAT){},nativeEndGuidanceButton(context){}))
    }else box.addView(nativeEndGuidanceButton(context){})
    box.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    return nativeGuidanceWindow(context,header,box)
}

@Composable private fun BoxScope.DemoHand(frame:IntroFrame,target:Rect,thumb:ContactPoint?,size:IntSize){
    val context=LocalContext.current;val density=LocalDensity.current.density
    val slider=frame.step==IntroStep.FONT_SIZE
    val origin=if(slider)thumb?:ContactPoint(target.center.x,target.center.y)else ContactPoint(target.center.x,target.center.y)
    val bounds=com.example.aplassistmvp0.accessibility.NodeBounds(target.left.roundToInt(),target.top.roundToInt(),target.right.roundToInt(),target.bottom.roundToInt())
    fun normalized(x:Float,extent:Int)=((x/(extent-1).coerceAtLeast(1))*1000).roundToInt().coerceIn(0,1000)
    val end=ContactPoint((origin.x+(target.width-24*density)*.45f).coerceAtMost(target.right-1),origin.y)
    val path=if(slider)listOf(normalized(origin.x,size.width),normalized(origin.y,size.height),normalized(end.x,size.width),normalized(end.y,size.height))else null
    val cue=dev.ccomet.chanchanhi04.agent.GestureMapping.resolve(if(slider)dev.ccomet.chanchanhi04.agent.Action.ADJUST else if(frame.step==IntroStep.INVOKE)dev.ccomet.chanchanhi04.agent.Action.LONG_PRESS else dev.ccomet.chanchanhi04.agent.Action.TAP,bounds,size.width,size.height,path)
    val composition by produceState<LottieComposition?>(null,cue,size){
        value=if(cue==null||size.width<=0||size.height<=0)null else withContext(Dispatchers.Default){
            val source=context.assets.open("gestures/${cue.asset}.json").bufferedReader().use{it.readText()}
            LottieCompositionFactory.fromJsonStringSync(GestureRetargeter.build(source,cue,size.width,size.height,density),null).value
        }
    }
    AndroidView(factory={LottieAnimationView(it).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;repeatCount=0}},modifier=Modifier.matchParentSize(),
        onRelease={it.cancelAnimation();it.setImageDrawable(null)},update={v->
            composition?.let{if(v.composition!==it)v.setComposition(it)
                v.progress=((frame.progress*frame.step.durationMs*Foundation.GestureSpeed)%it.duration/it.duration).coerceIn(0f,1f)
            }
            v.pivotX=origin.x;v.pivotY=origin.y;v.rotation=if(frame.step==IntroStep.INVOKE)-90f else 0f
        })
}
