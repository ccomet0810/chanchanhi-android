package dev.ccomet.chanchanhi04.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

enum class MicrophonePermissionStatus { AVAILABLE, RETRY, SETTINGS, PENDING, READY }

/** Check at the user action boundary, not only when drawing a preparation screen. */
object MicrophoneLaunchGate {
    fun allowed(screenGranted:Boolean,serviceConnected:Boolean,resumed:Boolean,setupHelpActive:Boolean=false)=
        screenGranted&&serviceConnected&&resumed&&!setupHelpActive
}

/** A prior request alone does not mean permanent denial: cancellation and revocation are possible. */
data class MicrophonePermissionState(
    val granted:Boolean=false,
    val requested:Boolean=false,
    val deniedWithoutRationale:Boolean=false,
    val pending:Boolean=false
) {
    val status get()=when {
        granted->MicrophonePermissionStatus.READY
        pending->MicrophonePermissionStatus.PENDING
        deniedWithoutRationale->MicrophonePermissionStatus.SETTINGS
        requested->MicrophonePermissionStatus.RETRY
        else->MicrophonePermissionStatus.AVAILABLE
    }
    val canRequest get()=status in setOf(MicrophonePermissionStatus.AVAILABLE,MicrophonePermissionStatus.RETRY)
}

/** The saved in-flight bit belongs to the Activity Result request across recreation. */
class MicrophoneRequestGate(restoredPending:Boolean=false) {
    var pending=restoredPending;private set
    fun begin(observed:MicrophonePermissionState):Boolean {
        if(pending||!observed.canRequest)return false
        pending=true
        return true
    }
    fun finish() { pending=false }
}

/** Permission preparation only. This controller never creates a recorder or starts voice input. */
class MicrophonePermission(private val activity:ComponentActivity,savedState:Bundle?) {
    private val preferences=activity.getSharedPreferences("microphone-preparation-1",0)
    private val gate=MicrophoneRequestGate(savedState?.getBoolean(STATE_PENDING)==true)
    var state by mutableStateOf(MicrophonePermissionState());private set
    val prepared get()=preferences.getBoolean("prepared",false)
    private val launcher=activity.activityResultRegistry.register(
        "microphone-preparation",activity,ActivityResultContracts.RequestPermission()
    ) {
        gate.finish()
        // The actual OS permission is the source of truth, including Settings returns.
        val granted=hasPermission()
        preferences.edit().putBoolean("denied-without-rationale",!granted&&!activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)).commit()
        refresh()
    }
    init { refresh() }
    fun refresh() {
        val granted=hasPermission()
        if(granted)preferences.edit().putBoolean("denied-without-rationale",false).apply()
        state=MicrophonePermissionState(granted,preferences.getBoolean("requested",false),
            preferences.getBoolean("denied-without-rationale",false),gate.pending)
    }
    /** Called only by an explicit user action, never by resume, recomposition or a grant callback. */
    fun request() {
        refresh()
        if(!gate.begin(state))return
        preferences.edit().putBoolean("requested",true).commit()
        refresh()
        try { launcher.launch(Manifest.permission.RECORD_AUDIO) }
        catch(_:IllegalStateException) { gate.finish();refresh() }
    }
    fun openSettings():Boolean {
        refresh()
        if(state.status!=MicrophonePermissionStatus.SETTINGS)return false
        return runCatching {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${activity.packageName}")))
        }.isSuccess
    }
    fun continueWithoutRecording() { preferences.edit().putBoolean("prepared",true).commit() }
    fun save(outState:Bundle) { outState.putBoolean(STATE_PENDING,gate.pending) }
    private fun hasPermission()=ContextCompat.checkSelfPermission(activity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    companion object {
        private const val STATE_PENDING="microphone-permission-pending"
        /** Other entry points may open the preparation page; this never launches a permission dialog. */
        const val EXTRA_PREPARE="dev.ccomet.chanchanhi04.PREPARE_MICROPHONE"
    }
}
