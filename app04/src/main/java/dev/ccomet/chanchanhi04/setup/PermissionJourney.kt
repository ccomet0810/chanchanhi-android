package dev.ccomet.chanchanhi04.setup

enum class PermissionStage { PIP, ACCESSIBILITY, MICROPHONE, COMPLETE }

/** Recomputed from OS grants and a real service binding; opening Settings never completes a step. */
data class PermissionJourney(val stage:PermissionStage) {
    val complete get()=stage==PermissionStage.COMPLETE
    val current get()=when(stage){PermissionStage.PIP->1;PermissionStage.ACCESSIBILITY->2;else->3}
    val completed get()=when(stage){PermissionStage.PIP->0;PermissionStage.ACCESSIBILITY->1;PermissionStage.MICROPHONE->2;PermissionStage.COMPLETE->3}
    companion object {
        fun observe(pipRequired:Boolean,pipAllowed:Boolean,screenGranted:Boolean,screenConnected:Boolean,microphone:MicrophonePermissionStatus)=PermissionJourney(when {
            pipRequired&&!pipAllowed->PermissionStage.PIP
            !screenGranted||!screenConnected->PermissionStage.ACCESSIBILITY
            microphone!=MicrophonePermissionStatus.READY->PermissionStage.MICROPHONE
            else->PermissionStage.COMPLETE
        })
    }
}
