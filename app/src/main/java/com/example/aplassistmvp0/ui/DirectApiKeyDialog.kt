package com.example.aplassistmvp0.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.DirectDecisionPolicy

/** 입력한 키는 저장 상태·로그에 넣지 않는다. 기존 키의 평문을 UI로 다시 읽지 않는다. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DirectApiKeyDialog(saved: Boolean, busy: Boolean, message: String,
                       onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val key = remember { TextFieldState() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("OpenAI API 키") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("휴대폰에서 OpenAI에 직접 연결합니다.")
                Text(if (saved) "키가 등록되어 있습니다. 바꿀 때만 새 키를 입력해주세요."
                    else "테스트용 OpenAI API 키를 입력해주세요.")
                OutlinedSecureTextField(state = key, label = { Text("API 키") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), textObfuscationMode = TextObfuscationMode.Hidden)
                Text("이 휴대폰에 암호화해서 저장합니다. 안내 테스트 한도는 기기당 하루 $${DirectDecisionPolicy.DAILY_LIMIT_USD}입니다.")
                if (message.isNotEmpty()) Text(message)
                if (saved) TextButton(onClick = onRemove, enabled = !busy) { Text("저장한 키 삭제") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key.text.toString()) },
            enabled = !busy && key.text.isNotBlank()) { Text(if (busy) "저장 중…" else "키 저장") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("닫기") } },
    )
}
