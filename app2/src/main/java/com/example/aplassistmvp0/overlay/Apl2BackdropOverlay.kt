package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.guide.GuideOverlayPolicy

/** Only draws. FLAG_NOT_TOUCHABLE preserves real app touch, focus, keyboard and scrolling. */
internal class Apl2BackdropOverlayManager(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: Apl2BackdropView? = null
    private var panelBounds: NodeBounds? = null
    fun setPanelBounds(bounds: NodeBounds?) { panelBounds = bounds; view?.panelBounds = bounds }
    val isShowing get() = view?.visibility == View.VISIBLE
    fun show(target: NodeBounds? = null) {
        view?.let { it.target = target; it.visibility = View.VISIBLE; return }
        val root = Apl2BackdropView(context).apply { this.target = target; this.panelBounds = this@Apl2BackdropOverlayManager.panelBounds }
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; setTitle("APL2 집중 배경")
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { manager.addView(root, params); view = root }
    }
    // Keep its stacking order beneath the panel when the user touches and the next step begins.
    fun hide() { view?.visibility = View.INVISIBLE }
    fun dispose() { val root = view ?: return; view = null; runCatching { manager.removeViewImmediate(root) } }
}

/** Uniform 55% black scrim, with a fully transparent cutout over the usable target. */
internal class Apl2BackdropView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val cutout = Path()
    private val location = IntArray(2)
    private val targetRect = RectF()
    var target: NodeBounds? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var panelBounds: NodeBounds? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        GuideOverlayPolicy.exposedTarget(target, panelBounds, (8 * resources.displayMetrics.density).toInt())?.let { bounds ->
            getLocationOnScreen(location)
            cutout.reset()
            targetRect.set(bounds.left - location[0].toFloat(), bounds.top - location[1].toFloat(),
                bounds.right - location[0].toFloat(), bounds.bottom - location[1].toFloat())
            cutout.addRoundRect(targetRect, 2 * resources.displayMetrics.density,
                2 * resources.displayMetrics.density, Path.Direction.CW)
            canvas.clipOutPath(cutout)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(save)
    }
}
