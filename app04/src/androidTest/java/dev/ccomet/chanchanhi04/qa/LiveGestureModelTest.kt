package dev.ccomet.chanchanhi04.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveGestureModelTest {
    @Test fun approvedSubscriptionReturnsExplicitDoubleTapWithNewMotionContract()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveGesture")=="true")
        val i=InstrumentationRegistry.getInstrumentation();i.runOnMainSync{AppRuntime.initialize(i.targetContext)}
        val node=Element(1,"0/1","public:id/double","View","두 번 누르기 전용 영역",NodeBounds(100,200,700,600),true,true,true,false,false,null,null,null,"공개 시험 구현에서 double tap 지원을 확인했음. 한 번 누르기는 효과 없음. 아직 확대되지 않았음.")
        val screen=Observation(1,"public.fixture",7,1,1,1000,1500,true,listOf(node))
        val began=android.os.SystemClock.elapsedRealtime()
        val result=AppRuntime.model.next(AgentRequest("공개 시험의 두 번 누르기 전용 영역을 두 번 눌러 확대하도록 안내해주세요. 이 시험 영역의 double tap 지원은 이미 검증되어 있습니다.",emptyList(),screen,emptyList(),0,null))
        assertEquals(Tool.GUIDE,result.tool);assertEquals(Action.DOUBLE_TAP,result.action);assertEquals(1,result.nodeId);assertNull(result.gesturePath)
        i.sendStatus(0,android.os.Bundle().apply{putString("stream","\nLIVE_MOTION=double_tap approved_subscription=true elapsedMs=${android.os.SystemClock.elapsedRealtime()-began}\n")})
    }
}
