package com.example.aplassistmvp0.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.BridgeStatus
import com.example.aplassistmvp0.guide.GuideUiState
import com.example.aplassistmvp0.guide.TargetApp
import com.example.aplassistmvp0.state.AssistUiState

/** 목적에 맞는 설치 앱을 제안하고, 사용자가 고른 앱을 직접 열도록 안내한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceGuideScreen(
    assist: AssistUiState,
    guide: GuideUiState,
    bridge: BridgeStatus,
    checkingBridge: Boolean,
    availableApps: List<TargetApp>,
    startingGuide: Boolean,
    routingMessage: String,
    resolveApp: (String) -> TargetApp?,
    onCheckConnection: () -> Unit,
    onAccessibilitySettings: () -> Unit,
    onStart: (String, String?) -> Unit,
    onStop: () -> Unit,
    onOpenConnectionSettings: () -> Unit = {},
    connectionDescription: String = "OpenAI에 직접 연결합니다. Wi-Fi 또는 모바일 데이터가 필요합니다.",
    assistantReady: Boolean = false,
    onAssistantSetup: () -> Unit = {},
    history: List<com.example.aplassistmvp0.guide.GuideHistoryEntry> = emptyList(),
    onCopyHistory: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onExportHistory: () -> Unit = {},
    route: com.example.aplassistmvp0.guide.GoalRoute? = null,
    onRouteApp: (TargetApp) -> Unit = {},
    onRouteAnswer: (String) -> Unit = {},
    notebook: com.example.aplassistmvp0.guide.ExperienceState = com.example.aplassistmvp0.guide.ExperienceState(),
    onCompleted: () -> Unit = {},
    onWrong: () -> Unit = {},
    onHome: () -> Unit = {},
    modelDescription: String = "Decisions · Luna",
    subscriptionSelected: Boolean = false,
    remoteSelected: Boolean = subscriptionSelected,
    onOpenModelSettings: () -> Unit = {},
    onOpenModelComparison: () -> Unit = {},
) {
    var request by rememberSaveable { mutableStateOf("") }
    var voiceMessage by rememberSaveable { mutableStateOf("") }
    var listening by rememberSaveable { mutableStateOf(false) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showNotebook by rememberSaveable { mutableStateOf(false) }
    var clarification by rememberSaveable { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(route) { if (route != null) listState.animateScrollToItem(5) }
    androidx.compose.runtime.LaunchedEffect(guide.phase) { if (guide.phase == "opening_app") listState.animateScrollToItem(4) }
    val selectedApp = availableApps.firstOrNull { it.packageName == selectedPackage }
    val automaticApp = androidx.compose.runtime.remember(request, availableApps) { resolveApp(request) }
    val targetApp = if (selectedPackage == null) automaticApp else selectedApp
    // 기기에 설치된 음성 인식 화면을 이용한다. APL은 녹음 파일을 만들거나 전송하지 않는다.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        when {
            result.resultCode != Activity.RESULT_OK -> voiceMessage = "음성 입력을 취소했습니다. 다시 말하거나 글로 입력해주세요."
            recognized.isNullOrBlank() -> voiceMessage = "말씀을 알아듣지 못했습니다. 다시 말하거나 글로 입력해주세요."
            route != null -> {
                val chosen = resolveApp(recognized)?.takeIf { match -> route.candidates.any { it.packageName == match.packageName } }
                if (chosen != null) onRouteApp(chosen)
                else if (route.candidates.isEmpty() && route.source != "manual") onRouteAnswer(recognized.take(200))
                else { request = recognized.take(500); voiceMessage = "후보 앱을 누르거나 새 요청으로 안내를 시작해주세요." }
            }
            else -> { request = recognized.take(500); voiceMessage = "인식한 문장을 확인하고 안내를 시작해주세요." }
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("APL · 음성으로 앱 안내", fontWeight = FontWeight.Bold) }) },
    ) { insets ->
        // 모든 안내와 버튼을 스크롤 영역에 넣어 큰 글자에서도 아래 버튼에 접근할 수 있다.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("무엇을 하고 싶으세요?", style = MaterialTheme.typography.headlineSmall)
                Text("예: 네이버 쇼핑을 하고 싶어 · 메시지를 보내고 싶어 · 화면을 밝게 하고 싶어", Modifier.padding(top = 8.dp))
            }
            item {
                Text(if (assistantReady) "길게 눌러 부르는 도우미: APL로 연결됨"
                    else "보고 있던 화면에서 바로 말하려면 APL을 기본 도우미로 설정해주세요.")
                OutlinedButton(onClick = onAssistantSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(if (assistantReady) "길게 누르기 설정 확인" else "길게 눌러 APL 부르기 설정")
                }
            }
            item {
                Button(
                    onClick = {
                        voiceMessage = ""
                        try {
                            com.example.aplassistmvp0.guide.GuideController.silence()
                            listening = true
                            voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "어떤 앱에서 무엇을 하고 싶으세요?")
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            })
                        } catch (_: ActivityNotFoundException) {
                            listening = false
                            voiceMessage = "음성 인식 앱이 없습니다. 아래에 요청을 글로 입력해주세요."
                        } catch (_: SecurityException) {
                            listening = false
                            voiceMessage = "기기의 음성 인식 권한을 확인하거나 글로 입력해주세요."
                        }
                    },
                    enabled = !listening && !guide.busy && !startingGuide,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) { Text(if (listening) "말씀을 듣고 있어요" else "말로 요청하기", style = MaterialTheme.typography.titleLarge) }
            }
            item {
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("요청 문장") },
                    minLines = 2,
                    enabled = !guide.busy && !startingGuide,
                )
                if (voiceMessage.isNotEmpty()) Text(voiceMessage, Modifier.padding(top = 8.dp))
                Text(
                    when {
                        targetApp != null -> "열 앱: ${targetApp.label}${if (selectedPackage == null) " · 자동 선택" else " · 직접 선택"}"
                        selectedPackage != null -> "선택한 앱이 설치돼 있지 않습니다. 다른 앱을 골라주세요."
                        else -> "사용할 앱을 말하거나 ‘앱 선택’에서 골라주세요."
                    }, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = { appSearch = ""; showAppPicker = true },
                    enabled = !guide.busy && !startingGuide && !listening,
                ) { Text("앱 선택") }
                if (routingMessage.isNotEmpty()) Text(routingMessage, color = MaterialTheme.colorScheme.error)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI 안내", fontWeight = FontWeight.Bold)
                        Text(guide.message, style = MaterialTheme.typography.titleMedium)
                        if (guide.goal.isNotEmpty()) Text("요청: ${guide.goal}")
                        if (guide.phase == "opening_app") OutlinedButton(onClick = onHome) { Text("홈 화면에서 앱 찾기") }
                        if (guide.goal.isNotEmpty() && guide.targetAppLabel.isNotEmpty()) Text("안내 앱: ${guide.targetAppLabel}")
                        if (guide.step > 0) Text("${guide.step}번째 안내 · 실제 항목은 직접 눌러주세요.")
                        OutlinedButton(onClick = { showHistory = true }) { Text("최근 안내 기록") }
                        OutlinedButton(onClick = { showNotebook = true }) { Text("기억한 사용법 · ${notebook.experiences.size}개") }
                        if (guide.goal.isNotBlank() && guide.phase in setOf("guiding", "confirming", "paused", "idle")) {
                            Button(onClick = onCompleted, modifier = Modifier.fillMaxWidth()) { Text("원하던 작업 완료") }
                            TextButton(onClick = onWrong) { Text("잘못 안내받았어요 · 다시 확인") }
                        }
                    }
                }
            }
            if (route != null) item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(route.question, style = MaterialTheme.typography.titleLarge)
                        route.candidates.forEach { app ->
                            Button(onClick = { onRouteApp(app) }, enabled = !startingGuide,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(app.label) }
                        }
                        route.answers.forEach { answer ->
                            OutlinedButton(onClick = { onRouteAnswer(answer) }, enabled = !startingGuide,
                                modifier = Modifier.fillMaxWidth()) { Text(answer) }
                        }
                        if (route.candidates.isEmpty() && route.source != "manual") {
                            OutlinedTextField(value = clarification, onValueChange = { clarification = it.take(200) }, label = { Text("답을 말씀하거나 입력해주세요") })
                            Button(onClick = { val answer = clarification; clarification = ""; onRouteAnswer(answer) },
                                enabled = clarification.isNotBlank() && !startingGuide) { Text("이 답으로 계속") }
                        }
                        TextButton(onClick = { appSearch = ""; showAppPicker = true }) { Text("모든 앱에서 직접 선택") }
                    }
                }
            }
            item {
                Button(
                    onClick = { onStart(request, selectedPackage) },
                    enabled = request.isNotBlank() && assist.serviceConnected && !guide.busy && !startingGuide && !listening,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("안내 시작", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 8.dp)) {
                    Text("안내 중지")
                }
                Text("앱 선택에는 요청과 설치 앱 이름·기능 메모를 AI에 보냅니다. 고른 앱은 직접 열고, 화면 안내의 버튼도 직접 눌러주세요.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Text("접근성 정보가 없는 화면은 항목 강조가 어려워 수동 안내가 필요할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (assist.serviceConnected) "화면 읽기: 연결됨" else "화면 읽기: 접근성 설정 필요", fontWeight = FontWeight.Bold)
                        if (!assist.serviceConnected) OutlinedButton(onClick = onAccessibilitySettings) { Text("접근성 설정 열기") }
                        Text(if (subscriptionSelected) {
                            if (bridge.ready) "AI: ChatGPT 구독 연결됨" else "AI: ChatGPT 구독 연결 확인 필요"
                        } else if (remoteSelected) {
                            if (bridge.ready) "AI: Decisions 서버 연결됨" else "AI: Decisions 서버 연결 확인 필요"
                        } else if (bridge.ready) "AI: API 키 등록됨" else "AI: API 키 설정 확인 필요", fontWeight = FontWeight.Bold)
                        Text(bridge.message)
                        Text(connectionDescription, style = MaterialTheme.typography.bodySmall)
                        Text("화면 안내 AI: $modelDescription")
                        OutlinedButton(onClick = onOpenModelSettings) { Text("안내 AI 선택") }
                        OutlinedButton(onClick = onOpenModelComparison) { Text("같은 화면으로 모델 비교") }
                        OutlinedButton(onClick = onOpenConnectionSettings) { Text(if (remoteSelected) "AI 서버 연결 설정" else "API 키 설정") }
                        OutlinedButton(onClick = onCheckConnection, enabled = !checkingBridge) {
                            Text(if (checkingBridge) "확인 중…" else "연결 상태 확인")
                        }
                    }
                }
            }
        }
    }
    if (showHistory) GuideHistoryDialog(history, onCopyHistory, onClearHistory, onDismiss = { showHistory = false }, onExport = onExportHistory)
    if (showNotebook) ExperienceDialog(notebook, availableApps, onDismiss = { showNotebook = false })
    if (showAppPicker) AlertDialog(
        onDismissRequest = { showAppPicker = false },
        title = { Text("사용할 앱 선택") },
        text = {
            Column {
                OutlinedTextField(value = appSearch, onValueChange = { appSearch = it.take(80) }, label = { Text("앱 이름 검색") }, singleLine = true)
                TextButton(onClick = { selectedPackage = null; showAppPicker = false }) { Text("요청에 맞게 자동 선택") }
                // 전체 앱 목록은 로컬 선택 창에만 표시하며 설치 앱 목록을 서버에 전송하지 않는다.
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(availableApps.filter { it.label.contains(appSearch.trim(), ignoreCase = true) }, key = { it.packageName }) { app ->
                        TextButton(
                            onClick = { selectedPackage = app.packageName; showAppPicker = false; if (route != null) onRouteApp(app) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(app.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAppPicker = false }) { Text("닫기") } },
    )
}
