package com.example.aplassistmvp0.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** 역할의 음성 인식 서비스 항목입니다. 자체 녹음 없이 검증한 외부 기기 엔진으로만 위임합니다. */
class AplRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var activeCallback: Callback? = null
    private val lockMonitor = object : Runnable {
        override fun run() {
            if (recognizer == null) return
            if (!canInteract()) {
                val callback = activeCallback
                release()
                runCatching { callback?.error(SpeechRecognizer.ERROR_CLIENT) }
            } else handler.postDelayed(this, 300L)
        }
    }

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        if (recognizer != null) { runCatching { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }; return }
        if (!canInteract()) { runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runCatching { callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }; return
        }
        val provider = SpeechProviderResolver.externalProvider(this)
        if (provider == null) { runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        val current = ++generation
        fun valid() = current == generation
        try {
            val speech = SpeechRecognizer.createSpeechRecognizer(this, provider)
            recognizer = speech
            activeCallback = callback
            handler.post(lockMonitor)
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (valid()) runCatching { callback.readyForSpeech(Bundle()) } }
                override fun onBeginningOfSpeech() { if (valid()) runCatching { callback.beginningOfSpeech() } }
                override fun onRmsChanged(rmsdB: Float) { if (valid()) runCatching { callback.rmsChanged(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (valid()) runCatching { callback.endOfSpeech() } }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!valid()) return
                    release()
                    runCatching { callback.error(error) }
                }
                override fun onResults(results: Bundle?) {
                    if (!valid()) return
                    val final = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.take(500)
                    release()
                    if (final.isNullOrBlank()) runCatching { callback.error(SpeechRecognizer.ERROR_NO_MATCH) }
                    else runCatching { callback.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(final))
                    }) }
                }
            })
            // 외부 Intent의 extras를 전달하지 않습니다. 한국어 한 회 인식에 필요한 옵션만 직접 생성합니다.
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: Exception) { release(); runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) } }
    }

    override fun onStopListening(callback: Callback) { runCatching { recognizer?.stopListening() } }
    override fun onCancel(callback: Callback) { release() }
    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        generation++
        handler.removeCallbacksAndMessages(null)
        activeCallback = null
        recognizer?.let { speech -> runCatching { speech.cancel() }; runCatching { speech.destroy() } }
        recognizer = null
    }
    private fun canInteract() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
}
