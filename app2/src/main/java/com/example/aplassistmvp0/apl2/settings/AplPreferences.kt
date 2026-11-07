package com.example.aplassistmvp0.apl2.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-facing presentation settings. Connection credentials stay in the separate app-private store. */
data class AplPreferenceValues(
    val voiceEnabled: Boolean = true,
    val speechRate: Float = 1f,
    val largeGuide: Boolean = false,
    val reduceMotion: Boolean = false,
) {
    /** Invalid values must never reach TextToSpeech, including NaN restored from local storage. */
    fun sanitized(): AplPreferenceValues = copy(
        speechRate = if (speechRate.isFinite()) speechRate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) else 1f,
    )

    companion object {
        const val MIN_SPEECH_RATE = .7f
        const val MAX_SPEECH_RATE = 1.3f
    }
}

/** A single process-wide source for Compose screens and accessibility/assistant windows. */
object AplPreferences {
    private const val STORAGE_NAME = "apl2-presentation"
    private var storage: SharedPreferences? = null
    private val mutableState = MutableStateFlow(AplPreferenceValues())
    val state: StateFlow<AplPreferenceValues> = mutableState.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (storage != null) return
        val preferences = context.applicationContext.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
        storage = preferences
        mutableState.value = AplPreferenceValues(
            voiceEnabled = preferences.getBoolean("voice_enabled", true),
            speechRate = preferences.getFloat("speech_rate", 1f),
            largeGuide = preferences.getBoolean("large_guide", false),
            reduceMotion = preferences.getBoolean("reduce_motion", false),
        ).sanitized()
    }

    fun get(context: Context): AplPreferenceValues {
        initialize(context)
        return mutableState.value
    }

    @Synchronized
    fun update(context: Context, transform: (AplPreferenceValues) -> AplPreferenceValues) {
        initialize(context)
        val next = transform(mutableState.value).sanitized()
        if (next == mutableState.value) return
        storage!!.edit()
            .putBoolean("voice_enabled", next.voiceEnabled)
            .putFloat("speech_rate", next.speechRate)
            .putBoolean("large_guide", next.largeGuide)
            .putBoolean("reduce_motion", next.reduceMotion)
            .apply()
        mutableState.value = next
    }

    fun reset(context: Context) = update(context) { AplPreferenceValues() }
}
