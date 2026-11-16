package dev.ccomet.chanchanhi04.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class IntroCameraTest{
    @Test fun eachSceneReturnsToTheWholePhoneBeforeTransition(){
        val target=Rect(20f,80f,380f,150f)
        for(step in IntroStep.entries){
            assertEquals(IntroCamera(),IntroCamera.at(IntroFrame(step,0f),target,400f,740f))
            assertEquals(IntroCamera(),IntroCamera.at(IntroFrame(step,1f),target,400f,740f))
            val middle=IntroCamera.at(IntroFrame(step,if(step==IntroStep.FONT_SIZE).78f else .5f),target,400f,740f)
            if(step==IntroStep.INVOKE)assertTrue(middle.scale>1f)
            else assertEquals("Navigation cuts must not get their own camera animation",IntroCamera(),middle)
        }
    }
    @Test fun transcriptLineWrappingCannotMoveThePhone(){
        for(step in listOf(IntroStep.LISTENING,IntroStep.REVIEW)){
            val short=IntroCamera.at(IntroFrame(step,.4f),Rect(20f,510f,380f,580f),400f,740f)
            val wrapped=IntroCamera.at(IntroFrame(step,.6f),Rect(20f,460f,380f,580f),400f,740f)
            assertEquals(IntroCamera(),short);assertEquals(short,wrapped)
        }
    }
    @Test fun bottomFontSliderRemainsVisibleDuringDemonstrationAndUserAdjustment(){
        val target=Rect(50f,650f,350f,700f)
        assertEquals(IntroCamera(),IntroCamera.at(IntroFrame(IntroStep.FONT_SIZE,.3f),target,400f,740f))
        val camera=IntroCamera.at(IntroFrame(IntroStep.FONT_SIZE,.78f),target,400f,740f)
        assertEquals(IntroCamera(),camera)
    }
    @Test fun reducedMotionAndUnmeasuredScenesStayStill(){
        val frame=IntroFrame(IntroStep.FONT_SIZE,.5f)
        assertEquals(IntroCamera(),IntroCamera.at(frame,Rect(10f,120f,390f,180f),400f,740f,false))
        assertEquals(IntroCamera(),IntroCamera.at(frame,null,400f,740f))
    }
}
