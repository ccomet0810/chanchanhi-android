package com.example.aplassistmvp0.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** 세션은 시스템의 BIND_VOICE_INTERACTION 권한을 가진 호출로만 생성됩니다. */
class AplVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        // 프로세스가 재생성될 때 기본 서비스의 onReady가 다시 오지 않아도 새 세션을 초기화합니다.
        // Coordinator는 앱 Context만 저장하며 Guide 초기화는 반복 호출해도 한 번만 수행됩니다.
        AssistantGuideCoordinator.initialize(this)
        return AplVoiceInteractionSession(this)
    }
}
