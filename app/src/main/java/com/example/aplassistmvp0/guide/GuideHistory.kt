package com.example.aplassistmvp0.guide

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.aplassistmvp0.state.AssistStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class GuideHistoryEntry(
    val timeMillis: Long,
    val requestId: Long,
    val goal: String,
    val appLabel: String,
    val phase: String,
    val message: String,
    val step: Int,
    val action: String?,
    val elapsedMillis: Long?,
    val apiMillis: Long?,
    val candidateCount: Int?,
    val candidateLabels: List<String> = emptyList(),
    val observedPackage: String? = null,
)

/** 최근 상태 50개만 기기 내부에 보관한다. 화면 트리·입력값·음성·API 원문은 기록하지 않는다. */
object GuideHistory {
    private var prefs: SharedPreferences? = null
    private val mutableEntries = MutableStateFlow<List<GuideHistoryEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    internal fun initialize(context: Context) {
        if (prefs != null) return
        val storage = context.applicationContext.getSharedPreferences("guide-history", Context.MODE_PRIVATE)
        prefs = storage
        mutableEntries.value = runCatching {
            val data = JSONArray(storage.getString("entries", "[]"))
            (0 until minOf(data.length(), GuideHistoryPolicy.LIMIT)).map { index ->
                val item = data.getJSONObject(index)
                GuideHistoryEntry(
                    item.getLong("time"), item.getLong("requestId"),
                    GuideHistoryPolicy.redact(item.getString("goal")),
                    GuideHistoryPolicy.redact(item.getString("app")), item.getString("phase"),
                    GuideHistoryPolicy.redact(item.getString("message")), item.getInt("step"),
                    if (item.isNull("action")) null else item.getString("action"),
                    if (item.isNull("elapsed")) null else item.getLong("elapsed"),
                    if (item.isNull("api")) null else item.getLong("api"),
                    if (item.isNull("candidates")) null else item.getInt("candidates"),
                    item.optJSONArray("labels")?.let { labels -> (0 until minOf(40, labels.length()))
                        .map { GuideHistoryPolicy.redact(labels.optString(it)).take(80) } }.orEmpty(),
                    item.optString("observed").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun record(state: GuideUiState) {
        if (prefs == null || state.goal.isBlank() || state.phase == "idle") return
        val snapshot = AssistStateRepository.state.value.snapshot?.takeIf { it.packageName == state.targetPackageName }
        val candidates = snapshot?.let(GuidePolicy::candidates)
        val entry = GuideHistoryEntry(
            timeMillis = System.currentTimeMillis(), requestId = GuideTiming.traceId(),
            goal = GuideHistoryPolicy.redact(state.goal),
            appLabel = GuideHistoryPolicy.redact(state.targetAppLabel.ifBlank { state.targetPackageName }),
            phase = state.phase, message = GuideHistoryPolicy.redact(state.message),
            step = state.step, action = state.currentAction,
            elapsedMillis = GuideTiming.elapsedMillis(),
            apiMillis = GuideTiming.lastApiMillis(),
            candidateCount = candidates?.size,
            candidateLabels = if (state.currentAction == "manual" || state.phase in setOf("error", "paused"))
                candidates.orEmpty().map { GuideHistoryPolicy.redact(GuidePolicy.label(it)).take(80) }.distinct().take(40) else emptyList(),
            observedPackage = state.foregroundEvidence ?: AssistStateRepository.state.value.foregroundPackage,
        )
        val updated = GuideHistoryPolicy.append(mutableEntries.value, entry)
        if (updated === mutableEntries.value) return
        mutableEntries.value = updated
        persist()
    }

    fun clear() {
        mutableEntries.value = emptyList()
        prefs?.edit { remove("entries") }
    }

    /** コピーは利用者の操作時だけ行う。外部への共有・アップロードは行わない。 */
    fun text(): String = mutableEntries.value.joinToString("\n\n") { entry ->
        val time = java.time.Instant.ofEpochMilli(entry.timeMillis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
        buildString {
            append("$time · ${entry.appLabel} · ${GuideHistoryPolicy.phaseLabel(entry.phase, entry.action)}")
            append("\n요청: ${entry.goal}\n응답: ${entry.message}")
            append("\n단계: ${entry.step}")
            entry.elapsedMillis?.let { append(" · 요청 제출 후 ${it}ms") }
            entry.apiMillis?.let { append(" · 최근 API ${it}ms") }
            entry.candidateCount?.let { append(" · 화면 후보 ${it}개") }
            entry.observedPackage?.let { append("\n감지된 앱: $it") }
            if (entry.candidateLabels.isNotEmpty()) append("\n읽은 항목: ${entry.candidateLabels.joinToString(" / ")}")
        }
    }

    private fun persist() {
        val data = JSONArray()
        mutableEntries.value.forEach { entry ->
            data.put(JSONObject().put("time", entry.timeMillis).put("requestId", entry.requestId)
                .put("goal", entry.goal).put("app", entry.appLabel).put("phase", entry.phase)
                .put("message", entry.message).put("step", entry.step)
                .put("action", entry.action ?: JSONObject.NULL).put("elapsed", entry.elapsedMillis ?: JSONObject.NULL)
                .put("api", entry.apiMillis ?: JSONObject.NULL).put("candidates", entry.candidateCount ?: JSONObject.NULL)
                .put("labels", JSONArray(entry.candidateLabels)).put("observed", entry.observedPackage ?: JSONObject.NULL))
        }
        prefs?.edit { putString("entries", data.toString()) }
    }
}
