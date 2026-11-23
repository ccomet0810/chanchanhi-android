package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds

enum class Phase { IDLE, THINKING, GUIDING, QUESTION, OPEN_APP, PAUSED, ERROR, COMPLETE, STOPPED }
enum class Tool { OBSERVE, CAPTURE_SCREEN, LIST_APPS, UPDATE_PLAN, OPEN_APP, GUIDE, ASK_USER, FINISH }
enum class Action { TAP, DOUBLE_TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, DRAG, HOLD_DRAG, ZOOM_IN, ZOOM_OUT, INPUT, ADJUST, BACK, HOME, NOTIFICATIONS, QUICK_SETTINGS }
data class PlanStep(val step: String, val status: String)
data class AppEntry(val packageName: String, val label: String)
/** Replay-only metadata. An accessibility name is not necessarily visible ink. */
data class ElementDisplay(val text: String?, val description: String?, val parentPath: String?)
data class Element(
    val id: Int, val path: String, val resource: String?, val role: String, val label: String,
    val bounds: NodeBounds, val enabled: Boolean, val visible: Boolean, val clickable: Boolean,
    val editable: Boolean, val scrollable: Boolean, val hasInput: Boolean?, val checked: Int?,
    val selected: Boolean?, val state: String?, val value: String? = null,
    val display: ElementDisplay? = null,
)
data class Observation(
    val id: Long, val packageName: String, val windowId: Int, val epoch: Long,
    val visualGeneration: Long, val width: Int, val height: Int, val complete: Boolean,
    val elements: List<Element>, val omitted: Int = 0,
    /** 비밀번호·입력란·비공개 미리보기 영역(화면 좌표). 스크린샷 가림에만 쓰며 AI 요청에 넣지 않는다. */
    val privateRegions: List<NodeBounds> = emptyList(),
)

/** Structural equality for trace deduplication; snapshot and animation counters do not alter node content. */
fun Observation.sameContent(other:Observation?):Boolean = other!=null && packageName==other.packageName &&
    windowId==other.windowId && width==other.width && height==other.height && complete==other.complete &&
    omitted==other.omitted && elements==other.elements
data class ScreenImage(val observation: Observation, val base64: String, val imageWidth: Int, val imageHeight: Int, val mime: String = "image/jpeg")
data class ToolCall(
    val callId: String, val tool: Tool, val summary: String, val snapshotId: Long? = null,
    val nodeId: Int? = null, val region: NodeBounds? = null, val action: Action? = null,
    val instruction: String? = null, val expectedChange: String? = null,
    val question: String? = null, val options: List<String> = emptyList(),
    val plan: List<PlanStep> = emptyList(), val evidenceNodeIds: List<Int> = emptyList(),
    val packageName: String? = null,
    /** Optional screen-normalized (0..1000) contact path, never screenshot pixels. */
    val gesturePath: List<Int>? = null,
)
data class ToolResult(val callId: String, val status: String, val detail: String, val observation: Observation? = null,
                      val apps: List<AppEntry> = emptyList(), val answer: String? = null)
data class Exchange(val call: ToolCall, val result: ToolResult)
data class UserAnswer(val question: String, val answer: String)
data class AgentRequest(val goal: String, val plan: List<PlanStep>, val screen: Observation,
                        val history: List<Exchange>, val omittedExchanges: Int, val image: ScreenImage?, val lastModelError: String? = null,
                        val userAnswers: List<UserAnswer> = emptyList(), val taskContext:String? = null)
class ModelOutputFailure(val reason: String) : IllegalStateException("AI 답변 형식을 다시 확인하고 있어요.")
class ObservationUnavailable(val reason:String):IllegalStateException("이 화면의 정보를 읽을 수 없어요. 설정을 마친 뒤 다시 확인해주세요.")

data class AgentState(
    val sessionId: String = "", val phase: Phase = Phase.IDLE, val goal: String = "",
    val message: String = "무엇을 도와드릴까요?", val plan: List<PlanStep> = emptyList(),
    val pending: ToolCall? = null, val screen: Observation? = null,
    val target: NodeBounds? = null, val turns: Int = 0,
)
data class UserInteraction(val serial: Long, val packageName: String, val windowId: Int,
                           val path: String?, val resource: String?, val kind: String)

interface AgentModel { suspend fun next(request: AgentRequest): ToolCall }
interface DevicePort {
    suspend fun observe(): Observation
    suspend fun capture(): ScreenImage
    fun installedApps(): List<AppEntry>
    fun render(state: AgentState)
    fun speak(message: String)
    fun stopSpeech()
    fun observationRecovery(failure:ObservationUnavailable,lastScreen:Observation?):String? = null
    val imagesAllowed: Boolean
}
interface SessionRecorder {
    fun begin(sessionId: String, goal: String)
    fun event(kind: String, value: Any?)
}

/** No application-name, goal-name or label allowlists. Rebind only a unique current public identity. */
object Grounding {
    private fun normal(s: String) = s.trim().replace(Regex("\\s+"), " ")
    fun resolve(original: Observation, latest: Observation, id: Int): Element? {
        if (original.packageName != latest.packageName || original.windowId != latest.windowId || original.epoch != latest.epoch) return null
        val source = original.elements.singleOrNull { it.id == id } ?: return null
        return latest.elements.filter {
            it.visible && it.enabled && !it.bounds.isEmpty && it.resource == source.resource &&
                it.role == source.role && normal(it.label) == normal(source.label) &&
                it.clickable == source.clickable && it.editable == source.editable && it.scrollable == source.scrollable &&
                it.checked == source.checked && it.selected == source.selected && it.value == source.value && it.state == source.state &&
                (source.resource != null || it.path == source.path)
        }.singleOrNull()
    }
    fun regionValid(image: ScreenImage?, basis: Observation, latest: Observation, region: NodeBounds): Boolean =
        image != null && image.observation.packageName == basis.packageName && image.observation.windowId == basis.windowId &&
            image.observation.epoch == basis.epoch && image.observation.visualGeneration == basis.visualGeneration &&
            image.observation.width == basis.width && image.observation.height == basis.height &&
            latest.packageName == basis.packageName &&
            latest.windowId == basis.windowId && latest.epoch == basis.epoch &&
            latest.visualGeneration == image.observation.visualGeneration && latest.width == basis.width && latest.height == basis.height &&
            image.imageWidth > 0 && image.imageHeight > 0 && !region.isEmpty && region.left >= 0 && region.top >= 0 &&
            region.right <= image.imageWidth && region.bottom <= image.imageHeight
    fun visualTarget(image: ScreenImage?, basis: Observation, latest: Observation, region: NodeBounds): NodeBounds? {
        if (!regionValid(image,basis,latest,region)) return null
        image!!
        fun x(value: Int) = (value.toLong() * latest.width / image.imageWidth).toInt()
        fun y(value: Int) = (value.toLong() * latest.height / image.imageHeight).toInt()
        return NodeBounds(x(region.left),y(region.top),x(region.right),y(region.bottom))
    }
}
