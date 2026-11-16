package dev.ccomet.chanchanhi04.platform

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.ui.Foundation
import kotlinx.coroutines.*

/** Entirely pass-through. No animator callback may advance an agent or acknowledge a user action. */
internal class GestureFocusView @JvmOverloads constructor(context:Context,private val changed:()->Unit={}):View(context){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var load:Job?=null
    private val drawable=LottieDrawable().apply{callback=this@GestureFocusView;repeatCount=LottieDrawable.INFINITE;speed=Foundation.GestureSpeed}
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=3*density}
    private val origin=IntArray(2)
    private val rectangle=RectF()
    private var bounds:NodeBounds?=null
    private var cue:GestureCue?=null
    private var signature=""
    private var callId:String?=null
    private var dismissed=false
    private var ready=false
    private var failed=false
    private var outline=true
    private var exclusion:NodeBounds?=null
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;isClickable=false;isFocusable=false}
    fun target(value:AgentState){
        val call=value.pending;val screen=value.screen
        if(callId!=call?.callId){callId=call?.callId;dismissed=false}
        bounds=value.target
        val next=if(value.phase==Phase.GUIDING&&screen!=null)GestureMapping.resolve(call?.action,value.target,screen.width,screen.height,call?.gesturePath)else null
        cue=next;outline=next?.outline?:true
        val key="${callId}:${value.target}:$next:${screen?.width}:${screen?.height}"
        if(signature!=key){
            signature=key;load?.cancel();drawable.cancelAnimation();drawable.clearComposition();ready=false;failed=false
            if(next!=null&&screen!=null)load=scope.launch{
                val result=withContext(Dispatchers.Default){runCatching{
                    val source=context.assets.open("gestures/${next.asset}.json").bufferedReader().use{it.readText()}
                    val json=GestureRetargeter.build(source,next,screen.width,screen.height,density)
                    LottieCompositionFactory.fromJsonStringSync(json,null).let{it.value?:throw(it.exception?:IllegalArgumentException("Invalid gesture asset"))}
                }}
                if(signature!=key)return@launch
                result.onSuccess{drawable.setComposition(it);ready=true;updatePlayback()}.onFailure{failed=true}
                invalidate();changed()
            }
        }
        updatePlayback();invalidate()
    }
    fun dismiss(){if(dismissed)return;dismissed=true;drawable.cancelAnimation();invalidate();changed()}
    fun exclusion(value:NodeBounds?){exclusion=value;invalidate()}
    private fun updatePlayback(){
        if(!ready)return
        if(!isShown||dismissed){drawable.pauseAnimation();return}
        if(!ValueAnimator.areAnimatorsEnabled()){drawable.pauseAnimation();drawable.progress=.42f}
        else if(!drawable.isAnimating)drawable.resumeAnimation()
    }
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(isAttachedToWindow)updatePlayback()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();updatePlayback()}
    override fun onDetachedFromWindow(){drawable.pauseAnimation();super.onDetachedFromWindow()}
    override fun verifyDrawable(who:Drawable)=who===drawable||super.verifyDrawable(who)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);getLocationOnScreen(origin)
        bounds?.takeIf{outline}?.let{b->
            val rect=rectangle.apply{set(b.left-origin[0].toFloat(),b.top-origin[1].toFloat(),b.right-origin[0].toFloat(),b.bottom-origin[1].toFloat())}
            rect.inset(2*density,2*density);paint.color=Foundation.Surface;paint.strokeWidth=Foundation.FocusStrokeDp*2*density;canvas.drawRoundRect(rect,Foundation.FocusRadiusDp*density,Foundation.FocusRadiusDp*density,paint)
            paint.color=Foundation.Green;paint.strokeWidth=Foundation.FocusStrokeDp*density;canvas.drawRoundRect(rect,Foundation.FocusRadiusDp*density,Foundation.FocusRadiusDp*density,paint)
        }
        if(!ready||dismissed)return
        val save=canvas.save();exclusion?.let{canvas.clipOutRect(it.left-origin[0].toFloat(),it.top-origin[1].toFloat(),it.right-origin[0].toFloat(),it.bottom-origin[1].toFloat())}
        canvas.translate(-origin[0].toFloat(),-origin[1].toFloat());drawable.setBounds(0,0,width,height);drawable.draw(canvas);canvas.restoreToCount(save)
    }
    fun diagnostics():Map<String,Any?> = mapOf("asset" to cue?.asset,"ready" to ready,"running" to drawable.isAnimating,"dismissed" to dismissed,"failed" to failed,"reducedMotion" to !ValueAnimator.areAnimatorsEnabled())
    fun release(){load?.cancel();scope.cancel();drawable.cancelAnimation();drawable.clearComposition();drawable.callback=null}
}
