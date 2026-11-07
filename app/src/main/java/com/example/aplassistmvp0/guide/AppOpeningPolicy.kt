package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot

/** The app being opened is different from the launcher currently being displayed. */
internal class AppOpeningRetryState {
    data class Request(val goal: String, val target: TargetApp, val binding: GuideGoalHandoff? = null)
    private var request: Request? = null
    fun begin(goal: String, target: TargetApp, binding: GuideGoalHandoff? = null) { request = Request(goal, target, binding) }
    fun hasBinding(binding: GuideGoalHandoff): Boolean = request?.binding === binding
    fun clear() { request = null }
    fun forRetry(ui: GuideUiState): Request? = request?.takeIf {
        ui.phase == "paused" && ui.goal == it.goal && ui.targetAppLabel == it.target.label &&
            (it.binding?.runCurrent(it.target) {} != false)
    }
    fun forOpening(ui: GuideUiState): Request? = request?.takeIf {
        ui.phase == "opening_app" && ui.goal == it.goal && ui.targetAppLabel == it.target.label &&
            (it.binding?.runCurrent(it.target) {} != false)
    }
}

/** Small launcher adapter. A matching settings search result is not an app icon. */
internal object AppOpeningPresentationPolicy {
    enum class Kind { UNAVAILABLE, GO_HOME, INCOMPLETE, APP_ICON, HOME_GESTURE, SEARCH_INPUT, SEARCH_BUTTON, UNRESOLVED }
    data class Step(val message: String, val node: AccessibilityNodeModel? = null, val kind: Kind = Kind.UNRESOLVED)
    private const val SAMSUNG_HOME = "com.sec.android.app.launcher"
    fun step(label: String, screen: ScreenSnapshot?, homePackage: String?): Step {
        if (screen == null) return Step("앱 목록에서 $label 앱을 열어주세요.", kind = Kind.UNAVAILABLE)
        if (screen.packageName != homePackage) return Step("홈 화면으로 이동해주세요.", kind = Kind.GO_HOME)
        if (screen.truncated || screen.viewport == null) return Step("앱 목록에서 $label 앱을 열어주세요.", kind = Kind.INCOMPLETE)
        val candidates = GuidePolicy.candidates(screen)
        val icons = candidates.filter { it.clickable && !AccessibilityNodePrivacy.isEditable(it) &&
            ExperiencePolicy.goalKey(GuidePolicy.label(it)) == ExperiencePolicy.goalKey(label) }
        val samsung = screen.packageName == SAMSUNG_HOME
        val knownIcon = if (samsung) icons.filter { it.viewIdResourceName == "$SAMSUNG_HOME:id/tray" &&
            it.className == "android.widget.LinearLayout" }.singleOrNull() else null
        val editingSearch = screen.allNodes.filter { AccessibilityNodePrivacy.isEditable(it) &&
            it.viewIdResourceName == "$SAMSUNG_HOME:id/search_src_text" }.singleOrNull()
        val icon = knownIcon ?: icons.singleOrNull().takeUnless { samsung && editingSearch != null }
        icon?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it ||
            (!screen.truncated && it === knownIcon && candidates.take(GuidePolicy.MAX_CANDIDATES).count { n -> n.id == it.id } == 1 &&
                screen.allNodes.count { n -> n.id == it.id } == 1) }?.let {
            return Step("$label 앱을 눌러주세요.", it, Kind.APP_ICON)
        }
        if (samsung && !screen.blockingPopup && (hasSamsungHomeSurface(screen) || screen.allNodes.any {
                it.className == "android.widget.LinearLayout" && !it.clickable &&
                    Regex("^홈\\s*화면\\s*\\d+\\s*페이지.*").matches(it.contentDescription.orEmpty()) }))
            return Step("홈 화면의 빈 곳을 위로 밀어주세요.", kind = Kind.HOME_GESTURE)
        if (samsung && editingSearch != null && GuidePolicy.resolveDecisionNode(screen, editingSearch.id) === editingSearch)
            return Step("검색창에 $label 앱 이름을 입력해주세요.", editingSearch, Kind.SEARCH_INPUT)
        val searchStub = if (samsung) screen.allNodes.singleOrNull { it.viewIdResourceName == "$SAMSUNG_HOME:id/app_search_edit_text" } else null
        if (samsung) candidates.singleOrNull { it.clickable && !AccessibilityNodePrivacy.isEditable(it) &&
            (it === searchStub || it.path == searchStub?.clickableAncestorPath) && GuidePolicy.label(it) == "검색" }
            ?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }?.let { return Step("검색을 눌러주세요.", it, Kind.SEARCH_BUTTON) }
        return Step("앱 목록에서 $label 앱을 열어주세요.")
    }

    /** Observed product tree; a separate QA observer's page description is absent here. */
    private fun hasSamsungHomeSurface(screen: ScreenSnapshot): Boolean {
        val visible = screen.allNodes.filter { it.visibleToUser && it.enabled && !it.password && !it.sensitiveContent }
        // A transitioning/overlaid app drawer must not inherit an underlying home hint.
        if (visible.any { it.viewIdResourceName == "$SAMSUNG_HOME:id/apps_screen" ||
                it.viewIdResourceName in setOf("$SAMSUNG_HOME:id/search_src_text", "$SAMSUNG_HOME:id/app_search_edit_text") }) return false
        fun role(resource: String, type: String) = visible.singleOrNull {
            !it.clickable && !AccessibilityNodePrivacy.isEditable(it) &&
                it.viewIdResourceName == "$SAMSUNG_HOME:id/$resource" && it.className == type
        }
        val home = role("home_screen", "android.widget.FrameLayout") ?: return false
        val workspace = role("workspace_fast_recycler_view", "android.view.ViewGroup") ?: return false
        val dock = role("hotseat_container", "android.widget.RelativeLayout") ?: return false
        return workspace.path.startsWith("${home.path}/") && dock.path.startsWith("${home.path}/")
    }
}
