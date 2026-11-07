package com.example.aplassistmvp0.guide

/** Preserve bounded machine metadata while existing callers keep the same localized message. */
internal class BridgeRequestFailure(val status:Int,code:String?,message:String):IllegalStateException(message) {
    val code=code?.takeIf{Regex("[a-z][a-z0-9_]{0,79}").matches(it)}
}
