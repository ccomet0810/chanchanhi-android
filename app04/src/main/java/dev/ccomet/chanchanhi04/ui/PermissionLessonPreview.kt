package dev.ccomet.chanchanhi04.ui

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ccomet.chanchanhi04.setup.*

/** Silent explanations only; this player never opens settings or grants a permission. */
@Composable fun PermissionLessonPreview(lesson:PermissionLesson,modifier:Modifier=Modifier,heading:@Composable ()->Unit={}){
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reduced=!ValueAnimator.areAnimatorsEnabled()||context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    var elapsed by rememberSaveable(lesson){mutableLongStateOf(lesson.startAt(lesson.initialIndex))}
    var playing by rememberSaveable(lesson){mutableStateOf(!reduced)}
    LaunchedEffect(reduced){if(reduced)playing=false}
    LaunchedEffect(playing,lifecycle,lesson){
        if(!playing||!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        var before=withFrameMillis{it}
        while(elapsed<lesson.totalMs){val now=withFrameMillis{it};elapsed=(elapsed+now-before).coerceAtMost(lesson.totalMs);before=now}
        playing=false
    }
    val index=lesson.indexAt(elapsed);val step=lesson.steps[index];val progress=lesson.progressAt(elapsed)
    SimulationPlayerLayout(modifier,heading=heading,scene={sceneModifier->
        when{
            step.pip!=null->PipTrainingPhone(step.pip,progress,sceneModifier)
            step.screen!=null->ScreenSetupPhoneScene(step.screen,progress,sceneModifier,motion=!reduced)
            else->AssistantSetupPhoneScene(step,progress,sceneModifier,motion=!reduced)
        }
    },caption=step.caption,controls={
        SimulationTransport(playing,elapsed>=lesson.totalMs,index>0,index<lesson.steps.lastIndex,
            onPrevious={elapsed=lesson.startAt(index-1)},onNext={elapsed=lesson.startAt(index+1)},
            onToggle={if(elapsed>=lesson.totalMs){elapsed=lesson.startAt(lesson.initialIndex);playing=true}else playing=!playing})
    })
}

@Composable private fun AssistantSetupPhoneScene(step:PermissionLessonStep,progress:Float,modifier:Modifier,motion:Boolean){
    BoxWithConstraints(modifier.semantics{contentDescription="버튼으로 부르기 설정 시연. ${step.caption}"},contentAlignment=Alignment.Center){
        val width=minOf(maxWidth-16.dp,maxHeight*SimulationPhoneStyle.Aspect,SimulationPhoneStyle.MaxWidth)
        SimulationPhoneShell(width){
            BoxWithConstraints(Modifier.fillMaxSize().background(SamsungPreviewStyle.Background)){
                val density=LocalDensity.current;val factor=maxWidth.value/SimulationPhoneStyle.ScreenWidth
                CompositionLocalProvider(LocalDensity provides Density(density.density*factor,1f)){
                    Column(Modifier.fillMaxSize()){
                        DemoStatusBar(currentStatusTime())
                        AssistantSettingsIllustration(step,progress,Modifier.weight(1f).fillMaxWidth(),motion)
                        DemoGalaxyNavigation()
                    }
                }
            }
        }
    }
}

/** Uses the same Galaxy-style rows and hand asset as the other setting illustrations. */
@Composable internal fun AssistantSettingsIllustration(step:PermissionLessonStep,progress:Float,modifier:Modifier=Modifier,motion:Boolean=true){
    var root by remember{mutableStateOf<LayoutCoordinates?>(null)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    var target by remember(step){mutableStateOf<Rect?>(null)}
    val focus=Modifier.onGloballyPositioned{node->target=root?.takeIf{it.isAttached}?.localBoundingBoxOf(node,false)}
    val pressed=progress in .52f.. .68f
    Box(modifier.background(SamsungPreviewStyle.Background).onGloballyPositioned{root=it;size=it.size}.clearAndSetSemantics{}){
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(18.dp)){
            SamsungHeader(when(step){
                PermissionLessonStep.ASSISTANT_SEARCH->"설정"
                PermissionLessonStep.ASSISTANT_RESULT->"검색 결과"
                PermissionLessonStep.ASSISTANT_DEFAULT->"디지털 어시스턴트 앱"
                else->"기본 디지털 어시스턴트 앱"
            },root=step==PermissionLessonStep.ASSISTANT_SEARCH)
            when(step){
                PermissionLessonStep.ASSISTANT_SEARCH,PermissionLessonStep.ASSISTANT_RESULT->{
                    Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=RoundedCornerShape(24.dp),color=Color.White){
                        Row((if(step==PermissionLessonStep.ASSISTANT_SEARCH)focus else Modifier).padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                            AppGlyph(Glyph.SEARCH,color=SamsungPreviewStyle.Ink);Spacer(Modifier.width(14.dp))
                            Text("어시스턴트",fontSize=24.sp,color=SamsungPreviewStyle.Ink)
                        }
                    }
                    if(step==PermissionLessonStep.ASSISTANT_RESULT)SamsungGroup{
                        SamsungRow("디지털 어시스턴트 앱",focus,subtitle="애플리케이션 > 기본 앱",pressed=pressed)
                    }else{
                        SamsungGroup{SamsungRow("연결",icon=Glyph.WIFI);SamsungDivider();SamsungRow("소리 및 진동",icon=Glyph.VOLUME)}
                        SamsungGroup{SamsungRow("디스플레이",icon=Glyph.SUN);SamsungDivider();SamsungRow("애플리케이션",icon=Glyph.SETTINGS)}
                    }
                }
                PermissionLessonStep.ASSISTANT_DEFAULT->SamsungGroup{
                    SamsungRow("기본 디지털 어시스턴트 앱",focus,subtitle="Google",pressed=pressed)
                    SamsungDivider();SamsungRow("화면의 텍스트 사용",toggle=true)
                    SamsungDivider();SamsungRow("스크린샷 사용",toggle=true)
                }
                else->SamsungGroup{
                    for(label in listOf("없음","Google","찬찬히")){
                        Row((if(label=="찬찬히")focus else Modifier).fillMaxWidth()
                            .background(if(label=="찬찬히"&&pressed)Color(Foundation.Selected)else Color.Transparent)
                            .padding(horizontal=16.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(label,Modifier.weight(1f),fontSize=24.sp,color=SamsungPreviewStyle.Ink)
                            val chosen=if(progress>=.72f)label=="찬찬히"else label=="Google"
                            RadioButton(chosen,{},colors=RadioButtonDefaults.colors(selectedColor=SamsungPreviewStyle.Blue))
                        }
                        if(label!="찬찬히")SamsungDivider()
                    }
                }
            }
        }
        target?.takeIf{it.width>0&&it.height>0}?.let{rect->
            Canvas(Modifier.matchParentSize()){
                drawRoundRect(Color(Foundation.Green),Offset(rect.left,rect.top),Size(rect.width,rect.height),CornerRadius(10.dp.toPx()),style=Stroke(2.dp.toPx()))
            }
            if(motion&&progress in .12f.. .8f)SetupTapHand(rect,size,((progress-.12f)/.68f).coerceIn(0f,1f),oneShot=true)
        }
    }
}
