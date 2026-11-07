package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.guide.GuideOverlayPolicy
import kotlin.math.min

/**
 * 접근성 서비스가 선택한 항목의 테두리만 그리는 관리자입니다.
 * Context는 연결된 AccessibilityService를 전달해야 하며 모든 함수는 메인 스레드에서 호출합니다.
 * 접근성 전용 창을 사용하므로 SYSTEM_ALERT_WINDOW 권한이나 자동 클릭이 필요하지 않습니다.
 */
class HighlightOverlayManager(private val context: Context) {
    private var panelBounds: NodeBounds? = null
    fun setPanelBounds(bounds: NodeBounds?) { panelBounds = bounds; overlayView?.setPanelBounds(bounds) }
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: HighlightView? = null

    // addView가 완료된 창이 있는지를 나타냅니다. 실제 레이아웃과 그리기는 다음 프레임에 완료됩니다.
    val isShowing: Boolean
        get() = overlayView != null

    /** 화면 좌표로 받은 사각형을 강조하고, 창을 추가하지 못하면 false를 반환합니다. */
    fun show(bounds: NodeBounds, label: String, scrollable: Boolean = false): Boolean {
        requireMainThread()
        // 빈 좌표가 들어오면 예전 표시까지 지워 잘못된 항목을 안내하지 않습니다.
        if (bounds.isEmpty) {
            hide()
            return false
        }

        // 이미 떠 있는 창은 다시 사용합니다. 다시 그리면 이전 테두리는 지워지고 새 대상만 남습니다.
        // 창을 매번 추가/제거하면 TYPE_WINDOWS_CHANGED가 반복되어 불필요한 트리 갱신이 생깁니다.
        overlayView?.let { existing ->
            existing.setTarget(bounds, label, scrollable)
            return true
        }
        val view = HighlightView(context).apply { setTarget(bounds, label, scrollable); setPanelBounds(this@HighlightOverlayManager.panelBounds) }
        val parameters = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 이 두 플래그가 핵심입니다. 아래 앱이 터치와 키보드 포커스를 그대로 받습니다.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setTitle("APL 터치 통과 강조 안내")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 최신 API에서는 시스템 바를 포함한 투명 표면을 요청합니다.
                // 실제 창 시작점은 View에서 측정하므로 상태 표시줄 높이를 추측하지 않습니다.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        return try {
            windowManager.addView(view, parameters)
            overlayView = view
            Log.i(TAG, "Overlay shown")
            true
        } catch (exception: RuntimeException) {
            // 서비스 종료, 잘못된 창 토큰, 디스플레이 해제 등은 우회하지 않고 로그로 남깁니다.
            Log.e(TAG, "접근성 Overlay를 추가하지 못했습니다.", exception)
            false
        }
    }

    /** 서비스 연결 해제와 화면 이동 시 호출합니다. 여러 번 호출해도 안전합니다. */
    fun hide() {
        requireMainThread()
        val view = overlayView ?: return
        overlayView = null
        try {
            // 즉시 제거하여 서비스가 종료된 뒤 떠 있는 창이 남지 않도록 합니다.
            windowManager.removeViewImmediate(view)
            Log.i(TAG, "Overlay removed")
        } catch (exception: RuntimeException) {
            // OS가 이미 창을 제거한 경우에도 참조를 해제하여 이후 정리가 반복되지 않습니다.
            Log.w(TAG, "Overlay 정리 중 이미 제거된 창 또는 종료된 연결을 확인했습니다.", exception)
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "HighlightOverlayManager는 메인 스레드에서 호출해야 합니다."
        }
    }

    private class HighlightView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val screenLocation = IntArray(2)
        private var targetBounds: NodeBounds? = null
        private var panelBounds: NodeBounds? = null
        fun setPanelBounds(bounds: NodeBounds?) {
            if (panelBounds != bounds) { panelBounds = bounds; invalidate() }
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = com.example.aplassistmvp0.apl2.design.AplAssistantFoundation.TargetArgb
        }
        init {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            contentDescription = null
        }
        @Suppress("UNUSED_PARAMETER")
        fun setTarget(bounds: NodeBounds, label: String, scrollable: Boolean) {
            // Coordinates are always supplied by the existing fresh-node validation path.
            // Repeated accessibility events do not restart animation or replace the window.
            if (targetBounds == bounds) return
            targetBounds = bounds
            invalidate()
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            invalidate()
            return super.onApplyWindowInsets(insets)
        }
        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            invalidate()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bounds = GuideOverlayPolicy.exposedTarget(targetBounds, panelBounds, (8 * density).toInt()) ?: return
            if (width <= 0 || height <= 0) return
            getLocationOnScreen(screenLocation)
            val rectangle = RectF(bounds.left - screenLocation[0].toFloat(), bounds.top - screenLocation[1].toFloat(),
                bounds.right - screenLocation[0].toFloat(), bounds.bottom - screenLocation[1].toFloat())
            if (!rectangle.intersect(0f, 0f, width.toFloat(), height.toFloat())) return
            val inset = min(borderPaint.strokeWidth / 2f, min(rectangle.width(), rectangle.height()) / 4f)
            rectangle.inset(inset, inset)
            canvas.drawRoundRect(rectangle, 2f * density, 2f * density, borderPaint)
        }
    }

    companion object {
        private const val TAG = "APLOverlay"
    }
}
