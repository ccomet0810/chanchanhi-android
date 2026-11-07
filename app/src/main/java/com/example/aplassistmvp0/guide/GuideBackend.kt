package com.example.aplassistmvp0.guide

/** 가격은 Standard USD / 1M tokens. 캐시 할인은 별도로 계산한다. */
enum class GuideBackend(val label: String, val model: String, val endpoint: String,
    val inputRate: Double, val cachedRate: Double, val outputRate: Double) {
    DECISIONS_LUNA("Decisions · Luna", "gpt-6-luna", "https://api.openai.com/v1/decisions", .10, .10, 0.0),
    RESPONSES_LUNA("일반 응답 · Luna", "gpt-6-luna", "https://api.openai.com/v1/responses", .10, .01, .50),
    RESPONSES_SOL("일반 응답 · 6.1 Sol", "gpt-6.1-sol", "https://api.openai.com/v1/responses", 2.0, .10, 10.0),
    SUBSCRIPTION_SOL("ChatGPT 구독 · 6.1 Sol", "gpt-6.1-sol", "https://api.openai.com/v1/responses", 0.0, 0.0, 0.0),
    SUBSCRIPTION_TERRA("ChatGPT 구독 · 6.1 Terra", "gpt-6.1-terra", "https://api.openai.com/v1/responses", 0.0, 0.0, 0.0),
    SERVER_DECISIONS_LUNA("Decisions · Luna · 서버", "gpt-6-luna", "https://api.openai.com/v1/decisions", .10, .10, 0.0);

    val subscription get() = this == SUBSCRIPTION_SOL || this == SUBSCRIPTION_TERRA
    val remote get() = subscription || this == SERVER_DECISIONS_LUNA
    val responses get() = this != DECISIONS_LUNA && this != SERVER_DECISIONS_LUNA
    val maxOutputTokens get() = if (this == RESPONSES_SOL) 1024 else 512
    fun validModel(value: String) = value == model || value.startsWith("$model-")
    fun cost(input: Long, output: Long = 0, cached: Long = 0): Double {
        require(input in 0..1_000_000 && output in 0..1_000_000 && cached in 0..input)
        return ((input - cached) * inputRate + cached * cachedRate + output * outputRate) / 1_000_000
    }
    fun reserve(bytes: Int): Double {
        require(bytes in 1..262_144)
        return cost((bytes + if (responses) 2048 else 16_384).toLong(), if (responses) maxOutputTokens.toLong() else 0)
    }
    companion object {
        val apiBackends get() = entries.filterNot { it.remote }
        val defaultSubscription get() = SUBSCRIPTION_TERRA
        val displayOrder get() = listOf(defaultSubscription, SUBSCRIPTION_SOL, SERVER_DECISIONS_LUNA) + apiBackends
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: DECISIONS_LUNA
    }
}
