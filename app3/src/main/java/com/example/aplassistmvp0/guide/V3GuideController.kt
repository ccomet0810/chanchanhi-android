package com.example.aplassistmvp0.guide

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.accessibility.TargetResolver
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.state.AssistUiState
import com.example.aplassistmvp0.state.ScreenSnapshot
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 연결부다. 클릭은 사용자가 하며, 화면 밖 확인을 위한 제한된 스크롤만 서비스가 한다.
 * 세션 처리와 최신 화면 검증은 아래 V3GuideEngine에서 Android 없이도 시험할 수 있다.
 */
object GuideController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: V3GuideEngine? = null
    private var serviceContext=java.lang.ref.WeakReference<android.accessibilityservice.AccessibilityService>(null)
    private var questionPanel:V3QuestionPanel?=null
    private var questionObservationWait:Job?=null
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var lastSpoken = ""
    private var pendingSpeech: String? = null
    private var speechSerial = 0L
    private var ttsContext: Context? = null

    /** Activity 또는 서비스가 최초 한 번 초기화한다. Activity 자체를 보관하지 않는다. */
    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if(context is android.accessibilityservice.AccessibilityService){
            if(serviceContext.get()!==context){questionPanel?.hide();questionPanel=null}
            serviceContext=java.lang.ref.WeakReference(context)
        }
        if (engine != null) return
        val application = context.applicationContext
        ttsContext = application
        AplPreferences.initialize(application)
        GuideHistory.initialize(application)
        GuideTrace.initialize(application)
        GuideComparisons.initialize(application)
        ExperienceStore.initialize(application)
        val client=V3PlanClient(application)
        engine=V3GuideEngine(scope,AssistStateRepository.state,application.packageName,
            client::status,client::plan,
            publish=::publishState,
            highlight=AssistAccessibilityService::requestGuideHighlight,
            clearHighlight={ if(AssistStateRepository.state.value.serviceConnected) AssistAccessibilityService.clearHighlight() },
            speak=::speak,stopSpeech={ GuideCaption.cancelSpeech();tts?.stop();lastSpoken="";pendingSpeech=null },
            refreshScreen=AssistAccessibilityService::requestRefresh,trace=GuideTrace::record,
            nowUptime=android.os.SystemClock::uptimeMillis,
            inputObservationVersion={com.example.aplassistmvp0.accessibility.LocalInputRevisions.changeVersion},
            highlightScoped=AssistAccessibilityService::requestScopedGuideHighlight)
        com.example.aplassistmvp0.accessibility.LocalInputRevisions.bind{pkg,window->
            onMain{engine?.onInputChanged(pkg,window)}
        }
        // 언어 데이터가 없거나 엔진 초기화가 실패해도 GuideUiState의 글 안내는 계속 제공한다.
        tts = TextToSpeech(application) { status ->
            scope.launch {
                voiceReady = status == TextToSpeech.SUCCESS &&
                    tts?.setLanguage(Locale.KOREA)?.let {
                        it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                    } == true
                if (voiceReady) pendingSpeech?.let { pendingSpeech=null; speak(it) }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.removePrefix("apl-guide-")?.substringBefore('-')?.toLongOrNull()?.let {
                    GuideTiming.markFor(it, "tts_started")
                    GuideTrace.recordFor(it, "tts_start")
                }
            }
            override fun onDone(utteranceId: String?) { scope.launch {
                utteranceId?.let { if (audible()) GuideCaption.speechDone(it) else GuideCaption.speechFailed(it) }
                utteranceId?.removePrefix("apl-guide-")?.substringBefore('-')?.toLongOrNull()?.let { GuideTrace.recordFor(it, "tts_done") }
            } }
            @Deprecated("Required by Android TTS listener")
            override fun onError(utteranceId: String?) { scope.launch {
                utteranceId?.let { GuideCaption.speechFailed(it) }
                utteranceId?.removePrefix("apl-guide-")?.substringBefore('-')?.toLongOrNull()?.let { GuideTrace.recordFor(it, "tts_error") }
            } }
        })
    }

    /** true일 때 Activity가 선택한 앱을 연다. 패키지 검증·연결 확인 중 취소하면 false를 반환한다. */
    suspend fun start(goal: String, targetPackage: String = GuidePolicy.SETTINGS_PACKAGE,
                      targetAppLabel: String = "설정"): Boolean = start(goal, targetPackage, targetAppLabel, null)

    /** Explicit owner-bound entry; shared legacy callers keep using the three-argument overload. */
    internal suspend fun start(goal: String, targetPackage: String, targetAppLabel: String,
                               goalRef: GuideGoalRef?): Boolean = withContext(Dispatchers.Main.immediate) {
        com.example.aplassistmvp0.accessibility.LocalInputRevisions.clear()
        com.example.aplassistmvp0.accessibility.LocalInputRevisions.enable(targetPackage)
        engine?.start(goal, targetPackage, targetAppLabel, goalRef) ?: run {
            GuideStateRepository.publish(GuideUiState(phase = "error", message = "앱을 다시 열어 안내를 준비해주세요."))
            false
        }
    }

    /** 사용자가 중단하면 예약·응답 대기·음성 출력을 함께 취소한다. */
    fun stop() = onMain { questionPanel?.hide();questionPanel=null; AppOpeningCoach.stop(); engine?.stop()
        com.example.aplassistmvp0.accessibility.LocalInputRevisions.deactivate() }
    fun announceChoice(message: String) = onMain { speak(message) }
    fun silence() = onMain { pendingSpeech=null; GuideCaption.cancelSpeech(); tts?.stop(); lastSpoken = "" }
    fun retry() = onMain {
        if (AppOpeningCoach.retry()) return@onMain
        val current = engine?.ui ?: return@onMain
        if (current.phase !in setOf("error", "paused") || current.goal.isBlank()) return@onMain
        scope.launch {
            com.example.aplassistmvp0.accessibility.LocalInputRevisions.enable(current.targetPackageName)
            if (engine?.retryFromCurrent() == true)
                AssistAccessibilityService.requestRefresh()
        }
    }
    internal fun onVisionCheck() = onMain { engine?.showVisionProgress() }

    /** 실제 사용자 클릭·스크롤을 구분한다. 탐색 스크롤은 서비스에서 제외한다. */
    internal fun onUserAction(packageName: String, scrolled: Boolean = false, nodeId: Int? = null,
                     direction: ScrollDirection? = null, evidence: PublicActionEvidence? = null) = onMain {
        engine?.onUserAction(packageName, scrolled, nodeId, direction, evidence)
    }

    fun confirmCompleted(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val accepted = engine?.confirmCompleted() == true
        if (!accepted) AssistAccessibilityService.requestRefresh()
        return accepted
    }
    fun confirmControl() = onMain {
        if(engine?.ui?.showsScopedQuestion==true)showQuestion() else engine?.confirmControl()
    }
    internal fun publishState(value:GuideUiState){
        GuideStateRepository.publish(value)
        if(value.showsScopedQuestion)showQuestion() else {questionObservationWait?.cancel();questionObservationWait=null;questionPanel?.hide()}
    }
    private fun showQuestion() {
        val owner=engine?.takeIf{it.ui.showsScopedQuestion} ?:return
        val q=owner.question()?:return
        val context=serviceContext.get()?:return
        if(questionPanel==null)questionPanel=V3QuestionPanel(context,::silence,::stop)
        questionPanel?.show(q){key,text->engine===owner && owner.question()===q && owner.ui.showsScopedQuestion && owner.answer(key,text)}
    }

    fun reportWrong() = onMain {
        if (AppOpeningCoach.active) {
            AppOpeningCoach.stop()
            GuideStateRepository.publish(GuideStateRepository.state.value.copy(phase = "paused",
                message = "사용할 앱을 다시 선택해주세요.", busy = false))
            return@onMain
        }
        val current = engine?.ui ?: return@onMain
        scope.launch {
            com.example.aplassistmvp0.accessibility.LocalInputRevisions.enable(current.targetPackageName)
            if (engine?.retryFromCurrent(rejected = true) == true)
                AssistAccessibilityService.requestRefresh()
        }
    }

    /** 화면 꺼짐·잠금·서비스 종료는 자동 재개하지 않고 새 시작을 기다린다. */
    fun onScreenUnavailable(reason: String) = onMain {
        com.example.aplassistmvp0.accessibility.LocalInputRevisions.deactivate()
        questionPanel?.hide();questionPanel=null
        if (AppOpeningCoach.active) {
            AppOpeningCoach.stop()
            GuideStateRepository.publish(GuideStateRepository.state.value.copy(phase = "paused", message = reason, busy = false))
        }
        engine?.onScreenUnavailable(reason)
    }

    /** 실제 포커스 창의 변경을 즉시 반영하여 이전 화면의 네트워크 응답을 무효화한다. */
    fun onForegroundChanged(packageName: String?) = onMain {
        val owner=engine
        val question=owner?.question()
        if(packageName==null && questionPanel?.isShowing==true && question!=null && owner!=null && owner.deferOwnQuestionObservation()){
            questionPanel?.setObservationPending(true)
            if(questionObservationWait?.isActive!=true){
                questionObservationWait=scope.launch{
                    delay(750)
                    questionObservationWait=null
                    if(engine===owner)owner.expireQuestionObservation(question)
                }
                AssistAccessibilityService.requestRefresh()
            }
            return@onMain
        }
        questionObservationWait?.cancel();questionObservationWait=null
        questionPanel?.setObservationPending(false)
        owner?.onForegroundChanged(packageName)
    }

    @androidx.annotation.MainThread
    internal fun shouldIgnoreScroll(evidence: PublicActionEvidence): Boolean = engine?.shouldIgnoreScroll(evidence)==true

    /** 음성 창을 닫은 뒤 현재 앱 복귀에 실패하면 글·음성으로 이유를 남깁니다. */
    fun reportAssistantStartFailure(goal: String, message: String) = onMain {
        engine?.reportAssistantStartFailure(goal, message)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else scope.launch { block() }
    }

    /** 같은 결과를 반복 수집해도 같은 문장을 다시 발화하지 않는다. */
    private fun speak(message: String) {
        val preferences = ttsContext?.let(AplPreferences::get) ?: return
        // APL 2 presentation preferences never change the guide decision or hide its written caption.
        if (!preferences.voiceEnabled) return
        if (message.isBlank() || lastSpoken == message) return
        if (!voiceReady) { pendingSpeech=message; return }
        val trace = GuideTiming.traceId()
        if (!audible()) return
        lastSpoken = message
        tts?.setSpeechRate(preferences.speechRate)
        val utterance = "apl-guide-$trace-${++speechSerial}"
        GuideCaption.speechStarted(utterance, message)
        val result = tts?.speak(message.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH,
            null, utterance)
        if (result == TextToSpeech.SUCCESS) GuideTiming.markFor(trace, "tts_enqueued")
        else GuideCaption.speechFailed(utterance)
    }

    private fun audible(): Boolean {
        if (ttsContext?.let(AplPreferences::get)?.voiceEnabled != true) return false
        val audio = ttsContext?.getSystemService(android.media.AudioManager::class.java) ?: return false
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0 &&
            !audio.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
    }
}
