package com.example.aplassistmvp0.guide

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GuideTraceEvent(val requestId: Long, val time: Long, val elapsedMs: Long?, val stage: String, val fields: Map<String, String>)

/** 최근 10개 요청/최대 1500개 이벤트. 입력 원문·음성·API 키·API 원문은 저장하지 않는다. */
object GuideTrace {
    private val mutableEvents = MutableStateFlow<List<GuideTraceEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()
    private var file: AtomicFile? = null
    private data class TraceWrite(val epoch: Long, val events: List<GuideTraceEvent>)
    private val writes = Channel<TraceWrite>(Channel.CONFLATED)
    private val storageLock = Any()
    @Volatile private var storageEpoch = 0L
    internal fun initialize(context: Context) {
        if (file != null) return
        val storage = AtomicFile(File(context.filesDir, "guide-trace.json"))
        file = storage
        mutableEvents.value = runCatching {
            val bytes = storage.readFully()
            require(bytes.size <= 2_000_000)
            val data = JSONArray(bytes.toString(Charsets.UTF_8))
            (maxOf(0, data.length() - 1500) until data.length()).map { i ->
                val row = data.getJSONObject(i)
                val fields = row.getJSONObject("fields")
                GuideTraceEvent(row.getLong("requestId"), row.getLong("time"), row.optLong("elapsedMs").takeIf { !row.isNull("elapsedMs") },
                    row.getString("stage"), fields.keys().asSequence().associateWith { fields.getString(it) })
            }
        }.getOrDefault(emptyList())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (initial in writes) {
                // Keep every in-memory event, but persist the latest complete list at most
                // twice a second. Serializing/fsyncing the whole trace on every UI event
                // creates substantial allocation and disk work on an emulator or phone.
                delay(500L)
                var copy=initial
                while(true) copy=writes.tryReceive().getOrNull() ?: break
                synchronized(storageLock) {
                    if(copy.epoch==storageEpoch) {
                        var stream: java.io.FileOutputStream? = null
                        try { stream = storage.startWrite(); stream.write(encode(copy.events,false).toByteArray()); storage.finishWrite(stream) }
                        catch (_: Exception) { stream?.let(storage::failWrite) }
                    }
                }
            }
        }
    }
    @Synchronized internal fun record(stage: String, fields: Map<String, String> = emptyMap()) {
        recordFor(GuideTiming.traceId(), stage, fields)
    }
    @Synchronized internal fun recordFor(requestId: Long, stage: String, fields: Map<String, String> = emptyMap()) {
        if (file == null) return // JVM 시험에서는 Android 시계·저장소를 호출하지 않는다.
        if (requestId != GuideTiming.traceId()) return // 중단된 요청의 늦은 콜백을 새 요청에 섞지 않는다.
        val safe = fields.mapValues { (key, value) ->
            if (key in setOf("confidence", "margin")) value.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1.0..1.0 }?.toString().orEmpty()
            else if (key in setOf("snapshotId", "windowId", "durationMs", "upstreamMs", "inputTokens", "outputTokens", "costUsd", "node", "step", "elapsedMs", "candidates", "nodes", "options", "fingerprint", "previousFingerprint", "bounds", "overlap", "eventType", "scrollDeltaX", "scrollDeltaY")) value.take(400)
            else value.lineSequence().take(120).joinToString("\n") { GuideHistoryPolicy.redact(it) }.take(6000)
        }
        val previous = mutableEvents.value.lastOrNull { it.requestId == requestId && it.stage == stage }
        if (stage in setOf("same_screen", "control_event") && previous?.fields == safe) return
        val entry = GuideTraceEvent(requestId, System.currentTimeMillis(), GuideTiming.elapsedMillis(), stage, safe)
        val all = mutableEvents.value + entry
        val sessions = all.asReversed().map { it.requestId }.distinct().take(10).toSet()
        // 한글 UTF-8 및 JSON 여백을 포함해 재시작 때 읽을 수 있는 크기 안에 유지한다.
        var budget = 1_800_000
        val retained = all.asReversed().filter { it.requestId in sessions }.take(1500).takeWhile {
            budget -= 600 + it.fields.entries.sumOf { field -> (field.key.length + field.value.length) * 3 }
            budget >= 0
        }.asReversed()
        mutableEvents.value = retained
        writes.trySend(TraceWrite(storageEpoch,retained))
    }
    @Synchronized fun clear() {
        storageEpoch++
        mutableEvents.value = emptyList()
        // A queued write from before deletion must never restore cleared records.
        synchronized(storageLock) { file?.delete() }
        writes.trySend(TraceWrite(storageEpoch,emptyList()))
    }
    fun text(requestId: Long? = null): String = mutableEvents.value.filter { requestId == null || it.requestId == requestId }.joinToString("\n\n") {
        buildString {
            append("+${it.elapsedMs ?: 0}ms · ${stageLabel(it.stage)}")
            it.fields.forEach { (key, value) -> append("\n$key: $value") }
        }
    }
    fun json(): String = encode(mutableEvents.value)
    private fun encode(events: List<GuideTraceEvent>, pretty: Boolean = true): String {
        val data=JSONArray().apply { events.forEach {
        put(JSONObject().put("requestId", it.requestId).put("time", it.time).put("elapsedMs", it.elapsedMs ?: JSONObject.NULL)
            .put("stage", it.stage).put("fields", JSONObject(it.fields)))
        } }
        return if(pretty) data.toString(2) else data.toString()
    }
    internal fun stageLabel(stage: String) = when (stage) {
        "screen_observed" -> "화면·상태 수집"; "user_action" -> "사용자 조작 감지"; "state_result" -> "조작 결과 확인"
        "capture_complete" -> "화면 수집 시간·유효성 검사"
        "app_opening_screen" -> "앱 열기 화면 근거·선택 경로"
        "highlight_visible" -> "강조 테두리 실제 표시"
        "same_screen" -> "재요청 생략 근거"; "decision_request" -> "AI에 전달한 목표·후보·상태"
        "decision_selected" -> "AI 선택 결과"; "api_response" -> "API 시간·사용량·비용"
        "decision_guard" -> "선택 검증·보정"; "comparison_response" -> "모델 비교 시간·사용량·비용"
        "guidance" -> "검증한 안내 표시"; "overlay" -> "안내 박스 배치"; "tts_done" -> "음성 종료"
        "tts_start" -> "음성 시작"; "tts_error" -> "음성 재생 실패"; "phase" -> "안내 처리 상태"
        "control_event" -> "입력·포커스 이벤트"; "local_decision" -> "기기 내 안내 규칙"
        "scroll_ignored" -> "안내와 무관한 영역의 스크롤 · 재판단 생략"
        "partial_screen" -> "전환 중 부분 수집 재확인"
        "vision_capture" -> "이미지 보완이 필요한 이유"
        "vision_capture_unavailable" -> "이미지 수집이 중단된 이유"
        "vision_request" -> "가린 화면으로 추가 판단"
        "vision_result" -> "이미지 보완 판단 결과"
        "vision_skipped" -> "이미지 보완 생략"
        "hybrid_review" -> "불확실한 Decisions 결과를 Chat으로 독립 재확인"
        "hybrid_result" -> "Chat 재확인 선택 결과"
        "hybrid_unavailable" -> "Chat 재확인을 사용할 수 없음"
        "external_wait" -> "시스템 선택 뒤 원래 작업 복귀 대기"
        else -> stage
    }
}
