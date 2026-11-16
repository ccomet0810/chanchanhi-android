package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPacketTest {
    private val nodes=(1..350).map{id->Element(id,"0/$id","demo:id/item$id","Button","한글 설명과 긴 항목 ".repeat(30),
        NodeBounds(0,0,100,100),true,true,true,false,false,null,null,null,null)}
    private val screen=Observation(1,"public.demo",1,1,1,720,1280,true,nodes)
    @Test fun `image and Korean context fit the entire UTF8 transport budget`() {
        val answer=UserAnswer("몇 시로 할까요?","오전 7시")
        val request=AgentRequest("공개 화면 안내",listOf(PlanStep("시간 맞추기","in_progress")),screen,emptyList(),0,
            ScreenImage(screen,"A".repeat(163840),720,1280),userAnswers=listOf(answer),taskContext="actual app=chanchanhi-test; package=dev.ccomet.chanchanhi; assistant=false")
        val packet=ModelPackets.build(request,JSONObject().put("type","object"),"도구 안내","gpt-6.1-terra")
        assertTrue(packet.bytes.size<=250000)
        assertEquals("공개 화면 안내",packet.context.getString("goal"))
        assertEquals("오전 7시",packet.context.getJSONArray("userAnswers").getJSONObject(0).getString("answer"))
        assertEquals(request.taskContext,packet.context.getString("taskContext"))
        assertFalse(packet.complete)
        assertTrue(packet.context.getJSONObject("screen").getInt("omittedNodeCount")>0)
        assertTrue("packet budgeting must be logarithmic",packet.buildPasses<=12)
        println("PACKET_BUDGET_BENCH "+JSONObject().put("nodes",nodes.size).put("retained",packet.visibleNodeIds.size)
            .put("beforeSerializations",1+nodes.size-packet.visibleNodeIds.size).put("afterSerializations",packet.buildPasses).put("bytes",packet.bytes.size))
        assertTrue(JSONObject(packet.bytes.toString(Charsets.UTF_8)).getJSONArray("input").getJSONObject(0).getJSONArray("content").length()==2)
    }
    @Test fun `old observations may be clipped but answers and current plan remain`() {
        val call=ToolCall("old",Tool.OBSERVE,"이전 관찰")
        val exchange=Exchange(call,ToolResult("old","observed","이전 화면",screen))
        val request=AgentRequest("목표",listOf(PlanStep("현재 조건","in_progress")),screen.copy(elements=nodes.take(2)),List(24){exchange},0,null,
            userAnswers=listOf(UserAnswer("조건","사용자 선택")),taskContext="verified OS task facts")
        val packet=ModelPackets.build(request,JSONObject().put("type","object"),"안내","gpt-6.1-terra")
        assertTrue(packet.context.getInt("omittedExchanges")>0)
        assertEquals(request.taskContext,packet.context.getString("taskContext"))
        assertTrue(packet.complete)
        assertEquals("사용자 선택",packet.context.getJSONArray("userAnswers").getJSONObject(0).getString("answer"))
        assertEquals("현재 조건",packet.context.getJSONArray("plan").getJSONObject(0).getString("step"))
    }
}
