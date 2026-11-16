package dev.ccomet.chanchanhi04.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.*
import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.R
import dev.ccomet.chanchanhi04.agent.Action
import dev.ccomet.chanchanhi04.agent.GestureMapping
import dev.ccomet.chanchanhi04.agent.GestureRetargeter
import dev.ccomet.chanchanhi04.setup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable fun ScreenSetupPreview(direct:Boolean,modifier:Modifier=Modifier,heading:@Composable ()->Unit={},footer:@Composable ()->Unit={},pipPermission:Boolean=false){
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context=LocalContext.current
    val reduced=!ValueAnimator.areAnimatorsEnabled()||context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    val steps=remember(direct,pipPermission){if(pipPermission)listOf(ScreenSetupStep.PIP_PERMISSION)else ScreenSetupTour.steps(direct)}
    val total=steps.size*ScreenSetupTour.StepMs
    var elapsed by rememberSaveable(direct,pipPermission){mutableLongStateOf(0)}
    var playing by rememberSaveable(direct,pipPermission){mutableStateOf(!reduced)}
    LaunchedEffect(playing,lifecycle){
        if(!playing||!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        var before=withFrameMillis{it}
        while(elapsed<total){val now=withFrameMillis{it};elapsed=(elapsed+now-before).coerceAtMost(total);before=now}
        playing=false
    }
    val index=ScreenSetupTour.stepIndex(elapsed,steps.size)
    SimulationPlayerLayout(modifier,heading=heading,scene={
        ScreenSetupPhoneScene(steps[index],ScreenSetupTour.progress(elapsed,total),it,motion=!reduced)
    },caption=ScreenSetupTour.caption(steps[index]),controls={
        SimulationTransport(playing,elapsed>=total,index>0,index<steps.lastIndex,
            onPrevious={elapsed=ScreenSetupTour.move(index,-1,steps.size)*ScreenSetupTour.StepMs},
            onNext={elapsed=ScreenSetupTour.move(index,1,steps.size)*ScreenSetupTour.StepMs},
            onToggle={val ended=elapsed>=total;if(ended)elapsed=0;playing=if(ended)true else!playing})
    },footer=footer)
}

@Composable internal fun ScreenSetupPhoneScene(step:ScreenSetupStep,progress:Float,modifier:Modifier=Modifier,motion:Boolean=true){
    BoxWithConstraints(modifier.semantics{contentDescription="화면 안내 설정 시연. ${step.instruction}. 실제 설정은 아래 버튼에서 열어요."},contentAlignment=Alignment.Center){
        val width=minOf(maxWidth-16.dp,maxHeight*SimulationPhoneStyle.Aspect,SimulationPhoneStyle.MaxWidth)
        SimulationPhoneShell(width){
            BoxWithConstraints(Modifier.fillMaxSize().background(SamsungPreviewStyle.Background)){
                val density=LocalDensity.current
                val factor=maxWidth.value/SimulationPhoneStyle.ScreenWidth
                CompositionLocalProvider(LocalDensity provides Density(density.density*factor,1f)){
                    Column(Modifier.fillMaxSize()){
                        DemoStatusBar(currentStatusTime())
                        ScreenSetupIllustration(step,progress,Modifier.weight(1f).fillMaxWidth(),motion=motion,inPhone=true)
                        DemoGalaxyNavigation()
                    }
                }
            }
        }
    }
}

/** Same setting illustration in the large preview and the focused PiP cue. Controls are inert. */
@Composable fun ScreenSetupIllustration(step:ScreenSetupStep,progress:Float,modifier:Modifier=Modifier,compact:Boolean=false,motion:Boolean=true,inPhone:Boolean=false,timerProgress:Float?=null,stepLabel:String?=null){
    val serviceName=stringResource(R.string.accessibility_name)
    var root by remember{mutableStateOf<LayoutCoordinates?>(null)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    var target by remember(step,compact){mutableStateOf<Rect?>(null)}
    val targetModifier=Modifier.onGloballyPositioned{c->target=root?.takeIf{it.isAttached}?.localBoundingBoxOf(c,false)}
    val pressed=progress in .65f.. .88f
    val shape=RoundedCornerShape(if(compact||inPhone)0.dp else Foundation.CardRadiusDp.dp)
    Box(modifier.clip(shape).background(SamsungPreviewStyle.Background).then(if(compact||inPhone)Modifier else Modifier.border(1.dp,MaterialTheme.colorScheme.outlineVariant,shape))
        .onGloballyPositioned{root=it;size=it.size}.semantics{contentDescription=if(compact)"설정 안내. ${step.instruction}. 창을 누르면 이전 안내, 다시 듣기, 다음 안내 버튼이 나타나요."else"설정 방법 예시. ${step.instruction}. 실제 설정은 아래 버튼에서 열어요."
            if(compact&&timerProgress!=null)progressBarRangeInfo=ProgressBarRangeInfo(timerProgress.coerceIn(0f,1f),0f..1f)
        }){
                Column(Modifier.fillMaxSize().padding(if(compact)SetupCueStyle.PaddingDp.dp else Foundation.PagePaddingDp.dp).clearAndSetSemantics{}){
            if(compact){
                timerProgress?.let{LinearProgressIndicator(progress={it.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(SetupCueStyle.TimerHeightDp.dp));Spacer(Modifier.height(SetupCueStyle.TimerHeightDp.dp))}
                // Keep the counter inline so larger instructions can use the full next line.
                Text(buildAnnotatedString{
                    stepLabel?.let{withStyle(SpanStyle(fontSize=SetupCueStyle.MetaSp.sp,color=SamsungPreviewStyle.Muted,fontWeight=FontWeight.Normal)){append("$it ")}}
                    append(step.instruction)
                },style=MaterialTheme.typography.titleSmall,fontSize=SetupCueStyle.TitleSp.sp,lineHeight=SetupCueStyle.TitleLineSp.sp,color=SamsungPreviewStyle.Ink)
                Spacer(Modifier.weight(1f))
                PreviewTarget(step,targetModifier,pressed,progress,serviceName,true)
                Spacer(Modifier.weight(1f))
                Text(if(step==ScreenSetupStep.CONFIRM)"허용하면 완료를 알려드려요"else UiCopy.PipAfterAction,fontSize=SetupCueStyle.HintSp.sp,lineHeight=SetupCueStyle.HintLineSp.sp,fontWeight=FontWeight.Medium,color=SamsungPreviewStyle.Ink)
            }else{
                Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){AppGlyph(Glyph.BACK,color=SamsungPreviewStyle.Ink);Spacer(Modifier.width(10.dp));Text(step.title,style=MaterialTheme.typography.titleMedium,fontSize=if(inPhone)28.sp else MaterialTheme.typography.titleMedium.fontSize,color=SamsungPreviewStyle.Ink)}
                Spacer(Modifier.height(12.dp))
                if(step==ScreenSetupStep.ACCESSIBILITY)PreviewRow("시인성 향상",Modifier,inPhone=inPhone)
                if(step==ScreenSetupStep.APPS)PlaceholderRow()
                if(step==ScreenSetupStep.CONFIRM)PreviewTarget(ScreenSetupStep.SERVICE,Modifier,false,progress,serviceName,false,inPhone,checked=ScreenSetupTour.switchOn(step,progress))
                else PreviewTarget(step,targetModifier,pressed,progress,serviceName,false,inPhone)
                if(step==ScreenSetupStep.APPS)PlaceholderRow()
            }
        }
        val accepted=step==ScreenSetupStep.CONFIRM&&progress>=ScreenSetupTour.ConfirmationAccepted
        if(step==ScreenSetupStep.CONFIRM&&!compact&&!accepted){
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.18f)))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)){
                PermissionConfirmationSheet(targetModifier,inPhone=inPhone)
            }
        }
        target?.takeIf{it.width>0&&it.height>0&&!accepted}?.let{r->
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()){
                drawRoundRect(Color(Foundation.Green),Offset(r.left,r.top),androidx.compose.ui.geometry.Size(r.width,r.height),androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),style=Stroke(2.dp.toPx()))
                if(pressed)drawCircle(Color(Foundation.Green).copy(alpha=.22f),14.dp.toPx(),r.center)
            }
            if(motion&&progress<.55f)SetupTapHand(r,size,progress)
        }
        Box(Modifier.matchParentSize().pointerInput(Unit){detectTapGestures{}})
    }
}

@Composable private fun PreviewTarget(step:ScreenSetupStep,target:Modifier,pressed:Boolean,progress:Float,service:String,compact:Boolean,inPhone:Boolean=false,checked:Boolean=ScreenSetupTour.switchOn(step,progress)){
    when(step){
        ScreenSetupStep.ACCESSIBILITY->PreviewRow("설치된 앱",target,pressed,compact,inPhone)
        ScreenSetupStep.APPS->PreviewRow(if(compact)"찬찬히 화면 안내"else service,target,pressed,compact,inPhone)
        ScreenSetupStep.PIP_PERMISSION,ScreenSetupStep.SERVICE->Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White){
            Row(Modifier.padding(if(compact)10.dp else 16.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(step==ScreenSetupStep.PIP_PERMISSION)"권한 허용"else if(checked)"사용 중"else"사용 안 함",Modifier.weight(1f),fontSize=if(compact)SetupCueStyle.TargetSp.sp else if(inPhone)24.sp else 18.sp,color=SamsungPreviewStyle.Ink)
                Box(target.size(38.dp,24.dp).background(if(checked)SamsungPreviewStyle.Blue else SamsungPreviewStyle.Muted,CircleShape).padding(3.dp),contentAlignment=if(checked)Alignment.CenterEnd else Alignment.CenterStart){Box(Modifier.size(18.dp).background(Color.White,CircleShape))}
            }
        }
        ScreenSetupStep.CONFIRM->if(compact)PreviewRow("허용",target,pressed,compact=true)else PermissionConfirmationSheet(target,inPhone)
    }
}
@Composable private fun PermissionConfirmationSheet(target:Modifier,inPhone:Boolean){
    Surface(shape=RoundedCornerShape(24.dp),color=Color.White){
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
            Text("찬찬히 화면 안내",fontSize=if(inPhone)24.sp else 18.sp,color=SamsungPreviewStyle.Ink)
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                listOf(1f,.86f,.94f,.72f).forEach{fraction->Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(SamsungPreviewStyle.Divider,RoundedCornerShape(4.dp)))}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                listOf("설치 삭제","허용 안 함","허용").forEachIndexed{index,label->
                    if(index>0)Box(Modifier.width(1.dp).height(22.dp).background(SamsungPreviewStyle.Divider))
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                        Text(label,(if(index==2)target else Modifier).padding(horizontal=6.dp,vertical=12.dp),fontSize=if(inPhone)18.sp else 14.sp,color=if(index==2)SamsungPreviewStyle.Blue else SamsungPreviewStyle.Ink)
                    }
                }
            }
        }
    }
}
@Composable private fun PreviewRow(label:String,modifier:Modifier,pressed:Boolean=false,compact:Boolean=false,inPhone:Boolean=false){
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=if(pressed)Color(Foundation.Selected)else Color.White){
        Text(label,Modifier.padding(horizontal=if(compact)12.dp else 16.dp,vertical=if(compact)8.dp else 18.dp),fontSize=if(compact)SetupCueStyle.TargetSp.sp else if(inPhone)24.sp else 18.sp,lineHeight=if(compact)SetupCueStyle.TargetLineSp.sp else if(inPhone)32.sp else 26.sp,color=SamsungPreviewStyle.Ink)
    }
}
@Composable private fun PlaceholderRow(){
    Box(Modifier.fillMaxWidth().padding(vertical=10.dp,horizontal=18.dp).height(12.dp).background(SamsungPreviewStyle.Divider,RoundedCornerShape(6.dp)))
}
@Composable internal fun SetupTapHand(target:Rect,size:IntSize,progress:Float,action:Action=Action.TAP,path:List<Int>?=null,fixedProgress:Float?=null,oneShot:Boolean=false){
    val context=LocalContext.current;val density=LocalDensity.current.density
    val cue=remember(target,size,action,path){GestureMapping.resolve(action,NodeBounds(target.left.roundToInt(),target.top.roundToInt(),target.right.roundToInt(),target.bottom.roundToInt()),size.width,size.height,path)}
    val composition by produceState<LottieComposition?>(null,cue,size){value=if(cue==null)null else withContext(Dispatchers.Default){
        val source=context.assets.open("gestures/${cue.asset}.json").bufferedReader().use{it.readText()}
        LottieCompositionFactory.fromJsonStringSync(GestureRetargeter.build(source,cue,size.width,size.height,density),null).value
    }}
    AndroidView(factory={LottieAnimationView(it).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}},modifier=Modifier.fillMaxSize(),onRelease={it.cancelAnimation();it.setImageDrawable(null)},update={v->composition?.let{if(v.composition!==it)v.setComposition(it);v.progress=(fixedProgress?:if(oneShot)progress else ((progress*ScreenSetupTour.StepMs*Foundation.GestureSpeed)%it.duration/it.duration)).coerceIn(0f,1f)}})
}
