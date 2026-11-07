package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import java.text.Normalizer
import java.util.Locale

data class AppCapability(val text: String, val source: String, val checkedAt: Long, val revision: String = "")
data class AppKnowledge(val packageName: String, val label: String, val revision: String,
    val capabilities: List<AppCapability> = emptyList())
data class MemoryControl(val label: String, val viewId: String, val role: String, val action: String)
data class MemoryStep(val from: String, val to: String, val control: MemoryControl,
    val message: String, val continuation: String? = null)
data class Experience(val id: String, val packageName: String, val appLabel: String, val goal: String,
    val revision: String, val checkedAt: Long, val steps: List<MemoryStep>, val result: String,
    val successes: Int = 1, val failures: Int = 0, val needsReview: Boolean = false,
    val reusable: Boolean = true, val apiRequests: Int = 0, val estimatedCostUsd: Double = 0.0,
    val memoryHits: Int = 0, val elapsedMillis: Long = 0)
data class ExperienceState(val apps: List<AppKnowledge> = emptyList(), val experiences: List<Experience> = emptyList())
data class MemoryMatch(val experienceId: String, val decision: GuideDecision)
data class PublicActionEvidence(val windowId: Int, val bounds: com.example.aplassistmvp0.accessibility.NodeBounds,
    val role: String, val viewId: String, val label: String = "", val scrollDeltaX: Int? = null, val scrollDeltaY: Int? = null,
    val eventType:Int?=null, val eventUptimeMillis:Long?=null, val receivedUptimeMillis:Long?=null,
    val packageName:String?=null, val sourceEpoch:Long?=null)

/** Public control descriptions and hashes only; never persist coordinates or snapshot-local IDs. */
internal object ExperiencePolicy {
    const val LIMIT = 100
    fun goalKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        .replace(Regex("(?:어떻게하더라|어떻게하지|어떻게해|어디있어|어디에있어|알려줘|알려주세요|해줘|해주세요)$"), "")

    fun safeText(value: String, limit: Int = 120) = GuideHistoryPolicy.redact(value).take(limit)
    fun control(node: AccessibilityNodeModel, action: String): MemoryControl? {
        val name = GuidePolicy.label(node)
        if (name.isBlank() || safeText(name) != name || name.length > 120 || node.password ||
            node.sensitiveContent || AccessibilityNodePrivacy.hasHiddenIdentifier(node) || AccessibilityNodePrivacy.isEditable(node) || node.hasRangeInfo ||
            DirectDecisionPolicy.sensitiveGoal(name)) return null
        return MemoryControl(name, node.viewIdResourceName.orEmpty().take(200), node.className.orEmpty().take(160), action)
    }
    fun signature(screen: ScreenSnapshot): String = GuidePolicy.interactionFingerprint(screen)

    fun match(goal: String, screen: ScreenSnapshot, experiences: List<Experience>): MemoryMatch? {
        if (screen.truncated || NavigationDecisionPolicy.hasPopup(screen) || DirectDecisionPolicy.sensitiveGoal(goal)) return null
        if (goalKey(goal).length < 2) return null
        val signature = signature(screen)
        if (signature.isEmpty()) return null
        val matches = experiences.filter { it.packageName == screen.packageName && it.reusable &&
            goalKey(it.goal) == goalKey(goal) }.flatMap { experience ->
            experience.steps.filter { it.from == signature }.mapNotNull { step ->
                val nodes = GuidePolicy.candidates(screen).filter { node ->
                    GuidePolicy.label(node) == step.control.label && node.className.orEmpty() == step.control.role &&
                        (step.control.viewId.isEmpty() || node.viewIdResourceName.orEmpty() == step.control.viewId) &&
                        if (step.control.action == "click") node.clickable else node.scrollable
                }
                val node = nodes.singleOrNull()?.let { GuidePolicy.resolveDecisionNode(screen, it.id) } ?: return@mapNotNull null
                if (control(node, step.control.action) == null) return@mapNotNull null
                step.control to MemoryMatch(experience.id, GuideDecision("highlight", node.id, step.message, step.continuation))
            }
        }
        // Conflicting successful paths on the same screen require a new decision.
        if (matches.map { it.first }.distinct().size != 1) return null
        return matches.firstOrNull()?.second
    }

    fun synchronize(state: ExperienceState, installed: List<TargetApp>): ExperienceState {
        val byPackage = installed.associateBy { it.packageName }
        return state.copy(apps = installed.map { app ->
            val old = state.apps.firstOrNull { it.packageName == app.packageName }
            AppKnowledge(app.packageName, safeText(app.label, 80), app.revision, old?.capabilities.orEmpty())
        }, experiences = state.experiences.filter { it.packageName in byPackage }.map {
            it.copy(needsReview = it.needsReview || it.revision != byPackage.getValue(it.packageName).revision)
        }.take(LIMIT))
    }
}

/** Records confirmed actions followed by a genuinely newer screen. Recommendations are not actions. */
internal class ExperienceRecorder(
    private val read: () -> ExperienceState,
    private val write: (ExperienceState) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var goal = ""
    private var app: TargetApp? = null
    private var startedAt = 0L
    private var last: ScreenSnapshot? = null
    private var pending: Pending? = null
    private val steps = mutableListOf<MemoryStep>()
    private var recalledIds = mutableSetOf<String>()
    var hits = 0; private set
    private var disabled = false
    private data class Pending(val screenId: Long, val time: Long, val signature: String,
        val control: MemoryControl, val message: String, val continuation: String?)

    fun start(request: String, target: TargetApp) {
        goal = ExperiencePolicy.safeText(request, 200); app = target; startedAt = now()
        last = null; pending = null; steps.clear(); recalledIds.clear(); hits = 0; disabled = false
    }
    fun observe(screen: ScreenSnapshot) {
        if (screen.packageName != app?.packageName || screen.truncated || screen.capturedAtMillis < startedAt) return
        last = screen
        val action = pending ?: return
        if (screen.snapshotId <= action.screenId || screen.capturedAtMillis < action.time) return
        val signature = ExperiencePolicy.signature(screen)
        if (signature.isEmpty() || signature == action.signature) return
        if (steps.size < 16) steps += MemoryStep(action.signature, signature, action.control, action.message, action.continuation)
        pending = null
    }
    fun action(screen: ScreenSnapshot, nodeId: Int?, scrollDirection: ScrollDirection?, instruction: GuideDecision?) {
        if (screen.packageName != app?.packageName || disabled) return
        // Unknown actions break the recorded chain; they are not filled in from model guesses.
        val node = nodeId?.let { GuidePolicy.resolveDecisionNode(screen, it) }
        val kind = if (scrollDirection == null) "click" else if (scrollDirection == ScrollDirection.DOWN) "scroll_down" else "scroll_up"
        val control = node?.let { ExperiencePolicy.control(it, kind) }
        if (control == null || (scrollDirection != null && NavigationDecisionPolicy.hasPopup(screen))) {
            pending = null; return
        }
        val message = if (scrollDirection != null) scrollDirection.instruction() else "${control.label} 항목을 눌러주세요."
        pending = Pending(screen.snapshotId, now(), ExperiencePolicy.signature(screen), control, message,
            if (scrollDirection != null) "find_target" else instruction?.continuation)
    }
    fun recall(screen: ScreenSnapshot): GuideDecision? {
        if (disabled) return null
        val match = ExperiencePolicy.match(goal, screen, read().experiences) ?: return null
        recalledIds += match.experienceId; hits++
        return match.decision
    }
    fun expectedResult(screen: ScreenSnapshot): Boolean = !screen.truncated && !NavigationDecisionPolicy.hasPopup(screen) &&
        steps.isNotEmpty() && read().experiences.any {
        it.reusable && it.packageName == screen.packageName && ExperiencePolicy.goalKey(it.goal) == ExperiencePolicy.goalKey(goal) &&
            it.result.isNotEmpty() && it.result == ExperiencePolicy.signature(screen)
    }
    fun wrong() {
        disabled = true; pending = null; steps.clear()
        write(read().copy(experiences = read().experiences.map {
            if (it.id in recalledIds) it.copy(failures = it.failures + 1, reusable = false, needsReview = true) else it
        }))
        recalledIds.clear()
    }
    /** 이미지로만 구분한 임시 ID/좌표를 이름 기반 경로 기억에 섞지 않는다. 실패로 집계하지 않는다. */
    fun skipVisualRoute() { disabled = true; pending = null; steps.clear() }
    fun confirm(apiRequests: Int = 0, cost: Double = 0.0): Boolean {
        val target = app ?: return false
        val screen = last ?: return false
        if (now() - screen.capturedAtMillis !in 0..120_000 || pending != null) return false
        val state = read()
        val features = state.apps.firstOrNull { it.packageName == target.packageName } ?: AppKnowledge(target.packageName, target.label, target.revision)
        val capability = AppCapability(goal, "confirmed", now(), target.revision)
        val updatedFeatures = features.copy(capabilities = (listOf(capability) + features.capabilities.filter { it.text != goal }).take(40))
        var experiences = state.experiences
        if (steps.isNotEmpty()) {
            val previous = experiences.firstOrNull { it.packageName == target.packageName &&
                ExperiencePolicy.goalKey(it.goal) == ExperiencePolicy.goalKey(goal) && it.steps.firstOrNull()?.from == steps.first().from }
            val entry = Experience(previous?.id ?: java.util.UUID.randomUUID().toString(), target.packageName, target.label,
                goal, target.revision, now(), steps.toList(), ExperiencePolicy.signature(screen),
                successes = (previous?.successes ?: 0) + 1, failures = previous?.failures ?: 0,
                apiRequests = apiRequests, estimatedCostUsd = cost, memoryHits = hits, elapsedMillis = now() - startedAt)
            experiences = (listOf(entry) + experiences.filter { it.id != entry.id }).take(ExperiencePolicy.LIMIT)
        }
        write(state.copy(apps = listOf(updatedFeatures) + state.apps.filter { it.packageName != target.packageName }, experiences = experiences))
        app = null; pending = null; steps.clear()
        return true
    }
    fun clear() { app = null; goal = ""; pending = null; last = null; steps.clear(); recalledIds.clear(); disabled = true }
}
