package dev.ccomet.chanchanhi04.setup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SetupPhase { IDLE, RUNNING, PAUSED, COMPLETE }
data class SetupState(val phase:SetupPhase=SetupPhase.IDLE,val requirement:SetupRequirement?=null,
    val snapshot:SetupSnapshot=SetupSnapshot(false,false,false),val message:String="",val requirements:Set<SetupRequirement> = setOf(SetupRequirement.SCREEN,SetupRequirement.ASSISTANT))

/** Only capability transitions are fixed. The AI chooses actions from the actual settings screen. */
interface SetupPort {
    fun snapshot():SetupSnapshot
    fun interactive():Boolean
    fun inSettings():Boolean
    fun serviceReady():Boolean
    fun open(requirement:SetupRequirement):Boolean
    fun guide(requirement:SetupRequirement):Boolean
    fun needsGuidance(requirement:SetupRequirement)=true
    fun guidancePaused():Boolean
    fun pauseGuidance(message:String)
    fun stopGuidance()
    fun completed()
    fun rememberPending(pending:Boolean)
}

class SetupCoordinator(private val scope:CoroutineScope,private val port:SetupPort,pending:Boolean=false){
    private val mutable=MutableStateFlow(SetupState(if(pending)SetupPhase.PAUSED else SetupPhase.IDLE,
        message=if(pending)"설정을 이어서 완료해주세요."else""))
    val state=mutable.asStateFlow()
    private var job:Job?=null
    fun start(requirements:Set<SetupRequirement> = setOf(SetupRequirement.SCREEN,SetupRequirement.ASSISTANT)){
        if(mutable.value.phase==SetupPhase.RUNNING)return
        job?.cancel()
        port.stopGuidance()
        port.rememberPending(true)
        job=scope.launch{
            var bootstrapWait=0
            while(isActive){
                val snapshot=port.snapshot()
                val next=requirements.firstOrNull{!snapshot.granted(it)}
                if(next==null){
                    if(requirements==setOf(SetupRequirement.SCREEN)&&!port.serviceReady()){
                        mutable.value=SetupState(SetupPhase.RUNNING,SetupRequirement.SCREEN,snapshot,"화면 안내에 연결하고 있어요.",requirements)
                        if(!port.interactive()){pause("화면 잠금을 해제한 뒤 설정을 이어가주세요.");return@launch}
                        // Permission is already on. Wait for binding without reopening Settings.
                        if(++bootstrapWait>=60){pause("화면 안내에 연결되지 않았어요. 설정을 다시 확인해주세요.");return@launch}
                        delay(350);continue
                    }
                    mutable.value=SetupState(SetupPhase.COMPLETE,snapshot=snapshot,message="사용 준비를 마쳤어요.",requirements=requirements)
                    port.rememberPending(false);port.stopGuidance();port.completed();return@launch
                }
                mutable.value=SetupState(SetupPhase.RUNNING,next,snapshot,requirements=requirements)
                if(next==SetupRequirement.MICROPHONE){pause("앱 안에서 마이크 사용 요청을 확인해주세요.");return@launch}
                if(!port.interactive()){pause("화면 잠금을 해제한 뒤 설정을 이어가주세요.");return@launch}
                // Only first accessibility access needs a public OS landing page. Once readable,
                // the AI chooses navigation from whatever screen is actually visible.
                if(next==SetupRequirement.SCREEN&&!port.open(next)){pause("설정을 열지 못했어요. 다시 시도해주세요.");return@launch}
                // Let the system finish its transition and permission confirmation before observing.
                delay(900)
                var guided=false
                var serviceWait=0
                while(isActive){
                    val actual=port.snapshot()
                    mutable.value=mutable.value.copy(snapshot=actual)
                    if(!port.interactive()){pause("화면 잠금을 해제한 뒤 설정을 이어가주세요.");return@launch}
                    if(next==SetupRequirement.SCREEN&&actual.screen){
                        if(port.serviceReady()){port.stopGuidance();break}
                        if(++serviceWait>=60){pause("화면 안내에 연결되지 않았어요. 설정을 다시 확인해주세요.");return@launch}
                        delay(350);continue
                    }
                    if(next==SetupRequirement.SCREEN&&!port.inSettings()){pause("다른 화면으로 이동해 설정 안내를 멈췄어요.");return@launch}
                    if(actual.granted(next)){port.stopGuidance();break}
                    if(next!=SetupRequirement.SCREEN&&!actual.screen){pause("화면 안내가 꺼졌어요. 다시 켠 뒤 이어가주세요.");return@launch}
                    if(guided&&port.guidancePaused()){pause("설정 안내를 잠시 멈췄어요.");return@launch}
                    if(port.needsGuidance(next)){
                        if(!guided&&next.canUseAi(actual)&&port.serviceReady())guided=port.guide(next)
                        if(next!=SetupRequirement.SCREEN&&!guided&&++serviceWait>=60){pause("화면 안내에 연결되지 않았어요. 화면 안내를 다시 켠 뒤 이어가주세요.");return@launch}
                    }
                    delay(350)
                }
            }
        }
    }
    fun pause(message:String="설정 안내를 잠시 멈췄어요."){
        if(mutable.value.phase!=SetupPhase.RUNNING)return
        job?.cancel();job=null
        mutable.value=mutable.value.copy(phase=SetupPhase.PAUSED,message=message)
        port.pauseGuidance(message)
    }
    fun cancel(){
        job?.cancel();job=null;port.rememberPending(false);port.stopGuidance()
        mutable.value=SetupState(snapshot=port.snapshot())
    }
}

fun SetupSnapshot.nextRequirement()=SetupRequirement.entries.firstOrNull{!granted(it)}
