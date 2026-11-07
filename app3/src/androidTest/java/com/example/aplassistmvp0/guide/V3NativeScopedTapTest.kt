package com.example.aplassistmvp0.guide

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.accessibility.LocalInputRevisions
import com.example.aplassistmvp0.apl2.settings.AplPreferences
import com.example.aplassistmvp0.state.AssistStateRepository
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline public fixture with the real Android service and overlay. */
@RunWith(AndroidJUnit4::class)
class V3NativeScopedTapTest {
    @Test fun onlyTheNamedCardsOpenButtonIsHighlightedAndRenamingClearsIt() = runScenario(nested = false)

    @Test fun nestedTitleAndButtonAreBoundUntilTheTitleMovesToTheSharedList() = runScenario(nested = true)

    @Test fun aWrongCardProposalIsRepairedBeforeTheCorrectNativeHighlightAppears() = runScenario(nested = true, wrongFirst = true)

    @Test fun onlyTheNamedButtonIsGuidedAfterItBecomesEnabled() = runScenario(nested = true, disabledFirst = true)

    @Test fun anObservedPreparationEditorIsHighlightedWhileTheNamedButtonIsDisabled() = runScenario(nested = true, prepare = true)

    @Test fun latePreparationRecoversWithTheCurrentNativeButtonNotice() = runScenario(nested = true, prepare = true, latePrepare = true)

    @Test fun aPendingNativeScopeRepairRestartsOnlyForTheNamedButtonsNotice() =
        runScenario(nested = true, wrongFirst = true, lateRepair = true)

    @Test fun aNativeTitleComingIntoViewGetsAFreshPlanWithoutRepairingTheClippedFrame() =
        runScenario(nested = true, wrongFirst = true, clippedTitle = true)

    @Test fun aNativeFormWithTwoRequiredFieldsRepairsThePlanAndOnlyShowsTheEmptyField() =
        runScenario(nested = true, prepare = true, multipleInputs = true)

    @Test fun clearingTheFirstNativeFieldDoesNotDiscardTheSecondDraftOrSkipItsDeclaration() =
        runScenario(nested = true, prepare = true, multipleInputs = true, sequenceInputs = true)

    @Test fun aNativeClickCompletesOnlyAfterTheRequestedResultHeadingAppears() =
        runScenario(nested = true, arrival = true)

    @Test fun aNativePaneCanVerifyArrivalWithoutAHeadingAndRejectAConflictingHeading() =
        runScenario(nested = true, arrival = true, paneArrival = true)

    @Test fun theNativeRecheckCanEndAnUnlabeledArrivalAsUserConfirmation() =
        runScenario(nested = true, arrival = true, manualArrival = true)

    @Test fun aWrongNativePaneGuidesAVisibleReturnAndResumesTheOriginalGoal() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true)

    @Test fun aLateNativeEditorWithdrawsTheReturnHighlight() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, lateEditor = true)

    @Test fun anUnchangedNativeScreenDoesNotRepeatTheReturnInstruction() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, returnDoesNotNavigate = true)

    @Test fun repeatingTheSameNativeWrongRouteRepairsToSearchAfterReturning() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true)

    @Test fun alternatingNativeRoutesDoNotForgetEitherFailure() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, alternateWrongRoutes = true)

    @Test fun systemBackRestoresTheFirstFailedRouteWithoutAnAppButton() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true, systemBackFirst = true)

    @Test fun aTargetlessRecoveryAfterReturningDoesNotAskToVerifyArrival() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true, noAlternative = true)

    @Test fun nativeRecoverySearchWaitsForTheMatchingSingleResultAndItsDestination() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true, searchContinuation = true)

    @Test fun nativeDuplicateNamedRowsWaitThenTheUniqueTitleRowCanOpen() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true,
            searchContinuation = true, directSearchRows = true)

    @Test fun nativeDateQualifiedRowsRequireTheSameDateAtArrival() =
        runScenario(nested = true, arrival = true, paneArrival = true, returnArrival = true, repeatWrongRoute = true,
            searchContinuation = true, directSearchRows = true, qualifiedRows = true)

    private fun runScenario(nested: Boolean, wrongFirst: Boolean = false, disabledFirst: Boolean = false,
                            prepare: Boolean = false, latePrepare: Boolean = false, lateRepair: Boolean = false,
                            clippedTitle: Boolean = false, multipleInputs: Boolean = false, sequenceInputs: Boolean = false,
                            arrival: Boolean = false, paneArrival: Boolean = false, manualArrival: Boolean = false,
                            returnArrival: Boolean = false, lateEditor: Boolean = false, returnDoesNotNavigate: Boolean = false,
                            repeatWrongRoute: Boolean = false, alternateWrongRoutes: Boolean = false, systemBackFirst: Boolean = false,
                            noAlternative: Boolean = false, searchContinuation: Boolean = false, directSearchRows: Boolean = false,
                            qualifiedRows: Boolean = false) {
        val i = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("qaV3ScopedTap") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = i.targetContext
        val fixture = i.context
        val automation = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (disabledFirst || prepare || arrival) automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val resolver = context.contentResolver
        val oldServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        AplPreferences.initialize(context)
        val oldPrefs = AplPreferences.get(context)
        val oldUi = GuideStateRepository.state.value
        val field = GuideController::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = AtomicInteger()
        val attempts = CopyOnWriteArrayList<V3PlanningAttempt>()
        val reservedPaths = CopyOnWriteArrayList<String>()
        val inputHighlights = CopyOnWriteArrayList<String>()
        val releaseInitialPlan = CompletableDeferred<Unit>()
        val releaseRepair = CompletableDeferred<Unit>()
        val lateRepairReturned = AtomicBoolean()
        val returningToSource = AtomicBoolean()
        val roundTrips = AtomicInteger()
        val firstRequestFrame = AtomicReference<ScreenSnapshot>()
        val observedInputProgress = CopyOnWriteArrayList<List<InputProgress>>()
        var oldEngine: Any? = null
        var injected = false
        fun main(block: () -> Unit) = i.runOnMainSync(block)
        fun secure(block: () -> Unit) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try { block() } finally { automation.dropShellPermissionIdentity() }
        }
        fun facts(frame: ScreenSnapshot?): String? = frame?.takeIf { it.packageName == fixture.packageName }?.let {
            val candidates = GuidePolicy.candidates(it).map { node -> node.id }
            "id=${it.snapshotId}; popup=${it.blockingPopup}; viewport=${it.viewport}; nodes=" + it.allNodes.map { node ->
                listOf(node.id, node.path, GuidePolicy.label(node), node.visibleToUser, node.clickable, node.enabled,
                    node.stateDescription, node.paneTitle, node.editable, node.hasInput, node.bounds, node.id in candidates)
            }
        }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (predicate()) return
                SystemClock.sleep(80)
            }
            val ui = GuideStateRepository.state.value
            val state = AssistStateRepository.state.value
            error("$label; phase=${ui.phase}; action=${ui.currentAction}; completion=${ui.awaitingControlCompletion}; " +
                "message=${ui.message}; highlight=${state.highlightedNodeId}; overlay=${state.overlayStatus}; calls=${calls.get()}; " +
                "first=${facts(firstRequestFrame.get())}; latest=${facts(state.snapshot)}")
        }
        fun cardPath(node: AccessibilityNodeModel, frame: ScreenSnapshot): String? = if (nested)
            frame.allNodes.singleOrNull { it.path == node.parentPath }?.parentPath else node.parentPath
        fun changeNotice(mutation: String) {
            fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java).putExtra(mutation, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        fun titleFits(frame: ScreenSnapshot): Boolean? {
            val viewport = frame.viewport ?: return null
            val title = frame.allNodes.singleOrNull { it.text == "회의 자료" && it.visibleToUser } ?: return null
            return title.bounds.left >= viewport.left && title.bounds.right <= viewport.right &&
                title.bounds.top >= viewport.top && title.bounds.bottom <= viewport.bottom
        }
        fun writeFixtureField(hint: String, value: String) {
            fun editor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (root.packageName?.toString() == fixture.packageName && root.isEditable &&
                    root.hintText?.toString() == hint) return root
                for (index in 0 until root.childCount) {
                    root.getChild(index)?.let { editor(it)?.let { found -> return found } }
                }
                return null
            }
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val target = automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::editor).first()
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        }

        try {
            main {
                GuideController.initialize(context)
                GuideController.stop()
                if (prepare) LocalInputRevisions.enable(fixture.packageName)
                AplPreferences.update(context) { it.copy(voiceEnabled = false, reduceMotion = true) }
            }
            val component = ComponentName(context, AssistAccessibilityService::class.java).flattenToString()
            secure {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            }
            await("service connects") { AssistStateRepository.state.value.serviceConnected }
            fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java).putExtra("scopedCardProbe", true)
                .putExtra("nestedScopedCards", nested)
                .putExtra("disableScopedCard", disabledFirst)
                .putExtra("prepareScopedCard", prepare)
                .putExtra("multipleScopedInputs", multipleInputs)
                .putExtra("sequenceScopedInputs", sequenceInputs)
                .putExtra("startClippedScope", clippedTitle)
                .putExtra("scopedArrival", arrival)
                .putExtra("scopedPaneArrival", paneArrival)
                .putExtra("scopedReturnArrival", returnArrival)
                .putExtra("scopedReturnDoesNotNavigate", returnDoesNotNavigate)
                .putExtra("repeatScopedWrongRoute", repeatWrongRoute && !noAlternative)
                .putExtra("scopedSearchContinuation", searchContinuation)
                .putExtra("alternateScopedWrongRoutes", alternateWrongRoutes)
                .putExtra("scopedSystemBack", systemBackFirst)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("both repeated buttons are present") {
                AssistStateRepository.state.value.snapshot?.let { frame ->
                    frame.packageName == fixture.packageName && !frame.truncated && !frame.blockingPopup &&
                        frame.allNodes.count { it.text == "열기" && it.clickable } == 2 &&
                        (!arrival || frame.allNodes.firstOrNull { it.parentPath == null }?.bounds == frame.viewport) &&
                        (!returnArrival || frame.allNodes.any { it.paneTitle == "자료 목록" }) &&
                        titleFits(frame) == !clippedTitle
                } == true
            }
            val engine = V3GuideEngine(scope, AssistStateRepository.state, context.packageName, { BridgeStatus(true, "offline scope fixture") },
                propose@{ goal, frame, _, _, attempt ->
                    val call = calls.incrementAndGet()
                    if (sequenceInputs) observedInputProgress += goal.inputProgress
                    if (call == 1) firstRequestFrame.set(frame)
                    val wrong = ((wrongFirst || disabledFirst) && call == 1) || (lateRepair && call == 2)
                    attempts += attempt
                    val titleText = if (wrong) "여행 사진" else "회의 자료"
                    val title = frame.allNodes.singleOrNull { it.text == titleText }
                    val button = title?.let { heading ->
                        // Deliberately keep proposing the first card after the title moves outside it.
                        if (nested && wrong) frame.allNodes.last { it.text == "열기" && it.clickable }
                        else if (nested) frame.allNodes.firstOrNull { it.text == "열기" && it.clickable }
                        else frame.allNodes.single { it.parentPath == heading.parentPath && it.clickable }
                    }
                    val navigation = TaskPlan(if (button == null) NextKind.OBSERVE else NextKind.GUIDE, "자료 위치 확인", GoalKind.INSPECT,
                        emptyList(), emptyList(), emptyList(), if (button == null) Operation.NONE else Operation.TAP,
                        button?.id, "현재 자료 제목을 확인해주세요.", null, WaitKind.NONE, emptyList(), emptyList(),
                        scopeNodeIds = listOfNotNull(title?.id))
                    if (searchContinuation && frame.allNodes.any { it.text == "검색 중" }) {
                        return@propose navigation.copy(kind = NextKind.WAIT, waitKind = WaitKind.SCREEN_TRANSITION,
                            message = "검색 결과를 기다리고 있어요.")
                    }
                    if (searchContinuation && frame.allNodes.any { it.paneTitle == "검색 결과" }) {
                        if (directSearchRows) {
                            val rows = frame.allNodes.filter { it.clickable && GuidePolicy.label(it) in setOf("여행 사진", "회의 자료") }
                            val ambiguous = if (qualifiedRows) frame.allNodes.count { it.text == "2026년" } > 1
                                else rows.count { it.text == "회의 자료" } > 1
                            if (ambiguous) return@propose navigation.copy(kind = NextKind.RECOVER,
                                operation = Operation.NONE, targetNodeId = null, scopeNodeIds = emptyList(), message = "결과를 확인해주세요.")
                            val repair = attempt.diagnostic?.reason == "scoped_navigation_mismatch"
                            val row = if (repair == qualifiedRows) rows.last() else rows.first()
                            return@propose navigation.copy(kind = NextKind.GUIDE, operation = Operation.TAP,
                                targetNodeId = row.id, scopeNodeIds = emptyList())
                        }
                        val result = frame.allNodes.single { it.text in setOf("여행 사진", "회의 자료") }
                        val open = frame.allNodes.single { it.text == "열기" && it.clickable }
                        return@propose navigation.copy(kind = NextKind.GUIDE, operation = Operation.TAP,
                            targetNodeId = open.id, scopeNodeIds = listOf(result.id))
                    }
                    if (repeatWrongRoute && attempt.diagnostic?.reason == "repeated_wrong_navigation") {
                        if (noAlternative) return@propose navigation.copy(kind = NextKind.RECOVER, operation = Operation.NONE,
                            targetNodeId = null, scopeNodeIds = emptyList(), message = "다른 경로를 찾지 못했어요.")
                        val search = frame.allNodes.single { it.text == "검색" && it.clickable }
                        return@propose navigation.copy(targetNodeId = search.id, scopeNodeIds = emptyList(), message = "검색을 눌러주세요.")
                    }
                    if (alternateWrongRoutes && button != null && (roundTrips.get() == 1 ||
                            roundTrips.get() >= 3 && attempt.diagnostic?.reason == "repeated_wrong_navigation")) {
                        val viewButton = frame.allNodes.first { it.text == "보기" && it.clickable }
                        return@propose navigation.copy(targetNodeId = viewButton.id)
                    }
                    if (arrival && call > 1) {
                        if (returnArrival && returningToSource.get() && button != null) return@propose navigation
                        val result = if (button == null) {
                            frame.allNodes.singleOrNull { it.paneTitle == "회의 자료" }
                                ?: frame.allNodes.singleOrNull { it.text in setOf("자료를 열고 있어요", "회의 자료", "여행 사진") }
                        } else null
                        return@propose if (result == null) navigation.copy(kind = NextKind.WAIT, operation = Operation.NONE, targetNodeId = null,
                            waitKind = WaitKind.SCREEN_TRANSITION, relevantNodeIds = listOfNotNull(title?.id))
                        else navigation.copy(kind = NextKind.COMPLETE, operation = Operation.NONE, targetNodeId = null, scopeNodeIds = emptyList(),
                            conditions = listOf(GoalCondition("opened", "회의 자료 열림", true, Truth.SATISFIED, EvidenceKind.OBSERVED,
                                listOf(result.id), basis = ConditionBasis(ConditionOrigin.INFERRED, quote = goal.request, rationale = "시험용 결과 화면"))),
                            message = "회의 자료가 열렸어요.")
                    }
                    if (call == 1 && (latePrepare || clippedTitle)) releaseInitialPlan.await()
                    if (prepare && (call == 1 || multipleInputs) && button != null) {
                        val label = if (multipleInputs && (!sequenceInputs || call > 1)) "분류 코드" else "문서 이름"
                        val input = frame.allNodes.single { it.editable && it.inputHint == label }
                        val covered = if (sequenceInputs) frame.allNodes.filter { it.editable && it.inputHint in setOf("문서 이름", "분류 코드") }
                        else listOf(input) + if (multipleInputs && call > 1)
                            listOf(frame.allNodes.single { it.editable && it.inputHint == "문서 이름" }) else emptyList()
                        val steps = covered.mapIndexed { index, editor ->
                            TaskStep("input$index", "${editor.inputHint} 입력", listOf(editor.id), emptyList())
                        }
                        val preparing = !sequenceInputs || call <= 2
                        navigation.copy(operation = if (preparing) Operation.INPUT else Operation.TAP,
                            targetNodeId = if (preparing) input.id else button.id,
                            effect = if (preparing) ActionEffect.PREPARE else ActionEffect.NONE,
                            message = if (label == "분류 코드") "분류 코드를 입력해주세요." else "문서 이름을 입력해주세요.",
                            tasks = steps + TaskStep("open", "자료 열기", listOf(button.id), steps.map { it.key }),
                            relations = steps.map { TaskRelation(RelationKind.BEFORE, listOf(it.key, "open")) },
                            conditions = covered.mapIndexed { index, editor ->
                                val description = "${editor.inputHint} 입력 필요"
                                val owned = goal.requirements.singleOrNull { it.description == description }
                                GoalCondition("required$index", description, true,
                                    if (editor.hasInput == true) Truth.SATISFIED else Truth.UNSATISFIED,
                                    EvidenceKind.OBSERVED, listOf(editor.id, button.id),
                                    requirementId = owned?.id, requirementVersion = owned?.version,
                                    basis = if (owned == null) ConditionBasis(ConditionOrigin.INFERRED, quote = goal.request,
                                        rationale = "앱의 공개 상태 설명") else null,
                                    phase = ConditionPhase.PREPARATION, expectations = listOf(ValueExpectation(ValueProperty.INPUT_PRESENT,
                                        ValueSubject(editor.className.orEmpty(), "", editor.inputHint.orEmpty()), expectedBoolean = true)))
                            })
                    } else if (lateRepair && call == 2) withContext(NonCancellable) {
                        releaseRepair.await()
                        lateRepairReturned.set(true)
                        navigation
                    } else navigation
                }, GuideController::publishState, { node ->
                    if (node.editable) inputHighlights += node.inputHint.orEmpty()
                    AssistAccessibilityService.requestGuideHighlight(node)
                },
                AssistAccessibilityService::clearHighlight, {}, {}, AssistAccessibilityService::requestRefresh,
                nowUptime = SystemClock::uptimeMillis, highlightScoped = { proof ->
                    reservedPaths += proof.node.path
                    AssistAccessibilityService.requestScopedGuideHighlight(proof)
                })
            main { oldEngine = field.get(null); field.set(null, engine); injected = true }
            val request = if (qualifiedRows) "2026년 회의 자료를 열어줘" else "회의 자료를 열어줘"
            runBlocking { withContext(Dispatchers.Main) { assertTrue(engine.start(request, fixture.packageName, "공개 자료")) } }
            if (arrival) {
                fun clickHighlighted(label: String) {
                    val state = AssistStateRepository.state.value
                    val shown = requireNotNull(state.snapshot).allNodes.single { it.id == state.highlightedNodeId }
                    val bounds = Rect(shown.bounds.left, shown.bounds.top, shown.bounds.right, shown.bounds.bottom)
                    if (qualifiedRows && label == "회의 자료") {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}"))
                            .use { it.readBytes() }
                        return
                    }
                    val native = automation.windows.asSequence().mapNotNull { it.root }
                        .filter { it.packageName?.toString() == fixture.packageName }
                        .flatMap { it.findAccessibilityNodeInfosByText(label).asSequence() }
                        .single { it.isClickable && Rect().apply { it.getBoundsInScreen(this) } == bounds }
                    assertTrue(native.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                await("the requested native open button is highlighted") {
                    val state = AssistStateRepository.state.value
                    state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && it.text == "열기" } == true
                }
                clickHighlighted("열기")
                fun awaitRejectedResult(title: String, afterCalls: Int = 0) {
                    await("result $title does not complete the guide") {
                        AssistStateRepository.state.value.snapshot?.allNodes?.any { it.text == title } == true &&
                            calls.get() > afterCalls && engine.ui.currentAction == "wait" &&
                            (engine.ui.message.contains("아직 확인하지 못했어요") || engine.ui.message.contains("화면을 다시 확인해주세요"))
                    }
                }
                fun updateResult(title: String, paneTitle: String? = null, heading: Boolean = true, hideReturn: Boolean = false,
                                 detail: String? = null) {
                    val change = Intent(fixture, V3FormFixtureActivity::class.java).putExtra("scopedArrivalHeading", title)
                        .putExtra("scopedArrivalPaneTitle", paneTitle).putExtra("scopedArrivalHasHeading", heading)
                        .putExtra("hideScopedReturn", hideReturn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (detail != null) change.putExtra("scopedArrivalDetail", detail)
                    fixture.startActivity(change)
                }
                awaitRejectedResult("자료를 열고 있어요")
                if (returnArrival) {
                    updateResult("여행 사진", paneTitle = "여행 사진", heading = false, hideReturn = systemBackFirst)
                    await("the wrong native pane exposes the expected recovery state") {
                        val state = AssistStateRepository.state.value
                        val nodes = state.snapshot?.allNodes.orEmpty()
                        val recoveryVisible = if (systemBackFirst) state.highlightedNodeId == null &&
                            engine.ui.currentAction == "wait" && nodes.none { it.text == "자료 목록으로 돌아가기" }
                        else nodes.any { it.id == state.highlightedNodeId && it.text == "자료 목록으로 돌아가기" }
                        nodes.any { it.paneTitle == "여행 사진" } && recoveryVisible &&
                            engine.ui.message.contains("여행 사진")
                    }
                    assertEquals(systemBackFirst, engine.ui.awaitingControlCompletion)
                    if (lateEditor) {
                        val beforeEditor = calls.get()
                        fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java)
                            .putExtra("showScopedArrivalEditor", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        await("the real overlay withdraws the return while the editor and the button remain visible") {
                            val state = AssistStateRepository.state.value
                            val nodes = state.snapshot?.allNodes.orEmpty()
                            nodes.any { it.inputHint == "공개 작성 시험" && it.hasInput == true } &&
                                nodes.any { it.text == "자료 목록으로 돌아가기" && it.visibleToUser } &&
                                state.highlightedNodeId == null && engine.ui.currentAction == "wait" &&
                                engine.ui.message.contains("작성 중인 내용")
                        }
                        assertEquals(beforeEditor, calls.get())
                        assertTrue(AssistStateRepository.state.value.snapshot?.allNodes.orEmpty().none { it.text == "공개 초안" })
                        i.sendStatus(0, Bundle().apply {
                            putString("v3ScopedLateEditorProof", "return_previously_guided=true;late_editor_observed=true;" +
                                "draft_text_redacted=true;return_still_visible=true;overlay_cleared=true;extra_plans=0;native_fixture_clicks=1")
                        })
                        return
                    }
                    val beforeReturn = calls.get()
                    if (!returnDoesNotNavigate) returningToSource.set(true)
                    if (alternateWrongRoutes) roundTrips.incrementAndGet()
                    if (systemBackFirst) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_BACK"))
                            .use { it.readBytes() }
                    } else clickHighlighted("자료 목록으로 돌아가기")
                    if (returnDoesNotNavigate) {
                        await("an unchanged native destination shows a recheck without highlighting return again") {
                            val state = AssistStateRepository.state.value
                            val nodes = state.snapshot?.allNodes.orEmpty()
                            nodes.any { it.paneTitle == "여행 사진" } &&
                                nodes.any { it.text == "자료 목록으로 돌아가기" && it.visibleToUser } &&
                                state.highlightedNodeId == null && engine.ui.currentAction == "wait" &&
                                engine.ui.controlCompletionEnabled && engine.ui.message.contains("이전 화면이 열렸는지 확인하지 못했어요")
                        }
                        main { assertTrue(engine.confirmControl()) }
                        assertEquals(beforeReturn, calls.get())
                        assertEquals(null, AssistStateRepository.state.value.highlightedNodeId)
                        returningToSource.set(true)
                        changeNotice("restoreScopedCardList")
                    }
                    if (alternateWrongRoutes) {
                        for ((label, repeated) in listOf("보기" to false, "열기" to true, "보기" to true)) {
                            await("the current scoped route $label is guided") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && it.text == label } == true
                            }
                            val beforeOpen = calls.get()
                            clickHighlighted(label)
                            awaitRejectedResult("자료를 열고 있어요", beforeOpen)
                            updateResult("여행 사진", paneTitle = "여행 사진", heading = false)
                            await("the same destination has the expected repeat status for $label") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && it.text == "자료 목록으로 돌아가기" } == true &&
                                    engine.ui.message.contains("다시 열렸어요") == repeated
                            }
                            roundTrips.incrementAndGet()
                            clickHighlighted("자료 목록으로 돌아가기")
                        }
                        await("both previously failed controls are blocked on the restored list") {
                            val state = AssistStateRepository.state.value
                            state.snapshot?.allNodes?.any { it.paneTitle == "자료 목록" } == true &&
                                state.highlightedNodeId == null && engine.ui.phase == "error" && engine.ui.message.contains("다른 방법")
                        }
                        assertEquals(listOf("0", "1", "0", "1"), reservedPaths.map { it.substringAfterLast('/') })
                        assertEquals(listOf(1, 2), attempts.mapNotNull { it.diagnostic?.blockedNodeIds?.size })
                        i.sendStatus(0, Bundle().apply {
                            putString("v3ScopedAlternatingRouteProof", "alternating_routes=4;distinct_route_failures=2;" +
                                "first_failures_independent=true;both_routes_blocked=true;repair_attempts=2;native_fixture_clicks=8")
                        })
                        return
                    }
                    await("after the native return the original target is guided again") {
                        val state = AssistStateRepository.state.value
                        val nodes = state.snapshot?.allNodes.orEmpty()
                        nodes.any { it.paneTitle == "자료 목록" } &&
                            nodes.any { it.id == state.highlightedNodeId && it.text == "열기" } &&
                            engine.ui.message == "‘회의 자료’의 ‘열기’를 눌러주세요."
                    }
                    if (repeatWrongRoute) {
                        val beforeSecondOpen = calls.get()
                        clickHighlighted("열기")
                        awaitRejectedResult("자료를 열고 있어요", beforeSecondOpen)
                        updateResult("여행 사진", paneTitle = "여행 사진", heading = false)
                        await("the second wrong arrival is recognized and offers the safe return") {
                            val state = AssistStateRepository.state.value
                            state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && it.text == "자료 목록으로 돌아가기" } == true &&
                                engine.ui.message.contains("다시 열렸어요")
                        }
                        clickHighlighted("자료 목록으로 돌아가기")
                        if (noAlternative) {
                            await("the restored list holds without asking to verify arrival") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.paneTitle == "자료 목록" } == true &&
                                    state.highlightedNodeId == null && !engine.ui.awaitingControlCompletion &&
                                    engine.ui.message.contains("이 화면에서 다른 방법을 찾지 못했어요")
                            }
                            assertEquals(2, reservedPaths.size)
                            assertEquals(1, attempts.count { it.diagnostic?.reason == "repeated_wrong_navigation" })
                            i.sendStatus(0, Bundle().apply {
                                putString("v3ScopedNoAlternativeProof", "source_restored=true;targetless_recovery=true;" +
                                    "arrival_recheck=false;repeat_highlight=false;native_fixture_clicks=4")
                            })
                            return
                        }
                        await("the returned source highlights search in place of the repeated control") {
                            val state = AssistStateRepository.state.value
                            state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && it.text == "검색" } == true &&
                                engine.ui.message == "검색을 눌러주세요."
                        }
                        assertEquals(2, reservedPaths.size)
                        val recovery = attempts.single { it.diagnostic?.reason == "repeated_wrong_navigation" }.diagnostic!!
                        assertEquals(1, recovery.blockedNodeIds.size)
                        if (searchContinuation) {
                            fun updateSearchResult(title: String) {
                                val extra = when {
                                    qualifiedRows -> "scopedSearchFirstDetail"
                                    directSearchRows -> "scopedSearchPeer"
                                    else -> "scopedSearchResult"
                                }
                                fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java)
                                    .putExtra(extra, title)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            }
                            clickHighlighted("검색")
                            await("loading search results does not retain a highlight") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.text == "검색 중" } == true &&
                                    state.highlightedNodeId == null && engine.ui.message == "검색 결과를 기다리고 있어요."
                            }
                            updateSearchResult(if (qualifiedRows) "2026년" else if (directSearchRows) "회의 자료" else "여행 사진")
                            await("the incorrectly proposed search result is not highlighted") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.paneTitle == "검색 결과" } == true &&
                                    state.highlightedNodeId == null &&
                                    engine.ui.message.contains(if (directSearchRows) "여러 개" else "해당하는 결과")
                            }
                            main { assertTrue(engine.confirmControl()) }
                            updateSearchResult(if (qualifiedRows) "2025년" else if (directSearchRows) "여행 사진" else "회의 자료")
                            val resultLabel = if (directSearchRows) "회의 자료" else "열기"
                            val instruction = when {
                                qualifiedRows -> "‘회의 자료’ 중 ‘2026년’ 항목을 눌러주세요."
                                directSearchRows -> "‘회의 자료’를 눌러주세요."
                                else -> "‘회의 자료’의 ‘열기’를 눌러주세요."
                            }
                            await("the title-only update binds the correct search result") {
                                val state = AssistStateRepository.state.value
                                state.snapshot?.allNodes?.any { it.id == state.highlightedNodeId && GuidePolicy.label(it) == resultLabel } == true &&
                                    engine.ui.message == instruction
                            }
                            if (directSearchRows) assertEquals(1, attempts.count { it.diagnostic?.reason == "scoped_navigation_mismatch" })
                            val beforeOpen = calls.get()
                            clickHighlighted(resultLabel)
                            awaitRejectedResult("자료를 열고 있어요", beforeOpen)
                            val wrongTitle = if (qualifiedRows) "회의 자료" else "여행 사진"
                            updateResult(wrongTitle, paneTitle = wrongTitle, heading = false, hideReturn = true,
                                detail = "2025년".takeIf { qualifiedRows })
                            awaitRejectedResult(wrongTitle)
                            updateResult("회의 자료", paneTitle = "회의 자료", heading = false, hideReturn = true,
                                detail = "2026년".takeIf { qualifiedRows })
                            await("only the verified named destination completes the guide") { engine.ui.phase == "done" }
                            assertEquals(3, reservedPaths.size)
                            i.sendStatus(0, Bundle().apply {
                                if (qualifiedRows) putString("v3ScopedQualifiedRowProof", "duplicate_dates_waited=true;unique_requested_date_highlighted=true;" +
                                    "same_title_wrong_date_not_completed=true;same_title_correct_date_completed=true;native_fixture_clicks=6")
                                else if (directSearchRows) putString("v3ScopedNamedRowProof", "duplicate_titles_waited=true;" +
                                    "wrong_peer_repaired=true;unique_title_row_highlighted=true;wrong_destination_not_completed=true;named_destination_completed=true;native_fixture_clicks=6")
                                else putString("v3ScopedSearchContinuationProof", "search_loading_waited=true;wrong_single_result_not_guided=true;" +
                                    "title_only_update_resumed=true;wrong_destination_not_completed=true;named_destination_completed=true;native_fixture_clicks=6")
                            })
                            return
                        }
                        i.sendStatus(0, Bundle().apply {
                            if (systemBackFirst) {
                                putString("v3ScopedSystemBackProof", "first_app_return_button_absent=true;system_back_invoked=true;" +
                                    "source_restored=true;repeat_blocked=true;alternate_highlighted=true;blocked_ids=1;native_fixture_clicks=3")
                            } else {
                                putString("v3ScopedRepeatedRouteProof", "same_wrong_pane_twice=true;source_restored_twice=true;" +
                                    "third_open_highlight=false;bounded_repair=1;different_control_highlighted=true;native_fixture_clicks=4")
                            }
                        })
                        return
                    }
                    i.sendStatus(0, Bundle().apply {
                        if (returnDoesNotNavigate) {
                            putString("v3ScopedReturnWaitProof", "return_click_observed=true;wrong_pane_unchanged=true;" +
                                "repeat_highlight=false;recheck_without_model=true;original_source_verified=true;original_goal_resumed=true;native_fixture_clicks=2")
                        } else {
                            putString("v3ScopedReturnProof", "wrong_peer_pane=true;named_source_return_guided=true;" +
                                "returned_only_after_fixture_click=true;original_goal_resumed=true;native_fixture_clicks=2;external_actions=0")
                        }
                    })
                    return
                }
                if (manualArrival) {
                    val afterLoading = calls.get()
                    updateResult("회의 자료", heading = false)
                    awaitRejectedResult("회의 자료", afterLoading)
                    val previousRead = requireNotNull(AssistStateRepository.state.value.snapshot).snapshotId
                    val previousCalls = calls.get()
                    fun overlayButton(label: String): AccessibilityNodeInfo? {
                        val root = automation.windows.singleOrNull { it.title?.toString() == "APL2 행동 안내" }?.root
                        return root?.findAccessibilityNodeInfosByText(label)?.singleOrNull {
                            it.isVisibleToUser && it.isClickable && it.isEnabled && it.text?.toString() == label
                        }
                    }
                    await("the local recheck is present in the native overlay") { overlayButton("다시 확인") != null }
                    assertTrue(requireNotNull(overlayButton("다시 확인")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("the new observation shows a user confirmation control") {
                        (AssistStateRepository.state.value.snapshot?.snapshotId ?: 0) > previousRead &&
                            overlayButton("화면에서 확인했어요") != null
                    }
                    assertEquals(previousCalls, calls.get())
                    assertTrue(requireNotNull(overlayButton("화면에서 확인했어요")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("the guide ends with the user confirmation source") {
                        engine.ui.phase == "done" && engine.ui.currentAction == "user_confirmed_result" &&
                            engine.ui.decisionSource == "user"
                    }
                    assertEquals(previousCalls, calls.get())
                    assertEquals("사용자 확인", GuideHistoryPolicy.phaseLabel(engine.ui.phase, engine.ui.currentAction))
                    i.sendStatus(0, Bundle().apply {
                        putString("v3ScopedArrivalManualProof", "native_overlay_recheck=true;fresh_local_read=true;" +
                            "extra_plans=0;native_user_confirmation=true;history_source_distinct=true;external_actions=0")
                    })
                    return
                }
                if (paneArrival) {
                    fun wirePaneTitles(frame: ScreenSnapshot): List<String> {
                        val goal = TaskGoal(1, 1, "회의 자료를 열어줘", fixture.packageName, "공개 자료")
                        val packet = V3PlanRequest.encode(goal, frame, null, "", GuideBackend.SUBSCRIPTION_TERRA, org.json.JSONObject())
                        val content = org.json.JSONObject(packet.bytes.toString(Charsets.UTF_8))
                            .getJSONArray("input").getJSONObject(0).getString("content")
                        val nodes = org.json.JSONObject(content).getJSONArray("nodes")
                        return (0 until nodes.length()).map { nodes.getJSONObject(it) }.filter { it.has("paneTitle") }
                            .map { it.getString("paneTitle") }
                    }
                    val afterLoading = calls.get()
                    updateResult("회의 자료", paneTitle = "여행 사진")
                    awaitRejectedResult("회의 자료", afterLoading)
                    val wrong = requireNotNull(AssistStateRepository.state.value.snapshot)
                    assertTrue(wrong.allNodes.any { it.paneTitle == "여행 사진" })
                    assertTrue(wirePaneTitles(wrong).isEmpty())

                    updateResult("자료 본문", paneTitle = "회의 자료", heading = false)
                    await("the newly named native pane completes without a heading") {
                        val nodes = AssistStateRepository.state.value.snapshot?.allNodes.orEmpty()
                        nodes.any { it.paneTitle == "회의 자료" } && nodes.none { it.heading } && engine.ui.phase == "done"
                    }
                    assertEquals(listOf("회의 자료"), wirePaneTitles(requireNotNull(AssistStateRepository.state.value.snapshot)))
                    i.sendStatus(0, Bundle().apply {
                        putString("v3ScopedPaneProof", "native_clicks=1;conflicting_heading_rejected=true;" +
                            "named_pane_verified_without_heading=true;unrelated_pane_not_encoded=true;external_actions=0")
                    })
                    return
                }
                updateResult("여행 사진")
                awaitRejectedResult("여행 사진")
                assertNotEquals("done", engine.ui.phase)
                updateResult("회의 자료")
                await("the matching native heading completes the guide") {
                    engine.ui.phase == "done" && AssistStateRepository.state.value.snapshot?.allNodes?.any {
                        it.heading && it.text == "회의 자료"
                    } == true
                }
                i.sendStatus(0, Bundle().apply {
                    putString("v3ScopedArrivalProof", "native_test_clicks=1;loading_rejected=true;wrong_title_rejected=true;" +
                        "matching_heading_verified=true;planner_calls=${calls.get()};external_actions=0")
                })
                return
            }
            if (sequenceInputs) {
                fun awaitEditor(hint: String, present: Boolean, messageFragment: String? = null) {
                    await("current native editor is $hint; hasInput=$present") {
                        val state = AssistStateRepository.state.value
                        val shown = state.snapshot?.allNodes?.singleOrNull { it.id == state.highlightedNodeId }
                        shown?.inputHint == hint && shown.hasInput == present &&
                            engine.ui.awaitingControlCompletion && engine.ui.controlCompletionEnabled == present &&
                            (messageFragment == null || engine.ui.message.contains(messageFragment))
                    }
                }
                awaitEditor("문서 이름", false)
                writeFixtureField("문서 이름", "공개 시험 제목")
                awaitEditor("문서 이름", true)
                main { assertTrue(engine.confirmControl()) }
                awaitEditor("분류 코드", false)
                writeFixtureField("분류 코드", "sample code")
                awaitEditor("분류 코드", true)
                writeFixtureField("문서 이름", "")
                awaitEditor("문서 이름", false)
                main { assertFalse(engine.confirmControl()) }
                writeFixtureField("문서 이름", "다시 입력한 공개 시험 제목")
                awaitEditor("문서 이름", true)
                main { assertTrue(engine.confirmControl()) }
                awaitEditor("분류 코드", true, "입력을 마쳤으면")
                assertEquals(3, calls.get())
                assertTrue(reservedPaths.isEmpty())
                assertEquals(1, observedInputProgress.last().count { it.userFinished })
                main { assertTrue(engine.confirmControl()) }
                await("the enabled native button is finally guided") {
                    val state = AssistStateRepository.state.value
                    val shown = state.snapshot?.allNodes?.singleOrNull { it.id == state.highlightedNodeId }
                    shown?.text == "열기" && shown.enabled && engine.ui.message == "‘회의 자료’의 ‘열기’를 눌러주세요."
                }
                assertEquals(4, calls.get())
                assertEquals(2, observedInputProgress.last().count { it.userFinished })
                assertEquals(1, reservedPaths.size)
                i.sendStatus(0, Bundle().apply {
                    putString("v3ScopedInputContinuationProof", "first_clear_revoked_declaration=true;second_draft_preserved=true;" +
                        "second_declaration_required_before_open=true;planner_calls=4;synthetic_input_writes=4;native_app_clicks=0")
                })
                return
            }
            if (multipleInputs) {
                await("the corrected plan offers only the remaining native editor") {
                    val state = AssistStateRepository.state.value
                    val nodes = state.snapshot?.allNodes ?: return@await false
                    val highlighted = nodes.singleOrNull { it.id == state.highlightedNodeId }
                    highlighted?.inputHint == "분류 코드" && highlighted.hasInput == false &&
                        nodes.any { it.inputHint == "문서 이름" && it.hasInput == true } &&
                        engine.ui.awaitingControlCompletion && !engine.ui.controlCompletionEnabled
                }
                assertEquals(listOf(0, 1), attempts.map { it.index })
                assertSame(attempts[0].ticket, attempts[1].ticket)
                assertEquals("missing_scoped_required_inputs", attempts[1].diagnostic?.reason)
                assertEquals(setOf("분류 코드"), inputHighlights.toSet())
                assertTrue(reservedPaths.isEmpty())
                i.sendStatus(0, Bundle().apply {
                    putString("v3RequiredInputsProof", "native_required_fields=2;prefilled_field_preserved=true;" +
                        "one_omission_repair=true;same_ticket=true;remaining_field_only=true;planner_calls=2;native_clicks=0;input_writes=0")
                })
                return
            }
            if (clippedTitle) {
                await("the initial planner gets the clipped native observation") { attempts.size == 1 }
                assertEquals(false, titleFits(requireNotNull(firstRequestFrame.get())))
                val original = attempts.single().ticket
                assertTrue(reservedPaths.isEmpty())
                changeNotice("revealScopedTitle")
                await("the same native title is now fully inside the viewport") {
                    val frame = AssistStateRepository.state.value.snapshot ?: return@await false
                    frame.windowId == original.observation.windowId && frame.snapshotId > original.observation.sequence && titleFits(frame) == true
                }
                assertEquals(1, calls.get())
                releaseInitialPlan.complete(Unit)
                await("the fresh plan highlights only the requested native button") {
                    val state = AssistStateRepository.state.value
                    val frame = state.snapshot ?: return@await false
                    val title = frame.allNodes.singleOrNull { it.text == "회의 자료" } ?: return@await false
                    val shown = frame.allNodes.singleOrNull { it.id == state.highlightedNodeId }
                    shown?.text == "열기" && cardPath(shown, frame) == cardPath(title, frame) &&
                        engine.ui.message == "‘회의 자료’의 ‘열기’를 눌러주세요."
                }
                assertEquals(listOf(0, 0), attempts.map { it.index })
                val replacement = attempts.last().ticket
                assertEquals(original.sessionId, replacement.sessionId)
                assertEquals(original.goalRevision, replacement.goalRevision)
                assertEquals(original.observation.windowId, replacement.observation.windowId)
                assertNotEquals(original.requestId, replacement.requestId)
                assertEquals(1, reservedPaths.size)
                i.sendStatus(0, Bundle().apply {
                    putString("v3ClippedScopeProof", "original_title_clipped=true;current_title_fully_visible=true;same_window=true;" +
                        "fresh_ticket=true;same_goal=true;planner_calls=2;repair_attempts=0;highlight_reservations=1;native_clicks=0")
                })
                return
            }
            if (lateRepair) {
                await("the scoped correction has been requested") { attempts.size == 2 }
                val original = attempts.last().ticket
                assertSame(attempts.first().ticket, original)
                changeNotice("changePeerRepairNotice")
                await("the other native button publishes a new status") {
                    AssistStateRepository.state.value.snapshot?.allNodes?.any { it.stateDescription == "사진을 불러왔어요." } == true
                }
                main { assertTrue(engine.ui.busy); assertEquals(2, calls.get()) }
                assertTrue(reservedPaths.isEmpty())

                changeNotice("changeScopedRepairNotice")
                await("the named button is highlighted before the old repair responds") {
                    val state = AssistStateRepository.state.value
                    val shown = state.snapshot?.allNodes?.singleOrNull { it.id == state.highlightedNodeId }
                    shown?.stateDescription == "현재 자료를 열 수 있어요." && engine.ui.message == "‘회의 자료’의 ‘열기’를 눌러주세요."
                }
                assertFalse(lateRepairReturned.get())
                assertEquals(listOf(0, 1, 0), attempts.map { it.index })
                val replacement = attempts.last().ticket
                assertEquals(original.sessionId, replacement.sessionId)
                assertEquals(original.goalRevision, replacement.goalRevision)
                assertEquals(original.observation.windowId, replacement.observation.windowId)
                assertNotEquals(original.requestId, replacement.requestId)
                releaseRepair.complete(Unit)
                await("the outdated repair reaches its completion gate") { lateRepairReturned.get() }
                main { assertEquals("‘회의 자료’의 ‘열기’를 눌러주세요.", engine.ui.message) }
                assertEquals(1, reservedPaths.size)
                i.sendStatus(0, Bundle().apply {
                    putString("v3ScopedRepairNoticeProof", "peer_notice_kept_repair=true;named_notice_restarted_before_reply=true;" +
                        "late_reply_discarded=true;fresh_ticket=true;same_goal=true;same_window=true;planner_calls=3;" +
                        "highlight_reservations=1;native_clicks=0;input_writes=0")
                })
                return
            }
            if (latePrepare) {
                await("first plan has captured the original native notice") { attempts.size == 1 }
                val original = attempts.single().ticket
                changeNotice("changeScopedPreparationNotice")
                await("new public button notice is collected before the original response") {
                    val frame = AssistStateRepository.state.value.snapshot ?: return@await false
                    frame.snapshotId > original.observation.sequence && frame.windowId == original.observation.windowId &&
                        frame.allNodes.any { it.text == "열기" && !it.enabled && it.stateDescription?.contains("검토가 끝날 때까지") == true }
                }
                assertEquals(1, calls.get())
                releaseInitialPlan.complete(Unit)
                await("the current notice reaches the real overlay without offering the old editor") {
                    val state = AssistStateRepository.state.value
                    val caption = automation.windows.singleOrNull { it.title?.toString() == "APL2 행동 안내" }?.root
                    val showsNotice = caption?.findAccessibilityNodeInfosByText("검토가 끝날 때까지")?.any {
                        it.isVisibleToUser && it.text?.toString()?.contains("회의 자료") == true
                    } == true
                    state.highlightedNodeId == null && engine.ui.currentAction == "wait" && showsNotice
                }
                assertEquals(2, calls.get())
                assertTrue(inputHighlights.isEmpty())
                assertTrue(reservedPaths.isEmpty())
                assertEquals(listOf(0, 0), attempts.map { it.index })
                val replacement = attempts.last().ticket
                assertEquals(original.sessionId, replacement.sessionId)
                assertEquals(original.goalRevision, replacement.goalRevision)
                assertNotEquals(original.requestId, replacement.requestId)
                assertTrue(replacement.observation.sequence > original.observation.sequence)
                i.sendStatus(0, Bundle().apply {
                    putString("v3LatePreparationNoticeProof", "same_native_window=true;new_notice_before_response=true;" +
                        "old_input_highlights=0;current_notice_on_real_overlay=true;same_goal=true;fresh_ticket=true;" +
                        "planner_calls=2;repair_attempts=0;native_clicks=0;input_writes=0")
                })
                return
            }
            if (prepare) {
                await("the currently empty, observed editor is highlighted while the scoped button is disabled") {
                    val state = AssistStateRepository.state.value
                    val frame = state.snapshot ?: return@await false
                    val shown = frame.allNodes.singleOrNull { it.id == state.highlightedNodeId }
                    val ui = GuideStateRepository.state.value
                    shown?.editable == true && shown.hasInput == false && shown.inputHint == "문서 이름" &&
                        frame.allNodes.any { it.text == "열기" && !it.enabled && it.stateDescription?.contains("문서 이름") == true } &&
                        ui.currentAction == "highlight" && ui.awaitingControlCompletion && ui.message == "문서 이름을 입력해주세요."
                }
                assertEquals(1, calls.get())
                assertFalse(engine.ui.controlCompletionEnabled)
                main { assertFalse(engine.confirmControl()) }

                val before = requireNotNull(AssistStateRepository.state.value.snapshot)
                writeFixtureField("문서 이름", "synthetic note")
                await("typing in the still-required native editor keeps the current guidance") {
                    val state = AssistStateRepository.state.value
                    val shown = state.snapshot?.allNodes?.singleOrNull { it.id == state.highlightedNodeId }
                    shown?.hasInput == true && shown.inputHint == "문서 이름" && engine.ui.controlCompletionEnabled
                }
                assertEquals(1, calls.get())

                changeNotice("changeScopedPreparationNotice")
                await("a new public reason removes the previous input action") {
                    val state = AssistStateRepository.state.value
                    state.highlightedNodeId == null && engine.ui.currentAction == "wait" &&
                        engine.ui.message.contains("검토가 끝날 때까지")
                }
                assertEquals(before.windowId, AssistStateRepository.state.value.snapshot?.windowId)
                assertEquals(2, calls.get())
                main { assertFalse(engine.confirmControl()) }
                i.sendStatus(0, Bundle().apply {
                    putString("v3ScopedPreparationLifetimeProof", "native_disabled_button=true;empty_editor_highlighted=true;" +
                        "empty_completion_blocked=true;typing_kept_guide=true;typing_extra_plans=0;same_window=true;" +
                        "changed_notice_cleared_guide=true;stale_completion_blocked=true;planner_calls=2;native_clicks=0;synthetic_input_writes=1")
                })
                return
            }
            if (disabledFirst) {
                await("the real panel reports the named disabled button without highlighting its neighbor") {
                    val state = AssistStateRepository.state.value
                    val caption = automation.windows.singleOrNull { it.title?.toString() == "APL2 행동 안내" }?.root
                    val panelShowsWait = caption?.findAccessibilityNodeInfosByText("지금 누를 수 없어요")?.any {
                        it.isVisibleToUser && it.text?.toString()?.contains("회의 자료") == true
                    } == true
                    val frame = state.snapshot ?: return@await false
                    val buttons = frame.allNodes.filter { it.text == "열기" && it.clickable }
                    val title = frame.allNodes.singleOrNull { it.text == "회의 자료" } ?: return@await false
                    val target = buttons.singleOrNull { cardPath(it, frame) == cardPath(title, frame) }
                    state.highlightedNodeId == null && target?.enabled == false && buttons.count { it.enabled } == 1 && panelShowsWait &&
                        GuideStateRepository.state.value.currentAction == "wait"
                }
                val disabledFrame = requireNotNull(AssistStateRepository.state.value.snapshot)
                assertEquals(1, calls.get())
                assertTrue(reservedPaths.isEmpty())
                main { AssistAccessibilityService.requestRefresh() }
                await("the disabled card has been observed again") {
                    (AssistStateRepository.state.value.snapshot?.snapshotId ?: 0) > disabledFrame.snapshotId
                }
                assertEquals(1, calls.get())
                fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java).putExtra("enableScopedCard", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            await("only the button inside the named card is highlighted") {
                val state = AssistStateRepository.state.value
                val frame = state.snapshot
                val title = frame?.allNodes?.singleOrNull { it.text == "회의 자료" }
                val shown = frame?.allNodes?.singleOrNull { it.id == state.highlightedNodeId }
                val group = if (frame != null && title != null) cardPath(title, frame) else null
                frame != null && title != null && shown?.text == "열기" && shown.enabled && group != null && cardPath(shown, frame) == group &&
                    (!nested || shown.parentPath != title.parentPath) &&
                    GuideStateRepository.state.value.message == "‘회의 자료’의 ‘열기’를 눌러주세요."
            }
            val before = requireNotNull(AssistStateRepository.state.value.snapshot)
            val titleBefore = before.allNodes.single { it.text == "회의 자료" }
            val firstPlanCount = if (wrongFirst || disabledFirst) 2 else 1
            assertEquals(firstPlanCount, calls.get())
            val highlighted = before.allNodes.single { it.id == AssistStateRepository.state.value.highlightedNodeId }
            assertEquals(listOf(highlighted.path), reservedPaths.toList())
            if (wrongFirst) {
                assertEquals(listOf(0, 1), attempts.map { it.index })
                assertSame(attempts[0].ticket, attempts[1].ticket)
                assertEquals("scoped_navigation_mismatch", attempts[1].diagnostic?.reason)
            }
            if (disabledFirst) assertEquals(listOf(0, 0), attempts.map { it.index })
            val mutation = if (nested) "moveScopedTitleOutsideCard" else "renameScopedCard"
            fixture.startActivity(Intent(fixture, V3FormFixtureActivity::class.java).putExtra(mutation, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("the changed scope loses its highlight in the same window") {
                val state = AssistStateRepository.state.value
                val ui = GuideStateRepository.state.value
                val nodes = state.snapshot?.allNodes.orEmpty()
                val scopeChanged = if (nested) nodes.any { it.text == "회의 자료" && it.parentPath != titleBefore.parentPath }
                    else nodes.any { it.text == "새 자료" }
                state.highlightedNodeId == null && scopeChanged && (if (nested) ui.phase == "error" else ui.currentAction == "manual")
            }
            val after = requireNotNull(AssistStateRepository.state.value.snapshot)
            assertEquals(before.windowId, after.windowId)
            assertEquals(firstPlanCount + 1, calls.get())
            assertEquals(2, after.allNodes.count { it.text == "열기" && it.clickable })
            i.sendStatus(0, Bundle().apply {
                putString("v3ScopedTapProof", "nested=$nested;duplicate_buttons=2;highlighted_named_group=1;title_in_caption=true;same_window=true;scope_change_cleared_highlight=true;shared_scope_proposal_rejected=$nested;scope_repair=$wrongFirst;disabled_scope_wait=$disabledFirst;planner_calls=${calls.get()};native_clicks=0")
            })
        } finally {
            main {
                GuideController.stop()
                if (injected) field.set(null, oldEngine)
                AplPreferences.update(context) { oldPrefs }
                GuideStateRepository.publish(oldUi)
            }
            releaseRepair.complete(Unit)
            scope.cancel()
            secure {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
            }
        }
    }
}
