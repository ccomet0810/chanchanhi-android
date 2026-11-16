package dev.ccomet.chanchanhi04.admin

/** Fail closed for an existing participant or any ordinary/unknown AI connection. */
object ReviewAdminPolicy {
    const val DEVICE_LABEL="[test] Google Play review"
    const val BRIDGE_KEY_HASH="652bc8c553d0fca86f7505fc5747792861cafc76cef935b39884d680cf3a65c7"
    const val PREFERENCES="review-admin-1"
    const val HASH_KEY="bridge-key-sha256"
    fun allows(enrolled:Boolean,label:String,hasSavedBridge:Boolean,bridgeKeyHash:String?,hasParticipantData:Boolean,hasGuidanceRecords:Boolean):Boolean {
        if(enrolled&&label!=DEVICE_LABEL)return false
        if(hasSavedBridge&&bridgeKeyHash!=BRIDGE_KEY_HASH)return false
        return enrolled||(!hasParticipantData&&!hasGuidanceRecords)
    }
    fun hashBridgeToken(token:String)=java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
}
