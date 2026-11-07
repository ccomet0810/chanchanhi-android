package com.example.aplassistmvp0.guide

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Small bounded local notebook. Android app backup is disabled; no screen images or raw trees. */
object ExperienceStore {
    private var prefs: SharedPreferences? = null
    private val mutable = MutableStateFlow(ExperienceState())
    val state = mutable.asStateFlow()
    internal val recorder = ExperienceRecorder({ mutable.value }, ::save)

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("guide-experiences-v1", Context.MODE_PRIVATE)
        mutable.value = runCatching { decode(prefs!!.getString("notebook", "{}")!!) }.getOrDefault(ExperienceState())
    }
    fun synchronize(apps: List<TargetApp>) = save(ExperiencePolicy.synchronize(mutable.value, apps))
    fun forget(id: String) = save(mutable.value.copy(experiences = mutable.value.experiences.filter { it.id != id }))
    fun clear() { recorder.clear(); save(ExperienceState()) }
    fun feature(app: TargetApp, text: String, source: String) {
        val clean = ExperiencePolicy.safeText(text)
        if (clean.isBlank()) return
        val old = mutable.value.apps.firstOrNull { it.packageName == app.packageName }
            ?: AppKnowledge(app.packageName, app.label, app.revision)
        val existing = old.capabilities.firstOrNull { it.text == clean }
        val rank = mapOf("inferred" to 0, "observed" to 1, "user" to 2, "confirmed" to 3)
        if (existing != null && (rank[existing.source] ?: 0) > (rank[source] ?: 0)) return
        val updated = old.copy(capabilities = (listOf(AppCapability(clean, source, System.currentTimeMillis(), app.revision)) +
            old.capabilities.filter { it.text != clean }).take(40))
        save(mutable.value.copy(apps = listOf(updated) + mutable.value.apps.filter { it.packageName != app.packageName }))
    }
    internal fun observeFunctions(screen: com.example.aplassistmvp0.state.ScreenSnapshot) {
        val old = mutable.value.apps.firstOrNull { it.packageName == screen.packageName } ?: return
        val current = old.capabilities.filter { it.revision == old.revision || it.source in setOf("confirmed", "user") }.map { it.text }.toSet()
        val observed = GuidePolicy.candidates(screen).filter { it.clickable }.mapNotNull { ExperiencePolicy.control(it, "click") }
            .map { it.label }.distinct().filter { it !in current && it !in setOf("닫기", "뒤로", "확인", "취소", "다음", "홈") }.take(10)
        if (observed.isEmpty()) return
        val protected = old.capabilities.filter { it.source in setOf("confirmed", "user") }
        val updated = old.copy(capabilities = (protected + observed.map {
            AppCapability(it, "observed", System.currentTimeMillis(), old.revision) } + old.capabilities)
            .distinctBy { it.text }.take(40))
        save(mutable.value.copy(apps = mutable.value.apps.map { if (it.packageName == old.packageName) updated else it }))
    }
    internal fun save(value: ExperienceState) {
        if (value == mutable.value) return
        mutable.value = value
        prefs?.edit { putString("notebook", encode(value)) }
    }
    internal fun encode(value: ExperienceState): String {
        val apps = JSONArray()
        value.apps.forEach { app -> apps.put(JSONObject().put("package", app.packageName).put("label", app.label)
            .put("revision", app.revision).put("features", JSONArray().also { features -> app.capabilities.forEach {
                features.put(JSONObject().put("text", it.text).put("source", it.source).put("time", it.checkedAt).put("revision", it.revision))
            } })) }
        val routes = JSONArray()
        value.experiences.forEach { route -> routes.put(JSONObject().put("id", route.id).put("package", route.packageName)
            .put("app", route.appLabel).put("goal", route.goal).put("revision", route.revision).put("time", route.checkedAt)
            .put("result", route.result).put("successes", route.successes).put("failures", route.failures)
            .put("review", route.needsReview).put("reusable", route.reusable).put("api", route.apiRequests)
            .put("cost", route.estimatedCostUsd).put("hits", route.memoryHits).put("elapsed", route.elapsedMillis)
            .put("steps", JSONArray().also { steps -> route.steps.forEach { step -> steps.put(JSONObject()
                .put("from", step.from).put("to", step.to).put("label", step.control.label)
                .put("viewId", step.control.viewId).put("role", step.control.role).put("action", step.control.action)
                .put("message", step.message).put("continuation", step.continuation ?: JSONObject.NULL)) } })) }
        return JSONObject().put("schema", 1).put("apps", apps).put("routes", routes).toString()
    }
    internal fun decode(raw: String): ExperienceState {
        require(raw.length <= 2_000_000)
        val root = JSONObject(raw)
        if (root.optInt("schema") != 1) return ExperienceState()
        val apps = root.optJSONArray("apps")
        val routes = root.optJSONArray("routes")
        return ExperienceState(
            apps = (0 until minOf(apps?.length() ?: 0, 500)).mapNotNull { i -> runCatching {
                val app = apps!!.getJSONObject(i)
                val features = app.getJSONArray("features")
                AppKnowledge(app.getString("package"), ExperiencePolicy.safeText(app.getString("label"), 80), app.getString("revision"),
                    (0 until minOf(features.length(), 40)).map { j -> features.getJSONObject(j).let {
                        AppCapability(ExperiencePolicy.safeText(it.getString("text")), it.getString("source"), it.getLong("time"), it.optString("revision")) } })
            }.getOrNull() },
            experiences = (0 until minOf(routes?.length() ?: 0, ExperiencePolicy.LIMIT)).mapNotNull { i -> runCatching {
                val item = routes!!.getJSONObject(i)
                val steps = item.getJSONArray("steps")
                Experience(item.getString("id"), item.getString("package"), ExperiencePolicy.safeText(item.getString("app"), 80),
                    ExperiencePolicy.safeText(item.getString("goal"), 200), item.getString("revision"), item.getLong("time"),
                    (0 until minOf(steps.length(), 16)).map { j -> steps.getJSONObject(j).let {
                        MemoryStep(it.getString("from"), it.getString("to"), MemoryControl(ExperiencePolicy.safeText(it.getString("label")),
                            it.getString("viewId").take(200), it.getString("role").take(160), it.getString("action")),
                            ExperiencePolicy.safeText(it.getString("message"), 200), it.optString("continuation").takeIf { s -> s == "find_target" }) } },
                    item.getString("result"), item.getInt("successes"), item.getInt("failures"), item.getBoolean("review"),
                    item.getBoolean("reusable"), item.optInt("api"), item.optDouble("cost", 0.0), item.optInt("hits"), item.optLong("elapsed"))
            }.getOrNull() },
        )
    }
}
