package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ScrollDirection(val actionIds: Set<Int>) {
    DOWN(setOf(16908346, 4096)), UP(setOf(16908344, 8192));
    fun reverse() = if (this == DOWN) UP else DOWN
    fun instruction() = if (this == DOWN) "화면을 위로 밀어 아래 항목을 보여주세요." else "화면을 아래로 밀어 위 항목을 보여주세요."
}

internal data class ProbeObservation(val direction: ScrollDirection, val screen: ScreenSnapshot)
internal sealed interface ScrollProbeResult {
    data class Verified(val screen: ScreenSnapshot, val observations: List<ProbeObservation>,
                        val moves: Int, val elapsedMillis: Long) : ScrollProbeResult
    data class Unavailable(val message: String) : ScrollProbeResult
    data class Interrupted(val message: String) : ScrollProbeResult
}

/** The port only exposes semantic scrolling. It cannot click, dismiss, type or inject gestures. */
internal interface ScrollProbePort {
    suspend fun freeze(origin: ScreenSnapshot): Boolean
    suspend fun read(): ScreenSnapshot?
    suspend fun scroll(container: AccessibilityNodeModel, direction: ScrollDirection): Boolean
    fun uncover()
}

internal object ScrollProbePolicy {
    const val MAX_MOVES = 2
    const val DEADLINE_MILLIS = 4_000L

    fun container(screen: ScreenSnapshot): AccessibilityNodeModel? = GuidePolicy.candidates(screen)
        .filter { it.scrollable && !it.clickable && !it.hasRangeInfo &&
            !it.className.orEmpty().contains("Horizontal", true) &&
            it.bounds.bottom - it.bounds.top > 160 &&
            it.actions.any { action -> action.id in ScrollDirection.DOWN.actionIds + ScrollDirection.UP.actionIds } }
        .singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }

    fun identity(node: AccessibilityNodeModel) = listOf(node.viewIdResourceName.orEmpty(),
        node.className.orEmpty(), GuidePolicy.label(node), node.clickable.toString(), node.scrollable.toString()).joinToString("\u001e")

    fun publicChildren(screen: ScreenSnapshot, container: AccessibilityNodeModel) = GuidePolicy.candidates(screen).filter {
        it.path.startsWith(container.path + "/") && it.visibleToUser && it.enabled && !it.bounds.isEmpty &&
            !it.password && !it.sensitiveContent && !AccessibilityNodePrivacy.isEditable(it) &&
            !AccessibilityNodePrivacy.isSensitivePreviewResource(it.viewIdResourceName) &&
            GuidePolicy.label(it).isNotBlank() && !it.scrollable &&
            (it.bounds.top + it.bounds.bottom) / 2 in container.bounds.top until container.bounds.bottom
    }

    /** Fixed headers outside the scrolling subtree are deliberately excluded. */
    fun anchors(screen: ScreenSnapshot, container: AccessibilityNodeModel): List<AccessibilityNodeModel> {
        val unique = publicChildren(screen, container).groupBy(::identity).values.filter { it.size == 1 }
            .map { it.single() }.sortedBy { it.bounds.top }
        if (unique.size < 2) return emptyList()
        val first = unique.first()
        val last = unique.last()
        return if (last.bounds.top - first.bounds.top >= 48) listOf(first, last) else emptyList()
    }

    fun sameContext(origin: ScreenSnapshot, live: ScreenSnapshot): Boolean =
        origin.packageName == live.packageName && origin.windowId == live.windowId &&
            origin.sourceEpoch == live.sourceEpoch && origin.viewport == live.viewport &&
            origin.platformActionBarBounds == live.platformActionBarBounds &&
            !live.truncated && !NavigationDecisionPolicy.hasPopup(live)

    fun currentContainer(original: AccessibilityNodeModel, live: ScreenSnapshot): AccessibilityNodeModel? =
        container(live)?.takeIf { it.path == original.path && it.bounds == original.bounds && identity(it) == identity(original) }

    fun restored(origin: ScreenSnapshot, live: ScreenSnapshot, anchors: List<AccessibilityNodeModel>): Boolean {
        if (!sameContext(origin, live) || anchors.size < 2) return false
        val original = container(origin) ?: return false
        val container = currentContainer(original, live) ?: return false
        val children = publicChildren(live, container).groupBy(::identity)
        val tolerance = ((container.bounds.bottom - container.bounds.top) / 100).coerceIn(8, 20)
        return anchors.all { anchor -> children[identity(anchor)]?.singleOrNull()?.let {
            kotlin.math.abs(it.bounds.left - anchor.bounds.left) <= tolerance &&
                kotlin.math.abs(it.bounds.top - anchor.bounds.top) <= tolerance &&
                kotlin.math.abs(it.bounds.right - anchor.bounds.right) <= tolerance &&
                kotlin.math.abs(it.bounds.bottom - anchor.bounds.bottom) <= tolerance
        } == true }
    }

    /** An already exposed offscreen node can provide direction without moving the app. */
    fun knownOffscreen(goal: String, origin: ScreenSnapshot): ProbeObservation? {
        if (NavigationDecisionPolicy.hasPopup(origin)) return null
        val container = container(origin) ?: return null
        val public = origin.allNodes.filter { it.path.startsWith(container.path + "/") && !it.visibleToUser }
            .map { it.copy(visibleToUser = true) }
        val catalog = origin.copy(nodes = public, allNodes = public, viewport = null)
        val target = NavigationDecisionPolicy.exactTarget(goal, catalog) ?: return null
        val direction = when {
            target.bounds.top >= container.bounds.bottom -> ScrollDirection.DOWN
            target.bounds.bottom <= container.bounds.top -> ScrollDirection.UP
            else -> return null
        }
        return ProbeObservation(direction, catalog)
    }
}

/** Bounds include capture, exploration and recovery. Cancellation never triggers recovery scrolling. */
internal class ScrollProbeRunner(private val port: ScrollProbePort, private val nowMillis: () -> Long) {
    suspend fun run(goal: String, origin: ScreenSnapshot): ScrollProbeResult {
        ScrollProbePolicy.knownOffscreen(goal, origin)?.let {
            return ScrollProbeResult.Verified(origin, listOf(it), 0, 0)
        }
        val container = ScrollProbePolicy.container(origin)
            ?: return ScrollProbeResult.Unavailable("스크롤 영역이 불명확해 자동 확인을 하지 못했어요. 원하는 메뉴가 있는 쪽으로 직접 스크롤해주세요.")
        val anchors = ScrollProbePolicy.anchors(origin, container)
        if (anchors.size < 2 || NavigationDecisionPolicy.hasPopup(origin)) return ScrollProbeResult.Unavailable(
            "원래 위치를 확인할 기준이 부족해요. 직접 스크롤하면 새 화면에서 이어서 찾아드릴게요.")
        val started = nowMillis()
        var latest = origin
        var moves = 0
        val observations = mutableListOf<ProbeObservation>()
        try {
            val completed = withTimeoutOrNull(ScrollProbePolicy.DEADLINE_MILLIS) {
                if (!port.freeze(origin)) return@withTimeoutOrNull ScrollProbeResult.Unavailable(
                    "이 화면을 잠시 고정할 수 없어요. 직접 스크롤하면 이어서 확인할게요.")
                for (direction in listOf(ScrollDirection.DOWN, ScrollDirection.UP)) {
                    if (moves >= ScrollProbePolicy.MAX_MOVES) break
                    val current = ScrollProbePolicy.currentContainer(container, latest) ?: return@withTimeoutOrNull interrupted(latest)
                    if (current.actions.none { it.id in direction.actionIds }) continue
                    moves++
                    if (!port.scroll(current, direction)) return@withTimeoutOrNull interrupted()
                    latest = port.read() ?: return@withTimeoutOrNull interrupted()
                    if (!ScrollProbePolicy.sameContext(origin, latest)) return@withTimeoutOrNull interrupted(latest)
                    if (ScrollProbePolicy.restored(origin, latest, anchors)) continue // no movement / list boundary
                    observations += ProbeObservation(direction, latest)
                    var recovered = false
                    for (attempt in 0 until 2) {
                        val recoveryContainer = ScrollProbePolicy.currentContainer(container, latest)
                            ?: return@withTimeoutOrNull interrupted(latest)
                        if (!port.scroll(recoveryContainer, direction.reverse())) return@withTimeoutOrNull interrupted()
                        latest = port.read() ?: return@withTimeoutOrNull interrupted()
                        if (!ScrollProbePolicy.sameContext(origin, latest)) return@withTimeoutOrNull interrupted(latest)
                        if (ScrollProbePolicy.restored(origin, latest, anchors)) { recovered = true; break }
                    }
                    if (!recovered) return@withTimeoutOrNull interrupted()
                    if (NavigationDecisionPolicy.exactTarget(goal, observations.last().screen) != null) break
                }
                ScrollProbeResult.Verified(latest, observations.toList(), moves, nowMillis() - started)
            }
            return completed ?: ScrollProbeResult.Interrupted(
                "화면 확인이 4초를 넘겨 중단했어요. 현재 위치를 다시 확인한 뒤 안내를 시작해주세요.")
        } finally { port.uncover() }
    }

    private fun interrupted(live: ScreenSnapshot? = null) = ScrollProbeResult.Interrupted(
        if (live != null && NavigationDecisionPolicy.hasPopup(live))
            "확인 중 팝업이 나타나 중단했어요. 현재 팝업을 닫은 뒤 다시 요청해주세요."
        else "원래 화면 위치를 확인할 수 없어 중단했어요. 현재 화면을 확인한 뒤 다시 요청해주세요.")
}
