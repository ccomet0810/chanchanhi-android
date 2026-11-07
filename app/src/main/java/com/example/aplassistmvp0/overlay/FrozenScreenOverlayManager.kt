package com.example.aplassistmvp0.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewTreeObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The captured image stays in RAM and is released as soon as exploration ends. */
internal class FrozenScreenOverlayManager(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var bitmap: Bitmap? = null

    fun show(image: Bitmap, onStop: () -> Unit): Boolean {
        hide()
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        frame.addView(ImageView(context).apply {
            setImageBitmap(image)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(20, 33, 52))
        }
        panel.addView(TextView(context).apply {
            text = "화면 확인 중 · 최대 4초"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        panel.addView(Button(context).apply { text = "확인 중단"; setOnClickListener { onStop() } })
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val params = WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                setTitle("APL 화면 확인 중")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        return try {
            manager.addView(frame, params)
            root = frame
            bitmap = image
            true
        } catch (_: RuntimeException) { image.recycle(); false }
    }

    fun hide() {
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null
        bitmap?.recycle()
        bitmap = null
    }

    suspend fun awaitFirstFrame(): Boolean = suspendCancellableCoroutine { continuation ->
        val frame = root
        if (frame == null) { continuation.resume(false); return@suspendCancellableCoroutine }
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var posted = false
            override fun onDraw() {
                if (posted) return
                posted = true
                frame.postOnAnimation {
                    if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnDrawListener(this)
                    if (continuation.isActive) continuation.resume(root === frame && frame.isShown && frame.isAttachedToWindow)
                }
            }
        }
        frame.viewTreeObserver.addOnDrawListener(listener)
        continuation.invokeOnCancellation { frame.post {
            if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnDrawListener(listener)
        } }
        frame.invalidate()
    }
}
