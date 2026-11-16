package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds

/** A click can trigger relayout before Android supplies its source bounds. Re-observation still verifies success. */
object InteractionBinding {
    private fun normal(value:String)=value.trim().replace(Regex("\\s+")," ")
    fun resolve(observation:Observation?,packageName:String,windowId:Int,resource:String?,role:String?,bounds:NodeBounds,publicLabel:String?):Element? {
        if(observation==null||observation.packageName!=packageName||observation.windowId!=windowId)return null
        val candidates=observation.elements.filter{it.visible&&it.enabled}
        candidates.filter{it.resource==resource&&it.bounds==bounds}.singleOrNull()?.let{return it}
        if(role.isNullOrBlank())return null
        if(resource!=null)candidates.filter{it.resource==resource&&it.role==role}.singleOrNull()?.let{return it}
        val label=publicLabel?.let(::normal)?.takeIf{it.isNotEmpty()}?:return null
        // Never use user-entered values, generic role fallbacks, or duplicated text to bind an action.
        if(label==role||label.startsWith("$role #"))return null
        return candidates.filter{!it.editable&&it.role==role&&normal(it.label)==label}.singleOrNull()
    }
}
