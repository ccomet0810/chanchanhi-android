package com.example.aplassistmvp0.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import com.example.aplassistmvp0.apl2.design.AplFoundation

/** Native WindowManager/assistant surfaces share these values with each other.
 * Sizes are dp/sp, so system font size and display density remain authoritative. */
internal object Apl2NativeStyle {
    const val Ink = AplFoundation.InkArgb
    const val Surface = AplFoundation.SurfaceArgb
    const val Muted = AplFoundation.MutedArgb
    const val Line = AplFoundation.OutlineArgb
    const val PanelRadius = AplFoundation.PanelRadiusDp
    const val ControlRadius = AplFoundation.ControlRadiusDp
    const val TargetMinimum = AplFoundation.MinTouchDp

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun surface(context: Context, fill: Int = Surface, stroke: Int = Ink, radius: Int = PanelRadius,
                strokeWidth: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius).toFloat()
        if (strokeWidth > 0) setStroke(dp(context, strokeWidth), stroke)
    }

    fun button(context: Context, label: String, icon: Apl2Icon.Kind? = null, filled: Boolean = false,
               action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        includeFontPadding = false
        minHeight = dp(context, TargetMinimum)
        minimumHeight = dp(context, TargetMinimum)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        gravity = Gravity.CENTER
        val foreground = if (filled) Surface else Ink
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Muted, foreground)))
        val shape = surface(context, if (filled) Ink else Surface, if (filled) Ink else Line, ControlRadius)
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape, null)
        if (icon != null) setCompoundDrawablesRelative(Apl2Icon(context, icon, foreground), null, null, null)
        compoundDrawablePadding = dp(context, 8)
        setOnClickListener { action() }
    }
}

/** Native counterpart of the same Google Material Symbols used by Compose. */
internal class Apl2Icon(context: Context, kind: Kind, color: Int, size: Int = 24) : Drawable() {
    enum class Kind { Microphone, Edit, Close, Volume, Send, Expand }
    private val icon = Apl2MaterialSymbols.drawable(context, when (kind) {
        Kind.Microphone -> com.example.aplassistmvp0.R.drawable.ms_mic
        Kind.Edit -> com.example.aplassistmvp0.R.drawable.ms_edit
        Kind.Close -> com.example.aplassistmvp0.R.drawable.ms_close
        Kind.Volume -> com.example.aplassistmvp0.R.drawable.ms_volume_up
        Kind.Send -> com.example.aplassistmvp0.R.drawable.ms_send
        Kind.Expand -> com.example.aplassistmvp0.R.drawable.ms_expand_less
    }, size).apply { setTint(color) }
    init { setBounds(icon.bounds) }
    override fun draw(canvas: Canvas) { icon.bounds = bounds; icon.draw(canvas) }
    override fun setAlpha(alpha: Int) { icon.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { icon.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
