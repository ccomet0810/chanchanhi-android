package com.example.aplassistmvp0.accessibility

/** 화면 픽셀 좌표를 저장한다. Android 객체를 사용하지 않아 일반 JVM에서도 검증할 수 있다. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** 너비나 높이가 없는 영역은 화면에서 강조할 수 없다. */
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/** 접근성 노드가 제공하는 동작의 정보만 기록하며, 동작 자체를 실행하지 않는다. */
data class AccessibilityActionModel(val id: Int, val label: String?)

data class NodeRange(val min: Float, val max: Float, val current: Float, val type: Int) {
    val usable: Boolean get() = min.isFinite() && max.isFinite() && current.isFinite() && max > min && current in min..max
}

/**
 * 한 번 수집한 화면의 불변 값이다. AccessibilityNodeInfo 참조는 보관하지 않는다.
 * id와 path는 이 스냅샷 안의 디버그 식별자이며, 다른 화면에서도 같은 요소라는 뜻은 아니다.
 */
data class AccessibilityNodeModel(
    val id: Int,
    val path: String,
    val parentPath: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val visibleToUser: Boolean,
    val password: Boolean,
    val bounds: NodeBounds,
    val actions: List<AccessibilityActionModel>,
    val descendantLabels: List<String> = emptyList(),
    val clickableAncestorPath: String? = null,
    // 입력 값은 보관하지 않고 화면이 제공한 입력란 힌트만 별도로 사용한다.
    val editable: Boolean = false,
    val inputHint: String? = null,
    // 메시지 미리보기로 확인한 노드와 그 하위 내용은 AI 안내에서 제외한다.
    val sensitiveContent: Boolean = false,
    // 범위 위젯 여부. 입력 원문은 보관하지 않고 공개된 범위/현재 숫자만 별도로 수집한다.
    val hasRangeInfo: Boolean = false,
    // null은 읽기 실패/적용 불가다. 입력 원문은 저장하지 않고 유무만 보관한다.
    val checkable: Boolean? = null,
    val checked: Int? = null,
    val selected: Boolean? = null,
    val focused: Boolean? = null,
    val hasInput: Boolean? = null,
    val contentInvalid: Boolean? = null,
    val range: NodeRange? = null,
    val stateDescription: String? = null,
    // 단말 안에서만 사용하는 검색 결과 결합 키. 이름·번호·입력 원문은 보관하지 않는다.
    val localRecipientKey: String? = null,
    // 프로세스 내 입력 수정 번호. 원문·해시가 아니며 모델/로그/저장소에 전달하지 않는다.
    val localInputRevision: Long? = null,
    val heading: Boolean = false,
    val paneTitle: String? = null,
) {
    /** 클릭 행 자체에 글자가 없어도 가까운 자식의 글자를 목록 이름으로 사용한다. */
    val displayLabel: String
        get() = when {
            password -> "비밀번호 입력란 (내용 숨김)"
            sensitiveContent -> "메시지 미리보기 (내용 숨김)"
            editable -> AccessibilityNodePrivacy.publicInputLabel(this)
            !text.isNullOrBlank() -> text
            !contentDescription.isNullOrBlank() -> contentDescription
            descendantLabels.isNotEmpty() -> descendantLabels.joinToString(" · ")
            PublicNodeRoleLabels.label(this) != null -> PublicNodeRoleLabels.label(this)!!
            else -> "${className?.substringAfterLast('.') ?: "노드"} #$id"
        }
}

/** 이름 없는 일반 아이콘은 그대로 둔다. 공개된 닫기 리소스/동작과 스크롤 역할만 이름을 보완한다. */
internal object PublicNodeRoleLabels {
    fun label(node: AccessibilityNodeModel): String? {
        if (node.password || node.sensitiveContent || AccessibilityNodePrivacy.isEditable(node) ||
            AccessibilityNodePrivacy.isSensitivePreviewResource(node.viewIdResourceName, node.text)) return null
        val resource = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(java.util.Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
        if (node.clickable && (resource in closeResources || node.actions.any {
                it.label?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("닫기", "close", "dismiss") })) return "닫기"
        if (node.hasRangeInfo && node.className.orEmpty().let { it.contains("SeekBar", true) || it.contains("Slider", true) }) return "조절 막대"
        if (node.className.orEmpty().contains("ProgressBar", true)) return "진행 표시"
        return if (node.scrollable) "화면 목록" else null
    }
    private val closeResources = setOf("close", "btnclose", "buttonclose", "ivclose", "imgclose", "imageclose",
        "popupclose", "btnpopupclose", "closepopup", "btnclosepopup", "dialogclose", "btndialogclose", "icclose", "closebutton", "closebtn")
}

/** 앱이 공개한 리소스 이름으로 확인할 수 있는 미리보기만 보수적으로 가린다. */
object AccessibilityNodePrivacy {
    private val emailIdentifier=Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val numberIdentifier=Regex("(?<![A-Za-z0-9])\\+?\\d(?:[ ()-]?\\d){6,}(?![A-Za-z0-9])")
    private val paneWhitespace = Regex("\\s+")
    fun redactIdentifiers(value: String): String = emailIdentifier.replace(value,"[이메일 숨김]")
        .let { numberIdentifier.replace(it,"[연락처 숨김]") }
    fun hasHiddenIdentifier(node: AccessibilityNodeModel): Boolean =
        (listOfNotNull(node.text,node.contentDescription)+node.descendantLabels).any {
            it.contains("[이메일 숨김]") || it.contains("[연락처 숨김]") || redactIdentifiers(it)!=it
        }
    fun isEditable(node: AccessibilityNodeModel): Boolean = node.editable ||
        node.className?.substringAfterLast('.')?.endsWith("EditText") == true

    fun publicPaneTitle(node: AccessibilityNodeModel): String? {
        val title = node.paneTitle?.trim() ?: return null
        if (node.password || node.sensitiveContent || node.clickable || isEditable(node) ||
            isSensitivePreview(node) || hasHiddenIdentifier(node)) return null
        return title.takeIf { it.length in 1..140 && !it.contains("숨김]") && redactIdentifiers(it) == it }
            ?.replace(paneWhitespace, " ")
    }

    /** Role comes only from a public hint/resource. Never infer a hint from text or contentDescription. */
    fun publicInputLabel(node: AccessibilityNodeModel): String {
        // Stable public roles take precedence over transient Samsung hints such as '채팅+'.
        val resource = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        if (Regex("^(?:message_edit_text|message_body|message_input|sms_body|compose_message|body_input)$").matches(resource))
            return "메시지 내용 입력란"
        if (Regex("^(?:recipient_input|recipient_edit_text|recipient_number|recipients_editor)$").matches(resource))
            return "받는 사람 입력란"
        node.inputHint?.trim()?.takeIf { it.isNotEmpty() }?.let {
            // SearchView commonly exposes its icon and editor as the same '검색'.
            // Preserve the public input role so they remain distinguishable without vision.
            if (it.lowercase(java.util.Locale.ROOT) in setOf("검색", "search")) return "검색 입력란"
            return redactIdentifiers(it.replace(Regex("\\s+")," "))
        }
        return when {
            Regex("url_bar|address_bar|omnibox").containsMatchIn(resource) -> "주소 입력란"
            Regex("search|query").containsMatchIn(resource) -> "검색 입력란"
            resource == "digits" || resource == "phone_number" -> "전화번호 입력란"
            else -> "입력란"
        }
    }

    fun isSensitivePreview(node: AccessibilityNodeModel) = !isEditable(node) &&
        !RecipientResultBinding.isSafeMatch(node) &&
        isSensitivePreviewResource(node.viewIdResourceName,node.text)

    /** 일반 '메시지 보내기' 버튼은 유지하고 실제 메시지 본문·목록 미리보기 이름만 제외한다. */
    fun isSensitivePreviewResource(resourceName: String?, publicText: String? = null): Boolean {
        // Framework AlertDialog also calls its notice 'message'. Only these exact public
        // opt-out labels are exempt; arbitrary dialog messages and conversation previews stay hidden.
        if (resourceName == "android:id/message" && publicText != null && publicText.length <= 80 &&
            publicText.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() } in PUBLIC_POPUP_NOTICES) return false
        val name = resourceName?.substringAfterLast('/')?.lowercase(java.util.Locale.ROOT) ?: return false
        // Observed on a real Samsung device. The whole row can aggregate preview/contact
        // text in contentDescription, so masking only the leaf text is insufficient.
        if (resourceName?.startsWith("com.samsung.android.messaging:id/") == true && name in SAMSUNG_PRIVATE_CONTENT) return true
        return name in PREVIEW_IDS || PREVIEW_PATTERN.containsMatchIn(name)
    }

    private val SAMSUNG_PRIVATE_CONTENT = setOf("card_view_list_item", "text_content", "list_avatar_name",
        "suggest_app_bar_sub_title", "list_item_content_container", "composer_title_layout", "composer_title",
        "container_contact_list_item_content", "contact_list_item_main", "contact_list_item_sub")

    private val PUBLIC_POPUP_NOTICES = setOf("오늘하루보지않기", "오늘보지않기", "오늘은그만보기", "오늘하루그만보기",
        "다시보지않기", "다시보지않음", "하루동안표시하지않음", "오늘하루표시하지않음",
        "dontshowagain", "donotshowagain", "notshowtoday")

    private val PREVIEW_IDS = setOf("message", "snippet", "preview", "subject", "sender_name",
        "conversation_item", "chat_item", "thread_item", "notification_entry", "lastmessage")
    private val PREVIEW_PATTERN = Regex(
        "(?:^|_)(?:message|chat|notification)_(?:text|body|content|preview|snippet)(?:_|$)|" +
            "(?:^|_)(?:last_message|conversation_snippet|preview_text)(?:_|$)",
    )
}

/** 전체 트리와 화면에서 선택할 후보를 구분하며, 수집이 제한되었는지도 함께 알린다. */
data class ParsedTree(
    val allNodes: List<AccessibilityNodeModel>,
    val nodes: List<AccessibilityNodeModel>,
    val truncated: Boolean,
    val readGaps: TreeReadGaps = TreeReadGaps(),
    val platformActionBarBounds: NodeBounds? = null,
)

/** Aggregate diagnostics. No node labels, paths, exception messages, or input values. */
data class TreeReadGaps(
    val propertyFailures: Int = 0,
    val childQueryFailures: Int = 0,
    val missingChildren: Int = 0,
    val nodeBudgetReached: Boolean = false,
    val depthLimitReached: Boolean = false,
) {
    val incomplete: Boolean
        get() = propertyFailures > 0 || childQueryFailures > 0 || missingChildren > 0 ||
            nodeBudgetReached || depthLimitReached
}
