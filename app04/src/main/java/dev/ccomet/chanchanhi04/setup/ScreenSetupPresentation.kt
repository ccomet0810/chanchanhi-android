package dev.ccomet.chanchanhi04.setup

/** Permission status changes the existing page's footer, never its navigation destination. */
data class ScreenSetupPresentation(val action:String,val message:String="",val waiting:Boolean=false,val ready:Boolean=false)
const val ScreenConnectionWaitMs=5000L

fun screenSetupPresentation(permission:Boolean,connected:Boolean,attempted:Boolean,pipAllowed:Boolean,connectionPaused:Boolean=false,connectionTimedOut:Boolean=false)=when{
    permission&&connected->ScreenSetupPresentation("다음",ready=true)
    permission&&connectionTimedOut->ScreenSetupPresentation("설정에서 확인하기","화면 안내가 켜져 있지만 연결되지 않았어요.")
    permission&&connectionPaused->ScreenSetupPresentation("설정 이어가기","화면 안내 연결을 다시 확인해주세요.")
    permission->ScreenSetupPresentation("연결 확인 중","화면 안내에 연결하고 있어요.",waiting=true)
    attempted->ScreenSetupPresentation("설정 이어가기","화면 안내가 아직 꺼져 있어요.")
    else->ScreenSetupPresentation(if(pipAllowed)"설정 시작하기"else"설정 이어가기")
}
