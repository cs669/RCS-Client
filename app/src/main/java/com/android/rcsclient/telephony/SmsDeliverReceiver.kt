package com.android.rcsclient.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.android.rcsclient.RcsApplication

/**
 * Required `BroadcastReceiver` for Android Default SMS App (`RoleManager.ROLE_SMS`).
 * Receives `android.provider.Telephony.SMS_DELIVER` broadcasts when Pulse RCS is the default handler.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val senderPhone = messages.first().displayOriginatingAddress ?: "Unknown"
        val combinedBody = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
        val timestamp = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        val app = context.applicationContext as? RcsApplication
        app?.repository?.receiveIncomingSms(
            senderPhone = senderPhone,
            body = combinedBody,
            timestampMs = timestamp
        )
    }
}
