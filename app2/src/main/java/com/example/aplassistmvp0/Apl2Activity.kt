package com.example.aplassistmvp0

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aplassistmvp0.apl2.AplAppViewModel
import com.example.aplassistmvp0.apl2.design.*
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.assistant.AplVoiceInteractionService
import com.example.aplassistmvp0.guide.*
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.update.PlayUpdateCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: AplAppViewModel
    private val updates = PlayUpdateCoordinator(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        AplPreferences.initialize(applicationContext)
        GuideController.initialize(applicationContext)
        model = ViewModelProvider(this)[AplAppViewModel::class.java]
        setContent {
            val updateReady by updates.readyToInstall.collectAsStateWithLifecycle()
            AplTheme { AplApplication(model, intent.getBooleanExtra(EXTRA_SETUP, false), ::openSettings, updateReady, updates::installDownloaded) }
        }
    }

    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refresh() }

    private fun openSettings(action: String) {
        val target = Intent(action).apply {
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(target) }.onFailure {
            Toast.makeText(this, "이 기기에서는 해당 설정을 바로 열 수 없어요. 설정 앱에서 확인해주세요.", Toast.LENGTH_LONG).show()
        }
    }

    companion object { const val EXTRA_SETUP = "apl2.open_setup" }
}

@Composable
private fun AplApplication(model: AplAppViewModel, startWithSetup: Boolean, openSettings: (String) -> Unit,
                           updateReady: Boolean = false, installUpdate: () -> Unit = {}) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val screenGuideName = stringResource(R.string.accessibility_service_name)
    val assist by AssistStateRepository.state.collectAsStateWithLifecycle()
    val guide by GuideStateRepository.state.collectAsStateWithLifecycle()
    val history by GuideHistory.entries.collectAsStateWithLifecycle()
    val preferences by AplPreferences.state.collectAsStateWithLifecycle()
    val setup by model.setup.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val connectionMessage by model.connectionMessage.collectAsStateWithLifecycle()
    val connectionBusy by model.connectionBusy.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (startWithSetup) "setup" else "home") }
    var selectedHistory by rememberSaveable { mutableLongStateOf(-1) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf("") }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refresh()
        localMessage = if (granted) "마이크를 사용할 수 있어요." else "글쓰기로도 요청할 수 있어요. 마이크는 앱 권한에서 허용할 수 있어요."
    }
    val mainPage = page in listOf("home", "history", "settings")
    fun navigate(next: String) { if (page == "connection") model.dismissConnection(); page = next; localMessage = "" }
    fun back() = navigate(when (page) { "detail" -> "history"; "setup" -> "home"; else -> "settings" })
    BackHandler(page != "home") { if (mainPage) navigate("home") else back() }
    fun openAssistant() {
        when {
            !assist.serviceConnected -> navigate("setup")
            !setup.assistant -> navigate("call")
            connection.pairingToken.isBlank() -> navigate("connection")
            !AplVoiceInteractionService.requestSession() -> { navigate("call"); localMessage = "도우미 연결을 확인해주세요." }
        }
    }
    val title = when (page) {
        "home" -> appName; "history" -> "기록"; "settings" -> "설정"; "detail" -> "안내 기록"
        "setup" -> "처음 설정"; "voice" -> "음성 안내"; "screen" -> "화면 안내"; "motion" -> "움직임"
        "call" -> "호출 방법"; "permissions" -> "권한"; "data" -> "기록과 화면 분석"; else -> "AI 연결"
    }
    Scaffold(
        containerColor = AplTokens.canvas,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (mainPage) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                AplFloatingNavigation(listOf("home", "history", "settings").indexOf(page), { navigate(listOf("home", "history", "settings")[it]) })
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = key(page) { rememberLazyListState() },
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = AplTokens.maxContentWidth).fillMaxSize().consumeWindowInsets(insets).testTag("page-$page"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = insets.calculateTopPadding() + 16.dp, bottom = insets.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { AplPageHeader(title, if (mainPage) null else ({ back() })) }
                if (localMessage.isNotEmpty()) item { Notice(localMessage) }
                if (updateReady) item {
                    AplCard {
                        Text("새 버전을 받았어요", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text("설치하면 앱이 잠깐 닫혔다가 다시 열려요.", style = MaterialTheme.typography.bodyMedium)
                        AplButton("지금 업데이트", AplIcon.Check, installUpdate, Modifier.fillMaxWidth())
                    }
                }
                when (page) {
                    "home" -> {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(AplTokens.radiusCard), color = AplTokens.ink, modifier = Modifier.size(76.dp)) {
                                    Box(contentAlignment = Alignment.Center) { AplGlyph(AplIcon.Help, Modifier.size(38.dp), AplTokens.surface) }
                                }
                                Text("무엇을\n도와드릴까요?", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                                AplButton("도우미 열기", AplIcon.Mic, ::openAssistant, Modifier.fillMaxWidth())
                            }
                        }
                        if (!assist.serviceConnected || !setup.assistant || connection.pairingToken.isBlank()) item {
                            val count = listOf(assist.serviceConnected, setup.assistant, connection.pairingToken.isNotBlank()).count { it }
                            AplSettingRow("처음 설정", { navigate("setup") }, value = "$count/3", icon = AplIcon.Settings)
                        }
                        if (guide.goal.isNotBlank() && guide.phase !in listOf("idle", "done")) item {
                            AplCard {
                                Text("진행 중인 안내", style = MaterialTheme.typography.labelLarge)
                                Text(guide.goal, style = MaterialTheme.typography.titleMedium)
                                Text(guide.message, style = MaterialTheme.typography.bodyMedium)
                                AplButton("안내 끝내기", AplIcon.Close, { GuideController.stop() }, Modifier.fillMaxWidth(), primary = false)
                            }
                        }
                    }
                    "history" -> {
                        val latest = history.distinctBy { it.requestId }
                        if (latest.isEmpty()) item { EmptyState("아직 안내 기록이 없어요", AplIcon.History) }
                        items(latest, key = { "${it.requestId}-${it.timeMillis}" }) { entry ->
                            AplSettingRow(entry.goal, { selectedHistory = entry.requestId; navigate("detail") }, value = entry.appLabel)
                        }
                        if (latest.isNotEmpty()) item { AplButton("기록 지우기", AplIcon.Trash, { confirmClear = true }, Modifier.fillMaxWidth(), primary = false) }
                    }
                    "detail" -> {
                        val entries = history.filter { it.requestId == selectedHistory }
                        if (entries.isEmpty()) item { EmptyState("이 기록은 지워졌어요", AplIcon.History) }
                        items(entries, key = { "${it.timeMillis}-${it.step}-${it.phase}" }) { entry ->
                            AplCard {
                                Text(entry.goal, style = MaterialTheme.typography.titleMedium)
                                Text(entry.appLabel + "  " + formatTime(entry.timeMillis), style = MaterialTheme.typography.bodySmall)
                                Text(entry.message, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    "settings" -> {
                        item { AplSettingRow("호출 방법", { navigate("call") }, icon = AplIcon.Phone) }
                        item { AplSettingRow("음성 안내", { navigate("voice") }, icon = AplIcon.Volume) }
                        item { AplSettingRow("화면 안내", { navigate("screen") }, icon = AplIcon.Help) }
                        item { AplSettingRow("움직임", { navigate("motion") }, icon = AplIcon.ArrowRight) }
                        item { AplSettingRow("권한", { navigate("permissions") }, icon = AplIcon.Lock) }
                        item { AplSettingRow("기록과 화면 분석", { navigate("data") }, icon = AplIcon.History) }
                        item { AplSettingRow("AI 연결", { navigate("connection") }, value = if (connection.pairingToken.isBlank()) "설정 필요" else "저장됨") }
                    }
                    "setup" -> {
                        item { Text("세 가지만 준비해주세요.", style = MaterialTheme.typography.headlineSmall) }
                        item { SetupCard("1", "화면 읽기", assist.serviceConnected, "화면의 버튼과 위치를 읽어 안내해요.", "접근성 설정 열기") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) } }
                        item { SetupCard("2", "기본 도우미", setup.assistant, "다른 앱을 사용하면서도 도우미를 부를 수 있어요.", "도우미 선택하기") { openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) } }
                        item { SetupCard("3", "AI 연결", connection.pairingToken.isNotBlank(), "기존 개인 서버의 연결 키를 등록해주세요.", "연결 설정하기") { navigate("connection") } }
                        item { AplSettingRow("마이크", { if (setup.microphone) openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }, value = if (setup.microphone) "허용됨" else "선택", icon = AplIcon.Mic) }
                        item { Text("마이크 없이 글쓰기로 요청할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = AplTokens.muted) }
                    }
                    "call" -> {
                        item { Text("기본 도우미로 선택해주세요", style = MaterialTheme.typography.headlineSmall) }
                        item { Text("디지털 어시스턴트 앱 목록에서 ‘$appName’ 항목을 선택하세요. 휴대폰의 도우미 호출 동작으로 현재 앱 위에서 열 수 있어요.", style = MaterialTheme.typography.bodyLarge) }
                        item { AplButton("도우미 선택하기", AplIcon.Phone, { openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) }, Modifier.fillMaxWidth()) }
                        item { AplCard {
                            Text("갤럭시 측면 버튼", style = MaterialTheme.typography.titleMedium)
                            Text("기기 설정의 유용한 기능에서 측면 버튼 길게 누르기를 확인해주세요. 지원하는 동작과 메뉴 이름은 기기에 따라 달라요.", style = MaterialTheme.typography.bodyMedium)
                        } }
                        item { AplButton("불러보기", AplIcon.Mic, { if (!AplVoiceInteractionService.requestSession()) localMessage = "기본 도우미에서 ‘$appName’ 항목을 선택해주세요." }, Modifier.fillMaxWidth(), primary = false, enabled = setup.assistant) }
                    }
                    "permissions" -> {
                        item { AplSettingRow("화면 읽기", { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, value = if (assist.serviceConnected) "연결됨" else "허용 필요") }
                        item { Text("현재 화면의 항목과 위치를 읽어요. 화면 밖 항목을 찾을 때 제한된 스크롤 탐색을 할 수 있어요.", style = MaterialTheme.typography.bodyMedium) }
                        item { AplSettingRow("마이크", { if (setup.microphone) openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) }, value = if (setup.microphone) "허용됨" else "선택") }
                        item { Text("도우미를 부르면 바로 음성 입력을 시작해요. 입력 내용을 확인한 뒤 전송을 누르세요. 음성 원음은 저장하지 않아요.", style = MaterialTheme.typography.bodyMedium) }
                        item { AplSettingRow("기본 도우미", { openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS) }, value = if (setup.assistant) appName else "선택 필요") }
                        item { Text("이전 버전의 화면 읽기도 켜져 있다면 ‘$screenGuideName’ 항목만 켜주세요.", style = MaterialTheme.typography.bodyMedium, color = AplTokens.muted) }
                    }
                    "voice" -> {
                        item { AplToggleRow("음성 안내 사용", preferences.voiceEnabled, { enabled -> AplPreferences.update(context) { it.copy(voiceEnabled = enabled) }; if (!enabled) GuideController.silence() }) }
                        item { AplCard {
                            Text("말하는 속도", style = MaterialTheme.typography.titleMedium)
                            Text(when { preferences.speechRate < .9f -> "느리게"; preferences.speechRate > 1.1f -> "빠르게"; else -> "보통" }, style = MaterialTheme.typography.bodyLarge)
                            Slider(value = preferences.speechRate, onValueChange = { rate -> AplPreferences.update(context) { it.copy(speechRate = rate) } }, valueRange = .7f..1.3f, steps = 5, enabled = preferences.voiceEnabled, modifier = Modifier.testTag("speech-rate"))
                        } }
                        item { AplButton("미리 듣기", AplIcon.Volume, { GuideController.silence(); GuideController.announceChoice("동그란 버튼을 오른쪽으로 밀어주세요.") }, Modifier.fillMaxWidth(), enabled = preferences.voiceEnabled) }
                    }
                    "screen" -> {
                        item { AplToggleRow("안내 글자 크게", preferences.largeGuide, { enabled -> AplPreferences.update(context) { it.copy(largeGuide = enabled) } }) }
                        item { Text("안내 예시", style = MaterialTheme.typography.labelLarge, color = AplTokens.muted) }
                        item { Surface(color = AplTokens.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(AplTokens.radiusCard), border = BorderStroke(2.dp, AplTokens.ink)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("동그란 버튼을\n오른쪽으로 밀어주세요", fontSize = if (preferences.largeGuide) 28.sp else 22.sp, lineHeight = if (preferences.largeGuide) 40.sp else 32.sp, fontWeight = FontWeight.Bold)
                                AplButton("다시 듣기", AplIcon.Volume, { GuideController.silence(); GuideController.announceChoice("동그란 버튼을 오른쪽으로 밀어주세요.") }, Modifier.fillMaxWidth(), primary = false, enabled = preferences.voiceEnabled)
                                AplButton("안내 끝내기", AplIcon.Close, { localMessage = "이 화면은 안내 모양을 확인하는 예시예요." }, Modifier.fillMaxWidth(), primary = false)
                            }
                        } }
                        item { Text("휴대폰의 글자 크기 설정도 함께 적용돼요.", style = MaterialTheme.typography.bodyMedium, color = AplTokens.muted) }
                    }
                    "motion" -> {
                        item { AplToggleRow("움직임 줄이기", preferences.reduceMotion, { enabled -> AplPreferences.update(context) { it.copy(reduceMotion = enabled) } }, "반복되는 강조 효과를 줄여요.") }
                        item { AplCard {
                            AplGlyph(AplIcon.ArrowRight, Modifier.size(36.dp))
                            Text(if (preferences.reduceMotion) "고정된 표시로 안내해요." else "행동 방향을 표시해요.", style = MaterialTheme.typography.bodyLarge)
                        } }
                    }
                    "data" -> {
                        item { AplCard {
                            Text("화면은 필요한 만큼만", style = MaterialTheme.typography.titleMedium)
                            Text("접근성 정보로 먼저 찾아요. 구분하기 어려울 때 필요한 화면 한 장을 가려서 AI 서버에 보내요. 보안 화면은 전송하지 않아요.", style = MaterialTheme.typography.bodyMedium)
                            Text("화면 캡처와 음성 원음은 저장하지 않아요. 요청과 안내 기록은 이 휴대폰에 남아요.", style = MaterialTheme.typography.bodyMedium)
                        } }
                        item { AplButton("안내 기록 지우기", AplIcon.Trash, { confirmClear = true }, Modifier.fillMaxWidth(), primary = false, enabled = history.isNotEmpty()) }
                    }
                    "connection" -> {
                        item { ConnectionForm(connection, connectionBusy, connectionMessage, model::saveConnection, model::checkConnection, model::disconnect) }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("안내 기록을 지울까요?") },
        text = { Text("이 휴대폰에 저장된 요청과 안내 기록이 지워져요.") },
        confirmButton = { TextButton(onClick = { GuideHistory.clear(); GuideTrace.clear(); confirmClear = false; if (page == "detail") navigate("history") }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
    )
}

@Composable
private fun EmptyState(message: String, icon: AplIcon) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AplGlyph(icon, Modifier.size(40.dp), AplTokens.muted)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = AplTokens.muted)
    }
}

@Composable
private fun Notice(message: String) { Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

@Composable
private fun SetupCard(number: String, title: String, ready: Boolean, description: String, action: String, onClick: () -> Unit) {
    AplCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(AplTokens.radiusPill), color = AplTokens.ink, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { if (ready) AplGlyph(AplIcon.Check, Modifier.size(20.dp), AplTokens.surface) else Text(number, color = AplTokens.surface) }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (ready) Text("완료", style = MaterialTheme.typography.labelMedium)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium)
        AplButton(action, AplIcon.ArrowRight, onClick, Modifier.fillMaxWidth(), primary = !ready)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ConnectionForm(current: BridgeConnectionConfig, busy: Boolean, message: String,
                           save: (BridgeConnectionConfig) -> Unit, check: (BridgeConnectionConfig) -> Unit, disconnect: () -> Unit) {
    var endpoint by remember { mutableStateOf(if (current.mode == BridgeConnectionMode.HTTPS) current.endpoint else BridgeConnectionPolicy.PUBLIC_ENDPOINT) }
    val token = remember { TextFieldState() }
    var error by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    fun candidate(): BridgeConnectionConfig? = runCatching {
        BridgeConnectionPolicy.fromInput(current, BridgeConnectionMode.HTTPS, endpoint, token.text.toString(), current.certificateSha256)
    }.onFailure { error = it.message ?: "주소와 연결 키를 확인해주세요." }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("개인 서버에 연결해주세요", style = MaterialTheme.typography.headlineSmall)
        Text("기존 ChatGPT 구독 연결을 사용해요. 연결 설정은 앱 버전마다 따로 저장돼요.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(endpoint, { endpoint = it.take(500); error = "" }, Modifier.fillMaxWidth(), label = { Text("서버 주소") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedSecureTextField(token, Modifier.fillMaxWidth(), label = { Text("개인 연결 키") }, enabled = !busy, textObfuscationMode = TextObfuscationMode.Hidden)
        Text(if (current.pairingToken.isBlank()) "서버에서 발급한 연결 키를 입력하세요. OpenAI API 키가 아니에요." else "키가 저장되어 있어요. 같은 서버는 빈칸으로 두면 유지돼요.", style = MaterialTheme.typography.bodySmall, color = AplTokens.muted)
        if (error.isNotBlank()) Notice(error)
        if (message.isNotBlank()) Notice(message)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        AplButton("저장하기", AplIcon.Check, { candidate()?.let { save(it); token.edit { replace(0, length, "") }; error = "" } }, Modifier.fillMaxWidth(), enabled = !busy)
        AplButton("연결 확인", AplIcon.ArrowRight, { candidate()?.let(check) }, Modifier.fillMaxWidth(), primary = false, enabled = !busy)
        if (current.pairingToken.isNotBlank()) AplButton("연결 해제", AplIcon.Close, { confirmDisconnect = true }, Modifier.fillMaxWidth(), primary = false, enabled = !busy)
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("AI 연결을 해제할까요?") }, text = { Text("현재 안내가 끝나고 저장된 연결 키가 삭제돼요.") }, confirmButton = { TextButton(onClick = { disconnect(); confirmDisconnect = false }) { Text("해제하기") } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("취소") } })
}

private fun formatTime(time: Long): String = DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
