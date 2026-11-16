package dev.ccomet.chanchanhi04.study

import androidx.compose.runtime.Composable
import dev.ccomet.chanchanhi04.ui.JourneyProgress
import org.json.JSONObject

/** The survey starts at collection consent and includes the final thank-you screen. */
data class SurveyProgressInfo(val label:String,val current:Int,val total:Int){
    companion object{
        fun forScreen(phase:StudyPhase,index:Int,template:JSONObject,missionPages:Int=StudyNavigation.pages(StudyPhase.MISSION_PRE,template,true)):SurveyProgressInfo?{
            val counts=linkedMapOf(
                StudyPhase.INTRO to 1,StudyPhase.PROFILE to 4,
                StudyPhase.BASELINE to template.getJSONArray("baseline").length(),
                StudyPhase.MISSION_PRE to missionPages.coerceAtLeast(1),StudyPhase.TASK to 2,
                StudyPhase.POST to template.getJSONArray("post").length(),StudyPhase.COMPLETE to 1)
            val count=counts[phase]?:return null
            if(phase==StudyPhase.INTRO&&index<StudyNavigation.onboarding(template).length())return null
            val offset=counts.entries.takeWhile{it.key!=phase}.sumOf{it.value}
            val local=if(phase==StudyPhase.INTRO)0 else index.coerceIn(0,count-1)
            return SurveyProgressInfo("설문 조사",offset+local+1,counts.values.sum())
        }
    }
}
@Composable fun StudyProgress(info:SurveyProgressInfo){
    JourneyProgress(info.label,info.current,info.total)
}
