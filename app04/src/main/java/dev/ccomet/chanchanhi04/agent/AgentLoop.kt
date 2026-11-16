package dev.ccomet.chanchanhi04.agent

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Application-owned tool loop. All entry points are serialized on the UI dispatcher. */
class AgentLoop(private val scope: CoroutineScope, private val model: AgentModel,
                private val device: DevicePort, private val recorder: SessionRecorder) {
    private val mutable = MutableStateFlow(AgentState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val exchanges = mutableListOf<Exchange>()
    private var totalExchanges = 0
    private val userAnswers = mutableListOf<UserAnswer>()
    private val usedCalls = mutableSetOf<String>()
    private var basis: Observation? = null
    private var image: ScreenImage? = null
    private var lastInteraction = 0L
    private var waitingAfterSerial = 0L
    private var targetElement: Element? = null
    private var automaticTools = 0
    private var failures = 0
    private var settling: ToolCall? = null
    private var modelRejections = 0
    private var lastModelError: String? = null
    private val recentGuides=mutableListOf<Pair<ToolCall,Observation>>()
    private var observedAction:String?=null

    fun start(goal: String, waitForUser:String?=null) {
        require(goal.isNotBlank() && goal.length <= 2000)
        cancelWork()
        interruptPending("superseded", "새 요청을 받아 이전 안내를 중단했습니다.")
        if(state.value.sessionId.isNotEmpty()) recorder.event("session_superseded",null)
        exchanges.clear(); userAnswers.clear(); usedCalls.clear(); recentGuides.clear(); observedAction=null; image = null; basis = null; targetElement = null
        totalExchanges = 0
        automaticTools = 0; failures = 0
        modelRejections = 0; lastModelError = null
        val id = UUID.randomUUID().toString()
        recorder.begin(id, goal.trim())
        publish(AgentState(sessionId = id, phase = if(waitForUser==null)Phase.THINKING else Phase.PAUSED, goal = goal.trim(), message = waitForUser?:"화면을 살펴보고 있어요."))
        if(waitForUser==null)launchLoop()else recorder.event("waiting_capability_setup",waitForUser)
    }

    fun pause(reason: String = "안내를 잠시 멈췄어요.") {
        if (state.value.phase in setOf(Phase.IDLE, Phase.STOPPED, Phase.COMPLETE)) return
        cancelWork()
        interruptPending("interrupted", reason)
        image = null; targetElement = null; device.stopSpeech()
        publish(state.value.copy(phase = Phase.PAUSED, message = reason, pending = null, target = null))
        recorder.event("paused", reason)
    }
    fun stop() {
        cancelWork(); interruptPending("cancelled", "사용자가 안내를 끝냈습니다.")
        device.stopSpeech(); image = null; targetElement = null
        publish(state.value.copy(phase = Phase.STOPPED, message = "안내를 끝냈어요.", pending = null, target = null))
        recorder.event("stopped", null)
    }
    fun resume() {
        if (state.value.phase !in setOf(Phase.PAUSED, Phase.ERROR)) return
        cancelWork(); automaticTools = 0; failures = 0; image = null
        recentGuides.clear()
        modelRejections = 0
        recorder.event("resumed", null)
        publish(state.value.copy(phase = Phase.THINKING, message = "현재 화면에서 다시 확인할게요.", pending = null, target = null))
        launchLoop()
    }
    fun repeat() { device.speak(state.value.message) }
    /** Only app-owned verified OS capability/assistant callbacks call this, never model JSON. */
    internal fun completeVerified(expectedSessionId:String,message:String):Boolean{
        if(state.value.sessionId!=expectedSessionId||state.value.phase in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE))return false
        cancelWork();interruptPending("verified_capability","운영체제의 실제 설정과 호출을 확인했습니다.")
        image=null;targetElement=null
        publish(state.value.copy(phase=Phase.COMPLETE,message=message,pending=null,target=null,plan=state.value.plan.map{it.copy(status="completed")}))
        device.speak(message);recorder.event("capability_completed",message);return true
    }

    /** An actual OS capability transition invalidates pending advice, including an in-flight model.
     * It never resumes a user pause or treats the timer used to poll OS state as evidence. */
    internal fun recheckVerifiedState(expectedSessionId:String):Boolean{
        if(state.value.sessionId!=expectedSessionId||state.value.phase !in setOf(Phase.THINKING,Phase.GUIDING,Phase.QUESTION,Phase.OPEN_APP))return false
        cancelWork();interruptPending("os_state_changed","운영체제의 실제 권한 또는 도우미 상태가 바뀌었습니다. 새 관측과 현재 OS 상태로 다시 판단하세요.")
        image=null;targetElement=null;device.stopSpeech();automaticTools=0
        publish(state.value.copy(phase=Phase.THINKING,message="실제 설정 상태를 다시 확인하고 있어요.",pending=null,target=null))
        launchLoop();return true
    }

    fun answer(value: String) {
        val call = state.value.pending ?: return
        if (state.value.phase != Phase.QUESTION || value.isBlank() || value.length > 2000) return
        settle(call, "user_answer", "사용자가 질문에 답했습니다.", value.trim())
    }
    fun userFinished() {
        val call = state.value.pending ?: return
        if (state.value.phase !in setOf(Phase.GUIDING, Phase.OPEN_APP)) return
        settle(call, "user_confirmed_action", "사용자가 행동 후 확인을 눌렀습니다. 성공 여부는 새 관찰로 판단하세요.")
    }

    /** Input editing never consumes GUIDE. Finishing a sentence is a user decision. */
    fun onInteraction(event: UserInteraction) {
        lastInteraction = maxOf(lastInteraction, event.serial)
        val call = state.value.pending ?: return
        val target = targetElement ?: return
        val screen = state.value.screen ?: return
        if (state.value.phase != Phase.GUIDING || event.serial <= waitingAfterSerial || call.action == Action.INPUT ||
            event.packageName != screen.packageName || event.windowId != screen.windowId) return
        val matches = if(event.path != null) event.path == target.path || event.path.startsWith(target.path + "/") else
            event.resource != null && event.resource == target.resource && screen.elements.count { it.resource == event.resource } == 1
        val expectedKind = when (call.action) {
            Action.TAP -> "click"
            Action.LONG_PRESS -> "long_click"
            Action.SWIPE_UP, Action.SWIPE_DOWN, Action.SWIPE_LEFT, Action.SWIPE_RIGHT -> "scroll"
            else -> return
        }
        if (matches && event.kind == expectedKind) {
            observedAction=call.callId
            settle(call, "action_observed", "대상에서 ${event.kind} 사건을 관찰했습니다. 성공 여부는 새 관찰로 판단하세요.")
        }
    }

    /** Refresh an existing highlight without replacing speech or completing a pending action. */
    fun onObservation(latest: Observation) {
        if (state.value.phase != Phase.GUIDING) return
        val call = state.value.pending ?: return
        val original = basis ?: return
        if(latest.packageName!=original.packageName || latest.windowId!=original.windowId || latest.epoch!=original.epoch) {
            settle(call,"screen_changed","대기 중 앱 또는 창이 바뀌었습니다. 이전 행동이 성공했다고 가정하지 말고 현재 화면에서 목표를 이어가세요.")
            return
        }
        val element = call.nodeId?.let { Grounding.resolve(original, latest, it) }
        if(call.gesturePath!=null&&!original.sameContent(latest)){
            settle(call,"gesture_path_changed","경로 안내 중 화면 배치가 달라졌습니다. 이전 경로를 재사용하지 말고 현재 화면에서 다시 정하세요.");return
        }
        // Replan for a changed target or a real change after its interaction. Unrelated banners
        // and clocks alone must not consume a guide. Input waits for the whole entry.
        if(call.action!=Action.INPUT&&!original.sameContent(latest)&&
            (observedAction==call.callId||call.nodeId!=null&&element==null)){
            settle(call,"content_changed","안내 중 실제 화면 내용이 바뀌었습니다. 이전 행동의 성공을 가정하지 말고 목표에 관련된 변화인지 새 관측으로 확인하세요.");return
        }
        targetElement = element
        val bounds = element?.bounds ?: call.region?.let { Grounding.visualTarget(image, original, latest, it) }
        publish(state.value.copy(screen = latest, target = bounds))
    }

    fun onObservationUnavailable(failure:ObservationUnavailable){
        if(state.value.phase!=Phase.GUIDING)return
        cancelWork();interruptPending("observation_failed","새 창의 정보를 읽지 못해 이전 대상을 지웠습니다.")
        if(!recoverObservation(failure))pause(failure.message.orEmpty())
    }
    private fun recoverObservation(failure:Exception):Boolean{
        val unavailable=failure as? ObservationUnavailable?:return false
        val instruction=device.observationRecovery(unavailable,state.value.screen)?:return false
        image=null;targetElement=null
        publish(state.value.copy(phase=Phase.PAUSED,message=instruction,pending=null,target=null))
        device.speak(instruction);recorder.event("observation_wait",mapOf("reason" to unavailable.reason,"coordinatesShown" to false))
        return true
    }
    private fun cancelWork() { generation++; job?.cancel(); job = null }
    private fun publish(value: AgentState) { mutable.value = value; device.render(value) }
    private fun interruptPending(status: String, detail: String) {
        (state.value.pending ?: settling)?.let { append(it, ToolResult(it.callId, status, detail)) }
        settling = null
    }
    private fun append(call: ToolCall, result: ToolResult) {
        exchanges += Exchange(call, result)
        totalExchanges++
        if(exchanges.size>24)exchanges.removeAt(0)
        recorder.event("tool_result", result)
    }
    private fun launchLoop(firstObservation: Observation? = null) {
        val token = generation
        job = scope.launch {
            // Consume only the fresh result acquired immediately before this loop. Never cache across user waits.
            var collected = firstObservation
            try {
                while (token == generation && state.value.phase == Phase.THINKING) {
                    if (++automaticTools > 12) {
                        pause("확인이 반복되고 있어요. 현재 화면에서 이어갈 수 있어요."); return@launch
                    }
                    val began = System.nanoTime()
                    val reused = collected != null
                    val screen = collected ?: withTimeout(8000) { device.observe() }
                    collected = null
                    if (token != generation) return@launch
                    recorder.event("observation_timing", mapOf("elapsedMs" to (System.nanoTime()-began)/1_000_000,
                        "reusedFreshResult" to reused, "nodeCount" to screen.elements.size))
                    basis = screen
                    publish(state.value.copy(screen = screen, turns = state.value.turns + 1))
                    recorder.event("observation", screen)
                    val request = AgentRequest(state.value.goal, state.value.plan, screen, exchanges.toList(),
                        totalExchanges-exchanges.size, image?.takeIf { it.observation.id == screen.id ||
                            it.observation.packageName == screen.packageName && it.observation.windowId == screen.windowId && it.observation.epoch == screen.epoch &&
                            it.observation.width == screen.width && it.observation.height == screen.height &&
                            it.observation.visualGeneration == screen.visualGeneration },lastModelError,userAnswers.toList())
                    val call = try { withTimeout(45000) { model.next(request) } }
                    catch (rejected: ModelOutputFailure) {
                        if(token != generation) return@launch
                        lastModelError = rejected.reason
                        recorder.event("model_rejected",mapOf("reason" to rejected.reason,"attempt" to ++modelRejections))
                        if(modelRejections>=3) throw IllegalStateException("AI 답변 형식을 확인하지 못했어요. 현재 화면에서 다시 시도할 수 있어요.")
                        continue
                    }
                    if (token != generation) return@launch
                    modelRejections = 0; lastModelError = null
                    recorder.event("tool_call", call)
                    if (!usedCalls.add(call.callId)) {
                        append(call, ToolResult(call.callId, "tool_error", "이미 사용한 callId입니다. 새 호출 번호로 현재 결과를 확인하세요."))
                        if (++failures >= 3) throw IllegalStateException("AI의 도구 요청이 반복되어 멈췄어요.")
                        continue
                    }
                    collected = execute(call, screen)
                }
            } catch (_: TimeoutCancellationException) {
                if (token == generation) publish(state.value.copy(phase = Phase.ERROR, message = "응답을 기다리는 시간이 길어졌어요. 다시 확인해주세요.", pending = null, target = null))
                recorder.event("timeout", null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) {
                    if(recoverObservation(failure))return@launch
                    image = null; targetElement = null
                    val message = failure.message?.take(180) ?: "현재 화면을 확인하지 못했어요."
                    recorder.event("error", message)
                    publish(state.value.copy(phase = Phase.ERROR, message = message, pending = null, target = null))
                }
            }
        }
    }

    private suspend fun execute(call: ToolCall, original: Observation): Observation? {
        fun result(status: String, detail: String, screen: Observation? = null, apps: List<AppEntry> = emptyList()) {
            append(call, ToolResult(call.callId, status, detail, screen, apps))
        }
        suspend fun reject(detail: String) {
            result("tool_error", detail)
            if (++failures >= 3) throw IllegalStateException("안내 대상을 확인하지 못했어요. 현재 화면에서 다시 시도해주세요.")
        }
        if (call.plan.isNotEmpty() && (call.plan.size>12 || call.plan.count{it.status=="in_progress"}>1 ||
            call.plan.any{it.step.isBlank() || it.step.length>180 || it.status !in setOf("pending","in_progress","completed")})) {
            reject("계획은 1~12개 단계와 pending/in_progress/completed 상태로 작성하세요."); return null
        }
        fun applyPlan() {
            if(call.plan.isNotEmpty()) {
                publish(state.value.copy(plan=call.plan))
                recorder.event("plan_updated",mapOf("callId" to call.callId,"combinedWith" to call.tool.name.lowercase(),"plan" to call.plan))
            }
        }
        when (call.tool) {
            Tool.OBSERVE -> {
                val observed=device.observe(); applyPlan(); result("observed", "현재 화면을 다시 읽었습니다.", observed)
                return observed
            }
            Tool.CAPTURE_SCREEN -> {
                if (!device.imagesAllowed) { result("unavailable", "사용자가 이미지 확인을 허용하지 않았습니다. 접근성 정보로 판단하거나 필요한 행동을 물어보세요."); return null }
                try {
                    val captured = device.capture()
                    image = captured
                    applyPlan()
                    result("image_captured", "현재 화면 이미지입니다. 좌표는 ${captured.observation.width}×${captured.observation.height} 픽셀입니다.", captured.observation)
                    // Image encoding can suspend while the underlying screen changes; re-observe before model dispatch.
                    return null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result("unavailable", "화면 이미지를 읽지 못했습니다. 보호 화면과 잠금을 우회하지 마세요.") }
            }
            Tool.LIST_APPS -> { val apps=device.installedApps(); applyPlan(); result("apps_listed", "설치된 공개 실행 앱입니다.", apps = apps) }
            Tool.UPDATE_PLAN -> {
                if (call.plan.isEmpty() || call.plan.size > 12 || call.plan.count { it.status == "in_progress" } > 1 ||
                    call.plan.any { it.step.isBlank() || it.step.length > 180 || it.status !in setOf("pending", "in_progress", "completed") }) {
                    reject("계획은 1~12개 단계와 pending/in_progress/completed 상태로 작성하세요."); return null
                }
                applyPlan()
                result("plan_updated", "계획을 갱신했습니다. 계획의 completed 표시는 실제 결과 검증을 대신하지 않습니다.")
            }
            Tool.OPEN_APP -> {
                if (device.installedApps().none { it.packageName == call.packageName }) { reject("설치 앱 목록에서 실제 실행 가능한 앱을 선택하세요."); return null }
                applyPlan()
                // Ask the model for grounded HOME/search/icon instructions instead of a launch shortcut.
                result("manual_app_navigation_required", "사용자가 직접 앱을 찾고 열어야 합니다. 현재 화면에서 홈으로 가기, 앱 목록이나 검색, 실제 앱 아이콘을 순서대로 guide로 안내하세요. 앱을 자동 실행하지 마세요.")
            }
            Tool.ASK_USER -> {
                if (call.question.isNullOrBlank()) { reject("질문 내용을 작성하세요."); return null }
                applyPlan()
                waitFor(call, Phase.QUESTION, call.question, null)
            }
            Tool.GUIDE -> {
                if (call.snapshotId != original.id || call.instruction.isNullOrBlank() || call.action == null || call.expectedChange.isNullOrBlank()) {
                    reject("현재 snapshotId, 행동, 안내 문구, 확인할 변화가 필요합니다."); return null
                }
                val latest = device.observe()
                val global = call.action in setOf(Action.BACK, Action.HOME, Action.NOTIFICATIONS, Action.QUICK_SETTINGS)
                val target = call.nodeId?.let { Grounding.resolve(original, latest, it) }
                val visual = call.region?.let { Grounding.visualTarget(image, original, latest, it) }
                if (!global && target == null && visual == null) { reject("대상이 최신 화면에 유일하게 연결되지 않습니다. 다시 관찰하세요."); return null }
                if (global && (latest.packageName != original.packageName || latest.windowId != original.windowId)) { reject("시스템 동작 안내 전에 화면이 바뀌었습니다. 다시 관찰하세요."); return null }
                if(global&&recentGuides.count{(previous,screen)->previous.action==call.action&&screen.sameContent(latest)}>=2){
                    reject("같은 화면에서 같은 시스템 행동 안내가 반복됐습니다. 경로를 재계획하거나 필요한 사실을 질문하세요. 반복 행동을 다시 안내하지 마세요.");return null
                }
                if (call.action == Action.INPUT && target?.editable != true && visual == null) { reject("해당 노드는 입력란이 아닙니다."); return null }
                if(!GestureMapping.pathValid(call.action,call.gesturePath,target?.bounds?:visual,latest.width,latest.height)||
                    call.gesturePath!=null&&!original.sameContent(latest)){
                    reject("옮기기는 최신 화면의 시작·도착 gesturePath가 필요합니다. 시작은 대상 안에, 조절은 양 끝 모두 대상 안에 두세요. 화면 배치가 바뀌면 다시 관찰하세요.");return null
                }
                applyPlan()
                recentGuides+=call to latest
                if(recentGuides.size>12)recentGuides.removeAt(0)
                basis = original; targetElement = target; waitingAfterSerial = lastInteraction
                publish(state.value.copy(screen = latest))
                waitFor(call, Phase.GUIDING, call.instruction, target?.bounds ?: visual)
            }
            Tool.FINISH -> {
                if (call.snapshotId != original.id) {
                    reject("완료는 현재 관찰의 snapshotId가 필요합니다."); return null
                }
                val latest = device.observe()
                val nativeEvidence=original.complete && call.evidenceNodeIds.isNotEmpty() && call.evidenceNodeIds.all { Grounding.resolve(original,latest,it)!=null }
                val visualEvidence=call.region?.let{Grounding.visualTarget(image,original,latest,it)}!=null
                if(!nativeEvidence && !visualEvidence) {reject("현재 화면의 결과 노드 또는 유효한 최신 이미지 영역 근거가 필요합니다. 바뀐 화면을 다시 확인하세요.");return null}
                applyPlan()
                result("completed_with_evidence", call.summary, latest)
                image = null; targetElement = null
                publish(state.value.copy(phase = Phase.COMPLETE, message = call.instruction ?: "안내를 마쳤어요.", pending = null, target = null, screen = latest))
                device.speak(state.value.message)
                recorder.event("completed", call.summary)
            }
        }
        return null
    }

    private fun waitFor(call: ToolCall, phase: Phase, message: String, target: com.example.aplassistmvp0.accessibility.NodeBounds?) {
        failures = 0; automaticTools = 0
        observedAction=null
        publish(state.value.copy(phase = phase, message = message, pending = call, target = target))
        recorder.event("waiting_user", mapOf("callId" to call.callId, "phase" to phase.name))
        device.speak(message)
    }
    private fun settle(call: ToolCall, status: String, detail: String, answer: String? = null) {
        if(status=="user_answer" && answer!=null) userAnswers += UserAnswer(call.question.orEmpty(),answer)
        cancelWork()
        val token = generation
        targetElement = null; device.stopSpeech(); image = null; settling = call
        publish(state.value.copy(phase = Phase.THINKING, message = "바뀐 화면을 확인하고 있어요.", pending = null, target = null))
        job = scope.launch {
            try {
                delay(450)
                val observed = withTimeout(8000) { device.observe() }
                if (token != generation) return@launch
                if(status=="action_observed"&&basis?.sameContent(observed)==true){
                    // A matching accessibility click can be emitted before a dialog loads or
                    // without any effect at all. The settling delay is never success evidence.
                    settling=null
                    val original=basis!!
                    targetElement=call.nodeId?.let{Grounding.resolve(original,observed,it)}
                    publish(state.value.copy(phase=Phase.GUIDING,screen=observed,pending=call,
                        message=call.instruction.orEmpty(),target=targetElement?.bounds?:call.region?.let{Grounding.visualTarget(image,original,observed,it)}))
                    recorder.event("action_waiting_for_change",mapOf("callId" to call.callId,"successAssumed" to false))
                    return@launch
                }
                append(call, ToolResult(call.callId, status, detail, observed, answer = answer))
                settling = null
                automaticTools = 0
                launchLoop(observed)
            } catch (_: TimeoutCancellationException) {
                if (token == generation) {
                    append(call, ToolResult(call.callId,"observation_failed","행동 뒤 화면 읽기 시간이 초과했습니다. 행동을 반복하지 말고 재관찰하세요.", answer = answer))
                    settling = null
                    publish(state.value.copy(phase = Phase.ERROR,message = "화면을 읽는 시간이 길어졌어요. 다시 확인해주세요."))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) {
                    append(call, ToolResult(call.callId, "observation_failed", "행동 뒤 화면을 읽지 못했습니다. 행동을 성공으로 간주하거나 자동으로 반복하지 마세요.", answer = answer))
                    settling = null
                    if(recoverObservation(failure))return@launch
                    publish(state.value.copy(phase = Phase.ERROR, message = "행동 뒤 화면을 읽지 못했어요. 현재 화면에서 다시 확인해주세요."))
                }
            }
        }
    }
}
