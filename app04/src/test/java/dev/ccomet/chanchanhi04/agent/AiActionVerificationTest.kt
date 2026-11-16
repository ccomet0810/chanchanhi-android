package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiActionVerificationTest{
    private val node=Element(1,"0/1","public:id/control","Button","항목",NodeBounds(10,100,200,180),true,true,true,false,false,null,null,null,null)
    private fun screen()=Observation(1,"public.app",4,1,1,1080,1920,true,listOf(node))
    private class Device(var current:Observation):DevicePort{
        val spoken=mutableListOf<String>()
        override suspend fun observe()=current
        override suspend fun capture()=ScreenImage(current,"AA==",540,960)
        override fun installedApps()=emptyList<AppEntry>()
        override fun render(state:AgentState)=Unit
        override fun speak(message:String){spoken+=message}
        override fun stopSpeech()=Unit
        override val imagesAllowed=false
    }
    private class Recorder:SessionRecorder{
        val results=mutableListOf<ToolResult>()
        override fun begin(sessionId:String,goal:String)=Unit
        override fun event(kind:String,value:Any?){if(kind=="tool_result")results+=value as ToolResult}
    }
    private fun guide(r:AgentRequest,index:Int,action:Action=Action.TAP)=ToolCall("call$index",Tool.GUIDE,"AI 실제 선택",r.screen.id,
        if(action==Action.HOME||action==Action.BACK)null else 1,action=action,instruction="관측한 행동을 해주세요.",expectedChange="결과를 새 관측으로 확인")
    @Test fun anUnrelatedBannerDoesNotConsumeTheGuideWithoutAnAction()=runTest{
        val d=Device(screen());var count=0
        val model=object:AgentModel{override suspend fun next(request:AgentRequest)=guide(request,++count)}
        val loop=AgentLoop(backgroundScope,model,d,Recorder());loop.start("공개 반례");runCurrent()
        d.current=d.current.copy(id=2,elements=listOf(node,node.copy(id=2,path="0/2",resource="public:id/banner",label="새 배너")))
        loop.onObservation(d.current);advanceTimeBy(30000);runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,count);assertEquals(1,d.spoken.size)
    }
    @Test fun aClickAndThirtySecondsWithoutAnObservedChangeDoNotTriggerNewSpeechOrModelWork()=runTest{
        val d=Device(screen());val log=Recorder();val requests=mutableListOf<AgentRequest>()
        val model=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{requests+=request;return guide(request,requests.size)}}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("공개 반례");runCurrent()
        loop.onInteraction(UserInteraction(1,"public.app",4,"0/1","public:id/control","click"))
        advanceTimeBy(30000);runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,requests.size);assertEquals(1,d.spoken.size)
        assertTrue(log.results.isEmpty());assertEquals("call1",loop.state.value.pending!!.callId)
        d.current=d.current.copy(id=2,elements=listOf(node.copy(checked=1)))
        loop.onObservation(d.current);advanceTimeBy(500);runCurrent()
        assertEquals(2,requests.size);assertEquals("content_changed",requests.last().history.single().result.status)
        assertFalse(log.results.any{it.status=="completed_with_evidence"})
    }
    @Test fun inputRequiresUserCompletionEvenWhenContentChangesAndAnswerIsItsOwnEvidence()=runTest{
        val d=Device(screen().copy(elements=listOf(node.copy(editable=true,clickable=false,hasInput=false))))
        val seen=mutableListOf<AgentRequest>();val log=Recorder()
        val model=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{
            seen+=request
            return if(seen.size==1)guide(request,1,Action.INPUT)else ToolCall("q${seen.size}",Tool.ASK_USER,"AI 확인",question="호출 결과는 어떤가요?")
        }}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("입력 확인");runCurrent()
        d.current=d.current.copy(id=2,elements=listOf(d.current.elements.single().copy(hasInput=true)))
        loop.onObservation(d.current);advanceTimeBy(10000);runCurrent()
        assertEquals(1,seen.size);assertEquals(Phase.GUIDING,loop.state.value.phase)
        loop.userFinished();advanceTimeBy(500);runCurrent()
        assertEquals("user_confirmed_action",seen.last().history.last().result.status)
        loop.answer("다른 앱이 열려요");advanceTimeBy(500);runCurrent()
        assertEquals("user_answer",seen.last().history.last().result.status)
        assertEquals("다른 앱이 열려요",seen.last().userAnswers.single().answer)
    }
    @Test fun verifiedOsChangesInvalidateOldModelWorkButCannotResumeAUserPauseOrAnotherSession()=runTest{
        val pending=CompletableDeferred<ToolCall>();val seen=mutableListOf<AgentRequest>();val d=Device(screen())
        val model=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{
            seen+=request
            return if(seen.size==1)withContext(NonCancellable){pending.await()}else guide(request,seen.size)
        }}
        val loop=AgentLoop(backgroundScope,model,d,Recorder());loop.start("역할 상태");runCurrent();val id=loop.state.value.sessionId
        assertFalse(loop.recheckVerifiedState("old-session"));assertTrue(loop.recheckVerifiedState(id));runCurrent()
        assertEquals(2,seen.size);pending.complete(guide(seen.first(),99));runCurrent()
        assertEquals("call2",loop.state.value.pending!!.callId);assertEquals(1,d.spoken.size)
        loop.pause();assertFalse(loop.recheckVerifiedState(id));advanceTimeBy(1000);runCurrent()
        assertEquals(Phase.PAUSED,loop.state.value.phase);assertEquals(2,seen.size)
    }
    @Test fun aSameScreenHomeBackCycleCannotProduceUnlimitedNavigationGuidance()=runTest{
        val d=Device(screen());var count=0;val log=Recorder()
        val model=object:AgentModel{override suspend fun next(request:AgentRequest)=guide(request,++count,if(count%2==1)Action.HOME else Action.BACK)}
        val loop=AgentLoop(backgroundScope,model,d,log);loop.start("반복 경로");runCurrent()
        repeat(4){loop.userFinished();advanceTimeBy(500);runCurrent()}
        assertEquals(Phase.ERROR,loop.state.value.phase);assertEquals(4,d.spoken.size)
        assertTrue(log.results.any{it.status=="tool_error"&&it.detail.contains("반복")})
    }
}
