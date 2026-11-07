package com.example.aplassistmvp0.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.caverock.androidsvg.SVG
import com.example.aplassistmvp0.R
import com.example.aplassistmvp0.apl2.design.AplAssistantFoundation as Tokens
import com.example.aplassistmvp0.apl2.settings.AplPreferences

/** Local, unmodified Figma SVGs. No network requests or substitute glyphs at runtime. */
internal class Apl2FigmaDrawable(context: Context, asset: String, widthDp: Int = 24, heightDp: Int = 24) : Drawable() {
    private val svg = synchronized(cache) { cache.getOrPut(asset) { SVG.getFromAsset(context.assets, "chanchan/$asset") } }
    private val w = Apl2NativeStyle.dp(context, widthDp)
    private val h = Apl2NativeStyle.dp(context, heightDp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setBounds(0, 0, w, h) }
    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h
    override fun draw(canvas: Canvas) {
        val rectangle = RectF(bounds)
        val saved = canvas.saveLayer(rectangle, paint)
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / svg.documentWidth, bounds.height() / svg.documentHeight)
        svg.renderToCanvas(canvas)
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        private val cache = mutableMapOf<String, SVG>()
        const val Logo = "b7c90.svg"
    }
}

internal object Apl2AssistantStyle {
    private var font: Typeface? = null
    fun text(context: Context, size: Float = 20f) = TextView(context).apply {
        applyText(this, size)
    }
    fun applyText(view: TextView, size: Float) {
        view.typeface = font ?: view.resources.getFont(R.font.noto_sans_kr).also { font = it }
        view.fontVariationSettings = "'wght' 500"
        view.textSize = size
        view.setTextColor(Tokens.TextArgb)
        view.includeFontPadding = false
        view.setLineSpacing(0f, 1f)
    }
    fun button(context: Context, label: String, icon: Int? = null, action: () -> Unit) = Button(context).apply {
        applyText(this, 16f)
        text = label; isAllCaps = false
        minWidth = 0; minimumWidth = 0
        minHeight = Apl2NativeStyle.dp(context, Tokens.ControlHeightDp); minimumHeight = minHeight
        setPadding(Apl2NativeStyle.dp(context, 16), Apl2NativeStyle.dp(context, 12),
            Apl2NativeStyle.dp(context, 16), Apl2NativeStyle.dp(context, 12))
        gravity = Gravity.CENTER
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        stateListAnimator = null
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Tokens.PlaceholderArgb, Tokens.TextArgb)))
        background = RippleDrawable(ColorStateList.valueOf(0x18000000),
            Apl2NativeStyle.surface(context, Tokens.ControlArgb, radius = 1024, strokeWidth = 0), null)
        compoundDrawablePadding = if (label.isEmpty()) 0 else Apl2NativeStyle.dp(context, 8)
        if (icon != null) setCompoundDrawablesRelative(Apl2MaterialSymbols.drawable(context, icon), null, null, null)
        // TextView otherwise pins a left compound drawable to the padding, rather than the label group.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val iconWidth = compoundDrawablesRelative[0]?.bounds?.width() ?: 0
            if (iconWidth > 0) {
                val group = iconWidth + compoundDrawablePadding + paint.measureText(text.toString())
                val pad = ((width - group) / 2).toInt().coerceAtLeast(Apl2NativeStyle.dp(context, 8))
                if (paddingLeft != pad) setPadding(pad, paddingTop, pad, paddingBottom)
            }
        }
        setOnClickListener { action() }
    }
    fun motions(context: Context) = !AplPreferences.get(context).reduceMotion && ValueAnimator.areAnimatorsEnabled()
}

/** Shared header/body/actions shell, used by both the voice session and accessibility guide. */
internal class Apl2AssistantPanel(context: Context, close: () -> Unit) : LinearLayout(context) {
    val closeButton = Apl2AssistantStyle.button(context, "", Apl2MaterialSymbols.Close, close).apply {
        contentDescription = "닫기"; background = null
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    val body = LinearLayout(context).apply { orientation = VERTICAL }
    val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val header = FrameLayout(context)
    var onOutsideTouch: (() -> Unit)? = null
    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = null
        isClickable = true
        header.addView(ImageView(context).apply {
            setImageDrawable(Apl2FigmaDrawable(context, Apl2FigmaDrawable.Logo, 60, 20))
            contentDescription = "찬찬히"; scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(60), dp(20), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
        header.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutsideTouch?.invoke(); return false }
        return super.dispatchTouchEvent(event)
    }
    private fun dp(v: Int) = Apl2NativeStyle.dp(context, v)
}

/** Own animation clock: changing transcript text never replaces or restarts the waveform. */
internal class Apl2WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.TextArgb }
    private val fade = Paint()
    private val density = resources.displayMetrics.density
    private var animator: ValueAnimator? = null
    private var offset = 0f
    var listening = false
        set(value) { if (field != value) { field = value; syncAnimation() } }
    internal val phaseForTest: Float get() = offset
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun syncAnimation() {
        if (!listening || !isAttachedToWindow || !Apl2AssistantStyle.motions(context)) {
            animator?.cancel(); animator = null
            if (!listening) offset = 0f
            invalidate(); return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, Tokens.WaveTravelDp).apply {
            duration = Tokens.WaveLoopMs; repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fade.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Tokens.ControlArgb, Tokens.ControlArgb, 0x00EFEFEF, Tokens.ControlArgb,
                Tokens.ControlArgb, 0x00EFEFEF, Tokens.ControlArgb, Tokens.ControlArgb),
            floatArrayOf(0f, .04f, .1f, .32f, .68f, .9f, .96f, 1f), Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        if (!listening) return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        apl2WaveHeights.forEachIndexed { index, h ->
            val x = (index * 8f - offset) * density
            if (x + 4 * density >= 0 && x <= width) {
                val half = h * density / 2
                canvas.drawRoundRect(x, height / 2f - half, x + 4 * density, height / 2f + half, 2 * density, 2 * density, paint)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)
        canvas.restoreToCount(saved)
    }
}
