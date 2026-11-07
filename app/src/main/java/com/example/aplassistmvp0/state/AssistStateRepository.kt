package com.example.aplassistmvp0.state

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 노드 ID는 이 스냅샷 안에서만 유효하다. 좌표와 목록은 화면 갱신 시 새로 만든다. */
data class ScreenSnapshot(
    val snapshotId: Long,
    val packageName: String,
    val windowId: Int,
    val capturedAtMillis: Long,
    val nodes: List<AccessibilityNodeModel>,
    val allNodes: List<AccessibilityNodeModel>,
    val truncated: Boolean,
    val viewport: com.example.aplassistmvp0.accessibility.NodeBounds? = null,
    val blockingPopup: Boolean = false,
    val sourceEpoch: Long = 0,
    /** Conservative native tree-read interval, on the same monotonic clock as AccessibilityEvent. */
    val captureStartedAtUptimeMillis: Long? = null,
    val captureFinishedAtUptimeMillis: Long? = null,
    /** Native input-event generation when this tree read began. Never a completion claim. */
    val inputObservationVersion: Long = 0,
    /** Native Android decor geometry for local touch checks. */
    val platformActionBarBounds: com.example.aplassistmvp0.accessibility.NodeBounds? = null,
    /** Service interaction counter for rejecting image results after a scroll, click, or focus change. */
    val visualInteractionGeneration: Long = 0,
)

/** 현재 앞에 있는 앱과 마지막으로 수집한 다른 앱의 화면을 구분하여 표시한다. */
data class AssistUiState(
    val serviceConnected: Boolean = false,
    val foregroundPackage: String? = null,
    val snapshot: ScreenSnapshot? = null,
    val overlayStatus: String = "접근성 서비스를 켜주세요.",
    val highlightedNodeId: Int? = null,
    val pendingTargetLabel: String? = null,
    val lastEvent: String? = null,
    val error: String? = null,
)

/** 서버·파일·DB 없이 같은 앱 프로세스의 서비스와 Activity가 공유하는 메모리 상태다. */
object AssistStateRepository {
    private val mutableState = MutableStateFlow(AssistUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (AssistUiState) -> AssistUiState) = mutableState.update(transform)

    // 서비스 종료/기록 지우기 시 화면 내용도 메모리에서 비운다.
    fun clearSnapshot() = update { it.copy(snapshot = null, highlightedNodeId = null) }
}
