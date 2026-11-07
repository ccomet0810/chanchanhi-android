package com.example.aplassistmvp0.assistant

import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import java.lang.ref.WeakReference

/** Android binds this service only when the user selects APL2 as the assistant. */
class AplVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AssistantGuideCoordinator.initialize(this)
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        active = WeakReference(this)
    }

    override fun onShutdown() { clearActive(); super.onShutdown() }
    override fun onDestroy() { clearActive(); super.onDestroy() }
    override fun onLaunchVoiceAssistFromKeyguard() = Unit

    private fun clearActive() { if (active?.get() === this) active = null }

    companion object {
        private var active: WeakReference<AplVoiceInteractionService>? = null

        /** Returns false while Android has not bound the selected assistant; never opens another assistant. */
        fun requestSession(): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) return false
            val service = active?.get() ?: return false
            if (!VoiceInteractionService.isActiveService(service, ComponentName(service, AplVoiceInteractionService::class.java))) return false
            return runCatching {
                service.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            }.getOrDefault(false)
        }
    }
}
