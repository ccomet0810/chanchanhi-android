package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign

/** One completion treatment shared by permissions and survey completion. */
@Composable fun SetupSuccess(modifier:Modifier=Modifier,title:String="화면 안내가 켜졌어요",message:String="잘하셨어요!"){
    BoxWithConstraints(modifier,contentAlignment=Alignment.Center){
        val content=if(constraints.hasBoundedHeight)Modifier.fillMaxSize()else Modifier.fillMaxWidth().heightIn(min=Foundation.SuccessMinHeightDp.dp)
        Column(content,verticalArrangement=Arrangement.spacedBy(Foundation.SectionGapDp.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally){
            AppGlyph(Glyph.CHECK,Modifier.size(Foundation.SuccessIconDp.dp))
            Text(title,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
            Text(message,style=MaterialTheme.typography.bodyLarge,textAlign=TextAlign.Center)
        }
    }
}
