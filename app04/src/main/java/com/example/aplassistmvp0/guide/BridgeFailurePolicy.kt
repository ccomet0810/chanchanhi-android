package com.example.aplassistmvp0.guide

/** Subscription-only messages for 0.4; no paid API or alternate-model fallback. */
internal object BridgeFailurePolicy {
    fun message(status:Int,code:String?):String = when {
        status in setOf(401,403) -> "서버 연결 키와 구독 인증을 확인해주세요."
        code=="guide_busy" -> "다른 AI 요청이 진행 중이에요. 잠시 뒤 이어가세요."
        code=="guide_rate_limit" -> "요청이 이어지고 있어요. 잠시 뒤 이어가세요."
        code=="invalid_model_request" -> "앱과 서버의 0.4 연결 규격을 확인해주세요."
        code in setOf("invalid_model_result","invalid_guidance_v04_result","missing_completed") -> "AI 답변을 확인하지 못했어요. 현재 화면에서 다시 확인해주세요."
        status==409 -> "서버의 구독 연결 상태를 확인해주세요."
        status==429 -> "구독 사용 한도 또는 요청 속도를 확인해주세요."
        else -> "AI 서버에 연결하지 못했어요. 인터넷 연결을 확인한 뒤 이어가세요."
    }
}
