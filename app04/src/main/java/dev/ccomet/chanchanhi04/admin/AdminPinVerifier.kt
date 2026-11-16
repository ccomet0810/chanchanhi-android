package dev.ccomet.chanchanhi04.admin

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted verification only. The administrator PIN is never persisted as plaintext. */
internal object AdminPinVerifier {
    private const val ITERATIONS = 210000
    private const val SALT = "8b59bdd23adf0b800d76f6c1150fc705"
    private const val EXPECTED = "2dfc493fb2241c1915b56da2ec62df20efb23afc67f587a9b73545c4c336f0eb"
    private fun hex(value:String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun matches(pin:CharArray):Boolean {
        if(pin.size!=6 || pin.any { it !in '0'..'9' })return false
        val spec=PBEKeySpec(pin,hex(SALT),ITERATIONS,256)
        return try {
            val actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { MessageDigest.isEqual(actual,hex(EXPECTED)) } finally { actual.fill(0) }
        } finally {spec.clearPassword()}
    }
}
