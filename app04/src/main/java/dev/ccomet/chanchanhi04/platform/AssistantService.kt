package dev.ccomet.chanchanhi04.platform

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.ccomet.chanchanhi04.MainActivity
import dev.ccomet.chanchanhi04.VoiceInputActivity

class AssistantService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); AppRuntime.initialize(this); setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT) }
    override fun onLaunchVoiceAssistFromKeyguard() = Unit
}
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}
private class AssistantSession(private val context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args,showFlags)
        setUiEnabled(false)
        if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            AppRuntime.initialize(context)
            val studyCheck=AppRuntime.recordStudyInvocation(showFlags)
            if(!studyCheck&&!AppRuntime.voiceAllowed()){
                startAssistantActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));hide();return
            }
            // A cold process may receive the assistant call before its accessibility service rebinds.
            val configured=Settings.Secure.getInt(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==1 &&
                Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    .split(':').mapNotNull(ComponentName::unflattenFromString).contains(ComponentName(context,ScreenService::class.java))
            if(AppRuntime.service != null || configured) startAssistantActivity(Intent(context,VoiceInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            else startAssistantActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }
}
