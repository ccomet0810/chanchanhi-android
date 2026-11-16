package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object SimulationPhoneStyle{
    const val ScreenWidth=400f
    const val Aspect=400f/780f
    val MaxWidth=280.dp
}

/** The same bezel, corners and side button in both onboarding players. No shadow. */
@Composable internal fun SimulationPhoneShell(width:Dp,modifier:Modifier=Modifier,powerActive:Boolean=false,onPowerPositioned:(LayoutCoordinates)->Unit={},content:@Composable BoxScope.()->Unit){
    val height=width/SimulationPhoneStyle.Aspect
    val geometry=SimulationPhoneGeometry.forWidth(width.value)
    Box(modifier.width(width+4.dp).height(height)){
        Box(Modifier.align(Alignment.CenterEnd).offset(x=(-2).dp,y=(-height*.15f)).width(3.dp).height(40.dp)
            .background(Color(if(powerActive)Foundation.Green else Foundation.Ink),RoundedCornerShape(2.dp)).onGloballyPositioned(onPowerPositioned))
        Surface(Modifier.width(width).fillMaxHeight().clearAndSetSemantics{},shape=RoundedCornerShape(geometry.outerRadius.dp),color=Color(Foundation.Ink)){
            Box(Modifier.padding(geometry.bezel.dp).clip(RoundedCornerShape(geometry.innerRadius.dp)),content=content)
        }
    }
}
