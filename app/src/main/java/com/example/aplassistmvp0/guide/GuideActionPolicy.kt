package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/** 동작과 결과 조건. 입력 원문이나 옛 숫자 ID를 재사용하지 않는다. */
internal object GuideActionPolicy {
    /** A carousel outside the guided control is not evidence that the user performed its action. */
    fun unrelatedScroll(decision: GuideDecision, requested: ScreenSnapshot, evidence: PublicActionEvidence?): Boolean {
        if(evidence==null || decision.action!="highlight" || evidence.windowId<0 || evidence.bounds.isEmpty) return false
        val target=requested.allNodes.singleOrNull { it.id==decision.targetNodeId } ?: return false
        if(target.bounds.isEmpty) return false
        if(evidence.windowId!=requested.windowId) return true
        if(decision.continuation=="find_target" && evidence.scrollDeltaX?.let { it!=0 }==true && evidence.scrollDeltaY==0) return true
        val a=target.bounds;val b=evidence.bounds
        return a.right<=b.left || b.right<=a.left || a.bottom<=b.top || b.bottom<=a.top
    }
    fun adjustable(node: AccessibilityNodeModel) = node.range?.usable == true &&
        (node.className.orEmpty().contains("SeekBar", true) || node.className.orEmpty().contains("Slider", true) || node.actions.any { it.id == 16908349 })
    fun isActionable(node: AccessibilityNodeModel): Boolean =
        node.clickable || node.scrollable || AccessibilityNodePrivacy.isEditable(node) || adjustable(node)
    private fun wantsOff(goal: String) = Regex("해제|체크.*취소|끄(?:기|고|려|는|면)|꺼(?:줘|주|주세요)|동의.*취소|turn\\s+off|uncheck", RegexOption.IGNORE_CASE).containsMatchIn(goal)
    fun option(node: AccessibilityNodeModel, goal: String, progress: GuideProgressContext = GuideProgressContext()): Boolean =
        isActionable(node) &&
            !(node.checkable == true && node.checked == 1 && !wantsOff(goal)) &&
            !(AccessibilityNodePrivacy.isEditable(node) && node.hasInput == true &&
                progress.previousAction == "fill_input" && progress.previousTarget == GuidePolicy.label(node) &&
                progress.outcome == "fill_input 결과 확인됨")

    fun enrich(decision: GuideDecision, screen: ScreenSnapshot, goal: String): GuideDecision {
        if (decision.action != "highlight" || decision.operation != null || decision.continuation != null) return decision
        val node = decision.targetNodeId?.let { GuidePolicy.resolveDecisionNode(screen, it) } ?: return decision
        val name = GuidePolicy.label(node).take(80)
        if (node.checkable == true) {
            val desired = if (wantsOff(goal)) 0 else 1
            return decision.copy(message = if (desired == 1) "$name 항목을 확인한 뒤 체크해주세요." else "$name 항목의 체크를 해제해주세요.",
                operation = "check", targetValue = desired.toFloat())
        }
        if (AccessibilityNodePrivacy.isEditable(node)) {
            val inputName=if(name.endsWith("입력란") || name.endsWith("입력칸")) name else "$name 입력란"
            if (node.hasInput == true) return decision.copy(message = if (node.focused == true)
                "$inputName 내용을 확인해주세요. 수정이 필요하면 입력해주세요."
                else "$inputName 내용을 확인해주세요. 수정하려면 입력란을 눌러주세요.", operation = "edit_input")
            if (node.hasInput == false && Regex("메시지|메세지|문자|sms", RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
                name.replace(" ", "") in setOf("받는사람", "수신자", "연락처검색또는번호입력"))
                return decision.copy(message = if (node.focused == true) "받는 사람의 이름이나 번호를 입력해주세요."
                    else "받는 사람 입력란을 눌러 이름이나 번호를 입력해주세요.", operation = "fill_input")
            return decision.copy(message = if (node.focused == true) "${inputName}에 필요한 내용을 입력해주세요."
                else "${inputName}을 눌러 필요한 내용을 입력해주세요.", operation = "fill_input")
        }
        if (adjustable(node)) {
            val range = node.range!!
            // GS25 같은 앱 이름의 숫자를 조절 목표로 오인하지 않는다.
            val requested = Regex("(?<![A-Za-z0-9])(-?\\d+(?:\\.\\d+)?)(?![\\d.])\\s*(%|퍼센트)?").find(goal)
            val raw = requested?.groupValues?.get(1)?.toFloatOrNull()
            val desired = raw?.let { if (requested.groupValues[2].isNotEmpty()) range.min + (range.max - range.min) * it / 100 else it }
                ?.takeIf { it.isFinite() && it in range.min..range.max }
            val target = desired
                // RANGE_TYPE_INT controls cannot reach a fractional target such as 4.2.
                ?.let { if (range.type == 0) it.roundToInt().toFloat().coerceIn(range.min, range.max) else it }
            val approximate=target!=null && desired!=null && abs(target-desired)>0.0001f
            if(approximate && Regex("정확히|반드시|exactly",RegexOption.IGNORE_CASE).containsMatchIn(goal))
                return decision.copy(action="manual",targetNodeId=null,continuation="wait_controls",message=
                    "$name 막대는 정수 단계여서 요청한 값을 정확히 맞출 수 없어요. 가장 가까운 값은 ${number(target!!)}입니다. 원하는 단계로 직접 조절해주세요.")
            return decision.copy(message = if (approximate) "$name 막대를 움직여 요청한 수준에 가장 가까운 값 ${number(target!!)}에 맞춰주세요. 정확히 같은 비율은 아니며 현재 값은 ${number(range.current)}입니다."
                else if (target != null) "$name 막대를 움직여 값을 ${number(target)}에 맞춰주세요. 현재 값은 ${number(range.current)}입니다."
                else "$name 막대를 직접 움직여 원하는 수준으로 맞춰주세요. 현재 값은 ${number(range.current)}입니다.", operation = "adjust_range", targetValue = target)
        }
        if (node.scrollable && !node.clickable) {
            val forward = node.actions.any { it.id in setOf(4096, 16908346, 16908347) }
            val horizontal = node.className.orEmpty().contains("Horizontal", true)
            val instruction = when {
                horizontal -> "이 영역을 왼쪽으로 밀어 다음 항목을 보여주세요."
                forward -> "이 영역을 위로 밀어 아래 항목을 보여주세요."
                else -> "이 영역을 아래로 밀어 위 항목을 보여주세요."
            }
            return decision.copy(message = instruction, continuation = "find_target")
        }
        return decision.copy(operation = "tap")
    }

    fun target(previous: AccessibilityNodeModel, live: ScreenSnapshot): AccessibilityNodeModel? = GuidePolicy.contextNodes(live).filter {
        it.localRecipientKey == previous.localRecipientKey &&
            it.className == previous.className && GuidePolicy.label(it) == GuidePolicy.label(previous) &&
            (previous.viewIdResourceName.isNullOrBlank() || it.viewIdResourceName == previous.viewIdResourceName)
    }.singleOrNull()

    fun fulfilled(decision: GuideDecision, requested: ScreenSnapshot, live: ScreenSnapshot): Boolean {
        if (live.packageName != requested.packageName || live.snapshotId <= requested.snapshotId || live.truncated) return false
        val previous = requested.allNodes.singleOrNull { it.id == decision.targetNodeId } ?: return false
        val current = target(previous, live) ?: return false
        return when (decision.operation) {
            "check" -> current.checkable == true && current.checked == decision.targetValue?.toInt() && previous.checked != current.checked
            "fill_input" -> false // A first character is not proof of finished input.
            // 원문 없이도 공개된 입력 오류가 해제된 경우는 확인할 수 있다. 포커스 변화만으로는 완료가 아니다.
            "edit_input" -> false
            "adjust_range" -> current.range?.let { range -> decision.targetValue?.let {
                abs(range.current - it) <= maxOf((range.max - range.min) * 0.01f, 0.01f)
            } ?: (previous.range?.current != range.current) } == true
            else -> false
        }
    }

    fun describe(node: AccessibilityNodeModel): String = buildString {
        append("활성=").append(node.enabled)
        node.checkable?.let { append(" 체크가능=").append(it) }; node.checked?.let { append(" 체크=").append(it) }
        node.selected?.let { append(" 선택=").append(it) }; node.focused?.let { append(" 포커스=").append(it) }
        if (AccessibilityNodePrivacy.isEditable(node)) append(" 입력=").append(node.hasInput?.toString() ?: "확인불가")
        node.contentInvalid?.let { append(" 오류=").append(it) }
        node.range?.let { append(" 범위=").append(number(it.min)).append("..").append(number(it.max)).append(" 현재=").append(number(it.current)) }
    }
    fun number(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(java.util.Locale.ROOT, "%.2f", value)
}

data class GuideProgressContext(val previousAction: String = "", val previousTarget: String = "", val outcome: String = "첫 안내")
