package com.example.aplassistmvp0.guide

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.state.AssistStateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Waits for the user to open the chosen app; never launches that app or clicks its icon. */
object AppOpeningCoach {
    internal class OpeningTicket internal constructor(internal val generation: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var generation = 0L
    private val retryState = AppOpeningRetryState()
    private var retryContext: Context? = null
    private var currentHomePackage: String? = null
    var active: Boolean = false; private set
    fun start(context: Context, goal: String, target: TargetApp) { start(context, goal, target, null) }
    internal fun start(context: Context, goal: String, target: TargetApp, binding: GuideGoalHandoff?): OpeningTicket? {
        val application = context.applicationContext
        var admitted = false
        var ticket = generation
        val prepare = {
            GuideController.stop()
            retryContext = application
            retryState.begin(goal, target, binding)
            active = true
            ticket = generation
            admitted = true
        }
        if (binding == null) prepare() else {
            if (binding.displayedGoal != goal || !binding.runCurrent(target, prepare)) return null
        }
        if (!admitted) return null
        val homePackage = application.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        currentHomePackage = homePackage
        val startedAt = System.currentTimeMillis()
        val trace = GuideTiming.traceId()
        job = scope.launch(start = CoroutineStart.LAZY) {
            val monitor = binding?.let { bound -> launch {
                try { bound.changes().collect {
                    if (!bound.runCurrent(target) {}) revokeBinding(ticket, bound)
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Connection loss revokes this opening only. */ }
                revokeBinding(ticket, bound)
            } }
            var requestedIcon: List<Any?>? = null
            var announcedInstruction: String? = null
            var lastObservation: Map<String, String>? = null
            try {
            withTimeoutOrNull(180_000) {
                AssistStateRepository.state.collect { screen ->
                    if (binding?.runCurrent(target) {} == false) {
                        revokeBinding(ticket, binding); return@collect
                    }
                    val unlocked = !application.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        application.getSystemService(PowerManager::class.java).isInteractive
                    if (!unlocked || !screen.serviceConnected) {
                        stop(); return@collect
                    }
                    val raw = screen.snapshot
                    val disposition = when {
                        raw == null -> "missing_snapshot"
                        raw.packageName != screen.foregroundPackage -> "foreground_mismatch"
                        raw.capturedAtMillis < startedAt -> "predates_opening"
                        raw.truncated -> "partial_tree"
                        else -> "usable"
                    }
                    val snapshot = raw?.takeIf { disposition == "usable" }
                    val step = AppOpeningPresentationPolicy.step(target.label, snapshot, homePackage)
                    // Record only structural decisions, never node labels, input, widget text,
                    // target names or contents. Unlike a separate QA observer this describes
                    // the actual product capture and the gate that consumed it.
                    val observation = mapOf(
                        "source" to disposition,
                        "selection" to step.kind.name,
                        "launcher" to (screen.foregroundPackage == homePackage).toString(),
                        "homePackageKnown" to (homePackage != null).toString(),
                        "targetReached" to (screen.foregroundPackage == target.packageName).toString(),
                        "nodes" to (raw?.allNodes?.size ?: 0).toString(),
                        "truncated" to (raw?.truncated?.toString() ?: "unknown"),
                        "viewportKnown" to (raw?.viewport != null).toString(),
                        "launcherStructure" to if (raw != null && raw.packageName == homePackage) raw.allNodes.asSequence()
                            .filter { it.className in setOf("android.widget.LinearLayout", "android.widget.FrameLayout") ||
                                it.viewIdResourceName?.startsWith("$homePackage:id/") == true }
                            .take(65).joinToString("\n") {
                                val resource = it.viewIdResourceName?.takeIf { name -> name.startsWith("$homePackage:id/") }
                                    ?.substringAfterLast('/').orEmpty()
                                "${it.path} ${it.className?.substringAfterLast('.')} $resource " +
                                    "visible=${it.visibleToUser} enabled=${it.enabled} clickable=${it.clickable} " +
                                    "editable=${it.editable} private=${it.password || it.sensitiveContent} description=${it.contentDescription != null}"
                            } else "",
                    )
                    if (observation != lastObservation) {
                        GuideTrace.recordFor(trace, "app_opening_screen", observation)
                        lastObservation = observation
                    }
                    if (screen.foregroundPackage == target.packageName && snapshot != null) {
                        active = false
                        retryState.clear(); retryContext = null
                        scope.launch {
                            if (ticket != generation) return@launch
                            val started = binding?.startGuide(target) ?: GuideController.start(goal, target.packageName, target.label)
                            if (started) AssistAccessibilityService.requestRefresh()
                            else if (ticket == generation && GuideStateRepository.state.value.phase == "opening_app")
                                GuideStateRepository.publish(GuideStateRepository.state.value.copy(phase = "waiting_goal",
                                    message = "요청이 바뀌어 이전 안내를 시작하지 않았어요."))
                        }
                        job?.cancel(); job = null
                        return@collect
                    }
                    if (screen.foregroundPackage == null || screen.foregroundPackage == "com.android.systemui") return@collect
                    val icon = step.node
                    val present = {
                    GuideStateRepository.publish(GuideUiState(goal = goal, phase = "opening_app", message = step.message,
                        targetPackageName = screen.foregroundPackage.orEmpty(), targetAppLabel = target.label))
                    if (step.kind !in setOf(AppOpeningPresentationPolicy.Kind.UNAVAILABLE, AppOpeningPresentationPolicy.Kind.INCOMPLETE,
                            AppOpeningPresentationPolicy.Kind.UNRESOLVED) && announcedInstruction != step.message) {
                        announcedInstruction = step.message
                        GuideController.announceChoice(step.message)
                    }
                    // Publish the opening phase before requesting its fresh target. Do not reset
                    // the same selection on every capture: it needs two stable observations.
                    val key = icon?.let { listOf(snapshot!!.packageName, snapshot.windowId, it.path,
                        it.className, GuidePolicy.label(it), it.bounds) }
                    if (icon != null && key != requestedIcon) {
                        requestedIcon = key
                        if (!AssistAccessibilityService.requestGuideHighlight(icon)) requestedIcon = null
                    } else if (icon == null && requestedIcon != null) {
                        requestedIcon = null
                        AssistAccessibilityService.clearHighlight()
                    }
                    }
                    if (binding == null) present() else if (!binding.runCurrent(target, present)) revokeBinding(ticket, binding)
                }
            }
            if (ticket != generation) return@launch
            active = false
            job = null
            GuideStateRepository.publish(GuideStateRepository.state.value.copy(phase = "paused", message = "${target.label} 앱을 아직 열지 못했어요."))
            } finally { monitor?.cancel() }
        }
        job?.start()
        AssistAccessibilityService.requestRefresh()
        return OpeningTicket(ticket)
    }
    fun retry(): Boolean {
        if (active) return false
        val request = retryState.forRetry(GuideStateRepository.state.value) ?: return false
        val application = retryContext ?: return false
        return start(application, request.goal, request.target, request.binding) != null
    }
    internal fun verifiedOpeningTarget(snapshot: com.example.aplassistmvp0.state.ScreenSnapshot,
                                       node: com.example.aplassistmvp0.accessibility.AccessibilityNodeModel): Boolean {
        if (!active) return false
        val request = retryState.forOpening(GuideStateRepository.state.value) ?: return false
        return AppOpeningPresentationPolicy.step(request.target.label, snapshot, currentHomePackage).node === node
    }
    fun stop() { generation++; active = false; job?.cancel(); job = null; retryState.clear(); retryContext = null; currentHomePackage = null }
    internal fun stopIfTicket(ticket: OpeningTicket) {
        if (ticket.generation == generation) stop()
    }
    private fun revokeBinding(ticket: Long, binding: GuideGoalHandoff) {
        if (ticket != generation || !active || !retryState.hasBinding(binding)) return
        val previous = GuideStateRepository.state.value
        stop()
        AssistAccessibilityService.clearHighlight()
        GuideStateRepository.publish(previous.copy(phase = "waiting_goal", message = "요청이 바뀌어 앱 열기 안내를 멈췄어요.", busy = false))
    }
}
