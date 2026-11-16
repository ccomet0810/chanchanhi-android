package dev.ccomet.chanchanhi04.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.guide.V04Model
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.setup.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in live transport contract using synthetic public observations only.
 * No Settings launch, accessibility toggle, session journal, participant record or permission change. */
@RunWith(AndroidJUnit4::class)
class AiSetupModelContractDeviceTest{
    @Test fun actualTerraReceivesEverySyntheticSettingsObservationAndExactOwnAppIdentity()=runBlocking{
        assumeTrue(InstrumentationRegistry.getArguments().getString("runAiSetupLive")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val ownLabel=app.packageManager.getApplicationLabel(app.applicationInfo).toString()
        val task=CapabilityGuideContext(SetupRequirement.ASSISTANT.goal(ownLabel),ownLabel,app.packageName,true)
        val events=mutableListOf<String>();val packets=mutableListOf<JSONObject>()
        // This private in-memory trace deliberately never goes through AppRuntime/SessionLog.
        val terra=V04Model(app){{kind,value->events+=kind;if(kind=="model_request")packets+=value as JSONObject}}
        val model=CapabilityGuideModel(terra,{task},{SetupPermissions.read(app)})
        fun node(id:Int,label:String,editable:Boolean=false)=Element(id,"0/$id","public.fixture:id/item$id","TextView",label,
            NodeBounds(20,120+id*100,900,200+id*100),true,true,!editable,editable,false,null,null,null,null)
        val screens=listOf(
            Observation(1,"public.synthetic.settings",1,1,1,1080,2340,true,listOf(node(1,"기기 도움 앱"),node(2,"검색"))),
            Observation(2,"public.synthetic.search",2,2,2,1080,2340,true,listOf(node(1,"어시스턴트 검색",true),node(2,"디지털 도우미 선택"))),
        )
        val tools=mutableListOf<String>()
        for(screen in screens){
            val call=model.next(AgentRequest(task.goal,emptyList(),screen,emptyList(),0,null))
            assertTrue(call.callId.isNotBlank());assertNotEquals(Tool.FINISH,call.tool)
            if(call.tool==Tool.GUIDE){
                assertEquals(screen.id,call.snapshotId)
                assertTrue(call.nodeId==null||screen.elements.any{it.id==call.nodeId})
                assertNull("No image was supplied",call.region)
            }
            tools+=call.tool.name
        }
        assertEquals(screens.size,events.count{it=="model_response"})
        assertEquals(screens.size,packets.size)
        packets.forEachIndexed{index,packet->
            assertEquals(screens[index].packageName,packet.getJSONObject("screen").getString("packageName"))
            assertTrue(packet.getString("taskContext").contains("라벨=$ownLabel"))
            assertTrue(packet.getString("taskContext").contains("package=${app.packageName}"))
        }
        File(app.cacheDir,"ai-setup-fixture-contract.json").writeText(JSONObject()
            .put("syntheticObservations",screens.size).put("modelResponses",events.count{it=="model_response"})
            .put("tools",tools).put("model",V04Model.MODEL).put("reasoningEffort","low")
            .put("journalLinked",false).put("permissionsChanged",false).toString())
    }
}
