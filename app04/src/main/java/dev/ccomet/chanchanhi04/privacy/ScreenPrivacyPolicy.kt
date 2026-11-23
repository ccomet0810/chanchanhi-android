package dev.ccomet.chanchanhi04.privacy

import dev.ccomet.chanchanhi04.agent.Element

/** 화면 구조로 판단하는 레이어 1 규칙입니다(문구 내용이 아닌 앱·역할·리소스 기준). */
object ScreenPrivacyPolicy {
    private val financeOrAuthKeywords = listOf("bank", "banking", "pay", "card", "securities", "stock", "invest", "insurance",
        "wallet", "otp", "auth", "certif", "toss", "kftc", "health", "medical", "hospital", "pharm", "nhis", "hometax", "minwon", "gov24")
    private val financeOrAuthPackages = setOf(
        "viva.republica.toss", "com.kakaobank.channel", "com.kbstar.kbbank", "com.kbstar.liivbank", "com.shinhan.sbanking",
        "com.wooribank.smart.npib", "com.hanabank.ebk.channel.android.hananbank", "nh.smart.banking", "com.nh.mobilenoq",
        "com.ibk.android.ionebank", "com.epost.psf.sdsi", "kr.co.citibank.citimobile", "com.scbank.ma30", "com.kbankwith.smartbank",
        "com.sktelecom.tauth", "com.kt.ktauth", "com.lguplus.smartotp", "com.samsung.android.spay", "com.nhn.android.naverpay",
        "kr.or.nhis.wbm", "kr.go.nts.android", "kr.go.minwon.m", "com.google.android.apps.authenticator2",
    )
    private val messagingKeywords = listOf("messag", "mms", "sms", "talk", "chat", "kakao", "line", "telegram", "whatsapp", "signal", "band", "messenger")
    private val contactKeywords = listOf("contacts", "dialer", "phone", "incallui", "telecom")
    private val nameResource = Regex("(?:^|_)(?:name|display_?name|contact_?name|sender|nickname|profile_?name|title|from|caller|recipient)(?:_|$)")
    private val bodyResource = Regex("(?:^|_)(?:message|msg|body|bubble|chat_?text|preview|snippet|content|text_?message|conversation)(?:_|$)")

    /** 금융·인증·건강·공공 서비스 앱: 이미지를 보내지 않고 금액과 숫자를 더 강하게 가린다. */
    fun isSensitiveApp(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        return pkg in financeOrAuthPackages || financeOrAuthKeywords.any { k -> pkg.split('.').any { part -> part.contains(k) } }
    }

    fun isMessagingApp(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        return messagingKeywords.any { k -> pkg.split('.').any { it.contains(k) } }
    }

    fun isContactApp(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        return contactKeywords.any { k -> pkg.split('.').any { it.contains(k) } }
    }

    private fun resourceName(element: Element) = element.resource?.substringAfterLast('/')?.lowercase()

    /** 대화 앱의 말풍선·미리보기 본문. 안내에는 위치와 역할만 필요하므로 본문은 길이만 남긴다. */
    fun isMessageContent(packageName: String, element: Element): Boolean {
        if (element.editable || element.clickable && element.label.length < 25) return false
        if (!isMessagingApp(packageName)) return false
        val resource = resourceName(element)
        if (resource != null && bodyResource.containsMatchIn(resource)) return true
        // 리소스 ID가 없는 대화 앱의 긴 글은 본문으로 본다(버튼·탭 이름은 대개 짧다).
        return !element.clickable && element.label.length >= 25
    }

    /** 연락처·대화 상대·발신자 칸. 이 칸의 사람 이름은 통째로 토큰으로 바꾼다. */
    fun isPersonField(packageName: String, element: Element): Boolean {
        if (!isMessagingApp(packageName) && !isContactApp(packageName)) return false
        val resource = resourceName(element) ?: return false
        return nameResource.containsMatchIn(resource)
    }

    fun messagePlaceholder(length: Int) = "메시지 본문 숨김(${length}자)"
}
