package com.example.aplassistmvp0.accessibility

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** 삼성 받는 사람 검색의 정확한 한 행만 로컬에서 연결한다. 연락처 원문은 외부로 보내지 않는다. */
object RecipientResultBinding {
    const val PACKAGE = "com.samsung.android.messaging"
    const val LABEL = "검색한 받는 사람"
    private val salt = UUID.randomUUID().toString()
    data class Match(val rowPath: String, val nameKey: String)

    fun key(value: CharSequence?): String? {
        val name = value?.toString()?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            ?.trim()?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT) ?: return null
        if (name.length !in 1..80 || name.none { it.isLetter() } ||
            AccessibilityNodePrivacy.redactIdentifiers(name) != name) return null
        return MessageDigest.getInstance("SHA-256").digest((salt + name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun isSearchInput(node: AccessibilityNodeModel): Boolean = AccessibilityNodePrivacy.isEditable(node) &&
        node.hasInput == true && !node.password && !node.sensitiveContent && node.visibleToUser && node.enabled &&
        Regex("연락처.*검색|받는\\s*사람|수신자|recipient", RegexOption.IGNORE_CASE)
            .containsMatchIn(AccessibilityNodePrivacy.publicInputLabel(node))

    fun isPicker(nodes: List<AccessibilityNodeModel>): Boolean = nodes.any {
        !it.password && !it.sensitiveContent && it.visibleToUser &&
            listOfNotNull(it.text, it.contentDescription).any { label ->
                label.filterNot(Char::isWhitespace) in setOf("대화멤버선택", "받는사람선택", "수신자선택") ||
                    Regex("^\\d+개검색됨$").matches(label.filterNot(Char::isWhitespace))
            }
    } && nodes.any(::isSearchInput) && nodes.none {
        AccessibilityNodePrivacy.isEditable(it) &&
            Regex("메시지.*(?:내용|입력)|본문|message body", RegexOption.IGNORE_CASE)
                .containsMatchIn(AccessibilityNodePrivacy.publicInputLabel(it))
    }

    fun bind(packageName: String?, nodes: List<AccessibilityNodeModel>, queryKeys: List<String>,
             matches: List<Match>, truncated: Boolean): List<AccessibilityNodeModel> {
        if (packageName != PACKAGE || truncated || queryKeys.size != 1 || !isPicker(nodes)) return nodes
        val query = queryKeys.single()
        val path = matches.filter { it.nameKey == query }.map { it.rowPath }.distinct().singleOrNull() ?: return nodes
        val row = nodes.singleOrNull { it.path == path } ?: return nodes
        if (!row.clickable || row.scrollable || row.password || AccessibilityNodePrivacy.isEditable(row) ||
            !row.enabled || !row.visibleToUser || row.bounds.isEmpty ||
            row.bounds.bottom - row.bounds.top > (row.bounds.right - row.bounds.left) / 2) return nodes
        return nodes.map { if (it !== row) it else it.copy(text = LABEL, contentDescription = null,
            descendantLabels = emptyList(), stateDescription = null, inputHint = null, sensitiveContent = false,
            actions = it.actions.map { action -> action.copy(label = null) }, localRecipientKey = query) }
    }

    fun isSafeMatch(node: AccessibilityNodeModel): Boolean = node.localRecipientKey?.matches(Regex("[0-9a-f]{64}")) == true &&
        node.text == LABEL && node.contentDescription == null && node.descendantLabels.isEmpty() &&
        node.stateDescription == null && node.inputHint == null && !node.sensitiveContent && !node.password &&
        !AccessibilityNodePrivacy.isEditable(node) && node.clickable && !node.scrollable &&
        node.actions.all { it.label == null }
}
