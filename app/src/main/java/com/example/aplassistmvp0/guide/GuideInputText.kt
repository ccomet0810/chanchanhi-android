package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel

internal object GuideInputText {
    fun instruction(node:AccessibilityNodeModel,editing:Boolean=false):String {
        val label=GuidePolicy.label(node).removeSuffix(" 입력란").removeSuffix(" 입력칸").trim()
        if(editing)return "${label.ifBlank{"내용"}} 입력란의 내용을 수정해주세요."
        return when {
            Regex("메시지.*내용|문자.*내용|본문|message.*body",RegexOption.IGNORE_CASE).containsMatchIn(label)->"메시지 내용을 입력해주세요."
            Regex("받는\\s*사람|수신자|recipient",RegexOption.IGNORE_CASE).containsMatchIn(label)->"받는 사람의 이름이나 번호를 입력해주세요."
            Regex("검색|search|query",RegexOption.IGNORE_CASE).containsMatchIn(label)->"검색어를 입력해주세요."
            else->"${label.ifBlank{"내용"}} 입력란에 내용을 입력해주세요."
        }
    }
}
