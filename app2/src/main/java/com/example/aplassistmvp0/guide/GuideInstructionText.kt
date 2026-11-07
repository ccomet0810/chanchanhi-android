package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** Shorten a card's title only when it still identifies one control. Keep full evidence for matching. */
internal object GuideInstructionText {
    fun tap(node: AccessibilityNodeModel, screen: ScreenSnapshot): String {
        val full = GuidePolicy.label(node)
        val title = (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: node.descendantLabels.firstOrNull()).orEmpty().lineSequence().firstOrNull().orEmpty()
            .trim().replace(Regex("\\s+"), " ").let(AccessibilityNodePrivacy::redactIdentifiers)
        val unique = title.isNotBlank() && title.length <= 40 && GuidePolicy.candidates(screen).count {
            GuidePolicy.label(it).startsWith(title)
        } == 1
        val label=if(unique)title else full.take(80)
        val last=label.lastOrNull()
        val particle=if(last!=null && last in '가'..'힣' && (last.code-'가'.code)%28==0)"를" else "을"
        return "$label$particle 눌러주세요."
    }
}
