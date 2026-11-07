package com.example.aplassistmvp0.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** 접근성 트리를 제한된 크기의 일반 값으로 복사하는 읽기 전용 수집기이다. */
class AccessibilityTreeParser {
    /**
     * 보이지 않는 노드도 allNodes에 기록하되 선택 목록에는 보이는 후보만 포함한다.
     * 화면 전환 중 일부 노드가 사라져도 읽을 수 있는 나머지 정보는 보존한다.
     */
    fun parse(root: AccessibilityNodeInfo): ParsedTree {
        val result = mutableListOf<AccessibilityNodeModel>()
        var propertyFailures = 0
        var childQueryFailures = 0
        var missingChildren = 0
        var nodeBudgetReached = false
        var depthLimitReached = false
        var childReads = 0
        val recipientQueries = mutableListOf<String>()
        val recipientMatches = mutableListOf<RecipientResultBinding.Match>()
        val packageName = runCatching { root.packageName?.toString() }.getOrNull()

        // 실패한 필드에는 안전한 기본값을 넣는다. 특히 visible/enabled는 실패 시 false다.
        fun <T> read(default: T, block: () -> T): T = try {
            block()
        } catch (_: Exception) {
            propertyFailures++
            default
        }

        fun visit(node: AccessibilityNodeInfo, path: String, parentPath: String?, depth: Int,
                  clickableAncestorPath: String?, privateAncestor: Boolean = false) {
            if (result.size >= MAX_NODES) {
                nodeBudgetReached = true
                return
            }

            val password = read(true) { node.isPassword }
            val className = read<String?>(null) { node.className?.toString() }
            val resourceName = read<String?>(null) { node.viewIdResourceName }
            val editable = read(true) { node.isEditable } ||
                className?.substringAfterLast('.')?.endsWith("EditText") == true
            val notice = if (resourceName == "android:id/message" && !password && !editable && !privateAncestor)
                read<String?>(null) { label(node.text) } else null
            val sensitive = privateAncestor || (!editable && AccessibilityNodePrivacy.isSensitivePreviewResource(resourceName, notice))
            // hintText는 minSdk 26부터 제공된다. 입력 값이나 접근성 설명에서 힌트를 추정하지 않는다.
            val hint = if (!password && !sensitive && editable)
                read<String?>(null) { label(node.hintText) } else null
            val clickable = read(false) { node.isClickable }
            val checkable = read<Boolean?>(null) { node.isCheckable }
            val checked = if (checkable == true) read<Int?>(null) {
                if (android.os.Build.VERSION.SDK_INT >= 36) node.checked else {
                    @Suppress("DEPRECATION")
                    if (node.isChecked) 1 else 0
                }
            } else null
            val range = if (!password && !sensitive) read<NodeRange?>(null) {
                node.rangeInfo?.let { NodeRange(it.min, it.max, it.current, it.type) }?.takeIf { it.usable }
            } else null
            val hasInput = if (editable && !password && !sensitive) read<Boolean?>(null) {
                if (node.isShowingHintText) false else node.text?.let { it.isNotBlank() }
            } else null
            val bounds = read(NodeBounds(0, 0, 0, 0)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
            }
            val actions = read(emptyList<AccessibilityActionModel>()) {
                node.actionList.map { action ->
                    // 입력/미리보기 위젯이 사용자 내용을 동작 이름에 실어도 수집하지 않는다.
                    AccessibilityActionModel(action.id, if (password || editable || sensitive) null else label(action.label))
                }.toList()
            }
            result += AccessibilityNodeModel(
                id = result.size + 1,
                path = path,
                parentPath = parentPath,
                className = className,
                text = if (password || sensitive) null else if (editable) hint else
                    read<String?>(null) { label(node.text) },
                contentDescription = if (password || editable || sensitive) null else
                    read<String?>(null) { label(node.contentDescription) },
                viewIdResourceName = resourceName,
                clickable = clickable,
                enabled = read(false) { node.isEnabled },
                focusable = read(false) { node.isFocusable },
                scrollable = read(false) { node.isScrollable },
                visibleToUser = read(false) { node.isVisibleToUser },
                password = password,
                bounds = bounds,
                actions = actions,
                clickableAncestorPath = clickableAncestorPath,
                editable = editable,
                inputHint = hint,
                sensitiveContent = sensitive,
                hasRangeInfo = read(false) { node.rangeInfo != null },
                checkable = checkable, checked = checked,
                selected = read<Boolean?>(null) { node.isSelected },
                focused = read<Boolean?>(null) { node.isFocused },
                hasInput = hasInput,
                localInputRevision = if(editable && !password && !sensitive) read<Long?>(null) {
                    LocalInputRevisions.capture(packageName,root.windowId,path,className,resourceName,bounds,
                        if(node.isShowingHintText) "" else node.text)
                } else null,
                contentInvalid = read<Boolean?>(null) { node.isContentInvalid },
                range = range,
                stateDescription = if (!password && !sensitive && !editable && android.os.Build.VERSION.SDK_INT >= 30)
                    read<String?>(null) { label(node.stateDescription) } else null,
                heading = android.os.Build.VERSION.SDK_INT >= 28 && read(false) { node.isHeading },
                paneTitle = if (!password && !sensitive && !editable && !clickable && android.os.Build.VERSION.SDK_INT >= 28)
                    read<String?>(null) { label(node.paneTitle) } else null,
            )

            // 이 화면에서 사용자가 직접 입력한 이름과 이름 전용 리소스만 비교한다.
            // 원문/전화번호는 모델에 넣지 않고 프로세스별 해시만 잠깐 사용한다.
            if (packageName == RecipientResultBinding.PACKAGE && !password) {
                val model = result.last()
                if (RecipientResultBinding.isSearchInput(model)) {
                    read<String?>(null) { RecipientResultBinding.key(node.text) }?.let(recipientQueries::add)
                }
                if (resourceName == "${RecipientResultBinding.PACKAGE}:id/contact_list_item_main" &&
                    model.visibleToUser && model.enabled) {
                    val rowPath = if (clickable) path else clickableAncestorPath
                    if (rowPath != null) read<String?>(null) { RecipientResultBinding.key(node.text) }
                        ?.let { recipientMatches += RecipientResultBinding.Match(rowPath, it) }
                }
            }

            val childCount = read(0) { node.childCount }
            if (depth >= MAX_DEPTH && childCount > 0) {
                depthLimitReached = true
                return
            }
            for (childIndex in 0 until childCount) {
                // 사라진 자식이 null로 반환되어도 조회 예산을 소모한다. 그렇지 않으면
                // 노드 수 제한과 달리 원격 앱 호출은 childCount만큼 계속될 수 있다.
                if (result.size >= MAX_NODES || childReads >= MAX_NODES - 1) {
                    nodeBudgetReached = true
                    break
                }
                childReads++
                val child = try {
                    node.getChild(childIndex)
                } catch (_: Exception) {
                    childQueryFailures++
                    continue
                }
                if (child == null) {
                    // 존재한다고 보고된 자식을 못 읽으면 부분 수집임을 표시한다.
                    missingChildren++
                    continue
                }
                visit(child, "$path/$childIndex", path, depth + 1,
                    if (clickable) path else clickableAncestorPath,
                    privateAncestor = sensitive || password || editable)
                // API 33부터 객체 재활용이 폐지되어 recycle()을 호출하지 않는다.
            }
        }

        visit(root, "0", null, 0, null)
        // 앱이 자식 입력/미리보기 원문을 조상 설명에 합치는 경우를 막는다.
        // 가까운 정상 자식 제목은 아래 행 이름 수집으로 복원할 수 있다.
        val privatePaths = result.filter { it.password || it.editable || it.sensitiveContent }.map { it.path }
        val sanitized = result.map { node ->
            if (!node.editable && privatePaths.any { it.startsWith("${node.path}/") })
                node.copy(text = null, contentDescription = null,
                    stateDescription = null, paneTitle = null,
                    actions = node.actions.map { it.copy(label = null) }) else node
        }
        val readGaps = TreeReadGaps(propertyFailures, childQueryFailures, missingChildren, nodeBudgetReached, depthLimitReached)
        val enriched = PublicTimePickerRoles.annotate(RecipientResultBinding.bind(packageName, addRowLabels(sanitized),
            recipientQueries, recipientMatches, readGaps.incomplete))
        return ParsedTree(
            allNodes = enriched,
            nodes = enriched.filter { it.visibleToUser &&
                (it.clickable || it.scrollable || it.editable || it.hasRangeInfo || it.checkable == true || it.text != null || it.contentDescription != null) },
            truncated = readGaps.incomplete,
            readGaps = readGaps,
            platformActionBarBounds = if (!readGaps.incomplete && result.any { it.scrollable && it.visibleToUser })
                platformActionBarBounds(root) else null,
        )
    }

    private fun platformActionBarBounds(root: AccessibilityNodeInfo): NodeBounds? = runCatching {
        // The platform decor can omit the container from the normal accessibility traversal.
        val bar = root.findAccessibilityNodeInfosByViewId("android:id/action_bar_container")
            .singleOrNull { it.isVisibleToUser && it.windowId == root.windowId }
            ?: return null
        val rect = Rect().also(bar::getBoundsInScreen)
        NodeBounds(rect.left, rect.top, rect.right, rect.bottom).takeUnless { it.isEmpty }
    }.getOrNull()

    /**
     * 가까운 클릭 행에만 자식 이름을 연결한다. 별도의 클릭 자식이나 스크롤 영역을 넘어
     * 화면 전체 문구를 합치지 않고, 너무 큰 묶음은 행 이름으로 사용하지 않는다.
     */
    private fun addRowLabels(nodes: List<AccessibilityNodeModel>): List<AccessibilityNodeModel> {
        val byPath = nodes.associateBy { it.path }
        val rowLabels = mutableMapOf<String, LinkedHashSet<String>>()
        val oversizedRows = mutableSetOf<String>()
        for (node in nodes) {
            if (node.password || node.editable || node.sensitiveContent || node.clickable || !node.visibleToUser) continue
            val rowPath = node.clickableAncestorPath ?: continue
            val row = byPath[rowPath] ?: continue
            if (row.scrollable || row.password || row.editable || row.sensitiveContent ||
                pathDepth(node.path) - pathDepth(rowPath) > LABEL_DEPTH) continue

            // 스크롤 가능한 컨테이너 안의 모든 행이 상위 버튼의 이름이 되는 것을 방지한다.
            var ancestorPath = node.parentPath
            var crossesContainer = false
            while (ancestorPath != null && ancestorPath != rowPath) {
                val ancestor = byPath[ancestorPath] ?: break
                if (ancestor.scrollable || ancestor.password || ancestor.editable || ancestor.sensitiveContent) {
                    crossesContainer = true
                    break
                }
                ancestorPath = ancestor.parentPath
            }
            if (crossesContainer || rowPath in oversizedRows) continue
            val labels = rowLabels.getOrPut(rowPath) { linkedSetOf() }
            listOfNotNull(node.text, node.contentDescription).forEach { labels.add(it) }
            if (labels.size > MAX_ROW_LABELS) {
                oversizedRows += rowPath
                rowLabels.remove(rowPath)
            }
        }
        return nodes.map { node ->
            node.copy(descendantLabels = rowLabels[node.path]?.toList() ?: emptyList())
        }.toList()
    }

    /** 앞뒤 공백만 정리한다. 의미가 다른 문구를 합치기 위한 부분 문자열 처리는 하지 않는다. */
    private fun label(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(AccessibilityNodePrivacy::redactIdentifiers)

    /** 경로는 현재 트리의 자식 인덱스로 구성되므로 구분자 수가 깊이와 같다. */
    private fun pathDepth(path: String): Int = path.count { it == '/' }

    private companion object {
        const val MAX_NODES = 1_500
        const val MAX_DEPTH = 60
        const val LABEL_DEPTH = 6
        const val MAX_ROW_LABELS = 8
    }
}
