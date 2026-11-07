package com.example.aplassistmvp0.guide

import android.os.SystemClock
import android.util.Log

/** 요청 내용과 키 없이 단계별 경과 시간만 기록한다. 측정 시작점은 요청 제출 시점이다. */
internal object GuideTiming {
    private var id = System.currentTimeMillis()
    private var started = 0L
    private val events = linkedMapOf<String, Long>()
    private var apiStarted: Long? = null
    private var apiDuration: Long? = null
    @Synchronized fun begin(source: String) {
        id++
        started = SystemClock.elapsedRealtime()
        events.clear()
        apiStarted = null
        apiDuration = null
        Log.d("APLTiming", "trace=$id source=$source")
        markFor(id, "request_received")
    }
    @Synchronized fun traceId() = id
    @Synchronized fun elapsedMillis(): Long? = if (started == 0L) null else SystemClock.elapsedRealtime() - started
    @Synchronized fun lastApiMillis(): Long? = apiDuration
    @Synchronized fun markFor(trace: Long, stage: String) {
        if (started == 0L || trace != id) return
        if (stage == "api_start") { apiStarted = SystemClock.elapsedRealtime(); apiDuration = null }
        if (stage == "api_response") apiDuration = apiStarted?.let { SystemClock.elapsedRealtime() - it }
        if (stage in events) return
        val elapsed = SystemClock.elapsedRealtime() - started
        events[stage] = elapsed
        Log.d("APLTiming", "trace=$id stage=$stage elapsed_ms=$elapsed")
    }
    @Synchronized fun snapshot(): Map<String, Long> = events.toMap()
}
