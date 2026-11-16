package dev.ccomet.chanchanhi04.voice

import dev.ccomet.chanchanhi04.agent.AgentState
import dev.ccomet.chanchanhi04.agent.Phase

/** A delayed transcript can only answer the question/session that opened the microphone. */
data class VoiceTarget(val sessionId:String,val phase:Phase,val questionId:String?) {
    val answering get()=questionId!=null
    fun accepts(state:AgentState)=state.sessionId==sessionId && state.phase==phase &&
        (if(answering)state.phase==Phase.QUESTION && state.pending?.callId==questionId else state.phase!=Phase.QUESTION)
    companion object {
        fun from(state:AgentState)=VoiceTarget(state.sessionId,state.phase,if(state.phase==Phase.QUESTION)state.pending?.callId else null)
    }
}
