package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.v3.*
import org.json.JSONArray
import org.json.JSONObject

internal object V3PlanCodec {
    fun response(response:JSONObject,backend:GuideBackend):TaskPlan {
        check(response.optString("status")=="completed" && backend.validModel(response.getString("model")))
        val parts=mutableListOf<JSONObject>()
        val output=response.getJSONArray("output")
        for(i in 0 until output.length()){
            val row=output.getJSONObject(i)
            if(row.optString("type")!="message")continue
            val content=row.getJSONArray("content");for(j in 0 until content.length())parts+=content.getJSONObject(j)
        }
        check(parts.none{it.optString("type")=="refusal"})
        val text=parts.single{it.optString("type")=="output_text"}.getString("text")
        check(text.length<=16_384)
        return parse(JSONObject(text))
    }
    private fun JSONArray.ints()=(0 until length()).map{getInt(it)}
    private fun JSONArray.strings()=(0 until length()).map{getString(it)}
    private fun parseBasis(o:JSONObject)=ConditionBasis(ConditionOrigin.valueOf(o.getString("kind")),
        if(o.isNull("key"))null else o.getString("key"),o.getString("quote"),o.getString("rationale"),o.getJSONArray("evidenceNodeIds").ints())
    fun basis(b:ConditionBasis)=JSONObject().put("kind",b.kind.name).put("key",b.key?:JSONObject.NULL)
        .put("quote",b.quote).put("rationale",b.rationale).put("evidenceNodeIds",JSONArray(b.evidenceNodeIds))
    fun expectations(values:List<ValueExpectation>)=JSONArray(values.map{v->JSONObject().put("property",v.property.name)
        .put("subject",JSONObject().put("role",v.subject.role).put("resource",v.subject.resource).put("label",v.subject.label))
        .put("expectedNumber",v.expectedNumber?:JSONObject.NULL).put("expectedBoolean",v.expectedBoolean?:JSONObject.NULL)})
    fun parseExpectations(values:JSONArray):List<ValueExpectation> = (0 until values.length()).map{i->val o=values.getJSONObject(i);val s=o.getJSONObject("subject")
        val number=if(o.isNull("expectedNumber"))null else (o.get("expectedNumber").also{check(it is Number)} as Number).toDouble()
        val flag=if(o.isNull("expectedBoolean"))null else o.get("expectedBoolean").also{check(it is Boolean)} as Boolean
        ValueExpectation(ValueProperty.valueOf(o.getString("property")),ValueSubject(s.getString("role"),s.getString("resource"),s.getString("label")),number,flag).canonical()
    }
    fun parse(o:JSONObject):TaskPlan {
        fun objects(name:String,convert:(JSONObject)->Unit){val a=o.getJSONArray(name);for(i in 0 until a.length())convert(a.getJSONObject(i))}
        val conditions=mutableListOf<GoalCondition>();objects("conditions"){r->conditions+=GoalCondition(r.getString("key"),r.getString("description"),r.getBoolean("required"),Truth.valueOf(r.getString("truth")),EvidenceKind.valueOf(r.getString("evidence")),r.getJSONArray("evidenceNodeIds").ints(),
            if(r.isNull("requirementId"))null else r.getLong("requirementId"),if(r.isNull("requirementVersion"))null else r.getInt("requirementVersion"),r.optJSONObject("basis")?.let(::parseBasis),ConditionPhase.valueOf(r.getString("phase")),parseExpectations(r.getJSONArray("expectations")))}
        val tasks=mutableListOf<TaskStep>();objects("tasks"){r->tasks+=TaskStep(r.getString("key"),r.getString("description"),r.getJSONArray("nodeIds").ints(),r.getJSONArray("dependsOn").strings())}
        val relations=mutableListOf<TaskRelation>();objects("relations"){r->relations+=TaskRelation(RelationKind.valueOf(r.getString("kind")),r.getJSONArray("members").strings())}
        val q=o.optJSONObject("question")?.let{PlanQuestion(it.getString("key"),it.getString("message"),it.getBoolean("neededNow"),it.getBoolean("changesGuidance"),it.getBoolean("canEnterInApp"),it.getJSONArray("conditionKeys").strings())}
        val edits=mutableListOf<ConditionEdit>();objects("conditionEdits"){r->edits+=ConditionEdit(r.getLong("requirementId"),r.getInt("expectedVersion"),ConditionEditKind.valueOf(r.getString("kind")),r.getString("reason"),parseBasis(r.getJSONObject("basis")))}
        val gates=mutableListOf<ActionGate>();objects("actionGates"){r->gates+=ActionGate(r.getInt("targetNodeId"),r.getJSONArray("conditionKeys").strings())}
        return TaskPlan(NextKind.valueOf(o.getString("kind")),o.getString("summary"),GoalKind.valueOf(o.getString("goalKind")),conditions,tasks,relations,
            Operation.valueOf(o.getString("operation")),if(o.isNull("targetNodeId"))null else o.getInt("targetNodeId"),o.getString("message"),q,
            WaitKind.valueOf(o.getString("waitKind")),o.getJSONArray("relevantNodeIds").ints(),o.getJSONArray("newEventNodeIds").ints(),
            ActionEffect.valueOf(o.getString("effect")),CommitOutcome.valueOf(o.getString("commitOutcome")),o.getJSONArray("outcomeNodeIds").ints(),o.getJSONArray("scopeNodeIds").ints(),
            if(o.isNull("outcomeReceiptId"))null else o.getLong("outcomeReceiptId"),edits,gates)
    }
    fun encode(plan:TaskPlan):JSONObject=JSONObject().put("kind",plan.kind.name).put("summary",plan.summary).put("goalKind",plan.goalKind.name)
        .put("conditions",JSONArray(plan.conditions.map{JSONObject().put("key",it.key).put("description",it.description).put("required",it.required).put("truth",it.truth.name).put("evidence",it.evidence.name).put("evidenceNodeIds",JSONArray(it.evidenceNodeIds))
            .put("requirementId",it.requirementId?:JSONObject.NULL).put("requirementVersion",it.requirementVersion?:JSONObject.NULL).put("basis",it.basis?.let(::basis)?:JSONObject.NULL).put("phase",it.phase.name).put("expectations",expectations(it.expectations))}))
        .put("tasks",JSONArray(plan.tasks.map{JSONObject().put("key",it.key).put("description",it.description).put("nodeIds",JSONArray(it.nodeIds)).put("dependsOn",JSONArray(it.dependsOn))}))
        .put("relations",JSONArray(plan.relations.map{JSONObject().put("kind",it.kind.name).put("members",JSONArray(it.members))}))
        .put("operation",plan.operation.name).put("targetNodeId",plan.targetNodeId?:JSONObject.NULL).put("message",plan.message)
        .put("question",plan.question?.let{JSONObject().put("key",it.key).put("message",it.message).put("neededNow",it.neededNow).put("changesGuidance",it.changesGuidance).put("canEnterInApp",it.canEnterInApp).put("conditionKeys",JSONArray(it.conditionKeys))}?:JSONObject.NULL)
        .put("waitKind",plan.waitKind.name).put("relevantNodeIds",JSONArray(plan.relevantNodeIds)).put("newEventNodeIds",JSONArray(plan.newEventNodeIds))
        .put("effect",plan.effect.name).put("commitOutcome",plan.commitOutcome.name).put("outcomeNodeIds",JSONArray(plan.outcomeNodeIds))
        .put("scopeNodeIds",JSONArray(plan.scopeNodeIds))
        .put("outcomeReceiptId",plan.outcomeReceiptId?:JSONObject.NULL)
        .put("conditionEdits",JSONArray(plan.conditionEdits.map{JSONObject().put("requirementId",it.requirementId).put("expectedVersion",it.expectedVersion)
            .put("kind",it.kind.name).put("reason",it.reason).put("basis",basis(it.basis))}))
        .put("actionGates",JSONArray(plan.actionGates.map{JSONObject().put("targetNodeId",it.targetNodeId).put("conditionKeys",JSONArray(it.conditionKeys))}))
}
