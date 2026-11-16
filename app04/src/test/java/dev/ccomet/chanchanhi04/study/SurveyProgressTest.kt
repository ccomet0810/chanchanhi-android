package dev.ccomet.chanchanhi04.study
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SurveyProgressTest {
    private val template=JSONObject(javaClass.getResource("/pilot-5.json")!!.readText())
    @Test fun participationStartsAtConsentInsteadOfIntroductionOrPermissions(){
        val consent=StudyNavigation.onboarding(template).length()
        assertNull(SurveyProgressInfo.forScreen(StudyPhase.INTRO,0,template))
        assertNull(SurveyProgressInfo.forScreen(StudyPhase.INTRO,1,template))
        assertEquals(1,SurveyProgressInfo.forScreen(StudyPhase.INTRO,consent,template)!!.current)
        assertEquals("설문 조사",SurveyProgressInfo.forScreen(StudyPhase.INTRO,consent,template)!!.label)
        val done=SurveyProgressInfo.forScreen(StudyPhase.COMPLETE,0,template)!!
        assertEquals(done.total,done.current)
        assertNull(SurveyProgressInfo.forScreen(StudyPhase.WITHDRAWN,0,template))
    }
    @Test fun allVisiblePagesAdvanceOneContinuousProcessAndPreviousCanReverseIt(){
        val phases=listOf(StudyPhase.PROFILE,StudyPhase.BASELINE,StudyPhase.MISSION_PRE,StudyPhase.TASK,StudyPhase.POST,StudyPhase.COMPLETE)
        val values=mutableListOf(SurveyProgressInfo.forScreen(StudyPhase.INTRO,StudyNavigation.onboarding(template).length(),template)!!)
        for(phase in phases)repeat(StudyNavigation.pages(phase,template,true)){index->values+=SurveyProgressInfo.forScreen(phase,index,template)!!}
        assertEquals((1..values.size).toList(),values.map{it.current})
        assertEquals(setOf(values.size),values.map{it.total}.toSet())
        assertEquals(2,SurveyProgressInfo.forScreen(StudyPhase.PROFILE,0,template)!!.current)
    }
    @Test fun customMissionUsesItsActualPageCount(){
        val normal=SurveyProgressInfo.forScreen(StudyPhase.POST,0,template,missionPages=3)!!
        val custom=SurveyProgressInfo.forScreen(StudyPhase.POST,0,template,missionPages=1)!!
        assertEquals(normal.total-2,custom.total);assertEquals(normal.current-2,custom.current)
    }
}
