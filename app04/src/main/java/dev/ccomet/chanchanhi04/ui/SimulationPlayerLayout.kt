package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Shared page rhythm for introduction and permission preparation. The phone owns spare height. */
@Composable internal fun SimulationPlayerLayout(
    modifier:Modifier=Modifier,
    heading:@Composable ()->Unit={},
    scene:@Composable (Modifier)->Unit,
    caption:String,
    controls:@Composable ()->Unit,
    footer:@Composable ()->Unit={}
){
    BoxWithConstraints(modifier){
        val scrolling=LocalDensity.current.fontScale>=1.5f||!constraints.hasBoundedHeight||maxHeight<420.dp
        val sizing=when{!constraints.hasBoundedHeight->Modifier;scrolling->Modifier.verticalScroll(rememberScrollState());else->Modifier.fillMaxHeight()}
        Column(Modifier.fillMaxWidth().then(sizing),verticalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
            heading()
            scene(Modifier.fillMaxWidth().then(if(scrolling)Modifier.height(280.dp)else Modifier.weight(1f)))
            SimulationCaption(caption)
            controls()
            footer()
        }
    }
}
