package com.example.aplassistmvp0.guide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 음성 목표와 현재 안내 단계만 메모리에 보관한다. 접근성 트리나 음성 녹음은 저장하지 않는다. */
data class GuideUiState(
    val goal: String = "",
    val phase: String = "idle",
    val message: String = "요청을 말해주세요.",
    val busy: Boolean = false,
    val step: Int = 0,
    val targetPackageName: String = GuidePolicy.SETTINGS_PACKAGE,
    val targetAppLabel: String = "설정",
    // 실제 검증한 안내만 기록한다. 화면 대기 상태를 수동 안내로 오해하지 않게 한다.
    val currentAction: String? = null,
    val foregroundEvidence: String? = null,
    val decisionSource: String? = null,
    val awaitingConfirmation: Boolean = false,
    val awaitingRecipientSelection: Boolean = false,
    val awaitingControlCompletion: Boolean = false,
    val controlCompletionEnabled: Boolean = false,
    val controlCompletionLabel: String = V3InputCompletion.DONE_LABEL,
    val overlayObservation: GuideOverlayObservation? = null,
)

/** Presentation only. A pending question record can outlive its surface; native observation also gates answers. */
internal val GuideUiState.showsScopedQuestion:Boolean
    get()=goal.isNotBlank() && phase=="guiding" && !busy && currentAction=="ask"

/** Activity가 사라져도 서비스의 안내 상태를 같은 앱 프로세스에서 관찰할 수 있다. */
object GuideStateRepository {
    private val mutableState = MutableStateFlow(GuideUiState())
    val state = mutableState.asStateFlow()

    internal fun publish(state: GuideUiState) {
        GuideHistory.record(state)
        GuideCaption.message(state.message)
        mutableState.value = state
    }
}
