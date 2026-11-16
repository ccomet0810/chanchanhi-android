package dev.ccomet.chanchanhi04

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import dev.ccomet.chanchanhi04.setup.MicrophonePermission
import dev.ccomet.chanchanhi04.setup.MicrophoneLaunchGate
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ccomet.chanchanhi04.agent.Phase
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.ui.*
import dev.ccomet.chanchanhi04.voice.*

/** App-owned listening sheet. SpeechRecognizer connects to an engine, never its Google input activity. */
class VoiceInputActivity:ComponentActivity() {
    private lateinit var capture:VoiceCapture
    private lateinit var target:VoiceTarget
    private val handler=Handler(Looper.getMainLooper())
    private var resumed=false
    private var completing=false
    private lateinit var microphone:MicrophonePermission
    private var microphonePage by mutableStateOf(false)
    private var microphoneNotice by mutableStateOf("")
    private val permissionPending get()=::microphone.isInitialized&&microphone.state.pending
    private var settingsPending=false
    private var autoStart=true
    private var prepareEditorForSpeech:(()->Unit)?=null
    private var stale by mutableStateOf(false)
    private val monitor=object:Runnable {
        override fun run(){if(!resumed || completing)return
            if(!unlocked()){dismiss();return}
            capture.poll();handler.postDelayed(this,250)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();AppRuntime.initialize(this)
        microphone=MicrophonePermission(this,savedInstanceState)
        microphonePage=savedInstanceState?.getBoolean("voice-microphone-page")==true
        settingsPending=savedInstanceState?.getBoolean("voice-microphone-settings")==true
        if(microphonePage||settingsPending)autoStart=false
        if(AppRuntime.takeStudyInvocation()){
            AppRuntime.prepareVoice(this)
            completing=true
            setContent{ChanchanhiTheme{
                val reveal=rememberAssistantReveal()
                Box(Modifier.fillMaxSize()){
                AssistantScrim(reveal,Modifier.matchParentSize())
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),contentAlignment=Alignment.BottomCenter){
                    AssistantSurface(Modifier.widthIn(max=Foundation.MaxWidthDp.dp).fillMaxWidth().assistantReveal(reveal)){
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                            Text("찬찬히가 열렸어요",style=MaterialTheme.typography.titleLarge)
                            Text("어시스턴트와 버튼 호출 설정을 확인했어요.",style=MaterialTheme.typography.bodyLarge)
                            LargeAction("사용 후 설문으로",Glyph.NEXT,{AppRuntime.finishGuide();finish()})
                        }
                    }
                }
                }
            }}
            return
        }
        if(!AppRuntime.voiceAllowed()){completing=true;AppRuntime.openStudy();finish();return}
        val prepared=AppRuntime.prepareVoice(this)
        target=if(savedInstanceState?.containsKey("voice_session")==true)VoiceTarget(
            savedInstanceState.getString("voice_session").orEmpty(),
            runCatching{Phase.valueOf(savedInstanceState.getString("voice_phase").orEmpty())}.getOrDefault(prepared.phase),
            savedInstanceState.getString("voice_question"))else prepared
        capture=VoiceCapture({VoiceEngines.create(this)},SystemClock::elapsedRealtime)
        if(savedInstanceState?.containsKey("voice_draft")==true){capture.edit(savedInstanceState.getString("voice_draft").orEmpty());autoStart=false}
        setContent{ChanchanhiTheme{
            val connected by AppRuntime.screenConnected.collectAsState()
            val visualActive by ScreenSetupHelp.active.collectAsState()
            val hostLifecycle by lifecycle.currentStateFlow.collectAsState()
            val requestReady=MicrophoneLaunchGate.allowed(SetupPermissions.read(this).screen,connected&&AppRuntime.service!=null,hostLifecycle.isAtLeast(Lifecycle.State.RESUMED),visualActive)
            LaunchedEffect(connected,visualActive,hostLifecycle,microphonePage){
                // A cold explicit invocation may precede service binding. Permission preparation
                // always clears autoStart, so a grant callback cannot start recognition here.
                if(autoStart&&requestReady&&!microphonePage&&!permissionPending&&!settingsPending)beginIfAllowed()
            }
            Box(Modifier.fillMaxSize()){
                // Keep the same editor composed so the live question, draft and selection survive.
                Box(if(microphonePage)Modifier.clearAndSetSemantics{}else Modifier){ListeningSheet()}
                if(microphonePage)Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
                    MicrophonePreparationPage(microphone.state.status,{
                        if(nativeMicrophoneReady())microphone.request()
                    },{
                        if(nativeMicrophoneReady()){
                            settingsPending=true
                            if(!microphone.openSettings()){settingsPending=false;microphoneNotice="앱 권한 화면을 열지 못했어요. 다시 눌러주세요."}
                        }
                    },{closeMicrophonePreparation(true)},{closeMicrophonePreparation(false)},microphoneNotice,requestEnabled=requestReady,layout=PageLayout.SETTINGS)
                }
            }
        }}
    }
    override fun onResume(){super.onResume();resumed=true
        microphone.refresh()
        if(settingsPending){settingsPending=false;autoStart=false}
        if(completing)return
        if(!unlocked()){dismiss();return}
        if(autoStart && !permissionPending && !settingsPending&&!microphonePage)beginIfAllowed()
        handler.removeCallbacks(monitor);handler.post(monitor)
    }
    override fun onPause(){resumed=false;handler.removeCallbacks(monitor)
        if(::capture.isInitialized){if(completing)capture.cancel()else capture.textMode()}
        if(!permissionPending && !settingsPending)autoStart=false
        super.onPause()
    }
    override fun onStop(){
        if(!isChangingConfigurations && !permissionPending && !settingsPending && !completing)finish()
        super.onStop()
    }
    override fun onSaveInstanceState(outState:Bundle){
        microphone.save(outState);outState.putBoolean("voice-microphone-page",microphonePage);outState.putBoolean("voice-microphone-settings",settingsPending)
        if(!::capture.isInitialized){super.onSaveInstanceState(outState);return}
        outState.putString("voice_session",target.sessionId);outState.putString("voice_phase",target.phase.name);outState.putString("voice_question",target.questionId)
        outState.putString("voice_draft",capture.state.value.text)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent)
        if(!completing&&!microphonePage&&!permissionPending&&!settingsPending){autoStart=true;if(resumed)beginIfAllowed()}
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null)
        if(::capture.isInitialized)capture.cancel()
        AppRuntime.finishVoice(this);super.onDestroy()
    }
    private fun unlocked()=getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun hasMicrophone()=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun beginIfAllowed(){
        if(!resumed || completing || !unlocked()||microphonePage)return
        if(!target.accepts(AppRuntime.loop.state.value)){stale=true;autoStart=false;capture.fail("안내가 바뀌었어요. 다시 불러주세요.");return}
        if(!nativeMicrophoneReady()){
            if(!SetupPermissions.read(this).screen)autoStart=false
            capture.fail("화면 안내에 연결한 뒤 말로 입력할 수 있어요.");return
        }
        if(!hasMicrophone()){
            autoStart=false;capture.fail("마이크를 허용하면 말로 입력할 수 있어요.")
            return
        }
        autoStart=false;prepareEditorForSpeech?.invoke();capture.start()
    }
    private fun nativeMicrophoneReady()=MicrophoneLaunchGate.allowed(SetupPermissions.read(this).screen,
        AppRuntime.service!=null&&AppRuntime.screenConnected.value,resumed&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),ScreenSetupHelp.active.value)
    private fun allowMicrophone(){
        autoStart=false;capture.textMode();prepareEditorForSpeech?.invoke()
        microphone.refresh();microphoneNotice="";microphonePage=true
    }
    private fun closeMicrophonePreparation(prepared:Boolean){
        if(permissionPending)return
        if(prepared)microphone.continueWithoutRecording()
        autoStart=false;capture.textMode();microphonePage=false;microphoneNotice=""
    }
    private fun submit(){
        if(completing || !resumed || !unlocked())return
        if(!target.accepts(AppRuntime.loop.state.value)){stale=true;capture.fail("안내가 바뀌었어요. 다시 불러주세요.");return}
        if(AppRuntime.service==null){capture.fail("설정에서 화면 안내를 켜주세요.");return}
        val value=capture.confirm() ?: return
        completing=true;AppRuntime.backgroundHostForVoice(taskId);moveTaskToBack(true);finish()
        AppRuntime.voiceResult(target,value)
    }
    private fun dismiss(){completing=true;autoStart=false;if(::capture.isInitialized)capture.cancel();finish()}

    @Composable private fun ListeningSheet(){
        val state by capture.state.collectAsState()
        val reveal=rememberAssistantReveal()
        val active=state.phase in setOf(VoicePhase.STARTING,VoicePhase.LISTENING,VoicePhase.PROCESSING)
        val keyboardProgress=rememberVoiceKeyboardProgress()
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        var editor by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(state.text,TextRange(state.text.length)))}
        var editing by remember{mutableStateOf(false)}
        val editorFocus=remember{FocusRequester()}
        val listeningFocus=remember{FocusRequester()}
        val interactions=remember{MutableInteractionSource()}
        fun enterEditing(){editing=true;autoStart=false;capture.edit(editor.text);editorFocus.requestFocus();keyboard?.show()}
        DisposableEffect(focus,keyboard,listeningFocus){
            val prepare={editing=false;focus.clearFocus();listeningFocus.requestFocus();keyboard?.hide();Unit}
            prepareEditorForSpeech=prepare
            onDispose{prepareEditorForSpeech=null}
        }
        LaunchedEffect(Unit){if(active){listeningFocus.requestFocus();keyboard?.hide()}}
        LaunchedEffect(interactions){interactions.interactions.collect{if(it is PressInteraction.Press)enterEditing()}}
        LaunchedEffect(state.text,state.phase){if(!editing && editor.text!=state.text)editor=TextFieldValue(state.text,TextRange(state.text.length))}
        LaunchedEffect(active){if(active)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        BackHandler{dismiss()}
        Box(Modifier.fillMaxSize()){
        AssistantScrim(reveal,Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).focusRequester(listeningFocus).focusable()){
            val available=maxHeight
            VoiceInputPanel(state,!stale&&editor.text.isNotBlank()&&state.phase!=VoicePhase.CLOSED,
                onClose=::dismiss,onSubmit={
                    autoStart=false;capture.edit(editor.text);focus.clearFocus();keyboard?.hide();submit()
                },onSecondary={action->when(action){
                    VoiceSecondary.STOP->capture.stop()
                    VoiceSecondary.ALLOW_MICROPHONE->allowMicrophone()
                    VoiceSecondary.RETRY->{focus.clearFocus();keyboard?.hide();autoStart=true;beginIfAllowed()}
                }},modifier=Modifier.align(Alignment.BottomCenter).widthIn(max=Foundation.MaxWidthDp.dp).fillMaxWidth().heightIn(max=available).assistantReveal(reveal),
                answering=target.answering,keyboardProgress=keyboardProgress,microphoneAllowed=hasMicrophone(),stale=stale){
                    VoiceDraftEditor(editor,{value->
                        val bounded=value.copy(text=value.text.take(2000))
                        if(bounded.text!=editor.text){editing=true;autoStart=false;capture.edit(bounded.text)}
                        editor=bounded
                    },answering=target.answering,
                        modifier=Modifier.fillMaxWidth().focusRequester(editorFocus).semantics{
                            contentDescription=if(target.answering)"답변" else "하고 싶은 일"
                            onClick{enterEditing();true}
                        }.onFocusChanged{if(!it.isFocused)editing=false},
                        interactionSource=interactions,enabled=!stale && state.phase!=VoicePhase.CLOSED,
                        maxLines=4)
            }
        }
        }
    }
}
