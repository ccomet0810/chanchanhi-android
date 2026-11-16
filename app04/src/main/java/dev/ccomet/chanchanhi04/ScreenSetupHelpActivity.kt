package dev.ccomet.chanchanhi04

import android.app.Activity
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.ccomet.chanchanhi04.platform.AppRuntime
import dev.ccomet.chanchanhi04.setup.*
import dev.ccomet.chanchanhi04.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object ScreenSetupHelp {
    private const val PermissionSettings="android.settings.PICTURE_IN_PICTURE_SETTINGS"
    private val mutableActive=MutableStateFlow(false)
    private var activeOwner:Any?=null
    private var helper=WeakReference<ScreenSetupHelpActivity>(null)
    private var previewGeneration=0
    val active=mutableActive.asStateFlow()
    internal fun activityActive(owner:Any,activity:ScreenSetupHelpActivity){activeOwner=owner;helper=WeakReference(activity);mutableActive.value=true}
    internal fun activityDestroyed(owner:Any){if(activeOwner===owner){activeOwner=null;helper.clear();mutableActive.value=false}}
    fun dismissPreview(){previewGeneration++;helper.get()?.dismissPreview()}
    /** Explicit in-app navigation leaves the lesson, including a guide already used in Settings. */
    fun close(){previewGeneration++;helper.get()?.finish()}
    internal fun previewIsCurrent(generation:Int)=generation==previewGeneration
    fun supported(context:Context)=context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    fun allowed(context:Context):Boolean {
        if(!supported(context))return false
        return runCatching {
            val ops=context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION") val mode=if(Build.VERSION.SDK_INT>=29)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,context.applicationInfo.uid,context.packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,context.applicationInfo.uid,context.packageName)
            mode==AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
    /** Explicit main-page action: permission first, then PiP and the real Settings page. */
    fun open(context:Context)=launch(context,startSettings=true)
    /** The second page can show the checklist before the user starts Settings. */
    fun showChecklist(context:Context){previewGeneration++;launch(context,startSettings=false)}
    private fun launch(context:Context,startSettings:Boolean){
        if(supported(context)&&!allowed(context)){
            val intent=permissionIntent(context)
            if(intent==null){Toast.makeText(context,"작은 창 설정을 열지 못했어요. 다시 시도해주세요.",Toast.LENGTH_LONG).show();return}
            if(context !is Activity)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try{context.startActivity(intent)}catch(_:android.content.ActivityNotFoundException){Toast.makeText(context,"작은 창 설정을 열지 못했어요.",Toast.LENGTH_LONG).show()}
            catch(_:SecurityException){Toast.makeText(context,"작은 창 설정을 열지 못했어요.",Toast.LENGTH_LONG).show()}
            return
        }
        helper.get()?.let{current->
            if(if(startSettings)current.startSettingsFromUser()else current.requestAttention())return
        }
        // Keep the helper out of Main's task. A failed launch-into-PiP can otherwise
        // leave it above onboarding, with a sticky launch flag that defeats recovery.
        val intent=Intent(context,ScreenSetupHelpActivity::class.java)
            .putExtra(ScreenSetupHelpActivity.StartSettings,startSettings)
            .putExtra(ScreenSetupHelpActivity.PreviewGeneration,previewGeneration)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    internal fun params()=PictureInPictureParams.Builder().setAspectRatio(Rational(SetupCueStyle.PipAspectWidth,SetupCueStyle.PipAspectHeight)).setActions(emptyList()).apply{
        if(Build.VERSION.SDK_INT>=31){setAutoEnterEnabled(false);setSeamlessResizeEnabled(false)}
        if(Build.VERSION.SDK_INT>=33)setTitle("찬찬히 설정 순서")
    }.build()
    fun permissionIntent(context:Context):Intent? {
        val flags=PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        for(intent in listOf(Intent(PermissionSettings,Uri.parse("package:${context.packageName}")),Intent(PermissionSettings))) {
            val info=context.packageManager.resolveActivity(intent,flags)?.activityInfo?:continue
            if(!info.exported)continue
            if(info.permission!=null&&ContextCompat.checkSelfPermission(context,info.permission)!=PackageManager.PERMISSION_GRANTED)continue
            return intent.setComponent(ComponentName(info.packageName,info.name))
        }
        return null
    }
}

/** All bootstrap steps stay visible. Only the app's real grant and connection are observed. */
class ScreenSetupHelpActivity:ComponentActivity() {
    internal companion object {
        const val StartSettings="start-screen-settings"
        const val PreviewGeneration="screen-preview-generation"
    }
    private lateinit var session:VisualSetupSession
    private lateinit var permissionGate:PipSetupGate
    private val owner=Any()
    private var pip by mutableStateOf(false)
    private var notice by mutableStateOf("")
    private var startRequested=false
    private var opening=false
    private var enterAnimationComplete=false
    private var needsPermission=false
    private var permissionEpoch:Int?=null
    private var entryTicket:Int?=null
    private val alive get()=!isFinishing&&!isDestroyed
    private val permissionResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ticket=permissionEpoch;permissionEpoch=null
        if(!alive||ticket!=session.generation){if(alive&&entryTicket==null)opening=false;return@registerForActivityResult}
        opening=false;refreshPermission()
        // Return to Main's follow-along page, never jump from permission into Settings.
        finish()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();AppRuntime.initialize(this)
        session=VisualSetupSession(1,0,savedInstanceState?.getBoolean("settings-opened")?:false,savedInstanceState?.getInt("generation")?:0)
        permissionGate=PipSetupGate(savedInstanceState?.getBoolean("pip-requested")?:false)
        permissionEpoch=if(savedInstanceState?.containsKey("permission-epoch")==true)savedInstanceState.getInt("permission-epoch")else null
        startRequested=savedInstanceState?.getBoolean("start-requested")?:intent.getBooleanExtra(StartSettings,false)
        opening=permissionEpoch!=null;pip=isInPictureInPictureMode;refreshPermission()
        // The lesson may have been left while Android was still creating this activity.
        if(savedInstanceState==null&&!startRequested&&!ScreenSetupHelp.previewIsCurrent(intent.getIntExtra(PreviewGeneration,-1))){finish();return}
        ScreenSetupHelp.activityActive(owner,this)
        setContent {ChanchanhiTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Content()}}}
        lifecycleScope.launch {
            while(isActive&&alive){
                if(!interactive()){finish();return@launch}
                if(completeIfReady())return@launch
                delay(350)
            }
        }
        lifecycleScope.launch {AppRuntime.screenConnected.collect{if(alive)completeIfReady()}}
    }
    private fun completeIfReady():Boolean {
        val granted=SetupPermissions.read(this).screen
        val connected=AppRuntime.screenConnected.value&&AppRuntime.service!=null
        if(session.observe(granted,connected)||granted&&connected){finish();return true}
        return false
    }
    override fun onStart(){
        super.onStart()
        // While a request is pending, only the mode-change callback confirms entry.
        if(entryTicket==null)pip=isInPictureInPictureMode
        if(pip&&startRequested)beginSettings()
    }
    override fun onResume(){super.onResume();refreshPermission();queueEntry()}
    override fun onEnterAnimationComplete(){
        super.onEnterAnimationComplete();enterAnimationComplete=true;queueEntry()
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra(StartSettings,false))startSettingsFromUser()else requestAttention()
    }
    private fun queueEntry(){
        if(!alive||opening||pip||!enterAnimationComplete)return
        if(session.completed){finish();return}
        val generation=session.generation
        window.decorView.doOnPreDraw {
            // Draw content before PiP snapshots this task, with no lifecycle or late-frame race.
            window.decorView.postOnAnimation {
                if(alive&&session.generation==generation&&!opening&&!pip&&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))continueSetup()
            }
        }
        window.decorView.postInvalidateOnAnimation()
    }
    private fun refreshPermission(){needsPermission=ScreenSetupHelp.supported(this)&&!ScreenSetupHelp.allowed(this)}
    private fun interactive()=!getSystemService(KeyguardManager::class.java).isKeyguardLocked&&getSystemService(PowerManager::class.java).isInteractive
    private fun continueSetup(){
        if(!alive||opening||pip||!enterAnimationComplete||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        refreshPermission();notice="";permissionGate.retry()
        when(permissionGate.next(ScreenSetupHelp.supported(this),!needsPermission)){
            PipSetupAction.REQUEST_PERMISSION->{requestPermission();return}
            PipSetupAction.WAIT_PERMISSION->return
            PipSetupAction.UNSUPPORTED->{notice="이 기기는 작은 안내창을 지원하지 않아요.";return}
            PipSetupAction.ENTER_PIP->Unit
        }
        val ticket=session.requestEntry()?:return
        opening=true;entryTicket=ticket
        if(!runCatching{enterPictureInPictureMode(ScreenSetupHelp.params())}.getOrDefault(false)){
            session.cancelEntry();entryTicket=null;opening=false;notice="작은 창을 열지 못했어요. 작은 창 허용 상태를 확인해주세요."
        }else{
            // Acceptance is asynchronous and does not prove that Android pinned the window.
            lifecycleScope.launch {
                delay(3000)
                if(alive&&entryTicket==ticket&&!pip){
                    session.cancelEntry();entryTicket=null;opening=false
                    notice="작은 창을 열지 못했어요. 다시 시도해주세요."
                }
            }
        }
    }
    private fun requestPermission(){
        if(permissionEpoch!=null)return
        val intent=ScreenSetupHelp.permissionIntent(this)
        if(intent==null){notice="작은 창 설정을 열지 못했어요. 다시 시도해주세요.";return}
        permissionEpoch=session.generation;opening=true
        runCatching{permissionResult.launch(intent)}.onFailure{permissionEpoch=null;opening=false;notice="작은 창 설정을 열지 못했어요."}
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode:Boolean,newConfig:Configuration){
        super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig);pip=isInPictureInPictureMode
        if(!alive||!::session.isInitialized)return
        if(pip){
            entryTicket?.let{if(session.consumeEntry(it)){entryTicket=null;opening=false}}
            if(startRequested&&lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))beginSettings()
        }else{
            session.cancelEntry();entryTicket=null;opening=false
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))queueEntry()
        }
    }
    internal fun startSettingsFromUser():Boolean {
        if(!alive)return false
        if(session.completed){finish();return false}
        startRequested=true
        if(pip)beginSettings()else queueEntry()
        return true
    }
    private fun beginSettings(){
        if(!alive||!interactive())return
        startRequested=false
        val wasOpened=session.settingsOpened
        val reconnect=SetupPermissions.read(this).screen&&AppRuntime.service==null
        session.beginSettings();AppRuntime.acceptScreenConsent()
        if(wasOpened&&AppRuntime.setup.state.value.phase==SetupPhase.RUNNING){
            if(!SetupPermissions.open(this,SetupRequirement.SCREEN))notice="설정을 열지 못했어요. 다시 시도해주세요."
        }else{
            AppRuntime.beginSetup(screenOnly=true)
            // Only this explicit user retry may reopen Settings after a binding timeout.
            if(reconnect&&!SetupPermissions.open(this,SetupRequirement.SCREEN))notice="설정을 열지 못했어요. 다시 시도해주세요."
        }
    }
    internal fun settingsStarted()=session.settingsOpened
    internal fun requestAttention():Boolean {
        if(!alive)return false
        if(session.completed){finish();return false}
        if(!pip&&!opening&&ScreenSetupHelp.allowed(this))queueEntry()
        return true
    }
    internal fun dismissPreview(){if(alive&&!session.settingsOpened&&!startRequested)finish()}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putBoolean("settings-opened",session.settingsOpened);outState.putInt("generation",session.generation)
        outState.putBoolean("pip-requested",permissionGate.requested);outState.putBoolean("start-requested",startRequested)
        permissionEpoch?.let{outState.putInt("permission-epoch",it)}
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy(){
        session.cancelEntry();entryTicket=null;super.onDestroy()
        if(!isChangingConfigurations)ScreenSetupHelp.activityDestroyed(owner)
    }
    @Composable private fun Content(){
        BackHandler{finish()}
        LaunchedEffect(notice){if(notice.isNotBlank()){Toast.makeText(this@ScreenSetupHelpActivity,notice,Toast.LENGTH_LONG).show();finish()}}
        PipChecklistFrame(Modifier.fillMaxSize()){
            PipSetupChecklist(SetupPermissions.opensServiceDetails(this@ScreenSetupHelpActivity),Modifier.fillMaxSize())
        }
    }
}
