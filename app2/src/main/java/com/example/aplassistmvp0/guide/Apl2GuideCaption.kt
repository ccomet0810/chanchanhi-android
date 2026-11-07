package com.example.aplassistmvp0.guide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuideCaptionState(val message: String = "", val collapsed: Boolean = false, val speaking: Boolean = false)

/** APL2 keeps the instruction visible after speech ends, until the user acts or ends guidance. */
object GuideCaption {
    private val mutableState = MutableStateFlow(GuideCaptionState())
    val state = mutableState.asStateFlow()
    private var active: String? = null

    internal fun message(value: String) {
        if (mutableState.value.message == value) return
        cancelSpeech()
        mutableState.value = GuideCaptionState(value)
    }
    internal fun speechStarted(id: String, message: String) {
        cancelSpeech()
        active = id
        mutableState.value = GuideCaptionState(message, speaking = true)
    }
    internal fun speechDone(id: String) { if (active == id) cancelSpeech() }
    internal fun speechFailed(id: String) {
        if (active == id) { cancelSpeech(); mutableState.value = mutableState.value.copy(collapsed = false) }
    }
    internal fun cancelSpeech() {
        active = null
        if (mutableState.value.speaking) mutableState.value = mutableState.value.copy(speaking = false)
    }
    fun toggle() { cancelSpeech(); mutableState.value = mutableState.value.copy(collapsed = !mutableState.value.collapsed) }
}
