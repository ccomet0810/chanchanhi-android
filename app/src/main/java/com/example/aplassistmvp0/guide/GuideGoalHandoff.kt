package com.example.aplassistmvp0.guide

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException

/** Owned by the same canonical owner used by the destination engine's claim authority. */
internal interface GuideGoalHandoff {
    val ownerId: String
    val inputId: String
    val displayedGoal: String
    /** Revalidate the owner and selected provider; invoke synchronously once while current. */
    fun consume(target: TargetApp?, effect: () -> Unit): Boolean
    /** Must use the bound engine entry; never downgrade a failed binding to a string request. */
    suspend fun startGuide(target: TargetApp): Boolean
    /** Optional route from reviewed capability knowledge; null means no known local route. */
    suspend fun knownRoute(installedApps: List<TargetApp>, origin: String?): GoalRoute? = null
    fun changes(): Flow<Unit>
}

/** Input provenance; the canonical interpreter decides whether it replaces or amends a goal. */
internal enum class AssistantGoalInputKind { UTTERANCE, CLARIFICATION }
internal data class AssistantGoalInputTicket(val ownerId: String, val id: String, val rawInput: String)
internal sealed interface AssistantGoalResolution {
    data class Ready(val binding: GuideGoalHandoff) : AssistantGoalResolution
    data class Question(val message: String) : AssistantGoalResolution
    /** Local guidance closed by the current owned interpretation; not successful completion. */
    data class Stopped(val message: String) : AssistantGoalResolution
}

internal interface AssistantGoalInputPort {
    val ownerId: String
    /** Synchronously register pending input BEFORE routing/interpretation can suspend. */
    fun register(rawInput: String, kind: AssistantGoalInputKind,
                 previousInput: AssistantGoalInputTicket?, previousGoal: GuideGoalHandoff?): AssistantGoalInputTicket
    suspend fun resolve(ticket: AssistantGoalInputTicket): AssistantGoalResolution
    fun isCurrent(ticket: AssistantGoalInputTicket): Boolean
    /** Invalidate only the matching preparation; cancellation is not successful completion. */
    fun abandon(ticket: AssistantGoalInputTicket)
}

/** Holds receipts/projections only. The port remains the sole authority for current goals. */
internal class AssistantGoalInputSession(private val owner: AssistantGoalInputPort) {
    class Request internal constructor(val ticket: AssistantGoalInputTicket?, val reused: GuideGoalHandoff?)
    private var current: Request? = null
    private var binding: GuideGoalHandoff? = null

    fun prepare(rawInput: String, selectedPackage: String?, clarified: Boolean): Request {
        val text = rawInput.trim().take(500)
        require(text.isNotBlank())
        val next = if (selectedPackage != null) {
            val prior = binding ?: error("NO_BOUND_APP_CHOICE")
            check(prior.ownerId == owner.ownerId && prior.displayedGoal == text && prior.runCurrent(null) {}) { "STALE_APP_CHOICE" }
            Request(current?.ticket, prior)
        } else {
            val ticket = owner.register(text, if (clarified) AssistantGoalInputKind.CLARIFICATION else AssistantGoalInputKind.UTTERANCE,
                current?.ticket, binding)
            check(ticket.ownerId == owner.ownerId && ticket.rawInput == text && ticket.id.isNotBlank()) { "INPUT_RECEIPT_MISMATCH" }
            binding = null
            Request(ticket, null)
        }
        current = next
        return next
    }

    fun isCurrent(request: Request): Boolean = current === request && request.ticket?.ownerId == owner.ownerId &&
        (request.reused?.runCurrent(null) {} ?: owner.isCurrent(request.ticket))
    /** UI job cleanup ownership only; never an authorization to act on a goal. */
    fun owns(request: Request): Boolean = current === request

    suspend fun resolve(request: Request): AssistantGoalResolution {
        check(isCurrent(request)) { "STALE_GOAL_INPUT" }
        val result = request.reused?.let { AssistantGoalResolution.Ready(it) }
            ?: owner.resolve(requireNotNull(request.ticket))
        check(isCurrent(request)) { "STALE_GOAL_RESOLUTION" }
        if (result is AssistantGoalResolution.Ready) {
            val ticket = requireNotNull(request.ticket)
            check(result.binding.ownerId == owner.ownerId && result.binding.ownerId == ticket.ownerId &&
                result.binding.inputId == ticket.id && result.binding.runCurrent(null) {}) { "FOREIGN_GOAL_RESOLUTION" }
            binding = result.binding
        }
        return result
    }

    fun cancel() {
        val request = current
        current = null; binding = null
        request?.ticket?.let(owner::abandon)
    }
}

internal fun GuideGoalHandoff.runCurrent(target: TargetApp?, effect: () -> Unit): Boolean =
    consumeGoalEffect(provider = { consume(target, it) }, effect = effect)

/** Callback lifetime only. The supplied owner still has to authorize the particular effect. */
internal fun consumeGoalEffect(provider: (() -> Unit) -> Boolean, isCurrent: () -> Boolean = { true },
                               effect: () -> Unit): Boolean {
    var open = true
    var invoked = false
    val thread = Thread.currentThread()
    return try {
        val accepted = provider callback@{
            // A late callback is outside this try block. Ignore it instead of throwing on a UI thread.
            if (Thread.currentThread() !== thread || !open || invoked || !isCurrent()) return@callback
            invoked = true
            effect()
        }
        accepted && invoked
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    finally { open = false }
}
