package com.example.aplassistmvp0.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.example.aplassistmvp0.guide.GuideTrace
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.GuideHistoryEntry
import com.example.aplassistmvp0.guide.GuideHistoryPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun GuideHistoryDialog(entries: List<GuideHistoryEntry>, onCopy: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit, onExport: () -> Unit = {}) {
    val traces by GuideTrace.events.collectAsState()
    val grouped = entries.groupBy { it.requestId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("최근 안내 기록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("요청별 요약과 상세 처리 과정을 이 휴대폰에 저장합니다. 최신 요청부터 표시합니다.", style = MaterialTheme.typography.bodySmall)
                if (entries.isEmpty()) Text("아직 안내 기록이 없습니다.")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(grouped.keys.toList()) { requestId ->
                        val entry = grouped.getValue(requestId).first()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            var showLabels by remember(entry.timeMillis) { mutableStateOf(false) }
                            val time = Instant.ofEpochMilli(entry.timeMillis).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                            Text("$time · ${GuideHistoryPolicy.phaseLabel(entry.phase, entry.action)}", fontWeight = FontWeight.Bold)
                            Text("앱: ${entry.appLabel}", style = MaterialTheme.typography.bodySmall)
                            Text("요청: ${entry.goal}")
                            Text(entry.message, style = MaterialTheme.typography.titleMedium)
                            Text(buildString {
                                append("${entry.step}단계")
                                entry.elapsedMillis?.let { append(" · 제출 후 ${seconds(it)}초") }
                                entry.apiMillis?.let { append(" · 최근 API ${seconds(it)}초") }
                                entry.candidateCount?.let { append(" · 화면 후보 ${it}개") }
                            }, style = MaterialTheme.typography.bodySmall)
                            if (entry.candidateLabels.isNotEmpty()) {
                                TextButton(onClick = { showLabels = !showLabels }) { Text(if (showLabels) "읽은 항목 접기" else "읽은 항목 보기") }
                                if (showLabels) Text(entry.candidateLabels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            var details by remember(requestId) { mutableStateOf(false) }
                            TextButton(onClick = { details = !details }) { Text(if (details) "처리 과정 접기" else "처리 과정 보기") }
                            if (details) {
                                val events = traces.filter { it.requestId == requestId }
                                if (events.isEmpty()) Text("업데이트 이전 기록입니다. 새 요청부터 상세 과정이 저장됩니다.", style = MaterialTheme.typography.bodySmall)
                                else {
                                  if (events.size > 200) Text("최근 200개 과정을 표시합니다. 전체 기록은 파일로 저장할 수 있어요.", style = MaterialTheme.typography.bodySmall)
                                  events.takeLast(200).forEach { event ->
                                    Text("+${event.elapsedMs ?: 0}ms · ${GuideTrace.stageLabel(event.stage)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    Text(event.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                                  }
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Row {
                    TextButton(onClick = onCopy, enabled = entries.isNotEmpty()) { Text("기록 복사") }
                    TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("기록 지우기") }
                }
                TextButton(onClick = onExport, enabled = traces.isNotEmpty()) { Text("상세 기록 파일 저장") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private fun seconds(millis: Long): String = String.format(Locale.KOREA, "%.2f", millis / 1000.0)
