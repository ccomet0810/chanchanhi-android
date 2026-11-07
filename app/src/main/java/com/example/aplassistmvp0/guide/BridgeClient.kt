package com.example.aplassistmvp0.guide

import android.content.Context
import com.example.aplassistmvp0.state.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BridgeStatus(val ready: Boolean, val message: String)
data class GuideDecision(val action: String, val targetNodeId: Int?, val message: String, val continuation: String? = null,
    val operation: String? = null, val targetValue: Float? = null, val source: String = "ai", val visionVerified: Boolean = false,
    val evidenceNodeIds: List<Int> = emptyList())

/** 화면 수집·판단·검증은 공통이다. 구독 모드는 앱 분류도 구독으로 처리하며 유료 전환하지 않는다. */
class BridgeClient internal constructor(context: Context, private val reviewWithChat: Boolean = false,
    private val captureVision: suspend (ScreenSnapshot) -> GuideVisionFrame?) {
    constructor(context: Context) : this(context, false, { screen ->
        com.example.aplassistmvp0.accessibility.AssistAccessibilityService.captureVision(screen)
    })
    /** Explicit evaluation strategy; ordinary subscription/Decisions selections never switch providers. */
    internal constructor(context: Context, reviewWithChat: Boolean) : this(context, reviewWithChat, { screen ->
        com.example.aplassistmvp0.accessibility.AssistAccessibilityService.captureVision(screen)
    })
    @Volatile private var reviewReady = false
    @Volatile private var primaryReady = true
    private val visionBudget = GuideVisionBudget()
    @Volatile private var progress = GuideProgressContext()
    internal fun updateProgress(value: GuideProgressContext) { progress = value }
    private val application = context.applicationContext
    private val keys = DirectApiKeyStore(application)
    private val usage = DirectUsageStore(application)
    private val settings = GuideModelSettings(application)
    private val connections = BridgeConnectionStore(application)
    private val subscription = SubscriptionBridgeClient(connections)
    @Volatile private var approvedSubscription: BridgeConnectionConfig? = null
    @Volatile private var backend = GuideBackend.DECISIONS_LUNA
    private data class Metrics(val durationMs: Long = 0, val input: Long = 0, val output: Long = 0, val cost: Double = 0.0,
        val upstreamMs: Long? = null, val transport: String = "direct_api")
    @Volatile private var lastMetrics = Metrics()
    internal fun useModel() = backend
    @Volatile private var approved: DirectApiKeyStore.Credential? = null

    /** 유료 모드는 로컬 키·비용 기록, 구독 모드는 추론 없는 PC 연결 상태를 확인한다. */
    suspend fun status(): BridgeStatus = withContext(Dispatchers.IO) {
        approved = null
        approvedSubscription = null
        visionBudget.reset()
        backend = settings.current()
        reviewReady = false
        primaryReady = true
        try {
            if (backend.remote) {
                val config = connections.load()
                val result = subscription.status(config, backend.model, subscription = backend.subscription)
                if (reviewWithChat) {
                    check(backend == GuideBackend.SERVER_DECISIONS_LUNA) { "혼합 검사는 Decisions 서버 선택에서만 사용합니다." }
                    val reviewer = subscription.status(config, GuideBackend.SUBSCRIPTION_SOL.model, subscription = true)
                    primaryReady = result.ready
                    reviewReady = reviewer.ready
                    if (result.ready || reviewer.ready) approvedSubscription = config
                    return@withContext BridgeStatus(result.ready || reviewer.ready,
                        if (result.ready || reviewer.ready) "혼합 검사 연결 준비 완료" else result.message)
                }
                if (result.ready) approvedSubscription = config
                return@withContext result
            }
            val credential = keys.load()
                ?: return@withContext BridgeStatus(false, "API 키 설정에서 OpenAI 키를 등록해주세요.")
            if (usage.current().spentUsd >= DirectDecisionPolicy.DAILY_LIMIT_USD)
                return@withContext BridgeStatus(false, "오늘의 AI 안내 한도에 도달했습니다. 내일 다시 사용할 수 있습니다.")
            approved = credential
            BridgeStatus(true, "API 키가 등록되어 있습니다. 첫 안내에서 API 인증을 확인합니다.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            BridgeStatus(false, if (backend.remote) (error as? IllegalStateException)?.message
                ?: "ChatGPT 구독 연결 설정을 확인해주세요." else "저장한 API 키와 사용량을 읽지 못했습니다. API 키 설정을 확인해주세요.")
        }
    }

    /** 의미 정보 우선. 화면 판단이 부족할 때만 같은 화면의 가린 이미지를 한 번 추가한다. */
    suspend fun decide(goal: String, snapshot: ScreenSnapshot, step: Int): GuideDecision = withContext(Dispatchers.IO) {
        sessionUnchanged()
        require(step >= 0 && goal.trim().length in 1..500)
        require(GuidePolicy.isValidTargetPackage(snapshot.packageName) &&
            snapshot.packageName != application.packageName && snapshot.packageName != "com.android.systemui")
        if (DirectDecisionPolicy.sensitiveGoal(goal)) return@withContext GuideDecision("manual", null,
            "비밀번호·인증번호 입력, 송금·결제·삭제 단계는 내용을 확인하며 직접 진행해주세요.", source = "local")
        val candidates = GuidePolicy.candidates(snapshot).take(120)
        val previousProgress = progress
        NavigationDecisionPolicy.beforeModel(goal, snapshot, previousProgress)?.takeUnless {
            it.action == "manual" && it.continuation == "dismiss_popup"
        }?.takeUnless { d -> d.operation!="fill_input" && candidates.firstOrNull { it.id == d.targetNodeId }?.let(GuideVisionPolicy::generic) == true }?.let {
            GuideTrace.record("local_decision", mapOf("reason" to it.message, "action" to it.action))
            return@withContext it.copy(source = "local")
        }
        val context = (candidates + GuidePolicy.contextNodes(snapshot).filter { it !in candidates }.take(30)).distinctBy { it.id }
        val nodes = JSONArray()
        val choices = JSONArray()
        context.forEach { node ->
            val label = GuidePolicy.label(node).take(240)
            nodes.put(JSONObject().put("id", node.id).put("label", label)
                .put("className", node.className?.take(160) ?: "")
                .put("viewId", node.viewIdResourceName?.take(200) ?: "")
                .put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                .put("clickable", node.clickable).put("scrollable", node.scrollable)
                .put("state", GuideActionPolicy.describe(node)))
            if (node in candidates && GuideActionPolicy.option(node, goal, previousProgress)) choices.put(JSONObject()
                .put("value", "node_${node.id}")
                .put("description", "$label (역할=${node.className?.take(160).orEmpty()}, ${GuideActionPolicy.describe(node)})"))
        }
        val actionChoiceCount = choices.length()
        choices.put(JSONObject().put("value", "manual")
            .put("description", "적절한 후보가 없거나 다음 단계를 판단할 정보가 불충분함"))
        choices.put(JSONObject().put("value", "ask").put("description", "본인의 주소나 메시지 유형처럼 사용자에게 선택을 요청해야 함"))
        if(DirectDecisionPolicy.navigationGoal(goal) && actionChoiceCount > 0) choices.put(JSONObject().put("value","confirm")
            .put("description","요청한 기능·정보의 실제 화면에 이미 도착함. 추가 조작 대신 사용자에게 도착 여부 확인. 진입 링크만 보이는 것은 제외"))
        val input = JSONObject().put("goal", GuideHistoryPolicy.redact(goal.trim())).put("step", step)
            .put("snapshotId", snapshot.snapshotId).put("packageName", snapshot.packageName)
            .put("separateWindow", snapshot.blockingPopup).put("dismissiblePopup", NavigationDecisionPolicy.dismissiblePopup(snapshot,goal))
            .put("appLabel", runCatching {
                val manager = application.packageManager
                manager.getApplicationLabel(manager.getApplicationInfo(snapshot.packageName, 0)).toString()
            }.getOrDefault(snapshot.packageName).take(80)).put("nodes", nodes)
            .put("previousAction", previousProgress.previousAction).put("previousTarget", previousProgress.previousTarget).put("previousOutcome", previousProgress.outcome)
        snapshot.viewport?.let { input.put("viewport", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
        var selectedBackend = if (reviewWithChat && !primaryReady && reviewReady) GuideBackend.SUBSCRIPTION_SOL else backend
        val waits = GuideActivityWaitPolicy.options(snapshot, context, minOf(32, (200 - choices.length()).coerceAtLeast(0)))
        fun choicesWithWait(count: Int): JSONArray = JSONArray((0 until choices.length()).map { choices.getJSONObject(it) }).also { all ->
            waits.values.take(count).forEach { all.put(JSONObject().put("value", it.value).put("description", it.description)) }
        }
        val waitCount = GuideActivityWaitPolicy.admittedCount(waits.values.size) { count ->
            val admitted = choicesWithWait(count)
            val primary = GuideModelCodec.payload(selectedBackend, input, admitted).toString().toByteArray(Charsets.UTF_8).size
            if (reviewWithChat && reviewReady) maxOf(primary,
                GuideModelCodec.payload(GuideBackend.SUBSCRIPTION_SOL, input, admitted).toString().toByteArray(Charsets.UTF_8).size)
            else primary
        }
        waits.values.take(waitCount).forEach { choices.put(JSONObject().put("value", it.value).put("description", it.description)) }
        if (actionChoiceCount == 0 && waitCount == 0) return@withContext manualMessage(goal, candidates).copy(source = "local")
        GuideTrace.record("activity_wait_options", mapOf("offered" to waitCount.toString(),
            "limited" to (waits.truncated || waitCount < waits.values.size).toString()))
        GuideComparisons.capture(input, choices)
        GuideTrace.record("decision_request", mapOf("step" to step.toString(), "goal" to goal, "backend" to selectedBackend.name,
            "model" to selectedBackend.model, "options" to (0 until choices.length()).count { choices.getJSONObject(it).getString("value").startsWith("node_") }.toString(),
            "context" to context.joinToString("\n") { "${GuidePolicy.label(it)}: ${GuideActionPolicy.describe(it)}" }))
        val bytes = GuideModelCodec.payload(selectedBackend, input, choices).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65_536) { "화면 정보가 너무 많습니다. 다른 화면에서 다시 요청해주세요." }
        val response = execute(bytes, selectedBackend)
        var answer = GuideModelCodec.answer(selectedBackend, response, choices)
        var imageUsed = false
        fun reasonFor(value: GuideModelCodec.Answer): String? {
            val wait = GuideActivityWaitPolicy.selection(value.choice)
            val chosen = if (wait != null) context.firstOrNull { it.id == wait.nodeId }
                else candidates.firstOrNull { value.choice == "node_${it.id}" }
            return if (wait != null && GuideActivityWaitPolicy.decision(value.choice, snapshot) == null ||
                wait == null && chosen != null && GuidePolicy.resolveDecisionNode(snapshot, chosen.id) == null) "ambiguous_node"
                else GuideVisionPolicy.reason(value.choice, value.confidenceValue, value.margin, chosen)
        }
        var reason = reasonFor(answer)
        if (selectedBackend == GuideBackend.SERVER_DECISIONS_LUNA && reason in setOf("low_confidence", "close_choices", "missing_screen_information")) {
            review(input,choices,null,reason!!)?.let {
                answer = it; selectedBackend = GuideBackend.SUBSCRIPTION_SOL; reason = reasonFor(it)
            }
        }
        if (reason != null && snapshot.viewport != null) {
            if (!visionBudget.claim(snapshot)) {
                GuideTrace.record("vision_skipped", mapOf("reason" to "화면당 1회·목표당 3회 제한", "trigger" to reason))
                return@withContext NavigationDecisionPolicy.probeAfterMissing(goal, snapshot) ?: uncertainMessage()
            }
            GuideTrace.record("vision_capture", mapOf("reason" to reason, "confidence" to answer.confidence,
                "margin" to answer.margin?.toString().orEmpty()))
            GuideController.onVisionCheck()
            val image = captureVision(snapshot)
            if (image == null) {
                GuideTrace.record("vision_skipped", mapOf("reason" to "화면 변경·보안 화면·키보드·캡처 불가"))
                return@withContext GuideDecision("manual", null,
                    "버튼을 구분할 화면을 확인하지 못했어요. 키보드가 열렸다면 닫고, 현재 보이는 메뉴 이름을 알려주세요.", "wait_controls")
            }
            sessionUnchanged()
            val visualInput = JSONObject(input.toString()).put("visualRetry", true).put("visualReason", reason)
            val visualBytes = GuideModelCodec.payload(selectedBackend, visualInput, choices, image).toString().toByteArray(Charsets.UTF_8)
            check(visualBytes.size <= GuideVisionPolicy.MAX_REQUEST_BYTES) { "화면 정보가 너무 커 이미지 확인을 중단했습니다." }
            GuideTrace.record("vision_request", mapOf("reason" to reason, "width" to image.width.toString(),
                "height" to image.height.toString(), "redactions" to image.redactions.toString(), "bytes" to visualBytes.size.toString()))
            answer = GuideModelCodec.answer(selectedBackend, execute(visualBytes, selectedBackend), choices)
            imageUsed = true
            GuideTrace.record("vision_result", mapOf("choice" to answer.choice, "confidence" to answer.confidence,
                "margin" to answer.margin?.toString().orEmpty()))
            if (selectedBackend == GuideBackend.SERVER_DECISIONS_LUNA && answer.choice != "ask" &&
                (answer.choice == "manual" || answer.confidenceValue?.let { it < GuideVisionPolicy.MIN_CONFIDENCE } == true ||
                    answer.margin?.let { it < GuideVisionPolicy.MIN_MARGIN } == true)) {
                review(visualInput,choices,image,"uncertain_after_image")?.let {
                    answer = it; selectedBackend = GuideBackend.SUBSCRIPTION_SOL
                }
            }
            if (answer.choice == "manual" || (answer.choice != "ask" &&
                    ((answer.confidenceValue?.let { it < GuideVisionPolicy.MIN_CONFIDENCE } == true) ||
                     (answer.margin?.let { it < GuideVisionPolicy.MIN_MARGIN } == true)))) return@withContext (
                NavigationDecisionPolicy.probeAfterMissing(goal, snapshot) ?: uncertainMessage())
        }
        GuideTrace.record("decision_selected", mapOf("choice" to answer.choice, "confidence" to answer.confidence, "backend" to selectedBackend.name))
        if (GuideActivityWaitPolicy.selection(answer.choice) != null) {
            val wait = GuideActivityWaitPolicy.decision(answer.choice, snapshot) ?: return@withContext uncertainMessage()
            return@withContext wait.copy(source = when {
                reviewWithChat && selectedBackend.subscription -> if (imageUsed) "hybrid_chat_vision" else "hybrid_chat"
                imageUsed -> "ai_vision"; else -> "ai"
            }, visionVerified = imageUsed)
        }
        if(answer.choice=="confirm") return@withContext GuideDecision("manual",null,
            "요청하신 화면을 열었어요.","wait_confirmation", evidenceNodeIds = answer.evidenceNodeIds.filter { id -> context.any { it.id == id } })
        if (answer.choice == "ask") return@withContext GuideDecision("manual", null,
            answer.message.ifBlank { questionMessage(choices) }, continuation = "wait_choice")
        val selected = DirectDecisionPolicy.selectedId(answer.choice,
            candidates.associate { it.id to GuideActionPolicy.option(it, goal, previousProgress) })
            ?: return@withContext NavigationDecisionPolicy.probeAfterMissing(goal, snapshot)
                ?: manualMessage(goal, candidates).let { if (answer.message.isBlank()) it else it.copy(message = answer.message) }
        val node = candidates.single { it.id == selected }
        if (GuideVisionPolicy.generic(node) && !imageUsed) return@withContext uncertainMessage()
        if (imageUsed && GuideVisionPolicy.resolveHighlight(snapshot, snapshot, node.id) == null) return@withContext uncertainMessage()
        GuideTrace.record("decision_selected", mapOf("node" to selected.toString(), "label" to GuidePolicy.label(node), "confidence" to answer.confidence))
        if (NavigationDecisionPolicy.blocksNavigation(GuidePolicy.label(node), goal, snapshot, previousProgress)) {
            GuideTrace.record("decision_guard", mapOf("reason" to "사용자가 원하지 않은 작업 취소", "choice" to answer.choice))
            return@withContext GuideDecision("manual", null, questionMessage(choices), continuation = "wait_choice")
        }
        if (DirectDecisionPolicy.sensitiveGoal(GuidePolicy.label(node))) return@withContext GuideDecision(
            "manual", null, "민감한 정보나 되돌리기 어려운 단계는 내용을 확인하며 직접 진행해주세요.")
        if (node.scrollable && !node.clickable) {
            NavigationDecisionPolicy.probeAfterMissing(goal, snapshot)
                ?.takeIf { it.targetNodeId == selected }?.let { return@withContext it }
        }
        val name = GuidePolicy.label(node).replace(Regex("[\\u0000-\\u001f\\u007f]"), "").take(80)
        GuideActionPolicy.enrich(GuideDecision("highlight", node.id, if (GuideVisionPolicy.generic(node))
            "화면과 대조한 표시 영역을 확인한 뒤 눌러주세요. 원한 버튼이 아니면 ‘다른 안내’를 눌러주세요."
            else if (node.clickable) "$name 항목을 눌러주세요."
            else "$name 영역을 위아래로 움직여 원하는 항목을 찾아주세요.",
            source = when { reviewWithChat && selectedBackend.subscription -> if (imageUsed) "hybrid_chat_vision" else "hybrid_chat"
                imageUsed -> "ai_vision"; else -> "ai" }, visionVerified = imageUsed), snapshot, goal)
    }

    /** At most one independent review at each text/image stage. No primary choice is shown to the reviewer. */
    private suspend fun review(input: JSONObject, choices: JSONArray, image: GuideVisionFrame?, reason: String): GuideModelCodec.Answer? {
        if (!reviewWithChat || !reviewReady) return null
        GuideTrace.record("hybrid_review",mapOf("reason" to reason,"image" to (image != null).toString()))
        return try {
            val target = GuideBackend.SUBSCRIPTION_SOL
            val bytes = GuideModelCodec.payload(target,input,choices,image).toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= if (image == null) 65_536 else GuideVisionPolicy.MAX_REQUEST_BYTES)
            GuideModelCodec.answer(target,execute(bytes,target),choices).also {
                GuideTrace.record("hybrid_result",mapOf("choice" to it.choice,"backend" to target.name))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            GuideTrace.record("hybrid_unavailable",mapOf("reason" to error.javaClass.simpleName))
            null
        }
    }

    private fun uncertainMessage() = GuideDecision("manual", null,
        "다음 버튼을 확실하게 구분하지 못했어요. 보이는 메뉴 이름이나 원하는 동작을 조금 더 알려주세요.", "wait_choice")

    internal suspend fun decisionRequest(input: JSONObject, questions: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        sessionUnchanged()
        val bytes = GuideModelCodec.routingRequest(backend, input, questions).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 65_536) { "앱 정보를 정리하지 못했어요. 모든 앱에서 사용할 앱을 골라주세요." }
        if (backend.subscription) {
            return@withContext GuideModelCodec.routingAnswer(execute(bytes, backend), questions)
        }
        execute(bytes, if (backend == GuideBackend.SERVER_DECISIONS_LUNA) backend else GuideBackend.DECISIONS_LUNA)
    }

    /** 고정된 같은 입력을 사용자가 선택한 방식만 순차 실행한다. 화면 조작·자동 재시도는 없다. */
    suspend fun compare(case: GuideComparisonCase, expected: String = "", targets: List<GuideBackend> = listOf(settings.current()),
        onResult: (GuideComparisonResult) -> Unit = {}): GuideComparisonRun =
        withContext(Dispatchers.IO) {
            val ready = status()
            check(ready.ready) { ready.message }
            require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.size <= GuideBackend.entries.size)
            val input = JSONObject(case.input)
            val choices = JSONArray(case.choices)
            require(expected.isEmpty() || case.labels().containsKey(expected))
            val order = targets.shuffled()
            val results = mutableListOf<GuideComparisonResult>()
            val runId = UUID.randomUUID().toString()
            for (target in order) {
                lastMetrics = Metrics()
                val began = System.nanoTime()
                val result = try {
                    val payload = GuideModelCodec.payload(target, input, choices).toString().toByteArray(Charsets.UTF_8)
                    val response = execute(payload, target, comparison = true)
                    val answer = GuideModelCodec.answer(target, response, choices)
                    val name = case.labels()[answer.choice].orEmpty()
                    val guarded = NavigationDecisionPolicy.cancelsProgress(name, case.goal, input.optBoolean("dismissiblePopup"))
                    val metrics = lastMetrics
                    GuideComparisonResult(target.name, if (guarded) "ask" else answer.choice,
                        if (guarded) questionMessage(choices) else answer.message.ifBlank {
                            if (answer.choice == "ask") questionMessage(choices) else name
                        }, metrics.durationMs, metrics.input, metrics.output, metrics.cost, answer.choice,
                        upstreamMs = metrics.upstreamMs, transport = metrics.transport)
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        val metrics = lastMetrics
                        val canceled = GuideComparisonResult(target.name, "", "", (System.nanoTime() - began) / 1_000_000,
                            metrics.input, metrics.output, metrics.cost, error = "비교 중지 · 비용은 확인된 사용량 또는 예약 상한")
                        GuideComparisons.save(GuideComparisonRun(runId, case.id, System.currentTimeMillis(), expected, results + canceled))
                        throw error
                    }
                    val metrics = lastMetrics
                    GuideComparisonResult(target.name, "", "", (System.nanoTime() - began) / 1_000_000,
                        metrics.input, metrics.output, metrics.cost, error = (error as? IllegalStateException)?.message
                            ?.let(GuideHistoryPolicy::redact) ?: "이 방식의 비교를 완료하지 못했습니다.")
                }
                results += result
                onResult(result)
            }
            GuideComparisonRun(runId, case.id, System.currentTimeMillis(), expected, results)
                .also(GuideComparisons::save)
        }

    private suspend fun execute(bytes: ByteArray,
        target: GuideBackend = GuideBackend.DECISIONS_LUNA, comparison: Boolean = false): JSONObject {
        if (!CALL_LOCK.tryLock()) throw IllegalStateException("이전 안내 요청이 끝나면 다시 시작해주세요.")
        try {
            sessionUnchanged()
            if (target.remote) {
                val config = approvedSubscription ?: connections.load().also {
                    val result = subscription.status(it, target.model, subscription = target.subscription)
                    check(result.ready) { result.message }
                }
                val trace = GuideTiming.traceId()
                if (!comparison) GuideTiming.markFor(trace, "api_start")
                val began = System.nanoTime()
                val response = subscription.execute(config, bytes, subscription = target.subscription)
                val duration = (System.nanoTime() - began) / 1_000_000
                if (!comparison) GuideTiming.markFor(trace, "api_response")
                val info = response.getJSONObject("usage")
                val input = checkedTokens(info, "input_tokens")
                val output = checkedTokens(info, "output_tokens")
                val cost = if (target.subscription) 0.0 else target.cost(input, output)
                val details = response.optJSONObject("apl")
                if (!target.subscription) check(details != null && kotlin.math.abs(details.getDouble("costUsd") - cost) < .000000001) {
                    "서버의 Decisions 비용 기록을 확인하지 못했습니다."
                }
                lastMetrics = Metrics(duration, input, output, cost, details?.optLong("upstreamMs", -1)?.takeIf { it in 0..60_000 },
                    if (config.mode == BridgeConnectionMode.HTTPS) "https_server" else "usb_pc")
                check(target.validModel(response.optString("model"))) { "구독 응답의 모델을 확인하지 못했습니다." }
                sessionUnchanged()
                GuideTrace.recordFor(trace, if (comparison) "comparison_response" else "api_response", mapOf(
                    "durationMs" to duration.toString(), "inputTokens" to input.toString(), "outputTokens" to output.toString(),
                    "billing" to if (target.subscription) "chatgpt_subscription" else "paid_api", "backend" to target.name, "model" to target.model,
                    "upstreamMs" to lastMetrics.upstreamMs?.toString().orEmpty(), "transport" to lastMetrics.transport, "costUsd" to cost.toString()))
                return response
            }
            val credential = approved ?: keys.load() ?: error("유료 비교를 실행하려면 API 키 설정을 확인해주세요.")
            val baseline = usage.current()
            val reserve = target.reserve(bytes.size)
            check(baseline.spentUsd + reserve <= DirectDecisionPolicy.DAILY_LIMIT_USD) {
                "오늘 남은 AI 안내 한도로 이 화면을 처리할 수 없습니다."
            }
            check(baseline.requests < Int.MAX_VALUE) { "오늘의 AI 요청 한도에 도달했습니다." }
            // 실패·취소로 과금 여부가 불명확한 요청은 예약 비용을 유지하며 자동 재시도하지 않는다.
            usage.save(baseline.copy(spentUsd = baseline.spentUsd + reserve, requests = baseline.requests + 1))
            lastMetrics = Metrics(cost = reserve)
            currentCoroutineContext().ensureActive()
            val trace = GuideTiming.traceId()
            if (!comparison) GuideTiming.markFor(trace, "api_start")
            val began = System.nanoTime()
            val response = request(credential, bytes, target)
            val duration = (System.nanoTime() - began) / 1_000_000
            if (!comparison) GuideTiming.markFor(trace, "api_response")
            val info = response.getJSONObject("usage")
            val tokens = checkedTokens(info, "input_tokens")
            val output = if (target.responses) checkedTokens(info, "output_tokens") else 0
            val cached = if (target.responses) info.optJSONObject("input_tokens_details")?.let {
                if (it.has("cached_tokens")) checkedTokens(it, "cached_tokens") else 0
            } ?: 0 else 0
            val actual = target.cost(tokens, output, cached)
            lastMetrics = Metrics(duration, tokens, output, actual)
            GuideTrace.recordFor(trace, if (comparison) "comparison_response" else "api_response", mapOf("durationMs" to duration.toString(),
                "inputTokens" to tokens.toString(), "outputTokens" to output.toString(), "cachedTokens" to cached.toString(),
                "costUsd" to actual.toString(), "backend" to target.name, "model" to target.model))
            usage.save(baseline.copy(spentUsd = baseline.spentUsd + actual, requests = baseline.requests + 1))
            check(actual <= reserve && baseline.spentUsd + actual <= DirectDecisionPolicy.DAILY_LIMIT_USD) {
                "AI 사용량이 예상보다 많아 추가 안내를 중단했습니다."
            }
            unchanged(credential)
            sessionUnchanged()
            currentCoroutineContext().ensureActive()
            check(target.validModel(response.optString("model"))) { "AI 안내 응답을 확인하지 못했습니다." }
            return response
        } finally { CALL_LOCK.unlock() }
    }

    private class RequestError(message: String) : IllegalStateException(message)
    private fun checkedTokens(data: JSONObject, field: String): Long {
        val value = data.opt(field)
        check(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0..1_000_000) {
            "AI 사용량을 확인하지 못했습니다."
        }
        return value.toLong()
    }

    /** 고정 HTTPS 주소, 리다이렉트 금지, 취소 시 연결 종료. 키나 응답 본문을 로그로 남기지 않는다. */
    private suspend fun request(credential: DirectApiKeyStore.Credential, payload: ByteArray, target: GuideBackend): JSONObject {
        unchanged(credential)
        val connection = URL(target.endpoint).openConnection() as HttpsURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${credential.apiKey}")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(payload.size)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                unchanged(credential)
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                if (code !in 200..299) throw RequestError(when (code) {
                    401, 403 -> "OpenAI API 키가 만료됐거나 사용할 수 없습니다. API 키 설정을 확인해주세요."
                    429 -> "OpenAI API 잔액 또는 사용 한도를 확인해주세요. 자동 재시도는 하지 않습니다."
                    400, 404 -> "${target.label}의 API 지원·모델 접근 권한 또는 요청 형식을 확인해주세요."
                    else -> "AI 안내 요청을 완료하지 못했습니다. 잠시 뒤 다시 요청해주세요."
                })
                if (connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                    throw RequestError("AI 안내 응답 형식을 확인하지 못했습니다.")
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(262_145)
                    var offset = 0
                    while (offset < buffer.size && continuation.isActive) {
                        val count = input.read(buffer, offset, buffer.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    buffer.copyOf(offset)
                }
                if (bytes.size > 262_144) throw RequestError("AI 응답 크기를 확인하지 못했습니다.")
                val body = JSONObject(bytes.toString(Charsets.UTF_8))
                if (continuation.isActive) continuation.resume(body)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(if (error is RequestError) error
                    else RequestError("OpenAI에 연결하지 못했습니다. Wi-Fi 또는 모바일 데이터를 확인해주세요."))
            } finally { connection.disconnect() }
        }
    }

    private fun unchanged(credential: DirectApiKeyStore.Credential) {
        check(keys.revision() == credential.revision) { "API 키 설정이 바뀌었습니다. 안내를 다시 시작해주세요." }
    }

    private fun sessionUnchanged() {
        check(settings.current() == backend) { "AI 선택이 바뀌었습니다. 안내를 다시 시작해주세요." }
        if (backend.remote) BridgeConnectionPolicy.requireUnchanged(
            approvedSubscription ?: error("ChatGPT 구독 연결을 확인하고 안내를 다시 시작해주세요."), connections.load())
        else unchanged(approved ?: error("API 키를 등록하고 안내를 다시 시작해주세요."))
    }

    private fun isSlider(className: String?) = Regex("SeekBar|Slider", RegexOption.IGNORE_CASE).containsMatchIn(className.orEmpty())
    private fun questionMessage(choices: JSONArray): String {
        val names = (0 until choices.length()).map { choices.getJSONObject(it) }.filter { it.getString("value").startsWith("node_") }
            .map { it.getString("description").substringBefore(" (역할=") }.filter {
                !NavigationDecisionPolicy.cancelsProgress(it, "", false) &&
                    !Regex("이름\\s*없는|^노드[ #_]*\\d|^(?:image)?button[ #_]*\\d", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            .take(4).joinToString(" · ") { it.take(40) }
        return if (names.isBlank()) "다음 항목을 정하지 못했어요. 다른 안내를 눌러 다시 확인해주세요."
            else "${names} 중 원하는 항목을 직접 골라주세요. 선택하면 이어서 안내할게요."
    }
    private fun manualMessage(goal: String, nodes: List<com.example.aplassistmvp0.accessibility.AccessibilityNodeModel>): GuideDecision {
        val text = if (nodes.any { isSlider(it.className) }) when {
            Regex("밝기|밝게").containsMatchIn(goal) -> "밝기 막대를 손가락으로 움직여 원하는 밝기로 맞춰주세요."
            Regex("글씨|글자|글꼴").containsMatchIn(goal) -> "글자 크기 막대를 손가락으로 움직여 보기 편한 크기로 맞춰주세요."
            Regex("소리|음량|볼륨").containsMatchIn(goal) -> "음량 막대를 손가락으로 움직여 원하는 소리 크기로 맞춰주세요."
            else -> "현재 보이는 항목 이름을 포함해서 요청을 다시 말씀해주세요."
        } else "다음 항목을 찾지 못했어요. 현재 보이는 항목 이름을 포함해서 요청을 다시 말씀해주세요."
        return GuideDecision("manual", null, text, continuation = "wait_controls")
    }
    private companion object { val CALL_LOCK = Mutex() }
}
