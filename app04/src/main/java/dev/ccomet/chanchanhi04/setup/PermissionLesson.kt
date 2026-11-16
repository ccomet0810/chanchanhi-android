package dev.ccomet.chanchanhi04.setup

/** Illustration timelines only. Runtime navigation continues to use observed screens and AI. */
enum class PermissionLessonStep(val caption:String,val durationMs:Long=4500L,val screen:ScreenSetupStep?=null,val pip:Int?=null){
    PIP_ENLARGE("작은 창을 두 번 누르면 커져요",pip=0),
    PIP_RESIZE("두 손가락으로 크기를 바꿔요",pip=1),
    PIP_MOVE("버튼을 가리면 끌어서 옮겨요",pip=2),
    ACCESSIBILITY("설치된 앱을 눌러주세요.",screen=ScreenSetupStep.ACCESSIBILITY),
    SERVICE_LIST("찬찬히 화면 안내를 눌러주세요.",screen=ScreenSetupStep.APPS),
    SERVICE_SWITCH("사용 안 함의 오른쪽 버튼을 눌러주세요.",screen=ScreenSetupStep.SERVICE),
    SERVICE_ALLOW("허용을 눌러주세요.",screen=ScreenSetupStep.CONFIRM),
    ASSISTANT_SEARCH("설정에서 어시스턴트를 검색해주세요."),
    ASSISTANT_RESULT("디지털 어시스턴트 앱을 눌러주세요."),
    ASSISTANT_DEFAULT("기본 디지털 어시스턴트 앱을 눌러주세요."),
    ASSISTANT_CHOOSE("찬찬히를 눌러주세요.")
}

data class PermissionLesson(val steps:List<PermissionLessonStep>,val initialIndex:Int=0){
    init{require(steps.isNotEmpty()&&initialIndex in steps.indices)}
    val totalMs=steps.sumOf{it.durationMs}
    fun startAt(index:Int)=steps.take(index.coerceIn(0,steps.lastIndex)).sumOf{it.durationMs}
    fun indexAt(elapsed:Long):Int{
        var end=0L
        steps.forEachIndexed{index,step->end+=step.durationMs;if(elapsed<end)return index}
        return steps.lastIndex
    }
    fun progressAt(elapsed:Long):Float{
        val index=indexAt(elapsed)
        return ((elapsed-startAt(index)).toFloat()/steps[index].durationMs).coerceIn(0f,1f)
    }
    companion object{
        fun accessibility(startWithSettings:Boolean=false)=PermissionLesson(listOf(
            PermissionLessonStep.PIP_ENLARGE,PermissionLessonStep.PIP_RESIZE,PermissionLessonStep.PIP_MOVE,
            PermissionLessonStep.ACCESSIBILITY,PermissionLessonStep.SERVICE_LIST,
            PermissionLessonStep.SERVICE_SWITCH,PermissionLessonStep.SERVICE_ALLOW),if(startWithSettings)3 else 0)
        fun assistant()=PermissionLesson(listOf(PermissionLessonStep.ASSISTANT_SEARCH,PermissionLessonStep.ASSISTANT_RESULT,
            PermissionLessonStep.ASSISTANT_DEFAULT,PermissionLessonStep.ASSISTANT_CHOOSE))
    }
}
