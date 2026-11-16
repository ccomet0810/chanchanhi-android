package dev.ccomet.chanchanhi04.agent

import org.json.JSONArray
import org.json.JSONObject

data class ModelPacket(val bytes:ByteArray,val context:JSONObject,val visibleNodeIds:Set<Int>,val complete:Boolean,val buildPasses:Int=1)

/** Budget actual encoded bytes, including the image, schema and escaped context. Never drop user facts. */
object ModelPackets {
    fun build(request:AgentRequest,schema:JSONObject,instructions:String,model:String,formatName:String="guidance_turn_v04"):ModelPacket {
        var passes=0
        fun encode(current:AgentRequest):ModelPacket? {
            passes++
            val context=AgentJson.request(current)
            val encoded=context.toString()
            val content:Any=current.image?.let {image->JSONArray().put(JSONObject().put("type","input_text").put("text",encoded))
                .put(JSONObject().put("type","input_image").put("detail","high").put("image_url","data:${image.mime};base64,${image.base64}"))} ?: encoded
            val payload=JSONObject().put("model",model).put("store",false).put("stream",true).put("service_tier","default")
                .put("reasoning",JSONObject().put("effort","low")).put("instructions",instructions)
                .put("input",JSONArray().put(JSONObject().put("role","user").put("content",content)))
                .put("text",JSONObject().put("format",JSONObject().put("type","json_schema").put("name",formatName)
                    .put("strict",true).put("schema",schema)))
            val bytes=payload.toString().toByteArray(Charsets.UTF_8)
            return if(encoded.length<=58000 && bytes.size<=250000) ModelPacket(bytes,context,current.screen.elements.map{it.id}.toSet(),current.screen.complete,passes) else null
        }
        encode(request)?.let{return it}
        val withoutHistory=request.copy(history=emptyList(),omittedExchanges=request.omittedExchanges+request.history.size)
        val fullScreen=encode(withoutHistory)
        if(fullScreen!=null) {
            var best:ModelPacket=fullScreen;var low=1;var high=request.history.size-1
            while(low<=high){val keep=(low+high)/2
                val candidate=encode(request.copy(history=request.history.takeLast(keep),omittedExchanges=request.omittedExchanges+request.history.size-keep))
                if(candidate!=null){best=candidate;low=keep+1}else high=keep-1
            }
            return best.copy(buildPasses=passes)
        }
        fun withNodes(keep:Int)=withoutHistory.copy(screen=request.screen.copy(elements=request.screen.elements.take(keep),
            complete=false,omitted=request.screen.omitted+request.screen.elements.size-keep))
        var low=1;var high=request.screen.elements.size-1;var best:ModelPacket?=null
        while(low<=high){val keep=(low+high)/2;val candidate=encode(withNodes(keep))
            if(candidate!=null){best=candidate;low=keep+1}else high=keep-1
        }
        return best?.copy(buildPasses=passes) ?: throw IllegalStateException("목표와 답변 정보가 너무 길어요. 새 요청으로 나누어 안내해주세요.")
    }
}
