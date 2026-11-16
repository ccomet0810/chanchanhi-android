package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplayEncodingTest {
    @Test fun replayTextAndDescriptionsNeverChangeTheModelPacket(){
        val node=Element(1,"0",null,"ViewGroup","전체 접근성 이름",NodeBounds(0,0,100,100),true,true,true,false,false,null,null,null,null,
            display=ElementDisplay("자기 글자","읽어주는 설명",null))
        val screen=Observation(1,"public.fixture",1,1,1,100,100,true,listOf(node))
        val replay=AgentJson.encode(screen,true) as JSONObject
        assertEquals("자기 글자",replay.getJSONArray("nodes").getJSONObject(0).getJSONObject("display").getString("text"))
        val a=AgentJson.request(AgentRequest("공개 시험",emptyList(),screen,emptyList(),0,null)).toString()
        val b=AgentJson.request(AgentRequest("공개 시험",emptyList(),screen.copy(elements=listOf(node.copy(display=null))),emptyList(),0,null)).toString()
        assertEquals(b,a);assertFalse(a.contains("읽어주는 설명"))
    }
}
