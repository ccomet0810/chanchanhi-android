package dev.ccomet.chanchanhi04.research

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class ResearchPrivacyTest {
    @Test fun renderingFieldsCannotReintroduceInputAtTheUploadBoundary(){
        val original=JSONObject("""{"data":{"nodes":[{"editable":true,"label":"private","display":{"version":1,"text":"private","description":"private"},"bounds":[1,2,3,4]}]}}""")
        val safe=ResearchPrivacy.event(original)
        assertFalse(safe.toString().contains("private"));assertTrue(original.toString().contains("private"))
        assertEquals("[가림]",safe.getJSONObject("data").getJSONArray("nodes").getJSONObject(0).getString("display"))
    }
    @Test fun redactInputsBeforeNetworkWithoutChangingLocalOriginal(){
        val raw="""{"seq":1,"at":1,"kind":"screen_frame","data":{"nodes":[{"editable":true,"label":"private words","hasInput":true,"bounds":[1,2,3,4]}],"token":"private-token","text":"010-1234-5678 test@example.com"}}"""
        val original=JSONObject(raw);val safe=ResearchPrivacy.event(original)
        assertEquals("private words",original.getJSONObject("data").getJSONArray("nodes").getJSONObject(0).getString("label"))
        assertFalse(safe.toString().contains("private words"));assertFalse(safe.toString().contains("private-token"));assertFalse(safe.toString().contains("010-1234"))
        val node=safe.getJSONObject("data").getJSONArray("nodes").getJSONObject(0)
        assertEquals("[1,2,3,4]",node.getJSONArray("bounds").toString());assertTrue(node.getBoolean("hasInput"));assertTrue(safe.getJSONArray("redactions").length()>=3)
    }
    @Test fun readQueueResumesOnlyAfterAcknowledgedRowsAndKeepsFile(){
        val file=File.createTempFile("cc-research-",".jsonl")
        try{
            file.writeText((1..45).joinToString("\n",postfix="\n"){"""{"seq":$it,"at":$it,"kind":"interaction","data":{}}"""}+"{\"seq\":46")
            val before=file.readText();val first=ResearchConnection.readBatch(file,0)
            assertEquals(40,first.size);assertEquals(40,first.last().getInt("seq"))
            val next=ResearchConnection.readBatch(file,40);assertEquals(listOf(41,42,43,44,45),next.map{it.getInt("seq")})
            assertEquals(before,file.readText())
        }finally{file.delete()}
    }
    @Test fun serializedModelOutputIsAlsoRedacted(){
        val safe=ResearchPrivacy.event(JSONObject().put("seq",1).put("data",JSONObject().put("text","""{"instruction":"010-1234-5678에게 연락"}""")))
        assertFalse(safe.toString().contains("010-1234"));assertTrue(safe.getJSONArray("redactions").length()>0)
    }
}
