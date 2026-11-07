package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.ConditionPhase
import com.example.aplassistmvp0.v3.NextKind
import com.example.aplassistmvp0.v3.Operation
import com.example.aplassistmvp0.v3.TaskGoal
import com.example.aplassistmvp0.v3.TaskPlan

/** A named read-only navigation needs fresh destination evidence after its own observed click. */
internal class V3ScopedArrival {
    private val backLabel=Regex("^(?:뒤로(?:\\s*가기)?|back|navigate up)$|\\s돌아가기$",RegexOption.IGNORE_CASE)
    private data class Attempt(
        val sessionId: Long, val revision: Long, val scope: GuideScopedTap, var before: ScreenSnapshot,
        val shownAt: Long, var clickedAt: Long? = null, var recovery: ReturnAttempt? = null,
        var wrongObservation: WrongObservation? = null, var sourceRestored: Boolean = false,
        val continuedAfterRecovery: Boolean = false,
    )
    private data class ReturnAttempt(
        val before: ScreenSnapshot, val buttonId: Int, val shownAt: Long, val sourceTitle: String, val wrongTitle: String,
        var clickedAt: Long? = null, var resolved: Boolean = false,
    )
    private data class Route(
        val sessionId: Long, val revision: Long, val packageName: String,
        val sourceTitle: String, val targetTitle: String, val controlLabel: String,
        val controlRole: String?, val controlResource: String?,
    ) {
        fun matchesControl(node: AccessibilityNodeModel): Boolean = node.visibleToUser && node.clickable &&
            node.className == controlRole && node.viewIdResourceName == controlResource && GuidePolicy.label(node) == controlLabel
    }
    private data class WrongRoute(val route: Route, val wrongTitle: String)
    private data class WrongObservation(val failure: WrongRoute, val snapshotId: Long, val finishedAt: Long)
    private enum class FailureStatus { RETURNED, REPEATED }
    private var attempt: Attempt? = null
    private val failures = linkedMapOf<WrongRoute, FailureStatus>()
    data class WrongDestination(
        val title: String,
        val pane: AccessibilityNodeModel,
        val returnButton: AccessibilityNodeModel?,
        val draftRisk: Boolean,
        val repeated: Boolean,
    )

    fun show(goal: TaskGoal, scope: GuideScopedTap, before: ScreenSnapshot, shownAt: Long) {
        attempt = Attempt(goal.sessionId, goal.revision, scope, before, shownAt, continuedAfterRecovery = recovered(goal) != null)
    }

    private fun owned(goal: TaskGoal) = attempt?.takeIf { it.sessionId == goal.sessionId && it.revision == goal.revision }
    private fun recovered(goal: TaskGoal) = owned(goal)?.takeIf { it.sourceRestored || it.continuedAfterRecovery }
    fun ownedTitle(goal:TaskGoal):String? = owned(goal)?.scope?.titleLabel?.take(80)
    private fun sameContinuationControl(node: AccessibilityNodeModel, original: AccessibilityNodeModel): Boolean =
        node.visibleToUser && node.clickable && node.className == original.className && GuidePolicy.label(node) == GuidePolicy.label(original)

    fun continuesNamedControl(goal: TaskGoal, frame: ScreenSnapshot, targetId: Int?): Boolean {
        val active = recovered(goal) ?: return false
        return frame.allNodes.singleOrNull { it.id == targetId }?.let { sameContinuationControl(it, active.scope.node) } == true
    }

    fun isNamedResultRow(goal: TaskGoal, frame: ScreenSnapshot, targetId: Int?): Boolean {
        val active = recovered(goal) ?: return false
        val candidate = frame.allNodes.singleOrNull { it.id == targetId && it.visibleToUser && it.clickable } ?: return false
        return GuideScopedTap.namedRows(frame, active.scope.titleLabel).any { row ->
            row === candidate || row.parentPath != null && row.parentPath == candidate.parentPath &&
                row.className == candidate.className && row.viewIdResourceName == candidate.viewIdResourceName
        }
    }

    fun hasDuplicateResultTitle(goal: TaskGoal, frame: ScreenSnapshot): Boolean =
        recovered(goal)?.let { GuideScopedTap.hasDuplicateTitle(frame, it.scope.titleLabel) } == true

    fun namedRowScope(goal: TaskGoal, frame: ScreenSnapshot): GuideScopedTap? = recovered(goal)?.let {
        GuideScopedTap.findNamedRow(frame, goal.request, it.scope.titleLabel, it.scope.rowQualifiers)
    }

    fun continuationPath(goal: TaskGoal, frame: ScreenSnapshot): String? {
        val active = recovered(goal) ?: return null
        namedRowScope(goal, frame)?.let { return it.node.path }
        val control = frame.allNodes.firstOrNull { sameContinuationControl(it, active.scope.node) } ?: return null
        return GuideScopedTap.find(frame, control.id, goal.request, allowSingleControl = true)?.node
            ?.takeIf { sameContinuationControl(it, active.scope.node) }?.path
    }
    fun requestsReturn(plan:TaskPlan,frame:ScreenSnapshot):Boolean =
        plan.kind==NextKind.RECOVER || plan.operation==Operation.BACK ||
            (plan.operation==Operation.TAP && frame.allNodes.singleOrNull { it.id==plan.targetNodeId }
                ?.let { backLabel.containsMatchIn(GuidePolicy.label(it)) } == true)

    fun click(goal: TaskGoal, evidence: PublicActionEvidence?, current: ScreenSnapshot?, now: Long) {
        val active = owned(goal) ?: return
        if (active.clickedAt != null || active.recovery?.clickedAt != null || active.sourceRestored) return
        val eventAt = evidence?.eventUptimeMillis ?: return
        val shown = active.before
        val source = current?.takeIf {
            it.packageName == shown.packageName && it.windowId == shown.windowId && it.sourceEpoch == shown.sourceEpoch &&
                it.snapshotId > shown.snapshotId && !it.truncated && it.captureFinishedAtUptimeMillis?.let { end -> end <= eventAt } == true
        } ?: shown
        val target = active.scope.current(source) ?: return
        if (V3InteractionEvidence.click(evidence, source, active.shownAt, now) === target) {
            active.before = source
            active.clickedAt = eventAt
        }
    }

    fun offerReturn(goal: TaskGoal, frame: ScreenSnapshot, wrong: WrongDestination, button: AccessibilityNodeModel, shownAt: Long) {
        val active = owned(goal) ?: return
        val sourceTitle = active.scope.sourcePaneTitle(active.before) ?: return
        active.recovery = ReturnAttempt(frame, button.id, shownAt, sourceTitle, wrong.title)
    }

    fun withdrawReturn() {
        val active = attempt ?: return
        if (active.recovery?.clickedAt == null) active.recovery = null
    }

    fun clickReturn(goal: TaskGoal, evidence: PublicActionEvidence?, now: Long): Boolean {
        val recovery = owned(goal)?.recovery ?: return false
        if (recovery.clickedAt != null) return false
        val clicked = V3InteractionEvidence.click(evidence, recovery.before, recovery.shownAt, now) ?: return false
        if (clicked.id != recovery.buttonId) return false
        recovery.clickedAt = evidence?.eventUptimeMillis ?: return false
        return true
    }

    fun awaitingReturn(goal: TaskGoal): Boolean = owned(goal)?.recovery?.let { it.clickedAt != null && !it.resolved } == true

    fun confirmReturn(goal: TaskGoal, frame: ScreenSnapshot): Boolean {
        val active = owned(goal) ?: return false
        val recovery = active.recovery ?: return false
        val clickedAt = recovery.clickedAt ?: return false
        if (recovery.resolved || frame.packageName != recovery.before.packageName || frame.sourceEpoch != recovery.before.sourceEpoch ||
            frame.snapshotId <= recovery.before.snapshotId || frame.truncated || frame.viewport == null || frame.blockingPopup ||
            frame.captureStartedAtUptimeMillis?.let { it >= clickedAt } != true) return false
        if (resultEvidence(frame).isEmpty()) {
            val restored = route(goal, active.scope, active.before) ?: return false
            if (restored.sourceTitle != recovery.sourceTitle || !sourceMatches(goal, frame, restored)) return false
            recordRestoration(active, WrongRoute(restored, recovery.wrongTitle))
        }
        recovery.resolved = true
        return true
    }

    private fun route(goal: TaskGoal, scope: GuideScopedTap, frame: ScreenSnapshot): Route? {
        val sourceTitle = scope.sourcePaneTitle(frame) ?: return null
        return Route(goal.sessionId, goal.revision, frame.packageName, sourceTitle, scope.titleLabel,
            GuidePolicy.label(scope.node), scope.node.className, scope.node.viewIdResourceName)
    }

    private fun scopedControl(goal: TaskGoal, frame: ScreenSnapshot, node: AccessibilityNodeModel,
                              allowSingleControl: Boolean = false): GuideScopedTap? =
        GuideScopedTap.find(frame, node.id, goal.request, allowSingleControl)?.takeIf { it.node === node }

    private fun sourceMatches(goal: TaskGoal, frame: ScreenSnapshot, expected: Route): Boolean =
        frame.allNodes.asSequence().filter(expected::matchesControl).mapNotNull { scopedControl(goal, frame, it) }
            .count { route(goal, it, frame) == expected } == 1

    private fun recordRestoration(active: Attempt, failure: WrongRoute) {
        failures.getOrPut(failure) { FailureStatus.RETURNED }
        if (failures.size > MAX_TRACKED_FAILURES) failures.remove(failures.keys.first())
        active.wrongObservation = null
        active.clickedAt = null
        active.sourceRestored = true
    }

    fun repeatsBlockedNavigation(goal: TaskGoal, plan: TaskPlan, frame: ScreenSnapshot): Boolean {
        if (plan.kind != NextKind.GUIDE || FailureStatus.REPEATED !in failures.values) return false
        val id = plan.targetNodeId ?: return false
        val scope = GuideScopedTap.find(frame, id, goal.request, allowSingleControl = true)?.takeIf { it.node.id == id } ?: return false
        val proposed = route(goal, scope, frame) ?: return false
        return failures.any { (failure, status) -> failure.route == proposed && status == FailureStatus.REPEATED }
    }

    fun blockedNodeIds(goal: TaskGoal, frame: ScreenSnapshot): Set<Int> {
        val blocked = failures.filterValues { it == FailureStatus.REPEATED }.keys.map { it.route }.filter {
            it.sessionId == goal.sessionId && it.revision == goal.revision && it.packageName == frame.packageName
        }.toSet()
        if (blocked.isEmpty()) return emptySet()
        return frame.allNodes.asSequence().filter { node -> blocked.any { it.matchesControl(node) } }
            .mapNotNull { scopedControl(goal, frame, it, allowSingleControl = true) }.filter { route(goal, it, frame) in blocked }
            .map { it.node.id }.toSet()
    }

    fun observeArrival(goal: TaskGoal, frame: ScreenSnapshot) {
        val active = owned(goal) ?: return
        if (active.clickedAt == null || frame.packageName != active.before.packageName ||
            frame.sourceEpoch != active.before.sourceEpoch || frame.truncated || frame.viewport == null || frame.blockingPopup) return
        val wrong = wrongDestination(goal, frame)
        if (wrong != null) {
            val attempted = route(goal, active.scope, active.before) ?: return
            val failure = WrongRoute(attempted, wrong.title)
            if (wrong.repeated) failures[failure] = FailureStatus.REPEATED
            frame.captureFinishedAtUptimeMillis?.let { active.wrongObservation = WrongObservation(failure, frame.snapshotId, it) }
            return
        }
        val observed = active.wrongObservation ?: return
        if (frame.snapshotId <= observed.snapshotId || frame.captureStartedAtUptimeMillis?.let { it >= observed.finishedAt } != true) return
        if (resultEvidence(frame).isNotEmpty()) active.wrongObservation = null
        else if (sourceMatches(goal, frame, observed.failure.route)) recordRestoration(active, observed.failure)
    }

    fun unverifiedTitle(goal: TaskGoal, plan: TaskPlan, frame: ScreenSnapshot): String? {
        val active = owned(goal) ?: return null
        val evidence = resultEvidence(frame)
        val cited = plan.conditions.any { condition ->
            condition.required && condition.phase == ConditionPhase.OUTCOME && evidence.any { it.id in condition.evidenceNodeIds }
        }
        return active.scope.titleLabel.take(80).takeUnless { cited }
    }

    fun canReport(goal: TaskGoal, frame: ScreenSnapshot): Boolean {
        val active = destinationObservation(frame) ?: return false
        if (goal.requiresNewEvent || owned(goal) !== active || active.scope.rowQualifiers.isNotEmpty() ||
            wrongDestination(goal, frame) != null) return false
        return active.scope.peerButtonPaths.isNotEmpty() || frame.allNodes.none {
            it.visibleToUser && AccessibilityNodePrivacy.publicPaneTitle(it)?.let { title -> title != active.scope.titleLabel } == true
        }
    }

    fun wrongDestination(goal:TaskGoal,frame:ScreenSnapshot):WrongDestination? {
        if (goal.requiresNewEvent || owned(goal)==null) return null
        val active=destinationObservation(frame) ?:return null
        val viewport=frame.viewport ?:return null
        val title=active.scope.titleLabel
        val panes=frame.allNodes.filter { it.visibleToUser }.mapNotNull { node ->
            AccessibilityNodePrivacy.publicPaneTitle(node)?.let { node to it }
        }
        if (panes.any { it.second==title } || resultEvidence(frame).isNotEmpty()) return null
        val (pane,otherTitle)=panes.filter { (_,label) ->
            active.scope.namesObservedPeer(active.before,label) && active.before.allNodes.none {
                it.visibleToUser && AccessibilityNodePrivacy.publicPaneTitle(it)==label
            }
        }.singleOrNull() ?:return null
        val bounds=pane.bounds
        if (bounds.isEmpty || bounds.left<viewport.left || bounds.top<viewport.top ||
            bounds.right>viewport.right || bounds.bottom>viewport.bottom) return null
        val draftRisk = active.before.allNodes.any {
            it.visibleToUser && (it.password || AccessibilityNodePrivacy.isEditable(it) && it.hasInput != false)
        } || frame.allNodes.any { it.visibleToUser && (it.password || AccessibilityNodePrivacy.isEditable(it)) }
        val sourceTitle = active.scope.sourcePaneTitle(active.before)
        val returnButton = if (!draftRisk && sourceTitle != null) findReturnButton(frame, pane, sourceTitle) else null
        val repeated = route(goal, active.scope, active.before)?.let { WrongRoute(it, otherTitle) in failures } == true
        return WrongDestination(otherTitle, pane, returnButton, draftRisk, repeated)
    }

    private fun findReturnButton(frame:ScreenSnapshot,pane:AccessibilityNodeModel,sourceTitle:String):AccessibilityNodeModel? {
        val visible=frame.allNodes.filter { it.visibleToUser }
        if (visible.any { it.checkable==true || it.hasRangeInfo ||
                (it.clickable && DirectDecisionPolicy.sensitiveGoal(GuidePolicy.label(it))) }) return null
        val labels=setOf("${sourceTitle}로 돌아가기","${sourceTitle}으로 돌아가기")
        val button=visible.singleOrNull {
            it.path.startsWith("${pane.path}/") && it.clickable && it.enabled && !GuideVisionPolicy.privateNode(it) &&
                GuidePolicy.label(it) in labels && !DirectDecisionPolicy.sensitiveGoal(GuidePolicy.label(it))
        } ?:return null
        return button.takeIf { GuidePolicy.resolveDecisionNode(frame,it.id)===it }
    }

    private fun destinationObservation(frame: ScreenSnapshot): Attempt? {
        val active = attempt ?: return null
        val clickedAt = active.clickedAt ?: return null
        val before = active.before
        if (frame.packageName != before.packageName || frame.sourceEpoch != before.sourceEpoch ||
            frame.snapshotId <= before.snapshotId || frame.viewport == null || frame.truncated || frame.blockingPopup ||
            frame.captureStartedAtUptimeMillis?.let { it >= clickedAt } != true) return null
        val source = active.scope.node
        if (frame.windowId == before.windowId && frame.allNodes.any {
                it.visibleToUser && it.path == source.path && it.clickable && GuidePolicy.label(it) == GuidePolicy.label(source)
            }) return null
        return active
    }

    fun resultEvidence(frame: ScreenSnapshot): List<AccessibilityNodeModel> {
        val active = destinationObservation(frame) ?: return emptyList()
        val before = active.before
        val viewport = frame.viewport ?: return emptyList()
        fun inViewport(node: AccessibilityNodeModel): Boolean {
            val bounds = node.bounds
            return node.visibleToUser && !bounds.isEmpty && bounds.left >= viewport.left && bounds.top >= viewport.top &&
                bounds.right <= viewport.right && bounds.bottom <= viewport.bottom
        }
        val title = active.scope.titleLabel
        val visible = frame.allNodes.filter { it.visibleToUser }
        val panes = visible.mapNotNull { node -> AccessibilityNodePrivacy.publicPaneTitle(node)?.let { node to it } }
        val matchingPanes = panes.filter { it.second == title }
        if (matchingPanes.size > 1) return emptyList()
        val newPane = matchingPanes.singleOrNull()?.first?.takeIf(::inViewport)?.takeUnless {
            before.allNodes.any { old -> old.visibleToUser && AccessibilityNodePrivacy.publicPaneTitle(old) == title }
        }
        fun namedHeading(node: AccessibilityNodeModel) = node.heading && !GuideActionPolicy.isActionable(node) &&
            !GuideVisionPolicy.privateNode(node) && node.visibleToUser &&
            (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) && GuidePolicy.label(node) == title
        val newHeading = visible.filter(::namedHeading).singleOrNull()?.takeIf(::inViewport)?.takeUnless { heading ->
            panes.any { (pane, label) ->
                label != title && (heading.path == pane.path || heading.path.startsWith("${pane.path}/"))
            } || before.allNodes.any { old ->
                namedHeading(old) && old.className == heading.className && old.viewIdResourceName == heading.viewIdResourceName
            }
        }
        val qualifiers = active.scope.rowQualifiers
        if (qualifiers.isNotEmpty()) {
            val pane = newPane ?: return emptyList()
            val details = visible.asSequence().filter { node ->
                node.path.startsWith("${pane.path}/") && inViewport(node) && !GuideActionPolicy.isActionable(node) &&
                    !GuideVisionPolicy.privateNode(node) && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            }.map(GuidePolicy::label).toSet()
            if (!details.containsAll(qualifiers)) return emptyList()
            return listOfNotNull(pane, newHeading?.takeIf { it.path.startsWith("${pane.path}/") }).distinctBy { it.id }
        }
        return listOfNotNull(newPane, newHeading).distinctBy { it.id }
    }

    fun interrupt() {
        withdrawReturn()
        attempt?.let {
            it.clickedAt = null
            it.wrongObservation = null
        }
    }
    fun clear() {
        attempt = null
        failures.clear()
    }

    private companion object {
        const val MAX_TRACKED_FAILURES = 32
    }
}
