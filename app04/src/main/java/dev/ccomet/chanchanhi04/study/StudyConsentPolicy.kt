package dev.ccomet.chanchanhi04.study

import org.json.JSONObject

/** Bind consent at session start. Revocation also stops old sessions; later opt-in never
 * backfills a session that began, or continued, without image consent. */
object StudyConsentPolicy{
    fun sessionBinding(events:List<JSONObject>,sessionId:String):JSONObject?{
        val link=events.firstOrNull{it.optString("kind")=="guidance_linked"&&it.optJSONObject("data")?.optString("sessionId")==sessionId}?:return null
        val consent=JSONObject(events.first().getJSONObject("data").toString())
        val linkedAt=link.getInt("seq")
        var records=consent.getBoolean("records");var captures=consent.getBoolean("captures")
        events.filter{it.optString("kind")=="consent_revised"}.forEach{event->val data=event.getJSONObject("data")
            if(event.getInt("seq")<linkedAt){records=data.getBoolean("records");captures=data.getBoolean("captures")}
            else{records=records&&data.getBoolean("records");captures=captures&&data.getBoolean("captures")}
        }
        return consent.put("records",records).put("captures",records&&captures)
    }
}
