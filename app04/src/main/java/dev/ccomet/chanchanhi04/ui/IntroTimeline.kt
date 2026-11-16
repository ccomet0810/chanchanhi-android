package dev.ccomet.chanchanhi04.ui

import dev.ccomet.chanchanhi04.voice.VoicePhase

/** A scripted onboarding illustration, independent of speech, AI, settings and the agent loop. */
enum class IntroStep(val durationMs:Long,val caption:String,val navigationStep:Boolean=false){
    INVOKE(3000,"옆 버튼을 길게 눌러 찬찬히를 불러요"),
    LISTENING(6000,"말씀을 듣고 있어요"),REVIEW(3200,"내용을 확인하고 전송해요"),
    THINKING(1800,"화면을 살펴보고 있어요"),OPEN_SETTINGS(3600,"설정 앱을 여는 방법을 알려줘요",true),
    SETTINGS(3600,"디스플레이 항목을 눌러요",true),DISPLAY(3600,"글자 크기와 스타일을 눌러요",true),
    FONT_SIZE(8000,"오른쪽으로 밀어 글자를 키워요"),COMPLETE(2400,"글자가 커졌어요")
}
enum class IntroPage{HOME,SETTINGS,DISPLAY,FONT_SIZE}

/** The footer explains a whole context; target instructions stay inside the illustrated phone. */
enum class IntroSection(val caption:String){
    REQUEST("찬찬히를 부르고\n하고 싶은 일을 말해요"),
    GUIDANCE("찬찬히 안내를 따라\n필요한 설정을 찾아가요"),
    ADJUSTMENT("손동작을 따라\n글자 크기를 조절해요");
    companion object{
        fun forStep(step:IntroStep)=when(step){
            IntroStep.INVOKE,IntroStep.LISTENING,IntroStep.REVIEW->REQUEST
            IntroStep.THINKING,IntroStep.OPEN_SETTINGS,IntroStep.SETTINGS,IntroStep.DISPLAY->GUIDANCE
            IntroStep.FONT_SIZE,IntroStep.COMPLETE->ADJUSTMENT
        }
    }
}

data class IntroFrame(val step:IntroStep,val progress:Float){
    val page get()=when(step){
        IntroStep.SETTINGS->IntroPage.SETTINGS
        IntroStep.DISPLAY->IntroPage.DISPLAY
        IntroStep.FONT_SIZE,IntroStep.COMPLETE->IntroPage.FONT_SIZE
        else->IntroPage.HOME
    }
    // Submission closes the voice sheet immediately, before the next timeline scene starts.
    val overlayStep get()=if(step==IntroStep.REVIEW&&progress>=.78f)IntroStep.THINKING else step
    val voiceVisible get()=step in setOf(IntroStep.INVOKE,IntroStep.LISTENING,IntroStep.REVIEW)&&overlayStep!=IntroStep.THINKING
    val revealProgress get()=when{
        step==IntroStep.INVOKE->AssistantMotion.reveal((progress*step.durationMs).toLong()-step.durationMs+AssistantMotion.EnterMs)
        voiceVisible->1f
        else->0f
    }
    val listeningMs get()=if(step==IntroStep.LISTENING)(progress*step.durationMs).toLong()else IntroSpeechTiming.SpeechEndMs
    val voicePhase get()=when(step){
        IntroStep.INVOKE->VoicePhase.STARTING
        IntroStep.LISTENING->if(listeningMs<IntroSpeechTiming.ProcessingStartMs)VoicePhase.LISTENING else VoicePhase.PROCESSING
        else->VoicePhase.REVIEW
    }
    val transcript get()=when(step){
        IntroStep.INVOKE->""
        IntroStep.LISTENING->IntroTimeline.request.take((IntroTimeline.request.length*IntroSpeechTiming.textProgress(listeningMs)).toInt())
        else->IntroTimeline.request
    }
    val waveformGain get()=when(step){
        IntroStep.LISTENING->AssistantMotion.attackGain(listeningMs-IntroSpeechTiming.ReadyMs)*AssistantMotion.quietGain(listeningMs-IntroSpeechTiming.SpeechEndMs)
        else->0f
    }
    val waveformTime get()=if(step==IntroStep.LISTENING)(listeningMs-IntroSpeechTiming.ReadyMs).coerceIn(0,IntroSpeechTiming.SpeechEndMs-IntroSpeechTiming.ReadyMs)/1000f else (IntroSpeechTiming.SpeechEndMs-IntroSpeechTiming.ReadyMs)/1000f
    val waveformLevels get()=if(step==IntroStep.LISTENING)IntroSpeechEnvelope.history((listeningMs-IntroSpeechTiming.ReadyMs).coerceAtMost(IntroSpeechTiming.SpeechEndMs-IntroSpeechTiming.ReadyMs))else List(22){0f}
    val dockProgress get()=when(step){IntroStep.FONT_SIZE->AssistantMotion.dock((progress*step.durationMs).toLong());IntroStep.COMPLETE->1f;else->0f}
    val instruction get()=when(overlayStep){
        IntroStep.THINKING->"화면을 살펴보고 있어요."
        IntroStep.OPEN_SETTINGS->"설정을 눌러주세요."
        IntroStep.SETTINGS->"디스플레이를 눌러주세요."
        IntroStep.DISPLAY->"글자 크기와 스타일을 눌러주세요."
        IntroStep.FONT_SIZE->"오른쪽으로 밀어주세요."
        IntroStep.COMPLETE->"글자가 커졌어요."
        else->""
    }
    // The demonstration never moves the slider; a separate, later user gesture does.
    val userActionProgress get()=((progress-.64f)/.28f).coerceIn(0f,1f)
    val contactProgress get()=userActionProgress
    val showGestureGuide get()=step==IntroStep.FONT_SIZE&&progress<.55f||step.navigationStep&&progress<.55f
    val showUserTouch get()=if(step==IntroStep.REVIEW)progress in .64f..<.78f else(step==IntroStep.FONT_SIZE||step.navigationStep)&&progress in .64f.. .92f
    val section get()=IntroSection.forStep(step)
    val caption get()=section.caption
}
object IntroSpeechTiming{
    const val ReadyMs=1200L
    const val SpeechEndMs=4800L
    const val ProcessingStartMs=5400L
    fun textProgress(ms:Long)=((ms-ReadyMs).toFloat()/(SpeechEndMs-ReadyMs)).coerceIn(0f,1f)
}
object IntroTimeline{
    const val request="글자가 너무 작은데, 글자 크기를 키울 수 있나요?"
    val durations=IntroStep.entries.map{it.durationMs}
    val durationMs=durations.sum()
    fun previous(elapsed:Long)=SceneNavigation.previous(elapsed,durations)
    fun next(elapsed:Long)=SceneNavigation.next(elapsed,durations)
    fun startOf(step:IntroStep)=IntroStep.entries.take(step.ordinal).sumOf{it.durationMs}
    fun at(elapsedMs:Long):IntroFrame{
        var rest=elapsedMs.coerceIn(0,durationMs)
        for(step in IntroStep.entries){if(rest<step.durationMs)return IntroFrame(step,rest.toFloat()/step.durationMs);rest-=step.durationMs}
        return IntroFrame(IntroStep.COMPLETE,1f)
    }
}

/** Scene boundaries, shared by introductory and mission previews. */
object SceneNavigation{
    fun index(elapsed:Long,durations:List<Long>):Int{
        require(durations.isNotEmpty()&&durations.all{it>0})
        var rest=elapsed.coerceAtLeast(0)
        durations.forEachIndexed{i,duration->if(rest<duration)return i;rest-=duration}
        return durations.lastIndex
    }
    fun previous(elapsed:Long,durations:List<Long>):Long=durations.take((index(elapsed,durations)-1).coerceAtLeast(0)).sum()
    fun next(elapsed:Long,durations:List<Long>):Long=if(index(elapsed,durations)==durations.lastIndex)durations.sum()else durations.take(index(elapsed,durations)+1).sum()
}
