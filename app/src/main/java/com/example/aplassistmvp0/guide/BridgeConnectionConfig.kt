package com.example.aplassistmvp0.guide

import android.content.Context
import android.util.AtomicFile
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/** 서버 연결 방식입니다. HTTP는 기존 USB의 고정 loopback 주소에서만 사용합니다. */
enum class BridgeConnectionMode(val value: String) { USB("usb"), HTTPS("https") }

/** 요청마다 고정해서 사용하는 불변 설정입니다. 연결 키를 toString이나 로그로 노출하지 않습니다. */
data class BridgeConnectionConfig(
    val mode: BridgeConnectionMode,
    val endpoint: String,
    val pairingToken: String,
    val certificateSha256: String? = null,
) {
    override fun toString() = "BridgeConnectionConfig(mode=${mode.value}, endpoint=$endpoint, paired=${pairingToken.isNotEmpty()}, pinned=${certificateSha256 != null})"
}

/** Android 없이 URL·키·인증서 핀과 서버 변경 규칙을 검증할 수 있는 정책입니다. */
object BridgeConnectionPolicy {
    const val USB_ENDPOINT = "http://127.0.0.1:18765"
    const val PUBLIC_ENDPOINT = "https://ccomet.dev"

    fun normalize(mode: BridgeConnectionMode, endpoint: String, token: String, pin: String?,
                  allowMissingToken: Boolean = false): BridgeConnectionConfig {
        val address = if (mode == BridgeConnectionMode.USB) {
            require(endpoint.trim().trimEnd('/') == USB_ENDPOINT) { "USB 연결은 기본 내부 주소만 사용할 수 있습니다." }
            USB_ENDPOINT
        } else normalizeHttpsEndpoint(endpoint)
        val pairing = token.trim()
        require((allowMissingToken && pairing.isEmpty()) || pairing.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            "개인 연결 키 43자를 확인해주세요."
        }
        val certificate = normalizePin(pin)
        require(mode != BridgeConnectionMode.USB || certificate == null) { "USB 모드에는 인증서 핀을 사용하지 않습니다." }
        return BridgeConnectionConfig(mode, address, pairing, certificate)
    }

    /** 사용자 정보·쿼리·fragment·하위 경로가 없는 HTTPS 서버의 원점만 허용합니다. */
    fun normalizeHttpsEndpoint(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("HTTPS 서버 주소를 확인해주세요.")
        require(uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.isOpaque &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535) && uri.rawAuthority?.endsWith(':') != true) {
            "https://서버주소 또는 https://서버주소:포트 형식만 사용할 수 있습니다."
        }
        val port = if (uri.port == 443) -1 else uri.port
        return URI("https", null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
    }

    fun normalizePin(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val pin = value.trim().replace(":", "").lowercase(Locale.ROOT)
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "인증서 SHA-256 핀은 64자리 16진수여야 합니다." }
        return pin
    }

    /** 주소/모드를 바꿀 때 이전 서버의 키가 새 서버로 자동 전달되지 않도록 새 키를 요구합니다. */
    fun fromInput(current: BridgeConnectionConfig, mode: BridgeConnectionMode, endpoint: String,
                  enteredToken: String, pin: String?): BridgeConnectionConfig {
        val address = normalize(mode, endpoint, "", pin, allowMissingToken = true)
        val token = enteredToken.trim().ifEmpty {
            require(address.mode == current.mode && address.endpoint == current.endpoint) {
                "새 서버의 개인 연결 키를 입력해주세요."
            }
            current.pairingToken
        }
        return normalize(mode, address.endpoint, token, address.certificateSha256)
    }

    /** 서버나 키/인증서가 바뀐 요청의 결과를 새 연결에 적용하지 않습니다. */
    fun requireUnchanged(expected: BridgeConnectionConfig, current: BridgeConnectionConfig) {
        require(expected == current) { "AI 서버 연결이 바뀌었습니다. 안내를 다시 시작해주세요." }
    }
}

/** 앱 전용 파일만 사용하며 읽기/쓰기 잠금과 AtomicFile로 부분 저장을 방지합니다. */
class BridgeConnectionStore(context: Context) {
    private val files = context.applicationContext.filesDir
    private val configFile = AtomicFile(files.resolve("bridge-connection.json"))
    private val legacyPairingFile = files.resolve("bridge-pairing.txt")

    fun load(): BridgeConnectionConfig = synchronized(FILE_LOCK) {
        if (configFile.baseFile.exists() || files.resolve("bridge-connection.json.bak").exists()) {
            try {
                val json = configFile.openRead().use { JSONObject(readLimited(it)) }
                require(json.optInt("version", -1) == 1)
                val mode = BridgeConnectionMode.entries.singleOrNull { it.value == json.optString("mode") }
                    ?: throw IllegalArgumentException()
                BridgeConnectionPolicy.normalize(mode, json.optString("endpoint"),
                    json.optString("pairingToken"), if (json.isNull("certificateSha256")) null else json.optString("certificateSha256"),
                    allowMissingToken = true)
            } catch (_: Exception) {
                throw IllegalStateException("AI 서버 연결 설정을 다시 저장해주세요.")
            }
        } else {
            // 기존 USB 초기 설치 방식도 그대로 읽습니다. 읽은 키를 표시하거나 로그에 남기지 않습니다.
            val token = if (legacyPairingFile.exists()) runCatching {
                legacyPairingFile.inputStream().use(::readLimited).trim()
            }.getOrDefault("") else ""
            if (token.isNotEmpty()) BridgeConnectionPolicy.normalize(BridgeConnectionMode.USB, BridgeConnectionPolicy.USB_ENDPOINT,
                token, null, allowMissingToken = true)
            else BridgeConnectionPolicy.normalize(BridgeConnectionMode.HTTPS, BridgeConnectionPolicy.PUBLIC_ENDPOINT,
                "", null, allowMissingToken = true)
        }
    }

    fun save(config: BridgeConnectionConfig) = synchronized(FILE_LOCK) {
        val normalized = BridgeConnectionPolicy.normalize(config.mode, config.endpoint,
            config.pairingToken, config.certificateSha256)
        val json = JSONObject().put("version", 1).put("mode", normalized.mode.value)
            .put("endpoint", normalized.endpoint).put("pairingToken", normalized.pairingToken)
            .put("certificateSha256", normalized.certificateSha256 ?: "")
        val stream = configFile.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            configFile.finishWrite(stream)
        } catch (_: Exception) {
            configFile.failWrite(stream)
            throw IllegalStateException("연결 설정을 저장할 수 없습니다. 다시 시도해주세요.")
        }
    }

    /** 연결 해제는 HTTPS 설정과 기존 USB 키 모두 지워 자동 복구하지 않습니다. */
    fun clear() = synchronized(FILE_LOCK) {
        configFile.delete()
        check(!configFile.baseFile.exists()) { "연결 설정을 지울 수 없습니다." }
        check(!legacyPairingFile.exists() || legacyPairingFile.delete()) { "연결 키를 지울 수 없습니다." }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val buffer = ByteArray(4097)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        require(size <= 4096)
        return buffer.copyOf(size).toString(Charsets.UTF_8)
    }

    private companion object { val FILE_LOCK = Any() }
}

/**
 * 공인 인증서는 기본 TLS 신뢰 검증을 사용합니다. 핀이 있으면 인증서 전체 DER의 SHA-256도 검증합니다.
 * 자체 서명 인증서는 정확한 핀·유효기간·자체 서명을 확인한 한 장만 요청별 신뢰 앵커로 사용합니다.
 */
internal class BridgePinnedTrustManager(
    private val systemTrust: X509TrustManager,
    private val pin: String,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("서버 인증서가 없습니다.")
        val certificate = chain[0]
        certificate.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), pin.toByteArray(Charsets.US_ASCII))) {
            throw CertificateException("서버 인증서 핀이 일치하지 않습니다.")
        }
        try {
            systemTrust.checkServerTrusted(chain, authType)
        } catch (untrusted: CertificateException) {
            if (chain.size != 1 || certificate.subjectX500Principal != certificate.issuerX500Principal) throw untrusted
            certificate.verify(certificate.publicKey)
            // 모든 인증서를 허용하지 않습니다. 이미 핀으로 확인한 인증서 한 장만 임시로 신뢰합니다.
            val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("paired-server", certificate)
            }
            trustManager(anchors).checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        systemTrust.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
}

/** 요청별 SSL 설정만 바꾸고 호스트 이름 검증·전역 TLS 설정은 그대로 유지합니다. */
internal fun configureBridgeTls(connection: HttpsURLConnection, config: BridgeConnectionConfig) {
    connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    config.certificateSha256?.let { pin ->
        val trust = BridgePinnedTrustManager(trustManager(null), pin)
        connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), null)
        }.socketFactory
    }
}

private fun trustManager(anchors: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
