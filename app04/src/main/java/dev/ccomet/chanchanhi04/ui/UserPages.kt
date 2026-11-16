package dev.ccomet.chanchanhi04.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.setup.*
import dev.ccomet.chanchanhi04.study.*
import androidx.compose.ui.platform.LocalContext
import dev.ccomet.chanchanhi04.ScreenSetupHelp

@Composable fun InfoCard(content:@Composable ColumnScope.()->Unit){
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=Color.White,
        border=BorderStroke(1.dp,Color(Foundation.Divider))){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

/** Settings manages one capability. It does not replay the onboarding sequence. */
@Composable fun PermissionSettingsPage(requirement:SetupRequirement,granted:Boolean,onBack:()->Unit,onOpen:()->Unit,connecting:Boolean=false){
    val screenUi=if(requirement==SetupRequirement.SCREEN)rememberScreenSetupPresentation(granted,connecting)else null
    SettingsPage(requirement.title,onBack,actions={Column{
        screenUi?.let{ScreenSetupStatus(it)}
        LargeAction(if(granted)"확인"else screenUi?.action?:"설정하기",
            if(granted)Glyph.CHECK else Glyph.SETTINGS,if(granted)onBack else onOpen,
            enabled=screenUi?.waiting!=true)
    }}){
        if(requirement==SetupRequirement.SCREEN){
            if(granted){Text("화면 안내가 켜져 있어요.",style=MaterialTheme.typography.bodyLarge);Spacer(Modifier.height(Foundation.GapDp.dp))}
            PermissionLessonPreview(PermissionLesson.accessibility(startWithSettings=true),Modifier.weight(1f))
        }else if(requirement==SetupRequirement.ASSISTANT){
            Text(if(granted)"찬찬히로 설정되어 있어요."else"기본 디지털 어시스턴트를 찬찬히로 바꿔주세요.",style=MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Foundation.GapDp.dp))
            PermissionLessonPreview(PermissionLesson.assistant(),Modifier.weight(1f))
        }else{
        LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=12.dp)){
            item{Text(when(requirement){
                SetupRequirement.SCREEN->"다른 앱에서 누를 곳을 안내하는 데 사용해요."
                SetupRequirement.MICROPHONE->"하고 싶은 일을 말로 요청할 때 사용해요."
                SetupRequirement.ASSISTANT->"옆 버튼을 길게 눌러 찬찬히를 불러요."
            },style=MaterialTheme.typography.bodyLarge)}
            item{InfoCard{Text(if(granted)"설정되어 있어요"else"설정이 필요해요",style=MaterialTheme.typography.titleMedium)}}
        }
        }
    }
}

/** This is a read-only replay; it never changes research progress or permission consent. */
@Composable fun IntroductionReviewPage(onBack:()->Unit){
    AppPage("찬찬히 소개",onBack){IntroductionPreview(showTitle=false,modifier=Modifier.fillMaxSize())}
}

@Composable fun ParticipantSettingsPage(state:StudyState,onBack:()->Unit,onContinue:()->Unit,onDetails:()->Unit,onWithdraw:()->Unit,onEdit:()->Unit={}){
    var confirm by remember{mutableStateOf(false)}
    val active=state.participantId!=null&&state.phase !in setOf(StudyPhase.COMPLETE,StudyPhase.WITHDRAWN)
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("참여를 중단할까요?")},
        text={DialogBody("지금까지의 기록은 보관돼요. 다시 참여하려면 진행자를 불러주세요.")},
        confirmButton={TextButton({confirm=false;onWithdraw()}){Text("참여 중단")}},dismissButton={TextButton({confirm=false}){Text("취소")}})
    AppPage("실험 참여 정보",onBack){LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=12.dp)){
        item{InfoCard{
            Text("기본정보",style=MaterialTheme.typography.titleMedium)
            val profile=state.profile?:state.profileAnswers
            if(profile==null)Text(if(state.participantId==null)"참여 동의 후 입력할 수 있어요."else"기본정보를 입력하고 있어요.")else{
                listOf("name" to "이름","age" to "나이","gender" to "성별","occupation" to "직업").forEach{(key,label)->
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Text(label,Modifier.width(76.dp));Text(profile.optString(key).ifBlank{"입력 전"},Modifier.weight(1f))}
                }
            }
        }}
        item{InfoCard{Text("기록 동의",style=MaterialTheme.typography.titleMedium)
            Text("전송: "+if(state.participantId==null)"동의 전"else if(state.recordsConsent)"동의함"else"동의 철회")
            Text("이미지 기록: "+if(state.captureConsent)"동의함"else"동의하지 않음")
        }}
        item{InformationTextLink("수집 내용 자세히 보기",onDetails)}
        if(state.phase !in setOf(StudyPhase.COMPLETE,StudyPhase.WITHDRAWN))item{LargeAction(if(state.phase==StudyPhase.PROFILE)"기본정보 입력 이어가기"else"실험 이어가기",Glyph.NEXT,onContinue)}
        if(state.phase==StudyPhase.COMPLETE)item{LargeAction("설문 응답 수정",Glyph.WRITE,onEdit)}
        if(active)item{LargeAction("참여 중단",Glyph.CLOSE,{confirm=true},secondary=true)}
    }}
}

/** Presentation-only home: the runtime owns all permission and research gates. */
@Composable fun HomePage(goal:String,onGoal:(String)->Unit,writing:Boolean,canGoBack:Boolean,continuingGoal:String?,recovering:Boolean,
    notice:String,connectionError:String?,onSettings:()->Unit,onBack:()->Unit,onSpeak:()->Unit,onWrite:()->Unit,onStart:()->Unit,
    onResume:()->Unit,onNew:()->Unit,onEnd:()->Unit,onReconnect:()->Unit){
    val ime=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val editor=remember{FocusRequester()}
    LaunchedEffect(writing){if(writing)editor.requestFocus()}
    AppPage("찬찬히",if(canGoBack)onBack else null,onSettings,actions={Column(verticalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
        when{
            continuingGoal!=null->{
                LargeAction(if(recovering)"다시 시도하기"else"안내 이어가기",Glyph.NEXT,onResume)
                LargeAction("새로 요청하기",Glyph.WRITE,onNew,secondary=true)
                LargeAction(UiCopy.EndGuidance,Glyph.CLOSE,onEnd,secondary=true)
            }
            writing->{LargeAction("안내 시작",Glyph.NEXT,onStart,enabled=goal.isNotBlank());if(!ime)LargeAction("말로 요청하기",Glyph.MIC,onSpeak,secondary=true)}
            else->{LargeAction("말로 요청하기",Glyph.MIC,onSpeak);LargeAction("글로 입력하기",Glyph.WRITE,onWrite,secondary=true)}
        }
    }}){
        if(continuingGoal!=null)LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=20.dp)){
            item{InfoCard{Text(continuingGoal,style=MaterialTheme.typography.titleMedium);Text(if(recovering)"안내를 이어가지 못했어요."else"이어서 안내받을 수 있어요.")}}
        }else if(writing){
            LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=20.dp)){
                item{Text("하고 싶은 일을 적어주세요",style=MaterialTheme.typography.titleLarge)}
                item{OutlinedTextField(goal,onGoal,modifier=Modifier.fillMaxWidth().focusRequester(editor).semantics{contentDescription="하고 싶은 일"},
                    minLines=if(ime)2 else 4,maxLines=if(ime)4 else 6,textStyle=MaterialTheme.typography.bodyLarge)}
                if(notice.isNotBlank())item{Text(notice,color=MaterialTheme.colorScheme.error)}
            }
        }else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart){
            Column(verticalArrangement=Arrangement.spacedBy(20.dp)){
                Text("무엇을 도와드릴까요?",style=MaterialTheme.typography.headlineSmall)
                Text("휴대폰을 쓰다 막히는 일이 있으면\n찬찬히에 말씀해주세요.",style=MaterialTheme.typography.bodyLarge,color=Color(Foundation.Muted))
                if(notice.isNotBlank())Text(notice,color=MaterialTheme.colorScheme.error)
                if(connectionError!=null){Text(connectionError,color=MaterialTheme.colorScheme.error);TextButton(onReconnect){Text("다시 연결하기")}}
            }
        }
    }
}
