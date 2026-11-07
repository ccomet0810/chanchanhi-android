package com.example.aplassistmvp0.guide

/** Server error codes are categories, not text to display. Never echo an upstream body. */
internal object BridgeFailurePolicy {
    fun message(status: Int, code: String?) = when {
        status in setOf(401,403) -> "개인 서버 연결 키와 서버의 AI 인증 상태를 확인해주세요."
        code == "guide_busy" -> "다른 안내 요청을 처리 중이에요. 잠시 뒤 다시 요청해주세요. 비용 한도 초과는 아닙니다."
        code == "guide_rate_limit" -> "요청 간격이 짧아요. 잠시 뒤 다시 확인해주세요."
        code == "budget_limit" -> "오늘의 Decisions 비용 한도에 도달했거나 남은 한도로 이 요청을 처리할 수 없어요."
        code == "invalid_model_request" -> "앱과 AI 서버의 버전이 맞지 않아요. 앱과 서버 업데이트를 확인해주세요."
        code in setOf("invalid_model_result", "missing_completed", "invalid_model_usage") -> "AI 답변을 확인하지 못했어요. 다시 시도를 눌러주세요."
        status == 409 -> "서버의 선택한 AI 연결·사용 한도를 확인해주세요."
        status == 429 -> "AI 요청 속도 또는 계정 사용 한도를 확인해주세요. 다른 방식으로 자동 전환하지 않습니다."
        else -> "AI 서버에 연결하지 못했어요. 다시 시도를 눌러주세요."
    }
}
