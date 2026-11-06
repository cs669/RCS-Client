package com.android.rcsclient.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import androidx.core.content.ContextCompat
import com.android.rcsclient.model.DeliveryStatus
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.RcsContactCapabilities
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.network.RcsTransport

data class DeviceContactItem(
    val displayName: String,
    val phoneNumber: String
)

data class HardwareUceProbeResult(
    val e164Phone: String,
    val simReady: Boolean,
    val carrierName: String,
    val subscriptionId: Int,
    val hasSendSmsPermission: Boolean,
    val isDefaultSmsApp: Boolean,
    val hardwareUceAccessible: Boolean,
    val diagnosticSummary: String
)

/**
 * Bridges Android's Carrier IMS / RCS APIs (`ImsManager`, `ImsRcsManager`, `RcsUceAdapter`,
 * `SubscriptionManager`, and `SmsManager`) with real cellular SMS/MMS & network RCS transports.
 */
class CarrierImsRcsManager(private val context: Context?) {

    companion object {
        const val ACTION_SMS_SENT = "com.android.rcsclient.ACTION_SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.android.rcsclient.ACTION_SMS_DELIVERED"
    }

    fun hasRuntimePermission(permission: String): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reports the real state: the actual carrier name, the default SMS role, and whether our own
     * RCS transport is registered. Nothing here is simulated. The platform `ImsRcsManager` is
     * only probed to report whether the OS exposes it to us (it normally doesn't, see below).
     */
    fun queryImsRegistrationStatus(rcsTransport: RcsTransport): ImsRegistrationStatus {
        val ctx = context ?: return ImsRegistrationStatus(
            isImsRegistered = rcsTransport.isRegistered,
            acsProvisioningState = rcsTransport.statusLabel
        )

        val isDefaultSms = DefaultSmsRoleHelper.isDefaultSmsApp(ctx)
        var carrierName = "Unknown carrier"
        var subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId()
        }

        try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val opName = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
            if (opName != null) {
                carrierName = opName
            }
        } catch (_: Throwable) {
            // Keep "Unknown carrier"
        }

        // Android only lets system or carrier privileged apps use ImsRcsManager, so for a normal
        // install this throws SecurityException. We report what actually happens.
        var platformImsAccessible = false
        var platformUceEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                val imsManager = ctx.getSystemService(ImsManager::class.java)
                if (imsManager != null) {
                    platformUceEnabled = imsManager.getImsRcsManager(subId).uceAdapter.isUceSettingEnabled
                    platformImsAccessible = true
                }
            } catch (_: SecurityException) {
                // Expected for non-privileged apps
            } catch (_: Throwable) {
                // Device does not expose an ImsService for this subscription
            }
        }

        return ImsRegistrationStatus(
            isImsRegistered = rcsTransport.isRegistered,
            imsTransportType = if (rcsTransport.isRegistered) "RCS (own stack)" else "None. Messages go out as cellular SMS.",
            carrierName = carrierName,
            subscriptionId = subId,
            acsProvisioningState = rcsTransport.statusLabel,
            ucePresenceEnabled = platformImsAccessible && platformUceEnabled,
            mlsKeyPackagePublished = false,
            isHardwareImsBacked = platformImsAccessible,
            defaultSmsAppGranted = isDefaultSms
        )
    }

    /**
     * Probes the actual Android OS `ImsRcsManager` / `RcsUceAdapter` & SIM state for a real phone number
     * and returns a detailed diagnostic report explaining what hardware paths are available.
     */
    fun probeRealHardwareUce(phoneNumber: String): HardwareUceProbeResult {
        val e164 = phoneNumber.filter { it.isDigit() || it == '+' }
        val ctx = context ?: return HardwareUceProbeResult(
            e164Phone = e164,
            simReady = false,
            carrierName = "JVM Test Environment",
            subscriptionId = -1,
            hasSendSmsPermission = false,
            isDefaultSmsApp = false,
            hardwareUceAccessible = false,
            diagnosticSummary = "No Android Context"
        )

        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val simReady = tm?.simState == TelephonyManager.SIM_STATE_READY
        val carrierName = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
            ?: if (simReady) "Active Cellular SIM" else "No Physical SIM / Emulator Radio"

        val subId = SubscriptionManager.getDefaultSmsSubscriptionId().let {
            if (it == SubscriptionManager.INVALID_SUBSCRIPTION_ID) SubscriptionManager.getDefaultSubscriptionId() else it
        }
        val hasSendSms = hasRuntimePermission(Manifest.permission.SEND_SMS)
        val isDefaultSms = DefaultSmsRoleHelper.isDefaultSmsApp(ctx)

        var uceAccessible = false
        var uceDetail: String

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            try {
                val imsManager = ctx.getSystemService(ImsManager::class.java)
                val rcsManager = imsManager?.getImsRcsManager(subId)
                val enabled = rcsManager?.uceAdapter?.isUceSettingEnabled ?: false
                uceAccessible = true
                uceDetail = "Hardware RcsUceAdapter accessible (UCE enabled=$enabled) for subId=$subId."
            } catch (se: SecurityException) {
                uceDetail = "OS blocked RcsUceAdapter (${se.javaClass.simpleName}: Android only allows system or carrier signed apps with READ_PRIVILEGED_PHONE_STATE). This is expected. Messages will go out as cellular SMS."
            } catch (t: Throwable) {
                uceDetail = "RcsUceAdapter unavailable on subId=$subId (${t.javaClass.simpleName})."
            }
        } else {
            uceDetail = "No active SIM subscription (subId=$subId) for hardware ImsRcsManager."
        }

        return HardwareUceProbeResult(
            e164Phone = e164,
            simReady = simReady,
            carrierName = carrierName,
            subscriptionId = subId,
            hasSendSmsPermission = hasSendSms,
            isDefaultSmsApp = isDefaultSms,
            hardwareUceAccessible = uceAccessible,
            diagnosticSummary = uceDetail
        )
    }

    /**
     * Loads real contacts from the phone's `ContactsContract` when `READ_CONTACTS` permission is held.
     */
    fun loadDeviceContacts(limit: Int = 50): List<DeviceContactItem> {
        val ctx = context ?: return emptyList()
        if (!hasRuntimePermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val contacts = mutableListOf<DeviceContactItem>()
        val seenNumbers = mutableSetOf<String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && contacts.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    val normalized = rawNum.filter { it.isDigit() || it == '+' }
                    if (normalized.length >= 7 && seenNumbers.add(normalized)) {
                        contacts.add(
                            DeviceContactItem(
                                displayName = name.ifBlank { rawNum },
                                phoneNumber = rawNum
                            )
                        )
                    }
                }
            }
        }
        return contacts
    }

    /**
     * RCS capability exchange (SIP OPTIONS or presence) needs a registered RCS stack, which this
     * client doesn't have yet. So we honestly report "unknown", which means everything false.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveContactCapabilities(phoneNumber: String): RcsContactCapabilities = RcsContactCapabilities()

    /**
     * Resolves the effective transport protocol for an outgoing message.
     */
    fun selectTransportProtocol(
        isImsRegistered: Boolean,
        preferredProtocol: TransportProtocol,
        recipientCapabilities: RcsContactCapabilities,
        autoSmsFallback: Boolean,
        hasMediaAttachments: Boolean
    ): TransportProtocol {
        if (preferredProtocol == TransportProtocol.SMS) {
            return if (hasMediaAttachments) TransportProtocol.MMS else TransportProtocol.SMS
        }
        if (preferredProtocol == TransportProtocol.MMS) {
            return TransportProtocol.MMS
        }

        val rcsAvailable = isImsRegistered && recipientCapabilities.supportsSessionChat
        if (rcsAvailable) {
            return TransportProtocol.RCS_UP_2_7
        }

        return if (autoSmsFallback) {
            if (hasMediaAttachments) TransportProtocol.MMS else TransportProtocol.SMS
        } else {
            TransportProtocol.RCS_UP_2_7
        }
    }

    /**
     * Dispatches a real cellular SMS through Android's `SmsManager` (requires `SEND_SMS` permission)
     * and wires up hardware `PendingIntent` callbacks for `SENT` and `DELIVERED` radio receipts.
     */
    fun dispatchRealCellularSms(
        messageId: String,
        destinationPhone: String,
        text: String,
        onStatusUpdate: (DeliveryStatus, String) -> Unit
    ): Boolean {
        val ctx = context ?: return false
        if (!hasRuntimePermission(Manifest.permission.SEND_SMS)) {
            onStatusUpdate(
                DeliveryStatus.FAILED,
                "Missing SEND_SMS permission. Grant it from the settings screen."
            )
            return false
        }

        val cleanPhone = destinationPhone.filter { it.isDigit() || it == '+' }
        if (cleanPhone.length < 3) {
            onStatusUpdate(DeliveryStatus.FAILED, "Invalid destination phone number: $destinationPhone")
            return false
        }

        return try {
            val smsManager = ctx.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()

            val sentAction = "$ACTION_SMS_SENT.$messageId"
            val deliveredAction = "$ACTION_SMS_DELIVERED.$messageId"

            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    runCatching { ctx.unregisterReceiver(this) }
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            onStatusUpdate(
                                DeliveryStatus.SMS_FALLBACK_SENT,
                                "SMS sent to $cleanPhone"
                            )
                        }
                        SmsManager.RESULT_ERROR_NO_SERVICE -> {
                            onStatusUpdate(DeliveryStatus.FAILED, "Radio error: No cellular service")
                        }
                        SmsManager.RESULT_ERROR_RADIO_OFF -> {
                            onStatusUpdate(DeliveryStatus.FAILED, "Radio error: Cellular radio is off")
                        }
                        else -> {
                            onStatusUpdate(
                                DeliveryStatus.FAILED,
                                "Cellular SMS error (code=$resultCode). Check active SIM."
                            )
                        }
                    }
                }
            }

            val deliveredReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    runCatching { ctx.unregisterReceiver(this) }
                    if (resultCode == Activity.RESULT_OK) {
                        onStatusUpdate(
                            DeliveryStatus.DELIVERED,
                            "SMS delivered to $cleanPhone"
                        )
                    }
                }
            }

            ContextCompat.registerReceiver(
                ctx,
                sentReceiver,
                IntentFilter(sentAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                ctx,
                deliveredReceiver,
                IntentFilter(deliveredAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sentPi = PendingIntent.getBroadcast(
                ctx,
                messageId.hashCode(),
                Intent(sentAction).setPackage(ctx.packageName),
                flags
            )
            val deliveredPi = PendingIntent.getBroadcast(
                ctx,
                messageId.hashCode() + 1,
                Intent(deliveredAction).setPackage(ctx.packageName),
                flags
            )

            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                val sentIntents = ArrayList(parts.map { sentPi })
                val deliveredIntents = ArrayList(parts.map { deliveredPi })
                smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, deliveredIntents)
            } else {
                smsManager.sendTextMessage(cleanPhone, null, text, sentPi, deliveredPi)
            }
            true
        } catch (t: Throwable) {
            onStatusUpdate(DeliveryStatus.FAILED, "SmsManager exception: ${t.message}")
            false
        }
    }

    /**
     * Backwards-compatible helper used by unit tests.
     */
    fun tryDispatchPlatformSms(destinationPhone: String, text: String): Boolean {
        return dispatchRealCellularSms(
            messageId = "msg-${System.currentTimeMillis()}",
            destinationPhone = destinationPhone,
            text = text,
            onStatusUpdate = { _, _ -> }
        )
    }
}
