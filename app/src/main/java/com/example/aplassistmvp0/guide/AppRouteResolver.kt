package com.example.aplassistmvp0.guide

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import java.text.Normalizer
import java.util.Locale

/** 폰에서 실제 발견한 앱 이름과 패키지입니다. 설치 목록은 로컬에서만 조회합니다. */
data class TargetApp(val packageName: String, val label: String, val revision: String = "")

/** 요청 문장을 설치된 앱과 연결하고, 실행 가능한 공개 시작 화면의 Intent만 만듭니다. */
class AppRouteResolver(context: Context) {
    private val context = context.applicationContext
    private val packageManager = this.context.packageManager

    /** LAUNCHER 앱을 패키지별로 정리하며 APL·시스템 UI·홈 앱은 안내 대상에서 제외합니다. */
    @Suppress("DEPRECATION")
    fun installedApps(): List<TargetApp> {
        val discovered = runCatching {
            packageManager.queryIntentActivities(launcherIntent(), 0).mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                if (!accessible(activity)) return@mapNotNull null
                // 한 앱의 표시 이름 조회 실패가 전체 설치 목록을 지우지 않도록 격리합니다.
                val label = runCatching { activity.applicationInfo.loadLabel(packageManager).toString().trim() }
                    .getOrDefault(activity.packageName)
                TargetApp(activity.packageName, label.ifBlank { activity.packageName }, revision(activity.packageName))
            }
        }.getOrDefault(emptyList())
        // 설정은 LAUNCHER 목록에 없어도 공식 Settings Intent로 열 수 있도록 항상 포함합니다.
        return AppRoutePolicy.catalog(discovered, context.packageName, homePackages()).map {
            if (it.revision.isBlank()) it.copy(revision = revision(it.packageName)) else it
        }
    }

    @Suppress("DEPRECATION")
    private fun revision(packageName: String): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$version:${info.lastUpdateTime}"
    }.getOrDefault("")

    /** 의미가 충돌하거나 앱이 없으면 추측하지 않고 null을 반환해 UI에서 직접 고르게 합니다. */
    fun resolve(goal: String, apps: List<TargetApp> = installedApps()): TargetApp? {
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return AppRoutePolicy.resolve(goal,
            AppRoutePolicy.catalog(apps, context.packageName, homePackages()), sms)
    }

    /** Discover the installed handler for a platform capability. The intent is queried, never executed. */
    @Suppress("DEPRECATION")
    fun capabilityTarget(goal:String, apps:List<TargetApp>):TargetApp? {
        val action=SystemCapabilityPolicy.action(goal) ?: return null
        val intent=Intent(action)
        val providers=runCatching { packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull{it.activityInfo?.takeIf{activity->activity.exported}?.packageName}.distinct()
            .mapNotNull{pkg->apps.singleOrNull{it.packageName==pkg}} }.getOrDefault(emptyList())
        val preferred=runCatching { packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return providers.singleOrNull{it.packageName==preferred} ?: providers.singleOrNull()
    }

    /**
     * 외부에서 받은 중첩 Intent를 전달하지 않습니다. 로컬에서 새 Intent를 생성하고 실행 대상만 고정합니다.
     * 문자 본문·수신 번호·전송 동작·URI 권한·추가 데이터는 넣지 않습니다.
     */
    @Suppress("DEPRECATION")
    fun launchIntent(app: TargetApp): Intent? = runCatching {
        if (app.packageName == context.packageName || app.packageName == AppRoutePolicy.SYSTEM_UI ||
            AppRoutePolicy.isBlockedHomePackage(app.packageName, homePackages())) return@runCatching null
        val base = if (app.packageName == AppRoutePolicy.SETTINGS) {
            Intent(Settings.ACTION_SETTINGS).setPackage(AppRoutePolicy.SETTINGS)
        } else launcherIntent().setPackage(app.packageName)
        val preferred = packageManager.resolveActivity(base, 0)?.activityInfo
        // 런처가 여러 개인 앱에서는 같은 패키지의 공개 LAUNCHER 중 하나를 사용합니다.
        val target = preferred?.takeIf { it.packageName == app.packageName && accessible(it) }
            ?: if (app.packageName != AppRoutePolicy.SETTINGS) {
                packageManager.queryIntentActivities(base, 0).mapNotNull { it.activityInfo }
                    .firstOrNull { it.packageName == app.packageName && accessible(it) }
            } else null
        target ?: return@runCatching null
        val intent = base.setComponent(ComponentName(target.packageName, target.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val verified = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@runCatching null
        if (verified.packageName != app.packageName || verified.name != target.name || !accessible(verified)) null
        else intent
    }.getOrNull()

    /**
     * 호출자는 항상 flags=0의 resolve/query 결과만 전달합니다. 실제 활성 상태는 PackageManager가 확인합니다.
     * ActivityInfo.enabled는 manifest 기본값이므로 동적으로 켠 아이콘 alias를 다시 제외하지 않습니다.
     * 오래 쓰지 않은 앱의 DISABLED_UNTIL_USED 처리도 OS의 실제 조회/실행 판단을 따릅니다.
     */
    private fun accessible(activity: ActivityInfo): Boolean =
        activity.exported &&
            (activity.permission.isNullOrEmpty() ||
                packageManager.checkPermission(activity.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)

    @Suppress("DEPRECATION")
    private fun homePackages(): Set<String> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}

/** Android 호출 없이 이름·별칭 우선순위와 모호성을 JVM에서 시험하는 순수 라우팅 정책입니다. */
internal object AppRoutePolicy {
    const val SETTINGS = "com.android.settings"
    const val SYSTEM_UI = "com.android.systemui"
    private const val NAVER = "com.nhn.android.search"
    private const val NAVER_SHOPPING = "com.navercorp.navershopping"
    private const val KAKAO_TALK = "com.kakao.talk"
    private const val BAEMIN = "com.sampleapp"
    private val GENERIC_LABELS = setOf("설정", "settings", "메시지", "messages", "문자", "sms")

    /** 설정의 FallbackHome도 HOME 조회에 잡힐 수 있습니다. 설정은 아래 공식 진입점 검증을 계속합니다. */
    fun isBlockedHomePackage(packageName: String, homePackages: Set<String>): Boolean =
        packageName != SETTINGS && packageName in homePackages

    /** 중복 Activity를 하나의 앱으로 묶고 설정의 표시 이름을 일관되게 유지합니다. */
    fun catalog(apps: List<TargetApp>, ownPackage: String, homePackages: Set<String>): List<TargetApp> {
        val excluded = homePackages + ownPackage + SYSTEM_UI
        return (listOf(TargetApp(SETTINGS, "설정")) + apps).filter {
            it.packageName.isNotBlank() && (it.packageName == SETTINGS || it.packageName !in excluded)
        }.distinctBy { it.packageName }.map { it.copy(label = it.label.trim().ifBlank { it.packageName }) }
            .sortedWith(compareBy<TargetApp> { if (it.packageName == SETTINGS) 0 else 1 }
                .thenBy { compact(it.label) }.thenBy { it.packageName })
    }

    /** 명시 앱과 강한 별칭을 먼저 비교하고, 일반 작업 별칭은 그 다음에만 사용합니다. */
    fun resolve(goal: String, apps: List<TargetApp>, defaultSmsPackage: String?): TargetApp? {
        val query = compact(goal)
        val wordBreaks = wordBreaks(goal)
        if (query.isEmpty()) return null
        val installed = apps.distinctBy { it.packageName }
        val byPackage = installed.associateBy { it.packageName }
        val names = installed.flatMap { app ->
            val label = compact(app.label)
            if (label.isEmpty() || label in GENERIC_LABELS) emptyList()
            else occurrences(query, label, wordBreaks).map { Mention(app, label, it) }
        }
        // 긴 이름 안에 겹친 짧은 이름만 제거합니다. '구글과 구글 지도'처럼 따로 말한 앱은 남깁니다.
        val explicit = names.filter { short -> names.none { long ->
            long.label.length > short.label.length && long.range.first <= short.range.first &&
                long.range.last >= short.range.last
        } }.map { it.app }.distinctBy { it.packageName }.toMutableList()
        val strong = mutableListOf<TargetApp>()
        var missingNamedApp = false
        fun nominate(packageName: String) {
            val app = byPackage[packageName]
            if (app == null) missingNamedApp = true else strong += app
        }

        val naverMention = occurrences(query, "네이버", wordBreaks).isNotEmpty() ||
            occurrences(query, "naver", wordBreaks).isNotEmpty() || occurrences(query, "navershopping", wordBreaks).isNotEmpty()
        // '네이버페이'처럼 더 구체적인 설치 앱 이름은 일반 네이버 별칭으로 덮어쓰지 않습니다.
        val specificNaver = explicit.any { it.packageName != NAVER &&
            (compact(it.label).contains("네이버") || compact(it.label).contains("naver")) }
        if (naverMention && !specificNaver) {
            if (query.contains("쇼핑") || query.contains("shopping")) {
                explicit.removeAll { it.packageName == NAVER }
                nominate(if (NAVER_SHOPPING in byPackage) NAVER_SHOPPING else NAVER)
            } else nominate(NAVER)
        }
        val directTalk = listOf("카카오톡", "카톡", "kakaotalk").any { occurrences(query, it, wordBreaks).isNotEmpty() }
        val kakaoMention = directTalk ||
            occurrences(query, "kakao", wordBreaks).isNotEmpty() || query.contains("카카오메시지") || query.contains("카카오로메시지")
        val specificKakao = explicit.any { it.packageName != KAKAO_TALK &&
            (compact(it.label).contains("카카오") || compact(it.label).contains("kakao")) }
        if (directTalk || (kakaoMention && !specificKakao)) nominate(KAKAO_TALK)

        // '배민커넥트' 같은 더 긴 설치 앱 이름 속의 별칭은 덮어쓰지 않습니다.
        val baeminMention = listOf("배민", "배달의민족", "baemin").any { alias ->
            occurrences(query, alias, wordBreaks).any { range -> names.none { name ->
                name.app.packageName != BAEMIN && name.label.length > alias.length &&
                    name.range.first <= range.first && name.range.last >= range.last
            } }
        }
        if (baeminMention) nominate(BAEMIN)

        val named = (explicit + strong).distinctBy { it.packageName }
        if (missingNamedApp || named.size > 1) return null
        if (named.size == 1) return named.single()

        val semantic = mutableListOf<TargetApp>()
        if (smsRequested(query, wordBreaks)) {
            val sms = defaultSmsPackage?.let(byPackage::get) ?: return null
            semantic += sms
        }
        if (settingsRequested(query, wordBreaks)) {
            byPackage[SETTINGS]?.let(semantic::add)
        }
        return semantic.distinctBy { it.packageName }.singleOrNull()
    }

    /** '문자 크기'·'문자열'은 문자 보내기와 구별하며, 단순 쇼핑 요청을 네이버로 추측하지 않습니다. */
    private fun smsRequested(query: String, wordBreaks: Set<Int>): Boolean {
        if (query in setOf("문자", "메시지", "sms", "messages")) return true
        val topic = (query.contains("문자") && !query.contains("문자크기") && !query.contains("문자열")) ||
            query.contains("메시지") || occurrences(query, "sms", wordBreaks).isNotEmpty() ||
            occurrences(query, "messages", wordBreaks).isNotEmpty()
        return topic && listOf("보내", "전송", "작성", "읽", "확인", "열", "켜", "send", "open").any(query::contains)
    }

    /** 소리 관련 일반 대화는 제외하고 설정 항목이나 변경 목표가 확인될 때만 설정 앱으로 연결합니다. */
    private fun settingsRequested(query: String, wordBreaks: Set<Int>): Boolean {
        if (query.contains("설정") || occurrences(query, "settings", wordBreaks).isNotEmpty() ||
            query.contains("밝기") || query.contains("벨소리") || query.contains("볼륨") ||
            query.contains("와이파이") || query.contains("블루투스") ||
            occurrences(query, "wifi", wordBreaks).isNotEmpty() || occurrences(query, "bluetooth", wordBreaks).isNotEmpty() ||
            (listOf("글씨", "글자", "글꼴", "문자").any(query::contains) &&
                listOf("크기", "크게", "키우", "작아", "작게", "줄여").any(query::contains)) ||
            (query.contains("화면") && query.contains("밝게"))) return true
        return query == "소리" || (query.contains("소리") && !query.contains("헛소리") && !query.contains("잔소리") &&
            listOf("바꾸", "줄", "키우", "높", "낮", "크게", "작게", "켜", "끄").any(query::contains))
    }

    /** 공백·대소문자·전각 표기를 정규화하되, 영어 앱 이름을 다른 영단어 내부에서 찾지 않습니다. */
    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** 공백을 없앤 위치에서도 원래 영단어 경계를 기억해 'open Chrome'을 한 단어로 오인하지 않습니다. */
    private fun wordBreaks(value: String): Set<Int> {
        val breaks = mutableSetOf<Int>()
        var compactOffset = 0
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).forEach { char ->
            if (char.isLetterOrDigit()) compactOffset++ else breaks += compactOffset
        }
        return breaks
    }

    private fun occurrences(query: String, label: String, wordBreaks: Set<Int>): List<IntRange> {
        val results = mutableListOf<IntRange>()
        var start = query.indexOf(label)
        while (start >= 0) {
            val end = start + label.length
            val asciiLabel = label.all { it in 'a'..'z' || it in '0'..'9' }
            val before = query.getOrNull(start - 1)
            val after = query.getOrNull(end)
            fun asciiWord(char: Char?) = char != null && (char in 'a'..'z' || char in '0'..'9')
            if (!asciiLabel || ((!asciiWord(before) || start in wordBreaks) &&
                    (!asciiWord(after) || end in wordBreaks))) results += start until end
            start = query.indexOf(label, start + 1)
        }
        return results
    }

    private data class Mention(val app: TargetApp, val label: String, val range: IntRange)
}
