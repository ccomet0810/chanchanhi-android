package com.example.aplassistmvp0.guide

/** Android 저장소와 분리된 기록 제한·마스킹 규칙. 마스킹은 임의의 개인정보를 모두 식별하지는 않는다. */
internal object GuideHistoryPolicy {
    const val LIMIT = 50
    private val keys = Regex("(?i)(?:sk-|Bearer\\s+)[A-Za-z0-9_-]+")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val numbers = Regex("(?<![A-Za-z0-9])\\d(?:[ -]?\\d){3,}(?![A-Za-z0-9])")

    fun redact(text: String): String = text.let { keys.replace(it, "[키 숨김]") }
        .let { email.replace(it, "[이메일 숨김]") }
        .let { numbers.replace(it, "[숫자 숨김]") }
        .replace(Regex("\\s+"), " ").trim().take(500)

    fun append(entries: List<GuideHistoryEntry>, entry: GuideHistoryEntry): List<GuideHistoryEntry> {
        val last = entries.firstOrNull()
        if (last != null && last.requestId == entry.requestId && last.goal == entry.goal &&
            last.appLabel == entry.appLabel && last.phase == entry.phase && last.message == entry.message &&
            last.step == entry.step && last.action == entry.action) return entries
        return (listOf(entry) + entries).take(LIMIT)
    }

    fun phaseLabel(phase: String, action: String?): String = when (phase) {
        "thinking" -> "확인 중"
        "probing" -> "스크롤 확인 중"
        "waiting_screen" -> "화면 확인"
        "guiding" -> if (action == "manual") "추가 확인 필요" else "안내"
        "error" -> "안내 실패"
        "paused" -> "안내 중단"
        "done" -> if (action == "user_confirmed_result") "사용자 확인" else "완료"
        "confirming" -> "완료 확인"
        "opening_app" -> "앱 열기 안내"
        else -> "대기"
    }
}
