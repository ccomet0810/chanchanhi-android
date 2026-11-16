package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import org.junit.Assert.*
import org.junit.Test

class GroundingTest {
    private val node=Element(1,"0/1","demo:id/item","Button","나만의\n냉장고",NodeBounds(10,100,300,200),true,true,true,false,false,null,null,null,null)
    private val screen=Observation(1,"demo.app",1,1,9,1080,1920,true,listOf(node))
    @Test fun `compact command decodes into the same grounded action with its full plan`() {
        val json=org.json.JSONObject("""{"callId":"c1","summary":"현재 입력란 확인","plan":[{"step":"입력","status":"in_progress"},{"step":"결과 확인","status":"pending"}],"command":{"name":"guide","snapshotId":1,"nodeId":1,"region":null,"action":"tap","instruction":"눌러주세요.","expectedChange":"다음 화면"}}""")
        val call=AgentJson.call(json)
        assertEquals(Tool.GUIDE,call.tool);assertEquals(1,call.nodeId);assertEquals(2,call.plan.size)
        val command=json.getJSONObject("command");command.put("untrusted",true)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java){AgentJson.call(json)}
    }
    @Test fun `frame deduplication ignores only counters and keeps real content changes`() {
        assertTrue(screen.sameContent(screen.copy(id=8,epoch=12,visualGeneration=30)))
        for(changed in listOf(screen.copy(windowId=5),screen.copy(complete=false),screen.copy(elements=listOf(node.copy(label="다른 내용"))),
            screen.copy(elements=listOf(node.copy(bounds=NodeBounds(20,100,310,200))))))assertFalse(screen.sameContent(changed))
    }
    @Test fun `multiline labels normalize but unrelated labels never match`() {
        assertNotNull(Grounding.resolve(screen,screen.copy(id=2,elements=listOf(node.copy(id=7,label="나만의 냉장고"))),1))
        assertNull(Grounding.resolve(screen,screen.copy(elements=listOf(node.copy(label="냉장고"))),1))
    }
    @Test fun `duplicate public identities are ambiguous`() {
        assertNull(Grounding.resolve(screen,screen.copy(elements=listOf(node,node.copy(id=2,path="0/2"))),1))
    }
    @Test fun `changed checked state invalidates old action`() {
        assertNull(Grounding.resolve(screen.copy(elements=listOf(node.copy(checked=0))),screen.copy(elements=listOf(node.copy(checked=1))),1))
    }
    @Test fun `resized image coordinates map back to physical screen pixels`() {
        val image=ScreenImage(screen,"",540,960)
        assertEquals(NodeBounds(100,200,400,600),Grounding.visualTarget(image,screen.copy(id=2),screen.copy(id=3),NodeBounds(50,100,200,300)))
    }
    @Test fun `interaction after screenshot invalidates visual region`() {
        assertNull(Grounding.visualTarget(ScreenImage(screen,"",540,960),screen,screen.copy(visualGeneration=10),NodeBounds(50,100,200,300)))
    }
    @Test fun `out of image coordinates cannot become a highlight`() {
        assertNull(Grounding.visualTarget(ScreenImage(screen,"",540,960),screen,screen,NodeBounds(0,0,541,900)))
    }
    @Test fun `tool call serializes and decodes without dropping fields`() {
        val call=ToolCall("x1",Tool.GUIDE,"설명",1,1,action=Action.TAP,instruction="눌러주세요.",expectedChange="이동")
        assertEquals(call,AgentJson.call(AgentJson.encode(call) as org.json.JSONObject))
    }
    @Test fun `numeric narrowing never turns an invalid model target into a real pixel or node`() {
        val call=ToolCall("x1",Tool.GUIDE,"설명",1,1,action=Action.TAP,instruction="눌러주세요.",expectedChange="이동")
        val json=AgentJson.encode(call) as org.json.JSONObject
        for(invalid in listOf(4294967297L,1.5,"1")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java){AgentJson.call(org.json.JSONObject(json.toString()).put("nodeId",invalid))}
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java){AgentJson.call(json.put("region",org.json.JSONArray(listOf(4294967297L,0,200,100))))}
    }
}
