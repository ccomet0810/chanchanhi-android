package com.example.aplassistmvp0.guide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.state.ScreenSnapshot
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal object GuideVisionEncoder {
    /** 앱 창 밖과 식별된 입력/미리보기 영역을 축소 전에 가린다. 원본은 호출자가 해제한다. */
    fun encode(source: Bitmap, screen: ScreenSnapshot, appBounds: NodeBounds): GuideVisionFrame? {
        val viewport = screen.viewport ?: return null
        if (screen.truncated || viewport.left != 0 || viewport.top != 0 || source.width != viewport.right ||
            source.height != viewport.bottom || appBounds.isEmpty || appBounds.left < 0 || appBounds.top < 0 ||
            appBounds.right > source.width || appBounds.bottom > source.height) return null
        val private = screen.allNodes.filter { it.visibleToUser && GuideVisionPolicy.privateNode(it) }
        if (private.any { it.bounds.isEmpty }) return null
        val masked = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(masked)
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.clipRect(appBounds.left, appBounds.top, appBounds.right, appBounds.bottom)
            canvas.drawBitmap(source, 0f, 0f, null)
            val paint = Paint().apply { color = Color.BLACK }
            private.forEach { n -> canvas.drawRect(Rect(n.bounds.left - 4, n.bounds.top - 4,
                n.bounds.right + 4, n.bounds.bottom + 4), paint) }
            canvas.restore()
            val scale = minOf(1.0, GuideVisionPolicy.MAX_EDGE.toDouble() / maxOf(masked.width, masked.height))
            val image = Bitmap.createScaledBitmap(masked, (masked.width * scale).roundToInt().coerceAtLeast(1),
                (masked.height * scale).roundToInt().coerceAtLeast(1), true)
            try {
                for (quality in listOf(80, 60, 40)) {
                    val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                    if (bytes.size <= GuideVisionPolicy.MAX_IMAGE_BYTES) return GuideVisionFrame(
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), image.width, image.height, private.size)
                }
                return null
            } finally { if (image !== masked) image.recycle() }
        } finally { masked.recycle() }
    }
}
