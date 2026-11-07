package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** A live, non-action instruction. Never proves a performed action or whole-goal completion. */
internal object GuideActivityWaitPolicy {
    enum class Mode(val operation:String,val prefix:String) {
        USER("wait_user","wait_user_node_"), SYSTEM("wait_system","wait_system_node_")
    }
    data class Selection(val mode:Mode,val nodeId:Int)
    data class Option(val value:String,val description:String)
    data class Options(val values:List<Option>,val truncated:Boolean)
    private val duration=Regex("(?i)^(?:[0-9]+(?:[.,][0-9]+)?\\s*(?:밀리초|초|분|시간|milliseconds?|seconds?|minutes?|hours?|ms|s|m|h)\\s*)+$")
    private val digitalDuration=Regex("^[0-9]{1,3}(?::[0-9]{2}){1,2}(?:[.,][0-9]{1,3})?$")
    private val percent=Regex("^[0-9]+(?:[.,][0-9]+)?\\s*(?:%|퍼센트)$")

    fun selection(value:String):Selection? {
        val mode=Mode.entries.firstOrNull{value.startsWith(it.prefix)}?:return null
        val raw=value.removePrefix(mode.prefix);val id=raw.toIntOrNull()?:return null
        return if(id>=0 && raw==id.toString())Selection(mode,id) else null
    }
    /** A known progress widget is useful observation even when no action button exists yet. */
    fun canObserveProgress(screen:ScreenSnapshot):Boolean = !screen.truncated && screen.viewport!=null &&
        GuidePolicy.contextNodes(screen).any{!it.clickable && it.className.orEmpty().contains("ProgressBar",true) &&
            anchor(screen,it.id,Mode.SYSTEM)==it}
    private fun anchor(screen:ScreenSnapshot,id:Int,mode:Mode):AccessibilityNodeModel? {
        val nodes=GuidePolicy.contextNodes(screen)
        val node=nodes.singleOrNull{it.id==id}?:return null
        val label=GuidePolicy.label(node)
        if(!node.enabled || !node.visibleToUser || node.bounds.isEmpty || label.isBlank() || label.length>120 ||
            GuideVisionPolicy.privateNode(node) || GuideVisionPolicy.generic(node) ||
            DirectDecisionPolicy.sensitiveGoal(label) || nodes.count{GuidePolicy.label(it)==label}!=1)return null
        if(mode==Mode.USER && (!node.clickable || node.scrollable || node.checkable==true || node.hasRangeInfo))return null
        if(mode==Mode.SYSTEM && progressCompleted(node))return null
        return node
    }
    fun options(screen:ScreenSnapshot,submitted:List<AccessibilityNodeModel>,limit:Int):Options {
        require(limit>=0)
        val user=submitted.filter{anchor(screen,it.id,Mode.USER)==it}.map{
            Option(Mode.USER.prefix+it.id,"이미 진행 중이며 사용자가 마칠 때 ‘${GuidePolicy.label(it)}’를 누름. 지금 누르는 지시가 아님")}
        val system=submitted.sortedBy{it.clickable}.filter{anchor(screen,it.id,Mode.SYSTEM)==it}.map{
            Option(Mode.SYSTEM.prefix+it.id,"‘${GuidePolicy.label(it)}’가 자동 작업의 진행 근거. 완료될 때까지 기다림. 이 항목을 누르지 않음")}
        val values=mutableListOf<Option>();var index=0
        while(values.size<limit && (index<user.size || index<system.size)) {
            if(index<user.size)values.add(user[index])
            if(values.size<limit && index<system.size)values.add(system[index])
            index++
        }
        return Options(values,values.size<user.size+system.size)
    }
    /** Admit additive options using the actual selected-provider encoding, retaining ordinary actions. */
    fun admittedCount(possible:Int,encodedBytes:(Int)->Int):Int {
        require(possible>=0)
        var low=1;var high=possible;var accepted=0
        while(low<=high) {
            val middle=low+(high-low)/2
            if(encodedBytes(middle)<=65_536){accepted=middle;low=middle+1}else high=middle-1
        }
        return accepted
    }
    val instructions="""
이미 요청한 활동이 진행 중이면 새 버튼을 계속 누르게 하지 마세요. 시간 측정·녹음·재생처럼 사용자가 마칠 때를 정하는 활동과, 다운로드·처리·예약한 타이머처럼 자동으로 끝나는 활동을 구분하세요.
wait_user_node_ID는 사용자가 원할 때 마치는 활동입니다. ID는 그때 사용할 종료/중지 버튼이어야 하며, 지금 누르라는 선택이 아닙니다.
wait_system_node_ID는 자동으로 끝나는 처리의 대기입니다. ID는 현재 진행을 보여주는 공개 항목이어야 합니다. 누르거나 취소하는 선택이 아닙니다.
공개된 진행 근거가 없거나 아직 시작하지 않았으면 대기를 선택하지 마세요. 단지 버튼이 있다는 이유는 근거가 아닙니다. 결제·전송·삭제·권한 확정은 활동 종료 버튼이 아닙니다.
대기는 목표 완료가 아닙니다. 대기 선택의 message는 빈 문자열로 두세요. 앱이 현재 근거와 사용자/자동 종료 방식에 맞는 문구를 표시합니다.
""".trimIndent()
    fun decision(value:String,screen:ScreenSnapshot):GuideDecision? {
        val selected=selection(value)?:return null
        val node=anchor(screen,selected.nodeId,selected.mode)?:return null
        val label=GuidePolicy.label(node).take(80).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1) else it}
        val message=if(selected.mode==Mode.USER)"마칠 때 ‘${label}’를 눌러주세요." else "끝날 때까지 기다려주세요."
        return GuideDecision("wait",node.id,message,operation=selected.mode.operation)
    }
    fun canonical(decision:GuideDecision,screen:ScreenSnapshot):GuideDecision? {
        val mode=Mode.entries.firstOrNull{it.operation==decision.operation}?:return null
        if(decision.action!="wait" || decision.continuation!=null || decision.targetValue!=null || decision.evidenceNodeIds.isNotEmpty())return null
        val id=decision.targetNodeId?:return null
        return GuideActivityWaitPolicy.decision(mode.prefix+id,screen)?.let{decision.copy(message=it.message)}
    }
    fun sameObservation(decision:GuideDecision,before:ScreenSnapshot,live:ScreenSnapshot):Boolean {
        val mode=Mode.entries.firstOrNull{it.operation==decision.operation}?:return false
        if(canonical(decision,before)==null || before.truncated || live.truncated ||
            before.packageName!=live.packageName || before.windowId!=live.windowId ||
            before.viewport==null || before.viewport!=live.viewport || before.blockingPopup!=live.blockingPopup ||
            live.snapshotId<before.snapshotId || live.capturedAtMillis<before.capturedAtMillis)return false
        if(decision.visionVerified && !GuideVisionPolicy.sameScreen(before,live))return false
        val original=decision.targetNodeId?.let{anchor(before,it,mode)}?:return false
        val current=GuideActionPolicy.target(original,live) ?: if(mode==Mode.SYSTEM && !original.clickable && pureReading(GuidePolicy.label(original)))
            GuidePolicy.contextNodes(live).filter{!it.clickable && !it.scrollable &&
                it.className==original.className && it.viewIdResourceName==original.viewIdResourceName &&
                it.localRecipientKey==original.localRecipientKey && pureReading(GuidePolicy.label(it)) &&
                reading(GuidePolicy.label(it),mode)==reading(GuidePolicy.label(original),mode)}.singleOrNull()
            else null
        current?:return false
        if(anchor(live,current.id,mode)!=current)return false
        return contextFingerprint(before,mode)==contextFingerprint(live,mode)
    }
    private fun progressCompleted(node:AccessibilityNodeModel):Boolean {
        if(node.hasRangeInfo && node.className.orEmpty().contains("ProgressBar",true) &&
            node.range?.let{it.usable && it.current>=it.max}==true)return true
        val text=GuidePolicy.label(node).trim()
        if(percent.matches(text))return text.takeWhile{it.isDigit() || it=='.' || it==','}.replace(',','.').toBigDecimalOrNull()
            ?.let{it>=java.math.BigDecimal(100)}==true
        return (duration.matches(text) || digitalDuration.matches(text)) && text.none{it in '1'..'9'}
    }

    /** Only whole public quantity readings vary. Unknown/error/status text stays exact.
     * This temporary comparison view is never passed as a requested frame or source receipt. */
    private fun contextFingerprint(screen:ScreenSnapshot,mode:Mode):String {
        val nodes=GuidePolicy.contextNodes(screen).map{node->
            if(node.clickable || node.scrollable || AccessibilityNodePrivacy.isEditable(node) || node.checkable==true)node
            else {
                val label=reading(GuidePolicy.label(node),mode)
                val state=node.stateDescription?.let{reading(it,mode)}
                val range=node.range?.takeIf{node.hasRangeInfo && node.className.orEmpty().contains("ProgressBar",true) && it.usable}
                    ?.let{it.copy(current=if(it.current>=it.max)it.max else it.min)}?:node.range
                node.copy(text=label,contentDescription=null,descendantLabels=emptyList(),stateDescription=state,range=range)
            }
        }
        return GuidePolicy.fingerprint(screen.copy(nodes=nodes,allNodes=nodes))
    }
    private fun reading(value:String,mode:Mode):String {
        val text=value.trim()
        if(duration.matches(text) || digitalDuration.matches(text))return if(mode==Mode.SYSTEM && text.none{it in '1'..'9'})
            encoded("duration","zero") else encoded("duration","active")
        if(percent.matches(text)) {
            val number=text.takeWhile{it.isDigit() || it=='.' || it==','}.replace(',','.').toBigDecimalOrNull()?:return encoded("literal",value)
            return if(number>=java.math.BigDecimal(100))encoded("progress","complete") else encoded("progress","active")
        }
        return encoded("literal",value)
    }
    private fun pureReading(value:String)=duration.matches(value.trim()) || digitalDuration.matches(value.trim()) || percent.matches(value.trim())
    private fun encoded(kind:String,value:String)="${kind.length}:$kind${value.length}:$value"
}
