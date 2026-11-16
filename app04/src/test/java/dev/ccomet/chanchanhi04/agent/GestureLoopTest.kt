package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureLoopTest {
    private val element=Element(1,"0/1","public:id/control","Button","공개 조작",NodeBounds(100,200,800,900),true,true,true,false,false,null,null,null,null)
    private class Device(var screen:Observation):DevicePort{
        override suspend fun observe()=screen
        override suspend fun capture()=error("Images not required")
        override fun installedApps()=emptyList<AppEntry>()
        override fun render(state:AgentState){}
        override fun speak(message:String){}
        override fun stopSpeech(){}
        override val imagesAllowed=false
    }
    private class Recorder:SessionRecorder{
        val results=mutableListOf<ToolResult>()
        override fun begin(sessionId:String,goal:String){}
        override fun event(kind:String,value:Any?){if(value is ToolResult)results+=value}
    }
    private fun device()=Device(Observation(1,"public.fixture",7,1,1,1000,1500,true,listOf(element)))
    @Test fun `double tap is not inferred from a single accessibility click`()=runTest{
        val d=device();val log=Recorder();val model=object:AgentModel{override suspend fun next(request:AgentRequest)=ToolCall("double",Tool.GUIDE,"공개 검증",1,1,action=Action.DOUBLE_TAP,instruction="두 번 눌러주세요.",expectedChange="화면 확대")}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("공개 시험");runCurrent()
        loop.onInteraction(UserInteraction(1,"public.fixture",7,"0/1","public:id/control","click"))
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertFalse(log.results.any{it.status=="action_observed"})
    }
    @Test fun `a drag without observed endpoints cannot reach guidance`()=runTest{
        val d=device();val log=Recorder();var calls=0;val model=object:AgentModel{override suspend fun next(request:AgentRequest)=if(++calls==1)ToolCall("drag",Tool.GUIDE,"공개 검증",1,1,action=Action.DRAG,instruction="옮겨주세요.",expectedChange="위치 변경")else ToolCall("question",Tool.ASK_USER,"목적지 확인",question="어디로 옮길까요?")}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("공개 시험");runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase);assertTrue(log.results.any{it.status=="tool_error"&&it.detail.contains("gesturePath")})
    }
    @Test fun `moving layout invalidates a waiting path without recording success`()=runTest{
        val d=device();val log=Recorder();val model=object:AgentModel{override suspend fun next(request:AgentRequest)=ToolCall("drag",Tool.GUIDE,"공개 검증",1,1,action=Action.DRAG,instruction="옮겨주세요.",expectedChange="위치 변경",gesturePath=listOf(200,300,750,600))}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("공개 시험");runCurrent();assertEquals(Phase.GUIDING,loop.state.value.phase)
        loop.onObservation(d.screen.copy(id=2,elements=listOf(element.copy(bounds=NodeBounds(100,300,800,1000)))))
        assertEquals(Phase.THINKING,loop.state.value.phase);assertNull(loop.state.value.target);assertFalse(log.results.any{it.status=="completed_with_evidence"})
    }
}
