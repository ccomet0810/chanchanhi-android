package dev.ccomet.chanchanhi04.platform

import android.content.Context
import android.content.Intent
import android.app.Application
import android.app.Activity
import java.lang.ref.WeakReference
import android.speech.tts.TextToSpeech
import com.example.aplassistmvp0.guide.V04Model
import dev.ccomet.chanchanhi04.agent.*
import dev.ccomet.chanchanhi04.voice.VoiceTarget
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.ccomet.chanchanhi04.setup.SetupRequirement
import dev.ccomet.chanchanhi04.setup.SetupPermissions
import dev.ccomet.chanchanhi04.setup.AppEntryPolicy
import dev.ccomet.chanchanhi04.setup.SetupCoordinator
import dev.ccomet.chanchanhi04.setup.SetupPort
import dev.ccomet.chanchanhi04.setup.SetupPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppRuntime {
    private lateinit var context: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var loop: AgentLoop; private set
    lateinit var log: SessionLog; private set
    lateinit var study: dev.ccomet.chanchanhi04.study.StudyRepository; private set
    internal lateinit var model: V04Model; private set
    @Volatile var service: ScreenService? = null
        private set
    private val mutableScreenConnected=MutableStateFlow(false)
    val screenConnected=mutableScreenConnected.asStateFlow()
    // Changes on each real binding, even if enabled and revoked while the UI is stopped.
    private val mutableScreenConnectionCycle=MutableStateFlow(java.util.UUID.randomUUID().toString())
    val screenConnectionCycle=mutableScreenConnectionCycle.asStateFlow()
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var queuedSpeech: String? = null
    private var lastSpeech: String? = null
    private lateinit var device: DevicePort
    private var hostVisible=false
    private var visibleHost:WeakReference<Activity>?=null
    private var guidanceSuppressed=false
    private val voiceOwners=mutableSetOf<Any>()
    var setupRequirement:SetupRequirement?=null;private set
    lateinit var setup:SetupCoordinator;private set
    private var capabilityContext:dev.ccomet.chanchanhi04.setup.CapabilityGuideContext?=null
    private var capabilityProtectedWait=false
    private var capabilityMonitor:kotlinx.coroutines.Job?=null
    val initialized get() = ::context.isInitialized
    val preferences get() = context.getSharedPreferences("v04",Context.MODE_PRIVATE)
    val researchMode get()=AppEntryPolicy.researchMode(dev.ccomet.chanchanhi04.research.ResearchConnection(context).enrolled,study.state.value)
    val showStudy get()=AppEntryPolicy.showStudy(dev.ccomet.chanchanhi04.research.ResearchConnection(context).enrolled,study.state.value)
    val screenConsentAccepted get()=preferences.getBoolean("screen-disclosure-accepted",false)
    private val mutableConnection=MutableStateFlow("AI 연결을 준비하고 있어요.")
    val connectionStatus=mutableConnection.asStateFlow()
    private var connectionJob:kotlinx.coroutines.Job?=null
    fun acceptScreenConsent(){preferences.edit().putBoolean("screen-disclosure-accepted",true).apply();connectAutomatically()}
    private var studyConnectionJob:kotlinx.coroutines.Job?=null
    fun prepareStudyConnection(){
        if(studyConnectionJob?.isActive==true)return
        studyConnectionJob=scope.launch{dev.ccomet.chanchanhi04.research.AutomaticStudyConnection.ensure(context)}
    }
    fun connectAutomatically(){
        if(connectionJob?.isActive==true)return
        mutableConnection.value="AI 연결을 준비하고 있어요."
        connectionJob=scope.launch{mutableConnection.value=runCatching{model.check();"AI 안내를 사용할 수 있어요."}.getOrElse{"연결하지 못했어요. 인터넷 연결을 확인하고 다시 눌러주세요."}}
    }
    private fun canGuide(goal:String)=if(researchMode)study.rules.canGuide(study.state.value,goal)else screenConsentAccepted&&SetupPermissions.read(context).screen
    val imagesAllowed get():Boolean {
        val participant=study.state.value
        val consent=participant.recordsConsent&&participant.captureConsent
        return setupRequirement==null&&preferences.getBoolean("images",false)&&consent
    }
    fun initialize(value: Context) {
        if (initialized) return
        context = value.applicationContext as Application
        study=dev.ccomet.chanchanhi04.study.StudyRepository(context)
        study.ensureDefaultMission()
        log = SessionLog(context,study){goal->isStudySetupTask()&&capabilityContext?.goal==goal}; model = V04Model(context,log::boundEvent)
        model.preparePrivacy()
        dev.ccomet.chanchanhi04.research.ResearchConnection(context).retry()
        device = object : DevicePort {
            override suspend fun observe() = requireService().observe()
            override suspend fun capture() = requireService().capture()
            override fun observationRecovery(failure:ObservationUnavailable,lastScreen:Observation?):String?{
                if(capabilityContext==null)return null
                capabilityProtectedWait=true
                log.event("protected_capability_wait",mapOf("coordinatesShown" to false,"reason" to failure.reason))
                return dev.ccomet.chanchanhi04.setup.CapabilityTaskContext.unavailableMessage()
            }
            override fun installedApps(): List<AppEntry> {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                return context.packageManager.queryIntentActivities(intent,0).filter { it.activityInfo.packageName != context.packageName }
                    .map { AppEntry(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString()) }
                    .distinctBy { it.packageName }.sortedBy { it.label }.take(180)
            }
            override fun render(state: AgentState) { service?.render(state) }
            override fun speak(message: String) { say(message) }
            override fun stopSpeech() { queuedSpeech = null; tts?.stop() }
            override val imagesAllowed get()=AppRuntime.imagesAllowed
        }
        loop = AgentLoop(scope, dev.ccomet.chanchanhi04.setup.CapabilityGuideModel(model,{capabilityContext},{SetupPermissions.read(context)}){source->log.event("setup_decision_source",mapOf("source" to source))}, device, log)
        val settingsPackages=SetupPermissions.settingsPackages(context)
        setup=SetupCoordinator(scope,object:SetupPort{
            override fun snapshot()=SetupPermissions.read(context)
            override fun interactive()=!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked&&context.getSystemService(android.os.PowerManager::class.java).isInteractive
            // Before accessibility is granted we cannot inspect another app. Returning to Main is
            // cancelled by its onResume instead of inferring visibility from an onStop callback.
            override fun inSettings()=service?.foregroundPackage()?.let{it in settingsPackages}?:true
            override fun serviceReady()=service!=null
            override fun open(requirement:SetupRequirement):Boolean{
                if(requirement!=SetupRequirement.SCREEN)return false
                setupRequirement=requirement
                return SetupPermissions.open(context,requirement)
            }
            override fun guide(requirement:SetupRequirement)=startSetup(requirement)
            override fun needsGuidance(requirement:SetupRequirement)=requirement==SetupRequirement.ASSISTANT
            override fun guidancePaused()=loop.state.value.phase in setOf(Phase.PAUSED,Phase.ERROR,Phase.STOPPED)
            override fun pauseGuidance(message:String){loop.pause(message)}
            override fun stopGuidance(){setupRequirement=null;capabilityContext=null;capabilityMonitor?.cancel()
                if(isStudySetupTask()&&loop.state.value.sessionId in study.state.value.sessions)loop.pause("설정 상태를 확인하고 있어요.")else loop.stop()
            }
            override fun completed(){setupRequirement=null
                if(isStudySetupTask()){
                    val actual=SetupPermissions.read(context)
                    log.event("setup_capabilities_verified",mapOf("screen" to actual.screen,"microphone" to actual.microphone,"assistant" to actual.assistant))
                    visibleHost?.get()?.let(::backgroundHost)
                    startStudyMission()
                }else{
                    if(setup.state.value.requirements!=setOf(SetupRequirement.SCREEN))say("사용 준비를 마쳤어요.")
                    openStudy()
                }
            }
            override fun rememberPending(pending:Boolean){preferences.edit().putBoolean("setup-pending",pending).apply()}
        },preferences.getBoolean("setup-pending",false))
        tts = TextToSpeech(context) { result ->
            voiceReady = result == TextToSpeech.SUCCESS
            if (voiceReady) {
                tts?.language = Locale.KOREAN
                tts?.setSpeechRate(0.9f)
                queuedSpeech?.let { message -> queuedSpeech = null; say(message) }
            }
        }
    }
    private fun requireService() = service ?: error("설정에서 ${context.getString(dev.ccomet.chanchanhi04.R.string.accessibility_name)}를 켜주세요.")
    fun attach(value: ScreenService) {
        if(service!==value)mutableScreenConnectionCycle.value=java.util.UUID.randomUUID().toString()
        service = value;mutableScreenConnected.value=true;value.hostVisible(hostVisible);value.suppressGuidance(guidanceSuppressed);if(voiceOwners.isNotEmpty())value.hideForVoice()
    }
    fun detach(value: ScreenService) {
        if (service === value) { loop.pause("화면 안내 권한이 꺼져 멈췄어요."); service = null;mutableScreenConnected.value=false }
    }
    private fun isStudySetupTask()=study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK&&study.rules.presetFor(study.state.value)?.optString("scene")=="assistant-setup"
    /** A fresh mission starts at Settings home; the AI chooses the route from there. */
    fun startStudyMission(){
        val goal=study.rules.missionFor(study.state.value).getString("goal")
        if(!isStudySetupTask()){start(goal);return}
        if(!canGuide(goal))return
        if(!SetupPermissions.openHome(context)){say("설정 앱을 열지 못했어요. 설정 앱을 열고 다시 시작해주세요.");openStudy();return}
        guidanceSuppressed=false;service?.suppressGuidance(false)
        val assistantGoal=SetupRequirement.ASSISTANT.goal(context.packageManager.getApplicationLabel(context.applicationInfo).toString())+" 설정 후 실제 우리 앱의 도우미 호출까지 확인해주세요."
        startCapabilityGuide(null,assistantGoal,verifyInvocation=true)
    }
    fun startCapabilityGuide(requirement:SetupRequirement?,goal:String?=null,verifyInvocation:Boolean=false){
        if(requirement==SetupRequirement.MICROPHONE){
            if(setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED))setup.cancel()
            capabilityContext=null;capabilityMonitor?.cancel();setupRequirement=null;loop.stop()
            say("앱에서 마이크 사용 요청을 확인해주세요.");openStudy(prepareMicrophone=true);return
        }
        if(requirement==SetupRequirement.SCREEN){beginSetup(screenOnly=true);return}
        if(service==null){say("앱 설정 안내에서 화면 안내를 켜주세요.");openStudy();return}
        if(setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED))setup.cancel()
        val actualGoal=goal?:SetupRequirement.ASSISTANT.goal(context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        runCapabilityGuide(requirement,actualGoal,verifyInvocation)
    }
    private fun runCapabilityGuide(requirement:SetupRequirement?,actualGoal:String,verifyInvocation:Boolean){
        val pm=context.packageManager
        capabilityContext=dev.ccomet.chanchanhi04.setup.CapabilityGuideContext(actualGoal,pm.getApplicationLabel(context.applicationInfo).toString(),context.packageName,verifyInvocation)
        setupRequirement=if(verifyInvocation)null else requirement
        guidanceSuppressed=false;service?.suppressGuidance(false)
        loop.start(actualGoal)
        val session=loop.state.value.sessionId
        capabilityMonitor?.cancel();capabilityMonitor=scope.launch{
            capabilityProtectedWait=false
            var previous=SetupPermissions.read(context)
            while(capabilityContext?.goal==actualGoal&&loop.state.value.sessionId==session){
                val state=loop.state.value
                if(state.phase in setOf(Phase.STOPPED,Phase.COMPLETE)){capabilityContext=null;return@launch}
                val actual=SetupPermissions.read(context)
                if(!verifyInvocation&&(state.phase!=Phase.PAUSED||capabilityProtectedWait)&&actual.screen&&actual.assistant){
                    loop.completeVerified(session,"설정을 마쳤어요.");capabilityContext=null;return@launch
                }
                if(actual!=previous)loop.recheckVerifiedState(session)
                if(state.phase==Phase.THINKING)capabilityProtectedWait=false
                previous=actual
                delay(300)
            }
        }
    }
    fun start(goal: String) {
        if(!canGuide(goal)){openStudy();return}
        if(setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED))setup.cancel()
        setupRequirement=null
        capabilityContext=null;capabilityMonitor?.cancel()
        guidanceSuppressed=false;service?.suppressGuidance(false)
        preferences.edit().putString("last_goal",goal).apply()
        loop.start(goal)
    }
    /** Internal capability setup only: no user-provided goal and no survey bypass into general guidance. */
    fun startSetup(requirement:SetupRequirement):Boolean{
        if(!researchMode&&!screenConsentAccepted)return false
        if(service==null||!requirement.canUseAi(SetupPermissions.read(context)))return false
        runCapabilityGuide(requirement,requirement.goal(context.packageManager.getApplicationLabel(context.applicationInfo).toString()),false);return true
    }
    fun beginSetup(screenOnly:Boolean=false){
        if(!screenConsentAccepted)return
        val actual=SetupPermissions.read(context)
        if(screenOnly||!actual.screen)setup.start(setOf(SetupRequirement.SCREEN))
        else if(!actual.microphone)startCapabilityGuide(SetupRequirement.MICROPHONE)
        else if(isStudySetupTask())startStudyMission()
        else if(!actual.assistant)startCapabilityGuide(SetupRequirement.ASSISTANT)
    }
    fun verifySetupOnReturn(){
        val actual=SetupPermissions.read(context)
        if(setup.state.value.requirements==setOf(SetupRequirement.SCREEN)&&actual.screen&&service==null&&setup.state.value.phase==SetupPhase.RUNNING)return
        if(setup.state.value.requirements.all{actual.granted(it)&&(it!=SetupRequirement.SCREEN||service!=null)}&&setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED))setup.cancel()
        else setup.pause(if(setup.state.value.requirements==setOf(SetupRequirement.SCREEN))"화면 안내가 아직 꺼져 있어요."else"설정을 이어서 확인해주세요.")
    }
    fun verifySetupStep(){
        val requirement=setupRequirement?:setup.state.value.requirement?:return
        if(!SetupPermissions.read(context).granted(requirement))startCapabilityGuide(requirement)
        else{setupRequirement=null;loop.stop();openStudy()}
    }
    fun enterApp(activity:Activity) {
        visibleHost=WeakReference(activity)
        hostVisible=true;guidanceSuppressed=true
        service?.hostVisible(true);service?.suppressGuidance(true)
        if(loop.state.value.phase in setOf(Phase.THINKING,Phase.GUIDING,Phase.QUESTION,Phase.OPEN_APP))loop.pause("앱에서 안내를 멈췄어요.")
    }
    fun leaveApp(activity:Activity){
        if(visibleHost?.get()!==activity)return
        visibleHost=null;hostVisible=false;service?.hostVisible(false)
    }
    fun backgroundHostForVoice(voiceTaskId:Int){
        visibleHost?.get()?.takeIf{!it.isFinishing && !it.isDestroyed && it.taskId!=voiceTaskId}?.let(::backgroundHost)
    }
    /** onStop may trail a fast guide response. Release only the host actually moved to the back. */
    fun backgroundHost(activity:Activity):Boolean=activity.moveTaskToBack(true).also{moved->if(moved)leaveApp(activity)}
    fun openStudy(prepareMicrophone:Boolean=false){context.startActivity(Intent(context,dev.ccomet.chanchanhi04.MainActivity::class.java)
        .putExtra(dev.ccomet.chanchanhi04.setup.MicrophonePermission.EXTRA_PREPARE,prepareMicrophone)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
    private var studyInvocation:Pair<String,String>?=null
    /** Only an actual VoiceInteractionSession callback can enter this task verification sheet. */
    fun recordStudyInvocation(showFlags:Int):Boolean{
        val current=study.state.value
        val permissions=SetupPermissions.read(context)
        if(current.phase!=dev.ccomet.chanchanhi04.study.StudyPhase.TASK||
            study.rules.presetFor(current)?.optString("scene")!="assistant-setup"||
            loop.state.value.sessionId !in current.sessions||!permissions.assistant)return false
        studyInvocation=current.runId!! to loop.state.value.sessionId
        log.passive("study_assistant_invoked",mapOf("showFlags" to showFlags,"microphoneGranted" to permissions.microphone,"defaultAssistant" to permissions.assistant,"source" to "VoiceInteractionSession.onShow"))
        if(capabilityContext?.verifyInvocation==true){loop.completeVerified(loop.state.value.sessionId,"기본 도우미 설정과 실제 호출을 확인했어요.");capabilityContext=null}
        else loop.pause("버튼 호출을 확인했어요.")
        return true
    }
    fun takeStudyInvocation():Boolean{
        val binding=studyInvocation?:return false;studyInvocation=null
        return study.state.value.runId==binding.first&&loop.state.value.sessionId==binding.second&&study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK
    }
    fun voiceAllowed():Boolean=(!researchMode&&screenConsentAccepted&&SetupPermissions.read(context).screen)||setupRequirement!=null&&loop.state.value.phase==Phase.QUESTION||study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.COMPLETE ||
        study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK&&loop.state.value.sessionId in study.state.value.sessions&&loop.state.value.phase==Phase.QUESTION
    fun finishGuide(){if(isStudySetupTask()){if(setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED))setup.cancel();study.requestResult();loop.stop();openStudy()}else if(setup.state.value.phase in setOf(SetupPhase.RUNNING,SetupPhase.PAUSED)){setup.cancel();openStudy()}else if(study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK){study.requestResult();loop.stop();openStudy()}else loop.stop()}
    fun resumeGuidance(){
        if(setup.state.value.phase==SetupPhase.PAUSED){beginSetup();return}
        if(researchMode&&setupRequirement==null&&study.state.value.phase!=dev.ccomet.chanchanhi04.study.StudyPhase.COMPLETE &&
            !(study.state.value.phase==dev.ccomet.chanchanhi04.study.StudyPhase.TASK&&loop.state.value.sessionId in study.state.value.sessions)){openStudy();return}
        guidanceSuppressed=false;service?.suppressGuidance(false);loop.resume()
    }
    fun showInput() {if(!voiceAllowed()){openStudy();return};guidanceSuppressed=false;service?.suppressGuidance(false);requireService().showInput() }
    fun say(message: String) {
        lastSpeech = message
        if (voiceReady) tts?.speak(message,TextToSpeech.QUEUE_FLUSH,null,"v04-${System.nanoTime()}") else queuedSpeech = message
    }
    fun prepareVoice(owner:Any):VoiceTarget {
        if(loop.state.value.phase in setOf(Phase.THINKING,Phase.GUIDING,Phase.OPEN_APP))loop.pause()
        queuedSpeech=null;tts?.stop();voiceOwners.add(owner);service?.hideForVoice()
        return VoiceTarget.from(loop.state.value)
    }
    fun finishVoice(owner:Any){voiceOwners.remove(owner);if(voiceOwners.isEmpty())service?.restoreAfterVoice()}
    fun voiceResult(target:VoiceTarget,value:String):Boolean {
        if(!target.accepts(loop.state.value) || value.isBlank())return false
        if(target.answering)loop.answer(value)else start(value)
        return true
    }
    /** Instrumentation-only injection; no Intent, UI or network route can select a fake model. */
    internal fun installTestModel(value: AgentModel) {
        check(dev.ccomet.chanchanhi04.BuildConfig.DEBUG)
        loop.stop(); loop = AgentLoop(scope,value,device,log)
    }
}
