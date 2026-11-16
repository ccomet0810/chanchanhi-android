package dev.ccomet.chanchanhi04.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText

private fun Context.dp(value:Int)=(resources.displayMetrics.density*value).toInt()
enum class ActionStyle { PRIMARY, SECONDARY, TEXT }

/** One native sheet surface for live guidance and the introduction's offline presentation. */
fun nativeGuidancePanel(context:Context)=SpacedColumn(context).apply{
    setPadding(context.dp(Foundation.PanelPaddingDp),context.dp(4),context.dp(Foundation.PanelPaddingDp),context.dp(Foundation.PanelPaddingDp))
    background=GradientDrawable().apply{
        setColor(Color.argb(Foundation.SheetAlpha,255,255,255));cornerRadius=context.dp(Foundation.SheetRadiusDp).toFloat()
        setStroke(context.dp(Foundation.PanelBorderDp),Foundation.PanelBorder)
    }
    elevation=context.dp(Foundation.PanelElevationDp).toFloat()
}

/** The handle belongs to the surface; only the instruction and actions scroll. */
fun nativeGuidanceWindow(context:Context,header:NativeSheetHeader,content:SpacedColumn,maxHeight:(()->Int)?=null)=
    NativeGuidanceWindow(context,header,content,maxHeight)

class NativeGuidanceWindow(context:Context,val header:NativeSheetHeader,content:SpacedColumn,
    private val maxHeight:(()->Int)?=null):FrameLayout(context){
    val scrollViewport=android.widget.ScrollView(context).apply{
        isFillViewport=false;clipToPadding=true
        scrollBarStyle=View.SCROLLBARS_INSIDE_INSET;isScrollbarFadingEnabled=false
    }
    private val surface=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL;background=content.background;elevation=content.elevation;clipToOutline=true
        setPadding(content.paddingLeft,content.paddingTop,content.paddingRight,content.paddingBottom)
        if(android.os.Build.VERSION.SDK_INT>=28){outlineAmbientShadowColor=Foundation.PanelShadow;outlineSpotShadowColor=Foundation.PanelShadow}
    }
    init{
        content.background=null;content.elevation=0f;content.setPadding(0,0,0,0)
        scrollViewport.addView(content)
        surface.addView(header,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        // A weighted body wraps short content and gives up height before the fixed header.
        surface.addView(scrollViewport,LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,0,1f).apply{topMargin=context.dp(Foundation.GapDp)})
        val inset=context.dp(Foundation.PanelShadowInsetDp)
        setPadding(inset,inset,inset,inset);clipChildren=false;clipToPadding=false
        addView(surface,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
    }
    // Cap the complete window while preserving a smaller exact WindowManager allocation.
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val limit=maxHeight?.invoke()?.coerceAtLeast(0)
        val mode=MeasureSpec.getMode(heightMeasureSpec)
        val bounded=when{
            limit==null||mode==MeasureSpec.EXACTLY->heightMeasureSpec
            mode==MeasureSpec.UNSPECIFIED->MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST)
            else->MeasureSpec.makeMeasureSpec(minOf(limit,MeasureSpec.getSize(heightMeasureSpec)),MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec,bounded)
    }
    /** Window-relative geometry, unaffected by the body's scroll offset. */
    fun viewportBounds()=android.graphics.Rect(surface.left+scrollViewport.left+scrollViewport.paddingLeft,
        surface.top+scrollViewport.top+scrollViewport.paddingTop,
        surface.left+scrollViewport.right-scrollViewport.paddingRight,
        surface.top+scrollViewport.bottom-scrollViewport.paddingBottom)
    fun headerBounds()=android.graphics.Rect(surface.left+header.left,surface.top+header.top,
        surface.left+header.right,surface.top+header.bottom)
}

fun nativeEndGuidanceButton(context:Context,action:()->Unit)=NativeActionButton(context,UiCopy.EndGuidance,Glyph.CLOSE,ActionStyle.SECONDARY,action)

private val emphasisTypeface:Typeface=if(android.os.Build.VERSION.SDK_INT>=28)
    Typeface.create(Typeface.SANS_SERIF,Foundation.EmphasisFontWeight,false)
else Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD)

/** The instruction carries the emphasis; action importance never changes button geometry. */
fun nativeGuidanceMessage(context:Context,value:String):TextView=TextView(context).apply{
    text=value;textSize=Foundation.GuideSp.toFloat();typeface=emphasisTypeface
    setTextColor(Foundation.Ink);includeFontPadding=false;setLineSpacing(0f,Foundation.GuideLineSpacing)
    androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
}

/** Native counterpart of LargeAction. A single accessible button with decorative icon and text. */
class NativeActionButton(context:Context,val label:String,val glyph:Glyph,val style:ActionStyle=ActionStyle.SECONDARY,action:()->Unit):LinearLayout(context){
    private val icon=ImageView(context)
    private val caption=TextView(context)
    init{
        orientation=HORIZONTAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight=context.dp(Foundation.TouchDp)
        setPadding(context.dp(Foundation.ActionHorizontalPaddingDp),context.dp(Foundation.ActionVerticalPaddingDp),context.dp(Foundation.ActionHorizontalPaddingDp),context.dp(Foundation.ActionVerticalPaddingDp))
        val contentColor=when(style){ActionStyle.PRIMARY->Color.WHITE;ActionStyle.SECONDARY->Foundation.Green;ActionStyle.TEXT->Foundation.Muted}
        icon.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.setImageDrawable(symbolDrawable(context,glyph,context.dp(Foundation.SmallIconDp),contentColor))
        addView(icon,LayoutParams(context.dp(24),context.dp(24)).apply{marginEnd=context.dp(8)})
        caption.text=label;caption.textSize=Foundation.ActionSp.toFloat();caption.typeface=emphasisTypeface
        caption.includeFontPadding=false;caption.gravity=Gravity.CENTER;caption.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        val ink=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Foundation.Muted,contentColor))
        caption.setTextColor(ink);caption.isDuplicateParentStateEnabled=true;icon.imageTintList=ink;icon.isDuplicateParentStateEnabled=true
        addView(caption,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT))
        val states=StateListDrawable().apply{
            addState(intArrayOf(-android.R.attr.state_enabled),surface(Foundation.Disabled,false))
            addState(intArrayOf(android.R.attr.state_focused),surface(if(style==ActionStyle.PRIMARY)Foundation.Green else Foundation.Selected,true))
            addState(intArrayOf(),surface(if(style==ActionStyle.PRIMARY)Foundation.Green else if(style==ActionStyle.TEXT)Color.TRANSPARENT else Foundation.Surface,style==ActionStyle.SECONDARY))
        }
        background=RippleDrawable(ColorStateList.valueOf(if(style==ActionStyle.PRIMARY)0x33FFFFFF else 0x2214532D),states,null)
        setOnClickListener{action()}
    }
    private fun surface(color:Int,border:Boolean)=GradientDrawable().apply{setColor(color);cornerRadius=context.dp(Foundation.ButtonRadiusDp).toFloat();if(border)setStroke(context.dp(1).coerceAtLeast(1),Foundation.Outline)}
    /** Measure labels at the actual system font scale before deciding between two columns and a stack. */
    fun comfortableWidth():Int=(caption.paint.measureText(label)+paddingLeft+paddingRight+context.dp(32)).toInt()
    override fun getAccessibilityClassName():CharSequence="android.widget.Button"
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.text=label;info.isClickable=true}
}

/** Transparent middle dividers are real layout space and automatically skip hidden children. */
open class SpacedColumn(context:Context,gapDp:Int=Foundation.GapDp):LinearLayout(context){
    init{orientation=VERTICAL;dividerDrawable=GradientDrawable().apply{setColor(Color.TRANSPARENT);setSize(1,context.dp(gapDp))};showDividers=SHOW_DIVIDER_MIDDLE}
    override fun generateDefaultLayoutParams()=LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
}
class NativeActionGroup(context:Context,private val first:NativeActionButton,private val second:NativeActionButton):LinearLayout(context){
    private var stacked:Boolean?=null
    init{addView(first);addView(second);showDividers=SHOW_DIVIDER_MIDDLE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val gap=context.dp(Foundation.GapDp);val need=maxOf(first.comfortableWidth(),second.comfortableWidth())*2+gap
        val vertical=context.resources.configuration.fontScale>=1.3f||MeasureSpec.getSize(widthMeasureSpec)<need
        if(stacked!=vertical){
            stacked=vertical;orientation=if(vertical)VERTICAL else HORIZONTAL
            dividerDrawable=GradientDrawable().apply{setColor(Color.TRANSPARENT);setSize(if(vertical)1 else gap,if(vertical)gap else 1)}
            for(button in listOf(first,second))button.layoutParams=if(vertical)LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)else LayoutParams(0,LayoutParams.WRAP_CONTENT,1f)
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
/** Style the platform input used by the accessibility overlay without introducing a custom widget. */
fun nativeTextInput(context:Context,label:String):EditText=EditText(context).apply{
        hint=label;textSize=Foundation.TextSp.toFloat();setTextColor(Foundation.Ink);setHintTextColor(Foundation.Muted)
        minHeight=context.dp(Foundation.TouchDp);maxLines=4;includeFontPadding=false;setPadding(context.dp(16),context.dp(14),context.dp(16),context.dp(14))
        backgroundTintList=null
        background=StateListDrawable().apply{
            fun field(focused:Boolean)=GradientDrawable().apply{setColor(Foundation.Surface);cornerRadius=context.dp(16).toFloat();setStroke(context.dp(if(focused)2 else 1).coerceAtLeast(1),if(focused)Foundation.Green else Foundation.Outline)}
            addState(intArrayOf(android.R.attr.state_focused),field(true));addState(intArrayOf(),field(false))
        }
}

/** A centered drag handle; commands belong to the action group, never this header. */
class NativeSheetHeader(context:Context,move:()->Unit,onHandle:(View)->Unit):FrameLayout(context){
    val handle=FrameLayout(context).apply{
        contentDescription="안내창 위아래로 이동";isClickable=true;isFocusable=true;setOnClickListener{move()}
        addView(View(context).apply{background=GradientDrawable().apply{setColor(Foundation.Muted);cornerRadius=context.dp(2).toFloat()};importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(context.dp(40),context.dp(4),Gravity.CENTER))
    }
    init{addView(handle,LayoutParams(LayoutParams.MATCH_PARENT,context.dp(48)));onHandle(handle)}
    fun markerCenter():Float{val point=IntArray(2);handle.getLocationOnScreen(point);return point[0]+handle.width/2f}
}
