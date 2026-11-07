package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.guide.GuideCaption
import com.example.aplassistmvp0.guide.GuideController
import com.example.aplassistmvp0.guide.GuideStateRepository

/** Only this narrow controls window consumes input; it never intercepts touches elsewhere. */
internal class GuideFeedbackOverlayManager(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var folded: Boolean? = null

    fun show(atTop: Boolean, collapsed: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (folded != collapsed) { hide(); folded = collapsed }
        val gravity = Gravity.CENTER_HORIZONTAL or if (atTop) Gravity.TOP else Gravity.BOTTOM
        val offset = dp(8) + systemInset(atTop)
        val width = (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(160))
        if (view != null) {
            if (params?.gravity != gravity || params?.y != offset || params?.width != width) {
                params?.gravity = gravity; params?.y = offset; params?.width = width
                runCatching { manager.updateViewLayout(view, params) }.onFailure { hide() }
            }
            return
        }
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = Gravity.CENTER_VERTICAL }
        val replay = Apl2NativeStyle.button(context, if (collapsed) "안내 보기" else "다시 듣기",
            if (collapsed) Apl2Icon.Kind.Expand else Apl2Icon.Kind.Volume) {
            if (collapsed) GuideCaption.toggle() else if (AplPreferences.get(context).voiceEnabled) {
                GuideController.silence()
                GuideController.announceChoice(GuideStateRepository.state.value.message)
            }
        }.apply {
            textSize = 16f
            if (!collapsed && !AplPreferences.get(context).voiceEnabled) {
                isEnabled = false
                contentDescription = "다시 듣기, 음성 안내 꺼짐"
            }
        }
        root.addView(replay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        root.addView(Apl2NativeStyle.button(context, "안내 끝내기", Apl2Icon.Kind.Close) { GuideController.stop() }.apply { textSize = 16f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val layout = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; y = offset; setTitle("APL2 안내 동작")
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { manager.addView(root, layout); view = root; params = layout }
    }

    fun hide() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val root = view ?: return
        view = null; params = null
        runCatching { manager.removeViewImmediate(root) }
    }
    private fun systemInset(atTop: Boolean): Int = if (Build.VERSION.SDK_INT >= 30) {
        val insets = manager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        if (atTop) insets.top else insets.bottom
    } else dp(24)
    private fun dp(value: Int) = Apl2NativeStyle.dp(context, value)
}
