package com.example.aplassistmvp0.accessibility

/** 저장해 둔 선택을 현재 화면의 정보와 대조한다. 클릭·스크롤 등 실제 동작은 실행하지 않는다. */
object TargetResolver {
    /**
     * 문구의 완전 일치와 viewId/클래스/역할을 이용해 최신 노드를 찾는다.
     * 숫자 id나 예전 좌표는 사용하지 않는다. 경로 역시 화면마다 바뀔 수 있으므로
     * 동점인 후보를 경로만으로 선택하지 않고 null을 반환한다.
     * requiredAnchor를 주면 행의 아이콘 설명 대신 사용자가 고른 원래 글자를 기준으로 삼는다.
     */
    fun resolve(selected: AccessibilityNodeModel,
                candidates: List<AccessibilityNodeModel>,
                requiredAnchor: String? = null): AccessibilityNodeModel? {
        if (selected.password) return null
        val selectedAnchor = normalized(requiredAnchor) ?: primaryAnchor(selected)
        val selectedLabels = labels(selected) + listOfNotNull(selectedAnchor)
        val selectedViewId = selected.viewIdResourceName?.takeIf { it.isNotBlank() }
        if (selectedLabels.isEmpty() && selectedViewId == null) return null

        val scored = candidates.asSequence()
            .filter { it.visibleToUser && it.enabled && !it.password && !it.bounds.isEmpty }
            // 텍스트와 클릭 행의 역할을 섞지 않는다. 행 전체 강조는 호출자가 따로 결정한다.
            .filter { it.clickable == selected.clickable && it.scrollable == selected.scrollable }
            .filter { AccessibilityNodePrivacy.isEditable(it)==AccessibilityNodePrivacy.isEditable(selected) }
            .filter { it.localRecipientKey == selected.localRecipientKey &&
                (it.localRecipientKey == null || RecipientResultBinding.isSafeMatch(it)) }
            .mapNotNull { current ->
                val currentLabels = labels(current)
                val matchingLabels = selectedLabels.intersect(currentLabels)
                val sameViewId = selectedViewId != null && selectedViewId == current.viewIdResourceName
                // 제목을 반드시 다시 찾는다. 요약 문구만 겹치는 다른 행은 같은 viewId여도 제외한다.
                if (selectedAnchor != null && selectedAnchor !in currentLabels) return@mapNotNull null
                // 이름 없는 항목은 고유 viewId와 클래스가 함께 일치할 때만 재선택한다.
                if (selectedLabels.isEmpty() && (!sameViewId || selected.className == null ||
                            selected.className != current.className)) return@mapNotNull null

                val ownLabelMatch = ownLabels(selected).intersect(ownLabels(current)).isNotEmpty()
                val rowLabelMatch = selected.descendantLabels.isNotEmpty() &&
                    current.descendantLabels.isNotEmpty() && matchingLabels.isNotEmpty()
                val identityScore =
                    (if (ownLabelMatch || rowLabelMatch) 50 else 30) +
                    (if (sameViewId) 60 else 0) +
                    (if (selected.className != null && selected.className == current.className) 20 else 0) +
                    matchingLabels.size.coerceAtMost(3)
                Match(current, identityScore, if (selected.path == current.path) 1 else 0)
            }
            .sortedWith(compareByDescending<Match> { it.identityScore }.thenByDescending { it.pathScore })
            .toList()

        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        // 같은 이름을 가진 후보의 근거가 비슷하면 잘못된 강조보다 재선택을 요청한다.
        if (second != null && best.identityScore - second.identityScore < MIN_SCORE_GAP) return null
        return best.node
    }

    /** 비밀번호와 표시용 fallback 이름을 비교 근거에 포함하지 않는다. */
    private fun labels(node: AccessibilityNodeModel): Set<String> =
        if(AccessibilityNodePrivacy.isEditable(node)) ownLabels(node)
        else (ownLabels(node) + node.descendantLabels.mapNotNull { normalized(it) }).toSet()

    /** text와 contentDescription은 둘 다 완전 일치 여부를 확인할 수 있는 이름이다. */
    private fun ownLabels(node: AccessibilityNodeModel): Set<String> =
        if(AccessibilityNodePrivacy.isEditable(node)) setOf(AccessibilityNodePrivacy.publicInputLabel(node))
        else listOfNotNull(normalized(node.text), normalized(node.contentDescription)).toSet()
            .ifEmpty { setOfNotNull(PublicNodeRoleLabels.label(node)) }

    /** 제목 기준은 text → contentDescription → 첫 자식 이름 순서이며, 다른 문구는 순위만 돕는다. */
    private fun primaryAnchor(node: AccessibilityNodeModel): String? =
        if(AccessibilityNodePrivacy.isEditable(node)) AccessibilityNodePrivacy.publicInputLabel(node)
        else normalized(node.text) ?: normalized(node.contentDescription) ?:
        node.descendantLabels.firstNotNullOfOrNull { normalized(it) } ?: PublicNodeRoleLabels.label(node)

    /** 앞뒤 공백 이외에는 원문을 유지하여 비슷해 보이는 다른 버튼을 선택하지 않는다. */
    private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** pathScore는 일관성 확인용이며 identityScore 동점을 해결하는 근거가 아니다. */
    private data class Match(val node: AccessibilityNodeModel, val identityScore: Int, val pathScore: Int)

    private const val MIN_SCORE_GAP = 15
}
