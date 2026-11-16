package dev.ccomet.chanchanhi04.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ccomet.chanchanhi04.agent.Action

private object PipTrainingStyle{
    const val SceneMs=4500L
    const val WindowWidth=240f
    const val WindowHeight=WindowWidth*SetupCueStyle.PipAspectHeight/SetupCueStyle.PipAspectWidth
    const val SmallScale=.63f
    val captions=listOf("작은 창을 두 번 누르면 커져요", "두 손가락으로 크기를 바꿔요", "버튼을 가리면 끌어서 옮겨요")
}

/** Silent player: captions, Lottie and window movement share the same clock. */
@Composable fun PipGestureTraining(modifier:Modifier=Modifier,heading:@Composable ()->Unit={PageHeading("앱 권한 설정하기","작은 창 사용법과 접근성 설정하는 법을 알려드릴게요.")}){
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reduced=!ValueAnimator.areAnimatorsEnabled()||context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled
    val total=PipTrainingStyle.SceneMs*PipTrainingStyle.captions.size
    var elapsed by rememberSaveable{mutableLongStateOf(0)}
    var playing by rememberSaveable{mutableStateOf(!reduced)}
    LaunchedEffect(reduced){if(reduced)playing=false}
    LaunchedEffect(playing,lifecycle){
        if(!playing||!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        var before=withFrameMillis{it}
        while(elapsed<total){val now=withFrameMillis{it};elapsed=(elapsed+now-before).coerceAtMost(total);before=now}
        playing=false
    }
    val scene=(elapsed/PipTrainingStyle.SceneMs).toInt().coerceIn(0,2)
    val progress=if(elapsed>=total)1f else (elapsed%PipTrainingStyle.SceneMs).toFloat()/PipTrainingStyle.SceneMs
    SimulationPlayerLayout(modifier,heading=heading,
        scene={PipTrainingPhone(scene,progress,it)},caption=PipTrainingStyle.captions[scene],controls={
            SimulationTransport(playing,elapsed>=total,scene>0,scene<2,
                onPrevious={elapsed=(scene-1).coerceAtLeast(0)*PipTrainingStyle.SceneMs},
                onNext={elapsed=(scene+1).coerceAtMost(2)*PipTrainingStyle.SceneMs},
                onToggle={if(elapsed>=total){elapsed=0;playing=true}else playing=!playing})
        })
}

@Composable internal fun PipTrainingPhone(scene:Int,progress:Float,modifier:Modifier){
    BoxWithConstraints(modifier.semantics{contentDescription="작은 창 손동작 시연. ${PipTrainingStyle.captions[scene]}"},contentAlignment=Alignment.Center){
        val width=minOf(maxWidth-16.dp,maxHeight*SimulationPhoneStyle.Aspect,SimulationPhoneStyle.MaxWidth)
        SimulationPhoneShell(width){
            BoxWithConstraints(Modifier.fillMaxSize()){
                val density=LocalDensity.current
                val factor=maxWidth.value/SimulationPhoneStyle.ScreenWidth
                CompositionLocalProvider(LocalDensity provides Density(density.density*factor,1f)){
                    Column(Modifier.fillMaxSize()){
                        DemoStatusBar(currentStatusTime())
                        PipTrainingScene(scene,progress,Modifier.weight(1f).fillMaxWidth())
                        DemoGalaxyNavigation()
                    }
                }
            }
        }
    }
}

private fun ease(value:Float):Float{val t=value.coerceIn(0f,1f);return t*t*(3-2*t)}
private fun blend(a:Float,b:Float,t:Float)=a+(b-a)*ease(t)

@Composable private fun PipTrainingScene(scene:Int,progress:Float,modifier:Modifier){
    BoxWithConstraints(modifier.background(SamsungPreviewStyle.Background).clearAndSetSemantics{}){
        val width=maxWidth.value;val height=maxHeight.value
        val density=LocalDensity.current.density
        val centerX=width*.65f;val centerY=height*.5f
        val half=if(progress<.5f)progress*2 else(progress-.5f)*2
        val scale=when(scene){
            0->blend(PipTrainingStyle.SmallScale,1f,(progress-.34f)/.28f)
            1->if(progress<.5f)blend(1f,PipTrainingStyle.SmallScale,(half-.16f)/.48f)else blend(PipTrainingStyle.SmallScale,1f,(half-.16f)/.48f)
            else->1f
        }
        val p=((progress-.2f)/.7f).coerceIn(0f,1f)
        val dragX=if(scene==2)when{p<.4f->blend(.65f,.30f,p/.4f);p<.72f->.30f;else->blend(.30f,.65f,(p-.72f)/.28f)}else .65f
        val dragY=if(scene==2)when{p<.4f->blend(.5f,.28f,p/.4f);p<.72f->blend(.28f,.7f,(p-.4f)/.32f);else->blend(.7f,.5f,(p-.72f)/.28f)}else .5f
        val safeX=(width*dragX).coerceIn(PipTrainingStyle.WindowWidth/2+4,width-PipTrainingStyle.WindowWidth/2-4)
        val safeY=(height*dragY).coerceIn(PipTrainingStyle.WindowHeight/2+4,height-PipTrainingStyle.WindowHeight/2-4)
        // One moving parent holds both the window and finger, within the phone's display.
        Box(Modifier.fillMaxSize().offset((safeX-centerX).dp,(safeY-centerY).dp)){
            PipChecklistFrame(Modifier.offset((centerX-PipTrainingStyle.WindowWidth/2).dp,(centerY-PipTrainingStyle.WindowHeight/2).dp)
                .size(PipTrainingStyle.WindowWidth.dp,PipTrainingStyle.WindowHeight.dp)
                .graphicsLayer{scaleX=scale;scaleY=scale}.clip(RoundedCornerShape(16.dp))){
                PipSetupChecklist(false,Modifier.fillMaxSize())
            }
            val handProgress=when(scene){0->((progress-.08f)/.72f).coerceIn(0f,1f);1->half;else->minOf(progress/.2f,1f)*.35f}
            val opacity=if(scene==0)minOf((progress/.08f).coerceIn(0f,1f),((.86f-progress)/.1f).coerceIn(0f,1f))
                else if(scene==2)minOf((progress/.12f).coerceIn(0f,1f),((1f-progress)/.1f).coerceIn(0f,1f))else 1f
            Box(Modifier.fillMaxSize().graphicsLayer{alpha=opacity}){
                SetupTapHand(Rect((centerX-20)*density,(centerY-20)*density,(centerX+20)*density,(centerY+20)*density),
                    IntSize((width*density).toInt(),(height*density).toInt()),handProgress,
                    action=when(scene){0->Action.DOUBLE_TAP;1->if(progress<.5f)Action.ZOOM_OUT else Action.ZOOM_IN;else->Action.LONG_PRESS},
                    fixedProgress=if(scene==2)handProgress else null,oneShot=true)
            }
        }
    }
}

