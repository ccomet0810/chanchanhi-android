package dev.ccomet.chanchanhi04.study

import org.json.JSONObject
import org.json.JSONArray

/** View navigation preserves journal order; earlier answers are saved as explicit revisions. */
object StudyNavigation{
    /** Keep versioned research protocols intact; only merge the presentation of permission pages. */
    fun onboarding(template:JSONObject):JSONArray=JSONArray().apply {
        val original=template.getJSONArray("onboarding")
        repeat(original.length()){val page=original.getJSONObject(it);if(page.optString("id")!="microphone")put(page)}
    }
    fun permissionIndex(template:JSONObject):Int=onboarding(template).let{pages->
        (0 until pages.length()).firstOrNull{pages.getJSONObject(it).optString("id")=="setup"}?:0
    }
    fun introIndex(draft:JSONObject,template:JSONObject):Int {
        val original=template.getJSONArray("onboarding");val visible=onboarding(template)
        val saved=draft.optInt("_index",0).coerceIn(0,original.length())
        if(saved==original.length())return visible.length()
        val id=original.getJSONObject(saved).optString("id").let{if(it=="microphone")"setup"else it}
        return (0 until visible.length()).firstOrNull{visible.getJSONObject(it).optString("id")==id}?:0
    }
    /** Persist the original protocol cursor so existing drafts and older clients remain readable. */
    fun introDraftIndex(index:Int,template:JSONObject):Int {
        val original=template.getJSONArray("onboarding");val visible=onboarding(template)
        if(index>=visible.length())return original.length()
        val id=visible.getJSONObject(index.coerceAtLeast(0)).optString("id")
        return (0 until original.length()).first{original.getJSONObject(it).optString("id")==id}
    }
    private val order=listOf(StudyPhase.INTRO,StudyPhase.PROFILE,StudyPhase.BASELINE,StudyPhase.MISSION_PRE,StudyPhase.TASK,StudyPhase.POST,StudyPhase.COMPLETE)
    fun previous(phase:StudyPhase)=order.indexOf(phase).takeIf{it>0}?.let{order[it-1]}
    fun next(phase:StudyPhase)=order.indexOf(phase).takeIf{it>=0&&it<order.lastIndex}?.let{order[it+1]}
    fun pages(phase:StudyPhase,template:JSONObject,preset:Boolean)=when(phase){
        StudyPhase.INTRO->onboarding(template).length()+1
        StudyPhase.PROFILE->4
        StudyPhase.BASELINE->template.getJSONArray("baseline").length()
        StudyPhase.MISSION_PRE->if(!preset)1 else if(template.getJSONObject("mission").optString("preparation")=="knowledge-confidence")2 else 3
        StudyPhase.TASK->2
        StudyPhase.POST->template.getJSONArray("post").length()
        else->1
    }
}
