package com.example.aplassistmvp0.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.speech.RecognitionService

/** 기본 역할이 APL의 인식 서비스로 바뀌어도 자기 서비스로 재귀 연결하지 않습니다. */
internal object SpeechProviderResolver {
    @Suppress("DEPRECATION")
    fun externalProvider(context: Context): ComponentName? {
        val candidates = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName && it.exported && it.enabled && it.applicationInfo.enabled &&
                it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                (it.permission == null || context.checkSelfPermission(it.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        // 사용자가 고른 외부 기본 서비스가 유효하면 우선합니다. 읽은 설정값은 기록하지 않습니다.
        val preferred = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                ?.let(ComponentName::unflattenFromString)
        }.getOrNull()
        candidates.firstOrNull { ComponentName(it.packageName, it.name) == preferred }?.let {
            return ComponentName(it.packageName, it.name)
        }
        // 기본 값이 자기 서비스라면 기기에 설치된 시스템 음성 엔진만 대체 후보로 허용합니다.
        return candidates.sortedWith(compareBy({ it.packageName }, { it.name })).firstOrNull()
            ?.let { ComponentName(it.packageName, it.name) }
    }
}
