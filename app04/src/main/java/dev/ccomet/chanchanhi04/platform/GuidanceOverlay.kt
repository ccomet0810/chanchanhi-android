package dev.ccomet.chanchanhi04.platform

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.VoiceInputActivity
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.setup.VisualSetupAvoidance
import dev.ccomet.chanchanhi04.ui.*
import kotlin.math.abs

/** Native accessibility windows; the highlight lets all touches pass to the actual app. */
class GuidanceOverlay(private val context: Context,private val measured:(Map<String,Any?>)->Unit = {}) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value*density).toInt()
    private var panel: ViewGroup? = null
    private var params: WindowManager.LayoutParams? = null
    private var focus: GestureFocusView? = null
    private var key = ""
    private var atTop = false
    private var preferredTop:Int?=null
    private var panelHeightLimit:Int?=null
    private var avoidanceLimited=false
    private var lastSafeArea:Pair<Int,Int>?=null
    private val hiddenReasons=mutableSetOf<String>()
    private val hidden get()=hiddenReasons.isNotEmpty()
    private var inputMode = false
    private var target: NodeBounds? = null
    private var state = AgentState()
    private var builtCallId:String?=null

    fun render(value: AgentState) {
        state = value; inputMode = false
        if (value.phase in setOf(Phase.IDLE,Phase.STOPPED)) { close(); return }
        val targetChanged=target!=value.target
        target = value.target
        val newKey = "${value.phase}:${value.pending?.callId}:${value.message}"
        if (newKey != key) { key = newKey; build(value) }
        else if(targetChanged)restorePanelHeight()
        drawTarget(value)
        avoidTarget()
        reportPosition()
    }
    fun showInput() {
        val running = AppRuntime.loop.state.value
        if (running.phase !in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE)) { render(running); return }
        inputMode = true; key = "input"; state = AgentState(); target=null;build(state)
    }
    private fun button(value:String,glyph:Glyph=Glyph.NEXT,primary:Boolean=false,action:()->Unit)=
        NativeActionButton(context,value,glyph,if(primary)ActionStyle.PRIMARY else ActionStyle.SECONDARY,action)
    private fun controls(box:LinearLayout,first:NativeActionButton,second:NativeActionButton){box.addView(NativeActionGroup(context,first,second))}
    private fun build(value: AgentState) {
        val previousGoal=if(inputMode)panel?.findViewWithTag<EditText>("guide-goal") else null
        val goalExpanded=(previousGoal?.parent as? View)?.visibility==View.VISIBLE
        val previousAnswer=if(value.phase==Phase.QUESTION&&builtCallId==value.pending?.callId)panel?.findViewWithTag<EditText>("guide-answer")else null
        val answerExpanded=previousAnswer?.visibility==View.VISIBLE
        val answerFocused=previousAnswer?.hasFocus()==true
        builtCallId=value.pending?.callId
        removePanel();panelHeightLimit=null
        val box = nativeGuidancePanel(context)
        
        val header=NativeSheetHeader(context,{atTop=!atTop;snap()},::attachDrag)
        if (inputMode) {
            box.addView(nativeGuidanceMessage(context,"무엇을 도와드릴까요?"))
            box.addView(button("말로 요청하기",Glyph.MIC){voice()})
            val writing=SpacedColumn(context).apply{visibility=if(goalExpanded)View.VISIBLE else View.GONE}
            val input = nativeTextInput(context,"하고 싶은 일").apply {tag="guide-goal";setText(previousGoal?.text?.toString().orEmpty())}
            writing.addView(input)
            writing.addView(button("안내 시작",primary=true) {
                val goal=input.text.toString().trim()
                if(goal.isNotEmpty()){hideKeyboard(input);AppRuntime.start(goal)}
            })
            lateinit var write:NativeActionButton
            write=button("글로 입력하기",Glyph.WRITE){write.visibility=View.GONE;writing.visibility=View.VISIBLE;focusEditor(input)}
            if(goalExpanded)write.visibility=View.GONE
            box.addView(write)
            box.addView(writing)
        } else {
            box.addView(nativeGuidanceMessage(context,if(value.phase==Phase.ERROR)"안내를 이어가지 못했어요." else value.message))
            when(value.phase) {
                Phase.QUESTION -> {
                    value.pending?.options?.forEach { option -> box.addView(button(option) { AppRuntime.loop.answer(option) }) }
                    val alternatives=SpacedColumn(context)
                    alternatives.addView(button("말로 답하기",Glyph.MIC){voice()})
                    val answer = nativeTextInput(context,"답변").apply {tag="guide-answer";setText(previousAnswer?.text?.toString().orEmpty());visibility=if(answerExpanded)View.VISIBLE else View.GONE}
                    val submit=button("답변 보내기",Glyph.SEND,primary=true){val text=answer.text.toString();if(text.isNotBlank()){hideKeyboard(answer);AppRuntime.loop.answer(text)}}.apply{visibility=if(answerExpanded)View.VISIBLE else View.GONE}
                    lateinit var write:NativeActionButton
                    write=button("글로 답하기",Glyph.WRITE){write.visibility=View.GONE;answer.visibility=View.VISIBLE;submit.visibility=View.VISIBLE;focusEditor(answer)}
                    if(answerExpanded)write.visibility=View.GONE
                    alternatives.addView(write)
                    alternatives.addView(answer);alternatives.addView(submit)
                    if(value.pending?.options?.isNotEmpty()==true){alternatives.visibility=if(answerExpanded)View.VISIBLE else View.GONE;box.addView(button("다른 답변",Glyph.WRITE){alternatives.visibility=View.VISIBLE}.apply{if(answerExpanded)visibility=View.GONE})}
                    box.addView(alternatives)
                    if(answerFocused)answer.post{focusEditor(answer)}
                }
                Phase.GUIDING -> box.addView(button(if(value.pending?.action == Action.INPUT) "입력했어요" else "했어요",Glyph.CHECK,primary=true) { AppRuntime.loop.userFinished() })
                Phase.OPEN_APP -> box.addView(button("열었어요",Glyph.CHECK,primary=true) { AppRuntime.loop.userFinished() })
                Phase.PAUSED, Phase.ERROR -> box.addView(button(if(value.phase==Phase.ERROR)"다시 시도하기" else "이어가기",primary=true) { AppRuntime.resumeGuidance() })
                Phase.COMPLETE -> if(AppRuntime.setupRequirement!=null||AppRuntime.study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK)box.addView(button(if(AppRuntime.setupRequirement!=null)"찬찬히로 돌아가기"else"설문으로 돌아가기",Glyph.NEXT,primary=true){if(AppRuntime.setupRequirement!=null)AppRuntime.verifySetupStep()else AppRuntime.finishGuide()})
                else -> Unit
            }
            if(value.phase in setOf(Phase.GUIDING,Phase.QUESTION,Phase.OPEN_APP))controls(box,
                button("다시 듣기",Glyph.REPEAT){AppRuntime.loop.repeat()},nativeEndGuidanceButton(context){AppRuntime.finishGuide()})
            else box.addView(nativeEndGuidanceButton(context){AppRuntime.finishGuide()})
        }
        if(inputMode)box.addView(button("닫기",Glyph.CLOSE){close()})
        val focusable = inputMode || value.phase == Phase.QUESTION
        val flags = (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val shadow=Foundation.PanelShadowInsetDp
        val p = WindowManager.LayoutParams((screenWidth()-dp((Foundation.SheetMarginDp-shadow)*2)).coerceAtMost(dp(Foundation.MaxWidthDp+shadow*2)).coerceAtLeast(1),
            -2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,PixelFormat.TRANSLUCENT).apply {
            gravity = (if(preferredTop!=null||atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = preferredTop?:offset(atTop); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setTitle("${context.getString(dev.ccomet.chanchanhi04.R.string.app_name)} 안내")
            if(android.os.Build.VERSION.SDK_INT>=30) setFitInsetsTypes(0)
        }
        val scroll=nativeGuidanceWindow(context,header,box){panelHeightLimit?:safeHeight()}
        panel = scroll; params = p
        scroll.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->avoidTarget();reportPosition()}
        wm.addView(scroll,p)
        if(hidden) scroll.visibility = View.INVISIBLE
        scroll.post { avoidTarget();reportPosition() }
    }
    private fun hideKeyboard(view: View) { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0); view.clearFocus() }
    private fun focusEditor(input:EditText){input.post{if(input.isAttachedToWindow&&input.isShown){input.requestFocus();context.getSystemService(InputMethodManager::class.java).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)}}}
    private fun voice() {
        panel?.let(::hideKeyboard)
        context.startActivity(Intent(context,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun attachDrag(handle: View) {
        var startY = 0f; var initial = 0; var dragging = false
        handle.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            val box=panel?:return@setOnTouchListener false
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val location=IntArray(2);box.getLocationOnScreen(location)
                    startY=event.rawY;initial=location[1];dragging=false
                    p.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;p.y=initial
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY-startY
                    if(abs(delta)>dp(5)) dragging = true
                    val top=offset(true);val bottom=(screenHeight()-offset(false)-box.height).coerceAtLeast(top)
                    p.y=(initial+delta.toInt()).coerceIn(top,bottom)
                    preferredTop=p.y;wm.updateViewLayout(box,p);reportPosition();true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if(dragging) {preferredTop=p.y;atTop=p.y+box.height/2<screenHeight()/2;avoidTarget();reportPosition()}
                    else if(event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    true
                }
                else -> false
            }
        }
    }
    private fun snap() {
        val p = params ?: return
        preferredTop=null
        p.gravity = (if(atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        p.y = offset(atTop); panel?.let { wm.updateViewLayout(it,p) }
        panel?.post{avoidTarget()}
        reportPosition()
    }
    private fun screenWidth()=if(android.os.Build.VERSION.SDK_INT>=30)wm.currentWindowMetrics.bounds.width()else context.resources.displayMetrics.widthPixels
    private fun screenHeight()=if(android.os.Build.VERSION.SDK_INT>=30)wm.currentWindowMetrics.bounds.height()else context.resources.displayMetrics.heightPixels
    private fun offset(top:Boolean):Int {
        val keyboard=(context as? android.accessibilityservice.AccessibilityService)?.windows?.firstOrNull{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}
            ?.let{w->android.graphics.Rect().also{w.getBoundsInScreen(it)}}?.takeIf{!it.isEmpty}?.let{(screenHeight()-it.top).coerceAtLeast(0)}?:0
        if(android.os.Build.VERSION.SDK_INT>=30) {
            val insets=wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            return if(top)insets.top else maxOf(dp(8),maxOf(insets.bottom,keyboard))
        }
        @Suppress("DEPRECATION") val insets=panel?.rootWindowInsets
        @Suppress("DEPRECATION") return if(top)maxOf(dp(24),insets?.systemWindowInsetTop?:dp(48))else maxOf(dp(8),insets?.systemWindowInsetBottom?:dp(32),keyboard)
    }
    private fun safeHeight()=(screenHeight()-offset(true)-offset(false)).coerceAtLeast(1)
    private fun restorePanelHeight() {
        panelHeightLimit=null
        params?.height=WindowManager.LayoutParams.WRAP_CONTENT
        panel?.let{params?.let{p->wm.updateViewLayout(it,p)};it.requestLayout();it.post{avoidTarget()}}
    }
    fun safeInsetsChanged() {
        val area=offset(true) to (screenHeight()-offset(false))
        if(lastSafeArea!=area){lastSafeArea=area;restorePanelHeight()}
        avoidTarget()
        reportPosition()
    }
    private fun avoidTarget() {
        val box = panel ?: return
        val p=params?:return
        if(box.height==0) return
        val location = IntArray(2); box.getLocationOnScreen(location)
        val safeTop=offset(true);val safeBottom=(screenHeight()-offset(false)).coerceAtLeast(safeTop+1)
        val rect=target?.takeIf{it.right>location[0]&&it.left<location[0]+box.width}
        val placement=VisualSetupAvoidance.place(safeTop,safeBottom,box.height,preferredTop?:location[1],rect?.top,rect?.bottom,dp(Foundation.GapDp))
        avoidanceLimited=!placement.avoidsTarget
        val heightChanged=placement.height<box.height
        if(heightChanged){panelHeightLimit=placement.height;p.height=placement.height;box.requestLayout()}
        if(p.gravity!=(Gravity.TOP or Gravity.CENTER_HORIZONTAL)||p.y!=placement.top||heightChanged) {
            p.gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;p.y=placement.top
            atTop=placement.top+placement.height/2<(safeTop+safeBottom)/2
            wm.updateViewLayout(box,p)
        }
    }
    private fun drawTarget(value:AgentState) {
        val system=value.pending?.action in setOf(Action.NOTIFICATIONS,Action.QUICK_SETTINGS)
        if(value.phase!=Phase.GUIDING || value.target==null&&!system) {
            focus?.let{runCatching{wm.removeViewImmediate(it)};it.release()};focus=null;return
        }
        val view=focus?:GestureFocusView(context,::reportPosition).also{
            wm.addView(it,WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;setTitle("찬찬히 강조")
                if(android.os.Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)})
            focus=it
        }
        view.target(value);view.visibility=if(hidden)View.INVISIBLE else View.VISIBLE
    }
    /** An observed activation stops the demonstration; the agent separately verifies the result. */
    fun onInteraction(event:UserInteraction,eventBounds:NodeBounds){
        val call=state.pending?:return;val screen=state.screen?:return;val b=state.target?:return
        if(state.phase!=Phase.GUIDING||event.packageName!=screen.packageName||event.windowId!=screen.windowId)return
        val node=screen.elements.firstOrNull{it.bounds==b}
        val bound=if(event.path!=null&&node!=null)event.path==node.path||event.path.startsWith(node.path+"/") else
            event.resource!=null&&event.resource==node?.resource&&screen.elements.count{it.resource==event.resource}==1
        val visual=call.region!=null&&!eventBounds.isEmpty&&eventBounds.left<=b.left+(b.right-b.left)/2&&eventBounds.right>b.left+(b.right-b.left)/2&&eventBounds.top<=b.top+(b.bottom-b.top)/2&&eventBounds.bottom>b.top+(b.bottom-b.top)/2
        if((bound||visual)&&event.kind in setOf("click","long_click","scroll","focus","text","selected"))focus?.dismiss()
    }
    private fun hideFor(reason:String,value:Boolean) {if(value)hiddenReasons.add(reason)else hiddenReasons.remove(reason);panel?.visibility=if(hidden)View.INVISIBLE else View.VISIBLE;focus?.visibility=if(hidden)View.INVISIBLE else View.VISIBLE;reportPosition()}
    fun setHidden(value:Boolean)=hideFor("capture",value)
    fun setVoiceHidden(value:Boolean)=hideFor("voice",value)
    fun setHostHidden(value:Boolean)=hideFor("host",value)
    fun setSuppressed(value:Boolean)=hideFor("settings",value)
    fun configurationChanged() {
        // Wait for a fresh observation before restoring any screen-coordinate demonstration.
        focus?.let{runCatching{wm.removeViewImmediate(it)};it.release()};focus=null
        if(panel!=null)build(state)
    }
    private var reportPosted=false
    /** Measure the native window after layout; never infer its position from intended gravity. */
    fun reportPosition(){
        if(reportPosted)return
        reportPosted=true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            reportPosted=false
            val view=panel;val position=IntArray(2);view?.getLocationOnScreen(position)
            focus?.exclusion(view?.takeIf{it.isShown}?.let{NodeBounds(position[0],position[1],position[0]+it.width,position[1]+it.height)})
            measured(mapOf("visible" to (view?.isShown==true && view.isAttachedToWindow),
                "panel" to view?.let{listOf(position[0],position[1],position[0]+it.width,position[1]+it.height)},
                "gesture" to focus?.diagnostics(),"hiddenReasons" to hiddenReasons.sorted(),"phase" to state.phase.name.lowercase(),
                "callId" to state.pending?.callId,"instruction" to state.message,"target" to target,"anchor" to if(atTop)"top"else"bottom",
                "avoidanceLimited" to avoidanceLimited,"heightLimit" to panelHeightLimit,"viewport" to viewportDiagnostics()))
        }
    }
    fun diagnostics():Map<String,Any?> {
        val position=IntArray(2);panel?.getLocationOnScreen(position)
        return mapOf("phase" to state.phase.name,"attached" to (panel?.isAttachedToWindow==true),
            "shown" to (panel?.isShown==true),"width" to (panel?.width ?: 0),"height" to (panel?.height ?: 0),"hidden" to hidden,
            "gesture" to focus?.diagnostics(),"top" to position[1],"bottom" to position[1]+(panel?.height ?: 0),"highlightAttached" to (focus?.isAttachedToWindow==true),"avoidanceLimited" to avoidanceLimited,"viewport" to viewportDiagnostics())
    }
    private fun viewportDiagnostics():Map<String,Any?> {
        val window=panel as? NativeGuidanceWindow?:return emptyMap()
        val scroll=window.scrollViewport;val viewport=window.viewportBounds();val header=window.headerBounds()
        return mapOf("top" to viewport.top,"bottom" to viewport.bottom,"height" to viewport.height().coerceAtLeast(0),
            "headerTop" to header.top,"headerBottom" to header.bottom,
            "contentHeight" to (scroll.getChildAt(0)?.height?:0),"scrollY" to scroll.scrollY,
            "canScrollForward" to scroll.canScrollVertically(1),"canScrollBackward" to scroll.canScrollVertically(-1),
            "withinWindow" to (viewport.top>=header.bottom&&viewport.bottom<=window.height-window.paddingBottom))
    }
    private fun removePanel() { panel?.let { runCatching { wm.removeViewImmediate(it) } }; panel = null; params = null }
    fun close() { removePanel(); focus?.let { runCatching { wm.removeViewImmediate(it) }; it.release() }; focus = null; key = "";target=null;preferredTop=null;panelHeightLimit=null;avoidanceLimited=false }

}
