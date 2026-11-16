package dev.ccomet.chanchanhi04.ui
import org.junit.Assert.*
import org.junit.Test
class IntroPlaybackTest{
 @Test fun demonstrationPrecedesEveryUserActionIncludingNavigation(){
  for(step in listOf(IntroStep.FONT_SIZE,IntroStep.OPEN_SETTINGS,IntroStep.SETTINGS,IntroStep.DISPLAY)){
   assertTrue(step.durationMs>=3000)
   for(p in listOf(.1f,.3f,.5f)){val f=IntroFrame(step,p);assertTrue(f.showGestureGuide);assertEquals(0f,f.userActionProgress);assertFalse(f.showUserTouch)}
   val gap=IntroFrame(step,.6f);assertFalse(gap.showGestureGuide);assertEquals(0f,gap.userActionProgress)
   val action=IntroFrame(step,.78f);assertFalse(action.showGestureGuide);assertTrue(action.showUserTouch);assertTrue(action.userActionProgress>0)
  }
 }
 @Test fun stageSeekingUsesExactBoundariesWithoutBreakingCompletion(){
  for(step in IntroStep.entries){
   val middle=IntroTimeline.startOf(step)+step.durationMs/2
   assertEquals(IntroTimeline.startOf(IntroStep.entries[(step.ordinal-1).coerceAtLeast(0)]),IntroTimeline.previous(middle))
   val next=if(step==IntroStep.COMPLETE)IntroTimeline.durationMs else IntroTimeline.startOf(IntroStep.entries[step.ordinal+1])
   assertEquals(next,IntroTimeline.next(middle))
  }
  assertEquals(IntroFrame(IntroStep.COMPLETE,1f),IntroTimeline.at(Long.MAX_VALUE))
 }
 @Test fun phoneCornersAndBezelShareTheSameGeometryAtEveryPreviewSize(){
  for(width in listOf(120f,180f,280f,400f)){
   val shape=SimulationPhoneGeometry.forWidth(width)
   assertEquals(shape.outerRadius,shape.innerRadius+shape.bezel,.0001f)
   assertEquals(.065f,shape.outerRadius/width,.0001f);assertTrue(shape.innerRadius>0)
  }
 }
}
