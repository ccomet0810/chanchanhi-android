package dev.ccomet.chanchanhi04.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.cos

/** Camera-only transform. Measured targets and gesture coordinates retain their original space. */
internal data class IntroCamera(val scale:Float=1f,val x:Float=0f,val y:Float=0f){
    companion object{
        fun at(frame:IntroFrame,target:Rect?,width:Float,height:Float,motionEnabled:Boolean=true):IntroCamera{
            if(!motionEnabled||target==null||width<=0||height<=0||frame.step.navigationStep)return IntroCamera()
            val peak=if(frame.step==IntroStep.INVOKE)1.25f else 1f
            if(peak==1f)return IntroCamera()
            fun ease(t:Float)=((1-cos(t.coerceIn(0f,1f)*PI))/2).toFloat()
            val p=frame.progress
            val amount=when{p<.08f->0f;p<.28f->ease((p-.08f)/.2f);p<.68f->1f;p<.94f->1-ease((p-.68f)/.26f);else->0f}
            // Preserve a complete wide label/slider instead of cropping both ends during a close-up.
            val fitted=minOf(peak,(width/target.width).coerceAtLeast(1.12f),(height/target.height).coerceAtLeast(1.12f))
            val scale=1+(fitted-1)*amount
            val center=target.center
            // Move the whole transparent phone illustration, including its bezel and system bars.
            val x=(1-scale)*center.x+amount*(width/2-center.x)
            val y=(1-scale)*center.y+amount*(height/2-center.y)
            return IntroCamera(scale,x,y)
        }
    }
}
