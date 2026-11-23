package dev.ccomet.chanchanhi04.privacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import dev.ccomet.chanchanhi04.agent.ScreenImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class RedactionReport(val nodeBoxes: Int, val ocrLines: Int, val ocrBoxes: Int, val ms: Double)

/**
 * 레이어 4: 스크린샷 가림. (a) 내용을 가린 접근성 노드와 비밀번호·입력란 영역을 칠하고,
 * (b) 휴대폰 안 한국어 OCR로 접근성에 없는 글자를 읽어 개인정보 형식이면 그 줄을 칠합니다.
 * 원본 비트맵은 보내지 않으며, 실패하면 예외를 던져 호출 쪽이 이미지 없이 보내게 합니다.
 */
class ScreenImageRedactor(private val detector: PiiDetector) {
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val paint = Paint().apply { color = android.graphics.Color.rgb(32, 32, 32); style = Paint.Style.FILL }

    fun redact(image: ScreenImage, privateNodeIds: Set<Int>, privateRegions: List<NodeBounds>, vault: PseudonymVault): Pair<ScreenImage, RedactionReport> {
        val began = System.nanoTime()
        val bytes = Base64.decode(image.base64, Base64.NO_WRAP)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("image decode failed")
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== decoded) decoded.recycle() }
        try {
            val canvas = Canvas(bitmap)
            val sx = image.imageWidth.toFloat() / image.observation.width
            val sy = image.imageHeight.toFloat() / image.observation.height
            fun fill(b: NodeBounds, pad: Int = 2) = canvas.drawRect(
                (b.left * sx - pad).coerceAtLeast(0f), (b.top * sy - pad).coerceAtLeast(0f),
                (b.right * sx + pad).coerceAtMost(bitmap.width.toFloat()), (b.bottom * sy + pad).coerceAtMost(bitmap.height.toFloat()), paint)
            val nodeBoxes = image.observation.elements.filter { it.id in privateNodeIds && !it.bounds.isEmpty }.onEach { fill(it.bounds) }.size +
                privateRegions.filter { !it.isEmpty }.onEach { fill(it) }.size

            // 접근성에 없는 글자(이미지 속 글, 웹뷰, 사진)를 OCR로 확인한다. 이미 칠한 영역은 다시 읽히지 않는다.
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), OCR_WAIT_SECONDS, TimeUnit.SECONDS)
            val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null && it.text.isNotBlank() }
            val known = vault.knownOriginals().map { it.first }
            val found = detector.detect(lines.map { it.text })
            var ocrBoxes = 0
            lines.forEachIndexed { i, line ->
                if (found[i].isNotEmpty() || known.any { line.text.contains(it) }) {
                    val box: Rect = line.boundingBox!!
                    canvas.drawRect(Rect(box.left - 3, box.top - 3, box.right + 3, box.bottom + 3), paint); ocrBoxes++
                }
            }
            val encoded = encode(bitmap)
            return ScreenImage(image.observation, encoded.first, encoded.second, encoded.third, "image/jpeg") to
                RedactionReport(nodeBoxes, lines.size, ocrBoxes, (System.nanoTime() - began) / 1e6)
        } finally { bitmap.recycle() }
    }

    /**
     * ScreenService.compressImage와 같은 크기 제한(122,880바이트)을 지킨다. 크기(픽셀)는 바꾸지 않는다:
     * AgentLoop가 AI가 고른 region을 원본 이미지 크기로 화면 좌표에 되돌리기 때문이다.
     */
    private fun encode(bitmap: Bitmap): Triple<String, Int, Int> {
        for (quality in listOf(75, 55, 35, 20)) {
            val out = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (out.size <= 122_880) return Triple(Base64.encodeToString(out, Base64.NO_WRAP), bitmap.width, bitmap.height)
        }
        error("redacted image too large")
    }

    private companion object { const val OCR_WAIT_SECONDS = 4L }
}
