package com.android.rcsclient.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.android.rcsclient.RcsApplication

/**
 * Required `BroadcastReceiver` for Android Default SMS/MMS App (`RoleManager.ROLE_SMS`).
 * Handles incoming `android.provider.Telephony.WAP_PUSH_DELIVER` (`application/vnd.wap.mms-message`).
 */
class MmsWapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val app = context.applicationContext as? RcsApplication
        app?.repository?.receiveIncomingSms(
            senderPhone = "MMS Carrier Gateway",
            body = "[Incoming Carrier MMS PDU Received]",
            timestampMs = System.currentTimeMillis(),
            isMms = true
        )
    }
}
