package dev.ccomet.chanchanhi04.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.setup.*

/** One onboarding shell owns every permission step and the single combined completion. */
@Composable fun PermissionsOnboardingPage(
    journey:PermissionJourney,screenGranted:Boolean,screenConnected:Boolean,
    microphone:MicrophonePermissionStatus,screenUi:ScreenSetupPresentation,
    onSettings:()->Unit,onScreenSetup:()->Unit,onRequestMicrophone:()->Unit,onMicrophoneSettings:()->Unit,
    onComplete:()->Unit,onPrevious:()->Unit,microphoneRequestEnabled:Boolean=true,notice:String=""
){
    val pending=journey.stage==PermissionStage.MICROPHONE&&microphone==MicrophonePermissionStatus.PENDING
    BackHandler(!pending,onPrevious)
    val button=when(journey.stage){
        PermissionStage.PIP->"작은 창 허용하기"
        PermissionStage.ACCESSIBILITY->screenUi.action
        PermissionStage.MICROPHONE->when(microphone){
            MicrophonePermissionStatus.PENDING->"허용 여부 확인 중"
            MicrophonePermissionStatus.SETTINGS->"앱 권한 확인하기"
            MicrophonePermissionStatus.RETRY->"마이크 다시 허용하기"
            else->"마이크 허용하기"
        }
        PermissionStage.COMPLETE->"다음"
    }
    val action=when(journey.stage){
        PermissionStage.PIP,PermissionStage.ACCESSIBILITY->onScreenSetup
        PermissionStage.MICROPHONE->if(microphone==MicrophonePermissionStatus.SETTINGS)onMicrophoneSettings else onRequestMicrophone
        PermissionStage.COMPLETE->onComplete
    }
    val enabled=when(journey.stage){
        PermissionStage.PIP->true
        PermissionStage.ACCESSIBILITY->!screenUi.waiting
        PermissionStage.MICROPHONE->!pending&&microphoneRequestEnabled
        PermissionStage.COMPLETE->true
    }
    OnboardingPage(onSettings=onSettings,actions={Column{
        if(journey.stage==PermissionStage.ACCESSIBILITY)ScreenSetupStatus(screenUi)
        if(notice.isNotBlank())Text(notice,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
        JourneyActions(button,action,enabled,onPrevious=onPrevious,previousEnabled=!pending)
    }}){
        JourneyProgress("권한 설정",journey.current,3,journey.completed)
        if(!journey.complete){PageHeading("앱 권한 설정하기",when(journey.stage){
            PermissionStage.PIP->"작은 창 사용을 허용해주세요."
            PermissionStage.ACCESSIBILITY->"작은 창 사용법과 접근성 설정하는 법을 알려드릴게요."
            PermissionStage.MICROPHONE->"하고 싶은 일을 말로 요청할 수 있도록 마이크를 허용해주세요."
            PermissionStage.COMPLETE->null
        })
        Spacer(Modifier.height(Foundation.SectionGapDp.dp))}
        when(journey.stage){
            PermissionStage.PIP,PermissionStage.ACCESSIBILITY->ScreenPreparationContent(
                false,connecting=journey.stage==PermissionStage.ACCESSIBILITY&&screenGranted&&!screenConnected,
                modifier=Modifier.weight(1f),showHeading=false)
            PermissionStage.MICROPHONE->MicrophonePreparationContent(microphone,Modifier.weight(1f))
            PermissionStage.COMPLETE->SetupSuccess(Modifier.weight(1f).fillMaxWidth(),title="설정을 모두 마쳤어요")
        }
    }
}
