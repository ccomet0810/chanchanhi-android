package com.example.aplassistmvp0.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.view.Display
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Build
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowManager
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import com.example.aplassistmvp0.overlay.HighlightOverlayManager
import com.example.aplassistmvp0.overlay.GuideTextOverlayManager
import com.example.aplassistmvp0.overlay.FrozenScreenOverlayManager
import com.example.aplassistmvp0.guide.ScrollDirection
import com.example.aplassistmvp0.guide.ScrollProbePort
import com.example.aplassistmvp0.guide.ScrollProbePolicy
import com.example.aplassistmvp0.guide.ScrollProbeResult
import com.example.aplassistmvp0.guide.ScrollProbeRunner
import com.example.aplassistmvp0.guide.GuideController
import com.example.aplassistmvp0.guide.GuidePolicy
import com.example.aplassistmvp0.guide.GuideScopedTap
import com.example.aplassistmvp0.guide.GuideStateRepository
import com.example.aplassistmvp0.guide.GuideTrace
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.state.ScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors

/**
 * 다른 앱이 공개한 접근성 의미 정보만 수집하고 선택된 항목의 안내를 관리한다.
 * Decisions AI는 화면을 읽고 강조·음성으로 안내한다. 화면 밖 탐색에만 제한된 스크롤을 한다.
 */
class AssistAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    // 원격 앱과 통신하는 트리 조회는 UI 스레드 밖의 단일 작업 큐에서 처리한다.
    private val readerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val readerScope = CoroutineScope(SupervisorJob() + readerDispatcher)
    private val parser = AccessibilityTreeParser()
    private lateinit var overlay: HighlightOverlayManager
    private lateinit var textOverlay: GuideTextOverlayManager
    private lateinit var backdrop: com.example.aplassistmvp0.overlay.Apl2BackdropOverlayManager
    private var releasedBackdropKey: String? = null
    private lateinit var frozenOverlay: FrozenScreenOverlayManager
    @Volatile private var probing = false
    @Volatile private var visionCapturing = false
    @Volatile private var probeEpoch = 0L
    private var probeJob: Job? = null
    private var ignoreProbeScrollUntil = 0L
    private val probeDeadline = Runnable {
        cancelScrollProbe()
        GuideController.onScreenUnavailable("화면 확인이 4초를 넘겨 중단했어요. 현재 화면을 확인한 뒤 다시 요청해주세요.")
    }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var textOverlayJob: Job? = null
    private var connected = false
    private var captureRunning = false
    private var captureAgain = false
    private var captureScheduled = false
    private var generation = 0L
    private var interactionGeneration = 0L
    // 이전 연결 또는 기록 삭제 전의 늦은 결과가 현재 상태를 덮어쓰지 않도록 구분한다.
    private var connectionEpoch = 0L
    private var snapshotSequence = 0L
    private var selectedTarget: Selection? = null
    private var lastEventName = "서비스 연결"
    private var receiverRegistered = false
    private var launcherPackage: String? = null
    private var focusedWindowKey: Pair<String?, Int>? = null
    private var focusedWindowChangedAt = 0L
    private val captureTask = Runnable {
        captureScheduled = false
        captureCurrentScreen()
    }

    /** 선택 당시의 객체는 좌표 사용용이 아니라 재식별을 위한 의미 정보로만 보관한다. */
    private data class Selection(
        val packageName: String,
        val node: AccessibilityNodeModel,
        val anchor: String?,
        var hasBeenShown: Boolean = false,
        var candidateBounds: NodeBounds? = null,
        var candidateWindowId: Int = -1,
        var candidateCapturedAt: Long = 0L,
        var candidateState: List<Any?>? = null,
        var stableObservations: Int = 0,
        var unresolvedSince: Long = 0L,
        val contentChangedAtByWindow: MutableMap<Int, Long> = mutableMapOf(),
        val visualScreen: ScreenSnapshot? = null,
        val scopedTap: GuideScopedTap? = null,
    )
    private data class Capture(val packageName: String?, val windowId: Int, val tree: ParsedTree?, val blockingPopup: Boolean = false,
        val inputObservationVersion:Long=0)

    // 화면이 꺼지면 강조와 예약을 제거한다. 잠금 화면 위에 안내가 남지 않는다.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                recordLifecycle("screen_off")
                interactionGeneration++
                GuideController.onScreenUnavailable("화면이 꺼져 안내를 멈췄습니다. 잠금 해제 후 다시 시작해주세요.")
                cancelScrollProbe()
                removeHighlight("화면이 꺼져 강조를 제거했습니다.")
                if (::textOverlay.isInitialized) textOverlay.hide()
                if (::backdrop.isInitialized) backdrop.dispose()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectionEpoch = ++nextConnectionEpoch
        focusedWindowKey = null
        focusedWindowChangedAt = SystemClock.uptimeMillis()
        captureRunning = false
        captureAgain = false
        captureScheduled = false
        handler.removeCallbacks(captureTask)
        if (::overlay.isInitialized) overlay.hide()
        overlay = HighlightOverlayManager(this)
        textOverlayJob?.cancel()
        if (::textOverlay.isInitialized) textOverlay.hide()
        if (::backdrop.isInitialized) backdrop.dispose()
        backdrop = com.example.aplassistmvp0.overlay.Apl2BackdropOverlayManager(this)
        releasedBackdropKey = null
        textOverlay = GuideTextOverlayManager(this, panelBoundsChanged = { bounds ->
            overlay.setPanelBounds(bounds)
            backdrop.setPanelBounds(bounds)
        }) {
            releasedBackdropKey = backdropKey(GuideStateRepository.state.value)
            backdrop.hide()
        }
        frozenOverlay = FrozenScreenOverlayManager(this)
        connected = true
        instance = this
        GuideController.initialize(this)
        textOverlayJob = uiScope.launch {
            combine(GuideStateRepository.state, AssistStateRepository.state, com.example.aplassistmvp0.guide.GuideCaption.state) { guide, screen, caption -> Triple(guide, screen, caption) }
                .collect { (guide, screen, caption) ->
                    val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        getSystemService(android.os.PowerManager::class.java).isInteractive
                    if (connected && unlocked && !probing && !visionCapturing && guide.phase != "probing" && guide.goal.isNotBlank() && guide.phase !in setOf("idle", "done") &&
                        screen.foregroundPackage == guide.targetPackageName && screen.foregroundPackage != packageName) {
                        // 강조 대상의 반대편에 글을 배치해 다음에 누를 항목을 덜 가린다.
                        // Position the touchable feedback row before the first highlight appears.
                        // Waiting for highlightedNodeId left it over bottom app buttons for a frame.
                        val bounds = screen.snapshot?.allNodes?.firstOrNull { it.id == screen.highlightedNodeId }?.bounds
                            ?: selectedTarget?.takeIf { it.packageName==guide.targetPackageName }?.let { it.candidateBounds ?: it.node.bounds }
                        val atTop = bounds?.let { (it.top + it.bottom) / 2 > resources.displayMetrics.heightPixels / 2 }
                            ?: (guide.phase == "opening_app")
                        // APL2 keeps an explicit exit while thinking, waiting, paused or failed too.
                        // The enclosing guard still excludes locked screens, other apps, probing and vision capture.
                        val protected = screen.snapshot?.let { current ->
                            com.example.aplassistmvp0.guide.GuidePolicy.contextNodes(current).filter { it.enabled &&
                                (it.id == screen.highlightedNodeId || it.focused == true ||
                                    com.example.aplassistmvp0.guide.GuidePolicy.label(it).trim() in setOf("다음", "다음으로", "확인", "검색", "계속") ||
                                    (guide.currentAction in setOf("manual", "wait") && it.clickable && com.example.aplassistmvp0.guide.GuideOverlayPolicy.quotedChoice(
                                        guide.message, com.example.aplassistmvp0.guide.GuidePolicy.label(it).trim()))) }.map { it.bounds }
                        }.orEmpty().let { (listOfNotNull(bounds)+it+
                            com.example.aplassistmvp0.guide.GuideOverlayEvidence.regions(guide,screen.snapshot)).distinct() }
                        // Background is a separate passive window, created before the interactive panel.
                        // Only fresh, actually highlighted targets get a clear cutout.
                        if (guide.currentAction != "wait" && releasedBackdropKey != backdropKey(guide) && !guide.awaitingRecipientSelection &&
                            !(textOverlay.isCollapsed && (guide.busy || guide.phase != "guiding"))) backdrop.show(
                            bounds.takeIf { !guide.busy && overlay.isShowing && screen.highlightedNodeId != null })
                        else backdrop.hide()
                        textOverlay.show(guide, atTop, protected = protected, primary = bounds)
                        if (textOverlay.isCollapsed) backdrop.hide()
                    } else { textOverlay.hide(); backdrop.dispose() }
                }
        }
        launcherPackage = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        AssistStateRepository.update {
            it.copy(serviceConnected = true, overlayStatus = "서비스 연결됨. 안내할 앱을 열어주세요.", error = null)
        }
        Log.i(TAG, "Service connected: Decisions AI 읽기 안내")
        scheduleCapture()
    }

    private fun backdropKey(guide: com.example.aplassistmvp0.guide.GuideUiState) =
        listOf(guide.goal, guide.step.toString(), guide.phase, guide.message).joinToString("|")

    /** 이벤트 자체의 packageName은 배경 앱에서도 올 수 있으므로 실제 활성 창을 다시 확인한다. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        val eventPackage = event.packageName?.toString()
        // 디버그 목록 갱신이 다시 자기 앱의 목록 갱신을 일으키는 순환을 방지한다.
        if (eventPackage == packageName && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        lastEventName = AccessibilityEvent.eventTypeToString(event.eventType)
        if(LocalInputRevisions.active && event.eventType==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && eventPackage!=null){
            val input=actionEvidence(event)
            LocalInputRevisions.changed(eventPackage,event.windowId,input.role,input.viewId,input.bounds)
        }
        if (!GuideStateRepository.state.value.busy && eventPackage == GuideStateRepository.state.value.targetPackageName && event.eventType in setOf(
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            releasedBackdropKey = backdropKey(GuideStateRepository.state.value)
            backdrop.hide()
        }
        if (eventPackage == GuideStateRepository.state.value.targetPackageName && event.eventType in setOf(
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED)) {
            com.example.aplassistmvp0.guide.GuideTrace.record("control_event", mapOf("eventType" to event.eventType.toString(), "kind" to lastEventName))
        }
        Log.d(TAG, "Event: $lastEventName package=$eventPackage window=${event.windowId}")
        val regionEvidence = if(event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && eventPackage==selectedTarget?.packageName && selectedTarget!=null && !overlay.isShowing)
            actionEvidence(event) else null
        val unrelatedRegion = !probing && regionEvidence?.let(GuideController::shouldIgnoreScroll)==true
        val unrelatedScroll = event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED && unrelatedRegion
        if(event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,AccessibilityEvent.TYPE_VIEW_FOCUSED) ||
            event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED && !unrelatedScroll) interactionGeneration++
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && regionEvidence!=null)
            com.example.aplassistmvp0.guide.GuideTrace.record("control_event",mapOf("kind" to "CONTENT_REGION","role" to regionEvidence.role,
                "bounds" to regionEvidence.bounds.toString(),"unrelated" to unrelatedRegion.toString()))

        // 대상 앱 복귀 때 좌표가 같아도 목록 내용 갱신과 늦은 스크롤 이벤트가 이어질 수 있다.
        // 실제 대상 창의 갱신 시각을 기록하며, 안내 창 자체의 WINDOWS 이벤트는 포함하지 않는다.
        if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            eventPackage == selectedTarget?.packageName && !unrelatedRegion) {
            selectedTarget?.contentChangedAtByWindow?.let { changes ->
                changes[event.windowId] = SystemClock.uptimeMillis()
                // 장시간 여러 창을 오가더라도 예약 하나가 보관하는 기록은 제한한다.
                if (changes.size > 8) changes.minByOrNull { it.value }?.key?.let(changes::remove)
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 같은 창의 제목/패널 변경도 STATE 이벤트를 만든다. 실제 포커스 창이 바뀐 경우만 숨긴다.
            // 늦게 도착한 이벤트의 packageName으로 현재 창을 추측하지 않는다.
            val focused = focusedContentWindow()
            val focusedPackage = focused?.let(::metadataRoot)?.packageName?.toString()
            // 네트워크 판단 중에도 타 앱으로 나가면 늦은 응답을 즉시 무효화한다.
            GuideController.onForegroundChanged(focusedPackage)
            // 같은 창의 루트가 잠깐 null이면 이를 다른 앱으로 오인하지 않는다.
            if (focusedPackage != null || focused?.id != focusedWindowKey?.second) {
                observeFocusedWindow(focusedPackage, focused?.id ?: -1)
            }
            if (selectedTarget != null && (focused?.id != selectedTarget?.candidateWindowId ||
                (focusedPackage != null && focusedPackage != selectedTarget?.packageName))) {
                hideForOtherWindow(focusedPackage)
            }
        }
        // CONTENT/STATE가 실제 클릭보다 먼저 올 수 있으므로 Overlay 존재 여부와 독립해 전달한다.
        // 스크롤도 사용자가 실행한 동작이며, 연속 fling은 Controller의 안정 대기로 합친다.
        if ((event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            eventPackage != null) {
            // 안내 세션의 대상 앱 여부는 Controller가 검증한다. 수동 디버그 강조와 독립된 경로다.
            if (probing && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                cancelScrollProbe()
                GuideController.onScreenUnavailable("확인 중 화면 조작이 감지되어 중단했어요. 현재 화면에서 다시 요청해주세요.")
            } else if (!probing && (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    SystemClock.uptimeMillis() >= ignoreProbeScrollUntil)) {
                val evidence = regionEvidence ?: actionEvidence(event)
                val delta = evidence.scrollDeltaY ?: 0
                val direction = when { delta > 0 -> com.example.aplassistmvp0.guide.ScrollDirection.DOWN
                    delta < 0 -> com.example.aplassistmvp0.guide.ScrollDirection.UP
                    else -> null }
                GuideController.onUserAction(eventPackage, scrolled = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    direction = direction, evidence = evidence)
            }
        }
        // 사용자가 아래 실제 버튼을 누른 경우에는 안내를 완료하고 다음 화면을 수집한다.
        // 화면 갱신 이벤트가 먼저 테두리를 숨겨도 뒤따르는 실제 클릭으로 예약을 완료한다.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && selectedTarget?.hasBeenShown == true &&
            eventPackage == selectedTarget?.packageName) {
            removeHighlight("사용자 클릭 감지. 강조를 제거하고 화면을 다시 읽습니다.")
        }
        // 실제 스크롤은 좌표가 이동하므로 즉시 숨기고 안정 시간을 새로 측정한다.
        // 일반 내용 변경은 아래 재읽기에서 실제 좌표가 달라졌을 때만 숨긴다.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            eventPackage == selectedTarget?.packageName && !unrelatedScroll) {
            overlay.hide()
            selectedTarget?.candidateBounds = null
            AssistStateRepository.update { it.copy(highlightedNodeId = null) }
        }
        // 창 추가/제거 이벤트에서는 숨기지 않는다. Overlay 자신이 만든 창 이벤트와 순환하지 않는다.
        scheduleCapture()
    }

    private fun actionEvidence(event: AccessibilityEvent): com.example.aplassistmvp0.guide.PublicActionEvidence {
        val source=if(Build.VERSION.SDK_INT>=33) event.getSource(0) else event.source
        val bounds=android.graphics.Rect();source?.getBoundsInScreen(bounds)
        val privateSource=source?.isEditable==true || source?.isPassword==true ||
            Regex("EditText|TextField",RegexOption.IGNORE_CASE).containsMatchIn(source?.className?.toString() ?: event.className?.toString().orEmpty())
        val label=(if(privateSource) "" else event.text.map { it.toString() }.distinct().singleOrNull().orEmpty())
            .takeIf { it.isNotBlank() && it.length<=120 && com.example.aplassistmvp0.guide.ExperiencePolicy.safeText(it)==it }.orEmpty()
        val result=com.example.aplassistmvp0.guide.PublicActionEvidence(event.windowId,
            NodeBounds(bounds.left,bounds.top,bounds.right,bounds.bottom),source?.className?.toString() ?: event.className?.toString().orEmpty(),
            source?.viewIdResourceName.orEmpty(),label,
            if(Build.VERSION.SDK_INT>=28 && event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED) event.scrollDeltaX else null,
            if(Build.VERSION.SDK_INT>=28 && event.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED) event.scrollDeltaY else null,
            eventType=event.eventType,eventUptimeMillis=event.eventTime,receivedUptimeMillis=SystemClock.uptimeMillis(),
            packageName=source?.packageName?.toString() ?: event.packageName?.toString(),sourceEpoch=connectionEpoch)
        @Suppress("DEPRECATION")
        source?.recycle()
        return result
    }

    /** 과도한 이벤트를 모으되 계속 이벤트가 오더라도 주기적으로 화면을 수집한다. */
    private fun scheduleCapture() {
        if (!connected) return
        generation++
        // Handler.hasCallbacks는 API 29 이상이므로 minSdk 26에서도 동작하는 상태값을 사용한다.
        if (!captureScheduled) {
            captureScheduled = true
            handler.postDelayed(captureTask, 180L)
        }
    }

    /** 동시에 여러 트리를 읽지 않고, 읽는 동안 변경된 화면은 다음 작업에서 다시 읽는다. */
    private fun captureCurrentScreen() {
        if (!connected) return
        if (captureRunning) {
            captureAgain = true
            return
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            GuideController.onScreenUnavailable("잠금 화면에서는 안내하지 않습니다. 잠금 해제 후 다시 시작해주세요.")
            removeHighlight("잠금 화면에서는 강조하지 않습니다.")
            return
        }
        captureRunning = true
        val requestGeneration = generation
        val requestInteraction = interactionGeneration
        val requestEpoch = connectionEpoch
        val began = SystemClock.uptimeMillis()
        val traceId = com.example.aplassistmvp0.guide.GuideTiming.traceId().takeIf {
            GuideStateRepository.state.value.phase in setOf("thinking","waiting_screen","guiding","probing","confirming") }
        readerScope.launch {
            val result = runCatching { readActiveApplication() }
            handler.post {
                if (!connected || requestEpoch != connectionEpoch) return@post
                captureRunning = false
                val focused=focusedContentWindow()
                val freshRoot=if(focused!=null) metadataRoot(focused) else rootInActiveWindow
                val livePackage=freshRoot?.packageName?.toString()
                val liveWindow=freshRoot?.windowId ?: focused?.id ?: -1
                observeFocusedWindow(livePackage,liveWindow)
                val capture=result.getOrNull()
                val accepted=requestInteraction==interactionGeneration && (capture==null ||
                    capture.packageName==livePackage && capture.windowId==liveWindow)
                if (traceId != null && GuideStateRepository.state.value.phase in setOf("thinking", "waiting_screen", "guiding", "probing", "confirming")) {
                    val fields = buildMap {
                        put("durationMs", (SystemClock.uptimeMillis() - began).toString())
                        put("accepted", accepted.toString())
                        put("nodes", (capture?.tree?.allNodes?.size ?: 0).toString())
                        put("truncated", capture?.tree?.truncated.toString())
                        put("package", capture?.packageName.orEmpty())
                        put("windowId", (capture?.windowId ?: -1).toString())
                        put("reason", if (accepted) "fresh_window_and_no_interaction_during_read" else "interaction_or_window_changed")
                        capture?.tree?.readGaps?.takeIf { it.incomplete }?.let { gaps ->
                            put("propertyFailures", gaps.propertyFailures.toString())
                            put("childQueryFailures", gaps.childQueryFailures.toString())
                            put("missingChildren", gaps.missingChildren.toString())
                            put("nodeBudgetReached", gaps.nodeBudgetReached.toString())
                            put("depthLimitReached", gaps.depthLimitReached.toString())
                        }
                    }
                    GuideTrace.recordFor(traceId, "capture_complete", fields)
                }
                // Content-only updates schedule another read, but do not starve publication.
                // User actions and fresh window/package checks still invalidate the entire result.
                if (accepted && (result.isSuccess || requestGeneration==generation)) {
                    result.fold({ publishCapture(it, began) }) { error ->
                        overlay.hide()
                        GuideController.onScreenUnavailable("현재 화면을 읽을 수 없어 안내를 멈췄습니다. 다시 시작해주세요.")
                        AssistStateRepository.update {
                            it.copy(error = "화면 정보를 읽을 수 없습니다. 잠시 후 다시 시도하세요.", highlightedNodeId = null)
                        }
                        Log.w(TAG, "Tree read failed", error)
                    }
                }
                if (captureAgain || requestGeneration != generation || !accepted) {
                    captureAgain = false
                    if (!captureScheduled) {
                        captureScheduled = true
                        handler.post(captureTask)
                    }
                }
            }
        }
    }

    /** 안내 창과 키보드를 제외한 실제 포커스 창을 찾는다. 시스템 창이 앞이면 아래 앱을 고르지 않는다. */
    private fun focusedContentWindow(): AccessibilityWindowInfo? {
        val available=windows
        val contentWindows = available.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        // A focusable question overlay owns input focus, while the verified app below it remains our observation context.
        // Pin only that same top application window, from this service epoch, while our own overlay is actually focused.
        val guide=GuideStateRepository.state.value
        val before=AssistStateRepository.state.value.snapshot
        if(guide.currentAction=="ask" && guide.phase=="guiding" && before!=null && before.sourceEpoch==connectionEpoch &&
            before.packageName==guide.targetPackageName && available.any{it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.isFocused && metadataRoot(it)?.packageName?.toString()==packageName}){
            val topApp=contentWindows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION}.maxByOrNull{it.layer}
            if(topApp?.id==before.windowId && metadataRoot(topApp)?.packageName?.toString()==before.packageName)return topApp
        }
        return contentWindows.firstOrNull { it.isFocused }
            ?: contentWindows.firstOrNull { it.isActive }
    }

    /** Root package/bounds checks do not need descendant prefetch. getRoot still reads a fresh root. */
    private fun metadataRoot(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(0) else window.root

    /** 같은 창에서 발생하는 여러 이벤트로 초기 전환 대기 시간이 계속 늘어나지 않게 한다. */
    private fun observeFocusedWindow(currentPackage: String?, windowId: Int) {
        val key = currentPackage to windowId
        if (focusedWindowKey != key) {
            interactionGeneration++
            focusedWindowKey = key
            focusedWindowChangedAt = SystemClock.uptimeMillis()
        }
    }

    /** 키보드·접근성 Overlay를 제외하고 실제 포커스/활성 앱의 루트를 고른다. */
    private fun readActiveApplication(): Capture {
        android.os.Trace.beginSection("APL.accessibility.capture")
        try {
        // 화면 전환 애니메이션 중 캐시된 좌표가 남을 수 있으므로 실제 노드를 다시 읽는다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
        val window = focusedContentWindow()
        if (window != null && window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
            return Capture(metadataRoot(window)?.packageName?.toString(), window.id, null)
        }
        // 실제 포커스 창이 있으면 그 루트만 사용한다. 전환 중 null일 때 다른 활성 창을 대신 읽지 않는다.
        android.os.Trace.beginSection("APL.capture.root")
        val root: AccessibilityNodeInfo? = try { if (window != null) window.root else rootInActiveWindow }
            finally { android.os.Trace.endSection() }
        // Samsung의 일시적인 active-root fallback이 우리 글 안내 창을 가리킬 수 있다.
        // 실제 APL Activity와 구분하고, 안내 창을 다른 앱으로 읽어 세션을 종료하지 않는다.
        if (window == null && root != null && windows.any { it.id == root.windowId &&
                (it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) }) {
            return Capture(null, root.windowId, null)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) root?.refresh()
        val currentPackage = root?.packageName?.toString()
        if (root == null || currentPackage == null || currentPackage == packageName ||
            currentPackage == SYSTEM_UI || (currentPackage == launcherPackage && !com.example.aplassistmvp0.guide.AppOpeningCoach.active)) {
            return Capture(currentPackage, root?.windowId ?: window?.id ?: -1, null)
        }
        // 포커스 창이 없는 경우의 fallback도 루트의 실제 windowId를 사용한다.
        val sameAppWindows = windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (it.id==root.windowId || metadataRoot(it)?.packageName?.toString() == currentPackage) }
        android.os.Trace.beginSection("APL.capture.parse")
        val inputVersion=LocalInputRevisions.changeVersion
        val tree = try { parser.parse(root) } finally { android.os.Trace.endSection() }
        return Capture(currentPackage, root.windowId, tree, sameAppWindows > 1,inputVersion)
        } finally { android.os.Trace.endSection() }
    }

    /** 다른 앱 스냅샷만 교체한다. 우리 앱을 열어도 마지막 대상 화면 목록은 유지한다. */
    private fun publishCapture(capture: Capture, began: Long) {
        val currentPackage = capture.packageName
        val tree = capture.tree
        observeFocusedWindow(currentPackage, capture.windowId)
        GuideController.onForegroundChanged(currentPackage)
        if (currentPackage == null || tree == null) {
            hideForOtherWindow(currentPackage)
            AssistStateRepository.update { it.copy(foregroundPackage = currentPackage, lastEvent = lastEventName) }
            return
        }
        val oldPackage = AssistStateRepository.state.value.snapshot?.packageName
        val snapshot = ScreenSnapshot(
            snapshotId = ++snapshotSequence, packageName = currentPackage, windowId = capture.windowId,
            capturedAtMillis = System.currentTimeMillis(), nodes = tree.nodes,
            allNodes = tree.allNodes, truncated = tree.truncated,
            viewport = displayBounds(),
            blockingPopup = capture.blockingPopup,
            sourceEpoch = connectionEpoch,
            visualInteractionGeneration = interactionGeneration,
            captureStartedAtUptimeMillis = began,
            captureFinishedAtUptimeMillis = SystemClock.uptimeMillis(),
            inputObservationVersion = capture.inputObservationVersion,
            platformActionBarBounds = tree.platformActionBarBounds,
        )
        // 새 트리와 그 트리에 속한 강조 ID를 한 번에 발행해 중간의 불일치 상태를 노출하지 않는다.
        fun publishSnapshot(highlighted: Int? = null, pending: String? = null, message: String? = null) {
            AssistStateRepository.update {
                it.copy(foregroundPackage = currentPackage, snapshot = snapshot, lastEvent = lastEventName,
                    highlightedNodeId = highlighted, pendingTargetLabel = pending,
                    overlayStatus = message ?: it.overlayStatus, error = null)
            }
        }
        Log.i(TAG, "[Screen changed] Old package: $oldPackage; New package: $currentPackage; " +
                "Tree refreshed: ${tree.allNodes.size} nodes, ${tree.nodes.count { it.clickable && it.enabled }} actionable nodes")
        val selection = selectedTarget ?: run { publishSnapshot(); return }
        if (selection.packageName != currentPackage) {
            hideForOtherWindow(currentPackage)
            publishSnapshot(pending = selection.node.displayLabel)
            return
        }
        val resolved = when {
            selection.scopedTap != null -> selection.scopedTap.current(snapshot)
            selection.visualScreen != null -> com.example.aplassistmvp0.guide.GuideVisionPolicy.resolveHighlight(
                selection.visualScreen, snapshot, selection.node.id)
            else -> TargetResolver.resolve(selection.node, tree.allNodes, selection.anchor)
        }
        val openingTargetInvalid = com.example.aplassistmvp0.guide.AppOpeningCoach.active && resolved != null &&
            !com.example.aplassistmvp0.guide.AppOpeningCoach.verifiedOpeningTarget(snapshot, resolved)
        val actualLabels = resolved?.let { if(AccessibilityNodePrivacy.isEditable(it))
            listOf(AccessibilityNodePrivacy.publicInputLabel(it)) else listOfNotNull(it.text, it.contentDescription) + it.descendantLabels }.orEmpty()
        val now = SystemClock.uptimeMillis()
        if (resolved == null || openingTargetInvalid || (selection.anchor != null && selection.anchor !in actualLabels) ||
            !isTargetOnDisplay(resolved.bounds)) {
            // 앱 복귀 애니메이션에서는 이름/좌표가 잠깐 사라지거나 화면 밖으로 보고될 수 있다.
            // 예전 테두리는 숨긴 채 짧게 다시 읽고, 실제로 사라진 대상은 제한 시간 뒤 제거한다.
            overlay.hide()
            selection.candidateBounds = null
            if (selection.unresolvedSince == 0L) selection.unresolvedSince = now
            if (now - selection.unresolvedSince < 1_200L) {
                publishSnapshot(pending = selection.node.displayLabel, message = "화면 전환 후 선택한 항목을 다시 확인하고 있습니다.")
                scheduleCapture()
                return
            }
            removeHighlight("선택한 항목이 없거나 중복됩니다. 현재 화면에서 다시 선택하세요.")
            publishSnapshot()
            return
        }
        selection.unresolvedSince = 0L
        // 화면 전환 도중에도 노드가 보인다고 보고될 수 있다. 두 번 읽은 위치가 안정됐을 때 표시한다.
        val publicState=listOf(resolved.className,resolved.viewIdResourceName,
            com.example.aplassistmvp0.guide.GuidePolicy.label(resolved),resolved.enabled,resolved.visibleToUser,
            resolved.checked,resolved.selected,resolved.focused,resolved.hasInput,resolved.contentInvalid,resolved.range,
            snapshot.platformActionBarBounds)
        if (selection.candidateBounds != resolved.bounds || selection.candidateWindowId != capture.windowId || selection.candidateState!=publicState) {
            selection.candidateBounds = resolved.bounds
            selection.candidateWindowId = capture.windowId
            selection.candidateCapturedAt = now
            selection.candidateState=publicState
            selection.stableObservations=1
            overlay.hide()
            publishSnapshot(pending = selection.node.displayLabel, message = "선택한 항목의 현재 위치를 확인하고 있습니다.")
            scheduleCapture()
            return
        }
        selection.stableObservations++
        // 초기 화면 전환이 안정되고 동일 좌표가 유지된 뒤 한 번만 표시한다.
        // Galaxy 복귀 애니메이션에서 한동안 같은 중간 좌표가 보고된 실기기 로그를 반영한다.
        // 아직 표시하지 않은 창은 실제 내용/스크롤 갱신도 300ms 이상 잦아든 뒤 표시한다.
        // 이미 표시 중인 같은 좌표의 내용 갱신은 숨기지 않아 반복 이벤트로 깜빡이지 않는다.
        val contentStillChanging = !overlay.isShowing &&
            selection.contentChangedAtByWindow[capture.windowId]?.let { now - it < 300L } == true &&
            !com.example.aplassistmvp0.guide.GuideOverlayPolicy.stableDespiteContentUpdates(
                selection.stableObservations,now-selection.candidateCapturedAt,now-focusedWindowChangedAt)
        if (now - selection.candidateCapturedAt < 300L || now - focusedWindowChangedAt < 900L ||
            contentStillChanging) {
            publishSnapshot(pending = selection.node.displayLabel, message = "선택한 항목의 현재 위치를 확인하고 있습니다.")
            scheduleCapture()
            return
        }
        val isScrollRegion = resolved.scrollable && !resolved.clickable
        val touchBounds = touchBounds(snapshot,resolved)
        if(touchBounds==null) {
            overlay.hide()
            publishSnapshot(pending=resolved.displayLabel,message="항목이 다른 버튼에 가려져 있어요. 화면을 조금 스크롤해주세요.")
            return
        }
        val wasShowing=overlay.isShowing
        if (overlay.show(touchBounds, resolved.displayLabel, isScrollRegion)) {
            if(!wasShowing) com.example.aplassistmvp0.guide.GuideTrace.record("highlight_visible",mapOf(
                "node" to resolved.id.toString(),"bounds" to touchBounds.toString(),
                "durationMs" to (now-selection.candidateCapturedAt).toString()))
            selection.hasBeenShown = true
            publishSnapshot(highlighted = resolved.id, message = "‘${resolved.displayLabel}’ 강조 중. " +
                if (isScrollRegion) "이 영역을 직접 밀어 움직이세요." else "실제 항목을 직접 누르세요.")
        } else {
            removeHighlight("Overlay 표시 실패. APLOverlay 로그를 확인하세요.")
            publishSnapshot()
        }
    }

    private fun touchBounds(screen:ScreenSnapshot,node:AccessibilityNodeModel):NodeBounds? {
        val full=screen.viewport ?: displayBounds()
        val safe=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R) runCatching {
            val insets=getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            NodeBounds(full.left+insets.left,full.top+insets.top,full.right-insets.right,full.bottom-insets.bottom)
        }.getOrDefault(full) else full
        return com.example.aplassistmvp0.guide.GuideTouchPolicy.exposedBounds(screen,node,safe)
    }

    /** visibleToUser가 참이어도 전환 중 화면 밖에 남은 좌표에는 테두리를 표시하지 않는다. */
    private fun isTargetOnDisplay(bounds: NodeBounds): Boolean {
        val windowManager = getSystemService(WindowManager::class.java)
        val centerX = bounds.left.toLong() + (bounds.right.toLong() - bounds.left) / 2L
        val centerY = bounds.top.toLong() + (bounds.bottom.toLong() - bounds.top) / 2L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = windowManager.maximumWindowMetrics.bounds
            return centerX >= display.left && centerX < display.right &&
                centerY >= display.top && centerY < display.bottom
        }
        // API 26~29에서는 실제 디스플레이 크기를 조회한다. 상태 표시줄 높이를 추측하지 않는다.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return centerX >= 0L && centerX < metrics.widthPixels && centerY >= 0L && centerY < metrics.heightPixels
    }

    /** 현재 디스플레이 영역을 읽어 화면 밖 조작 후보를 제외한다. */
    private fun displayBounds(): NodeBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return NodeBounds(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun selectNode(node: AccessibilityNodeModel, promoteClickableParent: Boolean = true, visual: Boolean = false,
                           scopedTap: GuideScopedTap? = null): Boolean {
        val snapshot = AssistStateRepository.state.value.snapshot ?: return false
        // UI가 옛 목록 객체를 전달했을 때 새로운 스냅샷의 같은 숫자 ID로 오인하지 않는다.
        val current = snapshot.allNodes.firstOrNull { it === node } ?: run {
            AssistStateRepository.update { it.copy(error = "화면 목록이 갱신되었습니다. 현재 항목을 다시 선택하세요.") }
            return false
        }
        val clickableParent = snapshot.allNodes.firstOrNull { it.path == current.clickableAncestorPath }
        val target = if (promoteClickableParent && !current.clickable && clickableParent != null && clickableParent.enabled &&
            clickableParent.visibleToUser && !clickableParent.bounds.isEmpty) clickableParent else current
        if (!target.enabled || !target.visibleToUser || target.bounds.isEmpty || target.password) return false
        overlay.hide()
        // 자식 글자 대신 행 전체를 강조하더라도 사용자가 선택한 원래 문구는 반드시 유지한다.
        val anchor = if(AccessibilityNodePrivacy.isEditable(current)) AccessibilityNodePrivacy.publicInputLabel(current)
            else current.text ?: current.contentDescription ?: target.text
                ?: target.contentDescription ?: target.descendantLabels.firstOrNull()
        selectedTarget = Selection(snapshot.packageName, target, anchor, visualScreen = if (visual) snapshot else null, scopedTap = scopedTap)
        AssistStateRepository.update {
            it.copy(highlightedNodeId = null, pendingTargetLabel = target.displayLabel,
                overlayStatus = "‘${target.displayLabel}’ 예약됨. ${snapshot.packageName} 화면으로 돌아가세요.")
        }
        Log.i(TAG, "Target selected: package=${snapshot.packageName} path=${target.path} label=${target.displayLabel}")
        scheduleCapture()
        return true
    }

    /** 다른 앱이나 디버그 UI에서는 표시만 숨기고 대상 앱 복귀를 기다린다. */
    private fun hideForOtherWindow(currentPackage: String?) {
        selectedTarget?.candidateBounds = null
        selectedTarget?.unresolvedSince = 0L
        if (::overlay.isInitialized) overlay.hide()
        AssistStateRepository.update {
            it.copy(foregroundPackage = currentPackage, highlightedNodeId = null,
                pendingTargetLabel = selectedTarget?.node?.displayLabel,
                overlayStatus = if (selectedTarget != null) "대상 앱으로 돌아오기를 기다립니다. 현재 화면에는 강조하지 않습니다."
                    else it.overlayStatus)
        }
    }

    /** 새 선택, 사용자 제거, 화면 꺼짐, 서비스 종료에서 기존 표시와 예약을 함께 비운다. */
    private fun removeHighlight(message: String) {
        selectedTarget = null
        if (::overlay.isInitialized) overlay.hide()
        AssistStateRepository.update {
            it.copy(highlightedNodeId = null, pendingTargetLabel = null, overlayStatus = message)
        }
    }

    /** 전체 Tree 로그는 사용자가 명시적으로 요청할 때만 출력한다. 비밀번호는 모델에서 가린다. */
    private fun writeTreeLog() {
        val snapshot = AssistStateRepository.state.value.snapshot ?: return
        Log.i(TREE_TAG, "Tree package=${snapshot.packageName} snapshot=${snapshot.snapshotId} window=${snapshot.windowId}")
        snapshot.allNodes.forEach { node ->
            Log.d(TREE_TAG, "[${node.id}] path=${node.path} class=${node.className} text=${node.text} " +
                "description=${node.contentDescription} viewId=${node.viewIdResourceName} " +
                "clickable=${node.clickable} enabled=${node.enabled} focusable=${node.focusable} " +
                "scrollable=${node.scrollable} visible=${node.visibleToUser} bounds=${node.bounds} actions=${node.actions}")
        }
        AssistStateRepository.update { it.copy(overlayStatus = "전체 트리 ${snapshot.allNodes.size}개를 APLTree 로그로 출력했습니다.") }
    }

    /** 회전/글꼴/화면 크기 변경 후 예전 좌표를 숨기고 새 화면 좌표를 수집한다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        interactionGeneration++
        if (probing) {
            cancelScrollProbe()
            GuideController.onScreenUnavailable("화면 방향이나 크기가 바뀌어 확인을 중단했어요. 다시 요청해주세요.")
        }
        if (::overlay.isInitialized) overlay.hide()
        if (::textOverlay.isInitialized) textOverlay.hide()
        if (::backdrop.isInitialized) backdrop.dispose()
        selectedTarget?.candidateBounds = null
        scheduleCapture()
    }

    override fun onInterrupt() {
        recordLifecycle("interrupt")
        cancelScrollProbe()
        GuideController.onScreenUnavailable("접근성 서비스가 중단되어 안내를 멈췄습니다.")
        removeHighlight("서비스가 중단되어 강조를 제거했습니다.")
    }

    private fun recordLifecycle(event: String) {
        val guide = GuideStateRepository.state.value
        if (guide.goal.isNotBlank() && guide.phase !in setOf("idle", "done")) {
            GuideTrace.record("accessibility_lifecycle", mapOf("event" to event, "phase" to guide.phase))
        }
    }

    private fun disconnect(event: String) {
        if (!connected) return
        recordLifecycle(event)
        connected = false
        cancelScrollProbe()
        textOverlayJob?.cancel()
        textOverlayJob = null
        if (::textOverlay.isInitialized) textOverlay.hide()
        if (::backdrop.isInitialized) backdrop.dispose()
        GuideController.onScreenUnavailable("접근성 서비스가 꺼져 안내를 멈췄습니다.")
        connectionEpoch = ++nextConnectionEpoch
        captureRunning = false
        captureAgain = false
        generation++
        handler.removeCallbacksAndMessages(null)
        captureScheduled = false
        removeHighlight("접근성 서비스가 꺼졌습니다.")
        if (instance === this) instance = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        AssistStateRepository.update {
            it.copy(serviceConnected = false, snapshot = null, foregroundPackage = null, lastEvent = null)
        }
        Log.i(TAG, "Service disconnected; overlay and captured data cleared")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect("unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect("destroy")
        readerScope.cancel()
        uiScope.cancel()
        readerDispatcher.close()
        super.onDestroy()
    }

    /** No click or gesture permission is used; only fresh nodes' supported vertical scroll actions. */
    private suspend fun runScrollProbe(goal: String, origin: ScreenSnapshot): ScrollProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ScrollProbeResult.Unavailable(
            "이 Android 버전에서는 화면을 고정할 수 없어요. 직접 스크롤하면 이어서 확인할게요.")
        val epoch = ++probeEpoch
        val port = object : ScrollProbePort {
            override suspend fun freeze(origin: ScreenSnapshot): Boolean {
                if (!validProbeContext(origin) || probing) return false
                probing = true
                probeJob = currentCoroutineContext()[Job]
                handler.postDelayed(probeDeadline, ScrollProbePolicy.DEADLINE_MILLIS)
                removeHighlight("화면 밖 항목을 확인합니다.")
                textOverlay.hide()
                backdrop.hide()
                delay(80) // do not capture APL's caption or old highlight
                val image = captureProbeImage() ?: return false
                currentCoroutineContext().ensureActive()
                val capture = withContext(readerDispatcher) { readActiveApplication() }
                if (probeEpoch != epoch || !validProbeContext(origin) || capture.tree == null ||
                    capture.packageName != origin.packageName || capture.windowId != origin.windowId) {
                    image.recycle(); return false
                }
                val fresh = origin.copy(nodes = capture.tree.nodes, allNodes = capture.tree.allNodes,
                    truncated = capture.tree.truncated, blockingPopup = capture.blockingPopup,
                    platformActionBarBounds = capture.tree.platformActionBarBounds)
                val anchors = ScrollProbePolicy.container(origin)?.let { ScrollProbePolicy.anchors(origin, it) }.orEmpty()
                if (!ScrollProbePolicy.restored(origin, fresh, anchors)) { image.recycle(); return false }
                if (!frozenOverlay.show(image) {
                    cancelScrollProbe()
                    GuideController.stop()
                }) return false
                return frozenOverlay.awaitFirstFrame()
            }

            override suspend fun read(): ScreenSnapshot? {
                delay(380) // let a semantic scroll animation settle before checking real coordinates
                currentCoroutineContext().ensureActive()
                if (probeEpoch != epoch || !validProbeContext(origin, allowNewWindow = true)) return null
                val began = SystemClock.uptimeMillis()
                val capture = withContext(readerDispatcher) { readActiveApplication() }
                if (probeEpoch != epoch || !connected) return null
                publishCapture(capture, began)
                return AssistStateRepository.state.value.snapshot?.takeIf {
                    it.packageName == capture.packageName && it.windowId == capture.windowId
                }
            }

            override suspend fun scroll(container: AccessibilityNodeModel, direction: ScrollDirection): Boolean =
                withContext(readerDispatcher) {
                    currentCoroutineContext().ensureActive()
                    if (!probing || probeEpoch != epoch || !validProbeContext(origin)) return@withContext false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
                    val root = focusedContentWindow()?.root ?: return@withContext false
                    var real = root
                    for (part in container.path.split('/').drop(1)) {
                        real = real.getChild(part.toIntOrNull() ?: return@withContext false) ?: return@withContext false
                    }
                    // The path alone never authorizes an action. Recheck the current node's identity and role.
                    if (!real.refresh() || !real.isScrollable || !real.isVisibleToUser || !real.isEnabled ||
                        real.isPassword || real.isEditable || real.windowId != origin.windowId ||
                        real.packageName?.toString() != origin.packageName ||
                        real.className?.toString() != container.className || real.viewIdResourceName != container.viewIdResourceName)
                        return@withContext false
                    val action = direction.actionIds.firstOrNull { candidate -> real.actionList.any { it.id == candidate } }
                        ?: return@withContext false
                    currentCoroutineContext().ensureActive()
                    if (!probing || probeEpoch != epoch) return@withContext false
                    real.performAction(action)
                }

            override fun uncover() { if (probeEpoch == epoch) finishScrollProbe() }
        }
        val started = SystemClock.uptimeMillis()
        return ScrollProbeRunner(port, SystemClock::uptimeMillis).run(goal, origin).also { result ->
            val moves = (result as? ScrollProbeResult.Verified)?.moves ?: -1
            Log.i(TAG, "Scroll probe result=${result.javaClass.simpleName} exploration_moves=$moves elapsed_ms=${SystemClock.uptimeMillis() - started}")
        }
    }

    private fun validProbeContext(origin: ScreenSnapshot, allowNewWindow: Boolean = false): Boolean {
        if (!connected || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive) return false
        val window = focusedContentWindow() ?: return false
        return (allowNewWindow || window.id == origin.windowId) && window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            metadataRoot(window)?.packageName?.toString() == origin.packageName && displayBounds() == origin.viewport
    }

    private suspend fun readVisionFrame(origin: ScreenSnapshot): com.example.aplassistmvp0.guide.GuideVisionFrame? = withContext(Dispatchers.Main.immediate) {
        fun unavailable(reason: String): com.example.aplassistmvp0.guide.GuideVisionFrame? {
            com.example.aplassistmvp0.guide.GuideTrace.record("vision_capture_unavailable", mapOf("reason" to reason))
            return null
        }
        // Display screenshots can succeed with a blacked-out secure layer. Window capture
        // explicitly rejects FLAG_SECURE and excludes other services' overlays (API 34+).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@withContext unavailable("window_capture_requires_android_14")
        if (probing || visionCapturing) return@withContext unavailable("capture_busy")
        fun originCurrent() = currentVisualGeneration(origin) && validProbeContext(origin)
        if (!originCurrent()) return@withContext unavailable("app_window_changed_or_locked")
        if (origin.truncated) return@withContext unavailable("incomplete_accessibility_tree")
        // 입력 키보드와 다른 앱의 떠 있는 창은 대상 앱의 트리로 가릴 수 없다.
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return@withContext unavailable("keyboard_visible")
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && metadataRoot(it)?.packageName?.toString() != origin.packageName })
            return@withContext unavailable("another_app_window_visible")
        visionCapturing = true
        removeHighlight("화면을 함께 확인합니다.")
        textOverlay.hide()
        backdrop.hide()
        try {
            val started = SystemClock.uptimeMillis()
            var failureReason = "capture_deadline"
            val frame = kotlinx.coroutines.withTimeoutOrNull(2500) {
                delay(100)
                suspend fun fresh(): Boolean {
                    failureReason = "screen_or_node_binding_changed"
                    if (!originCurrent()) return false
                    val current = withContext(readerDispatcher) { readActiveApplication() }
                    val tree = current.tree ?: return false
                    if (!originCurrent() || current.packageName != origin.packageName || current.windowId != origin.windowId) return false
                    val live = origin.copy(nodes = tree.nodes, allNodes = tree.allNodes, truncated = tree.truncated,
                        blockingPopup = current.blockingPopup, platformActionBarBounds = tree.platformActionBarBounds)
                    return com.example.aplassistmvp0.guide.GuideVisionPolicy.sameScreen(origin, live)
                }
                if (!fresh()) return@withTimeoutOrNull null
                val rootBounds = android.graphics.Rect()
                focusedContentWindow()?.let(::metadataRoot)?.getBoundsInScreen(rootBounds) ?: return@withTimeoutOrNull null
                failureReason = "platform_capture_failed"
                val windowBounds = android.graphics.Rect()
                focusedContentWindow()?.getBoundsInScreen(windowBounds) ?: return@withTimeoutOrNull null
                val image = captureVisionWindow(origin, windowBounds) { error -> failureReason = "android_screenshot_error_$error" }
                    ?: return@withTimeoutOrNull null
                try {
                    if (!fresh()) return@withTimeoutOrNull null
                    failureReason = "redaction_or_image_size_invalid"
                    val encoded = withContext(Dispatchers.Default) {
                        com.example.aplassistmvp0.guide.GuideVisionEncoder.encode(image, origin,
                            NodeBounds(rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom))
                    }
                    if (!originCurrent()) {
                        failureReason = "screen_or_node_binding_changed"
                        return@withTimeoutOrNull null
                    }
                    encoded
                } finally { image.recycle() }
            }
            frame ?: unavailable(if (SystemClock.uptimeMillis() - started >= 2500) "capture_deadline" else failureReason)
        } finally {
            visionCapturing = false
            // 새 상태를 발행해 캡처 중 숨긴 안내 창을 다시 표시한다.
            AssistStateRepository.update { it.copy(overlayStatus = "화면 확인 완료") }
        }
    }

    private fun currentVisualGeneration(origin: ScreenSnapshot) =
        origin.sourceEpoch == connectionEpoch && origin.visualInteractionGeneration == interactionGeneration

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun captureVisionWindow(origin: ScreenSnapshot, bounds: android.graphics.Rect,
        onError: (Int) -> Unit): Bitmap? {
        val viewport = origin.viewport ?: return null
        if (viewport.left != 0 || viewport.top != 0 || bounds.isEmpty || bounds.left < 0 || bounds.top < 0 ||
            bounds.right > viewport.right || bounds.bottom > viewport.bottom) return null
        val windowImage = captureScreenshot(windowId = origin.windowId, onError = onError) ?: return null
        try {
            // Never guess the mapping from a cropped/scaled window to accessibility coordinates.
            if (windowImage.width != bounds.width() || windowImage.height != bounds.height()) return null
            return Bitmap.createBitmap(viewport.right, viewport.bottom, Bitmap.Config.ARGB_8888).also { full ->
                android.graphics.Canvas(full).apply {
                    drawColor(android.graphics.Color.BLACK)
                    drawBitmap(windowImage, bounds.left.toFloat(), bounds.top.toFloat(), null)
                }
            }
        } finally { windowImage.recycle() }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureProbeImage(onError: (Int) -> Unit = {}): Bitmap? = captureScreenshot(onError = onError)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshot(windowId: Int? = null, onError: (Int) -> Unit = {}): Bitmap? = suspendCancellableCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val image = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                        try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                    }
                } catch (_: RuntimeException) { null } finally { buffer.close() }
                continuation.resume(image) { _, value, _ -> value?.recycle() }
            }
            override fun onFailure(errorCode: Int) { onError(errorCode); continuation.resume(null) { _, _, _ -> } }
        }
        if (windowId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            takeScreenshotOfWindow(windowId, mainExecutor, callback)
        else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
    }

    private fun finishScrollProbe() {
        handler.removeCallbacks(probeDeadline)
        if (probing) ignoreProbeScrollUntil = SystemClock.uptimeMillis() + 400L
        probing = false
        probeJob = null
        if (::frozenOverlay.isInitialized) frozenOverlay.hide()
        scheduleCapture()
    }

    private fun cancelScrollProbe() {
        if (!probing && probeJob == null) return
        probeEpoch++
        val job = probeJob
        finishScrollProbe() // uncover immediately, without another scroll
        job?.cancel()
    }

    companion object {
        private const val TAG = "APLAssist"
        private const val TREE_TAG = "APLTree"
        private const val SYSTEM_UI = "com.android.systemui"
        private var nextConnectionEpoch = 0L
        private var instance: AssistAccessibilityService? = null
        internal fun safeTouchBounds(screen:ScreenSnapshot,node:AccessibilityNodeModel):NodeBounds? = instance?.touchBounds(screen,node)

        internal suspend fun probeScroll(goal: String, origin: ScreenSnapshot): ScrollProbeResult =
            instance?.runScrollProbe(goal, origin) ?: ScrollProbeResult.Unavailable("접근성 서비스를 켜주세요.")
        internal fun cancelProbe() { instance?.cancelScrollProbe() }
        internal suspend fun captureVision(origin: ScreenSnapshot): com.example.aplassistmvp0.guide.GuideVisionFrame? =
            instance?.readVisionFrame(origin)

        /** Activity에서만 사용하는 메모리 명령 통로다. 외부 앱용 Receiver나 IPC는 만들지 않는다. */
        private fun send(command: AssistAccessibilityService.() -> Unit) {
            val service = instance
            if (service == null) {
                AssistStateRepository.update { it.copy(error = "먼저 접근성 설정에서 APL 서비스를 켜주세요.") }
                return
            }
            service.handler.post { if (service.connected) service.command() }
        }

        fun requestHighlight(node: AccessibilityNodeModel) = send { selectNode(node) }
        /** AI 응답은 메인 스레드에서 현재 객체·현재 포커스를 함께 확인한 뒤 기존 안정 표시 경로에 넣는다. */
        fun requestGuideHighlight(node: AccessibilityNodeModel): Boolean = requestGuideHighlight(node, false)
        /** Only GuideEngine's already-validated screenshot binding uses this path. Source labels are not proof. */
        internal fun requestVisualGuideHighlight(node: AccessibilityNodeModel): Boolean = requestGuideHighlight(node, true)
        internal fun requestScopedGuideHighlight(binding: GuideScopedTap): Boolean =
            requestGuideHighlight(binding.node, visual = false, scopedTap = binding)
        private fun requestGuideHighlight(node: AccessibilityNodeModel, visual: Boolean, scopedTap: GuideScopedTap? = null): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) return false
            val service = instance ?: return false
            if (!service.connected || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            val snapshot = AssistStateRepository.state.value.snapshot ?: return false
            val guide = GuideStateRepository.state.value
            val targetPackage = guide.targetPackageName
            // 자동 안내는 현재 세션에서 선택한 앱만 허용한다. debug requestHighlight는 이 제한을 공유하지 않는다.
            if (!com.example.aplassistmvp0.guide.GuideHighlightPhasePolicy.allowed(guide.phase,
                    com.example.aplassistmvp0.guide.AppOpeningCoach.active, visual || scopedTap != null) || guide.goal.isBlank() ||
                !GuidePolicy.isValidTargetPackage(targetPackage) ||
                AssistStateRepository.state.value.foregroundPackage != targetPackage ||
                snapshot.packageName != targetPackage || snapshot.allNodes.none { it === node } ||
                ((visual || scopedTap != null) && !service.currentVisualGeneration(snapshot))) return false
            val current = when {
                scopedTap != null -> scopedTap.current(snapshot)
                visual -> com.example.aplassistmvp0.guide.GuideVisionPolicy.resolveHighlight(snapshot, snapshot, node.id)
                else -> GuidePolicy.resolveDecisionNode(snapshot, node.id)
                    ?: node.takeIf { com.example.aplassistmvp0.guide.AppOpeningCoach.verifiedOpeningTarget(snapshot, it) }
            }
            if (current !== node) return false
            val focused = service.focusedContentWindow() ?: return false
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION || focused.id != snapshot.windowId ||
                service.metadataRoot(focused)?.packageName?.toString() != targetPackage) return false
            // AI 후보는 Policy가 이미 클릭 행 우선으로 정리했다. 응답에 없는 조상으로 다시 바꾸지 않는다.
            return service.selectNode(node, promoteClickableParent = false, visual = visual, scopedTap = scopedTap)
        }
        fun clearHighlight() = send { removeHighlight("강조를 제거했습니다.") }
        fun requestRefresh() = send { scheduleCapture() }
        fun dumpTree() = send { writeTreeLog() }
        fun clearCapturedData() = send {
            GuideController.onScreenUnavailable("화면 기록을 지워 안내를 멈췄습니다. 다시 시작해주세요.")
            // 삭제 전에 시작된 읽기와 예약을 무효화해 옛 기록이 뒤늦게 되살아나지 않게 한다.
            connectionEpoch = ++nextConnectionEpoch
            generation++
            captureRunning = false
            captureAgain = false
            captureScheduled = false
            handler.removeCallbacks(captureTask)
            removeHighlight("기록과 강조를 지웠습니다.")
            AssistStateRepository.clearSnapshot()
        }
    }
}
