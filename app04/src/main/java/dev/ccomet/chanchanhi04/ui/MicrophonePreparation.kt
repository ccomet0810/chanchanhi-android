package dev.ccomet.chanchanhi04.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.setup.MicrophonePermissionStatus

@Composable fun MicrophonePreparationPage(
    status:MicrophonePermissionStatus,onRequest:()->Unit,onSettings:()->Unit,
    onContinue:()->Unit,onBack:()->Unit,notice:String="",requestEnabled:Boolean=true,layout:PageLayout=PageLayout.ONBOARDING
) {
    BackHandler(status!=MicrophonePermissionStatus.PENDING,onBack)
    val ready=status==MicrophonePermissionStatus.READY
    PreparationPage(layout,"마이크",onBack=if(status==MicrophonePermissionStatus.PENDING)null else onBack,actions={
            val label=when(status){
                MicrophonePermissionStatus.READY->if(layout==PageLayout.ONBOARDING)"다음"else"확인"
                MicrophonePermissionStatus.PENDING->"허용 여부 확인 중"
                MicrophonePermissionStatus.SETTINGS->"앱 권한 확인하기"
                MicrophonePermissionStatus.RETRY->"마이크 다시 허용하기"
                else->"마이크 허용하기"
            }
            val action=when(status){
                MicrophonePermissionStatus.READY->onContinue
                MicrophonePermissionStatus.SETTINGS->onSettings
                else->onRequest
            }
            val enabled=status!=MicrophonePermissionStatus.PENDING&&(ready||requestEnabled)
            if(layout==PageLayout.ONBOARDING)JourneyActions(label,action,enabled,onPrevious=onBack,previousEnabled=status!=MicrophonePermissionStatus.PENDING)
            else LargeAction(label,if(ready)Glyph.CHECK else Glyph.MIC,action,enabled=enabled)
    }) {
        if(ready)SetupSuccess(Modifier.weight(1f),title="마이크가 켜졌어요")
        else Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Foundation.SectionGapDp.dp)) {
            PageHeading("마이크를 허용해주세요",when(status){
                MicrophonePermissionStatus.SETTINGS->"앱 권한에서 마이크를 허용해주세요."
                MicrophonePermissionStatus.RETRY->"마이크 허용을 다시 눌러주세요."
                else->"하고 싶은 일을 말로 요청할 때 사용해요."
            })
            MicrophonePreparationContent(status,Modifier.weight(1f))
            if(notice.isNotBlank())Text(notice,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable fun MicrophonePreparationContent(status:MicrophonePermissionStatus,modifier:Modifier=Modifier){
    if(status==MicrophonePermissionStatus.SETTINGS)Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Foundation.SectionGapDp.dp)){
        Text("앱 권한에서 마이크를 허용해주세요.",style=MaterialTheme.typography.bodyLarge)
    }else MicrophonePermissionPreview(modifier)
}
