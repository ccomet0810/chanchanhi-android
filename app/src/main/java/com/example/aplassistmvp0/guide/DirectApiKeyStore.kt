package com.example.aplassistmvp0.guide

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** APK·백업·로그에 키를 넣지 않고 앱 전용 저장소에 Android Keystore로 암호화한다. */
class DirectApiKeyStore private constructor(context: Context, filename: String, private val alias: String) {
    constructor(context: Context) : this(context, "openai-credentials.enc", "apl-openai-api-v1")
    private val file = AtomicFile(context.applicationContext.noBackupFilesDir.resolve(filename))

    internal class Credential(val apiKey: String, val revision: String) {
        override fun toString() = "OpenAiCredential(present=true)"
    }

    fun revision(): String? = synchronized(LOCK) { readEnvelope()?.getString("revision") }

    internal fun load(): Credential? = synchronized(LOCK) {
        val envelope = readEnvelope() ?: return@synchronized null
        try {
            val revision = envelope.getString("revision")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
            cipher.updateAAD(revision.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP))
            try { Credential(DirectDecisionPolicy.normalizeKey(plain.toString(Charsets.UTF_8)), revision) }
            finally { plain.fill(0) }
        } catch (_: Exception) {
            throw IllegalStateException("저장한 API 키를 읽지 못했습니다. API 키 설정에서 다시 저장해주세요.")
        }
    }

    fun save(value: String) = synchronized(LOCK) {
        val key = DirectDecisionPolicy.normalizeKey(value)
        val revision = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(create = true))
        cipher.updateAAD(revision.toByteArray(Charsets.UTF_8))
        val plain = key.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val json = JSONObject().put("version", 1).put("revision", revision)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) {
            file.failWrite(stream)
            throw IllegalStateException("API 키를 저장하지 못했습니다. 다시 시도해주세요.")
        }
    }

    fun clear() = synchronized(LOCK) {
        file.delete()
        check(!file.baseFile.exists()) { "API 키를 지우지 못했습니다." }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun readEnvelope(): JSONObject? {
        if (!file.baseFile.exists() && !java.io.File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= 4096)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8)).also {
                require(it.getInt("version") == 1)
                UUID.fromString(it.getString("revision"))
            }
        } catch (_: Exception) {
            throw IllegalStateException("API 키 저장 정보를 확인하지 못했습니다. 다시 저장해주세요.")
        }
    }

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "암호화 키를 확인하지 못했습니다." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private val LOCK = Any()
        /** 테스트는 제품 키와 분리된 파일·Keystore 별칭을 사용한다. */
        internal fun isolatedTestStore(context: Context) =
            DirectApiKeyStore(context, "openai-credentials-qa.enc", "apl-openai-api-qa")
    }
}
