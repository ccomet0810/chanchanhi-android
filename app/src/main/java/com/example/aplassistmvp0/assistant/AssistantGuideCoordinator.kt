package com.example.aplassistmvp0.assistant

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import com.example.aplassistmvp0.accessibility.AssistAccessibilityService
import com.example.aplassistmvp0.guide.AppRouteResolver
import com.example.aplassistmvp0.guide.GuideTiming
import com.example.aplassistmvp0.guide.GuideHistory
import com.example.aplassistmvp0.guide.GuideStateRepository
import com.example.aplassistmvp0.guide.GuideUiState
import com.example.aplassistmvp0.guide.GuideController
import com.example.aplassistmvp0.guide.GuidePolicy
import com.example.aplassistmvp0.guide.AssistantGoalInputPort
import com.example.aplassistmvp0.guide.AssistantGoalInputSession
import com.example.aplassistmvp0.guide.AssistantGoalResolution
import com.example.aplassistmvp0.guide.runCurrent
import com.example.aplassistmvp0.state.AssistStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AssistantStartResult(val started: Boolean, val message: String, val route: com.example.aplassistmvp0.guide.GoalRoute? = null,
                                val displayedGoal: String? = null, val ended: Boolean = false)

/** 음성 창의 수명과 실제 화면 안내의 수명을 분리합니다. 모든 상태 변경은 메인 스레드입니다. */
object AssistantGuideCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val guard = AssistantInvocationGuard()
    private var application: Context? = null
    private var invocation: Invocation? = null
    private var goalInputOwner: AssistantGoalInputPort? = null
    internal fun configureGoalInput(owner: AssistantGoalInputPort?) {
        checkMain()
        check(invocation == null && !com.example.aplassistmvp0.guide.AppOpeningCoach.active)
        goalInputOwner = owner
    }
    private data class Invocation(
        val id: Long,
        val foregroundPackage: String?,
        var assistPackage: String? = null,
        var startJob: Deferred<AssistantStartResult>? = null,
        val goals: AssistantGoalInputSession? = null,
    )

    fun initialize(context: Context) {
        checkMain()
        application = context.applicationContext
        GuideController.initialize(context.applicationContext)
    }

    /** 호출 순간의 앱 이름만 기억합니다. 과거 화면의 노드나 좌표를 안내에 재사용하지 않습니다. */
    fun beginInvocation(): Long {
        checkMain()
        invocation?.startJob?.cancel()
        GuideController.stop()
        val id = guard.begin()
        invocation = Invocation(id, AssistStateRepository.state.value.foregroundPackage,
            goals = goalInputOwner?.let(::AssistantGoalInputSession))
        Log.d("APLAssistant", "begin id=$id foreground=${invocation?.foregroundPackage}")
        return id
    }

    /** 시스템 AssistStructure에서는 Activity 패키지만 받고 본문·이미지·웹 주소를 읽지 않습니다. */
    fun supplyAssistPackage(invocationId: Long, packageName: String?) {
        checkMain()
        val context = application ?: return
        val current = invocation?.takeIf { it.id == invocationId && guard.isCurrent(it.id) } ?: return
        if (packageName != null && GuidePolicy.isValidTargetPackage(packageName) &&
            packageName != context.packageName && packageName != "com.android.systemui") {
            current.assistPackage = packageName
            Log.d("APLAssistant", "assist id=$invocationId package=$packageName")
        }
    }

    /** 창을 직접 닫으면 준비 작업을 취소합니다. 안내로 넘기기 위해 닫힌 창은 안내를 끊지 않습니다. */
    fun cancelInvocation(invocationId: Long) {
        checkMain()
        if (!guard.cancel(invocationId)) return
        invocation?.takeIf { it.id == invocationId }?.startJob?.cancel()
        runCatching { invocation?.takeIf { it.id == invocationId }?.goals?.cancel() }
        invocation = null
        GuideController.stop()
    }

    suspend fun start(invocationId: Long, goal: String, selectedPackage: String? = null,
                      clarified: Boolean = false, clarificationText: String? = null,
                      closeSession: () -> Unit): AssistantStartResult =
        withContext(Dispatchers.Main.immediate) {
            Log.d("APLAssistant", "start id=$invocationId current=${invocation?.id} active=${guard.isCurrent(invocationId)}")
            val current = invocation?.takeIf { it.id == invocationId && guard.canSubmit(it.id) }
                ?: return@withContext AssistantStartResult(false, "닫힌 요청입니다. 다시 불러주세요.")
            if (current.startJob?.isActive == true && current.goals == null)
                return@withContext AssistantStartResult(false, "현재 요청의 안내를 준비하고 있습니다.")
            val input = try {
                current.goals?.prepare(if (clarified) clarificationText ?: goal else goal, selectedPackage, clarified)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withContext AssistantStartResult(false, "요청이 바뀌었어요. 다시 말씀해주세요.") }
            // Register pending input first: cancellation alone does not invalidate a canonical goal.
            if (current.goals != null) current.startJob?.cancel()
            // 세션 창이 없어져도 승인된 준비 작업은 앱의 scope에서 끝까지 처리합니다.
            GuideTiming.begin("assistant")
            val job = scope.async { startInternal(current, goal, closeSession, selectedPackage, clarified, input) }
            current.startJob = job
            job.await().also { result ->
                if (current.goals != null && input?.let(current.goals::owns) != true) return@also
                // 앱 선택 이전의 실패는 음성 창에만 보였으므로 별도 기록한다.
                val guide = GuideStateRepository.state.value
                val displayedGoal = result.displayedGoal ?: goal.trim().take(500)
                if (!result.started && !result.ended && result.route == null && !(guide.goal == displayedGoal && guide.phase in setOf("error", "paused"))) {
                    val origin = current.assistPackage ?: current.foregroundPackage.orEmpty()
                    GuideHistory.record(GuideUiState(goal = displayedGoal, phase = "error", message = result.message,
                        targetPackageName = origin, targetAppLabel = origin))
                }
            }
        }

    private suspend fun startInternal(current: Invocation, rawGoal: String,
                                      closeSession: () -> Unit, selectedPackage: String?, clarified: Boolean,
                                      input: AssistantGoalInputSession.Request?): AssistantStartResult {
        Log.d("APLAssistant", "preflight id=${current.id} initialized=${application != null} goalPresent=${rawGoal.isNotBlank()} service=${AssistStateRepository.state.value.serviceConnected}")
        val context = application ?: return AssistantStartResult(false, "APL 앱을 열어 설정해주세요.")
        var goal = rawGoal.trim().take(500)
        if (!available(context)) return AssistantStartResult(false, "잠금을 풀고 다시 불러주세요.")
        if (goal.isBlank()) return AssistantStartResult(false, "어떤 작업을 하고 싶은지 말해주세요.")
        if (current.goals == null && goal.replace(" ", "") in setOf("끝났어", "완료", "다했어", "완료했어", "끝났어요")) {
            if (!GuideController.confirmCompleted()) return AssistantStartResult(false, "최근에 완료한 화면을 확인하지 못했어요. 안내 후 결과 화면에서 완료를 눌러주세요.")
            if (guard.handOff(current.id)) closeSession()
            guard.finish(current.id); invocation = null
            return AssistantStartResult(true, "완료한 사용법을 기억했어요.")
        }
        val binding = if (current.goals == null) null else try {
            when (val result = current.goals.resolve(requireNotNull(input))) {
                is AssistantGoalResolution.Ready -> result.binding
                is AssistantGoalResolution.Question -> return AssistantStartResult(false, result.message,
                    com.example.aplassistmvp0.guide.GoalRoute(question = result.message, source = "goal_owner"), goal)
                is AssistantGoalResolution.Stopped -> {
                    check(guard.isCurrent(current.id) && current.goals.isCurrent(requireNotNull(input)))
                    if (!guard.handOff(current.id)) return AssistantStartResult(false, "취소된 요청입니다.")
                    try {
                        com.example.aplassistmvp0.guide.AppOpeningCoach.stop()
                        GuideController.stop()
                        closeSession()
                    } finally {
                        guard.finish(current.id)
                        if (invocation?.id == current.id) invocation = null
                    }
                    return AssistantStartResult(false, result.message, displayedGoal = goal, ended = true)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return AssistantStartResult(false, "요청의 현재 상태를 확인하지 못했어요. 다시 말씀해주세요.") }
        // Closing owned guidance is local cleanup and must work even after accessibility disconnects.
        if (!AssistStateRepository.state.value.serviceConnected)
            return AssistantStartResult(false, "APL 앱에서 화면 읽기 접근성 서비스를 켜주세요.")
        if (binding != null) goal = binding.displayedGoal
        fun ownsPreparation() = guard.isCurrent(current.id) &&
            (current.goals == null || input?.let(current.goals::owns) == true)
        fun currentRequest() = ownsPreparation() &&
            (current.goals == null || input?.let(current.goals::isCurrent) == true)
        fun finishPreparation() {
            if (ownsPreparation()) {
                guard.finish(current.id)
                if (invocation?.id == current.id) invocation = null
            }
        }
        val router = AppRouteResolver(context)
        val apps = router.installedApps()
        val origin = current.assistPackage ?: current.foregroundPackage
        val resolved = if (selectedPackage != null) com.example.aplassistmvp0.guide.GoalRoute(
            target = apps.firstOrNull { it.packageName == selectedPackage }, source = "selected")
        else try {
            val known = binding?.knownRoute(apps, origin)
            if (!currentRequest() || binding?.runCurrent(null) {} == false)
                return AssistantStartResult(false, "취소된 요청입니다.")
            known ?: com.example.aplassistmvp0.guide.GoalRouter(context).prepare(goal, apps, origin,
                AssistStateRepository.state.value.snapshot?.takeIf { it.packageName == origin &&
                    System.currentTimeMillis() - it.capturedAtMillis in 0..15_000 }, clarified)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            com.example.aplassistmvp0.guide.GoalRoute(question = (error as? IllegalStateException)?.message
                ?: "앱 후보를 확인하지 못했어요. 모든 앱에서 사용할 앱을 골라주세요.", source = "manual")
        }
        if (!currentRequest() || binding?.runCurrent(null) {} == false) return AssistantStartResult(false, "취소된 요청입니다.")
        val target = resolved.target ?: run {
            resolved.candidates.forEach { com.example.aplassistmvp0.guide.ExperienceStore.feature(it, goal, "inferred") }
            return AssistantStartResult(false, resolved.question, resolved, goal)
        }
        if (!currentRequest() || binding?.runCurrent(target) {} == false) return AssistantStartResult(false, "취소된 요청입니다.")
        fun closeForHandoff(): Boolean {
            var accepted = false
            val close = { accepted = currentRequest() && guard.handOff(current.id); if (accepted) closeSession() }
            val consumed = if (binding == null) { close(); true } else binding.runCurrent(target, close)
            return consumed && accepted
        }
        GuideTiming.markFor(GuideTiming.traceId(), "target_resolved")
        if (!available(context)) return AssistantStartResult(false, "잠금을 풀고 다시 불러주세요.")
        // 현재 앱을 안내할 때는 런처로 다시 열지 않아 읽던 상세 화면을 유지합니다.
        if (target.packageName != origin) {
            val launch = router.launchIntent(target)
                ?: return AssistantStartResult(false, "${target.label} 앱을 열 수 없습니다. 다른 앱을 말해주세요.")
            val opening = com.example.aplassistmvp0.guide.AppOpeningCoach.start(context, goal, target, binding)
                ?: return AssistantStartResult(false, "요청이 바뀌어 앱 열기 안내를 시작하지 않았어요.")
            try {
                if (!closeForHandoff()) { com.example.aplassistmvp0.guide.AppOpeningCoach.stopIfTicket(opening); return AssistantStartResult(false, "취소된 요청입니다.") }
                return AssistantStartResult(true, "${target.label} 앱을 직접 열도록 안내합니다.")
            } catch (error: Exception) {
                com.example.aplassistmvp0.guide.AppOpeningCoach.stopIfTicket(opening)
                throw error
            } finally { finishPreparation() }
        }
        val before = AssistStateRepository.state.value.snapshot?.snapshotId ?: -1L
        val dismissedAt = System.currentTimeMillis()
        try {
            if (!closeForHandoff()) return AssistantStartResult(false, "취소된 요청입니다.")
            Log.d("APLAssistant", "handoff id=${current.id} target=${target.packageName}")
            AssistAccessibilityService.requestRefresh()
            // 음성 창을 읽던 기록을 보내지 않습니다. 닫힌 뒤 새로 읽은 실제 대상 앱만 사용합니다.
            val restored = withTimeoutOrNull(5_000L) {
                AssistStateRepository.state.first { state ->
                    !state.serviceConnected || !available(context) ||
                        (state.foregroundPackage == target.packageName &&
                            state.snapshot?.let { it.packageName == target.packageName &&
                                it.snapshotId > before && it.capturedAtMillis >= dismissedAt } == true)
                }
            }
            if (!currentRequest()) return AssistantStartResult(false, "취소된 요청입니다.")
            Log.d("APLAssistant", "restored id=${current.id} package=${restored?.foregroundPackage} snapshot=${restored?.snapshot?.snapshotId}")
            if (restored == null || !restored.serviceConnected || !available(context)) {
                val message = "${target.label}의 현재 화면을 확인하지 못했습니다. 화면을 열고 다시 불러주세요."
                GuideController.reportAssistantStartFailure(goal, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                return AssistantStartResult(false, message)
            }
            val started = binding?.startGuide(target) ?: GuideController.start(goal, target.packageName, target.label)
            Log.d("APLAssistant", "guide id=${current.id} started=$started")
            if (!currentRequest()) {
                return AssistantStartResult(false, "취소된 요청입니다.")
            }
            // GuideEngine의 시작 전 snapshot ID 조건을 유지하며 안내용으로 한 번 더 수집합니다.
            if (started) AssistAccessibilityService.requestRefresh()
            return AssistantStartResult(started, if (started) "${target.label} 화면의 안내를 시작합니다."
                else "안내를 시작하지 못했습니다. APL에서 API 키 설정을 확인해주세요.", displayedGoal = goal)
        } catch (cancelled: CancellationException) {
            if (currentRequest()) GuideController.stop()
            throw cancelled
        } catch (_: Exception) {
            val message = "화면 안내를 시작하지 못했습니다. APL을 다시 불러주세요."
            if (currentRequest()) GuideController.reportAssistantStartFailure(goal, message)
            return AssistantStartResult(false, message)
        } finally {
            finishPreparation()
        }
    }

    private fun available(context: Context): Boolean =
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
