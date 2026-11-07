package com.example.aplassistmvp0.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.BridgeConnectionConfig
import com.example.aplassistmvp0.guide.BridgeConnectionMode
import com.example.aplassistmvp0.guide.BridgeConnectionPolicy

/** 서버 연결만 편집·확인합니다. 연결 검사는 AI 추론을 요청하지 않습니다. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BridgeConnectionDialog(
    current: BridgeConnectionConfig,
    busy: Boolean,
    message: String,
    onCheck: (BridgeConnectionConfig) -> Unit,
    onSave: (BridgeConnectionConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(current.mode) }
    var endpoint by remember { mutableStateOf(if (current.mode == BridgeConnectionMode.HTTPS) current.endpoint else BridgeConnectionPolicy.PUBLIC_ENDPOINT) }
    var pin by remember { mutableStateOf(current.certificateSha256 ?: "") }
    // 개인 연결 키는 화면 저장 상태·클립보드·로그에 복사하지 않고 입력 중 메모리에만 보관합니다.
    val token = remember { TextFieldState() }
    var inputError by remember { mutableStateOf("") }
    fun candidate(): BridgeConnectionConfig? = try {
        inputError = ""
        BridgeConnectionPolicy.fromInput(current, mode,
            if (mode == BridgeConnectionMode.USB) BridgeConnectionPolicy.USB_ENDPOINT else endpoint,
            token.text.toString(), if (mode == BridgeConnectionMode.HTTPS) pin else null)
    } catch (error: IllegalArgumentException) {
        inputError = error.message ?: "연결 설정을 확인해주세요."
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 서버 연결") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mode = BridgeConnectionMode.HTTPS; token.edit { replace(0, length, "") }; inputError = "" }, enabled = !busy) {
                        Text(if (mode == BridgeConnectionMode.HTTPS) "✓ HTTPS" else "HTTPS")
                    }
                    OutlinedButton(onClick = { mode = BridgeConnectionMode.USB; token.edit { replace(0, length, "") }; inputError = "" }, enabled = !busy) {
                        Text(if (mode == BridgeConnectionMode.USB) "✓ USB" else "USB")
                    }
                }
                Text(if (mode == BridgeConnectionMode.HTTPS)
                    "PC를 거치지 않고 개인 서버에 연결합니다. Wi-Fi 또는 모바일 데이터가 필요합니다."
                else "USB와 PC 연결 프로그램이 필요합니다. 기존 USB 연결 키를 사용할 수 있습니다.")
                if (mode == BridgeConnectionMode.HTTPS) {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it.take(500) },
                        label = { Text("HTTPS 서버 주소") }, placeholder = { Text("https://서버주소") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                // 보안 입력란은 복사·잘라내기·드래그를 막고 마지막 입력 문자도 표시하지 않습니다.
                OutlinedSecureTextField(state = token,
                    label = { Text("개인 연결 키") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), textObfuscationMode = TextObfuscationMode.Hidden)
                Text(if (current.pairingToken.isNotEmpty())
                    "연결 키가 저장되어 있습니다. 같은 서버는 빈칸으로 두면 유지됩니다. 새 서버에는 새 키를 입력해주세요."
                else "개인 서버에서 발급한 연결 키를 입력해주세요. OpenAI API 키나 ChatGPT 로그인 토큰이 아닙니다.",
                    style = MaterialTheme.typography.bodySmall)
                if (mode == BridgeConnectionMode.HTTPS) {
                    OutlinedTextField(value = pin, onValueChange = { pin = it.take(100) },
                        label = { Text("인증서 SHA-256 핀 · 선택") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("공인 인증서 서버는 비워둘 수 있습니다. 인증서 핀은 개인 서버에서 안내한 값을 사용해주세요.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (inputError.isNotEmpty()) Text(inputError, color = MaterialTheme.colorScheme.error)
                if (message.isNotEmpty()) Text(message)
                OutlinedButton(onClick = { candidate()?.let(onCheck) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text(if (busy) "처리 중…" else "연결 검사") }
                Text("연결 검사는 AI 요청을 보내지 않습니다. 선택한 ChatGPT 구독 또는 Decisions의 서버 준비 상태를 확인합니다.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDisconnect, enabled = !busy) { Text("연결 해제") }
            }
        },
        confirmButton = { TextButton(onClick = { candidate()?.let(onSave) }, enabled = !busy) { Text("설정 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
