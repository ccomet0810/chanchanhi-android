package com.example.aplassistmvp0.update

/** Play 인앱 업데이트 방식입니다. */
enum class AppUpdateMode { NONE, FLEXIBLE, IMMEDIATE }

/**
 * Android 없이 검증할 수 있는 업데이트 방식 선택 규칙입니다.
 *
 * - Play Console(또는 Play Developer API)에서 우선순위 4 이상으로 올린 버전은 즉시 업데이트로 받습니다.
 *   브리지 계약이 바뀌어 예전 앱이 동작하지 않는 릴리스에 사용합니다.
 * - 그 밖의 버전은 사용 중인 안내를 끊지 않도록 유연 업데이트로 받고, 한 화면에서 거절하면 다시 묻지 않습니다.
 */
object AppUpdatePolicy {
    const val IMMEDIATE_PRIORITY = 4

    fun choose(
        updateAvailable: Boolean,
        priority: Int,
        flexibleAllowed: Boolean,
        immediateAllowed: Boolean,
        flexibleDeclined: Boolean,
    ): AppUpdateMode = when {
        !updateAvailable -> AppUpdateMode.NONE
        priority >= IMMEDIATE_PRIORITY && immediateAllowed -> AppUpdateMode.IMMEDIATE
        flexibleAllowed && !flexibleDeclined -> AppUpdateMode.FLEXIBLE
        else -> AppUpdateMode.NONE
    }
}
