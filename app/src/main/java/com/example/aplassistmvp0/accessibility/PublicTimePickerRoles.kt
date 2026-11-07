package com.example.aplassistmvp0.accessibility

/** Add public field roles before a capture becomes an immutable snapshot. Never read entered text. */
internal object PublicTimePickerRoles {
    data class Role(val name:String,val node:AccessibilityNodeModel)
    fun roles(all:List<AccessibilityNodeModel>):List<Role> {
        val wheels=all.filter{it.enabled && it.visibleToUser && it.scrollable && !it.password && !it.sensitiveContent &&
            it.className.orEmpty().endsWith("NumberPicker")}
        if(wheels.size !in 2..3 || wheels.map{it.parentPath}.distinct().size!=1)return emptyList()
        val meridiem=wheels.filter{wheel->all.any{child->
            child.path.startsWith(wheel.path+"/") && child.visibleToUser && !child.password && !child.sensitiveContent && !child.editable &&
                listOfNotNull(child.text,child.contentDescription).any{it.trim().lowercase() in setOf("오전","오후","am","pm")}
        }}.singleOrNull()
        val framework=all.any{it.className.orEmpty().endsWith("TimePicker") && wheels.all{w->w.path.startsWith(it.path+"/")}}
        if(!framework && (wheels.size!=3 || meridiem==null))return emptyList()
        val numbers=wheels.filter{it!==meridiem}.sortedBy{it.bounds.left}
        if(numbers.size!=2)return emptyList()
        return listOfNotNull(meridiem?.let{Role("오전/오후",it)},Role("시",numbers[0]),Role("분",numbers[1]))
    }
    fun annotate(all:List<AccessibilityNodeModel>):List<AccessibilityNodeModel> {
        val roles=roles(all).associate{it.node.path to it.name}
        return all.map{node->roles[node.path]?.let{node.copy(text="$it 조절",contentDescription=null,descendantLabels=emptyList())}?:node}
    }
}
