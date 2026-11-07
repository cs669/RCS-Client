package com.android.rcsclient.telephony

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.android.rcsclient.model.AttachmentType
import com.android.rcsclient.model.DeliveryStatus
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.MediaAttachment
import com.android.rcsclient.model.RcsContactCapabilities
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.network.RcsTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** A received MMS, ready for the repository to thread and store. */
data class IncomingMms(
    val sender: String,
    val recipients: List<String>,
    val body: String,
    val attachments: List<MediaAttachment>,
    val timestampMs: Long,
    val failed: Boolean = false
)

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
        const val ACTION_MMS_SENT = "com.android.rcsclient.ACTION_MMS_SENT"
        const val ACTION_MMS_DOWNLOADED = "com.android.rcsclient.ACTION_MMS_DOWNLOADED"
        const val EXTRA_MESSAGE_ID = "com.android.rcsclient.extra.MESSAGE_ID"
        const val EXTRA_PDU_PATH = "com.android.rcsclient.extra.PDU_PATH"
        const val EXTRA_RECIPIENTS = "com.android.rcsclient.extra.RECIPIENTS"
        const val EXTRA_NOTIFICATION_PDU = "com.android.rcsclient.extra.NOTIFICATION_PDU"
        const val EXTRA_SUB_ID = "com.android.rcsclient.extra.SUB_ID"
        const val EXTRA_IS_CONTROL_PDU = "com.android.rcsclient.extra.IS_CONTROL_PDU"
        private const val PREFS_NAME = "rcs_client"
        private val MMS_SERVICE_PACKAGES = listOf("com.android.phone", "com.android.mms.service")
        private const val PREF_OWN_NUMBER = "own_phone_number"
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
            imsTransportType = if (rcsTransport.isRegistered) "RCS (own stack)" else "Cellular SMS / MMS",
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
                uceDetail = "OS blocked RcsUceAdapter (${se.javaClass.simpleName}: Android only allows system or carrier signed apps with READ_PRIVILEGED_PHONE_STATE). Messages use cellular SMS/MMS."
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
     * Reads a real user-picked `content://` URI, copies its bytes into persistent app storage
     * (`filesDir/mms_media/`), and returns a `MediaAttachment` with real file metadata.
     */
    fun resolveAttachmentFromUri(rawUriString: String): MediaAttachment? {
        val ctx = context ?: return null
        return runCatching {
            val uri = Uri.parse(rawUriString)
            val cr = ctx.contentResolver
            val mimeType = cr.getType(uri) ?: "image/jpeg"

            var displayName = "attachment_${System.currentTimeMillis()}"
            var sizeBytes = 0L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                        sizeBytes = c.getLong(sizeIdx)
                    }
                }
            }

            val mediaDir = File(ctx.filesDir, "mms_media").apply { mkdirs() }
            val safeFileName = "${System.currentTimeMillis()}_${displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            val localFile = File(mediaDir, safeFileName)
            cr.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (localFile.exists() && localFile.length() > 0L) {
                sizeBytes = localFile.length()
            }

            val attachmentType = when {
                mimeType.startsWith("image/") -> AttachmentType.HIGH_RES_IMAGE
                mimeType.startsWith("video/") -> AttachmentType.HIGH_RES_VIDEO
                mimeType.startsWith("audio/") -> AttachmentType.VOICE_NOTE
                else -> AttachmentType.DOCUMENT_FILE
            }

            val kb = (sizeBytes / 1024L).coerceAtLeast(1L)
            MediaAttachment(
                type = attachmentType,
                title = displayName,
                subtitle = "$mimeType • $kb KB • MMS / FT",
                mimeType = mimeType,
                fileSizeBytes = sizeBytes,
                uriString = Uri.fromFile(localFile).toString()
            )
        }.getOrNull()
    }

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
        val ctx = context ?: run {
            onStatusUpdate(DeliveryStatus.FAILED, "No Android Context available for MMS SmsManager")
            return false
        }
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
     * Sends a real cellular MMS (1:1 or group, text and/or media) through
     * `SmsManager.sendMultimediaMessage`.
     *
     * Synchronous problems (no permission, too big, unreadable file) are reported through
     * [onStatusUpdate] right away. The carrier's answer arrives later at [MmsSentReceiver], a
     * manifest receiver, so it still lands even if the app was closed in the meantime.
     */
    fun dispatchRealCellularMms(
        messageId: String,
        recipients: List<String>,
        bodyText: String,
        attachments: List<MediaAttachment>,
        subject: String = "",
        onStatusUpdate: (DeliveryStatus, String) -> Unit
    ): Boolean {
        val ctx = context ?: run {
            onStatusUpdate(DeliveryStatus.FAILED, "No Android Context available for MMS")
            return false
        }
        if (!hasRuntimePermission(Manifest.permission.SEND_SMS)) {
            onStatusUpdate(DeliveryStatus.FAILED, "Missing SEND_SMS permission for MMS. Grant it in Settings.")
            return false
        }
        MmsLog.d(ctx, "send: start id=$messageId recipients=$recipients textLen=${bodyText.length} attachments=${attachments.map { "${it.mimeType}:${it.uriString}" }}")
        if (!DefaultSmsRoleHelper.isDefaultSmsApp(ctx)) {
            MmsLog.e(ctx, "send: aborted, not the default SMS app")
            onStatusUpdate(DeliveryStatus.FAILED, "MMS only works when this is the default SMS app")
            return false
        }

        val cleanRecipients = recipients
            .mapNotNull { MmsPduCodec.formatRecipientAddress(it)?.let { _ -> it } }
            .distinct()
        if (cleanRecipients.isEmpty()) {
            onStatusUpdate(DeliveryStatus.FAILED, "No valid MMS recipient phone number")
            return false
        }

        return try {
            val subId = defaultSmsSubId()
            val smsManager = smsManagerFor(ctx, subId)
            val limits = carrierMmsLimits(smsManager)
            MmsLog.d(ctx, "send: subId=$subId limits=$limits")

            val mmsParts = mutableListOf<MmsBodyPart>()
            if (bodyText.isNotBlank()) {
                mmsParts.add(
                    MmsBodyPart(
                        contentType = "text/plain",
                        filename = "text_0.txt",
                        data = bodyText.toByteArray(StandardCharsets.UTF_8),
                        charset = "UTF-8"
                    )
                )
            }

            // Read every attachment first so the image budget can be split fairly.
            val loaded = attachments.mapIndexedNotNull { index, att ->
                val bytes = att.uriString?.takeIf { it.isNotBlank() }?.let { readBytesFromUri(ctx, Uri.parse(it)) }
                if (bytes == null || bytes.isEmpty()) {
                    MmsLog.e(ctx, "send: couldn't read attachment ${att.uriString}")
                    onStatusUpdate(DeliveryStatus.FAILED, "Couldn't read attachment ${att.title}")
                    return false
                }
                Triple(index, att, bytes)
            }

            val textBytes = mmsParts.sumOf { it.data.size }
            val nonImageBytes = loaded.filterNot { isImage(it.second.mimeType) }.sumOf { it.third.size }
            val imageCount = loaded.count { isImage(it.second.mimeType) }
            val headerOverhead = 6 * 1024
            val imageBudget = if (imageCount == 0) 0 else
                ((limits.maxMessageBytes - textBytes - nonImageBytes - headerOverhead) / imageCount)
                    .coerceAtLeast(30 * 1024)

            for ((index, att, rawBytes) in loaded) {
                val (finalMime, finalBytes) = if (isImage(att.mimeType)) {
                    prepareImageForMms(rawBytes, att.mimeType, imageBudget, limits.maxImageWidth, limits.maxImageHeight)
                } else {
                    att.mimeType to rawBytes
                }
                mmsParts.add(
                    MmsBodyPart(
                        contentType = finalMime,
                        filename = filenameFor(att.title, finalMime, index),
                        data = finalBytes
                    )
                )
            }

            if (mmsParts.isEmpty()) {
                onStatusUpdate(DeliveryStatus.FAILED, "Cannot send an empty MMS")
                return false
            }

            val pduBytes = MmsPduCodec.encodeSendReq(
                transactionId = "T" + messageId.filter { it.isLetterOrDigit() }.take(20),
                recipients = cleanRecipients,
                subject = subject,
                parts = mmsParts
            )
            MmsLog.d(ctx, "send: parts=${mmsParts.map { "${it.contentType}:${it.filename}:${it.data.size}B" }} pduSize=${pduBytes.size}")
            MmsLog.d(ctx, "send: pdu head ${MmsLog.hex(pduBytes)}")
            MmsLog.savePdu(ctx, "last_send.pdu", pduBytes)
            if (pduBytes.size > limits.maxMessageBytes) {
                MmsLog.e(ctx, "send: too big ${pduBytes.size} > ${limits.maxMessageBytes}")
                onStatusUpdate(
                    DeliveryStatus.FAILED,
                    "Too big for MMS: ${pduBytes.size / 1024} KB, your carrier allows ${limits.maxMessageBytes / 1024} KB"
                )
                return false
            }

            val pduFile = File(mmsCacheDir(ctx), "send_${safeName(messageId)}.pdu")
            pduFile.writeBytes(pduBytes)
            val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.mmsprovider", pduFile)
            grantToMmsService(ctx, contentUri, writable = false)

            val sentIntent = Intent(ctx, MmsSentReceiver::class.java)
                .setAction("$ACTION_MMS_SENT.$messageId")
                .putExtra(EXTRA_MESSAGE_ID, messageId)
                .putExtra(EXTRA_PDU_PATH, pduFile.absolutePath)
                .putExtra(EXTRA_RECIPIENTS, cleanRecipients.joinToString(", "))
            val sentPi = PendingIntent.getBroadcast(
                ctx,
                messageId.hashCode(),
                sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            MmsLog.d(ctx, "send: calling sendMultimediaMessage uri=$contentUri")
            smsManager.sendMultimediaMessage(ctx, contentUri, null, null, sentPi)
            MmsLog.d(ctx, "send: handed to the system MMS service")
            true
        } catch (t: Throwable) {
            MmsLog.e(ctx, "send: exception", t)
            onStatusUpdate(DeliveryStatus.FAILED, "MMS send error: ${t.message}")
            false
        }
    }

    /**
     * Turns the carrier's answer to an `M-Send.req` into a delivery status. Called by [MmsSentReceiver].
     */
    fun interpretMmsSendResult(intent: Intent, resultCode: Int): Pair<DeliveryStatus, String> {
        intent.getStringExtra(EXTRA_PDU_PATH)?.let { runCatching { File(it).delete() } }
        val recipients = intent.getStringExtra(EXTRA_RECIPIENTS).orEmpty()
        val responseBytes = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        val conf = responseBytes?.let { MmsPduCodec.parsePdu(it) }
        MmsLog.d(
            context,
            "sent-result: control=${intent.getBooleanExtra(EXTRA_IS_CONTROL_PDU, false)} id=${intent.getStringExtra(EXTRA_MESSAGE_ID)} " +
                "resultCode=$resultCode (${mmsErrorText(resultCode)}) http=$httpStatus " +
                "confType=${conf?.messageType} responseStatus=${conf?.responseStatus} response=${MmsLog.hex(responseBytes, 80)}"
        )

        val mmscOk = conf == null || conf.responseStatus == -1 || conf.responseStatus == MmsPduCodec.RESPONSE_STATUS_OK
        return if (resultCode == Activity.RESULT_OK && mmscOk) {
            DeliveryStatus.SMS_FALLBACK_SENT to "MMS sent to $recipients"
        } else {
            val detail = when {
                conf != null && conf.responseStatus != -1 && !mmscOk ->
                    "carrier rejected it, status 0x${conf.responseStatus.toString(16)}"
                httpStatus != 0 -> "HTTP $httpStatus"
                else -> mmsErrorText(resultCode)
            }
            DeliveryStatus.FAILED to "MMS failed ($detail)"
        }
    }

    /**
     * Handles an incoming WAP push. A normal push carries an `M-Notification.ind`, which we
     * download with `SmsManager.downloadMultimediaMessage`. The download result goes to
     * [MmsDownloadedReceiver]. Repeat notifications for a message we already have are
     * acknowledged again and otherwise ignored.
     */
    fun handleIncomingWapPushMms(
        wapPushPdu: ByteArray,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        onMmsIngested: (IncomingMms) -> Unit
    ) {
        val ctx = context ?: return
        MmsLog.d(ctx, "push: received ${wapPushPdu.size} bytes subId=$subId head ${MmsLog.hex(wapPushPdu)}")
        MmsLog.savePdu(ctx, "last_notification.pdu", wapPushPdu)
        val parsed = MmsPduCodec.parsePdu(wapPushPdu) ?: run {
            MmsLog.e(ctx, "push: couldn't parse the PDU")
            return
        }
        MmsLog.d(ctx, "push: type=0x${parsed.messageType.toString(16)} tx=${parsed.transactionId} from=${parsed.from} size=${parsed.messageSize} location=${parsed.contentLocation}")

        if (parsed.messageType == MmsPduCodec.MESSAGE_TYPE_RETRIEVE_CONF) {
            onMmsIngested(toIncomingMms(ctx, parsed, parsed.from))
            return
        }
        if (parsed.messageType != MmsPduCodec.MESSAGE_TYPE_NOTIFICATION_IND || parsed.contentLocation.isBlank()) {
            MmsLog.d(ctx, "push: not a notification, ignored")
            return // Delivery reports, read-orig and others aren't handled yet
        }

        val effectiveSub = if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) defaultSmsSubId() else subId
        val smsManager = smsManagerFor(ctx, effectiveSub)

        if (MmsDedupeStore.wasRetrieved(ctx, parsed.contentLocation)) {
            MmsLog.d(ctx, "push: already downloaded this one, re-acknowledging")
            sendNotifyResp(ctx, smsManager, parsed.transactionId, MmsPduCodec.STATUS_RETRIEVED)
            return
        }
        if (!MmsDedupeStore.markInProgress(ctx, parsed.contentLocation)) {
            MmsLog.d(ctx, "push: download already running for this one, skipped")
            return // A download for this message is already running
        }

        cleanUpOldPduFiles(ctx)
        val downloadFile = File(mmsCacheDir(ctx), "retrieve_${safeName(parsed.transactionId.ifBlank { parsed.contentLocation })}.pdu")
        runCatching { downloadFile.delete(); downloadFile.createNewFile() }
        val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.mmsprovider", downloadFile)
        grantToMmsService(ctx, contentUri, writable = true)

        val doneIntent = Intent(ctx, MmsDownloadedReceiver::class.java)
            .setAction("$ACTION_MMS_DOWNLOADED.${parsed.contentLocation.hashCode()}")
            .putExtra(EXTRA_PDU_PATH, downloadFile.absolutePath)
            .putExtra(EXTRA_NOTIFICATION_PDU, wapPushPdu)
            .putExtra(EXTRA_SUB_ID, effectiveSub)
        val downloadPi = PendingIntent.getBroadcast(
            ctx,
            parsed.contentLocation.hashCode(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            MmsLog.d(ctx, "download: start subId=$effectiveSub uri=$contentUri")
            smsManager.downloadMultimediaMessage(ctx, parsed.contentLocation, contentUri, null, downloadPi)
        } catch (t: Throwable) {
            MmsLog.e(ctx, "download: exception", t)
            MmsDedupeStore.clearInProgress(ctx, parsed.contentLocation)
            onMmsIngested(
                IncomingMms(
                    sender = parsed.from.ifBlank { "Unknown sender" },
                    recipients = parsed.toList + parsed.ccList,
                    body = "MMS couldn't be downloaded (${t.message})",
                    attachments = emptyList(),
                    timestampMs = System.currentTimeMillis(),
                    failed = true
                )
            )
        }
    }

    /**
     * Called by [MmsDownloadedReceiver] when `downloadMultimediaMessage` finishes.
     */
    fun handleMmsDownloadResult(intent: Intent, resultCode: Int, onMmsIngested: (IncomingMms) -> Unit) {
        val ctx = context ?: return
        val notification = intent.getByteArrayExtra(EXTRA_NOTIFICATION_PDU)?.let { MmsPduCodec.parsePdu(it) } ?: return
        val subId = intent.getIntExtra(EXTRA_SUB_ID, defaultSmsSubId())
        val file = intent.getStringExtra(EXTRA_PDU_PATH)?.let { File(it) }

        val bytes = runCatching {
            if (file != null && file.exists() && file.length() > 0L) file.readBytes()
            else intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        }.getOrNull()
        runCatching { file?.delete() }

        val retrieved = bytes?.let { MmsPduCodec.parsePdu(it) }
        val smsManager = smsManagerFor(ctx, subId)
        MmsLog.savePdu(ctx, "last_retrieve.pdu", bytes)
        MmsLog.d(
            ctx,
            "download-result: resultCode=$resultCode (${mmsErrorText(resultCode)}) " +
                "http=${intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)} bytes=${bytes?.size} " +
                "type=${retrieved?.messageType} from=${retrieved?.from} to=${retrieved?.toList} cc=${retrieved?.ccList} " +
                "parts=${retrieved?.parts?.map { "${it.contentType}:${it.filename}:${it.data.size}B" }}"
        )

        if (resultCode == Activity.RESULT_OK && retrieved != null &&
            retrieved.messageType == MmsPduCodec.MESSAGE_TYPE_RETRIEVE_CONF
        ) {
            MmsDedupeStore.markRetrieved(ctx, notification.contentLocation)
            // Tell the MMSC we have it, the way stock Android Messages does.
            if (retrieved.transactionId.isNotBlank()) {
                sendAcknowledge(ctx, smsManager, retrieved.transactionId)
            } else {
                sendNotifyResp(ctx, smsManager, notification.transactionId, MmsPduCodec.STATUS_RETRIEVED)
            }
            onMmsIngested(toIncomingMms(ctx, retrieved, notification.from))
        } else {
            MmsDedupeStore.clearInProgress(ctx, notification.contentLocation)
            val subj = notification.subject.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()
            onMmsIngested(
                IncomingMms(
                    sender = notification.from.ifBlank { "Unknown sender" },
                    recipients = notification.toList + notification.ccList,
                    body = "MMS$subj couldn't be downloaded (${mmsErrorText(resultCode)}). Check mobile data.",
                    attachments = emptyList(),
                    timestampMs = System.currentTimeMillis(),
                    failed = true
                )
            )
        }
    }

    private fun toIncomingMms(ctx: Context, parsed: ParsedMmsPdu, fallbackSender: String): IncomingMms {
        val sender = parsed.from.ifBlank { fallbackSender.ifBlank { "Unknown sender" } }
        val textPieces = mutableListOf<String>()
        val attachments = mutableListOf<MediaAttachment>()
        val mediaDir = File(ctx.filesDir, "mms_media").apply { mkdirs() }

        for ((idx, part) in parsed.parts.withIndex()) {
            val mime = part.contentType.lowercase()
            when {
                mime == "application/smil" -> Unit // layout only
                mime == "text/plain" -> {
                    val str = part.decodeText().trim('\u0000', ' ', '\n', '\r')
                    if (str.isNotBlank()) textPieces.add(str)
                }
                part.data.isNotEmpty() -> {
                    val savedFile = File(mediaDir, "${System.currentTimeMillis()}_${idx}_${safeName(part.filename)}")
                    runCatching { savedFile.writeBytes(part.data) }
                    val attType = when {
                        mime.startsWith("image/") -> AttachmentType.HIGH_RES_IMAGE
                        mime.startsWith("video/") -> AttachmentType.HIGH_RES_VIDEO
                        mime.startsWith("audio/") -> AttachmentType.VOICE_NOTE
                        else -> AttachmentType.DOCUMENT_FILE
                    }
                    val kb = (part.data.size / 1024).coerceAtLeast(1)
                    attachments.add(
                        MediaAttachment(
                            type = attType,
                            title = part.filename.ifBlank { "mms_attachment_$idx" },
                            subtitle = "$mime • $kb KB • MMS",
                            mimeType = mime,
                            fileSizeBytes = part.data.size.toLong(),
                            uriString = Uri.fromFile(savedFile).toString()
                        )
                    )
                }
            }
        }

        val timestamp = if (parsed.dateSeconds > 0) parsed.dateSeconds * 1000L else System.currentTimeMillis()
        return IncomingMms(
            sender = sender,
            recipients = parsed.toList + parsed.ccList,
            body = textPieces.joinToString("\n").ifBlank { parsed.subject },
            attachments = attachments,
            timestampMs = timestamp
        )
    }

    private fun sendAcknowledge(ctx: Context, smsManager: SmsManager, transactionId: String) =
        sendControlPdu(ctx, smsManager, "ack", MmsPduCodec.encodeAcknowledgeInd(transactionId))

    private fun sendNotifyResp(ctx: Context, smsManager: SmsManager, transactionId: String, status: Int) {
        if (transactionId.isBlank()) return
        sendControlPdu(ctx, smsManager, "nresp", MmsPduCodec.encodeNotifyRespInd(transactionId, status))
    }

    /**
     * Lets the phone's MMS service open our PDU file. Some phones grant this automatically and
     * some don't, and when they don't, the send or download fails with a generic I/O error.
     */
    private fun grantToMmsService(ctx: Context, uri: android.net.Uri, writable: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        for (pkg in MMS_SERVICE_PACKAGES) {
            runCatching { ctx.grantUriPermission(pkg, uri, flags) }
                .onFailure { MmsLog.d(ctx, "grant to $pkg skipped: ${it.message}") }
        }
    }

    private fun sendControlPdu(ctx: Context, smsManager: SmsManager, kind: String, pdu: ByteArray) {
        MmsLog.d(ctx, "ack: sending $kind ${MmsLog.hex(pdu)}")
        runCatching {
            val file = File(mmsCacheDir(ctx), "${kind}_${System.currentTimeMillis()}.pdu")
            file.writeBytes(pdu)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.mmsprovider", file)
            grantToMmsService(ctx, uri, writable = false)
            val pi = PendingIntent.getBroadcast(
                ctx,
                file.name.hashCode(),
                Intent(ctx, MmsSentReceiver::class.java)
                    .setAction("$ACTION_MMS_SENT.${file.name}")
                    .putExtra(EXTRA_PDU_PATH, file.absolutePath)
                    .putExtra(EXTRA_IS_CONTROL_PDU, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            smsManager.sendMultimediaMessage(ctx, uri, null, null, pi)
        }
    }

    // ---------------------------------------------------------------- helpers

    private data class MmsLimits(val maxMessageBytes: Int, val maxImageWidth: Int, val maxImageHeight: Int)

    /** The carrier's real MMS size limits, from carrier config. 300 KB is the safe fallback. */
    private fun carrierMmsLimits(smsManager: SmsManager): MmsLimits {
        val values = runCatching { smsManager.carrierConfigValues }.getOrNull()
        val maxBytes = values?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0)?.takeIf { it > 0 } ?: (300 * 1024)
        val maxW = values?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, 0)?.takeIf { it > 0 } ?: 1280
        val maxH = values?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, 0)?.takeIf { it > 0 } ?: 1280
        return MmsLimits(maxBytes, maxW, maxH)
    }

    fun defaultSmsSubId(): Int {
        val sms = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (sms != SubscriptionManager.INVALID_SUBSCRIPTION_ID) sms else SubscriptionManager.getDefaultSubscriptionId()
    }

    @Suppress("DEPRECATION")
    private fun smsManagerFor(ctx: Context, subId: Int): SmsManager {
        val base = ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.createForSubscriptionId(subId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    }

    /**
     * Our own number, used to keep ourselves out of group MMS recipient lists. Uses the number
     * saved in settings first, then whatever the SIM reports (many carriers leave it blank).
     */
    @Suppress("DEPRECATION")
    fun loadOwnPhoneNumber(): String {
        val ctx = context ?: return ""
        val saved = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_OWN_NUMBER, null)
        if (!saved.isNullOrBlank()) return saved
        val canRead = hasRuntimePermission(Manifest.permission.READ_PHONE_NUMBERS) ||
            hasRuntimePermission(Manifest.permission.READ_PHONE_STATE)
        if (!canRead) return ""
        return runCatching {
            val subId = defaultSmsSubId()
            val fromSub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                ctx.getSystemService(SubscriptionManager::class.java)?.getPhoneNumber(subId)
            } else null
            fromSub?.takeIf { it.isNotBlank() }
                ?: ctx.getSystemService(TelephonyManager::class.java)?.line1Number.orEmpty()
        }.getOrDefault("")
    }

    fun saveOwnPhoneNumber(number: String) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_OWN_NUMBER, number.trim())?.apply()
    }

    private fun mmsCacheDir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }

    private fun cleanUpOldPduFiles(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        mmsCacheDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) runCatching { it.delete() } }
    }

    private fun safeName(raw: String) = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)

    private fun isImage(mime: String) = mime.lowercase().startsWith("image/")

    private fun filenameFor(title: String, mime: String, index: Int): String {
        val ext = when (mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/3gpp" -> "3gp"
            "audio/amr" -> "amr"
            "audio/mp4", "audio/aac" -> "m4a"
            else -> null
        }
        val stem = title.substringBeforeLast('.').ifBlank { "media_$index" }
        return if (ext != null) "$stem.$ext" else title.ifBlank { "media_$index" }
    }

    private fun mmsErrorText(resultCode: Int): String = when (resultCode) {
        SmsManager.MMS_ERROR_UNSPECIFIED -> "unspecified error"
        SmsManager.MMS_ERROR_INVALID_APN -> "invalid APN"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "couldn't connect to the carrier MMS server"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "HTTP failure"
        SmsManager.MMS_ERROR_IO_ERROR -> "I/O error"
        SmsManager.MMS_ERROR_RETRY -> "carrier asked to retry"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "carrier MMS configuration error"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "no mobile data"
        else -> "code $resultCode"
    }

    private fun readBytesFromUri(ctx: Context, uri: Uri): ByteArray? {
        return runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).readBytes() }
                else -> ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull()
    }

    /**
     * Makes an image fit the carrier's limits. JPEG, PNG and GIF that already fit (bytes and
     * dimensions) go out untouched. Everything else (HEIC, WebP, oversized photos) is
     * re-encoded as JPEG, with EXIF rotation applied so photos don't arrive sideways.
     */
    private fun prepareImageForMms(
        rawBytes: ByteArray,
        originalMime: String,
        budgetBytes: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<String, ByteArray> {
        val mime = originalMime.lowercase()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val widelySupported = mime == "image/jpeg" || mime == "image/png" || mime == "image/gif"
        val fitsDims = bounds.outWidth in 1..maxWidth && bounds.outHeight in 1..maxHeight
        if (widelySupported && fitsDims && rawBytes.size <= budgetBytes) {
            return mime to rawBytes
        }

        return runCatching {
            val rotation = exifRotationDegrees(rawBytes)
            var sampleSize = 1
            while ((bounds.outWidth / (sampleSize * 2)) >= maxWidth || (bounds.outHeight / (sampleSize * 2)) >= maxHeight) {
                sampleSize *= 2
            }
            var bitmap = BitmapFactory.decodeByteArray(
                rawBytes, 0, rawBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return mime to rawBytes

            // Exact downscale to the carrier's max dimensions
            val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height, 1f)
            if (scale < 1f || rotation != 0) {
                val matrix = Matrix().apply {
                    if (scale < 1f) postScale(scale, scale)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed != bitmap) bitmap.recycle()
                bitmap = transformed
            }

            var quality = 85
            var outBytes: ByteArray
            while (true) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                outBytes = baos.toByteArray()
                if (outBytes.size <= budgetBytes) break
                if (quality > 40) {
                    quality -= 10
                } else {
                    // Still too big at low quality: shrink the picture and try again
                    val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.75f).toInt().coerceAtLeast(1), (bitmap.height * 0.75f).toInt().coerceAtLeast(1), true)
                    if (smaller != bitmap) bitmap.recycle()
                    bitmap = smaller
                    quality = 75
                    if (bitmap.width < 160) break
                }
            }
            bitmap.recycle()
            "image/jpeg" to outBytes
        }.getOrElse { mime to rawBytes }
    }

    private fun exifRotationDegrees(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

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
