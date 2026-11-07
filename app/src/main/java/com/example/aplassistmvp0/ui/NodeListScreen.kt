package com.example.aplassistmvp0.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.state.AssistUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MVP-0의 디버그 화면입니다. 화면 수집과 강조 처리는 서비스가 담당하고,
 * 이 화면은 마지막으로 보관된 외부 앱의 정보를 보여주며 사용자 요청만 전달합니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(
    state: AssistUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSamsungSettings: () -> Unit,
    onSelectNode: (AccessibilityNodeModel) -> Unit,
    onClearOverlay: () -> Unit,
    onRefresh: () -> Unit,
    onDumpTree: () -> Unit,
    onClearSnapshot: () -> Unit,
) {
    // 화면 회전이나 앱 재진입 시에도 사용자가 입력한 검색어와 필터를 유지합니다.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var clickableOnly by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    val visibleNodes = remember(snapshot, searchQuery, clickableOnly) {
        val query = searchQuery.trim()
        snapshot?.nodes.orEmpty().filter { node ->
            (!clickableOnly || node.clickable) &&
                (query.isEmpty() || listOfNotNull(
                    node.displayLabel,
                    node.text,
                    node.contentDescription,
                    node.className,
                    node.viewIdResourceName,
                    node.descendantLabels.joinToString(" "),
                ).any { it.contains(query, ignoreCase = true) })
        }
    }

    // Scaffold가 시스템 표시줄 여백을 관리하므로 화면의 위·아래 버튼이 가려지지 않습니다.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("APL · MVP-0", fontWeight = FontWeight.Bold)
                    Text(
                        "스마트폰 사용 보조 기술검증",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
    ) { innerPadding ->
        // 상태·도구·노드를 하나의 목록에 넣어 작은 화면과 큰 글자 설정에서도 모두 스크롤합니다.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "service-status") {
                ServiceStatusCard(
                    state = state,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenSamsungSettings = onOpenSamsungSettings,
                )
            }
            item(key = "snapshot-tools") {
                SnapshotToolsCard(
                    state = state,
                    onClearOverlay = onClearOverlay,
                    onRefresh = onRefresh,
                    onDumpTree = onDumpTree,
                    onClearSnapshot = onClearSnapshot,
                )
            }
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("화면 요소 검색") },
                        placeholder = { Text("예: 디스플레이") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                TextButton(onClick = { searchQuery = "" }) { Text("지우기") }
                            }
                        },
                    )
                    Row(
                        // 레이블을 포함한 행 전체를 누를 수 있고 TalkBack에도 이름이 전달됩니다.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = clickableOnly,
                            role = Role.Switch,
                            onValueChange = { clickableOnly = it },
                        ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "${visibleNodes.size}개 표시 · 클릭 가능만",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(checked = clickableOnly, onCheckedChange = null)
                    }
                }
            }

            if (visibleNodes.isEmpty()) {
                item(key = "empty-state") {
                    EmptyNodesCard(
                        hasSnapshot = snapshot != null,
                        hasFilter = searchQuery.isNotBlank() || clickableOnly,
                        serviceConnected = state.serviceConnected,
                    )
                }
            }

            // snapshotId를 키에 포함하여 화면이 바뀌면 이전 노드의 펼침 상태를 새 노드에 옮기지 않습니다.
            items(
                items = visibleNodes,
                key = { "${snapshot?.snapshotId}:${it.id}" },
            ) { node ->
                NodeCard(
                    node = node,
                    selected = state.highlightedNodeId == node.id,
                    serviceConnected = state.serviceConnected,
                    onSelect = { onSelectNode(node) },
                )
            }
        }
    }
}

/** 서비스 연결, 현재 앱, 설정 진입 버튼을 간단히 보여줍니다. 권한은 사용자가 직접 켭니다. */
@Composable
private fun ServiceStatusCard(
    state: AssistUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSamsungSettings: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("접근성 서비스", fontWeight = FontWeight.Bold)
                StatusBadge(if (state.serviceConnected) "연결됨" else "연결 필요", state.serviceConnected)
            }
            Text(
                "현재 앞에 있는 앱: ${friendlyPackage(state.foregroundPackage)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.serviceConnected) {
                Text(
                    "접근성 설정의 설치된 앱에서 ‘APL 화면 안내 테스트’를 켜주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("접근성 설정") }
                Button(
                    onClick = onOpenSamsungSettings,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("설정 앱 열기") }
            }
        }
    }
}

/** 마지막 수집 기록과 강조 상태를 구분하고, 자주 쓰는 도구와 추가 디버그 도구를 제공합니다. */
@Composable
private fun SnapshotToolsCard(
    state: AssistUiState,
    onClearOverlay: () -> Unit,
    onRefresh: () -> Unit,
    onDumpTree: () -> Unit,
    onClearSnapshot: () -> Unit,
) {
    var showDebugTools by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    val capturedTime = remember(snapshot?.capturedAtMillis) {
        snapshot?.capturedAtMillis?.let {
            SimpleDateFormat("HH:mm:ss", Locale.KOREAN).format(Date(it))
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "마지막으로 읽은 화면",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (snapshot == null) "아직 수집된 외부 앱 화면이 없습니다."
                else "${friendlyPackage(snapshot.packageName)} · $capturedTime · ${snapshot.nodes.size}개 요소",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "설정 화면을 연 뒤 이 앱으로 돌아오면 기록이 보입니다. 강조 예약 후 뒤로 또는 최근 앱으로 설정에 돌아가세요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.overlayStatus, style = MaterialTheme.typography.bodyMedium)
            state.pendingTargetLabel?.let {
                Text("예약된 항목: $it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (snapshot?.truncated == true) {
                Text(
                    "트리가 커서 일부만 수집했습니다. 대상이 없다면 화면을 좁혀 다시 읽어주세요.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClearOverlay,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("강조 제거") }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.serviceConnected,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("다시 읽기") }
            }
            TextButton(onClick = { showDebugTools = !showDebugTools }) {
                Text(if (showDebugTools) "디버그 도구 접기" else "디버그 도구 펼치기")
            }
            if (showDebugTools) {
                HorizontalDivider()
                Text(
                    "현재 보이는 앱과 위 기록의 앱은 다를 수 있습니다. 다시 읽기는 외부 앱이 앞에 있을 때 최신 화면을 수집합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "전체 ${snapshot?.allNodes?.size ?: 0}개 노드 · 창 ID ${snapshot?.windowId ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.lastEvent?.let { Text("최근 이벤트: $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDumpTree,
                        enabled = snapshot != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("전체 트리 로그") }
                    OutlinedButton(
                        onClick = onClearSnapshot,
                        enabled = snapshot != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("기록 지우기") }
                }
            }
        }
    }
}

/** 개별 노드의 핵심 속성과 전체 속성을 표시합니다. 예약 버튼은 다른 앱의 버튼을 클릭하지 않습니다. */
@Composable
private fun NodeCard(
    node: AccessibilityNodeModel,
    selected: Boolean,
    serviceConnected: Boolean,
    onSelect: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 화면 밖·비활성·빈 좌표의 노드는 강조 대상으로 선택할 수 없도록 합니다.
    val canHighlight = serviceConnected && node.visibleToUser && node.enabled && !node.bounds.isEmpty && !node.password
    Card(
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                node.displayLabel.ifBlank { "이름 없는 요소 #${node.id}" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "#${node.id} · ${node.className?.substringAfterLast('.') ?: "클래스 없음"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusBadge(if (node.clickable) "클릭 가능" else "읽기 요소", node.clickable)
                StatusBadge(if (node.enabled) "활성" else "비활성", node.enabled)
                if (selected) StatusBadge("강조 대상", true)
            }
            Text(
                "좌표: (${node.bounds.left}, ${node.bounds.top}) → (${node.bounds.right}, ${node.bounds.bottom})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "상세 접기" else "상세 보기") }
                Button(onClick = onSelect, enabled = canHighlight, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("강조 예약")
                }
            }
            if (expanded) {
                HorizontalDivider()
                NodeDetails(node)
                Text(
                    "강조는 위치 안내입니다. 실제 항목은 대상 앱에서 직접 눌러주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 기술검증에 필요한 원본 필드를 접힌 상세 영역에 모아 목록의 가독성을 유지합니다. */
@Composable
private fun NodeDetails(node: AccessibilityNodeModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        DetailLine("텍스트", node.text)
        DetailLine("콘텐츠 설명", node.contentDescription)
        DetailLine("View ID", node.viewIdResourceName)
        DetailLine("클래스", node.className)
        DetailLine("노드 경로", node.path)
        DetailLine("부모 경로", node.parentPath)
        DetailLine("클릭 가능 조상", node.clickableAncestorPath)
        DetailLine("자식 레이블", node.descendantLabels.joinToString(" · "))
        DetailLine(
            "상태",
            "표시=${node.visibleToUser}, 포커스=${node.focusable}, 스크롤=${node.scrollable}, 암호=${node.password}",
        )
        DetailLine(
            "지원 액션",
            node.actions.joinToString("\n") { "${it.id}: ${it.label ?: "레이블 없음"}" },
        )
    }
}

/** 상세 값이 없더라도 빈 줄 대신 명시적인 표시를 제공해 수집 여부를 확인할 수 있게 합니다. */
@Composable
private fun DetailLine(label: String, value: String?) {
    Text(
        "$label: ${value?.takeIf { it.isNotBlank() } ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

/** 서비스 연결이나 클릭 가능 여부 같은 짧은 상태를 색과 문자로 함께 표현합니다. */
@Composable
private fun StatusBadge(label: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** 서비스 미연결, 미수집, 검색 결과 없음의 원인을 구분해 다음 행동을 안내합니다. */
@Composable
private fun EmptyNodesCard(hasSnapshot: Boolean, hasFilter: Boolean, serviceConnected: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hasFilter && hasSnapshot) "검색 결과가 없습니다" else "화면 요소를 기다리고 있어요",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    !serviceConnected -> "먼저 접근성 설정에서 ‘APL 화면 안내 테스트’를 켜주세요."
                    hasFilter && hasSnapshot -> "검색어를 지우거나 ‘클릭 가능만’ 필터를 꺼보세요."
                    else -> "‘설정 앱 열기’를 누르고 화면이 뜨면 최근 앱으로 APL에 돌아오세요. 읽은 항목을 여기서 선택할 수 있습니다."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 알려진 테스트 앱은 읽기 쉬운 이름으로, 나머지는 실제 패키지명으로 보여줍니다. */
private fun friendlyPackage(packageName: String?): String = when (packageName) {
    null -> "확인 중"
    "com.android.settings" -> "설정 (com.android.settings)"
    "com.example.aplassistmvp0" -> "APL 기술검증"
    else -> packageName
}
