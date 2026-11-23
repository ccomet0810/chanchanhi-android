package dev.ccomet.chanchanhi04.platform

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.example.aplassistmvp0.accessibility.AccessibilityTreeParser
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import dev.ccomet.chanchanhi04.agent.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device adapter: collect immutable snapshots; never perform node clicks or input. */
class ScreenService : AccessibilityService() {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reader = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutex = Mutex()
    private val captureMutex = Mutex()
    private var researchCapture: Job? = null
    private var imageRequested = false
    private var lastCaptureAt = 0L
    private val parser = AccessibilityTreeParser()
    private lateinit var overlay: GuidanceOverlay
    private val epoch = System.nanoTime()
    private var nextSnapshot = 0L
    @Volatile private var visualGeneration = 0L
    @Volatile private var windowGeneration = 0L
    private var interactionSerial = 0L
    private var refresh: Job? = null
    private var lastObservation: Observation? = null
    private var destroyed = false
    private var lastRecordedFrame: Observation? = null
    private var lastLayoutSignature=""
    @Volatile private var lastReadFailure="unknown"
    @Volatile private var focusedIdentity:Pair<Int,String>?=null
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) AppRuntime.loop.pause("화면이 꺼져 안내를 멈췄어요. 잠금을 해제한 뒤 이어가세요.")
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        AppRuntime.initialize(this); overlay = GuidanceOverlay(this,::recordLayout); AppRuntime.attach(this)
        ContextCompat.registerReceiver(this,lockReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if(destroyed||!::overlay.isInitialized)return
        // IME/window events may have no package or belong to our own focused answer field.
        // They still change the safe area, but must not enter the agent's external-screen history.
        overlay.safeInsetsChanged()
        val pkg = event.packageName?.toString() ?: return
        // Window focus may be published after its state event. Bind this metadata to the
        // focused window ID when reading it, rather than dropping that transition here.
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.windowId>=0)focusedIdentity=event.windowId to pkg
        if (pkg == packageName || destroyed || !::overlay.isInitialized) return
        val windowEvent=event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if(!windowEvent && event.windowId>=0 && event.windowId!=lastObservation?.windowId && windows.none{it.id==event.windowId})return
        visualGeneration++
        if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType==AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowGeneration++
        val kind = when(event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_click"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focus"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "selected"
            else -> null
        }
        if (kind != null) {
            val source = event.source
            val resource = runCatching { source?.viewIdResourceName }.getOrNull()
            val rect = android.graphics.Rect()
            runCatching { source?.getBoundsInScreen(rect) }
            val role=runCatching{source?.className?.toString()?.substringAfterLast('.')}.getOrNull()
            val label=runCatching{
                if(source==null||source.isPassword||source.isEditable||AccessibilityNodePrivacy.isSensitivePreviewResource(resource))null
                else (source.text?.toString()?.takeIf{it.isNotBlank()}?:source.contentDescription?.toString())?.let{AccessibilityNodePrivacy.redactIdentifiers(it).take(500)}
            }.getOrNull()
            val matching=InteractionBinding.resolve(lastObservation,pkg,event.windowId,resource,role,
                com.example.aplassistmvp0.accessibility.NodeBounds(rect.left,rect.top,rect.right,rect.bottom),label)
            val interaction = UserInteraction(++interactionSerial,pkg,event.windowId,matching?.path,resource,kind)
            overlay.onInteraction(interaction,com.example.aplassistmvp0.accessibility.NodeBounds(rect.left,rect.top,rect.right,rect.bottom))
            if(recording())AppRuntime.log.passive("interaction",mapOf("serial" to interaction.serial,"packageName" to pkg,"kind" to kind,
                "windowId" to event.windowId,"path" to matching?.path,"resource" to resource,"bounds" to listOf(rect.left,rect.top,rect.right,rect.bottom)))
            AppRuntime.loop.onInteraction(interaction)
        }
        // Coalesce instead of restarting the delay: continuous animation must not starve observations.
        if (AppRuntime.loop.state.value.phase == Phase.GUIDING && refresh?.isActive!=true) refresh = main.launch {
            delay(180)
            try{AppRuntime.loop.onObservation(observe())}
            catch(failure:ObservationUnavailable){AppRuntime.loop.onObservationUnavailable(failure)}
        }
    }
    fun render(state: AgentState) { if (::overlay.isInitialized && !destroyed) overlay.render(state) }
    fun showInput() { if (!unlocked()) error("잠금을 해제한 뒤 불러주세요."); overlay.showInput() }
    fun hideForVoice() { if(::overlay.isInitialized) overlay.setVoiceHidden(true) }
    fun restoreAfterVoice() { if(::overlay.isInitialized && !destroyed) overlay.setVoiceHidden(false) }
    fun hostVisible(value:Boolean){if(::overlay.isInitialized)overlay.setHostHidden(value)}
    fun suppressGuidance(value:Boolean){if(::overlay.isInitialized)overlay.setSuppressed(value)}
    /** No UI text is retained: only the current external app identity for setup interruption. */
    internal fun foregroundPackage():String?{
        val external=windows.filter{it.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&it.type!=AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        val window=external.firstOrNull{it.isFocused}?:external.firstOrNull{it.isActive}
        return window?.root?.packageName?.toString()?:focusedIdentity?.takeIf{it.first==window?.id}?.second
    }
    internal fun overlayDiagnostics() = overlay.diagnostics()
    internal fun observationFailureReason()=lastReadFailure
    private fun unlocked(): Boolean = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        getSystemService(PowerManager::class.java).isInteractive
    private fun recording()=AppRuntime.initialized&&AppRuntime.loop.state.value.phase in setOf(Phase.THINKING,Phase.GUIDING,Phase.QUESTION,Phase.OPEN_APP)
    private fun recordLayout(panel:Map<String,Any?>){
        if(destroyed||!recording()||!unlocked())return
        val ime=windows.firstOrNull{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}?.let{w->android.graphics.Rect().also{w.getBoundsInScreen(it)}}?.takeIf{!it.isEmpty}
        val focused=windows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION}.mapNotNull{it.root}
            .firstOrNull{it.packageName?.toString()!=packageName}?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        val input=focused?.takeIf{it.isEditable}?.let{n->android.graphics.Rect().also{n.getBoundsInScreen(it)}}
        val metrics=resources.displayMetrics
        val screen=if(Build.VERSION.SDK_INT>=30)getSystemService(WindowManager::class.java).currentWindowMetrics.bounds else android.graphics.Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        fun rect(r:android.graphics.Rect?)=r?.let{listOf(it.left,it.top,it.right,it.bottom)}
        val data=panel+mapOf("coordinateSpace" to "screen_px","screen" to listOf(screen.width(),screen.height()),
            "density" to metrics.density,"fontScale" to resources.configuration.fontScale,"ime" to rect(ime),"focusedInput" to rect(input),
            "focusedResource" to focused?.viewIdResourceName,"packageName" to lastObservation?.packageName)
        val signature=AgentJson.encode(data).toString()
        if(signature!=lastLayoutSignature){lastLayoutSignature=signature;AppRuntime.log.passive("overlay_layout",data+mapOf("snapshotId" to lastObservation?.id));queueResearchImage()}
    }

    suspend fun observe(): Observation {
        check(unlocked() && !destroyed) { "잠금을 해제한 뒤 안내를 이어가세요." }
        repeat(24) { attempt ->
            val value = mutex.withLock { withContext(reader) { readTree() } }
            if (value != null) {
                lastObservation = value
                if(recording()){
                    if(!value.sameContent(lastRecordedFrame)){lastRecordedFrame=value;AppRuntime.log.passive("screen_frame",value);queueResearchImage()}
                    overlay.reportPosition()
                }
                return value
            }
            if (attempt < 23) delay(150)
        }
        AppRuntime.log.passive("observation_unavailable",mapOf("reason" to lastReadFailure,"attempts" to 24,"windows" to windows.map{mapOf("id" to it.id,"type" to it.type,"active" to it.isActive,"focused" to it.isFocused)}))
        throw ObservationUnavailable(lastReadFailure)
    }
    private fun readTree(): Observation? {
        if (destroyed || !unlocked()) return null
        val began = windowGeneration
        val candidates = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val window = candidates.firstOrNull { it.isFocused } ?: candidates.firstOrNull { it.isActive }
        // A newly attached permission window can expose its active root before getWindows().root.
        // Never fall through to an unrelated background app behind a focused system dialog.
        val primary=window?.root?.takeIf{runCatching{it.refresh()}.getOrDefault(false)}
        val root=primary?:rootInActiveWindow?.takeIf{node->
            val actual=candidates.firstOrNull{it.id==node.windowId}
            node.packageName?.toString()!=packageName&&
                (window==null||window.id==node.windowId||!window.isFocused&&actual?.isActive==true)&&
                runCatching{node.refresh()}.getOrDefault(false)
        }?:run{lastReadFailure=if(window==null)"no_active_window"else"active_root_unavailable";return null}
        val pkg = root.packageName?.toString() ?: run{lastReadFailure="missing_package";return null}
        if (pkg == packageName){lastReadFailure="own_window";return null}
        val readWindowId=root.windowId
        val parsed = parser.parse(root)
        if(began!=windowGeneration){lastReadFailure="window_changed_during_read";return null}
        val metrics = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).currentWindowMetrics.bounds else {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m); android.graphics.Rect(0,0,m.widthPixels,m.heightPixels)
        }
        val public = parsed.allNodes.filter { it.visibleToUser && !it.password }.map { node ->
            Element(node.id,node.path,node.viewIdResourceName,node.className?.substringAfterLast('.') ?: "View",
                AccessibilityNodePrivacy.redactIdentifiers(node.displayLabel).take(500),node.bounds,node.enabled,node.visibleToUser,
                node.clickable,node.editable,node.scrollable,node.hasInput,node.checked,node.selected,node.stateDescription?.take(180),
                node.range?.let { "${it.current} (${it.min}..${it.max})" },
                ElementDisplay(
                    node.text?.takeUnless { node.editable || node.sensitiveContent }?.let { AccessibilityNodePrivacy.redactIdentifiers(it).take(500) },
                    node.contentDescription?.takeUnless { node.editable || node.sensitiveContent }?.let { AccessibilityNodePrivacy.redactIdentifiers(it).take(500) },node.parentPath))
        }
        val inViewport=public.filter{it.bounds.right>0&&it.bounds.bottom>0&&it.bounds.left<metrics.width()&&it.bounds.top<metrics.height()}
        if(inViewport.isEmpty()){lastReadFailure="offscreen_transition";return null}
        val retained = inViewport.take(400)
        // 스크린샷을 보낼 때 칠할 영역. 비밀번호 노드는 AI 요청 목록에서 빠지므로 여기서 위치만 따로 남긴다.
        val privateRegions = parsed.allNodes.filter { it.visibleToUser && !it.bounds.isEmpty &&
            (it.password || it.editable || it.sensitiveContent || AccessibilityNodePrivacy.isSensitivePreview(it)) }.map { it.bounds }
        return Observation(++nextSnapshot,pkg,readWindowId,epoch,visualGeneration,metrics.width(),metrics.height(),
            !parsed.truncated && inViewport.size == retained.size,retained,inViewport.size-retained.size,privateRegions)
    }

    suspend fun capture(): ScreenImage = captureMutex.withLock { captureUnlocked() }
    private suspend fun captureUnlocked(): ScreenImage {
        if(Build.VERSION.SDK_INT >= 30) {
            repeat(3) {attempt->
                try{return captureApi30()}catch(_:FrameChanged){if(attempt<2)delay(150)}
            }
            throw IllegalStateException("확인 중 화면이 바뀌었어요. 현재 화면에서 다시 확인해주세요.")
        }
        throw IllegalStateException("이 Android 버전에서는 이미지를 확인할 수 없어요.")
    }
    private class FrameChanged:RuntimeException()
    private class ScreenshotFailure(val code:Int):RuntimeException()
    /** Passive research capture includes the real overlay and IME. It never hides or moves user UI. */
    private fun queueResearchImage(){
        if(Build.VERSION.SDK_INT<30||!recording()||AppRuntime.log.imageSession()==null)return
        imageRequested=true
        if(researchCapture?.isActive==true)return
        researchCapture=main.launch {
            delay(450);imageRequested=false
            val session=AppRuntime.log.imageSession()?:return@launch
            try { captureMutex.withLock {
                if(!recording()||!unlocked()||AppRuntime.log.imageSession()!=session)return@withLock
                val before=mutex.withLock{withContext(reader){readTree()}}?:return@withLock
                val bitmap=screenshotBitmap()
                try{
                    val after=mutex.withLock{withContext(reader){readTree()}}?:throw FrameChanged()
                    if(!unlocked()||!after.sameContent(before)||before.visualGeneration!=after.visualGeneration ||
                        before.width!=bitmap.width||before.height!=bitmap.height)throw FrameChanged()
                    val image=compressImage(bitmap,after)
                    if(!recording()||!unlocked())return@withLock
                    AppRuntime.log.image(session,after,Base64.decode(image.base64,Base64.NO_WRAP),image.imageWidth,image.imageHeight)
                }finally{bitmap.recycle()}
            }} catch(e:TimeoutCancellationException){AppRuntime.log.imageUnavailable(session,"capture_timeout")}
              catch(e:CancellationException){throw e}
              catch(e:FrameChanged){AppRuntime.log.imageUnavailable(session,"screen_changed")}
              catch(e:ScreenshotFailure){AppRuntime.log.imageUnavailable(session,"capture_${e.code}")}
              catch(_:Exception){AppRuntime.log.imageUnavailable(session,"capture_failed")}
            finally {researchCapture=null;if(imageRequested)queueResearchImage()}
        }
    }
    @androidx.annotation.RequiresApi(30)
    private suspend fun screenshotBitmap():Bitmap {
        delay((600-(SystemClock.uptimeMillis()-lastCaptureAt)).coerceAtLeast(0))
        check(unlocked()&&!destroyed)
        lastCaptureAt=SystemClock.uptimeMillis()
        return withTimeout(4000){suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
                override fun onSuccess(result:ScreenshotResult){
                    val buffer=result.hardwareBuffer
                    val copied=try{val wrapped=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace);try{wrapped?.copy(Bitmap.Config.ARGB_8888,false)}finally{wrapped?.recycle()}}finally{buffer.close()}
                    if(!continuation.isActive){copied?.recycle();return}
                    if(copied==null)continuation.resumeWithException(ScreenshotFailure(-1))else continuation.resume(copied)
                }
                override fun onFailure(errorCode:Int){if(continuation.isActive)continuation.resumeWithException(ScreenshotFailure(errorCode))}
            })
        }}
    }
    private suspend fun compressImage(bitmap:Bitmap,observation:Observation):ScreenImage=withContext(Dispatchers.Default){
        var scale=minOf(1f,1280f/maxOf(bitmap.width,bitmap.height))
        repeat(5){
            val image=Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
            try{for(quality in listOf(75,55,35)){
                val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.JPEG,quality,it)}.toByteArray()
                if(bytes.size<=122880)return@withContext ScreenImage(observation,Base64.encodeToString(bytes,Base64.NO_WRAP),image.width,image.height)
            }}finally{if(image!==bitmap)image.recycle()}
            scale*=.75f
        };error("캡처 크기를 줄이지 못했어요.")
    }
    @androidx.annotation.RequiresApi(30)
    private suspend fun captureApi30(): ScreenImage {
        check(unlocked()) { "이 화면은 이미지를 확인할 수 없어요." }
        overlay.setHidden(true)
        try {
            delay(120)
            val before = observe()
            val bitmap = screenshotBitmap()
            try {
                val after = observe()
                if(before.packageName != after.packageName || before.windowId != after.windowId || before.visualGeneration != after.visualGeneration || !unlocked())throw FrameChanged()
                return compressImage(bitmap,after)

            } finally { bitmap.recycle() }
        } finally { if (!destroyed) overlay.setHidden(false) }
    }
    override fun onInterrupt() { if (AppRuntime.initialized) AppRuntime.loop.pause("화면 안내가 중단됐어요.") }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); visualGeneration++
        if(::overlay.isInitialized) overlay.configurationChanged()
        if(AppRuntime.initialized && AppRuntime.loop.state.value.phase==Phase.GUIDING) main.launch {
            runCatching {observe()}.getOrNull()?.let(AppRuntime.loop::onObservation)
        }
    }
    override fun onDestroy() {
        destroyed = true
        if (AppRuntime.initialized) AppRuntime.detach(this)
        runCatching { unregisterReceiver(lockReceiver) }
        if (::overlay.isInitialized) overlay.close()
        main.cancel(); reader.close()
        super.onDestroy()
    }
}
