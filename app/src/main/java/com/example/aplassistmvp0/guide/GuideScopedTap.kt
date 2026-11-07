package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.state.ScreenSnapshot

/** A navigation button scoped by a user-named title in the same observed group. */
internal class GuideScopedTap private constructor(
    val node: AccessibilityNodeModel,
    private val title: AccessibilityNodeModel,
    private val group: AccessibilityNodeModel,
    private val before: ScreenSnapshot,
    private val request: String,
    val peerButtonPaths: Set<String>,
    private val allowSingleControl: Boolean,
    private val namesControlDirectly: Boolean = false,
    val rowQualifiers: Set<String> = emptySet(),
) {
    val titleLabel: String get() = GuidePolicy.label(title)
    private val controlName: String get() = when {
        rowQualifiers.isNotEmpty() -> "‘${titleLabel.take(80)}’ 중 ${rowQualifiers.joinToString(", ") { "‘$it’" }} 항목"
        namesControlDirectly -> "‘${titleLabel.take(80)}’"
        else -> "‘${titleLabel.take(80)}’의 ‘${GuidePolicy.label(node).take(80)}’"
    }
    val instruction: String get() = "$controlName${if (rowQualifiers.isNotEmpty()) "을" else "를"} 눌러주세요."
    private val observedStatus: String? get() = node.stateDescription?.trim()?.takeIf {
        it.length in 2..120 && AccessibilityNodePrivacy.redactIdentifiers(it) == it &&
            !it.contains("숨김]") && !DirectDecisionPolicy.sensitiveGoal(it)
    }
    val disabledMessage: String get() = "${controlName} 버튼을 지금 누를 수 없어요. " +
        (observedStatus?.let { "앱에서 ‘$it’라고 알려줘요." } ?: "화면이 바뀌면 다시 확인할게요.")

    fun confirms(targetId: Int?, scopeIds: List<Int>): Boolean = node.id == targetId && (namesControlDirectly || title.id in scopeIds)

    fun namesObservedPeer(frame: ScreenSnapshot, label: String): Boolean {
        val viewport = frame.viewport ?: return false
        val heading = frame.allNodes.singleOrNull {
            it.visibleToUser && !GuideActionPolicy.isActionable(it) && !GuideVisionPolicy.privateNode(it) &&
                contains(viewport, it.bounds) && GuidePolicy.label(it) == label
        } ?: return false
        val byPath = frame.allNodes.groupBy { it.path }
        val titleParents = passiveParents(heading, byPath).map { it.path }.toSet()
        val buttonPaths = peerButtonPaths + node.path
        return frame.allNodes.count { peer ->
            peer.path in peerButtonPaths && passiveParents(peer, byPath).any { group ->
                group.path in titleParents && buttonPaths.count { it.startsWith("${group.path}/") } == 1
            }
        } == 1
    }

    fun sourcePaneTitle(frame: ScreenSnapshot): String? {
        val buttonPaths = peerButtonPaths + node.path
        return frame.allNodes.filter { pane ->
            pane.visibleToUser && buttonPaths.all { it.startsWith("${pane.path}/") }
        }.mapNotNull(AccessibilityNodePrivacy::publicPaneTitle).singleOrNull()
    }

    private fun containsEditor(input: AccessibilityNodeModel, byPath: Map<String, List<AccessibilityNodeModel>>): Boolean {
        if (!input.visibleToUser || !input.enabled || input.password || input.sensitiveContent ||
            !AccessibilityNodePrivacy.isEditable(input)) return false
        return passiveParents(input, byPath).any { it === group }
    }

    private fun namesRequiredEditor(input: AccessibilityNodeModel): Boolean {
        val status = observedStatus ?: return false
        val label = AccessibilityNodePrivacy.publicInputLabel(input)
        return label != "입력란" && !DirectDecisionPolicy.sensitiveGoal(label) &&
            !exclusion.containsMatchIn(status) && inputRequirement.containsMatchIn(status) && namesTitle(status, label)
    }

    fun supportsPreparationInput(input: AccessibilityNodeModel): Boolean =
        input.hasInput == false && namesRequiredEditor(input) && containsEditor(input, before.allNodes.groupBy { it.path })

    val announcesAllInputs: Boolean get() = observedStatus?.let(allInputsMarker::containsMatchIn) == true

    /** Resolve only an explicit list of public hints. Unresolved names leave the whole list unverified. */
    fun explicitRequiredEditors(): List<AccessibilityNodeModel>? {
        val status = observedStatus ?: return null
        if (exclusion.containsMatchIn(status)) return null
        val clause = allInputsClause.matchEntire(status)?.groupValues?.get(1) ?: return null
        val viewport = before.viewport ?: return null
        val byPath = before.allNodes.groupBy { it.path }
        val byLabel = before.allNodes.filter {
            AccessibilityNodePrivacy.isEditable(it) && contains(viewport, it.bounds) &&
                !AccessibilityNodePrivacy.hasHiddenIdentifier(it) && !AccessibilityNodePrivacy.isSensitivePreview(it) &&
                namesRequiredEditor(it) && containsEditor(it, byPath)
        }.groupBy(AccessibilityNodePrivacy::publicInputLabel)
        if (byLabel.size < 2) return null
        val token = byLabel.keys.sortedByDescending(String::length).joinToString("|", "(?:", ")", transform = Regex::escape)
        val separator = "(?:[과와]\\s+|,\\s*|\\s+(?:및|그리고)\\s+)"
        if (!Regex("$token(?:$separator$token)+(?:을|를)?").matches(clause)) return null
        val labels = Regex(token).findAll(clause).map { it.value }.toList()
        if (labels.size < 2 || labels.distinct().size != labels.size) return null
        return labels.map { byLabel[it]?.singleOrNull() ?: return null }
    }

    fun retainsPreparationInput(input: AccessibilityNodeModel, live: ScreenSnapshot): Boolean {
        val scope = currentScope(live, recheckPreparation = true) ?: return false
        val preparationApplies = if (scope.node.enabled) input.hasInput == true else scope.namesRequiredEditor(input)
        if (!scope.node.enabled && scope.announcesAllInputs && scope.explicitRequiredEditors()?.any { it === input } != true) return false
        return preparationApplies && scope.containsEditor(input, live.allNodes.groupBy { it.path })
    }

    fun publicNoticeChanged(live: ScreenSnapshot): Boolean {
        val priorNotice = observedStatus
        if ((priorNotice == null && node.stateDescription != null) || live.snapshotId <= before.snapshotId ||
            live.capturedAtMillis < before.capturedAtMillis) return false
        val current = currentScope(live, recheckPreparation = true) ?: return false
        val notice = current.observedStatus
        return current.node.enabled == node.enabled && notice != priorNotice && (notice != null || current.node.stateDescription == null)
    }

    /** A clipped title may explain why a fresh observation is needed. It cannot authorize a tap. */
    fun revealedFromClippedTitle(previous: ScreenSnapshot): Boolean {
        val viewport = previous.viewport ?: return false
        if (previous.truncated || previous.blockingPopup || previous.packageName != before.packageName ||
            previous.windowId != before.windowId || previous.sourceEpoch != before.sourceEpoch ||
            previous.snapshotId >= before.snapshotId || previous.capturedAtMillis > before.capturedAtMillis ||
            viewport != before.viewport || previous.platformActionBarBounds != before.platformActionBarBounds) return false
        fun prior(anchor: AccessibilityNodeModel) = previous.allNodes.singleOrNull { sameAnchor(it, anchor) }
        val oldTitle = prior(title) ?: return false
        val bounds = oldTitle.bounds
        if (bounds.isEmpty || contains(viewport, bounds) || bounds.left >= viewport.right || bounds.right <= viewport.left ||
            bounds.top >= viewport.bottom || bounds.bottom <= viewport.top) return false
        val oldButton = prior(node) ?: return false
        val oldGroup = prior(group) ?: return false
        val byPath = previous.allNodes.groupBy { it.path }
        return passiveParents(oldTitle, byPath).any { it === oldGroup } &&
            passiveParents(oldButton, byPath).any { it === oldGroup }
    }

    fun current(live: ScreenSnapshot): AccessibilityNodeModel? = currentScope(live)?.node

    private fun currentScope(live: ScreenSnapshot, recheckPreparation: Boolean = false): GuideScopedTap? {
        if (before === live) return this
        if (live.packageName != before.packageName || live.windowId != before.windowId ||
            live.sourceEpoch != before.sourceEpoch || live.snapshotId < before.snapshotId ||
            (!recheckPreparation && live.visualInteractionGeneration != before.visualInteractionGeneration) || live.viewport != before.viewport ||
            live.platformActionBarBounds != before.platformActionBarBounds) return null
        val currentTitle = live.allNodes.singleOrNull { sameAnchor(it, title) } ?: return null
        val currentNode = live.allNodes.singleOrNull { candidate ->
            val stable = if (recheckPreparation) candidate.copy(enabled = node.enabled, focusable = node.focusable,
                focused = node.focused, actions = node.actions, stateDescription = node.stateDescription) else candidate
            sameAnchor(stable, node)
        } ?: return null
        val bound = (if (namesControlDirectly) findNamedRow(live, request, titleLabel, rowQualifiers)
            else find(live, currentNode.id, request, allowSingleControl)) ?: return null
        return bound.takeIf { it.node === currentNode && it.title === currentTitle && sameAnchor(it.group, group) && it.rowQualifiers == rowQualifiers }
    }

    companion object {
        private val exclusion = Regex("말고|빼고|제외|아닌|아니고|않|\\b(?:not|except|instead)\\b", RegexOption.IGNORE_CASE)
        private val inputRequirement = Regex("입력해야|먼저 입력|입력이 필요|입력해\\s*주세요")
        private val allInputsMarker = Regex("모두\\s+입력")
        private val allInputsClause = Regex("^(.{2,100}?)\\s+모두\\s+입력(?:해야|해\\s*주세요)(?:\\s+.*)?[.!]?$")
        private val spacing = Regex("\\s+")
        private fun sameAnchor(current: AccessibilityNodeModel, old: AccessibilityNodeModel) =
            current.path == old.path && current.copy(id = old.id, bounds = old.bounds) == old

        private fun contains(outer: NodeBounds, inner: NodeBounds) = !inner.isEmpty &&
            outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

        private fun publicTitleNodes(frame: ScreenSnapshot, title: String): List<AccessibilityNodeModel> {
            val viewport = frame.viewport ?: return emptyList()
            return frame.allNodes.filter {
                val bounds = it.bounds
                it.visibleToUser && !bounds.isEmpty && bounds.left < viewport.right && bounds.right > viewport.left &&
                    bounds.top < viewport.bottom && bounds.bottom > viewport.top && !GuideVisionPolicy.privateNode(it) &&
                    GuidePolicy.label(it) == title
            }
        }

        private data class NamedRow(val node: AccessibilityNodeModel, val title: AccessibilityNodeModel)

        private fun ownsText(row: AccessibilityNodeModel, child: AccessibilityNodeModel): Boolean =
            child.clickableAncestorPath == row.path && child.path.startsWith("${row.path}/") &&
                !GuideActionPolicy.isActionable(child) && contains(row.bounds, child.bounds)

        private fun namedRowCandidates(frame: ScreenSnapshot, titles: List<AccessibilityNodeModel>): List<NamedRow> {
            val directTitles = titles.filter { it.clickable }.map { it.path }.toSet()
            val childTitles = titles.filter { !it.clickable }.groupBy { it.clickableAncestorPath }
            return frame.allNodes.asSequence().filter {
                it.visibleToUser && it.clickable && !it.scrollable && it.checkable != true && !it.hasRangeInfo && !GuideVisionPolicy.privateNode(it)
            }.mapNotNull { row ->
                val title = if (row.path in directTitles) row else childTitles[row.path]?.firstOrNull { ownsText(row, it) }
                title?.let { NamedRow(row, it) }
            }.toList()
        }

        fun namedRows(frame: ScreenSnapshot, title: String): List<AccessibilityNodeModel> =
            namedRowCandidates(frame, publicTitleNodes(frame, title)).map { it.node }

        fun hasDuplicateTitle(frame: ScreenSnapshot, title: String): Boolean {
            val matching = publicTitleNodes(frame, title)
            val rows = namedRowCandidates(frame, matching).map { it.node }.associateBy { it.path }
            return matching.map { node -> rows[node.clickableAncestorPath]?.takeIf { ownsText(it, node) }?.path ?: node.path }
                .distinct().size > 1
        }

        fun findNamedRow(frame: ScreenSnapshot, request: String, title: String,
                         requiredQualifiers: Set<String> = emptySet()): GuideScopedTap? {
            val viewport = frame.viewport ?: return null
            val normalizedRequest = request.replace(spacing, " ")
            if (frame.truncated || frame.blockingPopup || exclusion.containsMatchIn(request) ||
                !DirectDecisionPolicy.navigationGoal(request) || DirectDecisionPolicy.sensitiveGoal(request) ||
                DirectDecisionPolicy.sensitiveGoal(title) || !namesTitle(normalizedRequest, title)) return null
            val detailRequest = titlePattern(title).replace(normalizedRequest, " ")
            val rows = namedRowCandidates(frame, publicTitleNodes(frame, title))
            if (rows.isEmpty()) return null
            val details = frame.allNodes.filter {
                it.visibleToUser && it.clickableAncestorPath != null && contains(viewport, it.bounds) &&
                    (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) && !GuideVisionPolicy.privateNode(it)
            }.groupBy { it.clickableAncestorPath }
            val matches = rows.associateWith { (row, _) ->
                details[row.path].orEmpty().asSequence().filter { ownsText(row, it) }.map(GuidePolicy::label).filter {
                    it != title && it.length in 2..80 && it.count(Char::isLetterOrDigit) >= 2 &&
                        !DirectDecisionPolicy.sensitiveGoal(it) && namesTitle(detailRequest, it)
                }.toSet()
            }
            val expected = requiredQualifiers + matches.values.flatten()
            if (expected.isEmpty() && hasDuplicateTitle(frame, title)) return null
            val (matched, qualifiers) = matches.entries.singleOrNull { it.value.containsAll(expected) } ?: return null
            val row = matched.node
            if (!row.enabled || !contains(viewport, row.bounds) || GuidePolicy.candidates(frame).none { it === row }) return null
            if (qualifiers.isEmpty() && GuidePolicy.resolveDecisionNode(frame, row.id) !== row) return null
            return GuideScopedTap(row, matched.title, row, frame, request, emptySet(), allowSingleControl = false,
                namesControlDirectly = true, rowQualifiers = qualifiers)
        }

        private fun titlePattern(label: String): Regex {
            val name = Regex.escape(label)
            return Regex("(?<![\\p{L}\\p{N}])$name(?:으로|에서|이랑|하고|은|는|이|가|을|를|에|로|의|랑|과|와)?(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE)
        }

        private fun namesTitle(request: String, label: String): Boolean = titlePattern(label).containsMatchIn(request)

        private fun passiveParents(node: AccessibilityNodeModel, byPath: Map<String, List<AccessibilityNodeModel>>) =
            generateSequence(node) { child ->
                val path = child.parentPath ?: return@generateSequence null
                if (path != child.path.substringBeforeLast('/', "")) return@generateSequence null
                byPath[path]?.singleOrNull()?.takeIf { it.parentPath != null && it.visibleToUser &&
                    !GuideActionPolicy.isActionable(it) && !GuideVisionPolicy.privateNode(it) && contains(it.bounds, child.bounds) }
            }.drop(1).toList()

        /** Uses the proposed button's label, then finds the unique group independently of the proposal. */
        fun find(frame: ScreenSnapshot, targetId: Int, request: String, allowSingleControl: Boolean = false): GuideScopedTap? {
            val viewport = frame.viewport ?: return null
            if (frame.truncated || frame.blockingPopup || exclusion.containsMatchIn(request) ||
                !DirectDecisionPolicy.navigationGoal(request) || DirectDecisionPolicy.sensitiveGoal(request)) return null
            val candidates = GuidePolicy.candidates(frame)
            val visibleButtons = frame.allNodes.filter { it.visibleToUser && it.clickable && !it.bounds.isEmpty && !GuideVisionPolicy.privateNode(it) }
            val target = visibleButtons.singleOrNull { it.id == targetId } ?: return null
            val label = GuidePolicy.label(target)
            if (!DirectDecisionPolicy.navigationGoal(label) || DirectDecisionPolicy.sensitiveGoal(label)) return null
            val buttons = visibleButtons.filter { GuidePolicy.label(it) == label }
            if (buttons.size < 2 && !allowSingleControl) return null

            val readOnlyTitles = frame.allNodes.filter { it.visibleToUser && !GuideActionPolicy.isActionable(it) &&
                !GuideVisionPolicy.privateNode(it) && contains(viewport, it.bounds) }
            val normalizedRequest = request.replace(spacing, " ")
            val namedTitles = readOnlyTitles.filter {
                val name = GuidePolicy.label(it)
                name.count(Char::isLetterOrDigit) >= 2 && namesTitle(normalizedRequest, name)
            }
            val byPath = frame.allNodes.groupBy { it.path }
            val titleParents = namedTitles.associateWith { title -> passiveParents(title, byPath).map { it.path }.toSet() }
            val matches = buttons.flatMap { button ->
                val groups = passiveParents(button, byPath)
                titleParents.mapNotNull { (title, paths) ->
                    val group = groups.firstOrNull { it.path in paths } ?: return@mapNotNull null
                    // A shared page or list section cannot lend a title to several identically named buttons.
                    if (buttons.count { it.path.startsWith("${group.path}/") } != 1) return@mapNotNull null
                    if (buttons.size == 1 && AccessibilityNodePrivacy.publicPaneTitle(group) != null) return@mapNotNull null
                    Triple(button, title, group)
                }
            }
            val (button, title, group) = matches.singleOrNull() ?: return null
            if (button.enabled && candidates.none { it === button }) return null
            if (readOnlyTitles.count { GuidePolicy.label(it) == GuidePolicy.label(title) } != 1) return null
            val peers = buttons.filterNot { it === button }.map { it.path }.toSet()
            return GuideScopedTap(button, title, group, frame, request, peers, allowSingleControl)
        }
    }
}
