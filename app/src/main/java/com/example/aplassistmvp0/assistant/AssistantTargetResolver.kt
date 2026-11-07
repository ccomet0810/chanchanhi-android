package com.example.aplassistmvp0.assistant

import com.example.aplassistmvp0.guide.AppRoutePolicy
import com.example.aplassistmvp0.guide.GuidePolicy
import com.example.aplassistmvp0.guide.TargetApp
import java.text.Normalizer
import java.util.Locale

/** 호출 시점의 앱 선택 결과다. 대상이 없으면 추측 실행 대신 앱 선택 또는 잠금 해제를 안내한다. */
data class AssistantTargetResolution(
    val target: TargetApp?,
    val source: String,
    val message: String,
) {
    val useCurrentApp: Boolean get() = source == AssistantTargetResolver.CURRENT_APP
    val shouldChooseApp: Boolean get() = source == AssistantTargetResolver.CHOOSE_APP
}

/**
 * 길게 누르기 직전 실제 앱 정보를 사용하는 순수 선택 정책이다. Android 창·음성·Intent를 직접 호출하지 않는다.
 * 기존 AppRouteResolver의 결과를 주입받아 기본 문자 앱, 별칭 우선순위와 중복 앱 이름의 판단을 유지한다.
 */
object AssistantTargetResolver {
    const val EXPLICIT_APP = "explicit_app"
    const val DEVICE_SETTINGS = "device_settings"
    const val REQUESTED_APP = "requested_app"
    const val CURRENT_APP = "current_app"
    const val CHOOSE_APP = "choose_app"
    const val BLOCKED = "blocked"

    /**
     * foregroundPackage는 마지막 보관 스냅샷이 아니라 호출 직전 실제 전경 패키지여야 한다.
     * installedApps는 appRouter.installedApps(), routedApp은 appRouter.resolve(goal, installedApps)의 결과다.
     * 실행 직전 공개 진입점·현재 창·잠금 상태의 재검사는 호출자가 수행한다.
     */
    fun resolve(
        goal: String,
        installedApps: List<TargetApp>,
        foregroundPackage: String?,
        ownPackage: String,
        routedApp: TargetApp? = null,
        isLocked: Boolean = false,
        launcherPackages: Set<String> = emptySet(),
    ): AssistantTargetResolution {
        if (isLocked) return AssistantTargetResolution(null, BLOCKED,
            "잠금 해제 후 현재 앱에서 다시 요청해주세요.")
        if (goal.isBlank()) return choose("어떤 작업을 하고 싶은지 말한 뒤 앱을 확인해주세요.")

        // 호출자가 잘못된 목록이나 예전 라우팅 객체를 전달해도 사설/시스템 대상을 새로 만들어내지 않는다.
        val available = installedApps.filter { app ->
            GuidePolicy.isValidTargetPackage(app.packageName) && app.packageName != ownPackage &&
                app.packageName != AppRoutePolicy.SYSTEM_UI &&
                !AppRoutePolicy.isBlockedHomePackage(app.packageName, launcherPackages)
        }.distinctBy { it.packageName }.map { app ->
            app.copy(label = app.label.trim().ifBlank { app.packageName })
        }
        val routed = routedApp?.let { result -> available.singleOrNull { it.packageName == result.packageName } }
        val query = compact(goal)

        // '설정/메시지'는 기능 이름으로도 쓰인다. 명시 앱 선택과 일반 작업 선택을 구분한다.
        // 명시 앱이 여러 개이거나 알려진 별칭 앱이 없어서 기존 라우터가 null이면 현재 앱으로 덮어쓰지 않는다.
        val namedApp = installedApps.any { app ->
            val name = compact(app.label)
            name.isNotEmpty() && name !in GENERIC_NAMES && nameOccurs(goal, app.label)
        } || STRONG_ALIASES.any { nameOccurs(goal, it) }
        val genericAppReference = GENERIC_APP_REFERENCES.any(query::contains)
        if (namedApp || genericAppReference) {
            if (routed == null) return choose("요청에서 사용할 앱을 한 개로 확인하지 못했습니다. 앱을 직접 골라주세요.")
            // 문자 앱을 명시했는데 설정 의미만 매칭된 경우도 선택 확인 없이 다른 앱으로 보내지 않는다.
            if (GENERIC_MESSAGE_APP_REFERENCES.any(query::contains) &&
                routed.packageName == GuidePolicy.SETTINGS_PACKAGE && !query.contains("설정앱")) {
                return choose("문자 앱과 설정 중 사용할 앱을 직접 골라주세요.")
            }
            return AssistantTargetResolution(routed, EXPLICIT_APP, "${routed.label} 앱에서 안내합니다.")
        }

        // 이름 없는/설치되지 않은 '다른 앱을 열기' 요청도 현재 앱으로 조용히 대체하지 않는다.
        // '이 앱을 열어'는 호출한 앱 자체를 가리키므로 이 경우만 일반 현재 화면 요청으로 남긴다.
        val externalAppRequest = query.replace("이앱", "").replace("현재앱", "").replace("지금앱", "")
        if (APP_OPEN_PATTERN.containsMatchIn(externalAppRequest))
            return choose("열려는 앱을 확인하지 못했습니다. 설치된 앱을 직접 골라주세요.")

        if (isDeviceSettingGoal(goal)) {
            if (routed?.packageName != GuidePolicy.SETTINGS_PACKAGE)
                return choose("기기 설정 앱을 확인하지 못했습니다. 앱을 직접 골라주세요.")
            return AssistantTargetResolution(routed, DEVICE_SETTINGS, "설정 앱에서 기기 설정을 안내합니다.")
        }

        // 일반 '메시지 보내기'는 기본 문자 앱 요청이다. '여기서'와 판매자/주문 문의는 현재 앱의 기능이다.
        // 기본 문자 앱은 기존 라우터가 선택한 설치 앱만 사용하며 패키지를 새로 추측하지 않는다.
        val currentScreenRequest = CURRENT_SCREEN_REFERENCES.any(query::contains)
        val contentMessageRequest = MESSAGE_CONTENT_REFERENCES.any(query::contains)
        if (isMessageGoal(goal) && !currentScreenRequest && !contentMessageRequest) {
            if (routed == null || routed.packageName == GuidePolicy.SETTINGS_PACKAGE)
                return choose("기본 문자 앱을 한 개로 확인하지 못했습니다. 사용할 앱을 직접 골라주세요.")
            return AssistantTargetResolution(routed, REQUESTED_APP, "${routed.label} 앱에서 메시지 사용을 안내합니다.")
        }

        val current = available.singleOrNull { it.packageName == foregroundPackage }
            ?: return choose("현재 앱을 안내 대상으로 확인하지 못했습니다. 사용할 앱을 직접 골라주세요.")
        return AssistantTargetResolution(current, CURRENT_APP, "현재 ${current.label} 화면에서 안내합니다.")
    }

    /** 기존 문자 작업 분류만 따른다. '문자 크기/문자열'과 영단어 내부의 sms는 문자 앱 요청이 아니다. */
    private fun isMessageGoal(goal: String): Boolean {
        val query = compact(goal)
        if (query in setOf("문자", "메시지", "sms", "messages")) return true
        val topic = (query.contains("문자") && !query.contains("문자크기") && !query.contains("문자열")) ||
            query.contains("메시지") || nameOccurs(goal, "sms") || nameOccurs(goal, "messages")
        return topic && MESSAGE_ACTION_WORDS.any(query::contains)
    }

    /** 화면 밝기 등 기기 작업과 앱 안의 계정/사진 편집 설정을 구분한다. 라우터 결과 없이 앱을 생성하지 않는다. */
    private fun isDeviceSettingGoal(goal: String): Boolean {
        val query = compact(goal)
        val here = CURRENT_SCREEN_REFERENCES.any(query::contains)
        if (query in setOf("설정", "settings", "소리")) return true
        if (query.startsWith("설정에서") || query.startsWith("설정으로") || query.startsWith("settings에서")) return true
        // '계정 설정 열기'는 앱의 기능 요청이다. 설정을 직접 여는 문구만 기기 앱 요청으로 본다.
        val directSettings = query.startsWith("설정") || query.startsWith("settings") ||
            query.startsWith("opensettings") || query.startsWith("launchsettings")
        if (!here && directSettings && OPEN_WORDS.any(query::contains)) return true

        // '이 화면에서 사진 밝기 조절'은 앱 콘텐츠 작업이다. '폰 화면 밝기'를 함께 명시하면 기기 작업이다.
        val contentEditing = CONTENT_REFERENCES.any(query::contains)
        val deviceReference = DEVICE_REFERENCES.any(query::contains)
        val fontSize = Regex("font\\s*size|text\\s*size", RegexOption.IGNORE_CASE).containsMatchIn(goal) ||
            (listOf("글씨", "글자", "글꼴").any(query::contains) &&
                listOf("크기", "크게", "키우", "작게", "줄여").any(query::contains))
        if (fontSize && !here && (!contentEditing || deviceReference) &&
            !Regex("말고|아니|않|don't|do not", RegexOption.IGNORE_CASE).containsMatchIn(goal)) return true
        val brightness = query.contains("밝기") || (query.contains("화면") && query.contains("밝게")) ||
            nameOccurs(goal, "brightness")
        if (brightness && (!contentEditing || deviceReference)) return true
        if (query.contains("벨소리") || query.contains("볼륨") || nameOccurs(goal, "ringtone") ||
            nameOccurs(goal, "volume")) return true
        return !here && query.contains("소리") && !query.contains("헛소리") && !query.contains("잔소리") &&
            SOUND_CHANGE_WORDS.any(query::contains)
    }

    /** 선택 실패를 모두 같은 명시적 결과로 돌려, 호출자가 현재 앱을 임의로 재선택하지 않도록 한다. */
    private fun choose(message: String) = AssistantTargetResolution(null, CHOOSE_APP, message)

    /** 기존 라우터와 같은 전각·대소문자·띄어쓰기 정규화를 사용한다. */
    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * 이름이 언급됐는지만 확인한다. 최종 앱과 모호성은 기존 라우터가 결정한다.
     * 영어 LINE이 online에 포함되는 경우를 피하며, 'open Chrome'의 원래 단어 경계를 보존한다.
     */
    private fun nameOccurs(goal: String, label: String): Boolean {
        val query = compact(goal)
        val name = compact(label)
        if (name.isEmpty()) return false
        val ascii = name.all { it in 'a'..'z' || it in '0'..'9' }
        if (!ascii) return query.contains(name)
        val breaks = mutableSetOf<Int>()
        var offset = 0
        Normalizer.normalize(goal, Normalizer.Form.NFKC).lowercase(Locale.ROOT).forEach { char ->
            if (char.isLetterOrDigit()) offset++ else breaks += offset
        }
        fun isAsciiWord(char: Char?) = char != null && (char in 'a'..'z' || char in '0'..'9')
        var start = query.indexOf(name)
        while (start >= 0) {
            val end = start + name.length
            if ((!isAsciiWord(query.getOrNull(start - 1)) || start in breaks) &&
                (!isAsciiWord(query.getOrNull(end)) || end in breaks)) return true
            start = query.indexOf(name, start + 1)
        }
        return false
    }

    private val GENERIC_NAMES = setOf("설정", "settings", "메시지", "messages", "문자", "sms")
    // 별칭은 기존 라우터의 명시적 브랜드 분류만 따른다. 실제 설치/최종 앱 판단은 routedApp에 남긴다.
    private val STRONG_ALIASES = listOf("배민", "배달의민족", "baemin", "네이버", "naver", "navershopping", "카카오톡", "카톡",
        "kakaotalk", "kakao", "카카오메시지", "카카오로메시지")
    private val GENERIC_MESSAGE_APP_REFERENCES = listOf("메시지앱", "문자앱", "sms앱", "smsapp", "messagesapp")
    private val GENERIC_APP_REFERENCES = GENERIC_MESSAGE_APP_REFERENCES + listOf("설정앱", "settings앱", "settingsapp")
    private val CURRENT_SCREEN_REFERENCES = listOf("여기", "이화면", "현재화면", "지금화면", "이앱", "현재앱", "지금앱")
    // 일반 '문의' 전체를 예외로 두지 않는다. 앱 콘텐츠의 상대/대상을 명시한 좁은 문맥만 현재 앱에 남긴다.
    private val MESSAGE_CONTENT_REFERENCES = listOf("판매자", "상품", "주문", "고객센터", "배송문의", "배송조회",
        "seller", "product", "order")
    private val MESSAGE_ACTION_WORDS = listOf("보내", "전송", "작성", "읽", "확인", "열", "켜", "send", "open")
    private val CONTENT_REFERENCES = listOf("사진", "이미지", "동영상", "영상", "상품", "photo", "image", "video")
    private val DEVICE_REFERENCES = listOf("기기", "휴대폰", "스마트폰", "폰화면", "디스플레이",
        "화면밝기", "화면을밝", "screenbrightness", "displaybrightness")
    private val OPEN_WORDS = listOf("열", "켜", "실행", "open", "launch")
    private val SOUND_CHANGE_WORDS = listOf("바꾸", "줄", "키우", "높", "낮", "크게", "작게", "켜", "끄")
    private val APP_OPEN_PATTERN = Regex("앱(?:을|를)?(?:열|켜|실행)")
}
