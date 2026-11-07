package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.Shader
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.apl2.design.AplAssistantFoundation as Tokens
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.guide.*

/** One Figma panel. Only its measured rectangle accepts touches. */
internal class GuideTextOverlayManager(private val context: Context,
                                       private val panelBoundsChanged: (NodeBounds?) -> Unit = {},
                                       private val outsideTouch: () -> Unit = {}) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: ScrollView? = null
    private var parameters: WindowManager.LayoutParams? = null
    private var message: TextView? = null
    private var history: TextView? = null
    private var action: Button? = null
    private var alternative: Button? = null
    private var confirm: Button? = null
    private var controlDone: Button? = null
    private var lastBounds: NodeBounds? = null
    private var shell: Apl2AssistantPanel? = null
    private var lastStateKey: String? = null
    private var currentState: GuideUiState? = null
    private var folded = false
    private var userDockTop: Boolean? = null
    private var dragging = false
    private var docking: ValueAnimator? = null
    private var glow: ValueAnimator? = null
    private var reveal: ValueAnimator? = null
    private var processingTop: Int? = null
    private data class PanelWindowKey(val width: Int, val height: Int, val topInset: Int, val bottomInset: Int,
                                      val fontScale: Float, val largeGuide: Boolean)
    private data class ExpandedPlacement(val top: Int, val window: PanelWindowKey)
    private var expandedPlacement: ExpandedPlacement? = null
    private var placementPreferredTop = true
    private var placementProtected: List<NodeBounds> = emptyList()
    private var placementPrimary: NodeBounds? = null
    val isCollapsed: Boolean get() = folded
    fun collapse() {
        if (view == null || folded) return
        val state = currentState ?: return
        folded = true
        show(state, placementPreferredTop, protected = placementProtected, primary = placementPrimary)
    }
    private fun applyFold() {
        shell?.body?.visibility = if (folded) View.GONE else View.VISIBLE
        shell?.actions?.visibility = if (folded) View.GONE else View.VISIBLE
        shell?.setPadding(dp(16), dp(if (folded) 8 else 16), dp(16), dp(if (folded) 8 else 16))
        shell?.contentDescription = if (folded) "안내 펼치기" else null
    }
    val isShowing: Boolean get() = view != null

    @Suppress("UNUSED_PARAMETER")
    fun show(state: GuideUiState, preferredTop: Boolean, offsetDp: Int = 12,
             protected: List<NodeBounds> = emptyList(), primary: NodeBounds? = null, expand: Boolean = false): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Fold/expand are presentation changes of the same instruction. Keep the
        // latest protected regions instead of reverting to an unprotected top panel.
        placementPreferredTop = preferredTop
        placementProtected = protected.toList()
        placementPrimary = primary
        val wasFolded = folded
        val previousHeight=view?.height ?: 0
        if (expand) folded = false
        val windowKey = currentWindowKey()
        val planCurrent = expandedPlacement?.window == windowKey
        // The folded window's bottom-edge top is not a valid anchor for a taller
        // panel. Re-measure against target protection and safe insets when unfolding.
        if (!state.busy || wasFolded || !planCurrent) processingTop = null
        else if (processingTop == null) processingTop = expandedPlacement?.top
        val key = "${state.goal}|${state.step}|${state.phase}|${state.message}"
        if (key != lastStateKey) {
            if (state.busy || state.phase in setOf("opening_app", "guiding", "error", "paused", "confirming", "done")) folded = false
            lastStateKey = key
        }
        currentState = state
        if (view == null) {
            val panel = Apl2AssistantPanel(context) { GuideController.stop() }.apply {
                closeButton.contentDescription = "안내 끝내기"
                closeButton.text = "안내 끝내기"
                closeButton.compoundDrawablePadding = dp(8)
                closeButton.layoutParams = FrameLayout.LayoutParams(dp(144), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
                onOutsideTouch = outsideTouch
                actions.orientation = LinearLayout.VERTICAL
                background = null
                setOnClickListener {
                    if (folded) {
                        currentState?.let { show(it, placementPreferredTop, protected = placementProtected,
                            primary = placementPrimary, expand = true) }
                    }
                }
            }
            shell = panel
            history = Apl2AssistantStyle.text(context, 16f).apply {
                setPadding(dp(4), 0, dp(4), 0)
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f),
                        Tokens.HistoryArgb, Tokens.PlaceholderArgb, Shader.TileMode.CLAMP)
                }
            }.also { panel.body.addView(it) }
            message = Apl2AssistantStyle.text(context).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(dp(4), dp(16), dp(4), dp(16))
            }.also { panel.body.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            controlDone = Apl2AssistantStyle.button(context, "입력했어요", com.example.aplassistmvp0.R.drawable.ms_check) {
                GuideController.confirmControl()
            }.also { panel.actions.addView(it, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }) }
            confirm = Apl2AssistantStyle.button(context, "완료", com.example.aplassistmvp0.R.drawable.ms_check) {
                GuideController.confirmCompleted()
            }.also { panel.actions.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
            val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            panel.actions.addView(actionRow, LinearLayout.LayoutParams(-1, -2))
            action = Apl2AssistantStyle.button(context, "다시 듣기", Apl2MaterialSymbols.Volume) {
                if (GuideStateRepository.state.value.phase in setOf("error", "paused")) GuideController.retry()
                else if (GuideStateRepository.state.value.busy) GuideController.stop()
                else if (AplPreferences.get(context).voiceEnabled) {
                    GuideController.silence()
                    GuideController.announceChoice(GuideStateRepository.state.value.message)
                }
            }.also { actionRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            alternative = Apl2AssistantStyle.button(context, "다른 안내", com.example.aplassistmvp0.R.drawable.ms_help) {
                GuideController.reportWrong()
            }.also { actionRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }) }
            val root = object : ScrollView(context) {
                override fun onMeasure(w: Int, h: Int) {
                    if(View.MeasureSpec.getMode(h)==View.MeasureSpec.EXACTLY) super.onMeasure(w,h)
                    else super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * .72f).toInt(), View.MeasureSpec.AT_MOST))
                }
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { outsideTouch(); return false }
                    return super.dispatchTouchEvent(event)
                }
            }.apply {
                isFillViewport = false; isVerticalScrollBarEnabled = false
                val surface = Apl2GuidePanelSurface(context)
                background = surface
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                clipToOutline = false; addView(panel)
                if (Apl2AssistantStyle.motions(context)) glow = ValueAnimator.ofFloat(.25f, .6f).apply {
                    duration = 2400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { surface.phase = it.animatedValue as Float }; start()
                }
                viewTreeObserver.addOnPreDrawListener {
                    if (view === this && width > 0 && height > 0) {
                        val location = IntArray(2).also(::getLocationOnScreen)
                        val bounds = NodeBounds(location[0], location[1], location[0] + width, location[1] + height)
                        if (lastBounds != bounds) { lastBounds = bounds; panelBoundsChanged(bounds) }
                    }
                    true
                }
            }
            val params = WindowManager.LayoutParams(overlayWidth(), -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
                setTitle("APL2 행동 안내")
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= 30) {
                    setFitInsetsTypes(0); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            updateText(state)
            applyFold()
            val atTop = chooseTop(root, protected, preferredTop, primary)
            params.gravity = Gravity.CENTER_HORIZONTAL or if (atTop) Gravity.TOP else Gravity.BOTTOM
            params.y = offset(atTop)
            runCatching {
                manager.addView(root, params); view = root; parameters = params
                installHeaderDrag(panel)
                rememberExpandedPlacement(root, params, windowKey)
            }
            return atTop
        }
        updateText(state)
        applyFold()
        if (wasFolded && !folded && Apl2AssistantStyle.motions(context)) shell?.body?.apply {
            alpha = 0f; animate().alpha(1f).setDuration(160).start()
        }
        val root = view ?: return preferredTop
        val params = parameters ?: return preferredTop
        if (dragging || docking?.isRunning == true || reveal?.isRunning==true) return userDockTop ?: preferredTop
        // Anchor the header to its actual screen position while processing. Bottom
        // gravity would move Exit when a confirmation row disappears.
        val height = WindowManager.LayoutParams.WRAP_CONTENT
        val heightChanged = params.height != height
        params.height = height
        val anchored = state.busy && !folded && processingTop != null && Build.VERSION.SDK_INT >= 30
        val atTop = if (anchored) true else if (state.busy && !folded && !wasFolded && planCurrent)
            (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP
            else chooseTop(root, protected, preferredTop, primary)
        val gravity = Gravity.CENTER_HORIZONTAL or if (atTop) Gravity.TOP else Gravity.BOTTOM
        val y = if (anchored) processingTop!! else offset(atTop)
        if (heightChanged || params.gravity != gravity || params.y != y || params.width != overlayWidth()) {
            // Move the native touchable window itself. Translating its root separately
            // makes the visible Exit and the window's input region disagree mid-transition.
            root.animate().cancel()
            root.translationY = 0f
            params.gravity = gravity; params.y = y; params.width = overlayWidth()
            runCatching {
                manager.updateViewLayout(root, params)
            }.onFailure { hide() }
        }
        if (view === root) rememberExpandedPlacement(root, params, windowKey)
        if(wasFolded && !folded && previousHeight>0 && root.measuredHeight>previousHeight && Apl2AssistantStyle.motions(context)) {
            val height=root.measuredHeight
            params.height=previousHeight;manager.updateViewLayout(root,params)
            reveal=ValueAnimator.ofInt(previousHeight,height).apply {
                duration=Tokens.RespondMs
                addUpdateListener { if(view===root){params.height=it.animatedValue as Int;manager.updateViewLayout(root,params)} }
                addListener(object:android.animation.AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:android.animation.Animator){if(view===root){params.height=-2;manager.updateViewLayout(root,params)}}
                });start()
            }
        }
        return atTop
    }

    private fun currentWindowKey() = PanelWindowKey(context.resources.displayMetrics.widthPixels,
        context.resources.displayMetrics.heightPixels, offset(true), offset(false),
        context.resources.configuration.fontScale, AplPreferences.get(context).largeGuide)

    private fun rememberExpandedPlacement(root: View, params: WindowManager.LayoutParams, key: PanelWindowKey) {
        if (folded) return
        // Layout may still report the old folded window while WindowManager is
        // applying this request. Anchor later processing to our measured plan.
        root.measure(View.MeasureSpec.makeMeasureSpec(overlayWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val top = if ((params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) params.y
            else key.height - params.y - root.measuredHeight
        expandedPlacement = ExpandedPlacement(top, key)
    }

    private fun chooseTop(root: View, protected: List<NodeBounds>, preferred: Boolean, primary: NodeBounds?): Boolean {
        if (primary == null && protected.isEmpty()) parameters?.let {
            return userDockTop ?: ((it.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP)
        }
        val width = overlayWidth()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = root.measuredHeight
        val screenHeight = context.resources.displayMetrics.heightPixels
        val top = offset(true); val bottom = screenHeight - offset(false) - height
        val left = (context.resources.displayMetrics.widthPixels - width) / 2
        fun overlap(y: Int, regions: List<NodeBounds>): Long = regions.sumOf { n ->
            (minOf(y + height, n.bottom) - maxOf(y, n.top)).coerceAtLeast(0).toLong() *
                (minOf(left + width, n.right) - maxOf(left, n.left)).coerceAtLeast(0)
        }
        return GuideOverlayPolicy.chooseTopForTarget(overlap(top, listOfNotNull(primary)), overlap(bottom, listOfNotNull(primary)),
            overlap(top, protected), overlap(bottom, protected), userDockTop ?: preferred)
    }
    private fun updateText(state: GuideUiState) {
        controlDone?.apply {
            visibility=if(state.awaitingControlCompletion) View.VISIBLE else View.GONE
            text=state.controlCompletionLabel
            isEnabled=state.controlCompletionEnabled
        }
        val thinking = state.busy
        confirm?.visibility = if (!thinking && (state.phase == "confirming" ||
            (state.phase == "guiding" && state.awaitingConfirmation))) View.VISIBLE else View.GONE
        history?.apply { text = state.goal; visibility = if (thinking && state.step == 0) View.VISIBLE else View.GONE }
        message?.apply {
            textSize = if (AplPreferences.get(context).largeGuide) 26f else 20f
            text = if (thinking) "화면을 확인하고 있어요." else state.message.trim()
        }
        val speaking = GuideCaption.state.value.speaking
        val retry = state.phase in setOf("error", "paused") && state.goal.isNotBlank()
        alternative?.visibility = if (!thinking && state.phase == "guiding" && !state.awaitingRecipientSelection) View.VISIBLE else View.GONE
        action?.apply {
            val label = when { retry -> "다시 시도"; thinking -> "중지"; speaking -> "음성 안내 중"; else -> "다시 듣기" }
            text = label; contentDescription = label
            isEnabled = retry || thinking || (!speaking && AplPreferences.get(context).voiceEnabled)
            val icon = Apl2MaterialSymbols.drawable(context, if (retry) com.example.aplassistmvp0.R.drawable.ms_refresh
                else if (thinking) Apl2MaterialSymbols.Stop else Apl2MaterialSymbols.Volume).apply {
                if (!isEnabled) alpha = 96
            }
            setCompoundDrawablesRelative(icon, null, null, null)
        }
    }
    fun hide() {
        val root = view ?: return
        view = null; parameters = null; message = null; history = null; action = null; alternative = null; confirm = null; controlDone = null; shell = null
        docking?.cancel(); docking = null; glow?.cancel(); glow = null; reveal?.cancel(); reveal=null
        dragging = false; userDockTop = null
        folded = false; lastStateKey = null; currentState = null
        processingTop = null
        expandedPlacement = null
        placementPreferredTop = true; placementProtected = emptyList(); placementPrimary = null
        lastBounds = null; panelBoundsChanged(null)
        root.animate().cancel(); runCatching { manager.removeViewImmediate(root) }
    }
    private fun dp(v: Int) = Apl2NativeStyle.dp(context, v)
    private fun overlayWidth() = minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(Tokens.PanelWidthDp)).coerceAtLeast(dp(160))
    private fun installHeaderDrag(panel: Apl2AssistantPanel) {
        var startY = 0f; var startTop = 0; var moved = false
        panel.header.contentDescription = "안내 접기 또는 펼치기. 위아래로 끌어 이동"
        panel.header.setOnClickListener {
            val state = currentState ?: return@setOnClickListener
            if (folded) show(state, placementPreferredTop, protected = placementProtected, primary = placementPrimary, expand = true)
            else collapse()
        }
        panel.header.setOnTouchListener { header, event ->
            val root = view ?: return@setOnTouchListener false
            val params = parameters ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    header.parent?.requestDisallowInterceptTouchEvent(true)
                    docking?.cancel(); startY = event.rawY; moved = false
                    startTop = lastBounds?.top ?: if ((params.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) params.y
                        else context.resources.displayMetrics.heightPixels - params.y - root.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawY-startY) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    if (moved) {
                        dragging = true
                        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        params.y = (startTop + event.rawY-startY).toInt().coerceIn(offset(true),
                            maxOf(offset(true), context.resources.displayMetrics.heightPixels-offset(false)-root.height))
                        manager.updateViewLayout(root, params)
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    header.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    if(!moved && event.actionMasked==MotionEvent.ACTION_CANCEL) return@setOnTouchListener true
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) header.performClick()
                    else {
                        userDockTop = params.y + root.height/2 < context.resources.displayMetrics.heightPixels/2
                        val end = if (userDockTop == true) offset(true)
                            else maxOf(offset(true), context.resources.displayMetrics.heightPixels-offset(false)-root.height)
                        expandedPlacement=ExpandedPlacement(end,currentWindowKey())
                        processingTop=end.takeIf{currentState?.busy==true}
                        if (Apl2AssistantStyle.motions(context)) docking = ValueAnimator.ofInt(params.y,end).apply {
                            duration = Tokens.RespondMs
                            addUpdateListener { if (view === root) { params.y = it.animatedValue as Int; manager.updateViewLayout(root,params) } }
                            start()
                        } else { params.y=end; manager.updateViewLayout(root,params) }
                    }; true
                }
                else -> false
            }
        }
    }
    private fun offset(top: Boolean): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = manager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            return if (top) maxOf(dp(48), insets.top + dp(16)) else maxOf(dp(32), insets.bottom + dp(8))
        }
        return if (top) dp(48) else dp(32)
    }
}
