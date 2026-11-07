package com.example.aplassistmvp0.guide

internal object GuideOverlayPolicy {
    fun quotedChoice(message: String, label: String): Boolean = label.length >= 2 &&
        listOf("‘$label’", "'$label'", "“$label”", "\"$label\"").any(message::contains)
    /** Draw only the largest usable part of the real target outside the moving guide panel. */
    fun exposedTarget(target: com.example.aplassistmvp0.accessibility.NodeBounds?,
                      panel: com.example.aplassistmvp0.accessibility.NodeBounds?, gap: Int = 0): com.example.aplassistmvp0.accessibility.NodeBounds? {
        target ?: return null
        if (target.isEmpty) return null
        panel ?: return target
        val left = panel.left - gap; val top = panel.top - gap
        val right = panel.right + gap; val bottom = panel.bottom + gap
        if (target.right <= left || target.left >= right || target.bottom <= top || target.top >= bottom) return target
        return listOf(
            target.copy(bottom = minOf(target.bottom, top)),
            target.copy(top = maxOf(target.top, bottom)),
            target.copy(right = minOf(target.right, left)),
            target.copy(left = maxOf(target.left, right)),
        ).filterNot { it.isEmpty }.maxByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }
    }
    fun chooseTopForTarget(topTarget:Long,bottomTarget:Long,topOther:Long,bottomOther:Long,preferredTop:Boolean):Boolean =
        if(topTarget!=bottomTarget) topTarget<bottomTarget else chooseTop(topOther,bottomOther,preferredTop)
    /** Coarse root CONTENT events may never stop (ads). Require three fresh equal target
     * bindings and a longer stable window instead of waiting forever for the whole app. */
    fun stableDespiteContentUpdates(reads: Int, targetStableMs: Long, windowStableMs: Long): Boolean =
        reads >= 3 && targetStableMs >= 1_500L && windowStableMs >= 1_500L
    fun chooseTop(topOverlap: Long, bottomOverlap: Long, preferredTop: Boolean): Boolean =
        when { topOverlap < bottomOverlap -> true; bottomOverlap < topOverlap -> false; else -> preferredTop }
}
