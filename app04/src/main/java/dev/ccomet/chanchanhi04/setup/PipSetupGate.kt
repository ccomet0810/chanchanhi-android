package dev.ccomet.chanchanhi04.setup

enum class PipSetupAction{REQUEST_PERMISSION,WAIT_PERMISSION,ENTER_PIP,UNSUPPORTED}

/** Rechecking/cancelling a Settings screen cannot start another request or skip this gate. */
class PipSetupGate(requested:Boolean=false){
    var requested=requested;private set
    fun next(supported:Boolean,allowed:Boolean):PipSetupAction=when{
        !supported->PipSetupAction.UNSUPPORTED
        allowed->PipSetupAction.ENTER_PIP
        !requested->{requested=true;PipSetupAction.REQUEST_PERMISSION}
        else->PipSetupAction.WAIT_PERMISSION
    }
    fun retry(){requested=false}
}
