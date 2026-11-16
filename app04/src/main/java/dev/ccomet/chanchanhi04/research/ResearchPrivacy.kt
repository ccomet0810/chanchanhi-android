package dev.ccomet.chanchanhi04.research

import org.json.JSONArray
import org.json.JSONObject

/** Redacts a copy at the upload boundary. Local append-only files are never rewritten. */
object ResearchPrivacy {
    private val sensitive=Regex("^(authorization|token|api_?key|password|secret|jpegbase64|base64|audio|image_url|inputValue)$",RegexOption.IGNORE_CASE)
    private val identifiers=listOf(Regex("\\b(?:sk-[a-zA-Z0-9_-]{12,}|Bearer\\s+[a-zA-Z0-9_.-]{10,})\\b"),Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),Regex("(?<!\\d)(?:\\+82[- ]?|0)1[016789][- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"))
    fun event(original:JSONObject):JSONObject {
        val paths=sortedSetOf<String>()
        fun visit(value:Any?,path:String,key:String="",parent:JSONObject?=null):Any {
            if(sensitive.matches(key)||(parent?.optBoolean("editable")==true&&key in setOf("label","value","text","state","display"))){paths.add(path);return "[가림]"}
            return when(value){
                is JSONObject->JSONObject().also{out->value.keys().forEach{k->if(k!="redactions")out.put(k,visit(value.opt(k),if(path.isEmpty())k else "$path.$k",k,value))}}
                is JSONArray->JSONArray().also{out->for(i in 0 until value.length())out.put(visit(value.opt(i),"$path[$i]"))}
                is String->{
                    if(key=="text"&&value.trimStart().startsWith("{"))runCatching{visit(JSONObject(value),"$path.json").toString()}.getOrNull()?.let{return it}
                    val masked=identifiers.fold(value){v,pattern->pattern.replace(v,"[가림]")};if(masked!=value)paths.add(path);masked
                }
                null->JSONObject.NULL
                else->value
            }
        }
        return (visit(original,"") as JSONObject).put("redactions",JSONArray(paths.toList()))
    }
}
