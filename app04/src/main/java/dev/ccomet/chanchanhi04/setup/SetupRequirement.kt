package dev.ccomet.chanchanhi04.setup

/** Product capabilities, not app-specific button sequences. Only these goals can bypass the study gate. */
enum class SetupRequirement(val title:String,val instruction:String){
    SCREEN("화면 안내","접근성 설정에서 설치된 앱을 누른 뒤, 찬찬히 화면 안내를 켜주세요."),
    MICROPHONE("마이크","앱에서 표시하는 마이크 사용 요청을 확인해주세요."),
    ASSISTANT("버튼으로 부르기","기본 디지털 도우미 앱으로 찬찬히를 선택해주세요.");
    fun canUseAi(snapshot:SetupSnapshot)=this==ASSISTANT&&snapshot.screen&&!snapshot.granted(this)
    fun goal(appName:String)=when(this){
        SCREEN->"$appName 접근성 화면 안내를 켜도록 안내해주세요."
        MICROPHONE->"$appName 앱 안에서 마이크 사용 요청을 확인해주세요."
        ASSISTANT->"설정에서 어시스턴트를 검색해 실제 설치된 $appName 앱을 기본 디지털 도우미로 선택하도록 안내해주세요. 검색은 경로 힌트이며 현재 화면에서 가능한 다음 한 행동은 AI가 실제 관측으로 판단합니다. 설정 첫 화면에서 시작하고, 이후 상세 항목은 사용자가 직접 누르도록 안내합니다. 이미 설정됐다면 OS 상태를 확인하고 다시 변경하지 마세요."
    }
}
data class SetupSnapshot(val screen:Boolean,val microphone:Boolean,val assistant:Boolean){
    val complete get()=screen&&microphone&&assistant
    fun granted(requirement:SetupRequirement)=when(requirement){SetupRequirement.SCREEN->screen;SetupRequirement.MICROPHONE->microphone;SetupRequirement.ASSISTANT->assistant}
}
