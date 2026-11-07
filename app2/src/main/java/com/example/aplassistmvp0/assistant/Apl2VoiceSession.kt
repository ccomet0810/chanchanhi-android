package com.example.aplassistmvp0.assistant

import android.Manifest
import android.animation.ValueAnimator
import android.graphics.Typeface
import com.example.aplassistmvp0.overlay.*
import com.example.aplassistmvp0.apl2.design.AplAssistantFoundation as AssistantTokens
import android.os.Build
import com.example.aplassistmvp0.overlay.Apl2NativeStyle
import com.example.aplassistmvp0.overlay.Apl2Icon
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.apl2.design.AplFoundation
import android.annotation.TargetApi
import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 현재 앱 위에 잠시 열리는 시스템 어시스턴트입니다. 화면 내용·스크린샷·음성 원음을 보관하지 않습니다. */
class AplVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var invocationId = 0L
    private var visible = false
    private var handoff = false
    private var busy = false
    private var startJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognitionStopping = false
    private var recognitionGeneration = 0L
    private var internalTextChange = false
    private enum class InputMode { VOICE, TYPE, REVIEW }
    private var inputMode = InputMode.VOICE
    private lateinit var inputChoices: LinearLayout
    private lateinit var waveform: Apl2WaveformView
    private lateinit var voiceControl: FrameLayout
    private lateinit var stopButton: Button
    private var textAnimator: ValueAnimator? = null
    private var renderedBusy: Boolean? = null
    private var pendingSelectedPackage: String? = null
    private lateinit var sessionRoot: View
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var retryButton: Button
    private lateinit var closeButton: Button
    private lateinit var routeButtons: LinearLayout
    private var clarificationPending = false
    private var routeGoal: String? = null
    private var routeCandidates = emptyList<com.example.aplassistmvp0.guide.TargetApp>()
    private val lockMonitor = object : Runnable {
        override fun run() {
            if (!visible) return
            if (!canInteract()) closeInvocation() else handler.postDelayed(this, 300L)
        }
    }

    init { setTheme(com.example.aplassistmvp0.R.style.Theme_APL2_Assistant) }

    override fun onCreate() {
        super.onCreate()
        setDisabledShowContext(SHOW_WITH_SCREENSHOT)
    }

    /** 큰 글자·버튼과 높이가 제한된 스크롤 영역으로 작은 화면·키보드에서도 조작할 수 있습니다. */
    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setOnClickListener { closeInvocation() }
        }
        sessionRoot = root
        root.addView(Apl2BackdropView(context), FrameLayout.LayoutParams(-1, -1))
        val shell = Apl2AssistantPanel(context) { closeInvocation() }
        closeButton = shell.closeButton
        val panel = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) {
                val available = View.MeasureSpec.getSize(h)
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec((available * .84f).toInt(), View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false; isVerticalScrollBarEnabled = false
            background = Apl2NativeStyle.surface(context, fill = 0xE0FFFFFF.toInt(), radius = 24, strokeWidth = 0)
            clipToOutline = true
            addView(shell)
        }
        input = EditText(context).apply {
            Apl2AssistantStyle.applyText(this, 20f)
            hint = "원하시는 작업을 말씀해 주세요!"
            contentDescription = "요청 입력"
            setHintTextColor(AssistantTokens.PlaceholderArgb)
            background = null
            setPadding(dp(4), dp(16), dp(4), dp(16))
            minLines = 1
            filters = arrayOf(InputFilter.LengthFilter(500))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            showSoftInputOnFocus = false; isCursorVisible = true
            isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener { if (visible && !busy) beginTyping() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!internalTextChange && visible && !busy) {
                        cancelRecognition(); pendingSelectedPackage = null
                        if (!clarificationPending) { routeGoal = null; routeCandidates = emptyList(); status.text = "" }
                        if (::routeButtons.isInitialized) routeButtons.removeAllViews()
                    }
                    updateControls()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                paint.shader = if (busy) android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f),
                    AssistantTokens.HistoryArgb, AssistantTokens.PlaceholderArgb, android.graphics.Shader.TileMode.CLAMP) else null
            }
        }
        shell.body.addView(input, LinearLayout.LayoutParams(-1, -2))
        status = Apl2AssistantStyle.text(context).apply {
            setPadding(dp(4), dp(16), dp(4), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        shell.body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        routeButtons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        shell.body.addView(routeButtons)
        startButton = Apl2AssistantStyle.button(context, "전송", Apl2MaterialSymbols.Send) { startGuidance(pendingSelectedPackage) }
        retryButton = Apl2AssistantStyle.button(context, "녹음 중지", Apl2MaterialSymbols.Stop) {
            if (recognizer != null && !recognitionStopping) {
                val speech = recognizer
                recognitionStopping = true
                inputMode = InputMode.REVIEW
                updateControls()
                runCatching { speech?.stopListening() }.onFailure {
                    cancelRecognition(); inputMode = InputMode.REVIEW; updateControls()
                }
                handler.postDelayed({
                    if (recognizer === speech && recognitionStopping) {
                        cancelRecognition(); updateControls()
                    }
                }, 2_000L)
            } else {
                sessionRoot.requestFocus()
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                input.showSoftInputOnFocus = false; input.isCursorVisible = true
                input.requestFocus()
                startRecognition()
            }
        }
        waveform = Apl2WaveformView(context)
        voiceControl = FrameLayout(context).apply {
            background = Apl2NativeStyle.surface(context, AssistantTokens.ControlArgb, radius = 1024, strokeWidth = 0)
            clipToOutline = true
            addView(waveform, FrameLayout.LayoutParams(-1, -1))
            retryButton.background = null
            addView(retryButton, FrameLayout.LayoutParams(-1, -2))
        }
        inputChoices = shell.actions
        val stacked = context.resources.configuration.fontScale > 1.3f || context.resources.configuration.screenWidthDp < 350
        inputChoices.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        inputChoices.addView(voiceControl, if (stacked) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        inputChoices.addView(startButton, LinearLayout.LayoutParams(if (stacked) -1 else dp(110), -2))
        stopButton = Apl2AssistantStyle.button(context, "중지", Apl2MaterialSymbols.Stop) { closeInvocation() }
        shell.addView(stopButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val params = FrameLayout.LayoutParams(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(380)), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) }
        root.addView(panel, params)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom: Int; val top: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                bottom = bars.bottom; top = bars.top
                params.width = minOf(context.resources.displayMetrics.widthPixels - bars.left - bars.right - dp(32), dp(380))
            } else {
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
            }
            params.bottomMargin = maxOf(dp(32), bottom + dp(8)); params.topMargin = top + dp(8)
            panel.layoutParams = params
            insets
        }
        window.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
            }
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(-1, -1)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        updateControls()
        return root
    }

    private fun beginTyping() {
        cancelRecognition()
        inputMode = InputMode.TYPE
        if (!clarificationPending) status.text = ""
        updateControls()
        input.isFocusable = true; input.isFocusableInTouchMode = true
        input.showSoftInputOnFocus = true; input.isCursorVisible = true
        input.requestFocus()
        input.post { if (visible && inputMode == InputMode.TYPE && !busy)
            context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** API 26 이상의 공식 순서인 UI 생성 이전 단계에서 호출 순간의 앱 식별자만 기억합니다. */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        visible = false
        releaseInvocation()
        handoff = false
        busy = false
        val unlocked = canInteract()
        setUiEnabled(unlocked)
        if (unlocked) invocationId = AssistantGuideCoordinator.beginInvocation()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 준비한 ID를 유지합니다. 창 생성 뒤에는 이전 화면 노드·좌표·앱 식별자를 다시 추정하지 않습니다.
        if (invocationId == 0L || !canInteract()) { closeInvocation(); return }
        visible = true
        routeButtons.removeAllViews()
        clarificationPending = false
        routeGoal = null
        routeCandidates = emptyList()
        setInputText("")
        inputMode = InputMode.VOICE
        renderedBusy = null; pendingSelectedPackage = null
        input.isFocusable = true; input.isFocusableInTouchMode = true
        input.showSoftInputOnFocus = false; input.isCursorVisible = true
        input.requestFocus()
        status.text = ""
        updateControls()
        handler.post(lockMonitor)
        // Invoking the system assistant is the user action that starts listening.
        rootStartListening()
    }

    private fun rootStartListening() { sessionRoot.post { if (visible && !busy) startRecognition() } }

    /** Android 26~28의 콜백에서는 최상위 앱 Component만 전달하고 노드·텍스트는 읽지 않습니다. */
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        supplyPackage(structure?.activityComponent?.packageName)
    }

    /** 다중 창에서는 Android가 포커스가 있다고 알려준 앱만 후보로 전달합니다. */
    @TargetApi(29)
    override fun onHandleAssist(state: AssistState) {
        if (state.isFocused) supplyPackage(state.assistStructure?.activityComponent?.packageName)
    }

    private fun supplyPackage(packageName: String?) {
        if (visible && invocationId != 0L && canInteract())
            AssistantGuideCoordinator.supplyAssistPackage(invocationId, packageName)
    }

    /** Live text comes from recognition events; raw audio is never stored. */
    private fun startRecognition() {
        if (!visible || busy || !canInteract()) return
        com.example.aplassistmvp0.guide.GuideController.silence()
        cancelRecognition()
        inputMode = InputMode.VOICE
        updateControls()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            inputMode = InputMode.REVIEW
            status.text = ""
            updateControls()
            return
        }
        val provider = SpeechProviderResolver.externalProvider(context)
        if (provider == null) {
            inputMode = InputMode.REVIEW
            status.text = ""
            updateControls()
            return
        }
        val generation = recognitionGeneration
        var heardSpeech = false
        val current = invocationId
        fun valid() = visible && !busy && invocationId == current && recognitionGeneration == generation && canInteract()
        try {
            val speech = SpeechRecognizer.createSpeechRecognizer(context, provider)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (valid()) { status.text = ""; updateControls() } }
                override fun onBeginningOfSpeech() { if (valid()) heardSpeech = true }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!valid()) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { setInputText(it.take(500)) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!valid()) return
                    cancelRecognition()
                    inputMode = InputMode.REVIEW
                    status.text = ""
                    Log.d("APLAssistant", "recognition ended error=" + error)
                    updateControls()
                }
                override fun onResults(results: Bundle?) {
                    if (!valid()) return
                    val goal = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.take(500)
                    cancelRecognition()
                    if (goal.isNullOrBlank()) { inputMode = InputMode.REVIEW; status.text = ""; updateControls(); return }
                    val chosen = com.example.aplassistmvp0.guide.AppRouteResolver(context).resolve(goal, routeCandidates)
                        ?.takeIf { match -> routeCandidates.any { it.packageName == match.packageName } }
                    if (chosen != null && routeGoal != null) {
                        setInputText(routeGoal!!)
                        pendingSelectedPackage = chosen.packageName
                        inputMode = InputMode.REVIEW; status.text = ""; updateControls()
                        return
                    }
                    setInputText(goal)
                    inputMode = InputMode.REVIEW
                    status.text = ""
                    updateControls()
                }
            })
            status.text = ""
            updateControls()
            handler.postDelayed({
                if (valid() && !heardSpeech && input.text.isNullOrBlank()) {
                    cancelRecognition(); inputMode = InputMode.REVIEW; status.text = ""; updateControls()
                }
            }, 8_000L)
            speech.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
        } catch (_: Exception) {
            cancelRecognition()
            inputMode = InputMode.REVIEW
            status.text = ""
            updateControls()
        }
    }

    /** 세션을 닫은 뒤의 화면 검증·안내 시작은 Coordinator의 앱 수명주기 작업이 소유합니다. */
    private fun startGuidance(selectedPackage: String? = null) {
        Log.d("APLAssistant", "submit id=$invocationId visible=$visible busy=$busy unlocked=${canInteract()} textPresent=${input.text?.isNotBlank() == true} scopeActive=${scope.coroutineContext[Job]?.isActive}")
        if (!visible || busy || !canInteract()) return
        val supplied = input.text?.toString()?.trim()?.take(500).orEmpty()
        if (supplied.isEmpty()) { status.text = "원하는 작업이나 질문의 답을 말씀하거나 입력해주세요."; return }
        val goal = if (clarificationPending && routeGoal != null) (routeGoal + " / " + supplied).take(500) else supplied
        cancelRecognition()
        busy = true
        status.text = "분석 중입니다...\n잠시만 기다려 주세요..."
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        updateControls()
        com.example.aplassistmvp0.guide.GuideController.announceChoice("분석 중입니다. 잠시만 기다려 주세요.")
        val current = invocationId
        val clarified = clarificationPending
        clarificationPending = false
        routeGoal = null
        routeCandidates = emptyList()
        routeButtons.removeAllViews()
        startJob = scope.launch {
            try {
                val result = AssistantGuideCoordinator.start(current, goal, selectedPackage, clarified, clarificationText = supplied) {
                    if (visible && invocationId == current) {
                        handoff = true
                        hide()
                    }
                }
                if (visible && invocationId == current) {
                    busy = false
                    inputMode = InputMode.REVIEW
                    // Shared routing can return legacy setup labels; show the actual APL2 menu names.
                    status.text = when (result.message) {
                        "APL 앱을 열어 설정해주세요." -> "APL2를 열어 설정을 확인해 주세요."
                        "APL 앱에서 화면 읽기 접근성 서비스를 켜주세요." -> "설정 → 권한에서 화면 읽기를 켜 주세요."
                        "안내를 시작하지 못했습니다. APL에서 API 키 설정을 확인해주세요." -> "설정 → AI 연결을 확인해 주세요."
                        else -> result.message
                    }
                    if (result.started || result.ended) { handoff = true; hide() } else {
                        result.route?.let { showRoute(it, result.displayedGoal ?: goal) }
                        updateControls()
                    }
                }
            } catch (_: CancellationException) {
                // 새 호출·닫기에서 취소된 작업의 결과를 이전 창에 다시 표시하지 않습니다.
            } catch (_: Exception) {
                if (visible && invocationId == current) {
                    busy = false
                    inputMode = InputMode.REVIEW
                    status.text = "연결을 확인하고 다시 시도해 주세요."
                    updateControls()
                }
            }
        }
    }

    private fun showRoute(route: com.example.aplassistmvp0.guide.GoalRoute, goal: String) {
        routeButtons.removeAllViews()
        routeGoal = goal
        routeCandidates = route.candidates
        com.example.aplassistmvp0.guide.GuideController.announceChoice(route.question +
            if (route.candidates.isEmpty()) "" else " " + route.candidates.joinToString(", ") { it.label } + " 중 골라주세요.")
        clarificationPending = route.candidates.isEmpty() && route.source != "manual"
        if (clarificationPending) setInputText("")
        route.candidates.forEach { app -> routeButtons.addView(button(app.label) {
            setInputText(goal); startGuidance(app.packageName)
        }, rowParams()) }
        route.answers.forEach { answer -> routeButtons.addView(button(answer) {
            setInputText(answer); startGuidance()
        }, rowParams()) }
        routeButtons.addView(button("다른 앱 선택") {
            clarificationPending = false
            routeButtons.removeAllViews()
            val apps = com.example.aplassistmvp0.guide.AppRouteResolver(context).installedApps()
            routeCandidates = apps
            val search = EditText(context).apply { hint = "앱 이름 검색"; textSize = 20f; isSaveEnabled = false }
            val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun populate(query: String) {
                choices.removeAllViews()
                apps.filter { it.label.contains(query, true) }.take(30).forEach { app -> choices.addView(button(app.label) {
                    setInputText(goal); startGuidance(app.packageName)
                }, rowParams()) }
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            routeButtons.addView(search); routeButtons.addView(choices); populate("")
            status.text = "사용할 앱을 골라 주세요."
            updateControls()
        }, rowParams())
    }

    private fun cancelRecognition() {
        stopPulse()
        recognitionStopping = false
        recognitionGeneration++
        recognizer?.let { speech -> runCatching { speech.cancel() }; runCatching { speech.destroy() } }
        recognizer = null
    }

    private fun releaseInvocation() {
        cancelRecognition()
        if (!handoff) com.example.aplassistmvp0.guide.GuideController.silence()
        textAnimator?.cancel(); textAnimator = null
        handler.removeCallbacksAndMessages(null)
        if (!handoff) startJob?.cancel()
        startJob = null
        if (invocationId != 0L) AssistantGuideCoordinator.cancelInvocation(invocationId)
        invocationId = 0L
        if (::input.isInitialized) setInputText("")
    }

    private fun closeInvocation() { visible = false; releaseInvocation(); hide() }
    override fun onBackPressed() { closeInvocation() }
    override fun onCloseSystemDialogs() { closeInvocation() }
    override fun onLockscreenShown() { closeInvocation() }
    override fun onHide() { visible = false; releaseInvocation(); super.onHide() }
    override fun onDestroy() { visible = false; releaseInvocation(); scope.cancel(); super.onDestroy() }

    private fun canInteract(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun updateControls() {
        if (!::startButton.isInitialized || !::inputChoices.isInitialized) return
        val listening = recognizer != null && !recognitionStopping
        status.visibility = if (status.text.isNotBlank()) View.VISIBLE else View.GONE
        input.visibility = if (input.text?.isNotBlank() == true || !busy) View.VISIBLE else View.GONE
        routeButtons.visibility = if (routeButtons.childCount > 0 && !busy) View.VISIBLE else View.GONE
        inputChoices.visibility = if (busy) View.GONE else View.VISIBLE
        stopButton.visibility = if (busy) View.VISIBLE else View.GONE
        startButton.isEnabled = visible && !busy && input.text?.isNotBlank() == true
        startButton.compoundDrawablesRelative[0]?.alpha = if (startButton.isEnabled) 255 else 96
        retryButton.isEnabled = visible && !busy
        input.isEnabled = !busy
        input.isCursorVisible = !busy
        input.hint = if (inputMode == InputMode.VOICE) "원하시는 작업을 말씀해 주세요!" else "원하시는 작업을 직접 작성해 주세요"
        val label = if (listening) "녹음 중지" else "음성 입력"
        retryButton.text = label; retryButton.contentDescription = label
        retryButton.setCompoundDrawablesRelative(Apl2MaterialSymbols.drawable(context,
            if (listening) Apl2MaterialSymbols.Stop else Apl2MaterialSymbols.Mic), null, null, null)
        waveform.listening = visible && !busy && listening
        if (renderedBusy != busy) {
            val wasRendered = renderedBusy != null
            renderedBusy = busy
            textAnimator?.cancel()
            val from = input.textSize / context.resources.displayMetrics.scaledDensity
            val to = if (busy) 16f else 20f
            input.setPadding(dp(4), if (busy) 0 else dp(16), dp(4), if (busy) 0 else dp(16))
            input.setTextColor(if (busy) AssistantTokens.HistoryArgb else AssistantTokens.TextArgb)
            input.paint.shader = null
            if (wasRendered && Apl2AssistantStyle.motions(context)) {
                textAnimator = ValueAnimator.ofFloat(from, to).apply {
                    duration = AssistantTokens.RespondMs
                    addUpdateListener { input.textSize = it.animatedValue as Float }
                    start()
                }
            } else input.textSize = to
        }
    }

    private fun stopPulse() { if (::waveform.isInitialized) waveform.listening = false }
    private fun setInputText(value: String) {
        internalTextChange = true
        try { input.setText(value); input.setSelection(input.length()) } finally { internalTextChange = false }
    }
    private fun dp(value: Int) = Apl2NativeStyle.dp(context, value)
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Apl2NativeStyle.Ink)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, action: () -> Unit) = Apl2NativeStyle.button(context, value) {
        // An explicit app/answer choice supersedes a pending speech result.
        cancelRecognition()
        action()
    }
    private fun rowParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(16) }
}
