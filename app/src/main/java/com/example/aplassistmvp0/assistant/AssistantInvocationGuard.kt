package com.example.aplassistmvp0.assistant

/** 호출별 세대 번호입니다. 닫힌 창의 늦은 음성 결과가 새 요청을 실행하지 못하게 합니다. */
internal class AssistantInvocationGuard {
    private var sequence = 0L
    private var currentId: Long? = null
    private var handedOff = false

    fun begin(): Long {
        currentId = ++sequence
        handedOff = false
        return sequence
    }

    fun isCurrent(id: Long): Boolean = currentId == id
    /** The handed-off continuation stays current, but its closed input window cannot submit. */
    fun canSubmit(id: Long): Boolean = isCurrent(id) && !handedOff

    /** 안내 시작을 위해 창을 닫는 시점부터는 onHide를 사용자 취소로 해석하지 않습니다. */
    fun handOff(id: Long): Boolean {
        if (!isCurrent(id) || handedOff) return false
        handedOff = true
        return true
    }

    fun cancel(id: Long): Boolean {
        if (!isCurrent(id) || handedOff) return false
        currentId = null
        return true
    }

    fun finish(id: Long) {
        if (isCurrent(id)) currentId = null
    }
}
