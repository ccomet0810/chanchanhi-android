package dev.ccomet.chanchanhi04.setup

import dev.ccomet.chanchanhi04.agent.*

data class CapabilityGuideContext(
    val goal:String,
    val appLabel:String,
    val appPackage:String,
    val verifyInvocation:Boolean,
)

/** Supplies task facts to the AI. It never selects a navigation action or a target node.
 * Only the runtime's OS/service callbacks may complete a capability task. */
class CapabilityGuideModel(private val delegate:AgentModel,private val context:()->CapabilityGuideContext?,
    private val snapshot:()->SetupSnapshot,private val report:(String)->Unit={}):AgentModel{
    override suspend fun next(request:AgentRequest):ToolCall{
        val active=context()?.takeIf{it.goal==request.goal}?:return delegate.next(request)
        report("ai_observed_screen")
        val result=delegate.next(request.copy(taskContext=CapabilityTaskContext.describe(active,snapshot())))
        if(result.tool==Tool.FINISH)throw ModelOutputFailure("capability_finish_requires_os_verification; continue from taskContext or ask_user; invocation requires actual service callback")
        return result
    }
}

object CapabilityTaskContext{
    fun describe(context:CapabilityGuideContext,actual:SetupSnapshot)=buildString{
        appendLine("설정 작업 목표: 현재 설치된 우리 앱을 기본 디지털 도우미로 선택한다.")
        appendLine("대상 앱의 실제 Android 라벨=${context.appLabel}; package=${context.appPackage}. 비슷한 이름이나 예전 앱을 같은 앱으로 간주하지 않는다. 후보의 정체가 모호하면 확인한다.")
        appendLine("제품이 OS에서 확인한 상태: 화면 안내=${actual.screen}; 마이크=${actual.microphone}; 우리 앱이 기본 디지털 도우미=${actual.assistant}.")
        appendLine("마이크는 앱 내부 native RECORD_AUDIO 요청으로 준비하는 별도 온보딩이다. 이 작업에서 마이크 설정이나 권한 화면으로 안내하지 않는다.")
        appendLine("경로 힌트: 설정에서 어시스턴트를 검색해 대상 앱으로 기본 도우미를 바꿀 수 있다. 이는 고정 순서가 아니다. 매번 실제 관측에서 다음 한 행동을 선택한다.")
        appendLine("설정 첫 화면으로 돌아갈 필요는 없다. 현재 하위 화면, 별도 검색 앱, 다른 검색 결과 명칭, 검색이 없는 화면, 중간 팝업에서도 관측으로 판단한다. 기종별 버튼명이나 패키지 규칙을 가정하지 않는다.")
        appendLine("이미 우리 앱이 기본 도우미면 다시 선택하거나 권한을 끄지 않는다. 다른 앱을 열거나 설정을 자동 변경하지 않고 사용자가 조작하게 안내한다.")
        appendLine("클릭 사건, 타이머, 애니메이션 종료, 사용자의 했어요만으로 성공을 단정하지 않는다. 새 관측과 OS 상태를 확인한다. 읽지 못한 화면이나 버튼은 지어내지 않고 필요한 사실을 묻거나 멈춘다.")
        appendLine("홈/뒤로 안내가 반복되면 같은 경로를 계속 요구하지 말고 원인을 확인하고 재계획한다. 목표를 완료했다고 finish로 선언하지 않는다. 완료는 앱이 OS 상태로 검증한다.")
        if(context.verifyInvocation)appendLine("추가 목표: 실제 우리 앱의 도우미 호출까지 확인한다. OS에서 우리 앱이 기본 도우미인 것을 확인한 뒤 휴대폰의 도우미 호출 동작을 안내한다. 옆 버튼 길게 누르기는 경로 힌트이며 실제 기기 동작은 확인한다. 실제 VoiceInteractionSession.onShow 콜백만 호출 완료 근거다. 사용자 답변이나 화면 표시로 대체하지 않는다.")
    }
    /** Unreadable means unavailable, never a synthetic Settings observation or guessed next tap. */
    fun unavailableMessage()="현재 화면을 읽을 수 없어 다음 행동을 확인하지 못했어요. 읽을 수 있는 화면으로 돌아온 뒤 이어가기를 눌러주세요. 설정 완료 여부는 휴대폰의 실제 상태로 확인할게요."
}
