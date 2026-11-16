package dev.ccomet.chanchanhi04.setup

import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.agent.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CapabilityGuideTest{
    private val context=CapabilityGuideContext("설정 미션","chanchanhi-test","dev.ccomet.chanchanhi",true)
    private fun element(id:Int,label:String,editable:Boolean=false)=Element(id,"0/$id","observed:id/row$id","TextView",label,NodeBounds(20,100+id*70,400,160+id*70),true,true,!editable,editable,false,null,null,null,null)
    private fun request(pkg:String,vararg elements:Element)=AgentRequest(context.goal,emptyList(),Observation(73,pkg,8,3,9,1080,2340,true,elements.toList()),emptyList(),0,null)
    @Test fun everyObservedLayoutCallsTheAiWithoutSelectingALocalAction()=runTest{
        val requests=mutableListOf<AgentRequest>()
        val remote=ToolCall("ai-choice",Tool.ASK_USER,"관측 확인",question="현재 후보를 확인해주세요.")
        val delegate=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{requests+=request;return remote}}
        val model=CapabilityGuideModel(delegate,{context},{SetupSnapshot(true,true,false)})
        val layouts=listOf(
            request("system.home",element(1,"설정")),
            request("system.settings",element(1,"연결"),element(2,"디스플레이")),
            request("system.settings",element(1,"기본 앱"),element(2,"뒤로")),
            request("system.search",element(1,"검색",true)),
            request("system.search",element(1,"기기 도움 앱")),
            request("system.settings",element(1,"검색이 없는 화면")),
            request("system.popup",element(1,"허용"),element(2,"찬찬히3")),
            request("unknown.vendor",element(1,"디지털 어시스턴트 앱"),element(2,"chanchanhi-test")),
            request("system.settings").copy(screen=request("system.settings").screen.copy(complete=false)),
        )
        for(layout in layouts){assertSame(remote,model.next(layout));assertEquals(layout.screen,requests.last().screen)}
        assertEquals(layouts.size,requests.size)
        assertTrue(requests.all{it.taskContext!!.contains("package=dev.ccomet.chanchanhi")})
    }
    @Test fun osStateAndExactAppIdentityAreFreshTaskFactsRatherThanMatchedButtonNames()=runTest{
        var actual=SetupSnapshot(true,false,false);val seen=mutableListOf<AgentRequest>();val reports=mutableListOf<String>()
        val delegate=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{seen+=request;return ToolCall("ai",Tool.OBSERVE,"관측")}}
        val model=CapabilityGuideModel(delegate,{context},{actual},reports::add)
        model.next(request("search.provider",element(1,"찬찬히3"),element(2,"chanchanhi-test")))
        assertTrue(seen.last().taskContext!!.contains("라벨=chanchanhi-test"))
        assertTrue(seen.last().taskContext!!.contains("기본 디지털 도우미=false"))
        assertTrue(seen.last().taskContext!!.contains("이 작업에서 마이크 설정이나 권한 화면으로 안내하지 않는다"))
        actual=SetupSnapshot(true,true,true);model.next(request("system.settings"))
        assertTrue(seen.last().taskContext!!.contains("기본 디지털 도우미=true"))
        assertTrue(seen.last().taskContext!!.contains("VoiceInteractionSession.onShow"))
        assertEquals(listOf("ai_observed_screen","ai_observed_screen"),reports)
    }
    @Test fun aModelFinishCannotReplaceEitherOsCompletionOrActualInvocation()=runTest{
        val delegate=object:AgentModel{override suspend fun next(request:AgentRequest)=ToolCall("ai",Tool.FINISH,"화면만 보고 추정")}
        for(verified in listOf(false,true))for(invocation in listOf(false,true)){
            val model=CapabilityGuideModel(delegate,{context.copy(verifyInvocation=invocation)},{SetupSnapshot(true,true,verified)})
            try{model.next(request("system.settings",element(1,"완료")));fail("model finish must be rejected")}
            catch(expected:ModelOutputFailure){assertTrue(expected.reason.contains("os_verification"))}
        }
    }
    @Test fun unrelatedGoalsArePassedThroughWithoutCapabilityInstructions()=runTest{
        var seen:AgentRequest?=null
        val delegate=object:AgentModel{override suspend fun next(request:AgentRequest):ToolCall{seen=request;return ToolCall("ai",Tool.OBSERVE,"관측")}}
        val original=request("public.app").copy(goal="다른 사용자 목표")
        CapabilityGuideModel(delegate,{context},{SetupSnapshot(true,true,false)}).next(original)
        assertSame(original,seen);assertNull(seen!!.taskContext)
    }
    @Test fun anUnreadableScreenNeverInventsAnAppOrATap(){
        val message=CapabilityTaskContext.unavailableMessage()
        assertTrue(message.contains("읽을 수 없어"));assertFalse(message.contains("마이크를 허용"));assertFalse(message.contains("뒤로가기"))
    }
}
