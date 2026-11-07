package com.example.aplassistmvp0.v3

internal enum class ValueProperty { RANGE, CHECKED, SELECTED, INPUT_PRESENT }
/** Stable public identity; a mutable value label is never combined with a resource identity. */
internal data class ValueSubject(val role:String,val resource:String,val label:String)
internal data class ValueExpectation(val property:ValueProperty,val subject:ValueSubject,
    val expectedNumber:Double?=null,val expectedBoolean:Boolean?=null) {
    fun canonical()=expectedNumber?.let{if(it==0.0)copy(expectedNumber=0.0)else this}?:this
    fun valid()=subject.role.isNotBlank() && subject.role.length<=140 && subject.resource.length<=180 && subject.label.length<=140 &&
        (if(subject.resource.isEmpty())subject.label.isNotBlank()else subject.resource.isNotBlank() && subject.label.isEmpty()) &&
        (if(property==ValueProperty.RANGE)expectedNumber?.isFinite()==true && expectedBoolean==null
            else expectedNumber==null && expectedBoolean!=null)
}
