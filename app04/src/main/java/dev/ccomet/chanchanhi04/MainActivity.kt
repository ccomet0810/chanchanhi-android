package dev.ccomet.chanchanhi04

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.platform.AssistantService
import dev.ccomet.chanchanhi04.ui.*
import dev.ccomet.chanchanhi04.setup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** User-only entry. Session files and connection credentials are never loaded here. */
class MainActivity:ComponentActivity() {
    private var permissionVersion by mutableIntStateOf(0)
    private var notice by mutableStateOf("")
    private var setupHelp by mutableStateOf<SetupRequirement?>(null)
    private var accessDetails by mutableStateOf(false)
    private lateinit var microphone:MicrophonePermission
    private var microphonePage by mutableStateOf(false)
    private var microphoneLayout by mutableStateOf(PageLayout.ONBOARDING)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT));AppRuntime.initialize(this)
        microphone=MicrophonePermission(this,savedInstanceState)
        microphonePage=savedInstanceState?.getBoolean("microphone-page")==true
        microphoneLayout=runCatching{PageLayout.valueOf(savedInstanceState?.getString("microphone-layout").orEmpty())}.getOrDefault(PageLayout.ONBOARDING)
        setupHelp=runCatching{SetupRequirement.valueOf(savedInstanceState?.getString("setup-help").orEmpty())}.getOrNull()
        readMicrophoneIntent(intent)
        setContent {ChanchanhiTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){UserApp()}}}
    }
    override fun onResume(){
        super.onResume();AppRuntime.enterApp(this);permissionVersion++;AppRuntime.prepareStudyConnection()
        microphone.refresh()
        AppRuntime.verifySetupOnReturn()
        if(AppRuntime.screenConsentAccepted)AppRuntime.connectAutomatically()
    }
    override fun onPause(){AppRuntime.leaveApp(this);super.onPause()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        setIntent(intent);readMicrophoneIntent(intent)
    }
    override fun onSaveInstanceState(outState:Bundle){
        microphone.save(outState);outState.putBoolean("microphone-page",microphonePage);outState.putString("microphone-layout",microphoneLayout.name)
        setupHelp?.let{outState.putString("setup-help",it.name)}
        super.onSaveInstanceState(outState)
    }
    private fun readMicrophoneIntent(value:Intent){
        if(value.getBooleanExtra(MicrophonePermission.EXTRA_PREPARE,false)){
            value.removeExtra(MicrophonePermission.EXTRA_PREPARE);microphoneLayout=PageLayout.ONBOARDING;microphonePage=true
        }
    }
    override fun onStop(){AppRuntime.leaveApp(this);super.onStop()}
    @Composable private fun UserApp() {
        val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
        var settings by rememberSaveable {mutableStateOf(false)}
        var writing by rememberSaveable {mutableStateOf(false)}
        var newRequest by rememberSaveable {mutableStateOf(false)}
        var settingsRequirement by rememberSaveable{mutableStateOf<SetupRequirement?>(null)}
        var reviewIntro by rememberSaveable{mutableStateOf(false)}
        var participantSettings by rememberSaveable{mutableStateOf(false)}
        var editStudy by rememberSaveable{mutableStateOf(false)}
        var collectionDetails by rememberSaveable{mutableStateOf(false)}
        val scope=rememberCoroutineScope()
        var initialSetupPage by rememberSaveable{mutableStateOf(false)}
        var goal by remember {mutableStateOf(AppRuntime.preferences.getString("last_goal","").orEmpty())}
        val state by AppRuntime.loop.state.collectAsStateWithLifecycle()
        val setupFlow by AppRuntime.setup.state.collectAsStateWithLifecycle()
        val screenConnected by AppRuntime.screenConnected.collectAsStateWithLifecycle()
        val visualActive by ScreenSetupHelp.active.collectAsStateWithLifecycle()
        val hostLifecycle by lifecycle.currentStateFlow.collectAsState()
        val study by AppRuntime.study.state.collectAsStateWithLifecycle()
        // Entry decisions must read the Compose-observed state, not a plain StateFlow getter.
        val researchLink by dev.ccomet.chanchanhi04.research.AutomaticStudyConnection.status.collectAsStateWithLifecycle()
        val enrolled=remember(permissionVersion,researchLink){dev.ccomet.chanchanhi04.research.ResearchConnection(this).enrolled}
        val researchMode=AppEntryPolicy.researchMode(enrolled,study)
        val showStudy=AppEntryPolicy.showStudy(enrolled,study)
        val setup=rememberSetupSnapshot(permissionVersion)
        val screenUi=rememberScreenSetupPresentation(setup.screen&&screenConnected,setup.screen&&!screenConnected)
        val nativeRequestReady=MicrophoneLaunchGate.allowed(setup.screen,screenConnected&&AppRuntime.service!=null,hostLifecycle.isAtLeast(Lifecycle.State.RESUMED),visualActive)
        val microphoneState=microphone.state
        val pipAllowed=rememberPipAllowed()
        val permissions=PermissionJourney.observe(ScreenSetupHelp.supported(this),pipAllowed,setup.screen,screenConnected,microphoneState.status)
        val mergedStudyPreparation=showStudy&&study.phase==dev.ccomet.chanchanhi04.study.StudyPhase.INTRO&&microphoneLayout==PageLayout.ONBOARDING
        LaunchedEffect(mergedStudyPreparation){if(mergedStudyPreparation)microphonePage=false}
        if(accessDetails){
            BackHandler{accessDetails=false}
            AccessDetailsScreen{accessDetails=false}
            return
        }
        if(microphonePage&&!mergedStudyPreparation){
            val close={ScreenSetupHelp.close();AppRuntime.setup.cancel();microphonePage=false;notice=""}
            if(!setup.screen||!screenConnected||AppRuntime.service==null){
                BackHandler(onBack=close)
                PreparationPage(microphoneLayout,"화면 안내",onBack=close,actions={
                    Column{
                        ScreenSetupStatus(screenUi)
                    if(microphoneLayout==PageLayout.ONBOARDING)JourneyActions(screenUi.action,{startSetupFromUser(screenOnly=true)},enabled=!screenUi.waiting,onPrevious=close)
                        else LargeAction(screenUi.action,Glyph.SETTINGS,{startSetupFromUser(screenOnly=true)},enabled=!screenUi.waiting)
                    }
                }){
                    ScreenPreparationContent(false,connecting=setup.screen,modifier=Modifier.weight(1f))
                }
                return
            }
            MicrophonePreparationPage(microphoneState.status,{requestMicrophoneFromUser()},{
                if(!openMicrophoneSettingsFromUser())notice="앱 권한 화면을 열지 못했어요. 다시 눌러주세요."
            },{microphone.continueWithoutRecording();microphonePage=false;permissionVersion++;notice=""},{microphonePage=false;notice=""},notice,requestEnabled=nativeRequestReady,layout=microphoneLayout)
            return
        }
        if(collectionDetails){
            BackHandler{collectionDetails=false}
            dev.ccomet.chanchanhi04.study.StudyConsentDetails{collectionDetails=false};return
        }
        if(reviewIntro){BackHandler{reviewIntro=false};IntroductionReviewPage{reviewIntro=false};return}
        if(participantSettings){
            BackHandler{participantSettings=false}
            ParticipantSettingsPage(study,{participantSettings=false},{participantSettings=false;settings=false},{collectionDetails=true},{
                AppRuntime.loop.stop();scope.launch{
                    runCatching{withContext(Dispatchers.IO){AppRuntime.study.submit("withdrawn",JSONObject())}}
                        .onSuccess{participantSettings=false;settings=false}.onFailure{notice="참여 상태를 확인해주세요."}
                }
            },onEdit={participantSettings=false;settings=false;editStudy=true});return
        }
        settingsRequirement?.let{requirement->
            val back={if(requirement==SetupRequirement.SCREEN){ScreenSetupHelp.close();AppRuntime.setup.cancel()};settingsRequirement=null}
            BackHandler(onBack=back)
            PermissionSettingsPage(requirement,setup.granted(requirement)&&(requirement!=SetupRequirement.SCREEN||screenConnected),back,{
                if(requirement==SetupRequirement.MICROPHONE)openMicrophonePreparation(PageLayout.SETTINGS)
                else if(requirement==SetupRequirement.SCREEN)startSetupFromUser(screenOnly=true)
                else if(AppRuntime.service==null)ScreenSetupHelp.open(this@MainActivity)
                else{AppRuntime.backgroundHost(this@MainActivity);AppRuntime.startCapabilityGuide(requirement)}
            },connecting=requirement==SetupRequirement.SCREEN&&setup.screen&&!screenConnected);return
        }
        if(settings){
            BackHandler{settings=false}
            SettingsPage("설정",onBack={settings=false}){
                UserSettings({if(it==SetupRequirement.MICROPHONE)openMicrophonePreparation(PageLayout.SETTINGS)else settingsRequirement=it},{accessDetails=true},{reviewIntro=true},{participantSettings=true},researchMode,setup,screenConnected)
            };return
        }
        // Setup progress never owns navigation. Only an explicit in-app entry opens this page.
        setupHelp?.takeUnless{it==SetupRequirement.MICROPHONE}?.let{requirement->
            val close={ScreenSetupHelp.close();setupHelp=null;AppRuntime.setup.cancel()}
            if(requirement==SetupRequirement.SCREEN){
                PermissionsOnboardingPage(permissions,setup.screen,screenConnected,microphoneState.status,screenUi,
                    onSettings={settings=true},onScreenSetup={startSetupFromUser(screenOnly=true)},
                    onRequestMicrophone={requestMicrophoneFromUser()},
                    onMicrophoneSettings={if(!openMicrophoneSettingsFromUser())notice="앱 권한 화면을 열지 못했어요. 다시 눌러주세요."},
                    onComplete={microphone.continueWithoutRecording();permissionVersion++;close()},onPrevious=close,
                    microphoneRequestEnabled=nativeRequestReady,notice=notice)
                return
            }
            BackHandler{close()}
            OnboardingPage(onSettings={settings=true},actions={
                Column{
                    if(requirement==SetupRequirement.SCREEN)ScreenSetupStatus(screenUi)
                    JourneyActions(if(requirement==SetupRequirement.SCREEN)screenUi.action else if(setup.granted(requirement))"다음"else"설정 이어가기",
                        {if(requirement==SetupRequirement.SCREEN&&screenUi.ready||requirement!=SetupRequirement.SCREEN&&setup.granted(requirement))close()else startSetupFromUser(screenOnly=requirement==SetupRequirement.SCREEN)},
                        enabled=requirement!=SetupRequirement.SCREEN||!screenUi.waiting,onPrevious={close()})
                }
            }){
                if(requirement==SetupRequirement.SCREEN)ScreenPreparationContent(screenUi.ready,connecting=setup.screen&&!screenConnected,modifier=Modifier.weight(1f))
                else LazyColumn(Modifier.weight(1f).clipToBounds(),verticalArrangement=Arrangement.spacedBy(Foundation.GapDp.dp)){
                    if(setup.granted(requirement))item{Text("이미 설정되어 있어요.",style=MaterialTheme.typography.titleLarge)}
                    else item{SetupInstructions(requirement,getString(R.string.accessibility_name),getString(R.string.app_name),SetupPermissions.opensServiceDetails(this@MainActivity))}
                    if(setupFlow.phase==SetupPhase.PAUSED&&setupFlow.message.isNotBlank())item{Text(setupFlow.message)}
                    if(notice.isNotBlank())item{Text(notice,color=MaterialTheme.colorScheme.error)}
                }
            }
            return
        }
        if(showStudy||editStudy){
            dev.ccomet.chanchanhi04.study.StudyScreen(
                onSettings={settings=true},
                setup=setup,onSetup={if(it==SetupRequirement.SCREEN)startSetupFromUser(screenOnly=true)else showSetup(it)},
                onStartMission={AppRuntime.backgroundHost(this@MainActivity);AppRuntime.startStudyMission()},
                onResumeMission={AppRuntime.backgroundHost(this@MainActivity);AppRuntime.resumeGuidance()},onClose={finish()},screenConnected=screenConnected,
                initialReview=if(editStudy)dev.ccomet.chanchanhi04.study.StudyPhase.PROFILE else null,
                onReviewComplete=if(editStudy)({editStudy=false;settings=true;participantSettings=true})else null,
                microphoneStatus=microphoneState.status,onRequestMicrophone={requestMicrophoneFromUser()},
                onMicrophoneSettings={openMicrophoneSettingsFromUser()},
                onMicrophonePrepared={microphone.continueWithoutRecording();permissionVersion++},microphoneRequestEnabled=nativeRequestReady)
            return
        }
        if(!researchMode&&(initialSetupPage||!AppRuntime.screenConsentAccepted||!permissions.complete)){
            if(initialSetupPage){
                PermissionsOnboardingPage(permissions,setup.screen,screenConnected,microphoneState.status,screenUi,
                    onSettings={settings=true},onScreenSetup={startSetupFromUser(screenOnly=true)},
                    onRequestMicrophone={requestMicrophoneFromUser()},
                    onMicrophoneSettings={if(!openMicrophoneSettingsFromUser())notice="앱 권한 화면을 열지 못했어요. 다시 눌러주세요."},
                    onComplete={microphone.continueWithoutRecording();permissionVersion++;initialSetupPage=false;notice=""},
                    onPrevious={ScreenSetupHelp.close();AppRuntime.setup.cancel();initialSetupPage=false},
                    microphoneRequestEnabled=nativeRequestReady,notice=notice)
            }else OnboardingPage(onSettings={settings=true},actions={JourneyActions("다음",{initialSetupPage=true})}){
                IntroductionPreview(modifier=Modifier.fillMaxSize())
            }
            return
        }
        val continuing=state.phase !in setOf(Phase.IDLE,Phase.STOPPED,Phase.COMPLETE) && !newRequest
        val startRequest={if(checkScreenPermission() && goal.isNotBlank()){newRequest=false;AppRuntime.backgroundHost(this@MainActivity);AppRuntime.start(goal)}}
        BackHandler(writing || newRequest){writing=false;newRequest=false;notice=""}
        val connection by AppRuntime.connectionStatus.collectAsStateWithLifecycle()
        HomePage(goal,{goal=it.take(2000)},writing,writing||newRequest,if(continuing)state.goal else null,state.phase==Phase.ERROR,
            notice,connection.takeIf{it.startsWith("연결하지")},{settings=true},{writing=false;newRequest=false;notice=""},
            {if(checkScreenPermission()){microphone.refresh();if(microphone.state.granted)startActivity(Intent(this@MainActivity,VoiceInputActivity::class.java))else openMicrophonePreparation(PageLayout.SETTINGS)}},
            {writing=true;notice=""},startRequest,
            {if(checkScreenPermission()){AppRuntime.backgroundHost(this@MainActivity);AppRuntime.resumeGuidance()}},
            {newRequest=true;writing=false;notice=""},{AppRuntime.loop.stop()},{AppRuntime.connectAutomatically()})
    }
    private fun checkScreenPermission():Boolean {
        if(AppRuntime.service!=null)return true
        notice="설정에서 화면 안내를 켜주세요."
        return false
    }
    @Composable private fun UserSettings(onRequirement:(SetupRequirement)->Unit,onInformation:()->Unit,onIntroduction:()->Unit,onParticipant:()->Unit,researchMode:Boolean,actual:SetupSnapshot,connected:Boolean) {
        val enabled=actual.screen;val mic=actual.microphone;val chosen=actual.assistant
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)){
            if(researchMode)settingsGroup("실험",first=true){
                item{SettingsAction("실험 참여 정보",null,Glyph.SCIENCE,onParticipant)}
            }
            settingsGroup("사용 권한",first=!researchMode){
                item{SettingsAction("화면 안내",null,Glyph.SHIELD,enabled=!enabled||!connected){onRequirement(SetupRequirement.SCREEN)}}
                item{SettingsAction("마이크",null,Glyph.MIC,enabled=!mic){onRequirement(SetupRequirement.MICROPHONE)}}
                item{SettingsAction("버튼으로 부르기",null,Glyph.ASSISTANT,enabled=!chosen){onRequirement(SetupRequirement.ASSISTANT)}}
            }
            settingsGroup("도움말과 정보"){
                item{SettingsAction("앱 소개 다시 보기",null,Glyph.REPEAT,onIntroduction)}
                item{SettingsAction("화면 정보 처리 안내",null,Glyph.INFO,onInformation)}
            }
            settingsGroup("관리자"){
                item{SettingsAction("관리자 모드",null,Glyph.LOCK){startActivity(Intent(this@MainActivity,AdminActivity::class.java))}}
                item{Text("찬찬히 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall)}
            }
            if(notice.isNotBlank())item{Text(notice,color=MaterialTheme.colorScheme.error)}
        }
    }

    private fun showSetup(requirement:SetupRequirement){
        if(requirement==SetupRequirement.MICROPHONE){openMicrophonePreparation();return}
        notice=""
        val actual=SetupPermissions.read(this)
        setupHelp=if(actual.granted(requirement))requirement else actual.nextRequirement()
    }
    private fun openMicrophonePreparation(layout:PageLayout=PageLayout.ONBOARDING){microphoneLayout=layout;notice="";setupHelp=null;microphone.refresh();microphonePage=true}
    /** Fresh native state prevents a revoked permission or detached service racing a stale UI. */
    private fun microphoneActionAllowed():Boolean {
        val granted=SetupPermissions.read(this).screen
        val connected=AppRuntime.service!=null&&AppRuntime.screenConnected.value
        if(!granted||!connected){
            // Refresh the current preparation shell instead of navigating to a second mic page.
            permissionVersion++
        }
        return MicrophoneLaunchGate.allowed(granted,connected,lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),ScreenSetupHelp.active.value)
    }
    private fun requestMicrophoneFromUser(){if(microphoneActionAllowed())microphone.request()}
    private fun openMicrophoneSettingsFromUser()=microphoneActionAllowed()&&microphone.openSettings()
    /** User starts setup; the information page remains available from the body's link. */
    private fun startSetupFromUser(screenOnly:Boolean=false){
        permissionVersion++
        if(!SetupPermissions.read(this).screen||AppRuntime.service==null||ScreenSetupHelp.supported(this)&&!ScreenSetupHelp.allowed(this))ScreenSetupHelp.open(this)
        else if(screenOnly){AppRuntime.acceptScreenConsent();AppRuntime.beginSetup(true)}
        else{AppRuntime.acceptScreenConsent();val next=SetupPermissions.read(this).nextRequirement()?.takeUnless{it==SetupRequirement.SCREEN}?:SetupRequirement.ASSISTANT
            if(next==SetupRequirement.MICROPHONE)openMicrophonePreparation()else{AppRuntime.backgroundHost(this);AppRuntime.startCapabilityGuide(next)}}
    }
}
