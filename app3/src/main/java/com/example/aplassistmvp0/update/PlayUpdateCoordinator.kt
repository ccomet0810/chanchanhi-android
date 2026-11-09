package com.example.aplassistmvp0.update

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play 인앱 업데이트를 화면 수명주기에 맞춰 확인합니다.
 * Play 스토어로 설치하지 않은 디버그 빌드에서는 확인이 실패하며, 이때는 아무것도 표시하지 않습니다.
 *
 * Activity 필드 초기화에서 생성해야 합니다(결과 런처는 STARTED 전에 등록되어야 함).
 */
class PlayUpdateCoordinator(private val activity: ComponentActivity) {
    // Activity 생성자 시점에는 Context가 아직 연결되지 않았으므로 처음 사용할 때(onStart 이후) 만든다.
    private val manager by lazy { AppUpdateManagerFactory.create(activity.applicationContext) }
    private val ready = MutableStateFlow(false)
    /** 유연 업데이트 다운로드가 끝나 재시작 설치를 기다리는 중입니다. */
    val readyToInstall: StateFlow<Boolean> = ready.asStateFlow()

    private var flexibleDeclined = false
    private var flowRunning = false
    private var lastMode = AppUpdateMode.NONE

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        flowRunning = false
        // 즉시 업데이트를 닫으면 다음 onResume에서 다시 묻는다. 유연 업데이트 거절은 이 화면에서 다시 묻지 않는다.
        if (result.resultCode != Activity.RESULT_OK && lastMode == AppUpdateMode.FLEXIBLE) flexibleDeclined = true
    }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) ready.value = true
    }

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = manager.registerListener(installListener)
            override fun onResume(owner: LifecycleOwner) = check()
            override fun onStop(owner: LifecycleOwner) = manager.unregisterListener(installListener)
        })
    }

    /** 다운로드된 업데이트를 설치합니다. Play가 앱을 종료하고 새 버전으로 다시 엽니다. */
    fun installDownloaded() { manager.completeUpdate() }

    private fun check() {
        if (flowRunning) return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (activity.isFinishing || flowRunning) return@addOnSuccessListener
            if (info.installStatus() == InstallStatus.DOWNLOADED) { ready.value = true; return@addOnSuccessListener }
            // 즉시 업데이트 도중 앱으로 돌아오면 Play 화면을 다시 연다.
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                lastMode != AppUpdateMode.FLEXIBLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                start(info, AppUpdateMode.IMMEDIATE); return@addOnSuccessListener
            }
            val mode = AppUpdatePolicy.choose(
                updateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE,
                priority = info.updatePriority(),
                flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                flexibleDeclined = flexibleDeclined,
            )
            if (mode != AppUpdateMode.NONE) start(info, mode)
        }.addOnFailureListener {
            // Play 스토어 미설치, 사이드로드 설치, 오프라인 등은 정상적인 사용 환경이다.
            Log.i(TAG, "In-app update check unavailable: ${it.javaClass.simpleName}")
        }
    }

    private fun start(info: AppUpdateInfo, mode: AppUpdateMode) {
        val type = if (mode == AppUpdateMode.IMMEDIATE) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        lastMode = mode
        flowRunning = runCatching {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        }.getOrElse { Log.w(TAG, "In-app update flow could not start", it); false }
    }

    private companion object { const val TAG = "APLUpdate" }
}
