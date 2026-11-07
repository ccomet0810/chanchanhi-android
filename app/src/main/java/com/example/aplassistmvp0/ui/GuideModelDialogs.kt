package com.example.aplassistmvp0.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.GuideBackend
import com.example.aplassistmvp0.guide.GuideComparisonCase
import com.example.aplassistmvp0.guide.GuideComparisonResult
import com.example.aplassistmvp0.guide.GuideComparisonState
import com.example.aplassistmvp0.guide.GuideModelCodec
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GuideModelDialog(current: GuideBackend, onChoose: (GuideBackend) -> Unit, onDismiss: () -> Unit) {
    var showDirect by remember { mutableStateOf(!current.remote) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("안내에 사용할 AI") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideBackend.displayOrder.filter { it.remote || showDirect }.forEach { model ->
                Row {
                    RadioButton(selected = current == model, onClick = { onChoose(model) })
                    TextButton(onClick = { onChoose(model) }) { Text(model.label) }
                }
            }
            TextButton(onClick = { showDirect = !showDirect }) { Text(if (showDirect) "직접 API 방식 접기" else "직접 API 방식 더 보기") }
            Text("위 두 방식은 같은 AI 서버를 사용합니다. HTTPS로 연결하면 USB 없이 사용할 수 있습니다.")
            Text("ChatGPT는 구독 한도, Decisions 서버는 하루 $0.50 비용 한도를 사용합니다. 아래 세 방식은 기기에서 직접 호출하는 유료 API입니다.")
            Text("글과 버튼 정보로 먼저 판단합니다. 구분이 어려울 때만 입력란 등을 가린 화면을 한 번 추가 전송합니다. 이미지 확인은 한 요청의 안내 전체에서 최대 3회입니다.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

@Composable
fun GuideComparisonDialog(state: GuideComparisonState, busy: Boolean, runningCaseId: String?, current: GuideBackend,
    live: List<GuideComparisonResult>, message: String, onCompare: (GuideComparisonCase, String, List<GuideBackend>) -> Unit,
    onCancel: () -> Unit, onExport: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var caseId by remember { mutableStateOf(state.cases.firstOrNull()?.id) }
    var expected by remember(caseId) { mutableStateOf("") }
    var pickingCase by remember { mutableStateOf(false) }
    var pickingExpected by remember { mutableStateOf(false) }
    var detailModel by remember(caseId) { mutableStateOf<String?>(null) }
    var targets by remember { mutableStateOf(setOf(current)) }
    var showDirect by remember { mutableStateOf(!current.remote) }
    val case = state.cases.firstOrNull { it.id == caseId } ?: state.cases.firstOrNull()
    val labels = remember(case?.id) { case?.labels().orEmpty() }
    val latest = state.runs.firstOrNull { it.caseId == case?.id }
    val results = if (busy && runningCaseId == case?.id) live else latest?.results.orEmpty()
    val expectedForResult = if (busy) expected else latest?.expected.orEmpty()
    val reservation = remember(case?.id, targets) { case?.let { c -> runCatching {
        targets.sumOf { model -> model.reserve(GuideModelCodec.payload(model, JSONObject(c.input), JSONArray(c.choices))
            .toString().toByteArray(Charsets.UTF_8).size) }
    }.getOrNull() } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("같은 화면으로 모델 비교") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("저장한 같은 글·버튼 정보로 선택한 방식만 실행합니다. 이미지는 보관하지 않아 이 비교에는 포함하지 않습니다.")
            if (case == null) Text("아직 비교할 화면이 없습니다. 다른 앱에서 안내를 받은 뒤 돌아오세요.")
            else {
                OutlinedButton(onClick = { pickingCase = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("${clock(case.time)} · ${case.app} · ${case.step + 1}단계")
                }
                Text("요청: ${case.goal}")
                Text(JSONObject(case.input).optString("previousTarget").takeIf { it.isNotBlank() }?.let { "직전 안내: $it" } ?: "첫 안내 화면")
                OutlinedButton(onClick = { pickingExpected = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expected.isBlank()) "정답 지정: 선택 사항" else "정답: ${labels[expected]}")
                }
                GuideBackend.displayOrder.filter { it.remote || showDirect }.forEach { model ->
                    Row {
                        Checkbox(checked = model in targets, enabled = !busy, onCheckedChange = { checked ->
                            targets = if (checked) targets + model else targets - model
                        })
                        Text(model.label + if (model.subscription) " · 요금제" else " · 유료", Modifier.padding(top = 12.dp))
                    }
                }
                if (targets.any { !it.subscription }) reservation?.let { Text("유료 API 예약 상한 ${usd(it)} · 하루 한도 $0.50") }
                else Text("ChatGPT 요금제 한도를 사용합니다. 유료 API는 호출하지 않습니다.")
                Button(onClick = { onCompare(case, expected, targets.toList()) }, enabled = !busy && reservation != null && targets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "비교 중 · ${live.size}/${targets.size}" else "선택한 방식 실행")
                }
                TextButton(onClick = {
                    showDirect = !showDirect
                    if (!showDirect) targets = targets.filter { it.remote }.toSet()
                }, enabled = !busy) { Text(if (showDirect) "직접 API 방식 접기" else "직접 API 방식 더 보기") }
                if (showDirect) Text("기기에서 직접 호출하는 방식은 서버 경로와 연결 조건이 다릅니다. 구독은 출력 토큰 상한을 지원하지 않습니다.")
                if (busy) TextButton(onClick = onCancel) { Text("비교 중지") }
                results.sortedBy { it.durationMs }.forEach { row ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth()) {
                        Text(GuideBackend.from(row.backend).label, Modifier.weight(1f).padding(top = 10.dp), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { detailModel = if (detailModel == row.backend) null else row.backend }) { Text("자세히") }
                    }
                    if (row.error.isNotBlank()) Text("실패: ${row.error}") else {
                        Text("선택: ${labels[row.rawChoice] ?: row.rawChoice}")
                        if (row.rawChoice != row.choice) Text("취소 선택 차단 → 사용자 선택 안내")
                        if (expectedForResult.isNotBlank()) Text(if (row.rawChoice == expectedForResult) "정답 일치" else "정답 불일치")
                    }
                    Text("${String.format(Locale.US, "%.2f", row.durationMs / 1000.0)}초 · " +
                        if (GuideBackend.from(row.backend).subscription) "ChatGPT 요금제 사용"
                        else if (row.error.isNotBlank()) "실패·중단 비용 확인 필요" else usd(row.costUsd))
                    if (detailModel == row.backend) {
                        if (row.message.isNotBlank()) Text(row.message)
                        Text("입력 ${row.inputTokens} / 출력 ${row.outputTokens}토큰 · 왕복 ${row.durationMs}ms")
                        row.upstreamMs?.let { Text("서버에서 측정한 AI 호출 ${it}ms · 경로 ${row.transport}") }
                    }
                }
                if (results.isNotEmpty()) Text("시간은 연결 왕복 시간입니다. 유료 비용은 Standard 요금 추정이며 구독 사용량은 ChatGPT 설정에서 확인합니다.")
            }
            if (message.isNotBlank()) Text(message)
            if (state.runs.isNotEmpty()) Text("저장된 비교 ${state.runs.size}회 · 같은 사례를 다시 실행해 반복 측정할 수 있습니다.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("닫기") } }, dismissButton = {
        Row {
            TextButton(onClick = onExport, enabled = !busy && state.cases.isNotEmpty()) { Text("내보내기") }
            TextButton(onClick = onClear, enabled = !busy && state.cases.isNotEmpty()) { Text("지우기") }
        }
    })
    if (pickingCase) AlertDialog(onDismissRequest = { pickingCase = false }, title = { Text("비교할 화면") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            state.cases.forEach { c -> TextButton(onClick = { caseId = c.id; pickingCase = false }) {
                Text("${clock(c.time)} · ${c.app} · ${c.step + 1}단계\n${c.goal}\n직전: ${JSONObject(c.input).optString("previousTarget").ifBlank { "없음" }}")
            } }
        }
    }, confirmButton = { TextButton(onClick = { pickingCase = false }) { Text("닫기") } })
    if (pickingExpected) AlertDialog(onDismissRequest = { pickingExpected = false }, title = { Text("이 화면의 올바른 다음 단계") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { expected = ""; pickingExpected = false }) { Text("평가하지 않음") }
            labels.forEach { (value, name) -> TextButton(onClick = { expected = value; pickingExpected = false }) { Text(name) } }
        }
    }, confirmButton = { TextButton(onClick = { pickingExpected = false }) { Text("닫기") } })
}

private fun usd(value: Double) = String.format(Locale.US, "$%.6f", value)
private fun clock(value: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(value))
