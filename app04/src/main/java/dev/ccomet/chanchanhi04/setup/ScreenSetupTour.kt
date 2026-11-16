package dev.ccomet.chanchanhi04.setup

/** Illustration steps only. These never click settings or declare a permission granted. */
enum class ScreenSetupStep(val title:String,val instruction:String){
    PIP_PERMISSION("사진 속 사진(PIP)","권한 허용을 켜주세요"),
    ACCESSIBILITY("접근성","설치된 앱을 눌러주세요"),
    APPS("설치된 앱","찬찬히 화면 안내를 눌러주세요"),
    SERVICE("찬찬히 화면 안내","사용 안 함의 오른쪽 버튼을 눌러주세요"),
    CONFIRM("찬찬히 화면 안내","허용을 눌러주세요")
}

object ScreenSetupTour{
    const val StepMs=4000L
    const val PipStepMs=9000L
    const val ConfirmationAccepted=.88f
    fun switchOn(step:ScreenSetupStep,progress:Float)=step==ScreenSetupStep.CONFIRM&&progress>=ConfirmationAccepted||step==ScreenSetupStep.PIP_PERMISSION&&progress>=.8f
    fun steps(direct:Boolean)=if(direct)listOf(ScreenSetupStep.SERVICE,ScreenSetupStep.CONFIRM)else listOf(ScreenSetupStep.ACCESSIBILITY,ScreenSetupStep.APPS,ScreenSetupStep.SERVICE,ScreenSetupStep.CONFIRM)
    // The protected confirmation may block PiP menus: teach switch + Allow together beforehand.
    fun manualSteps(direct:Boolean)=steps(direct).filter{it!=ScreenSetupStep.SERVICE}
    fun move(index:Int,delta:Int,size:Int):Int{
        require(size>0)
        require(delta in -1..1)
        return (index+delta).coerceIn(0,size-1)
    }
    fun stepIndex(elapsed:Long,size:Int):Int{
        require(size>0)
        return (elapsed.coerceAtLeast(0)/StepMs).coerceAtMost((size-1).toLong()).toInt()
    }
    fun progress(elapsed:Long,total:Long)=if(elapsed>=total)1f else(elapsed.coerceAtLeast(0)%StepMs).toFloat()/StepMs
    fun caption(step:ScreenSetupStep)=when(step){
        ScreenSetupStep.PIP_PERMISSION->"권한 허용을 켜주세요"
        ScreenSetupStep.ACCESSIBILITY,ScreenSetupStep.APPS->"설정에서 찬찬히 화면 안내를 찾아요"
        ScreenSetupStep.SERVICE->step.instruction
        ScreenSetupStep.CONFIRM->"허용을 눌러주세요"
    }
    fun compactInstruction(step:ScreenSetupStep)=when(step){
        ScreenSetupStep.PIP_PERMISSION->"작은 창 허용하기"
        ScreenSetupStep.ACCESSIBILITY->"설치된 앱 누르기"
        ScreenSetupStep.APPS->"화면 안내 누르기"
        ScreenSetupStep.SERVICE->"화면 안내 켜기"
        ScreenSetupStep.CONFIRM->"허용 누르기"
    }
}
