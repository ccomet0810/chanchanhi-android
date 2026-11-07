package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Transparent surface and a restrained breathing edge; text itself keeps full contrast. */
internal class Apl2GuidePanelSurface(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var phase = .35f
        set(value) { field=value; invalidateSelf() }
    override fun draw(canvas: Canvas) {
        val inset=8*density; val radius=24*density
        paint.color=0xE0FFFFFF.toInt()
        paint.setShadowLayer(8*density,0f,0f,((phase*110).toInt().coerceIn(0,110) shl 24) or 0x8BDDAD)
        canvas.drawRoundRect(bounds.left+inset,bounds.top+inset,bounds.right-inset,bounds.bottom-inset,radius,radius,paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
