package com.android.rcsclient.telephony

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import com.android.rcsclient.RcsApplication

/**
 * Required `Service` for Android Default SMS App (`RoleManager.ROLE_SMS`).
 * Handles `android.intent.action.RESPOND_VIA_MESSAGE` quick-reply intents from incoming calls.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val extras = intent.extras
            val messageText = extras?.getString(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            val recipientUri = intent.data
            val phone = recipientUri?.schemeSpecificPart?.takeIf { it.isNotBlank() }

            if (phone != null && messageText != null) {
                val app = applicationContext as? RcsApplication
                app?.repository?.sendQuickReplyToPhone(phone, messageText)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
