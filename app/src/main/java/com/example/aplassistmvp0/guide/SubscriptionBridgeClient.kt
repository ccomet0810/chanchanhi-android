package com.example.aplassistmvp0.guide

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 폰에는 OAuth 토큰을 복사하지 않는다. USB loopback 또는 명시적으로 설정한 HTTPS 연결만 사용한다. */
internal class SubscriptionBridgeClient(private val store: BridgeConnectionStore) {
    suspend fun status(config: BridgeConnectionConfig, model: String, verifyStored: Boolean = true, subscription: Boolean = true): BridgeStatus {
        val body = request(config, if (subscription) "/api/model-status" else "/api/decisions-status", null, verifyStored)
        val models = body.optJSONArray("models")
        val available = models != null && (0 until models.length()).any { models.getString(it) == model }
        return BridgeStatus(body.optBoolean("ready") && available,
            if (!available && body.optBoolean("ready")) "서버에서 선택한 모델을 사용할 수 없습니다. 서버 연결 상태를 확인해주세요."
            else body.optString("message", "AI 서버 연결을 확인해주세요."))
    }

    suspend fun execute(config: BridgeConnectionConfig, payload: ByteArray, subscription: Boolean = true): JSONObject =
        request(config, if (subscription) "/api/model-response" else "/api/decisions-response", payload)

    private suspend fun request(config: BridgeConnectionConfig, path: String, payload: ByteArray?, verifyStored: Boolean = true): JSONObject {
        require(config.pairingToken.isNotBlank()) { "AI 서버 연결 설정에서 개인 연결 키를 등록해주세요." }
        if (verifyStored) BridgeConnectionPolicy.requireUnchanged(config, store.load())
        val connection = URL(config.endpoint + path).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) configureBridgeTls(connection, config)
        else require(config.mode == BridgeConnectionMode.USB && config.endpoint == BridgeConnectionPolicy.USB_ENDPOINT)
        connection.connectTimeout = 5_000
        connection.readTimeout = 35_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = if (payload == null) "GET" else "POST"
        connection.setRequestProperty("Authorization", "Bearer ${config.pairingToken}")
        connection.setRequestProperty("Accept", "application/json")
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(payload.size)
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                if (verifyStored) BridgeConnectionPolicy.requireUnchanged(config, store.load())
                payload?.let { connection.outputStream.use { out -> out.write(it) } }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val kind = runCatching { connection.errorStream?.use { input ->
                        val buffer=ByteArray(4097);var size=0
                        while(size<buffer.size) { val count=input.read(buffer,size,buffer.size-size);if(count<0)break;size+=count }
                        if(size>4096) null else JSONObject(buffer.copyOf(size).toString(Charsets.UTF_8)).optString("error")
                            .takeIf { Regex("[a-z][a-z0-9_]{0,79}").matches(it) }
                    } }.getOrNull()
                    throw BridgeRequestFailure(code,kind,BridgeFailurePolicy.message(code,kind))
                }
                check(connection.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/json") {
                    "구독 연결의 응답 형식을 확인하지 못했습니다."
                }
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(262_145)
                    var size = 0
                    while (size < buffer.size && continuation.isActive) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    buffer.copyOf(size)
                }
                check(bytes.size <= 262_144) { "구독 응답이 너무 큽니다."
                }
                val result = JSONObject(bytes.toString(Charsets.UTF_8))
                if (verifyStored) BridgeConnectionPolicy.requireUnchanged(config, store.load())
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(if (error is IllegalStateException || error is IllegalArgumentException) error
                    else IllegalStateException(if (config.mode == BridgeConnectionMode.HTTPS) "AI 서버에 연결하지 못했습니다. 인터넷과 서버 연결 설정을 확인해주세요."
                        else "PC 연결을 확인해주세요. USB 연결과 PC 프로그램 실행이 필요합니다."))
            } finally { connection.disconnect() }
        }
    }
}
