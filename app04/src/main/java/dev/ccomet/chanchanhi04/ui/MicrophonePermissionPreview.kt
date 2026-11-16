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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal object MicrophonePreviewStyle {
    const val DurationMs=6500L
    const val AllowForeground="앱 사용 중에만 허용"
    const val Instruction="앱 사용 중에만 허용을 눌러주세요."
}

/** A silent illustration of the native prompt. Playback never requests or grants a permission. */
@Composable fun MicrophonePermissionPreview(modifier:Modifier=Modifier){
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reduced=!ValueAnimator.areAnimatorsEnabled()||context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    var elapsed by rememberSaveable{mutableLongStateOf(0)}
    var playing by rememberSaveable{mutableStateOf(!reduced)}
    LaunchedEffect(reduced){if(reduced)playing=false}
    LaunchedEffect(playing,lifecycle){
        if(!playing||!lifecycle.isAtLeast(Lifecycle.State.RESUMED))return@LaunchedEffect
        var previous=withFrameMillis{it}
        while(elapsed<MicrophonePreviewStyle.DurationMs){val now=withFrameMillis{it};elapsed=(elapsed+now-previous).coerceAtMost(MicrophonePreviewStyle.DurationMs);previous=now}
        playing=false
    }
    SimulationPlayerLayout(modifier,scene={MicrophonePermissionPhone(elapsed.toFloat()/MicrophonePreviewStyle.DurationMs,it,!reduced)},
        caption=MicrophonePreviewStyle.Instruction,controls={
            SimulationTransport(playing,elapsed>=MicrophonePreviewStyle.DurationMs,false,false,{},{},{
                if(elapsed>=MicrophonePreviewStyle.DurationMs){elapsed=0;playing=true}else playing=!playing
            })
        })
}

@Composable private fun MicrophonePermissionPhone(progress:Float,modifier:Modifier,motion:Boolean){
    BoxWithConstraints(modifier.semantics{contentDescription="마이크 권한 설정 시연. ${MicrophonePreviewStyle.Instruction}"},contentAlignment=Alignment.Center){
        val width=minOf(maxWidth-16.dp,maxHeight*SimulationPhoneStyle.Aspect,SimulationPhoneStyle.MaxWidth)
        SimulationPhoneShell(width){
            BoxWithConstraints(Modifier.fillMaxSize().background(SamsungPreviewStyle.Background)){
                val density=LocalDensity.current
                val scale=maxWidth.value/SimulationPhoneStyle.ScreenWidth
                CompositionLocalProvider(LocalDensity provides Density(density.density*scale,1f)){
                    Column(Modifier.fillMaxSize()){
                        DemoStatusBar(currentStatusTime())
                        MicrophonePermissionIllustration(progress,Modifier.weight(1f).fillMaxWidth(),motion)
                        DemoGalaxyNavigation()
                    }
                }
            }
        }
    }
}

@Composable private fun MicrophonePermissionIllustration(progress:Float,modifier:Modifier,motion:Boolean){
    var root by remember{mutableStateOf<LayoutCoordinates?>(null)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    var target by remember{mutableStateOf<Rect?>(null)}
    val pressed=progress in .5f.. .74f
    Box(modifier.onGloballyPositioned{root=it;size=it.size}){
        Column(Modifier.fillMaxSize().padding(18.dp)){
            Text("찬찬히",fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color(Foundation.Ink))
            Spacer(Modifier.height(28.dp))
            Text("마이크를 허용해주세요",fontSize=24.sp,color=Color(Foundation.Ink))
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){AppGlyph(Glyph.MIC,Modifier.size(56.dp))}
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.18f)))
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),shape=RoundedCornerShape(26.dp),color=Color(0xFFFAFBFF)){
            Column(Modifier.fillMaxWidth().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){
                AppGlyph(Glyph.MIC,Modifier.size(32.dp),color=SamsungPreviewStyle.Blue)
                Spacer(Modifier.height(16.dp))
                Text("찬찬히에서 오디오를 녹음하도록\n허용하시겠습니까?",fontSize=18.sp,lineHeight=26.sp,textAlign=TextAlign.Center,color=SamsungPreviewStyle.Ink)
                Spacer(Modifier.height(24.dp))
                listOf(MicrophonePreviewStyle.AllowForeground,"이번만 허용","허용 안함").forEachIndexed{index,label->
                    Box(Modifier.fillMaxWidth().heightIn(min=52.dp)
                        .then(if(index==0)Modifier.onGloballyPositioned{c->target=root?.takeIf{it.isAttached}?.localBoundingBoxOf(c,false)}else Modifier)
                        .background(if(index==0&&pressed)Color(Foundation.Selected)else Color.Transparent,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center){
                        Text(label,fontSize=20.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,color=SamsungPreviewStyle.Ink)
                    }
                }
            }
        }
        target?.takeIf{it.width>0&&it.height>0}?.let{r->
            Canvas(Modifier.matchParentSize()){
                drawRoundRect(Color(Foundation.Green),Offset(r.left,r.top),Size(r.width,r.height),CornerRadius(10.dp.toPx()),style=Stroke(2.dp.toPx()))
            }
            if(motion&&progress in .16f.. .78f)SetupTapHand(r,size,((progress-.16f)/.62f).coerceIn(0f,1f),oneShot=true)
        }
    }
}
