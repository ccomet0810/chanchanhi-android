package dev.ccomet.chanchanhi04.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FoundationContrastTest{
    private fun luminance(c:Int):Double=doubleArrayOf(.2126,.7152,.0722).mapIndexed{i,w->
        val v=((c ushr (16-i*8))and 255)/255.0;w*if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)
    }.sum()
    private fun contrast(a:Int,b:Int):Double{val x=luminance(a);val y=luminance(b);return(maxOf(x,y)+.05)/(minOf(x,y)+.05)}
    @Test fun `text and control colors stay legible across shared surfaces`(){
        for(surface in listOf(Foundation.Paper,Foundation.Surface,Foundation.Selected)){
            assertTrue("Main ink contrast",contrast(Foundation.Ink,surface)>=7)
            assertTrue("Secondary text contrast",contrast(Foundation.Muted,surface)>=7)
        }
        assertTrue("Primary action label contrast",contrast(-1,Foundation.Green)>=7)
        assertTrue("Disabled labels stay readable",contrast(Foundation.Muted,Foundation.Disabled)>=4.5)
        assertTrue("Visible control boundary",contrast(Foundation.Outline,Foundation.Surface)>=3)
    }
}
