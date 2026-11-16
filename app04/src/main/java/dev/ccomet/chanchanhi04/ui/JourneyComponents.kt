package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.setup.*

/** One icon, one accessible name; reusable across onboarding and setup. */
@Composable fun JourneyHeader(title:String,onSettings:(()->Unit)?=null){
    AppHeader(title,onSettings=onSettings)
}
/** Previous hugs its label; next owns the remaining width. Both share the action baseline. */
@Composable fun JourneyActions(next:String,onNext:()->Unit,enabled:Boolean=true,onPrevious:(()->Unit)?=null,previousEnabled:Boolean=true){
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
        if(onPrevious!=null)TextButton(onPrevious,Modifier.fillMaxHeight().heightIn(min=Foundation.TouchDp.dp),enabled=previousEnabled,contentPadding=PaddingValues(Foundation.ActionHorizontalPaddingDp.dp,Foundation.ActionVerticalPaddingDp.dp)){Text("이전")}
        LargeAction(next,Glyph.NEXT,onNext,Modifier.weight(1f).fillMaxHeight(),enabled=enabled)
    }
}
@Composable fun SetupChecklist(snapshot:SetupSnapshot,onSetup:(SetupRequirement)->Unit,requirements:Set<SetupRequirement> = SetupRequirement.entries.toSet()){
    Column(verticalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
        requirements.forEach{requirement->SettingsAction(requirement.title,null,when(requirement){SetupRequirement.SCREEN->Glyph.SHIELD;SetupRequirement.MICROPHONE->Glyph.MIC;SetupRequirement.ASSISTANT->Glyph.ASSISTANT},enabled=!snapshot.granted(requirement)){onSetup(requirement)}}
    }
}

/** Each first-use screen names the real permission, destination and action. */
@Composable fun SetupInstructions(requirement:SetupRequirement,serviceName:String,appName:String,directDetails:Boolean=false,showTitle:Boolean=true){
    val steps=when(requirement){
        SetupRequirement.SCREEN->(if(directDetails)emptyList()else listOf("설치된 앱 또는 다운로드한 앱을 눌러주세요."))+listOf("$serviceName 항목을 눌러 켜주세요.","확인 창에서 허용을 눌러주세요.")
        SetupRequirement.MICROPHONE->listOf("$appName 앱의 마이크 사용을 허용해주세요.","확인 창에서 앱 사용 중에만 허용을 눌러주세요. 이미 거절한 경우에는 휴대폰의 앱 권한 설정에서 바꿀 수 있어요.")
        SetupRequirement.ASSISTANT->listOf("기본 디지털 도우미 앱을 $appName 앱으로 바꿔주세요.","설정을 열면 찬찬히가 누를 곳을 안내해요.")
    }
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)){
        if(showTitle)Text(when(requirement){SetupRequirement.SCREEN->"화면 안내를 켜주세요";SetupRequirement.MICROPHONE->"마이크를 허용해주세요";SetupRequirement.ASSISTANT->"찬찬히를 도우미로 선택해주세요"},style=MaterialTheme.typography.titleLarge)
        if(requirement==SetupRequirement.SCREEN){
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text("다음 화면에서",style=MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Text(serviceName,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        // Illustration only. It must not pretend to grant accessibility inside our app.
                        Switch(checked=true,onCheckedChange=null,modifier=Modifier.semantics{contentDescription="켜진 상태 예시"})
                    }
                }
            }
        }
        steps.forEachIndexed{index,text->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Text("${index+1}",style=MaterialTheme.typography.titleMedium)
            Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        }}
        Text(if(requirement==SetupRequirement.SCREEN)"켜지면 찬찬히로 돌아와 다음 과정을 이어가요."else"허용이 확인되면 다음 설정으로 넘어가요.",style=MaterialTheme.typography.bodyMedium)
    }
}

/** Illustration-only brand colors belong to the scenario, never to product actions. */
private object ScenarioColors{
    val Chat=Color(0xFFB2C7D9);val Tile=Color(0xFF969696);val Link=Color(0xFF186FC4)
}
/** Reconstruct the supplied photo sheet and permission modal with public text only. */
@Composable fun KakaoPermissionScene(){
    Surface(shape=MaterialTheme.shapes.large,color=ScenarioColors.Chat,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Box(Modifier.fillMaxWidth().semantics(mergeDescendants=true){contentDescription="카카오톡 사진 권한 오류 상황 예시"}){
            Column(verticalArrangement=Arrangement.spacedBy(24.dp)){
                Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    Text("친구",style=MaterialTheme.typography.titleMedium)
                    Text("상황 예시",style=MaterialTheme.typography.bodySmall)
                }
                Surface(shape=MaterialTheme.shapes.large,color=Color.White){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("최근 사진",style=MaterialTheme.typography.titleMedium);Text("전송",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically){
                        Surface(shape=MaterialTheme.shapes.large,color=ScenarioColors.Tile){Box(Modifier.size(88.dp),contentAlignment=Alignment.Center){AppGlyph(Glyph.CAMERA,Modifier.size(40.dp),Color.White)}}
                        Column(horizontalAlignment=Alignment.CenterHorizontally){AppGlyph(Glyph.NEXT,color=MaterialTheme.colorScheme.onSurface);Text("전체보기",style=MaterialTheme.typography.bodyMedium)}
                    }
                    Text("사진 접근 권한이 없습니다. 설정에서 다시 권한을 허용해주세요.",style=MaterialTheme.typography.bodyMedium)
                    Text("권한 설정",color=ScenarioColors.Link,style=MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){AppGlyph(Glyph.PHOTOS);Text("사진")}
                }}
            }
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.25f)))
            Surface(Modifier.align(Alignment.Center).padding(20.dp),shape=MaterialTheme.shapes.large,color=Color.White,shadowElevation=4.dp){
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
                    Text("접근 권한이 없어 해당 기능을 사용할 수 없습니다.\n권한을 허용하시려면 설정을 눌러주세요.",style=MaterialTheme.typography.bodyMedium)
                    Text("필요 권한: 사진, 동영상",style=MaterialTheme.typography.bodyMedium)
                    Row(Modifier.align(Alignment.End),horizontalArrangement=Arrangement.spacedBy(24.dp)){
                        Text("취소",style=MaterialTheme.typography.labelLarge,color=ScenarioColors.Link)
                        Text("설정",style=MaterialTheme.typography.labelLarge,color=ScenarioColors.Link)
                    }
                }
            }
        }
    }
}
