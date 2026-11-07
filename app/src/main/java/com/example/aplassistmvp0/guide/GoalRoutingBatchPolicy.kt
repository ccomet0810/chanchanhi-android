package com.example.aplassistmvp0.guide

/** Preserve every global app index while bounding the actual selected-provider request. */
internal object GoalRoutingBatchPolicy {
    data class Batch(val from: Int, val until: Int, val includeRoute: Boolean)
    fun partition(appCount: Int, encodedBytes: (Batch) -> Int): List<Batch> {
        require(appCount >= 0)
        if (appCount == 0) return listOf(Batch(0, 0, true)).also {
            check(encodedBytes(it.single()) <= 65_536)
        }
        val batches = mutableListOf<Batch>()
        var from = 0
        while (from < appCount) {
            val first = from == 0
            var low = from + 1
            var high = minOf(appCount, from + 100)
            var accepted = from
            while (low <= high) {
                val until = low + (high - low) / 2
                if (encodedBytes(Batch(from, until, first)) <= 65_536) {
                    accepted = until; low = until + 1
                } else high = until - 1
            }
            check(accepted > from) { "앱 정보를 정리하지 못했어요. 모든 앱에서 사용할 앱을 골라주세요." }
            batches += Batch(from, accepted, first)
            from = accepted
        }
        return batches
    }
}
