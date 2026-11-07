package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** Keep a request alive across small label rotations; never use similarity alone to approve a response. */
internal object GuideResponsePolicy {
    private fun slot(node: AccessibilityNodeModel) = listOf(node.className, node.viewIdResourceName, node.bounds).toString()
    private fun state(node: AccessibilityNodeModel) = listOf(node.clickable, node.scrollable, node.enabled,
        node.editable, node.hasRangeInfo, node.checkable, node.checked, node.selected, node.focused,
        node.hasInput, node.contentInvalid, node.range, node.localRecipientKey, node.stateDescription,
        node.actions.map { it.id }.sorted())
    private fun label(node: AccessibilityNodeModel) = GuidePolicy.labels(node).map {
        // Collection IDs are not identity. Do not mistake anonymous node renumbering for navigation.
        it.replace(Regex("^이름 없는 버튼 #\\d+$"), "이름 없는 버튼")
    }

    fun sameFrame(before: ScreenSnapshot, live: ScreenSnapshot): Boolean {
        if (before.packageName != live.packageName || before.windowId != live.windowId ||
            before.viewport != live.viewport || before.truncated || live.truncated ||
            before.blockingPopup || live.blockingPopup ||
            NavigationDecisionPolicy.hasPopup(before) || NavigationDecisionPolicy.hasPopup(live)) return false
        val old = GuidePolicy.contextNodes(before).groupBy(::slot)
        val fresh = GuidePolicy.contextNodes(live).groupBy(::slot)
        if (old.size < 5 || old.keys != fresh.keys || old.values.any { it.size != 1 } || fresh.values.any { it.size != 1 }) return false
        var changed = 0
        for ((key, values) in old) {
            val a = values.single(); val b = fresh.getValue(key).single()
            if (state(a) != state(b)) return false
            if (label(a) != label(b)) {
                if (AccessibilityNodePrivacy.isEditable(a) || a.checkable == true || a.hasRangeInfo) return false
                changed++
            }
        }
        return changed <= 2 && changed * 5 <= old.size
    }

    private fun unchanged(node: AccessibilityNodeModel, live: ScreenSnapshot): Boolean =
        GuidePolicy.contextNodes(live).count { slot(it) == slot(node) && state(it) == state(node) && label(it) == label(node) } == 1 &&
            GuidePolicy.contextNodes(live).count { label(it) == label(node) && it.className == node.className } == 1

    fun accepts(policy: GuideSessionPolicy, generation: Long, decision: GuideDecision,
                before: ScreenSnapshot, live: ScreenSnapshot?): Boolean {
        live ?: return false
        if (!policy.acceptsContext(generation, before, live)) return false
        if (before.blockingPopup != live.blockingPopup ||
            NavigationDecisionPolicy.hasPopup(before) != NavigationDecisionPolicy.hasPopup(live)) return false
        if (decision.action == "wait") return GuideActivityWaitPolicy.sameObservation(decision, before, live)
        if (decision.visionVerified) return if (decision.action == "highlight")
            decision.targetNodeId?.let { GuideVisionPolicy.resolveHighlight(before, live, it) } != null
        else GuideVisionPolicy.sameScreen(before, live)
        if (policy.accepts(generation, before, live)) return true
        if (decision.action == "highlight" && policy.acceptsHighlight(generation, before, live)) return true
        if (!sameFrame(before, live)) return false
        return when {
            decision.action == "highlight" -> decision.targetNodeId?.let { GuidePolicy.resolveDecisionNode(before, it) }
                ?.let { unchanged(it, live) } == true
            decision.action == "manual" && decision.continuation == "wait_confirmation" ->
                decision.evidenceNodeIds.isNotEmpty() && decision.evidenceNodeIds.all { id ->
                    GuidePolicy.contextNodes(before).singleOrNull { it.id == id }
                        ?.takeUnless { GuideVisionPolicy.generic(it) || it.editable || it.password || it.sensitiveContent }
                        ?.let { unchanged(it, live) } == true
                }
            else -> false
        }
    }
}

/** Limits consecutive failed/canceled judgments. Accepted guidance or a user action resets it. */
internal class GuideRequestGuard {
    private var attempts = 0
    fun reset() { attempts = 0 }
    fun claim(): Boolean {
        if (attempts >= 3) return false
        attempts++
        return true
    }
}
