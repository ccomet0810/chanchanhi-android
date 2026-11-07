package com.example.aplassistmvp0.guide

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/** 개발용 기기 내 비용 가드. 불명확/취소 요청의 예약 비용은 되돌리지 않는다. */
internal class DirectUsageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("direct-decisions-usage", Context.MODE_PRIVATE)
    data class Ledger(val day: String, val spentUsd: Double, val requests: Int)
    fun current(): Ledger {
        val day = Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString()
        if (prefs.getString("day", "") != day) return Ledger(day, 0.0, 0)
        val spent = prefs.getString("spentUsd", "0")!!.toDoubleOrNull()
        check(spent != null && spent.isFinite() && spent >= 0) { "오늘의 AI 사용량 기록을 확인해주세요." }
        val requests = prefs.getInt("requests", 0)
        check(requests >= 0) { "오늘의 AI 사용량 기록을 확인해주세요." }
        return Ledger(day, spent, requests)
    }
    fun save(value: Ledger) {
        check(prefs.edit().putString("day", value.day).putString("spentUsd", value.spentUsd.toString())
            .putInt("requests", value.requests).commit()) { "AI 사용량을 저장하지 못해 추가 요청을 멈췄습니다." }
    }
}
