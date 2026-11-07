package com.example.aplassistmvp0.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.guide.ExperienceState
import com.example.aplassistmvp0.guide.ExperienceStore
import com.example.aplassistmvp0.guide.GuideController
import com.example.aplassistmvp0.guide.TargetApp

@Composable
fun ExperienceDialog(notebook: ExperienceState, installed: List<TargetApp>, onDismiss: () -> Unit) {
    var feature by remember { mutableStateOf("") }
    var appSearch by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<TargetApp?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("기억한 사용법") }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("이 휴대폰에서 완료를 확인한 사용법입니다. 버튼은 매번 현재 화면에서 다시 확인합니다.") }
            items(notebook.experiences, key = { it.id }) { experience ->
                Card { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(experience.appLabel, style = MaterialTheme.typography.titleMedium)
                    Text(experience.goal)
                    Text(if (!experience.reusable) "잘못된 안내 신고 · 재사용 중지" else if (experience.needsReview) "앱 변경 · 재확인 필요" else "완료 확인됨")
                    experience.steps.forEachIndexed { i, step -> Text("${i + 1}. ${step.message}") }
                    Text("화면 안내 API ${experience.apiRequests}회 · 기억 사용 ${experience.memoryHits}회 · 추정 $${String.format(java.util.Locale.ROOT, "%.5f", experience.estimatedCostUsd)}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { ExperienceStore.forget(experience.id) }) { Text("이 사용법 잊기") }
                } }
            }
            item { Text("앱에서 확인한 기능", style = MaterialTheme.typography.titleMedium) }
            items(notebook.apps.filter { it.capabilities.isNotEmpty() }, key = { "app:${it.packageName}" }) { app ->
                Text(app.label + "\n" + app.capabilities.take(12).joinToString("\n") {
                    val source = when (it.source) { "confirmed" -> "완료 확인"; "user" -> "사용자 설명"; "observed" -> "화면에서 봄"; else -> "AI 추정" }
                    "· ${it.text} ($source${if (it.revision != app.revision) " · 앱 변경 후 재확인 필요" else ""})"
                })
            }
            item {
                Text("모르는 앱의 기능도 직접 알려줄 수 있어요.")
                OutlinedButton(onClick = { selecting = !selecting }) { Text(selected?.label ?: "앱 고르기") }
                if (selecting) {
                    OutlinedTextField(value = appSearch, onValueChange = { appSearch = it }, label = { Text("앱 이름 검색") })
                    installed.filter { it.label.contains(appSearch, true) }.take(12).forEach { app ->
                        TextButton(onClick = { selected = app; selecting = false }) { Text(app.label) }
                    }
                }
                OutlinedTextField(value = feature, onValueChange = { feature = it.take(120) }, label = { Text("이 앱으로 할 수 있는 일") })
                Button(onClick = { selected?.let { ExperienceStore.feature(it, feature, "user") }; feature = "" },
                    enabled = selected != null && feature.isNotBlank()) { Text("기능 기억하기") }
                TextButton(onClick = { confirmClear = true }) { Text("모든 기억 지우기") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("모든 사용법 기억을 지울까요?") },
        text = { Text("저장한 경로와 앱 기능 메모를 지웁니다.") },
        confirmButton = { TextButton(onClick = { GuideController.stop(); ExperienceStore.clear(); confirmClear = false }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
}
