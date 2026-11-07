package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** 초기 운영 기준이다. 모델 confidence는 실제 성공률이 아니며 평가 후 조정한다. */
internal object GuideVisionPolicy {
    private val privateResource = Regex("(?i)delete|remove|close_account|pay|transfer|password|auth")
    private val genericName = Regex("(?i)^(?:button|imagebutton|버튼|노드)[ #_]*\\d*$")
    const val MIN_CONFIDENCE = .65
    const val MIN_MARGIN = .15
    const val MAX_PER_GOAL = 3
    const val MAX_IMAGE_BYTES = 122_880
    const val MAX_REQUEST_BYTES = 262_144
    const val MAX_EDGE = 1280
    fun anonymous(node: AccessibilityNodeModel): Boolean = node.clickable &&
        node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() && node.descendantLabels.isEmpty() &&
        !node.editable && !node.password && !node.sensitiveContent &&
        !privateResource.containsMatchIn(node.viewIdResourceName.orEmpty()) &&
        com.example.aplassistmvp0.accessibility.PublicNodeRoleLabels.label(node) == null

    fun generic(node: AccessibilityNodeModel): Boolean = anonymous(node) ||
        (AccessibilityNodePrivacy.isEditable(node) && AccessibilityNodePrivacy.publicInputLabel(node) == "입력란") ||
        genericName.matches(GuidePolicy.label(node).trim())

    fun reason(choice: String, confidence: Double?, margin: Double?, selected: AccessibilityNodeModel?): String? = when {
        choice in setOf("ask","confirm") -> null // 사용자 선택·도착 확인은 조작이나 성공 확정이 아니다.
        choice == "manual" -> "missing_screen_information"
        selected != null && generic(selected) -> "unnamed_control"
        confidence != null && confidence < MIN_CONFIDENCE -> "low_confidence"
        margin != null && margin < MIN_MARGIN -> "close_choices"
        else -> null // confidence 부재는 0%가 아니다.
    }

    /** Visual evidence belongs to its original connection, interaction, and ordered node bindings. */
    fun sameScreen(a: ScreenSnapshot, b: ScreenSnapshot): Boolean = !a.truncated && !b.truncated &&
        a.packageName == b.packageName && a.windowId == b.windowId && a.viewport != null && a.viewport == b.viewport &&
        a.sourceEpoch == b.sourceEpoch && a.visualInteractionGeneration == b.visualInteractionGeneration &&
        a.snapshotId <= b.snapshotId &&
        a.blockingPopup == b.blockingPopup && a.platformActionBarBounds == b.platformActionBarBounds &&
        a.allNodes == b.allNodes

    /** An image result must still identify a unique current control with a supported action. */
    fun resolveHighlight(before: ScreenSnapshot, live: ScreenSnapshot, targetId: Int): AccessibilityNodeModel? {
        if (!sameScreen(before, live) || GuidePolicy.resolveDecisionNode(before, targetId) == null) return null
        val target = GuidePolicy.resolveDecisionNode(live, targetId) ?: return null
        return target.takeIf { GuideActionPolicy.isActionable(it) && live.allNodes.singleOrNull { node -> node.id == targetId } === it }
    }

    fun privateNode(n: AccessibilityNodeModel) = n.password || n.sensitiveContent || AccessibilityNodePrivacy.hasHiddenIdentifier(n) ||
        AccessibilityNodePrivacy.isEditable(n) || AccessibilityNodePrivacy.isSensitivePreviewResource(n.viewIdResourceName, n.text)
}

/** 이미지 원문은 이 요청의 메모리에서만 사용하며 비교 기록·트레이스에 저장하지 않는다. */
internal data class GuideVisionFrame(val dataUrl: String, val width: Int, val height: Int, val redactions: Int)

internal class GuideVisionBudget {
    private val attempted = mutableSetOf<String>()
    @Synchronized fun reset() { attempted.clear() }
    @Synchronized fun claim(screen: ScreenSnapshot): Boolean = attempted.size < GuideVisionPolicy.MAX_PER_GOAL &&
        attempted.add("${screen.packageName}:${screen.windowId}:${GuidePolicy.fingerprint(screen)}")
}
