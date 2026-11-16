package dev.ccomet.chanchanhi04.admin

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Separate test credential; the operator PIN and its verifier are never changed or shared. */
internal object ReviewPinVerifier {
    private const val SALT="7be55eb5ea08a466d84f1fb8111cf1e5"
    private const val EXPECTED="c5630910e14038548ec02607e2b18af64bef32409d22a32eaa6b09df6e53d9c0"
    fun matches(pin:CharArray):Boolean {
        if(pin.size!=6||pin.any{it !in '0'..'9'})return false
        val spec=PBEKeySpec(pin,SALT.chunked(2).map{it.toInt(16).toByte()}.toByteArray(),210000,256)
        return try {
            val actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {MessageDigest.isEqual(actual,EXPECTED.chunked(2).map{it.toInt(16).toByte()}.toByteArray())}finally{actual.fill(0)}
        }finally{spec.clearPassword()}
    }
}
