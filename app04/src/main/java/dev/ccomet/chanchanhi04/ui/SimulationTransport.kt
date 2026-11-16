package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Scene controls deliberately omit timeline, elapsed time and total duration. */
@Composable fun SimulationTransport(playing:Boolean,complete:Boolean,hasPrevious:Boolean,hasNext:Boolean,onPrevious:()->Unit,onNext:()->Unit,onToggle:()->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(4.dp)){
        SceneControl("이전 장면",Glyph.PREVIOUS_SCENE,hasPrevious,false,onPrevious,Modifier.weight(1f))
        SceneControl(if(complete)"다시 보기"else if(playing)"일시정지"else"재생",if(complete)Glyph.REPEAT else if(playing)Glyph.PAUSE else Glyph.PLAY,true,true,onToggle,Modifier.weight(1f))
        SceneControl("다음 장면",Glyph.NEXT_SCENE,hasNext,false,onNext,Modifier.weight(1f))
    }
}

@Composable private fun SceneControl(label:String,glyph:Glyph,enabled:Boolean,primary:Boolean,onClick:()->Unit,modifier:Modifier){
    val color=if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.55f)
    Column(modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(vertical=4.dp).semantics(mergeDescendants=true){},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
        Box(Modifier.size(64.dp).then(if(primary)Modifier.background(color,CircleShape)else Modifier.border(1.dp,if(enabled)MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,CircleShape)),contentAlignment=Alignment.Center){
            AppGlyph(glyph,color=if(primary)Color.White else color)
        }
        Text(label,style=MaterialTheme.typography.bodySmall,color=color,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
