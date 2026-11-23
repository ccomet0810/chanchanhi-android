package dev.ccomet.chanchanhi04.privacy

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.util.concurrent.TimeUnit

/**
 * 레이어 2: ML Kit 항목 추출(휴대폰 안에서 실행, 한국어 모델 약 수 MB).
 * 주소·전화·이메일·카드·계좌(IBAN)·금액·운송장 등 규칙이 놓치는 형식을 보완합니다.
 * 모델이 준비되지 않았거나 실패하면 예외를 던져 요청을 보내지 않게 합니다(fail-closed).
 */
class MlKitEntityDetector : PiiDetector {
    private val extractor: EntityExtractor = EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.KOREAN).build())
    @Volatile private var ready = false

    /** 앱 시작 시 미리 내려받아 첫 요청 지연을 줄입니다. */
    fun prepareAsync() { extractor.downloadModelIfNeeded().addOnSuccessListener { ready = true } }

    private fun ensureReady() {
        if (ready) return
        try { Tasks.await(extractor.downloadModelIfNeeded(), DOWNLOAD_WAIT_SECONDS, TimeUnit.SECONDS); ready = true }
        catch (e: Exception) {
            throw PrivacyBlocked("개인정보 보호 모델을 준비하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해주세요.", "entity_model_unavailable")
        }
    }

    override fun detect(texts: List<String>): List<List<PiiSpan>> {
        if (texts.isEmpty()) return emptyList()
        ensureReady()
        // 문장마다 호출하지 않고 구분자로 이어 붙여 한 번에 추출한 뒤 원래 위치로 나눈다.
        val starts = IntArray(texts.size)
        val joined = StringBuilder()
        texts.forEachIndexed { i, s -> if (i > 0) joined.append(SEPARATOR); starts[i] = joined.length; joined.append(s) }
        val annotations = Tasks.await(extractor.annotate(EntityExtractionParams.Builder(joined.toString()).build()), ANNOTATE_WAIT_SECONDS, TimeUnit.SECONDS)
        val out = List(texts.size) { mutableListOf<PiiSpan>() }
        for (a in annotations) {
            val kind = a.entities.firstNotNullOfOrNull { kindOf(it.type) } ?: continue
            var index = starts.indexOfLast { it <= a.start }
            if (index < 0) continue
            val begin = a.start - starts[index]
            val end = minOf(a.end - starts[index], texts[index].length)
            if (begin in 0 until end) out[index] += PiiSpan(begin, end, kind)
        }
        return out
    }

    private fun kindOf(type: Int): PiiKind? = when (type) {
        Entity.TYPE_ADDRESS -> PiiKind.ADDRESS
        Entity.TYPE_EMAIL -> PiiKind.EMAIL
        Entity.TYPE_PHONE -> PiiKind.PHONE
        Entity.TYPE_PAYMENT_CARD -> PiiKind.CARD
        Entity.TYPE_IBAN -> PiiKind.ACCOUNT
        Entity.TYPE_MONEY -> PiiKind.MONEY
        Entity.TYPE_TRACKING_NUMBER, Entity.TYPE_FLIGHT_NUMBER -> PiiKind.IDENTIFIER
        else -> null // 날짜·URL·ISBN은 화면 안내에 필요하고 개인 식별 위험이 낮다.
    }

    fun close() = extractor.close()

    private companion object {
        const val SEPARATOR = "\n\n"
        const val DOWNLOAD_WAIT_SECONDS = 8L
        const val ANNOTATE_WAIT_SECONDS = 5L
    }
}
