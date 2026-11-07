package com.example.aplassistmvp0.guide

import org.json.JSONArray
import org.json.JSONObject

internal data class GoalRoutingRequest(val input: JSONObject, val questions: JSONArray)

internal object GoalRoutingRequests {
    fun build(backend: GuideBackend, input: JSONObject, apps: JSONArray, questions: JSONArray): List<GoalRoutingRequest> {
        require(questions.length() == apps.length() + 1 && questions.getJSONObject(0).getString("name") == "route")
        fun request(batch: GoalRoutingBatchPolicy.Batch): GoalRoutingRequest {
            val catalog = JSONArray()
            val prompts = JSONArray()
            if (batch.includeRoute) prompts.put(questions.getJSONObject(0))
            for (i in batch.from until batch.until) {
                check(apps.getJSONObject(i).getString("id") == "app_$i" &&
                    questions.getJSONObject(i + 1).getString("name") == "app_$i")
                catalog.put(apps.getJSONObject(i)); prompts.put(questions.getJSONObject(i + 1))
            }
            return GoalRoutingRequest(JSONObject(input.toString()).put("installedApps", catalog), prompts)
        }
        return GoalRoutingBatchPolicy.partition(apps.length()) { batch ->
            val part = request(batch)
            GuideModelCodec.routingRequest(backend, part.input, part.questions).toString().toByteArray(Charsets.UTF_8).size
        }.map(::request)
    }
}
