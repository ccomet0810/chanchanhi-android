package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {
    private fun node(id: Int=1,label: String="검색",editable: Boolean=false) = Element(id,"0/$id","sample:id/control","Button",label,
        NodeBounds(10,100,200,180),true,true,!editable,editable,false,if(editable) false else null,null,null,null)
    private fun screen(id: Long=1,pkg: String="sample.app",nodes: List<Element> = listOf(node())) = Observation(id,pkg,4,1,1,1080,1920,true,nodes)
    private open class Device(var current: Observation) : DevicePort {
        var spoken = mutableListOf<String>(); var opened = 0; var rendered = AgentState(); var observeError = false; var reads=0
        override suspend fun observe(): Observation {reads++; if(observeError) error("read failure"); return current }
        override suspend fun capture() = ScreenImage(current,"AA==",540,960)
        override fun installedApps() = listOf(AppEntry("sample.clock","시계"))
        override fun render(state: AgentState) { rendered=state }
        override fun speak(message: String) { spoken += message }
        override fun stopSpeech() = Unit
        override val imagesAllowed = true
    }
    private class Recorder : SessionRecorder {
        val events=mutableListOf<Pair<String,Any?>>()
        override fun begin(sessionId: String,goal:String) {events.clear()}
        override fun event(kind: String,value:Any?) {events += kind to value}
        fun results()=events.filter{it.first=="tool_result"}.map{it.second as ToolResult}
    }
    private class Model(val respond: suspend (AgentRequest,Int)->ToolCall) : AgentModel {
        val requests=mutableListOf<AgentRequest>()
        override suspend fun next(request: AgentRequest):ToolCall {requests+=request;return respond(request,requests.size)}
    }
    private fun guide(request: AgentRequest,index:Int,action:Action=Action.TAP) = ToolCall("c$index",Tool.GUIDE,"현재 요소 안내",request.screen.id,1,
        action=action,instruction="검색을 눌러주세요.",expectedChange="검색 입력란 표시")
    private fun question(index:Int) = ToolCall("c$index",Tool.ASK_USER,"선택이 필요함",question="몇 시로 할까요?")

    @Test fun protectedFailureAfterAUserActionPreservesRecoveryInsteadOfGenericError()=runTest{
        val device=object:Device(screen()){
            override suspend fun observe():Observation {if(observeError)throw ObservationUnavailable("active_root_unavailable");return super.observe()}
            override fun observationRecovery(failure:ObservationUnavailable,lastScreen:Observation?)="이 화면은 읽을 수 없어요. 설정 후 뒤로가기로 돌아와주세요."
        }
        val model=Model{r,i->guide(r,i)};val recorder=Recorder();val loop=AgentLoop(backgroundScope,model,device,recorder)
        loop.start("설정");runCurrent();device.observeError=true;loop.userFinished();advanceTimeBy(600);runCurrent()
        assertEquals(Phase.PAUSED,loop.state.value.phase);assertTrue(loop.state.value.message.contains("읽을 수 없어요"));assertNull(loop.state.value.target);assertNull(loop.state.value.pending)
        assertEquals(1,model.requests.size);assertTrue(recorder.events.any{it.first=="observation_wait"})
        loop.resume();runCurrent();assertEquals(Phase.PAUSED,loop.state.value.phase);assertEquals(1,model.requests.size)
        device.observeError=false;loop.resume();runCurrent();assertEquals(Phase.GUIDING,loop.state.value.phase)
    }
    @Test fun verifiedCapabilityCompletionRejectsOldSessionsAndCancelsLateResponses()=runTest{
        val model=Model{_,i->delay(3000);question(i)};val device=Device(screen());val loop=AgentLoop(backgroundScope,model,device,Recorder())
        loop.start("첫 작업");runCurrent();val old=loop.state.value.sessionId
        loop.start("현재 작업");runCurrent();val current=loop.state.value.sessionId
        assertFalse(loop.completeVerified(old,"과거 결과"));assertEquals(Phase.THINKING,loop.state.value.phase)
        assertTrue(loop.completeVerified(current,"운영체제 확인 완료"));advanceTimeBy(4000);runCurrent()
        assertEquals(Phase.COMPLETE,loop.state.value.phase);assertEquals("운영체제 확인 완료",loop.state.value.message)
        assertFalse(loop.completeVerified(current,"중복 완료"))
        loop.stop();assertFalse(loop.completeVerified(current,"중단 후 결과"))
    }

    @Test fun `capability setup waits without model IO and resumes the same goal`() = runTest{
        val device=Device(screen());val model=Model{r,i->guide(r,i)};val log=Recorder();val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("마이크와 도우미 준비","권한 설정을 기다려요.");runCurrent()
        assertEquals(Phase.PAUSED,loop.state.value.phase);assertEquals(0,device.reads);assertTrue(model.requests.isEmpty())
        assertTrue(log.events.any{it.first=="waiting_capability_setup"})
        loop.resume();runCurrent();assertEquals("마이크와 도우미 준비",model.requests.single().goal)
    }
    @Test fun `plan and grounded guide arrive in one model request`() = runTest {
        val device=Device(screen());val log=Recorder();val plan=listOf(PlanStep("시간과 요일 조절","in_progress"),PlanStep("저장하고 결과 확인","pending"))
        val model=Model{r,i->guide(r,i).copy(plan=plan)};val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("조건에 맞게 설정");runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,model.requests.size);assertEquals(plan,loop.state.value.plan)
        assertTrue(log.events.any{it.first=="plan_updated"});assertEquals(2,device.reads)
        loop.userFinished();advanceTimeBy(500);runCurrent()
        assertEquals(4,device.reads) // one post-action read plus mandatory grounding, without a duplicate pre-model read
        assertEquals(plan,model.requests.last().plan)
    }
    @Test fun `combined question keeps all user choices through the next turn`() = runTest {
        val plan=listOf(PlanStep("시간 선택","in_progress"),PlanStep("요일과 저장","pending"))
        val model=Model{r,i->if(i==1)question(i).copy(plan=plan)else guide(r,i)}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder());loop.start("알람 설정");runCurrent()
        assertEquals(1,model.requests.size);loop.answer("평일 오전 7시 30분");advanceTimeBy(500);runCurrent()
        assertEquals(plan,model.requests.last().plan);assertEquals("평일 오전 7시 30분",model.requests.last().userAnswers.single().answer)
    }
    @Test fun `invalid combined plan and changed target cannot mutate the accepted plan`() = runTest {
        val device=Device(screen());val model=Model{r,i->when(i){
            1->guide(r,i).copy(plan=listOf(PlanStep("a","in_progress"),PlanStep("b","in_progress")))
            2->{device.current=screen(2,pkg="other.app");guide(r,i).copy(plan=listOf(PlanStep("invalid old screen","completed")))}
            else->question(i)}}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("현재 목표");runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase);assertTrue(loop.state.value.plan.isEmpty());assertNull(loop.state.value.target)
    }
    @Test fun `explicit observe result is consumed once without a second tree collection`() = runTest {
        val device=Device(screen());val model=Model{r,i->if(i==1)ToolCall("o1",Tool.OBSERVE,"화면 재확인")else question(i)}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("현재 화면 확인");runCurrent()
        assertEquals(2,device.reads);assertEquals(2,model.requests.size);assertEquals(Phase.QUESTION,loop.state.value.phase)
    }

    @Test fun `input waits for explicit completion and retains goal`() = runTest {
        val device=Device(screen(nodes=listOf(node(editable=true)))); val log=Recorder()
        val model=Model { r,i -> if(i==1) guide(r,i,Action.INPUT) else question(i) }
        val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("메시지 작성하기");runCurrent()
        device.current=device.current.copy(id=2,elements=listOf(node(editable=true).copy(hasInput=true)))
        loop.onInteraction(UserInteraction(1,"sample.app",4,"0/1","sample:id/control","text"));runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,model.requests.size)
        loop.userFinished();advanceTimeBy(500);runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase)
        assertEquals("메시지 작성하기",model.requests.last().goal)
        assertEquals("user_confirmed_action",model.requests.last().history.single().result.status)
    }
    @Test fun `unrelated content change does not cancel an in-flight model response`() = runTest {
        val deferred=CompletableDeferred<ToolCall>();val device=Device(screen());val log=Recorder()
        val model=Model {_,_->deferred.await()};val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("검색하기");runCurrent()
        val original=model.requests.single()
        device.current=screen(2,nodes=listOf(node(),node(2,"새 배너")))
        loop.onObservation(device.current)
        deferred.complete(guide(original,1));runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,model.requests.size)
        assertEquals(node().bounds,loop.state.value.target)
    }
    @Test fun `late response cannot revive a paused session`() = runTest {
        val deferred=CompletableDeferred<ToolCall>();val device=Device(screen())
        val model=Model {_,_->withContext(NonCancellable){deferred.await()}}
        val loop=AgentLoop(backgroundScope,model,device,Recorder())
        loop.start("검색하기");runCurrent();val request=model.requests.single()
        loop.pause();deferred.complete(guide(request,1));runCurrent()
        assertEquals(Phase.PAUSED,loop.state.value.phase);assertNull(loop.state.value.target)
        assertTrue(device.spoken.isEmpty())
    }
    @Test fun `changed window rejects old target and returns failure to model`() = runTest {
        val device=Device(screen());val log=Recorder()
        val model=Model {r,i->if(i==1){device.current=screen(2,pkg="other.app");guide(r,i)}else question(i)}
        val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("검색하기");runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase)
        assertEquals("tool_error",model.requests.last().history.single().result.status)
        assertNull(loop.state.value.target)
    }
    @Test fun `question answer returns to the same goal and plan`() = runTest {
        val model=Model {_,i->when(i){1->ToolCall("c1",Tool.UPDATE_PLAN,"전체 계획",plan=listOf(PlanStep("알람 시간 결정","in_progress"),PlanStep("저장","pending")));else->question(i)}}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("알람 맞추기");runCurrent();loop.answer("오전 7시");advanceTimeBy(500);runCurrent()
        assertEquals("알람 맞추기",model.requests.last().goal)
        assertEquals(2,model.requests.last().plan.size)
        assertEquals("오전 7시",model.requests.last().history.last().result.answer)
    }
    @Test fun `legacy open app is redirected to manual navigation and never launched`() = runTest {
        val device=Device(screen());val model=Model {_,i->if(i==1)ToolCall("c1",Tool.OPEN_APP,"앱 선택",packageName="sample.clock",instruction="시계를 열어주세요.")else question(i)}
        val log=Recorder();val loop=AgentLoop(backgroundScope,model,device,log)
        loop.start("알람 맞추기");runCurrent();assertEquals(0,device.opened)
        assertEquals(Phase.QUESTION,loop.state.value.phase)
        assertEquals("manual_app_navigation_required",log.results().first().status)
        assertEquals("sample.app",model.requests.last().screen.packageName)
        loop.userFinished();runCurrent();assertEquals(0,device.opened)
    }
    @Test fun `more than eight user steps continue without resetting goal`() = runTest {
        val device=Device(screen());val model=Model{r,i->guide(r,i)};val loop=AgentLoop(backgroundScope,model,device,Recorder())
        loop.start("긴 작업");runCurrent()
        repeat(11){loop.userFinished();advanceTimeBy(500);runCurrent()}
        assertEquals(12,model.requests.size);assertEquals(Phase.GUIDING,loop.state.value.phase)
        assertTrue(model.requests.all{it.goal=="긴 작업"})
    }
    @Test fun `model final claim without evidence never completes`() = runTest {
        val model=Model{r,i->ToolCall("c$i",Tool.FINISH,"끝남",snapshotId=r.screen.id)}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("메시지 전송");runCurrent();assertEquals(Phase.ERROR,loop.state.value.phase)
    }
    @Test fun `completion evidence is revalidated in the current screen`() = runTest {
        val device=Device(screen());val model=Model{r,i->if(i==1){device.current=screen(2,pkg="other.app");ToolCall("c1",Tool.FINISH,"결과",r.screen.id,evidenceNodeIds=listOf(1))}else question(i)}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("설정 확인");runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase);assertEquals("tool_error",model.requests.last().history.last().result.status)
    }
    @Test fun `pause during post-action observation records interruption instead of losing the action`() = runTest {
        val log=Recorder();val model=Model{r,i->guide(r,i)};val loop=AgentLoop(backgroundScope,model,Device(screen()),log)
        loop.start("검색");runCurrent();loop.userFinished();loop.pause();advanceTimeBy(600);runCurrent()
        assertEquals(Phase.PAUSED,loop.state.value.phase)
        assertEquals("interrupted",log.results().last().status)
        assertEquals(1,log.results().size)
    }
    @Test fun `timeout produces a retryable error state`() = runTest {
        val model=Model{_,_->delay(60000);question(1)};val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("검색");runCurrent();advanceTimeBy(46000);runCurrent()
        assertEquals(Phase.ERROR,loop.state.value.phase)
    }
    @Test fun `input cleared and screen change alone never finish a task`() = runTest {
        val device=Device(screen(nodes=listOf(node(editable=true).copy(hasInput=true))))
        val loop=AgentLoop(backgroundScope,Model{r,i->guide(r,i,Action.INPUT)},device,Recorder())
        loop.start("문자 보내기");runCurrent()
        loop.onObservation(screen(2,nodes=listOf(node(editable=true).copy(hasInput=false))))
        assertEquals(Phase.GUIDING,loop.state.value.phase)
    }
    @Test fun `same resource on another row does not consume the guide`() = runTest {
        val device=Device(screen(nodes=listOf(node(),node(2,"다른 항목"))))
        val model=Model{r,i->guide(r,i)};val loop=AgentLoop(backgroundScope,model,device,Recorder())
        loop.start("검색");runCurrent()
        loop.onInteraction(UserInteraction(1,"sample.app",4,"0/2","sample:id/control","click"));runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase);assertEquals(1,model.requests.size)
    }
    @Test fun `new session cancels old results and preserves new goal`() = runTest {
        val deferred=CompletableDeferred<ToolCall>();val model=Model{_,i->if(i==1)withContext(NonCancellable){deferred.await()}else question(i)}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("옛 목표");runCurrent();loop.start("새 목표");runCurrent();deferred.complete(question(1));runCurrent()
        assertEquals("새 목표",loop.state.value.goal);assertEquals(Phase.QUESTION,loop.state.value.phase)
    }
    @Test fun `rejected model shape returns explicit feedback without losing goal`() = runTest {
        val model=Model{r,i->if(i==1)throw ModelOutputFailure("invalid_json")else guide(r,i)}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("검색하기");runCurrent()
        assertEquals(Phase.GUIDING,loop.state.value.phase)
        assertEquals("invalid_json",model.requests.last().lastModelError)
        assertEquals("검색하기",model.requests.last().goal)
    }
    @Test fun `model rejection retries are bounded`() = runTest {
        val model=Model{_,_->throw ModelOutputFailure("invalid_json")}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("검색하기");runCurrent()
        assertEquals(3,model.requests.size);assertEquals(Phase.ERROR,loop.state.value.phase)
    }
    @Test fun `user choices survive after their original exchange leaves the rolling history`() = runTest {
        val model=Model{r,i->if(i==1)question(i)else guide(r,i)}
        val loop=AgentLoop(backgroundScope,model,Device(screen()),Recorder())
        loop.start("조건을 유지하는 긴 작업");runCurrent();loop.answer("오전 7시");advanceTimeBy(500);runCurrent()
        repeat(27){loop.userFinished();advanceTimeBy(500);runCurrent()}
        assertTrue(model.requests.last().omittedExchanges>0)
        assertEquals(UserAnswer("몇 시로 할까요?","오전 7시"),model.requests.last().userAnswers.single())
    }
    @Test fun `current visual result can finish when accessibility observation is incomplete`() = runTest {
        val device=Device(screen().copy(complete=false))
        val model=Model{r,i->if(i==1)ToolCall("capture",Tool.CAPTURE_SCREEN,"이미지 확인")else
            ToolCall("visual_done",Tool.FINISH,"이미지의 결과 확인",r.screen.id,region=NodeBounds(10,20,200,100),instruction="안내를 마쳤어요.")}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("공개 이미지 결과 확인");runCurrent()
        assertEquals(Phase.COMPLETE,loop.state.value.phase)
    }
    @Test fun `changed image cannot establish visual completion`() = runTest {
        val device=Device(screen().copy(complete=false))
        val model=Model{r,i->when(i){1->ToolCall("capture",Tool.CAPTURE_SCREEN,"이미지 확인");2->{device.current=device.current.copy(visualGeneration=2)
            ToolCall("visual_done",Tool.FINISH,"예전 이미지",r.screen.id,region=NodeBounds(10,20,200,100))};else->question(i)}}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("공개 이미지 결과 확인");runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase);assertEquals("tool_error",model.requests.last().history.last().result.status)
    }
    @Test fun `new window during user wait returns an interruption result and preserves the goal`() = runTest {
        val device=Device(screen());val model=Model{r,i->if(i==1)guide(r,i)else question(i)}
        val loop=AgentLoop(backgroundScope,model,device,Recorder());loop.start("원래 목표");runCurrent()
        device.current=device.current.copy(id=2,windowId=9,packageName="public.popup")
        loop.onObservation(device.current);advanceTimeBy(500);runCurrent()
        assertEquals(Phase.QUESTION,loop.state.value.phase)
        assertEquals("screen_changed",model.requests.last().history.last().result.status)
        assertEquals("원래 목표",model.requests.last().goal)
    }
}
