package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.drawable.Drawable
import com.example.aplassistmvp0.R

/** Unmodified Google Material Symbols Rounded: optical size 24, weight 400, fill 0. */
internal object Apl2MaterialSymbols {
    val Close = R.drawable.ms_close
    val Send = R.drawable.ms_send
    val Mic = R.drawable.ms_mic
    val Stop = R.drawable.ms_stop
    val Volume = R.drawable.ms_volume_up
    fun drawable(context: Context, resource: Int, sizeDp: Int = 24): Drawable =
        checkNotNull(context.getDrawable(resource)).mutate().apply {
            // AccessibilityService contexts do not necessarily inherit the activity theme.
            setTint(com.example.aplassistmvp0.apl2.design.AplAssistantFoundation.TextArgb)
            val size = Apl2NativeStyle.dp(context, sizeDp)
            setBounds(0, 0, size, size)
        }
}
