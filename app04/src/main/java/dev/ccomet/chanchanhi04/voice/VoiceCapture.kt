package dev.ccomet.chanchanhi04.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoicePhase { STARTING, LISTENING, PROCESSING, REVIEW, ERROR, TEXT, CLOSED }
data class VoiceState(val phase:VoicePhase=VoicePhase.STARTING,val text:String="",val message:String="",val levels:List<Float> = List(22){0f})
interface VoiceEngine {
    interface Events {
        fun ready();fun level(rms:Float);fun partial(text:String);fun ended();fun result(text:String);fun error(message:String)
    }
    fun start(events:Events)
    fun stop()
    fun cancel()
}

/** Recognition updates a draft. Only explicit confirmation consumes it; editing invalidates speech callbacks. */
class VoiceCapture(private val factory:()->VoiceEngine,private val now:()->Long) {
    private val mutable=MutableStateFlow(VoiceState())
    val state=mutable.asStateFlow()
    private var generation=0L
    private var engine:VoiceEngine?=null
    private var deadline=Long.MAX_VALUE
    private fun active()=state.value.phase in setOf(VoicePhase.STARTING,VoicePhase.LISTENING,VoicePhase.PROCESSING)
    private fun release(){generation++;val previous=engine;engine=null;deadline=Long.MAX_VALUE;runCatching{previous?.cancel()}}
    fun start() {
        val draft=state.value.text
        release();mutable.value=VoiceState(text=draft);deadline=now()+12000
        val token=generation
        fun valid()=token==generation && active()
        try {
            val next=factory();engine=next
            next.start(object:VoiceEngine.Events {
                override fun ready(){if(valid() && state.value.phase==VoicePhase.STARTING){deadline=now()+60000;mutable.value=state.value.copy(phase=VoicePhase.LISTENING)}}
                override fun level(rms:Float){if(valid() && state.value.phase==VoicePhase.LISTENING && rms.isFinite()){
                    mutable.value=state.value.copy(levels=state.value.levels.drop(1)+((rms+2f)/12f).coerceIn(0f,1f))
                }}
                override fun partial(text:String){if(valid() && text.isNotBlank())mutable.value=state.value.copy(text=text.take(2000))}
                override fun ended(){if(valid()){deadline=now()+12000;mutable.value=state.value.copy(phase=VoicePhase.PROCESSING)}}
                override fun result(text:String){if(!valid())return
                    if(text.isBlank()){fail("말씀을 듣지 못했어요.");return}
                    val answer=text.trim().take(2000);release();mutable.value=state.value.copy(phase=VoicePhase.REVIEW,text=answer)
                }
                override fun error(message:String){if(valid())fail(message)}
            })
        }catch(_:Exception){fail("음성 입력을 시작하지 못했어요.")}
    }
    fun stop() {
        if(state.value.phase !in setOf(VoicePhase.STARTING,VoicePhase.LISTENING))return
        mutable.value=state.value.copy(phase=VoicePhase.PROCESSING);deadline=now()+12000
        try{engine?.stop()}catch(_:Exception){fail("말씀을 확인하지 못했어요.")}
    }
    fun poll(){if(!active() || now()<deadline)return
        if(state.value.phase==VoicePhase.LISTENING)stop()else fail("음성 입력 응답이 늦어요. 다시 말씀해주세요.")
    }
    fun fail(message:String){release();mutable.value=state.value.copy(phase=VoicePhase.ERROR,message=message,levels=List(22){0f})}
    fun textMode(){release();mutable.value=state.value.copy(phase=VoicePhase.TEXT)}
    fun edit(text:String){
        if(state.value.phase==VoicePhase.CLOSED)return
        if(state.value.phase!=VoicePhase.TEXT)textMode()
        mutable.value=state.value.copy(text=text.take(2000))
    }
    fun confirm():String? {
        if(state.value.phase==VoicePhase.CLOSED || state.value.text.isBlank())return null
        val value=state.value.text.trim();release();mutable.value=state.value.copy(phase=VoicePhase.CLOSED,text=value)
        return value
    }
    fun cancel(){release();mutable.value=state.value.copy(phase=VoicePhase.CLOSED)}
}
