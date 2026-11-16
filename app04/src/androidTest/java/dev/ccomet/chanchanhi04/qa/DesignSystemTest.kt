package dev.ccomet.chanchanhi04.qa

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ccomet.chanchanhi04.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignSystemTest{
    private val i=InstrumentationRegistry.getInstrumentation()
    @Test fun constrainedGuidanceWindowKeepsItsEntireScrollViewportInsideTheBorder(){
        val source=i.targetContext
        for(scale in listOf(1f,2f))i.runOnMainSync{
            val context=source.createConfigurationContext(Configuration(source.resources.configuration).apply{fontScale=scale})
            val density=context.resources.displayMetrics.density
            val box=nativeGuidancePanel(context)
            val header=NativeSheetHeader(context,{},{})
            box.addView(nativeGuidanceMessage(context,"‘디지털 어시스턴트 앱’ 아래에 Google이 보이는 항목을 눌렀을 때 어떤 화면이 나타나나요?"))
            for(label in listOf("앱 선택 목록이 열림","화면이 바뀌지 않음","다른 화면이 열림"))box.addView(NativeActionButton(context,label,Glyph.NEXT){})
            val end=nativeEndGuidanceButton(context){}
            box.addView(end)
            // Simulate a large safe area followed by a much shorter WindowManager
            // allocation after target avoidance, keyboard appearance or rotation.
            val window=nativeGuidanceWindow(context,header,box){(700*density).toInt()}
            val width=(360*density).toInt()
            for(heightDp in listOf(420,240,160))for(mode in listOf(View.MeasureSpec.EXACTLY,View.MeasureSpec.AT_MOST)){
                val height=(heightDp*density).toInt()
                window.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,mode))
                window.layout(0,0,width,window.measuredHeight)
                val scroll=window.scrollViewport
                val viewport=window.viewportBounds();val fixed=window.headerBounds()
                assertTrue("Viewport escapes parent at $scale/$heightDp/$mode",viewport.bottom<=window.height-window.paddingBottom)
                assertTrue("Body must start below the fixed header",viewport.top>=fixed.bottom)
                assertTrue(window.height<=height)
                assertTrue("Long content must remain scrollable",scroll.canScrollVertically(1))
                scroll.isSmoothScrollingEnabled=false
                scroll.fullScroll(View.FOCUS_DOWN)
                assertEquals("Scrolling must not move the header",fixed,window.headerBounds())
                assertEquals("Header must be outside the scroll hierarchy",window.header.parent,scroll.parent)
                assertTrue("Final action must be reachable inside the viewport",end.bottom+box.top-scroll.scrollY<=scroll.height-scroll.paddingBottom)
                if(end.height<=scroll.height-scroll.paddingTop-scroll.paddingBottom)assertTrue(end.top+box.top-scroll.scrollY>=scroll.paddingTop)
                else assertTrue("Oversized final action must intersect the viewport",end.bottom+box.top-scroll.scrollY>scroll.paddingTop)
                scroll.scrollTo(0,0)
            }
        }
    }
    @Test fun officialSymbolsRenderAndAdaptiveActionsKeepTheirSpacing(){
        val source=i.targetContext;val density=source.resources.displayMetrics.density
        val out=File(source.getExternalFilesDir(null),"ui-polish-checks").apply{mkdirs()}
        for(glyph in Glyph.entries){
            val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);symbolDrawable(source,glyph,96).draw(Canvas(bitmap))
            val pixels=IntArray(96*96);bitmap.getPixels(pixels,0,96,0,0,96,96);assertTrue("Missing official symbol: $glyph",pixels.count{it ushr 24>0}>50);bitmap.recycle()
        }
        for(scale in listOf(1f,1.3f,2f))i.runOnMainSync{
            val config=Configuration(source.resources.configuration).apply{fontScale=scale}
            val context=source.createConfigurationContext(config)
            val first=NativeActionButton(context,"다시 듣기",Glyph.REPEAT){}
            val second=nativeEndGuidanceButton(context){}
            val group=NativeActionGroup(context,first,second)
            val width=(304*density).toInt();group.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST));group.layout(0,0,width,group.measuredHeight)
            val gap=if(first.top==second.top)second.left-first.right else second.top-first.bottom
            assertTrue("Buttons touch at font scale $scale",gap>=(Foundation.GapDp*density).toInt())
            assertTrue(first.height>=(Foundation.TouchDp*density).toInt());assertTrue(second.height>=(Foundation.TouchDp*density).toInt())
            assertEquals("Peer actions must have equal heights",first.height,second.height)
            val primary=NativeActionButton(context,"했어요",Glyph.CHECK,ActionStyle.PRIMARY){}
            primary.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST))
            assertEquals("Importance must not increase a single-line action's height",first.height,primary.measuredHeight)
            val primaryText=primary.getChildAt(1)as android.widget.TextView
            val secondaryText=first.getChildAt(1)as android.widget.TextView
            assertTrue("All button labels must be bold",primaryText.typeface.isBold&&secondaryText.typeface.isBold)
            assertEquals(primaryText.textSize,secondaryText.textSize,.1f)
            val message=nativeGuidanceMessage(context,"버튼을 길게 눌러주세요.")
            assertTrue("The instruction must carry the strongest text emphasis",message.typeface.isBold&&message.textSize>primaryText.textSize)
            if(scale>=1.3f)assertTrue("Large text needs stacked actions",second.top>=first.bottom)
            val close=nativeEndGuidanceButton(context){}
            assertEquals("안내 종료",close.label);assertEquals(ActionStyle.SECONDARY,close.style)
            val header=NativeSheetHeader(context,{},{})
            header.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST));header.layout(0,0,width,header.measuredHeight)
            assertEquals("Handle owns only its movement area",(48*context.resources.displayMetrics.density).toInt(),header.height)
            assertEquals("Handle must be centered on the full sheet",width/2f,header.markerCenter(),1f)
            assertEquals("Header contains no command button",1,header.childCount)
            val bitmap=Bitmap.createBitmap(width,group.height+header.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Foundation.Paper);header.draw(canvas);canvas.translate(0f,header.height.toFloat());group.draw(canvas)
            File(out,"components-font-$scale.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
