package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.state.ScreenSnapshot
import com.example.aplassistmvp0.v3.*

/** Verifies a model-declared input before a named, currently disabled navigation button. */
internal object V3ScopedPreparation {
    const val MISSING_REQUIRED_INPUTS = "missing_scoped_required_inputs"
    data class Review(val scope: GuideScopedTap, val inputVerified: Boolean, val problem: String? = null)

    fun review(goal: TaskGoal, plan: TaskPlan, frame: ScreenSnapshot): Review? {
        val inputId = plan.targetNodeId ?: return null
        if (plan.kind != NextKind.GUIDE || plan.operation != Operation.INPUT || plan.effect != ActionEffect.PREPARE ||
            !plan.groundingComplete || goal.requiresNewEvent || plan.goalKind !in setOf(GoalKind.STATE, GoalKind.INSPECT)) return null
        val steps = plan.tasks.associateBy { it.key }
        val before = plan.relations.filter { it.kind == RelationKind.BEFORE }
        fun following(nodeId: Int) = before.flatMap { relation ->
            val index = relation.members.indexOfFirst { key -> nodeId in steps[key]?.nodeIds.orEmpty() }
            if (index == -1) emptyList() else relation.members.drop(index + 1).flatMap { steps[it]?.nodeIds.orEmpty() }
        }.toSet()
        val followingIds = following(inputId)
        val scope = followingIds.mapNotNull { GuideScopedTap.find(frame, it, goal.request) }
            .filterNot { it.node.enabled }.distinctBy { it.node.id }.singleOrNull() ?: return null

        val input = frame.allNodes.singleOrNull { it.id == inputId } ?: return Review(scope, false)
        if (scope.node.id !in followingIds || !scope.confirms(scope.node.id, plan.scopeNodeIds) || !scope.supportsPreparationInput(input))
            return Review(scope, false)
        fun reports(node: AccessibilityNodeModel, present: Boolean): Boolean {
            val truth = if (present) Truth.SATISFIED else Truth.UNSATISFIED
            val expected = if (present) V3ValueEvidence.Result.MATCH else V3ValueEvidence.Result.MISMATCH
            return plan.conditions.any { condition ->
                condition.required && condition.phase == ConditionPhase.PREPARATION && condition.truth == truth &&
                    condition.evidence == EvidenceKind.OBSERVED && node.id in condition.evidenceNodeIds &&
                    condition.expectations.any { expectation ->
                        expectation.property == ValueProperty.INPUT_PRESENT && expectation.expectedBoolean == true &&
                            V3ValueEvidence.subject(expectation, frame) === node &&
                            V3ValueEvidence.evaluate(expectation, node) == expected
                    }
            }
        }
        if (scope.announcesAllInputs) {
            val required = scope.explicitRequiredEditors() ?: return Review(scope, false)
            if (input !in required || required.any { it.hasInput == null }) return Review(scope, false)
            if (required.any { scope.node.id !in following(it.id) || !reports(it, it.hasInput == true) })
                return Review(scope, false, MISSING_REQUIRED_INPUTS)
        }
        return Review(scope, reports(input, present = false))
    }
}
