package com.android.rcsclient.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.android.rcsclient.RcsApplication

/**
 * Required receiver for the default SMS app. Gets `WAP_PUSH_DELIVER` for
 * `application/vnd.wap.mms-message`, which normally carries an `M-Notification.ind`.
 * The actual download runs in the background and finishes in [MmsDownloadedReceiver].
 */
class MmsWapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MmsLog.d(context, "WAP push received: action=${intent.action} type=${intent.type} extras=${intent.extras?.keySet()}")
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val app = context.applicationContext as? RcsApplication ?: run {
            MmsLog.e(context, "WAP push: application isn't RcsApplication, dropping")
            return
        }
        val pduBytes = intent.getByteArrayExtra("data")
        if (pduBytes == null || pduBytes.isEmpty()) {
            MmsLog.e(context, "WAP push: no 'data' extra, dropping")
            return
        }

        // Which SIM it arrived on, so the download uses the same one (dual SIM phones)
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val subId = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", invalid)
        )
        MmsLog.d(context, "WAP push: ${pduBytes.size} bytes, subId=$subId")
        app.repository.receiveIncomingWapPushMms(pduBytes, subId)
    }
}
