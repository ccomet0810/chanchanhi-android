package dev.ccomet.chanchanhi04.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared by real speech input and the permission-free introductory simulation. */
@Composable fun VoiceWaveform(levels:List<Float>,smoothing:Boolean=true,description:String="음성 크기 파형",gain:Float=1f){
    val animated=levels.map{if(smoothing)animateFloatAsState(it,tween(AssistantMotion.LevelMs),label="voice-level").value else it}
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics{contentDescription=description}){
        if(animated.isEmpty())return@Canvas
        val spacing=size.width/animated.size;val stroke=minOf(8.dp.toPx(),spacing*.55f)
        animated.forEachIndexed{i,level->val height=6.dp.toPx()+level.coerceIn(0f,1f)*gain.coerceIn(0f,1f)*(size.height-12.dp.toPx())
            drawLine(Color(Foundation.Green),Offset(spacing*(i+.5f),size.height/2-height/2),Offset(spacing*(i+.5f),size.height/2+height/2),stroke,StrokeCap.Round)
        }
    }
}
