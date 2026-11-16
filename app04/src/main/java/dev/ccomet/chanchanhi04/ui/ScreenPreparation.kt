package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.setup.*
import dev.ccomet.chanchanhi04.ScreenSetupHelp
import dev.ccomet.chanchanhi04.platform.AppRuntime

/** AppOps is not Compose state; observe the return from its native Settings screen. */
@Composable internal fun rememberPipAllowed():Boolean{
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var allowed by remember(context){mutableStateOf(ScreenSetupHelp.allowed(context))}
    DisposableEffect(context,owner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)allowed=ScreenSetupHelp.allowed(context)}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    return allowed
}

@Composable internal fun rememberScreenSetupPresentation(granted:Boolean,connecting:Boolean=false):ScreenSetupPresentation {
    val allowed=rememberPipAllowed()
    val flow by AppRuntime.setup.state.collectAsState()
    val attempted=flow.phase!=SetupPhase.IDLE&&SetupRequirement.SCREEN in flow.requirements
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var timedOut by remember(connecting){mutableStateOf(false)}
    LaunchedEffect(connecting,lifecycle){
        if(!connecting){timedOut=false;return@LaunchedEffect}
        if(lifecycle.isAtLeast(Lifecycle.State.RESUMED)){
            kotlinx.coroutines.delay(ScreenConnectionWaitMs);timedOut=true
        }
    }
    return screenSetupPresentation(granted||connecting,granted,attempted,allowed,connectionPaused=flow.phase==SetupPhase.PAUSED,connectionTimedOut=timedOut)
}

@Composable fun ScreenSetupStatus(state:ScreenSetupPresentation){
    if(state.message.isNotBlank())Text(state.message,Modifier.fillMaxWidth().padding(bottom=Foundation.GapDp.dp),
        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Keep this same lesson visible while the real PiP is opened alongside it. */
@Composable fun ScreenPreparationContent(granted:Boolean,connecting:Boolean=false,modifier:Modifier=Modifier,showHeading:Boolean=true){
    val context=LocalContext.current
    val direct=remember(context){SetupPermissions.opensServiceDetails(context)}
    val supported=remember(context){ScreenSetupHelp.supported(context)}
    val allowed=rememberPipAllowed()
    val needsPip=supported&&!allowed
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val connectionCycle by AppRuntime.screenConnectionCycle.collectAsState()
    var previewCycle by rememberSaveable{mutableStateOf<String?>(null)}
    LaunchedEffect(allowed,granted,connecting,lifecycle,connectionCycle){
        // A completed-then-revoked permission starts a new preview cycle, including when
        // both transitions happened while Main was stopped. Saved process state is safe too.
        if(supported&&allowed&&!granted&&!connecting&&previewCycle!=connectionCycle&&lifecycle.isAtLeast(Lifecycle.State.RESUMED)){
            previewCycle=connectionCycle
            ScreenSetupHelp.showChecklist(context)
        }
    }
    DisposableEffect(Unit){onDispose{ScreenSetupHelp.dismissPreview()}}
    if(!granted){
        val heading:@Composable ()->Unit={if(showHeading)PageHeading("앱 권한 설정하기","작은 창 사용법과 접근성 설정하는 법을 알려드릴게요.")}
        if(needsPip)ScreenSetupPreview(direct,modifier,heading=heading,pipPermission=true)
        else PermissionLessonPreview(PermissionLesson.accessibility(),modifier,heading=heading)
        return
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(Foundation.SectionGapDp.dp)){
        if(granted)SetupSuccess(Modifier.weight(1f,fill=false).fillMaxWidth())
        else if(connecting)Text("화면 안내에 연결하고 있어요.",style=MaterialTheme.typography.bodyLarge)
    }
}
