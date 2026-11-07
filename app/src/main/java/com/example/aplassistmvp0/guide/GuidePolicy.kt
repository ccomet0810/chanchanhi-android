package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.state.ScreenSnapshot
import java.security.MessageDigest

/** Android 객체나 네트워크 없이 안내 후보와 늦게 도착한 응답의 유효성을 판단한다. */
object GuidePolicy {
    const val SETTINGS_PACKAGE = "com.android.settings"
    const val MAX_STEPS = 8
    const val MAX_CANDIDATES = 120

    // Snapshots are immutable. Keep only two identities (requested/live), never reuse by numeric id.
    // The same tree is inspected by the engine, overlay and response validator many times.
    private class Derived(val snapshot: ScreenSnapshot) {
        val active by lazy { allCandidates(snapshot) }
        val context by lazy { allCandidates(snapshot, includeDisabled = true) }
        val fingerprint by lazy { fingerprintOf(snapshot, context) }
        val controls by lazy {
            context.filter {
                it.clickable || AccessibilityNodePrivacy.isEditable(it) ||
                    (it.hasRangeInfo && !it.className.orEmpty().contains("ProgressBar", true)) ||
                    (it.scrollable && labels(it) == listOf("화면 목록"))
            }
        }
        val interaction by lazy {
            if (controls.isEmpty()) "" else fingerprintOf(snapshot, controls)
        }
        val settling by lazy {
            if (snapshot.truncated || controls.isEmpty()) "" else hash(pack(listOf(
                interaction, snapshot.viewport.toString(), snapshot.blockingPopup.toString(),
            )))
        }
    }
    private var derivedTrees = emptyList<Derived>()
    @Synchronized private fun derived(snapshot: ScreenSnapshot): Derived =
        derivedTrees.firstOrNull { it.snapshot === snapshot } ?: Derived(snapshot).also {
            derivedTrees = listOf(it) + derivedTrees.take(1)
        }
    private val whitespace = Regex("\\s+")
    private const val HEX = "0123456789abcdef"

    /** Intent/URI나 공백을 패키지로 해석하지 않는다. 실제 설치 여부와 실행 가능성은 앱 선택부가 확인한다. */
    fun isValidTargetPackage(packageName: String): Boolean =
        packageName.length <= 255 && PACKAGE_PATTERN.matches(packageName)

    /** 실제 문구를 우선하고 익명 버튼은 이미지 대조용으로만 표시한다. 임시 이름은 의미의 근거가 아니다. */
    fun label(node: AccessibilityNodeModel): String {
        if (isPrivate(node)) return ""
        if (AccessibilityNodePrivacy.isEditable(node)) return AccessibilityNodePrivacy.publicInputLabel(node)
        val text = node.text?.let(::normalize).orEmpty()
        if (text.isNotEmpty()) return AccessibilityNodePrivacy.redactIdentifiers(text)
        val description = node.contentDescription?.let(::normalize).orEmpty()
        if (description.isNotEmpty()) return AccessibilityNodePrivacy.redactIdentifiers(description)
        return node.descendantLabels.map(::normalize).filter { it.isNotEmpty() }
            .distinct().joinToString(" · ").let(AccessibilityNodePrivacy::redactIdentifiers).ifBlank { structuralLabel(node).orEmpty() }
    }

    /**
     * 읽을 수 있는 이름 또는 이미지 대조용 익명 표시와 유효한 영역이 있는 요소만 전달한다.
     * 클릭 행에 포함된 같은 이름의 비클릭 자식은 중복 전달하지 않는다.
     */
    fun candidates(snapshot: ScreenSnapshot): List<AccessibilityNodeModel> =
        derived(snapshot).active.take(MAX_CANDIDATES)

    /** 입력 값은 재식별 근거로도 사용하지 않는다. 반환값은 비교용이며 강조에는 원래 최신 객체를 사용한다. */
    fun matchingNode(node: AccessibilityNodeModel): AccessibilityNodeModel =
        if (AccessibilityNodePrivacy.isEditable(node))
            node.copy(text = label(node), contentDescription = null, descendantLabels = emptyList()) else node

    /**
     * 좌표·임시 ID·수집 시각이 변해도 화면의 의미가 같으면 같은 지문을 만든다.
     * 전송 제한 뒤의 후보도 포함하므로 121번째 요소의 변화도 늦은 응답을 무효화한다.
     * 창 ID는 지문에 넣지 않고 세션 검증에서 별도로 비교한다.
     */
    fun fingerprint(snapshot: ScreenSnapshot): String {
        return derived(snapshot).fingerprint
    }

    /**
     * 읽기 전용 광고·상품 문구의 변화가 추가 AI 요청을 만들지 않도록 실제 조작 항목만 비교한다.
     * 비클릭 스크롤 컨테이너는 페이지 전체 문구를 합칠 수 있어 제외한다. 이름·역할·개수는 유지한다.
     * 빈 조작 항목 집합은 화면 동일성의 근거가 될 수 없으므로 빈 문자열을 반환한다.
     */
    fun interactionFingerprint(snapshot: ScreenSnapshot): String {
        return derived(snapshot).interaction
    }

    /** Debounce only. Never substitutes for the full captured frame or an action/arrival proof. */
    fun settlingFingerprint(snapshot: ScreenSnapshot): String = derived(snapshot).settling

    private fun fingerprintOf(snapshot: ScreenSnapshot, candidates: List<AccessibilityNodeModel>): String {
        val rows = candidates.map(::fingerprintRow).sorted()
        val canonical = pack(listOf(snapshot.packageName, snapshot.truncated.toString(), pack(rows)))
        return hash(canonical)
    }

    private fun fingerprintRow(node: AccessibilityNodeModel): String = pack(
                listOf(
                    node.className.orEmpty(),
                    node.viewIdResourceName.orEmpty(),
                    node.clickable.toString(),
                    node.scrollable.toString(),
                    node.enabled.toString(),
                    AccessibilityNodePrivacy.isEditable(node).toString(),
                    node.hasRangeInfo.toString(),
                    node.checkable.toString(), node.checked.toString(), node.selected.toString(),
                    node.focused.toString(), node.hasInput.toString(), node.contentInvalid.toString(),
                    node.range.toString(),
                    node.localRecipientKey.orEmpty(),
                    primaryLabel(node).orEmpty(),
                    pack(labels(node).sorted()),
                ) + node.stateDescription?.let(::normalize)?.takeIf { it.isNotBlank() }
                    ?.let { listOf("stateDescription", AccessibilityNodePrivacy.redactIdentifiers(it)) }.orEmpty(),
            )

    private fun hash(canonical: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) { for (byte in bytes) {
            val value=byte.toInt() and 0xff; append(HEX[value ushr 4]); append(HEX[value and 15])
        } }
    }

    /**
     * 응답 ID는 이 스냅샷의 전송 후보 안에서만 해석한다.
     * 동일한 이름의 행은 ID나 viewId가 달라도 보수적으로 거부하여 추측 선택을 막는다.
     */
    fun resolveDecisionNode(snapshot: ScreenSnapshot, targetNodeId: Int): AccessibilityNodeModel? {
        val all = derived(snapshot).active
        val target = all.take(MAX_CANDIDATES).singleOrNull { it.id == targetNodeId } ?: return null
        if (all.count { it.id == targetNodeId } != 1) return null
        val name = primaryLabel(target) ?: return null
        if (all.count { primaryLabel(it) == name } != 1) return null
        return target
    }

    fun contextNodes(snapshot: ScreenSnapshot): List<AccessibilityNodeModel> = derived(snapshot).context

    private fun allCandidates(snapshot: ScreenSnapshot, includeDisabled: Boolean = false): List<AccessibilityNodeModel> {
        // 테스트나 외부 수집기가 아직 원문을 가진 경우도 입력 값이 조상 행 이름으로 재전송되지 않게 한다.
        val privateValues = (snapshot.allNodes + snapshot.nodes).filter {
            isPrivate(it) || AccessibilityNodePrivacy.isEditable(it)
        }.flatMap { node ->
            val hint = node.inputHint?.let(::normalize)
            (listOfNotNull(node.text, node.contentDescription) + node.descendantLabels)
                .map(::normalize).filter { it.isNotEmpty() && (isPrivate(node) || it != hint) }
                .map { node.path to it }
        }.distinct()
        val scrollingParents = snapshot.allNodes.filter { it.scrollable }
        // WebViews can mark the whole page clickable and give it the page title.
        // Its center may activate an unrelated child. Offer the actual child controls instead.
        val pageWrappers=snapshot.viewport?.let { viewport ->
            val viewportArea=(viewport.right-viewport.left).toLong()*(viewport.bottom-viewport.top)
            snapshot.allNodes.filter { parent ->
                parent.clickable && !AccessibilityNodePrivacy.isEditable(parent) && parent.checkable!=true &&
                    !parent.hasRangeInfo && viewportArea>0 &&
                    (minOf(parent.bounds.right,viewport.right)-maxOf(parent.bounds.left,viewport.left)).coerceAtLeast(0).toLong()*
                    (minOf(parent.bounds.bottom,viewport.bottom)-maxOf(parent.bounds.top,viewport.top)).coerceAtLeast(0)*4>=viewportArea*3 &&
                    snapshot.allNodes.asSequence().filter { child -> child.visibleToUser && child.enabled && child.clickable &&
                        child.path.startsWith(parent.path+"/") && !isPrivate(child) && !child.bounds.isEmpty }
                        .map(::label).filter { it.isNotEmpty() }.distinct().take(3).count()>=3
            }.map { it.path }.toSet()
        }.orEmpty()
        val readable = snapshot.nodes.filter { node ->
            val names = labels(node)
            node.path !in pageWrappers && node.visibleToUser && (includeDisabled || node.enabled) && !isPrivate(node) && !node.bounds.isEmpty &&
                (snapshot.viewport?.let { viewport ->
                    val x = (node.bounds.left.toLong() + node.bounds.right) / 2
                    val y = (node.bounds.top.toLong() + node.bounds.bottom) / 2
                    x >= viewport.left && x < viewport.right && y >= viewport.top && y < viewport.bottom
                } ?: true) &&
                scrollingParents.filter { node.path.startsWith(it.path + "/") }.all { parent ->
                    // Some apps report partially clipped rows as visible. Their center must still be in the actual list viewport.
                    val x = (node.bounds.left.toLong() + node.bounds.right) / 2
                    val y = (node.bounds.top.toLong() + node.bounds.bottom) / 2
                    parent.visibleToUser && x >= parent.bounds.left && x < parent.bounds.right &&
                        y >= parent.bounds.top && y < parent.bounds.bottom
                } &&
                (snapshot.viewport?.let { viewport ->
                    !node.clickable || GuideTouchPolicy.exposedBounds(snapshot,node,viewport)!=null
                } ?: true) &&
                names.isNotEmpty() && (AccessibilityNodePrivacy.isEditable(node) || names.none { name ->
                    // 다른 위치에 있는 정상 버튼이 우연히 같은 글자를 가질 때는 제외하지 않는다.
                    privateValues.any { (privatePath, value) -> privatePath.startsWith("${node.path}/") &&
                        (name == value || (value.length >= 4 && name.contains(value))) }
                })
        }
        val rowsByPath = readable.filter { it.clickable }.groupBy { it.path }
        return readable.filter { node ->
            if (node.clickable) return@filter true
            val ancestor = node.clickableAncestorPath?.let { rowsByPath[it]?.singleOrNull() }
            // 조상 경로가 중복되거나 자식 문구를 포함하지 않는 행이면 자식을 유지한다.
            ancestor == null || !labels(ancestor).containsAll(labels(node))
        }.sortedByDescending { it.clickable }
    }

    internal fun labels(node: AccessibilityNodeModel): List<String> {
        if (isPrivate(node)) return emptyList()
        if (AccessibilityNodePrivacy.isEditable(node)) return listOf(AccessibilityNodePrivacy.publicInputLabel(node))
        return (listOfNotNull(node.text, node.contentDescription) + node.descendantLabels)
            .map(::normalize).map(AccessibilityNodePrivacy::redactIdentifiers).filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOfNotNull(structuralLabel(node)) }
    }

    /** 일반 익명 버튼은 추측하지 않는다. 공개된 닫기 리소스/동작과 스크롤 역할만 보완한다. */
    private fun structuralLabel(node: AccessibilityNodeModel): String? {
        return com.example.aplassistmvp0.accessibility.PublicNodeRoleLabels.label(node)
            ?: if (GuideVisionPolicy.anonymous(node)) "이름 없는 버튼 #${node.id}" else null
    }

    private fun isPrivate(node: AccessibilityNodeModel): Boolean = node.password || node.sensitiveContent ||
        AccessibilityNodePrivacy.isSensitivePreview(node)

    private fun primaryLabel(node: AccessibilityNodeModel): String? = labels(node).firstOrNull()

    private fun normalize(value: String): String = value.trim().replace(whitespace, " ")

    // 길이를 함께 기록해 구분 문자가 실제 문구 안에 들어가도 서로 다른 화면이 섞이지 않는다.
    private fun pack(values: List<String>): String = values.joinToString("") { "${it.length}:$it" }

    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
}

/**
 * 안내를 새로 시작하거나 취소할 때 세대 번호를 바꿔 이전 요청의 응답을 폐기한다.
 * Controller가 현재 화면을 다시 읽은 뒤 이 정책을 통과한 응답만 적용하도록 사용한다.
 */
class GuideSessionPolicy(private val requestLimit: Int? = GuidePolicy.MAX_STEPS) {
    var generation: Long = 0
        private set
    var stepsTaken: Int = 0
        private set
    var active: Boolean = false
        private set

    private var startedAtMillis: Long = Long.MAX_VALUE
    private var afterSnapshotId: Long = Long.MAX_VALUE
    private var targetPackage: String = GuidePolicy.SETTINGS_PACKAGE

    /** 시작 버튼을 누르기 전에 수집한 화면을 재사용하지 않도록 시각과 ID를 모두 기억한다. */
    @Synchronized
    fun start(startedAtMillis: Long, afterSnapshotId: Long = -1,
              targetPackage: String = GuidePolicy.SETTINGS_PACKAGE): Long {
        require(GuidePolicy.isValidTargetPackage(targetPackage)) { "안내 대상 앱 패키지가 올바르지 않습니다." }
        generation += 1
        stepsTaken = 0
        active = true
        this.startedAtMillis = startedAtMillis
        this.afterSnapshotId = afterSnapshotId
        this.targetPackage = targetPackage
        return generation
    }

    /** 취소 뒤 네트워크 요청이 완료되어도 이전 세대의 응답은 받아들이지 않는다. */
    @Synchronized
    fun cancel(): Long {
        generation += 1
        active = false
        return generation
    }

    /** 최신 화면만 사용한다. Legacy engine keeps its default limit; APL2 bounds failed retries separately. */
    @Synchronized
    fun canRequest(snapshot: ScreenSnapshot): Boolean =
        (requestLimit == null || stepsTaken < requestLimit) && isEligible(snapshot)

    /** Response validity is independent of whether the next request fits the configured limit. */
    private fun isEligible(snapshot: ScreenSnapshot): Boolean =
        active &&
            snapshot.packageName == targetPackage && !snapshot.truncated &&
            snapshot.capturedAtMillis >= startedAtMillis && snapshot.snapshotId > afterSnapshotId &&
            GuidePolicy.candidates(snapshot).isNotEmpty()

    /** 원래 요청 화면과 새로 확인한 현재 화면이 같은 의미와 창을 가질 때만 응답을 승인한다. */
    @Synchronized
    fun accepts(
        generation: Long,
        requestedSnapshot: ScreenSnapshot,
        liveSnapshot: ScreenSnapshot?,
    ): Boolean {
        val live = liveSnapshot ?: return false
        if (!acceptsContext(generation, requestedSnapshot, live)) return false
        return GuidePolicy.fingerprint(requestedSnapshot) == GuidePolicy.fingerprint(live)
    }

    /**
     * 강조 응답에만 읽기 전용 문구 변화 예외를 허용한다. 실제 최신 대상의 고유성은 Controller가 재검증한다.
     * 수동 안내·완료는 화면 문구 전체가 의미에 영향을 주므로 위 accepts 검사를 그대로 사용한다.
     */
    @Synchronized
    fun acceptsHighlight(generation: Long, requestedSnapshot: ScreenSnapshot, liveSnapshot: ScreenSnapshot?): Boolean {
        val live = liveSnapshot ?: return false
        if (!acceptsContext(generation, requestedSnapshot, live)) return false
        if (GuidePolicy.fingerprint(requestedSnapshot) == GuidePolicy.fingerprint(live)) return true
        val interaction = GuidePolicy.interactionFingerprint(requestedSnapshot)
        return interaction.isNotEmpty() && interaction == GuidePolicy.interactionFingerprint(live)
    }

    /** 세대·대상 앱·최신 수집 시각·실제 창 검사는 강조 예외에서도 생략하지 않는다. */
    internal fun acceptsContext(generation: Long, requested: ScreenSnapshot, live: ScreenSnapshot?): Boolean {
        return generation == this.generation && live != null && isEligible(requested) && isEligible(live) &&
            live.snapshotId >= requested.snapshotId && live.capturedAtMillis >= requested.capturedAtMillis &&
            requested.windowId == live.windowId
    }

    /** Counts request ordinals for telemetry and, when configured, the legacy limit. */
    @Synchronized
    fun recordStep(generation: Long): Boolean {
        if (!active || generation != this.generation || (requestLimit != null && stepsTaken >= requestLimit)) return false
        stepsTaken += 1
        return true
    }
}
