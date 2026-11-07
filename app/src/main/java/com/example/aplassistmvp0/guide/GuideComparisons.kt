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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GuideComparisonCase(val id: String, val time: Long, val app: String, val goal: String,
    val step: Int, val input: String, val choices: String) {
    fun labels(): Map<String, String> {
        val data = JSONArray(choices)
        return (0 until data.length()).associate { i -> data.getJSONObject(i).let {
            it.getString("value") to it.getString("description").substringBefore(" (역할=").take(100)
        } }
    }
}
data class GuideComparisonResult(val backend: String, val choice: String, val message: String,
    val durationMs: Long, val inputTokens: Long, val outputTokens: Long, val costUsd: Double,
    val rawChoice: String = choice, val error: String = "", val upstreamMs: Long? = null, val transport: String = "")
data class GuideComparisonRun(val id: String, val caseId: String, val time: Long, val expected: String,
    val results: List<GuideComparisonResult>)
data class GuideComparisonState(val cases: List<GuideComparisonCase> = emptyList(), val runs: List<GuideComparisonRun> = emptyList())

/** 현재 안내의 공개 후보·상태를 최근 10개 보존한다. 키·이미지·입력값은 포함하지 않는다. */
object GuideComparisons {
    private val mutable = MutableStateFlow(GuideComparisonState())
    val state = mutable.asStateFlow()
    private var storage: AtomicFile? = null
    private val writes = Channel<String>(Channel.CONFLATED)
    @Synchronized fun initialize(context: Context) {
        if (storage != null) return
        val file = AtomicFile(File(context.applicationContext.filesDir, "guide-comparisons.json"))
        storage = file
        mutable.value = runCatching {
            val bytes = file.readFully(); require(bytes.size <= 1_500_000)
            decode(JSONObject(bytes.toString(Charsets.UTF_8)))
        }.getOrDefault(GuideComparisonState())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (text in writes) {
                var stream: java.io.FileOutputStream? = null
                try { stream = file.startWrite(); stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (_: Exception) { stream?.let(file::failWrite) }
            }
        }
    }
    @Synchronized internal fun capture(input: JSONObject, choices: JSONArray): GuideComparisonCase {
        check(input.toString().toByteArray(Charsets.UTF_8).size + choices.toString().toByteArray(Charsets.UTF_8).size <= 100_000) {
            "화면 정보가 너무 많아 비교용 화면을 저장하지 못했습니다."
        }
        val item = GuideComparisonCase(UUID.randomUUID().toString(), System.currentTimeMillis(), input.optString("appLabel"),
            input.getString("goal"), input.getInt("step"), input.toString(), choices.toString())
        val cases = (listOf(item) + mutable.value.cases).take(10)
        publish(GuideComparisonState(cases, mutable.value.runs.filter { run -> cases.any { it.id == run.caseId } }))
        return item
    }
    @Synchronized internal fun save(run: GuideComparisonRun) {
        if (mutable.value.cases.none { it.id == run.caseId }) return
        publish(mutable.value.copy(runs = (listOf(run) + mutable.value.runs).take(30)))
    }
    @Synchronized fun clear() = publish(GuideComparisonState())
    fun json() = encode(mutable.value).toString(2)
    private fun publish(value: GuideComparisonState) { mutable.value = value; writes.trySend(encode(value).toString()) }
    private fun encode(value: GuideComparisonState) = JSONObject().put("schemaVersion", 1)
        .put("cases", JSONArray().apply { value.cases.forEach { c -> put(JSONObject().put("id", c.id).put("time", c.time)
            .put("app", c.app).put("goal", c.goal).put("step", c.step).put("input", JSONObject(c.input)).put("choices", JSONArray(c.choices))) } })
        .put("runs", JSONArray().apply { value.runs.forEach { run -> put(JSONObject().put("id", run.id).put("caseId", run.caseId)
            .put("time", run.time).put("expected", run.expected).put("results", JSONArray().apply { run.results.forEach { r ->
                put(JSONObject().put("backend", r.backend).put("choice", r.choice).put("rawChoice", r.rawChoice).put("message", r.message)
                    .put("durationMs", r.durationMs).put("inputTokens", r.inputTokens).put("outputTokens", r.outputTokens)
                    .put("costUsd", r.costUsd).put("error", r.error).put("upstreamMs", r.upstreamMs ?: JSONObject.NULL).put("transport", r.transport))
            } })) } })
    private fun decode(data: JSONObject): GuideComparisonState {
        require(data.getInt("schemaVersion") == 1)
        val cases = data.getJSONArray("cases").let { rows -> (0 until minOf(10, rows.length())).map { i ->
            val c = rows.getJSONObject(i)
            require(c.getJSONObject("input").toString().toByteArray().size + c.getJSONArray("choices").toString().toByteArray().size <= 100_000)
            GuideComparisonCase(c.getString("id"), c.getLong("time"), c.getString("app"), c.getString("goal"),
                c.getInt("step"), c.getJSONObject("input").toString(), c.getJSONArray("choices").toString())
        } }
        val runs = data.getJSONArray("runs").let { rows -> (0 until minOf(30, rows.length())).map { i ->
            val r = rows.getJSONObject(i)
            val results = r.getJSONArray("results").let { entries -> (0 until minOf(GuideBackend.entries.size, entries.length())).map { j ->
                val e = entries.getJSONObject(j)
                GuideComparisonResult(e.getString("backend"), e.getString("choice"), e.getString("message"),
                    e.getLong("durationMs"), e.getLong("inputTokens"), e.getLong("outputTokens"), e.getDouble("costUsd"),
                    e.getString("rawChoice"), e.getString("error"),
                    if (e.isNull("upstreamMs") || !e.has("upstreamMs")) null else e.getLong("upstreamMs"), e.optString("transport"))
            } }
            GuideComparisonRun(r.getString("id"), r.getString("caseId"), r.getLong("time"), r.optString("expected"), results)
        } }.filter { run -> cases.any { it.id == run.caseId } }
        return GuideComparisonState(cases, runs)
    }
}
