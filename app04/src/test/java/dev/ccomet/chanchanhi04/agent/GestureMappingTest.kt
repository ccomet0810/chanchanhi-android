package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GestureMappingTest {
    private val target=NodeBounds(100,200,900,1000)
    @Test fun `every authored motion maps from a structured action`() {
        val actions=listOf(Action.TAP,Action.DOUBLE_TAP,Action.LONG_PRESS,Action.SWIPE_UP,Action.SWIPE_DOWN,Action.SWIPE_LEFT,Action.SWIPE_RIGHT,Action.DRAG,Action.HOLD_DRAG,Action.ZOOM_IN,Action.ZOOM_OUT)
        val actual=actions.map{GestureMapping.resolve(it,target,1000,1500,if(it in setOf(Action.DRAG,Action.HOLD_DRAG))listOf(200,200,800,500)else null)!!.asset}
        assertEquals(GestureMapping.assets.toSet(),actual.toSet())
        assertTrue(GestureMapping.resolve(Action.SWIPE_UP,target,1000,1500)!!.let{it.start.y>it.end.y})
        assertTrue(GestureMapping.resolve(Action.SWIPE_LEFT,target,1000,1500)!!.let{it.start.x>it.end.x})
    }
    @Test fun `unknown direction is never invented and paths stay bound to targets`() {
        assertNull(GestureMapping.resolve(Action.ADJUST,target,1000,1500))
        assertNull(GestureMapping.resolve(Action.DRAG,target,1000,1500))
        assertFalse(GestureMapping.pathValid(Action.DRAG,listOf(0,0,500,500),target,1000,1500))
        assertFalse(GestureMapping.pathValid(Action.ADJUST,listOf(200,200,950,900),target,1000,1500))
        assertFalse(GestureMapping.pathValid(Action.TAP,listOf(200,200,400,500),target,1000,1500))
        assertFalse(GestureMapping.pathValid(Action.DRAG,listOf(200,200,1001,500),target,1000,1500))
        assertNull(GestureMapping.resolve(Action.BACK,null,1000,1500))
        assertNull(GestureMapping.resolve(Action.HOME,null,1000,1500))
        assertEquals("swipe_down",GestureMapping.resolve(Action.NOTIFICATIONS,null,1000,1500)!!.asset)
    }
    @Test fun `retargeting preserves hand shape and removes fictional demo controls`() {
        for(asset in GestureMapping.assets){
            val source=javaClass.classLoader!!.getResource("gestures/$asset.json")!!.readText()
            val original=JSONObject(source).getJSONArray("layers").let{a->(0 until a.length()).map(a::getJSONObject).first{it.getString("nm") in setOf("Hand","Pinch hand")}}
            val cue=GestureCue(asset,ContactPoint(300f,500f),ContactPoint(600f,650f))
            val output=JSONObject(GestureRetargeter.build(source,cue,1080,2340,3f));val layers=output.getJSONArray("layers")
            val hand=(0 until layers.length()).map(layers::getJSONObject).first{it.getString("nm") in setOf("Hand","Pinch hand")}
            assertEquals("$asset hand outline changed",original.getJSONArray("shapes").toString(),hand.getJSONArray("shapes").toString())
            assertEquals(1080,output.getInt("w"));assertEquals(2340,output.getInt("h"))
            assertTrue((0 until layers.length()).none{layers.getJSONObject(it).getString("nm") in setOf("Demo item","Drop destination","Movement guide","Zoom reference")})
        }
    }
    @Test fun `tap contact lands exactly on the screen target at every size`() {
        val source=javaClass.classLoader!!.getResource("gestures/tap.json")!!.readText()
        for(density in listOf(1f,2f,3.5f)){
            val d=JSONObject(GestureRetargeter.build(source,GestureCue("tap",ContactPoint(430f,730f),ContactPoint(430f,730f)),1080,2340,density))
            val layers=d.getJSONArray("layers");val pressure=(0 until layers.length()).map(layers::getJSONObject).first{it.getString("nm")=="Contact pressure"}.getJSONObject("ks").getJSONObject("p").getJSONArray("k")
            assertEquals(430.0,pressure.getDouble(0),.001);assertEquals(730.0,pressure.getDouble(1),.001)
        }
    }
    @Test fun `new path is parsed strictly while legacy compact calls remain readable`() {
        val base=JSONObject("""{"callId":"x","summary":"안내","plan":[],"command":{"name":"guide","snapshotId":1,"nodeId":1,"region":null,"action":"tap","instruction":"누르세요","expectedChange":"화면 변경"}}""")
        assertNull(AgentJson.call(base).gesturePath)
        val command=base.getJSONObject("command");command.put("action","hold_drag").put("gesturePath",org.json.JSONArray(listOf(100,200,700,800)))
        assertEquals(listOf(100,200,700,800),AgentJson.call(base).gesturePath)
        command.put("gesturePath",org.json.JSONArray(listOf(100,200,1001,800)))
        assertThrows(IllegalArgumentException::class.java){AgentJson.call(base)}
    }
}
