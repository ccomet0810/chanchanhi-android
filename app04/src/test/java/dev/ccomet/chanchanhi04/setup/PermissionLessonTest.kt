package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class PermissionLessonTest{
    @Test fun combinedLessonKeepsAllPipGesturesAndEveryAccessibilityStep(){
        val lesson=PermissionLesson.accessibility()
        assertEquals(listOf(0,1,2),lesson.steps.mapNotNull{it.pip})
        assertEquals(ScreenSetupTour.steps(false),lesson.steps.mapNotNull{it.screen})
        assertEquals(0,lesson.initialIndex)
        val settings=PermissionLesson.accessibility(startWithSettings=true)
        assertEquals(PermissionLessonStep.ACCESSIBILITY,settings.steps[settings.initialIndex])
        assertEquals(lesson.steps,settings.steps)
    }
    @Test fun everyBoundaryAndFinalReplayHasAReachableScene(){
        for(lesson in listOf(PermissionLesson.accessibility(),PermissionLesson.assistant())){
            lesson.steps.forEachIndexed{index,step->
                val start=lesson.startAt(index)
                assertEquals(index,lesson.indexAt(start))
                assertEquals(0f,lesson.progressAt(start),0f)
                assertEquals(index,lesson.indexAt(start+step.durationMs-1))
            }
            assertEquals(lesson.steps.lastIndex,lesson.indexAt(lesson.totalMs))
            assertEquals(1f,lesson.progressAt(lesson.totalMs),0f)
            assertEquals(0,lesson.indexAt(0))
        }
    }
    @Test fun assistantIllustrationShowsSearchAndTheRealSelectionGoal(){
        assertEquals(listOf(PermissionLessonStep.ASSISTANT_SEARCH,PermissionLessonStep.ASSISTANT_RESULT,
            PermissionLessonStep.ASSISTANT_DEFAULT,PermissionLessonStep.ASSISTANT_CHOOSE),PermissionLesson.assistant().steps)
        assertTrue(PermissionLesson.assistant().steps.all{it.pip==null&&it.screen==null})
    }
}
