package com.android.rcsclient.network

import com.android.rcsclient.model.RcsMessage

/**
 * Local settings for this client. The old P2P socket and RBM gateway settings are gone:
 * the P2P socket spoke a private JSON format no other RCS client understands, and the RBM API
 * is for businesses messaging customers, not person to person chat.
 */
data class RcsClientConfig(
    val localPhoneNumber: String = ""
)

/**
 * The seam where a real RCS stack plugs in (ACS provisioning, SIP REGISTER, MSRP/CPIM chat,
 * IMDN, file transfer over HTTP). Everything above this interface stays the same when it lands.
 */
interface RcsTransport {
    /** True only after real ACS provisioning and a successful SIP REGISTER. */
    val isRegistered: Boolean

    /** Short human readable state for the diagnostics screen. */
    val statusLabel: String

    /** Sends a 1:1 chat message to an E.164 number. */
    suspend fun sendChatMessage(toE164: String, message: RcsMessage): Result<Unit>
}

/**
 * The transport in use until a real RCS stack exists. It never pretends to be connected.
 */
object NotConnectedRcsTransport : RcsTransport {
    override val isRegistered: Boolean = false
    override val statusLabel: String = "Not connected. This app doesn't have an RCS stack yet."

    override suspend fun sendChatMessage(toE164: String, message: RcsMessage): Result<Unit> =
        Result.failure(IllegalStateException("RCS isn't connected"))
}

object PhoneNumbers {
    /**
     * Best effort E.164 normalization. Assumes North American numbers when there's no country code.
     * Short codes (under 7 digits) and alphanumeric senders like "AMAZON" are returned unchanged.
     */
    fun normalizeToE164(rawPhone: String): String {
        val trimmed = rawPhone.trim()
        val digitsAndPlus = trimmed.filter { it.isDigit() || it == '+' }
        if (digitsAndPlus.count { it.isDigit() } < 7) return trimmed
        if (digitsAndPlus.startsWith("+")) return digitsAndPlus
        if (digitsAndPlus.length == 10) return "+1$digitsAndPlus"
        if (digitsAndPlus.length == 11 && digitsAndPlus.startsWith("1")) return "+$digitsAndPlus"
        return "+$digitsAndPlus"
    }

    /**
     * Compares two addresses. Real numbers match by digits, ignoring formatting and a leading
     * country code 1. Short codes and alphanumeric senders must match exactly (ignoring case).
     */
    fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.length < 7 || db.length < 7) return a.trim().equals(b.trim(), ignoreCase = true)
        return da.removePrefix("1") == db.removePrefix("1")
    }
}
