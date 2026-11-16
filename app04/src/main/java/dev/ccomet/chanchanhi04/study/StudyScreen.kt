package dev.ccomet.chanchanhi04.study

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.AdminActivity
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.research.ResearchConnection
import dev.ccomet.chanchanhi04.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import dev.ccomet.chanchanhi04.setup.*

/** One decision per page, large touch targets, durable drafts and no skip into the assistant. */
@Composable fun StudyScreen(onSettings:()->Unit,onStartMission:()->Unit,onResumeMission:()->Unit,onClose:()->Unit,setup:SetupSnapshot,onSetup:(SetupRequirement)->Unit,repository:StudyRepository=AppRuntime.study,screenConnected:Boolean=setup.screen,initialReview:StudyPhase?=null,onReviewComplete:(()->Unit)?=null,
    microphoneStatus:MicrophonePermissionStatus?=null,onRequestMicrophone:(()->Unit)?=null,onMicrophoneSettings:(()->Boolean)?=null,onMicrophonePrepared:()->Unit={},microphoneRequestEnabled:Boolean=true){
    val context=LocalContext.current;val repo=repository;val actualState by repo.state.collectAsState()
    val t=repo.rules.template;val introPages=StudyNavigation.onboarding(t)
    var reviewedName by rememberSaveable(actualState.uiEpoch,actualState.runId,actualState.phase){mutableStateOf<String?>(initialReview?.takeIf{actualState.phase!=StudyPhase.WITHDRAWN}?.name)}
    var reviewIndex by rememberSaveable(actualState.uiEpoch,actualState.runId,actualState.phase){mutableIntStateOf(0)}
    val reviewing=reviewedName!=null
    val state=if(reviewing)actualState.copy(phase=StudyPhase.valueOf(reviewedName!!))else actualState
    val agent by AppRuntime.loop.state.collectAsState();val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    val research=remember{ResearchConnection(context)}
    val researchStatus by dev.ccomet.chanchanhi04.research.AutomaticStudyConnection.status.collectAsState()
    val section=state.phase.name.lowercase()
    var draft by remember(state.uiEpoch,state.runId,section,state.mission?.optString("id"),reviewedName){mutableStateOf(repo.draft(section))}
    var index by remember(state.uiEpoch,state.runId,section,state.mission?.optString("id"),reviewedName){mutableIntStateOf(if(reviewing)reviewIndex else if(state.phase==StudyPhase.INTRO)StudyNavigation.introIndex(draft,t)else draft.optInt("_index",0))}
    var resultShowing by rememberSaveable(state.uiEpoch,state.runId){mutableStateOf(state.resultRequested)}
    val showResult=if(reviewing)state.phase==StudyPhase.TASK&&index==1 else resultShowing
    val editable=!busy&&actualState.phase!=StudyPhase.WITHDRAWN
    var recordsConsent by rememberSaveable(state.uiEpoch,state.runId){mutableStateOf(actualState.runId!=null)}
    var imageConsent by rememberSaveable(state.uiEpoch,state.runId){mutableStateOf(actualState.captureConsent)}
    var details by rememberSaveable{mutableStateOf(false)}
    var screenDetails by rememberSaveable{mutableStateOf(false)}
    fun put(key:String,value:Any){draft=JSONObject(draft.toString()).put(key,value);repo.saveDraft(section,draft);error=""}
    fun step(next:Int){index=next;if(reviewing)reviewIndex=next else put("_index",if(state.phase==StudyPhase.INTRO)StudyNavigation.introDraftIndex(next,t)else next)}
    fun perform(success:()->Unit={},action:()->Unit){if(busy)return;busy=true;error="";scope.launch{
        val result=withContext(Dispatchers.IO){runCatching(action)}
        result.onSuccess{success()}.onFailure{error=it.message?.takeIf{v->v.any{c->c in '\uac00'..'\ud7a3'}}?:"입력한 내용과 연결 상태를 확인해주세요."};busy=false
    }}
    fun submit(kind:String,value:JSONObject){perform{repo.submit(kind,value);repo.clearDraft(section)}}
    LaunchedEffect(agent.phase,state.phase,state.resultRequested){if(!reviewing&&state.phase==StudyPhase.TASK&&(state.resultRequested||agent.phase==Phase.COMPLETE&&agent.sessionId in state.sessions))resultShowing=true}
    if(screenDetails){BackHandler{screenDetails=false};AccessDetailsScreen{screenDetails=false};return}
    if(details){BackHandler{details=false};StudyConsentDetails{details=false};return}
    val setupReady=setup.screen&&screenConnected
    val screenUi=rememberScreenSetupPresentation(setupReady,setup.screen&&!screenConnected)
    val pipAllowed=rememberPipAllowed()
    val micStatus=microphoneStatus?:if(setup.microphone)MicrophonePermissionStatus.READY else MicrophonePermissionStatus.AVAILABLE
    val permissions=PermissionJourney.observe(dev.ccomet.chanchanhi04.ScreenSetupHelp.supported(context),pipAllowed,setup.screen,screenConnected,micStatus)
    var title="";var body="";var progress="";var button="다음";var valid=false;var action:()->Unit={}
    val mission=repo.rules.missionFor(state)
    val preset=repo.rules.presetFor(state)
    val readiness=repo.rules.usesReadinessSurvey(state)
    val needsReason=repo.rules.requiresMissionReason(state)
    val progressMissionPages=StudyNavigation.pages(StudyPhase.MISSION_PRE,t,preset!=null||state.mission==null&&repo.rules.defaultPreset()!=null)
    LaunchedEffect(state.phase,index,permissions){if(!reviewing&&state.phase==StudyPhase.INTRO&&index>=introPages.length()&&!permissions.complete)step(StudyNavigation.permissionIndex(t))}
    when(state.phase){
        StudyPhase.INTRO->{
            val pages=introPages;index=index.coerceIn(0,pages.length())
            if(index<pages.length()){
                val page=pages.getJSONObject(index);title=page.getString("title");body=page.getString("body");valid=true;action={step(index+1)}
                if(page.optString("id")=="setup"){
                    title="앱 권한 설정하기";body=""
                }
            }
            else {title="수집 내용 확인";body="참여 정보와 설문 응답, 안내 과정을 연구 서버에 기록해요. 화면 이미지 기록은 선택할 수 있어요."
                button="동의하고 시작";valid=permissions.complete&&recordsConsent&&research.enrolled;action={val images=imageConsent;perform{repo.consent(images)}}}
        }
        StudyPhase.PROFILE->{
            index=index.coerceIn(0,3);progress="기본정보 ${index+1}/4"
            title=listOf("이름을 입력해주세요","나이를 입력해주세요","성별을 선택해주세요","직업을 입력해주세요")[index]
            valid=when(index){0->draft.optString("name").trim().length in 1..40;1->draft.optString("age").toIntOrNull() in 1..120;2->draft.optString("gender").isNotBlank();else->draft.optString("occupation").isNotBlank()}
            action={val field=listOf("name","age","gender","occupation")[index];val value:Any=if(field=="age")draft.getString(field).toInt()else draft.getString(field).trim()
                val finalProfile=if(index==3)JSONObject().put("name",draft.getString("name").trim()).put("age",draft.getString("age").toInt()).put("gender",draft.getString("gender")).put("occupation",draft.getString("occupation"))else null
                perform(success={if(finalProfile==null)step(index+1)}){repo.submit("profile_answered",JSONObject().put("field",field).put("value",value));if(finalProfile!=null){repo.submit("profile_submitted",finalProfile);repo.clearDraft(section)}}}
        }
        StudyPhase.BASELINE,StudyPhase.POST->{
            val questions=repo.rules.questions(if(state.phase==StudyPhase.BASELINE)"baseline" else "post");index=index.coerceIn(0,questions.lastIndex)
            progress=(if(state.phase==StudyPhase.BASELINE)"시작 전 설문" else "사용 후 설문")+" ${index+1}/${questions.size}"
            val q=questions[index];title=q.getString("text");valid=draft.optInt(q.getString("id"),0) in 1..repo.rules.options(q).size
            if(index==questions.lastIndex)button=if(state.phase==StudyPhase.POST)"설문 마치기" else "다음"
            action={val key=q.getString("id");val value=draft.getInt(key);val last=index==questions.lastIndex;val answers=JSONObject();questions.forEach{answers.put(it.getString("id"),draft.optInt(it.getString("id"),0))}
                perform(success={if(!last)step(index+1)}){repo.submit(if(state.phase==StudyPhase.BASELINE)"baseline_answered"else"post_answered",JSONObject().put("questionId",key).put("value",value));if(last){repo.submit(if(state.phase==StudyPhase.BASELINE)"baseline_submitted"else"post_submitted",answers);repo.clearDraft(section)}}}
        }
        StudyPhase.MISSION_PRE->{
            progress="사용해볼 작업"
            if(preset!=null){index=index.coerceIn(0,StudyNavigation.pages(StudyPhase.MISSION_PRE,t,true)-1);button=if(index==2&&!readiness)"찬찬히와 시작하기"else"다음"
                if(readiness)when(index){
                    0->{val knowledge=preset.getJSONObject("knowledge");title=knowledge.getString("question");body=knowledge.optString("description");val choices=knowledge.getJSONArray("choices");valid=draft.optString("knowledge") in (0 until choices.length()).map{choices.getJSONObject(it).getString("id")}
                        action={perform(success={step(1)}){repo.submit("mission_knowledge_answered",JSONObject().put("missionId",mission.getString("id")).put("answer",draft.getString("knowledge")).put("method",""))}}}
                    1->{title=mission.getString("confidenceQuestion");valid=draft.optInt("confidence",0) in 1..5;action={if(needsReason)step(2)else submit("mission_pre_submitted",JSONObject().put("missionId",mission.getString("id")).put("confidence",draft.getInt("confidence")))}}
                    else->{title=mission.getString("reasonQuestion");valid=draft.optString("reason").trim().length in 1..1000;action={submit("mission_pre_submitted",JSONObject().put("missionId",mission.getString("id")).put("confidence",draft.getInt("confidence")).put("reason",draft.getString("reason").trim()))}}
                }else when(index){
                    0->{title=mission.getString("title");body=mission.getString("description");valid=true;action={step(1)}}
                    1->{val knowledge=preset.getJSONObject("knowledge");title=knowledge.getString("question");body=knowledge.optString("description");val choices=knowledge.getJSONArray("choices");valid=draft.optString("knowledge") in (0 until choices.length()).map{choices.getJSONObject(it).getString("id")}
                        action={perform(success={step(2)}){repo.submit("mission_knowledge_answered",JSONObject().put("missionId",mission.getString("id")).put("answer",draft.getString("knowledge")).put("method",draft.optString("method")))}}}
                    else->{title=mission.getString("confidenceQuestion");valid=draft.optInt("confidence",0) in 1..5;action={submit("mission_pre_submitted",JSONObject().put("missionId",mission.getString("id")).put("confidence",draft.getInt("confidence")))}}
                }
            }else{title=if(state.mission==null)"미션을 준비하고 있어요"else mission.getString("title");body=if(state.mission==null)"진행자를 불러주세요."else mission.getString("description");button="찬찬히와 시작하기";valid=state.mission!=null&&draft.optInt("confidence",0) in 1..5
                action={submit("mission_pre_submitted",JSONObject().put("missionId",mission.getString("id")).put("confidence",draft.getInt("confidence")))}}
        }
        StudyPhase.TASK->{
            title=if(showResult)"미션 결과를 알려주세요" else "미션 수행";body=if(showResult)"실제로 해본 결과를 선택해주세요." else mission.optString("description")
            if(showResult){button="사용 후 설문으로";valid=draft.optString("outcome") in repo.rules.strings(mission.getJSONArray("outcomes"));action={AppRuntime.loop.stop();submit("mission_result_submitted",JSONObject().put("missionId",mission.getString("id")).put("outcome",draft.getString("outcome")))}}
            else {val resumable=agent.sessionId in state.sessions&&agent.phase in setOf(Phase.PAUSED,Phase.ERROR,Phase.GUIDING,Phase.QUESTION,Phase.OPEN_APP,Phase.THINKING)
                button=if(resumable)"안내 이어가기" else "찬찬히로 미션 시작";valid=true;action={if(!setupReady)error="설정에서 화면 안내를 켜주세요." else if(resumable)onResumeMission()else onStartMission()}}
        }
        StudyPhase.COMPLETE->{title=if(onReviewComplete==null)"설문을 마쳤어요"else"수정 내용을 저장했어요";body=if(onReviewComplete==null)"설문에 참여해주셔서 감사합니다."else"변경한 응답을 저장했어요.";button=if(onReviewComplete==null)"찬찬히 사용하기"else"수정 마치기";valid=true;action={if(onReviewComplete==null)repo.acknowledgeComplete()else onReviewComplete()}}
        StudyPhase.WITHDRAWN->{title=if(repo.problem==null)"참여를 중단했어요" else "진행자를 불러주세요";body="다음 참여는 진행자가 준비해드릴게요.";button="앱 닫기";valid=true;action=onClose}
    }
    fun pages(phase:StudyPhase)=StudyNavigation.pages(phase,t,preset!=null)
    fun review(phase:StudyPhase,at:Int){reviewIndex=at.coerceIn(0,pages(phase)-1);reviewedName=phase.name}
    fun previous(){
        dev.ccomet.chanchanhi04.ScreenSetupHelp.close()
        if(state.phase==StudyPhase.INTRO)AppRuntime.setup.cancel()
        if(!reviewing&&state.phase==StudyPhase.TASK&&showResult){review(StudyPhase.TASK,0);return}
        if(index>0){step(index-1);return}
        StudyNavigation.previous(state.phase)?.let{review(it,pages(it)-1)}
    }
    fun nextReview(){
        if(index<pages(state.phase)-1){step(index+1);return}
        val next=StudyNavigation.next(state.phase)
        if(next==null||next.ordinal>=actualState.phase.ordinal)reviewedName=null else review(next,0)
    }
    if(reviewing){
        button="다음"
        val consentPage=state.phase==StudyPhase.INTRO&&index==introPages.length()
        if(consentPage){valid=true;if(!recordsConsent)button="동의 철회하고 중단"}
        if(state.phase==StudyPhase.TASK&&!showResult)valid=true
        action={perform(success={if(repo.state.value.phase!=StudyPhase.WITHDRAWN)nextReview()else{AppRuntime.loop.stop();AppRuntime.setup.cancel();reviewedName=null}}){
            when(state.phase){
                StudyPhase.INTRO->if(consentPage){repo.reviseConsent(recordsConsent,imageConsent)}
                StudyPhase.PROFILE->{val field=listOf("name","age","gender","occupation")[index];repo.revise("profile",field,if(field=="age")draft.getString(field).toInt()else draft.getString(field).trim())}
                StudyPhase.BASELINE,StudyPhase.POST->{val key=repo.rules.questions(section)[index].getString("id");repo.revise(section,key,draft.getInt(key))}
                StudyPhase.MISSION_PRE->if(readiness)when(index){
                    0->repo.reviseKnowledge(draft.getString("knowledge"),"")
                    1->repo.revise("mission_pre","confidence",draft.getInt("confidence"))
                    else->repo.revise("mission_pre","reason",draft.getString("reason").trim())
                }else if(preset!=null&&index==1)repo.reviseKnowledge(draft.getString("knowledge"),draft.optString("method"))else if(preset==null||index==2)repo.revise("mission_pre","confidence",draft.getInt("confidence"))
                StudyPhase.TASK->if(showResult)repo.revise("task","outcome",draft.getString("outcome"))
                else->Unit
            }
            repo.clearDraft(section)
        }}
    }
    val canPrevious=index>0||StudyNavigation.previous(state.phase)!=null
    val setupPlayer=state.phase==StudyPhase.INTRO&&index<introPages.length()&&introPages.getJSONObject(index).optString("id")=="setup"
    if(setupPlayer){
        PermissionsOnboardingPage(permissions,setup.screen,screenConnected,micStatus,screenUi,
            onSettings=onSettings,onScreenSetup={onSetup(SetupRequirement.SCREEN)},
            onRequestMicrophone={onRequestMicrophone?.invoke()?:onSetup(SetupRequirement.MICROPHONE)},
            onMicrophoneSettings={if(onMicrophoneSettings?.invoke()!=true)error="앱 권한 화면을 열지 못했어요. 다시 눌러주세요."},
            onComplete={onMicrophonePrepared();if(reviewing)nextReview()else step(index+1)},onPrevious={previous()},
            microphoneRequestEnabled=microphoneRequestEnabled,notice=error)
        return
    }
    BackHandler(canPrevious&&!busy){previous()}
    OnboardingPage(onSettings=onSettings,actions={Column{
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom=8.dp))
        JourneyActions(if(busy)"저장 중"else button,{if(!busy)action()},valid&&!busy,onPrevious=if(canPrevious)({previous()})else null,previousEnabled=!busy)

    }}){
        SurveyProgressInfo.forScreen(state.phase,if(state.phase==StudyPhase.TASK&&showResult)1 else index,t,missionPages=progressMissionPages)?.let{StudyProgress(it)}
        val introPlayer=state.phase==StudyPhase.INTRO&&index<introPages.length()&&introPages.getJSONObject(index).optString("id")=="introduction"
        if(introPlayer){
            IntroductionPreview(modifier=Modifier.weight(1f))
        }else if(state.phase==StudyPhase.COMPLETE){
            SetupSuccess(Modifier.weight(1f).fillMaxWidth(),title=title,message=body)
        }else LazyColumn(Modifier.weight(1f).selectableGroup(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=18.dp)){
            item{PageHeading(title,body,action=if(state.phase==StudyPhase.INTRO&&index==introPages.length())({InformationTextLink("자세히 보기"){details=true}})else null)}
            if(state.phase==StudyPhase.INTRO&&index==introPages.length()){
                item{ConsentChoice("[필수] 참여 정보와 이용 기록 전송에 동의해요",recordsConsent,editable){recordsConsent=it;if(!it)imageConsent=false}}
                if(android.os.Build.VERSION.SDK_INT>=30){
                    item{ConsentChoice("[선택] 화면 이미지 기록에 동의해요",imageConsent,editable&&recordsConsent){imageConsent=it}}
                }
                if(reviewing&&!recordsConsent)item{Text("동의를 철회하면 참여와 새 기록 수집을 중단해요. 이전 기록과 응답 이력은 보존돼요.",style=MaterialTheme.typography.bodyMedium)}
                if(!research.enrolled)item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(researchStatus.ifBlank{"실험 연결을 준비하고 있어요."},style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick={AppRuntime.prepareStudyConnection()}){Text("다시 연결")}
                }}
            }
            if(state.phase==StudyPhase.PROFILE){when(index){
                0,1->item{val key=if(index==0)"name" else "age";OutlinedTextField(draft.optString(key),{value->put(key,if(index==0)value.take(40)else value.filter(Char::isDigit).take(3))},label={Text(if(index==0)"이름" else "나이")},singleLine=true,enabled=editable,modifier=Modifier.fillMaxWidth(),textStyle=MaterialTheme.typography.bodyLarge,keyboardOptions=KeyboardOptions(keyboardType=if(index==0)KeyboardType.Text else KeyboardType.Number))}
                2->repo.rules.strings(t.getJSONArray("genders")).forEach{option->item(key=option){StudyChoice(option,draft.optString("gender")==option,editable){put("gender",option)}}}
                3->if(t.optString("occupationInput")=="text")item{OutlinedTextField(draft.optString("occupation"),{put("occupation",it.take(80))},label={Text("직업")},singleLine=true,enabled=editable,modifier=Modifier.fillMaxWidth(),textStyle=MaterialTheme.typography.bodyLarge)}else repo.rules.strings(t.getJSONArray("occupations")).forEach{option->item(key=option){StudyChoice(option,draft.optString("occupation")==option,editable){put("occupation",option)}}}
            }}
            if(state.phase in setOf(StudyPhase.BASELINE,StudyPhase.POST)){
                val q=repo.rules.questions(if(state.phase==StudyPhase.BASELINE)"baseline" else "post")[index]
                repo.rules.options(q).forEachIndexed{i,label->item(key=label){StudyChoice(label,draft.optInt(q.getString("id"))==i+1,editable){put(q.getString("id"),i+1)}}}
            }
            if(state.phase==StudyPhase.MISSION_PRE&&preset!=null&&!readiness&&index==0)when(preset.optString("scene")){
                "photo-permission-blocked"->item{KakaoPermissionScene()}
            }
            if(state.phase==StudyPhase.MISSION_PRE&&preset!=null&&index==if(readiness)0 else 1){
                val choices=preset.getJSONObject("knowledge").getJSONArray("choices")
                (0 until choices.length()).map(choices::getJSONObject).forEach{choice->item(key=choice.getString("id")){StudyChoice(choice.getString("label"),draft.optString("knowledge")==choice.getString("id"),editable){put("knowledge",choice.getString("id"));if(choice.getString("id")=="unknown")put("method","")}}}
                if(!readiness&&draft.optString("knowledge") in setOf("known","partial"))item{OutlinedTextField(draft.optString("method"),{put("method",it.take(1000))},label={Text("알고 있는 방법 (선택)")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,enabled=editable)}
            }
            if(state.phase==StudyPhase.MISSION_PRE&&needsReason&&index==2){
                item{OutlinedTextField(draft.optString("reason"),{put("reason",it.take(1000))},label={Text("이유")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,enabled=editable,textStyle=MaterialTheme.typography.bodyLarge)}
            }
            if(state.phase==StudyPhase.MISSION_PRE&&state.mission!=null&&(preset==null||index==if(readiness)1 else 2)){
                if(preset==null)item{Text(mission.getString("confidenceQuestion"),style=MaterialTheme.typography.titleLarge)}
                repo.rules.strings(if(readiness)mission.getJSONArray("confidenceChoices")else t.getJSONArray("likert")).forEachIndexed{i,label->item(key=label){StudyChoice(label,draft.optInt("confidence")==i+1,editable){put("confidence",i+1)}}}
            }
            if(state.phase==StudyPhase.TASK){
                if(showResult)repo.rules.strings(mission.getJSONArray("outcomes")).forEach{option->item(key=option){StudyChoice(option,draft.optString("outcome")==option,editable){put("outcome",option)}}}
                else if(!reviewing&&state.sessions.isNotEmpty())item{LargeAction("미션 마치기",Glyph.CHECK,{repo.requestResult()},secondary=true)}
            }
            if(error.isNotBlank())item{Text(error,color=MaterialTheme.colorScheme.error)}
        }
    }
}
@Composable private fun StudyChoice(label:String,selected:Boolean,enabled:Boolean=true,click:()->Unit){SelectionOption(label,selected,enabled,onSelect=click)}
@Composable private fun ConsentChoice(label:String,selected:Boolean,enabled:Boolean=true,change:(Boolean)->Unit){SelectionOption(label,selected,enabled,checkbox=true){change(!selected)}}
