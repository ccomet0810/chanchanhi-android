package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.state.ScreenSnapshot

/** A reference to an externally owned goal, not another goal store or a model prediction. */
internal data class GuideGoalRef(
    val owner: String,
    val epoch: Long,
    val root: Long,
    val revision: Long,
    val digest: String,
)

internal data class GuideInteractionRef(val goal: GuideGoalRef, val serial: Long)

internal enum class GuideClaimUse { ATTACH, REVALIDATE_ORIGIN, INSTRUCTION, COMPLETION, USER_COMPLETION, LEARNING, READ_ONLY_PROBE }
internal enum class GuideClaimCause { DECISION, LOCAL_GOAL_WITNESS, USER_SIGNAL }
internal enum class GuideJudgmentSource { LIVE_SCREEN, DERIVED_SCROLL_CATALOG }

/** Authority-owned original assessment receipt; the engine cannot refresh its proof fields. */
internal class GuideJudgmentOrigin(val goal: GuideGoalRef, val id: String, internal val seal: Any)

/** Issued only after the SAME canonical owner has committed whole-goal completion. */
internal class GuideCompletionReceipt(val goal: GuideGoalRef, val id: String, internal val seal: Any)

internal data class GuideClaim(
    val goal: GuideGoalRef,
    val use: GuideClaimUse,
    val cause: GuideClaimCause,
    val displayedGoal: String,
    val targetPackage: String,
    val rawDecision: GuideDecision? = null,
    val decision: GuideDecision? = null,
    val requested: ScreenSnapshot? = null,
    val live: ScreenSnapshot? = null,
    val interaction: GuideInteractionRef? = null,
    val origin: GuideJudgmentOrigin? = null,
)

/**
 * The implementation must check the SAME current goal/knowledge owners at consumption.
 * Invoke [effect] once, synchronously on the calling thread, only while its particular
 * claim is authorized. Return false without invoking it for unknown/revoked claims.
 * Instruction, completion, optional learning and probing are separate permissions.
 * A candidate match, a UI goal string or a layout plan is not an implementation.
 * Implementations must not perform network/model calls in this synchronous boundary.
 */
internal interface GuideClaimAuthority {
    /** Capture goal, knowledge and observation-source lifetime BEFORE any decision producer runs. */
    fun originate(goal: GuideGoalRef, requested: ScreenSnapshot,
                  source: GuideJudgmentSource = GuideJudgmentSource.LIVE_SCREEN): GuideJudgmentOrigin?
    fun consume(claim: GuideClaim, effect: () -> Unit): Boolean

    /**
     * Revalidate completion, commit it in the canonical owner, then invoke synchronously once.
     * A completion permission from [consume] alone must never implement this operation.
     * Publish the goal-change notification after the callback, including on callback failure.
     * A committed goal must stay completed if rendering or optional learning fails.
     */
    fun complete(claim: GuideClaim, effect: (GuideCompletionReceipt) -> Unit): Boolean = false

    /** Revalidate the canonical terminal receipt at publication, including after optional callbacks. */
    fun consumeCompletion(receipt: GuideCompletionReceipt, effect: () -> Unit): Boolean = false

    /** Separate optional permission, at most once for this still-current terminal receipt. */
    fun consumeLearning(receipt: GuideCompletionReceipt, effect: () -> Unit): Boolean = false

    /** Publish canonical goal/input/knowledge revocations even when the Android screen is unchanged. */
    fun changes(): kotlinx.coroutines.flow.Flow<Unit>
}
