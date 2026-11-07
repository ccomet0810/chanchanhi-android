package com.example.aplassistmvp0.guide

/** The OS supplies providers; no clock brand/package is selected from an app-name keyword list. */
internal object SystemCapabilityPolicy {
    fun action(goal:String):String? {
        val text=goal.replace(Regex("\\s+"),"")
        val alarm=Regex("알람|알림시계|모닝콜|깨워",RegexOption.IGNORE_CASE).containsMatchIn(text)
        val task=Regex("맞추|맞춰|설정|예약|켜|꺼|끄|울리|깨워|모닝콜|바꾸|변경",RegexOption.IGNORE_CASE).containsMatchIn(text)
        val discussion=Regex("추천|비교|무슨뜻|뜻이|차이|설명",RegexOption.IGNORE_CASE).containsMatchIn(text)
        return if(alarm && task && !discussion) "android.intent.action.SET_ALARM" else null
    }
}
