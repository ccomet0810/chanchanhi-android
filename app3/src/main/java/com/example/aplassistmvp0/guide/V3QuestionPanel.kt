package com.example.aplassistmvp0.guide

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.aplassistmvp0.assistant.SpeechProviderResolver
import com.example.aplassistmvp0.v3.PlanQuestion

/** Only answers to a current planner question. It is not a free-form chat surface. */
internal class V3QuestionPanel(private val context:AccessibilityService,
    private val silence:()->Unit,private val stop:()->Unit) {
    private var dialog:AlertDialog?=null
    private var key:String?=null
    private var recognizer:SpeechRecognizer?=null
    private var generation=0L
    private var observationPending=false
    private var observationStatus:TextView?=null
    private val handler=Handler(Looper.getMainLooper())
    fun show(question:PlanQuestion,answer:(String,String)->Boolean) {
        if(dialog?.isShowing==true && key==question.key)return
        hide();key=question.key
        val column=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(32,16,32,8)}
        val input=EditText(context).apply{hint="답변";setSingleLine(false);minLines=1;maxLines=4;textSize=20f}
        val status=TextView(context).apply{textSize=16f}
        observationStatus=status
        column.addView(input);column.addView(status)
        val panel=AlertDialog.Builder(context).setTitle(question.message).setView(column)
            .setPositiveButton("답변 보내기",null).setNeutralButton("말로 답하기",null)
            .setNegativeButton("안내 끝내기"){_,_->stop()}.setCancelable(false).create()
        panel.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog=panel
        panel.setOnDismissListener{if(dialog===panel){dialog=null;key=null;cancelVoice()}}
        panel.show()
        panel.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun submit(){
            if(observationPending || dialog!==panel || key!=question.key || !panel.isShowing)return
            val value=input.text.toString().trim()
            if(value.isEmpty()){status.text="답변을 입력하거나 말씀해주세요.";return}
            if(value.length>500){status.text="답변을 조금 줄여주세요.";return}
            cancelVoice()
            if(answer(question.key,value))hide() else {status.text="현재 질문을 다시 확인해주세요."}
        }
        panel.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{submit()}
        panel.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if(observationPending)return@setOnClickListener
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
                status.text="마이크 권한이 필요해요. 글로 답할 수도 있어요.";return@setOnClickListener
            }
            val provider=SpeechProviderResolver.externalProvider(context)
            if(provider==null){status.text="음성 입력을 사용할 수 없어요. 글로 답해주세요.";return@setOnClickListener}
            silence();cancelVoice();val ticket=++generation
            fun valid()=!observationPending && generation==ticket && dialog===panel && key==question.key && panel.isShowing
            try{
                val speech=SpeechRecognizer.createSpeechRecognizer(context,provider);recognizer=speech
                speech.setRecognitionListener(object:RecognitionListener {
                    override fun onReadyForSpeech(params:Bundle?){if(valid())status.text="듣고 있어요."}
                    override fun onBeginningOfSpeech()=Unit
                    override fun onRmsChanged(rmsdB:Float)=Unit
                    override fun onBufferReceived(buffer:ByteArray?)=Unit
                    override fun onEndOfSpeech(){if(valid())status.text="답변을 확인하고 있어요."}
                    override fun onError(error:Int){if(valid()){cancelVoice();status.text="다시 말씀하거나 글로 답해주세요."}}
                    override fun onResults(results:Bundle?){
                        if(!valid())return
                        val value=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        input.setText(value);submit()
                    }
                    override fun onPartialResults(partialResults:Bundle?)=Unit
                    override fun onEvent(eventType:Int,params:Bundle?)=Unit
                })
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR"))
                handler.postDelayed({if(valid()){cancelVoice();status.text="다시 말씀하거나 글로 답해주세요."}},30_000)
            }catch(_:Exception){cancelVoice();status.text="음성 입력을 사용할 수 없어요. 글로 답해주세요."}
        }
    }
    val isShowing:Boolean get()=dialog?.isShowing==true
    fun setObservationPending(value:Boolean){
        if(observationPending==value)return
        observationPending=value
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=!value
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled=!value
        observationStatus?.text=if(value)"화면을 확인하고 있어요."else ""
        if(value)cancelVoice()
    }
    fun hide(){val previous=dialog;dialog=null;key=null;observationPending=false;observationStatus=null;cancelVoice();runCatching{previous?.dismiss()}}
    private fun cancelVoice(){generation++;recognizer?.let{runCatching{it.cancel()};runCatching{it.destroy()}};recognizer=null}
}
