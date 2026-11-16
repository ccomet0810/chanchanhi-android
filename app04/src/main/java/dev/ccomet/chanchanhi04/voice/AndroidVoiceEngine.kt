package dev.ccomet.chanchanhi04.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.aplassistmvp0.assistant.SpeechProviderResolver

/** Uses the installed system engine without launching the provider's activity. No audio is saved. */
internal class AndroidVoiceEngine(private val context:Context):VoiceEngine {
    private var recognizer:SpeechRecognizer?=null
    override fun start(events:VoiceEngine.Events) {
        val provider=SpeechProviderResolver.externalProvider(context)
        if(provider==null){events.error("휴대폰의 음성 인식 서비스를 확인해주세요.");return}
        val speech=SpeechRecognizer.createSpeechRecognizer(context,provider);recognizer=speech
        speech.setRecognitionListener(object:RecognitionListener {
            override fun onReadyForSpeech(params:Bundle?){events.ready()}
            override fun onBeginningOfSpeech()=Unit
            override fun onRmsChanged(rmsdB:Float){events.level(rmsdB)}
            override fun onBufferReceived(buffer:ByteArray?)=Unit
            override fun onEndOfSpeech(){events.ended()}
            override fun onPartialResults(partialResults:Bundle?){events.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())}
            override fun onResults(results:Bundle?){events.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())}
            override fun onError(error:Int){events.error(when(error){
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"마이크를 허용해주세요."
                SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"음성 인식 연결을 확인해주세요."
                SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"말씀을 듣지 못했어요."
                SpeechRecognizer.ERROR_AUDIO->"마이크를 확인하고 다시 말씀해주세요."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"음성 입력을 잠시 사용할 수 없어요."
                else->"음성을 확인하지 못했어요. 다시 말씀해주세요."
            })}
            override fun onEvent(eventType:Int,params:Bundle?)=Unit
        })
        speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
        })
    }
    override fun stop(){recognizer?.stopListening()}
    override fun cancel(){val previous=recognizer;recognizer=null;runCatching{previous?.cancel()};runCatching{previous?.destroy()}}
}

internal object VoiceEngines {
    private var testFactory:((Context)->VoiceEngine)?=null
    fun create(context:Context):VoiceEngine=testFactory?.invoke(context) ?: AndroidVoiceEngine(context)
    /** Instrumentation-only injection; no Intent, saved setting or external component selects this. */
    fun installForTest(factory:((Context)->VoiceEngine)?){check(dev.ccomet.chanchanhi04.BuildConfig.DEBUG);testFactory=factory}
}
