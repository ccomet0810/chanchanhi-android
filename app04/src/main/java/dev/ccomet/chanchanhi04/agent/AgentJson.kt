package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds
import org.json.JSONArray
import org.json.JSONObject

object AgentJson {
    private fun array(values: List<*>, rendering: Boolean = false) = JSONArray(values.map { encode(it,rendering) })
    private fun rect(value: NodeBounds) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    fun encode(value: Any?, rendering: Boolean = false): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Enum<*> -> value.name.lowercase()
        is NodeBounds -> rect(value)
        is List<*> -> array(value,rendering)
        is Map<*,*> -> JSONObject().also { out -> value.forEach { (k,v) -> out.put(k.toString(), encode(v,rendering)) } }
        is Element -> JSONObject().put("id", value.id).put("path", value.path).put("resource", encode(value.resource))
            .put("role", value.role).put("label", value.label).put("bounds", rect(value.bounds))
            .put("enabled", value.enabled).put("visible", value.visible).put("clickable", value.clickable)
            .put("editable", value.editable).put("scrollable", value.scrollable).put("hasInput", encode(value.hasInput))
            .put("checked", encode(value.checked)).put("selected", encode(value.selected)).put("state", encode(value.state)).put("value", encode(value.value))
            .also { out -> if(rendering)value.display?.let { d -> out.put("display",JSONObject().put("version",1)
                .put("text",encode(d.text)).put("description",encode(d.description)).put("parentPath",encode(d.parentPath))) } }
        is Observation -> JSONObject().put("snapshotId", value.id).put("packageName", value.packageName)
            .put("windowId", value.windowId).put("epoch", value.epoch).put("visualGeneration", value.visualGeneration)
            .put("width", value.width).put("height", value.height).put("observationComplete", value.complete)
            .put("omittedNodeCount", value.omitted).put("nodes", array(value.elements,rendering))
        is AppEntry -> JSONObject().put("packageName", value.packageName).put("label", value.label)
        is PlanStep -> JSONObject().put("step", value.step).put("status", value.status)
        is ToolCall -> JSONObject().put("callId", value.callId).put("name", encode(value.tool)).put("summary", value.summary)
            .put("snapshotId", encode(value.snapshotId)).put("nodeId", encode(value.nodeId)).put("region", encode(value.region))
            .put("action", encode(value.action)).put("instruction", encode(value.instruction)).put("expectedChange", encode(value.expectedChange))
            .put("question", encode(value.question)).put("options", array(value.options)).put("plan", array(value.plan))
            .put("evidenceNodeIds", array(value.evidenceNodeIds)).put("packageName", encode(value.packageName))
            .put("gesturePath",encode(value.gesturePath))
        is ToolResult -> JSONObject().put("callId", value.callId).put("status", value.status).put("detail", value.detail)
            .put("observation", encode(value.observation,rendering)).put("apps", array(value.apps)).put("answer", encode(value.answer))
        is Exchange -> JSONObject().put("call", encode(value.call)).put("result", encode(value.result,rendering))
        is UserAnswer -> JSONObject().put("question",value.question).put("answer",value.answer)
        else -> error("Unsupported trace type")
    }
    fun request(value: AgentRequest): JSONObject = JSONObject().put("protocol", "chanchanhi/0.4")
        .put("goal", value.goal).put("plan", array(value.plan)).put("screen", encode(value.screen))
        .put("userAnswers",array(value.userAnswers))
        .put("taskContext",encode(value.taskContext))
        .put("history", array(value.history)).put("omittedExchanges", value.omittedExchanges)
        .put("lastModelError",encode(value.lastModelError))
        .put("image", value.image?.let { JSONObject().put("snapshotId", it.observation.id)
            .put("screenWidth",it.observation.width).put("screenHeight",it.observation.height)
            .put("imageWidth",it.imageWidth).put("imageHeight",it.imageHeight) } ?: JSONObject.NULL)
    fun call(value: JSONObject): ToolCall {
        if(value.has("command")) {
            require(value.keys().asSequence().toSet()==setOf("callId","summary","plan","command"))
            val command=value.getJSONObject("command")
            val fields=when(command.getString("name")) {
                "observe","capture_screen","list_apps","update_plan" -> emptySet()
                "open_app" -> setOf("packageName","instruction")
                "guide" -> setOf("snapshotId","nodeId","region","action","instruction","expectedChange")
                "ask_user" -> setOf("question","options")
                "finish" -> setOf("snapshotId","region","evidenceNodeIds","instruction")
                else -> throw IllegalArgumentException("Unsupported command")
            }
            val motionField=if(command.getString("name")=="guide"&&command.has("gesturePath"))setOf("gesturePath")else emptySet()
            require(command.keys().asSequence().toSet()==fields+"name"+motionField)
            val normalized=JSONObject().put("callId",value.getString("callId")).put("summary",value.getString("summary"))
                .put("plan",value.getJSONArray("plan")).put("name",command.getString("name"))
            for(key in listOf("snapshotId","nodeId","region","action","instruction","expectedChange","question","packageName"))
                normalized.put(key,command.opt(key)?:JSONObject.NULL)
            for(key in listOf("options","evidenceNodeIds"))normalized.put(key,command.opt(key)?:JSONArray())
            if(command.has("gesturePath"))normalized.put("gesturePath",command.get("gesturePath"))
            return call(normalized)
        }
        fun str(key: String) = if (value.isNull(key)) null else value.getString(key)
        fun integer(raw:Any,maximum:Double=Int.MAX_VALUE.toDouble()):Long {
            require(raw is Number)
            val number=raw.toDouble()
            require(number.isFinite() && number>=0 && number<=maximum && number==kotlin.math.floor(number))
            return number.toLong()
        }
        fun long(key: String) = if (value.isNull(key)) null else integer(value.get(key),9007199254740991.0)
        fun strings(key: String) = value.getJSONArray(key).let { a -> (0 until a.length()).map(a::getString) }
        val region = if (value.isNull("region")) null else value.getJSONArray("region").let {
            require(it.length() == 4); NodeBounds(integer(it.get(0)).toInt(),integer(it.get(1)).toInt(),integer(it.get(2)).toInt(),integer(it.get(3)).toInt()) }
        val path=if(!value.has("gesturePath")||value.isNull("gesturePath"))null else value.getJSONArray("gesturePath").let{a->
            require(a.length()==4);(0 until 4).map{integer(a.get(it),1000.0).toInt()}}
        return ToolCall(value.getString("callId"), Tool.valueOf(value.getString("name").uppercase()), value.getString("summary"),
            long("snapshotId"), if(value.isNull("nodeId"))null else integer(value.get("nodeId")).toInt(), region, str("action")?.let { Action.valueOf(it.uppercase()) },
            str("instruction"), str("expectedChange"), str("question"), strings("options"),
            value.getJSONArray("plan").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> PlanStep(p.getString("step"),p.getString("status")) } } },
            value.getJSONArray("evidenceNodeIds").let { a -> (0 until a.length()).map{integer(a.get(it)).toInt()} }, str("packageName"),path)
    }
}
