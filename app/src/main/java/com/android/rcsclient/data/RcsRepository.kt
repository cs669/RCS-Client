package com.android.rcsclient.data

import com.android.rcsclient.model.ConversationType
import com.android.rcsclient.model.DeliveryStatus
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.MediaAttachment
import com.android.rcsclient.model.MessageReaction
import com.android.rcsclient.model.Participant
import com.android.rcsclient.model.RcsConversation
import com.android.rcsclient.model.RcsMessage
import com.android.rcsclient.model.RichCard
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.network.NotConnectedRcsTransport
import com.android.rcsclient.network.PhoneNumbers
import com.android.rcsclient.network.RcsClientConfig
import com.android.rcsclient.network.RcsTransport
import com.android.rcsclient.telephony.CarrierImsRcsManager
import com.android.rcsclient.telephony.IncomingMms
import com.android.rcsclient.telephony.SystemMessage
import com.android.rcsclient.telephony.SystemMessageStore
import com.android.rcsclient.telephony.SystemThread
import android.telephony.SubscriptionManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Central repository for conversations and messages.
 *
 * Sending goes through [rcsTransport] when RCS is really registered, and through the phone's
 * cellular SMS otherwise. Nothing here simulates replies, delivery receipts or encryption.
 */
class RcsRepository(
    private val dbHelper: RcsDatabaseHelper?,
    val imsRcsManager: CarrierImsRcsManager,
    val rcsTransport: RcsTransport = NotConnectedRcsTransport,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Android's shared SMS/MMS storage. Null in unit tests that don't care about it. */
    private val systemStore: SystemMessageStore? = null,
    /** One at a time, so a message is always saved before its status update is applied. */
    private val storeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {

    private val _conversations = MutableStateFlow<List<RcsConversation>>(emptyList())
    val conversations: StateFlow<List<RcsConversation>> = _conversations.asStateFlow()

    private val _messagesByConversation = MutableStateFlow<Map<String, List<RcsMessage>>>(emptyMap())
    val messagesByConversation: StateFlow<Map<String, List<RcsMessage>>> = _messagesByConversation.asStateFlow()

    private val _imsStatus = MutableStateFlow(imsRcsManager.queryImsRegistrationStatus(rcsTransport))
    val imsStatus: StateFlow<ImsRegistrationStatus> = _imsStatus.asStateFlow()

    private val _networkConfig = MutableStateFlow(RcsClientConfig(localPhoneNumber = imsRcsManager.loadOwnPhoneNumber()))
    val networkConfig: StateFlow<RcsClientConfig> = _networkConfig.asStateFlow()

    private val _lastTransportEvent = MutableStateFlow<String?>(null)
    val lastTransportEvent: StateFlow<String?> = _lastTransportEvent.asStateFlow()

    private val importRunning = AtomicBoolean(false)

    init {
        loadPersistedData()
        importSystemHistoryIfNeeded()
    }

    /**
     * Loads saved conversations. Older builds seeded fake demo chats (an airline bot, sample
     * groups) that were never real recipients, so those get deleted here on first launch.
     */
    private fun loadPersistedData() {
        val persisted = dbHelper?.loadAllConversations().orEmpty()
        val (real, demo) = persisted.partition { it.isRealRecipient }
        demo.forEach { dbHelper?.deleteConversation(it.id) }
        val realIds = real.map { it.id }.toSet()
        _conversations.value = sortConversations(real)
        _messagesByConversation.value = dbHelper?.loadAllMessagesByConversation().orEmpty()
            .filterKeys { it in realIds }

        // Anything still "Sending" from before the app last closed, and older than the MMS timeout,
        // is never going to finish. Show it as failed instead of leaving it hanging.
        val cutoff = System.currentTimeMillis() - MMS_SEND_TIMEOUT_MS
        _messagesByConversation.value.forEach { (convId, msgs) ->
            msgs.filter { it.deliveryStatus == DeliveryStatus.SENDING && it.timestampMs < cutoff }
                .forEach { updateMessageStatus(convId, it.id, DeliveryStatus.FAILED) }
        }
    }

    fun updateNetworkConfig(newConfig: RcsClientConfig) {
        _networkConfig.value = newConfig
        imsRcsManager.saveOwnPhoneNumber(newConfig.localPhoneNumber)
    }

    fun loadDeviceContacts() = imsRcsManager.loadDeviceContacts()

    fun probeHardwareUceForNumber(phone: String) = imsRcsManager.probeRealHardwareUce(phone)

    fun clearTransportEvent() {
        _lastTransportEvent.value = null
    }

    fun refreshImsAndRoleStatus() {
        _imsStatus.value = imsRcsManager.queryImsRegistrationStatus(rcsTransport)
        // Becoming the default SMS app is what unlocks the shared storage, so check again here.
        importSystemHistoryIfNeeded()
    }

    fun markConversationRead(conversationId: String) {
        var updatedConv: RcsConversation? = null
        _conversations.update { list ->
            list.map { conv ->
                if (conv.id == conversationId && conv.unreadCount > 0) {
                    val copy = conv.copy(unreadCount = 0)
                    updatedConv = copy
                    copy
                } else {
                    conv
                }
            }
        }
        updatedConv?.let { dbHelper?.saveConversation(it) }

        val conv = _conversations.value.find { it.id == conversationId } ?: return
        val inSystemStore = _messagesByConversation.value[conversationId].orEmpty().any { it.systemUri != null }
        if (inSystemStore) {
            withSystemStore { store -> store.markThreadRead(conversationPhones(conv)) }
        }
    }

    fun togglePinConversation(conversationId: String) {
        var updatedConv: RcsConversation? = null
        _conversations.update { list ->
            sortConversations(
                list.map { conv ->
                    if (conv.id == conversationId) {
                        val copy = conv.copy(isPinned = !conv.isPinned)
                        updatedConv = copy
                        copy
                    } else {
                        conv
                    }
                }
            )
        }
        updatedConv?.let { dbHelper?.saveConversation(it) }
    }

    fun setConversationPreferredProtocol(conversationId: String, protocol: TransportProtocol) {
        var updatedConv: RcsConversation? = null
        _conversations.update { list ->
            list.map { conv ->
                if (conv.id == conversationId) {
                    val copy = conv.copy(preferredProtocol = protocol, isE2eeEnabled = false)
                    updatedConv = copy
                    copy
                } else {
                    conv
                }
            }
        }
        updatedConv?.let { dbHelper?.saveConversation(it) }
    }

    /**
     * Sends an outgoing message.
     *
     * RCS is used only when [rcsTransport] is really registered and the contact is known to
     * support chat. Otherwise text goes out as cellular SMS. MMS (attachments, group texts)
     * isn't built yet, so those fail with a clear reason instead of pretending to send.
     */
    fun sendMessage(
        conversationId: String,
        body: String,
        attachments: List<MediaAttachment> = emptyList(),
        richCards: List<RichCard> = emptyList(),
        replyToMessage: RcsMessage? = null,
        forceProtocol: TransportProtocol? = null
    ): RcsMessage? {
        val conv = _conversations.value.find { it.id == conversationId } ?: return null
        val trimmedBody = body.trim()
        if (trimmedBody.isEmpty() && attachments.isEmpty() && richCards.isEmpty()) return null

        val isGroupThread = conv.participants.size > 1 || conv.conversationType == ConversationType.GROUP_RCS
        val effectiveProtocol = when {
            forceProtocol != null -> forceProtocol
            isGroupThread && !rcsTransport.isRegistered -> TransportProtocol.MMS
            else -> imsRcsManager.selectTransportProtocol(
                isImsRegistered = rcsTransport.isRegistered,
                preferredProtocol = conv.preferredProtocol,
                recipientCapabilities = conv.capabilities,
                autoSmsFallback = conv.autoSmsFallback,
                hasMediaAttachments = attachments.isNotEmpty() || richCards.isNotEmpty()
            )
        }

        val now = System.currentTimeMillis()
        val effectiveText = when {
            trimmedBody.isNotEmpty() -> trimmedBody
            attachments.isNotEmpty() -> "📎 ${attachments.first().title}"
            richCards.isNotEmpty() -> richCards.first().title
            else -> ""
        }

        val outgoingMessage = RcsMessage(
            conversationId = conversationId,
            senderId = "me",
            senderName = "You",
            senderPhone = _networkConfig.value.localPhoneNumber,
            body = trimmedBody,
            timestampMs = now,
            isOutgoing = true,
            protocol = effectiveProtocol,
            deliveryStatus = DeliveryStatus.SENDING,
            replyToMessageId = replyToMessage?.id,
            replyToSenderName = replyToMessage?.senderName,
            replyToPreviewText = replyToMessage?.body?.takeIf { it.isNotBlank() }
                ?: replyToMessage?.attachments?.firstOrNull()?.title,
            attachments = attachments,
            richCards = richCards
        )

        appendMessageInternal(outgoingMessage)
        updateConversationPreview(conversationId, effectiveText, now, clearUnread = true)
        dispatch(conv, outgoingMessage, effectiveText, effectiveProtocol)
        return outgoingMessage
    }

    /**
     * Resolves a user-picked `content://` media URI into a persistent `MediaAttachment`
     * and dispatches it over MMS (or RCS if registered).
     */
    fun sendMediaUriMessage(
        conversationId: String,
        uriString: String,
        captionText: String = "",
        replyToMessage: RcsMessage? = null
    ): RcsMessage? {
        val attachment = imsRcsManager.resolveAttachmentFromUri(uriString)
        if (attachment == null) {
            _lastTransportEvent.value = "Could not read selected attachment file"
            return null
        }
        return sendMessage(
            conversationId = conversationId,
            body = captionText,
            attachments = listOf(attachment),
            replyToMessage = replyToMessage
        )
    }

    private fun dispatch(
        conv: RcsConversation,
        message: RcsMessage,
        effectiveText: String,
        protocol: TransportProtocol
    ) {
        val ownNumber = _networkConfig.value.localPhoneNumber
        val recipients = conv.participants.map { it.phoneNumber }
            .filterNot { ownNumber.isNotBlank() && PhoneNumbers.sameNumber(it, ownNumber) }
            .ifEmpty { listOf(conv.subtitleOrPhone) }

        fun fail(reason: String) {
            updateMessageStatus(conv.id, message.id, DeliveryStatus.FAILED)
            _lastTransportEvent.value = reason
        }

        when {
            protocol == TransportProtocol.RCS_UP_2_7 -> {
                if (!rcsTransport.isRegistered) {
                    if (conv.autoSmsFallback) {
                        if (message.attachments.isNotEmpty() || recipients.size > 1) {
                            sendCellularMms(conv.id, message.id, recipients, message.body, message.attachments)
                        } else {
                            sendCellularSms(conv.id, message.id, recipients.first(), effectiveText)
                        }
                    } else {
                        fail("RCS isn't connected, and SMS/MMS fallback is off for this chat")
                    }
                    return
                }
                externalScope.launch {
                    rcsTransport.sendChatMessage(PhoneNumbers.normalizeToE164(recipients.first()), message)
                        .onSuccess { updateMessageStatus(conv.id, message.id, DeliveryStatus.SENT) }
                        .onFailure { err ->
                            if (conv.autoSmsFallback) {
                                _lastTransportEvent.value = "RCS send failed (${err.message}), falling back to SMS/MMS"
                                if (message.attachments.isNotEmpty() || recipients.size > 1) {
                                    sendCellularMms(conv.id, message.id, recipients, message.body, message.attachments)
                                } else {
                                    sendCellularSms(conv.id, message.id, recipients.first(), effectiveText)
                                }
                            } else {
                                fail("RCS send failed: ${err.message}")
                            }
                        }
                }
            }

            protocol == TransportProtocol.MMS || recipients.size > 1 || message.attachments.isNotEmpty() -> {
                sendCellularMms(conv.id, message.id, recipients, message.body, message.attachments)
            }

            else -> sendCellularSms(conv.id, message.id, recipients.first(), effectiveText)
        }
    }

    private fun sendCellularSms(
        conversationId: String,
        messageId: String,
        destinationPhone: String,
        text: String
    ) {
        saveOutgoingToSystemStore(conversationId, messageId) { store, msg ->
            store.insertSms(
                SystemMessageStore.Box.OUTBOX, destinationPhone, text, msg.timestampMs,
                read = true, subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )
        }
        imsRcsManager.dispatchRealCellularSms(
            messageId = messageId,
            destinationPhone = destinationPhone,
            text = text,
            onStatusUpdate = { newStatus, infoMessage ->
                updateMessageStatus(conversationId, messageId, newStatus)
                _lastTransportEvent.value = infoMessage
            }
        )
    }

    private fun sendCellularMms(
        conversationId: String,
        messageId: String,
        recipients: List<String>,
        bodyText: String,
        attachments: List<MediaAttachment>
    ) {
        saveOutgoingToSystemStore(conversationId, messageId) { store, msg ->
            store.insertMms(
                SystemMessageStore.Box.OUTBOX, recipients, null, recipients, bodyText, attachments, msg.timestampMs,
                read = true, subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )
        }
        val handedOff = imsRcsManager.dispatchRealCellularMms(
            messageId = messageId,
            recipients = recipients,
            bodyText = bodyText,
            attachments = attachments,
            onStatusUpdate = { newStatus, infoMessage ->
                updateMessageStatus(conversationId, messageId, newStatus)
                _lastTransportEvent.value = infoMessage
            }
        )
        if (handedOff) {
            // The phone's MMS service sometimes never answers, for example when the SIM has no
            // MMS APN. Without this the message would say "Sending" forever.
            externalScope.launch {
                delay(MMS_SEND_TIMEOUT_MS)
                if (statusOf(conversationId, messageId) == DeliveryStatus.SENDING) {
                    updateMessageStatus(conversationId, messageId, DeliveryStatus.FAILED)
                    _lastTransportEvent.value = MMS_TIMEOUT_TEXT
                }
            }
        }
    }

    private fun statusOf(conversationId: String, messageId: String): DeliveryStatus? =
        _messagesByConversation.value[conversationId]?.firstOrNull { it.id == messageId }?.deliveryStatus

    /**
     * Toggles an emoji reaction on a message (GSMA UP 2.7 Reaction CPIM header).
     */
    fun toggleReaction(conversationId: String, messageId: String, emoji: String) {
        var updatedMsg: RcsMessage? = null
        _messagesByConversation.update { map ->
            val currentList = map[conversationId].orEmpty()
            val newList = currentList.map { msg ->
                if (msg.id == messageId) {
                    val existing = msg.reactions.find { it.senderId == "me" && it.emoji == emoji }
                    val updatedReactions = if (existing != null) {
                        msg.reactions.filterNot { it.senderId == "me" && it.emoji == emoji }
                    } else {
                        msg.reactions + MessageReaction(emoji = emoji, senderId = "me", senderName = "You")
                    }
                    val copy = msg.copy(reactions = updatedReactions)
                    updatedMsg = copy
                    copy
                } else {
                    msg
                }
            }
            map + (conversationId to newList)
        }
        updatedMsg?.let { dbHelper?.saveMessage(it) }
    }

    /**
     * Opens the existing conversation for a phone number, or creates a new one.
     */
    fun createOrOpenConversation(
        title: String,
        phoneOrGroupPhones: String,
        isGroup: Boolean,
        preferredProtocolOverride: TransportProtocol? = null
    ): RcsConversation {
        val phones = phoneOrGroupPhones.split(",")
            .filter { it.any { ch -> ch.isDigit() } }
            .map { PhoneNumbers.normalizeToE164(it.trim()) }

        val primaryPhone = phones.firstOrNull() ?: phoneOrGroupPhones.trim()

        if (!isGroup) {
            val existing = _conversations.value.find { PhoneNumbers.sameNumber(it.subtitleOrPhone, primaryPhone) }
            if (existing != null) {
                if (preferredProtocolOverride == null || preferredProtocolOverride == existing.preferredProtocol) {
                    return existing
                }
                val updated = existing.copy(preferredProtocol = preferredProtocolOverride)
                _conversations.update { list -> list.map { if (it.id == existing.id) updated else it } }
                dbHelper?.saveConversation(updated)
                return updated
            }
        }

        val capabilities = imsRcsManager.resolveContactCapabilities(primaryPhone)
        val protocol = preferredProtocolOverride ?: if (capabilities.supportsSessionChat && rcsTransport.isRegistered) {
            TransportProtocol.RCS_UP_2_7
        } else {
            TransportProtocol.SMS
        }

        val now = System.currentTimeMillis()
        val effectivePhones = if (phones.isEmpty()) listOf(primaryPhone) else phones
        val participants = effectivePhones.mapIndexed { idx, p ->
            Participant(
                id = "participant-$idx-$now",
                displayName = if (effectivePhones.size == 1 && title.isNotBlank()) title else p,
                phoneNumber = p,
                avatarColorHex = listOf(0xFF0B57D0, 0xFF006A60, 0xFF6750A4, 0xFFB3261E)[idx % 4],
                capabilities = imsRcsManager.resolveContactCapabilities(p)
            )
        }

        val displayTitle = title.takeIf { it.isNotBlank() } ?: primaryPhone
        val newConv = RcsConversation(
            title = displayTitle,
            subtitleOrPhone = if (isGroup) "${participants.size + 1} participants" else primaryPhone,
            avatarColorHex = if (isGroup) 0xFF006A60 else 0xFF0B57D0,
            conversationType = if (isGroup) ConversationType.GROUP_RCS else ConversationType.ONE_TO_ONE,
            preferredProtocol = protocol,
            autoSmsFallback = true,
            participants = participants,
            isE2eeEnabled = false,
            lastMessagePreview = "",
            lastMessageTimestampMs = now,
            capabilities = capabilities,
            isRealRecipient = true
        )

        _conversations.update { list -> sortConversations(listOf(newConv) + list) }
        dbHelper?.saveConversation(newConv)
        return newConv
    }

    /**
     * Handles real incoming SMS/MMS broadcasts from `SmsDeliverReceiver` / `MmsWapPushDeliverReceiver`.
     */
    fun receiveIncomingSms(
        senderPhone: String,
        body: String,
        timestampMs: Long,
        isMms: Boolean = false
    ) {
        val existing = createOrOpenConversation(
            title = senderPhone,
            phoneOrGroupPhones = senderPhone,
            isGroup = false
        )

        val msg = RcsMessage(
            conversationId = existing.id,
            senderId = "external-$senderPhone",
            senderName = existing.title,
            senderPhone = senderPhone,
            body = body,
            timestampMs = timestampMs,
            isOutgoing = false,
            protocol = if (isMms) TransportProtocol.MMS else TransportProtocol.SMS,
            deliveryStatus = DeliveryStatus.DELIVERED
        )
        appendMessageInternal(msg)
        updateConversationPreview(existing.id, body, timestampMs, clearUnread = false)
        withSystemStore { store ->
            val uri = store.insertSms(
                SystemMessageStore.Box.INBOX, senderPhone, body, timestampMs,
                read = false, subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )
            if (uri != null) setSystemUri(existing.id, msg.id, uri)
        }
    }

    /**
     * Handles an incoming binary MMS WAP Push PDU (`M-Notification.ind` or `M-Retrieve.conf`)
     * from `MmsWapPushDeliverReceiver`, downloading the full carrier MMS via `SmsManager.downloadMultimediaMessage`
     * and ingesting its text and media attachments.
     */
    fun receiveIncomingWapPushMms(wapPushPdu: ByteArray, subId: Int) {
        imsRcsManager.handleIncomingWapPushMms(wapPushPdu, subId) { mms -> receiveIncomingMms(mms) }
    }

    /**
     * Threads a received MMS. Group MMS are recognised from the To and Cc lists: everyone on
     * the message except us and the sender. If that leaves anyone, it's a group thread made of
     * the sender plus those people, matched to an existing group with the same members.
     */
    fun receiveIncomingMms(mms: IncomingMms) {
        val senderPhone = mms.sender
        val ownNumber = _networkConfig.value.localPhoneNumber
        val others = mms.recipients
            .filterNot { ownNumber.isNotBlank() && PhoneNumbers.sameNumber(it, ownNumber) }
            .filterNot { PhoneNumbers.sameNumber(it, senderPhone) }
            .distinctBy { it.filter { ch -> ch.isDigit() }.removePrefix("1").ifBlank { it.lowercase() } }

        // Without our own number we can't tell which recipient is us. A 1:1 MMS then shows one
        // "other" recipient (us), so only treat it as a group when there are at least two.
        val isGroupMms = if (ownNumber.isNotBlank()) others.isNotEmpty() else others.size >= 2
        val members = if (isGroupMms) listOf(senderPhone) + others else listOf(senderPhone)

        val conversation = if (isGroupMms) {
            findGroupConversation(members) ?: createOrOpenConversation(
                title = "Group with ${members.size + 1}",
                phoneOrGroupPhones = members.joinToString(","),
                isGroup = true
            )
        } else {
            createOrOpenConversation(title = senderPhone, phoneOrGroupPhones = senderPhone, isGroup = false)
        }

        val preview = when {
            mms.body.isNotBlank() -> mms.body
            mms.attachments.isNotEmpty() -> "📎 ${mms.attachments.first().title}"
            else -> "[MMS]"
        }

        val msg = RcsMessage(
            conversationId = conversation.id,
            senderId = "external-$senderPhone",
            senderName = senderPhone,
            senderPhone = senderPhone,
            body = mms.body,
            timestampMs = mms.timestampMs,
            isOutgoing = false,
            protocol = TransportProtocol.MMS,
            deliveryStatus = if (mms.failed) DeliveryStatus.FAILED else DeliveryStatus.DELIVERED,
            attachments = mms.attachments
        )
        appendMessageInternal(msg)
        updateConversationPreview(conversation.id, preview, mms.timestampMs, clearUnread = false)
        if (!mms.failed) {
            withSystemStore { store ->
                val uri = store.insertMms(
                    SystemMessageStore.Box.INBOX, conversationPhones(conversation), senderPhone, mms.recipients, mms.body, mms.attachments,
                    mms.timestampMs, read = false, subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )
                if (uri != null) setSystemUri(conversation.id, msg.id, uri)
            }
        }
        _lastTransportEvent.value = if (mms.failed) mms.body else "Received MMS from $senderPhone"
    }

    /** Finds a group conversation whose members are exactly [phones], ignoring order and formatting. */
    private fun findGroupConversation(phones: List<String>): RcsConversation? {
        val ownNumber = _networkConfig.value.localPhoneNumber
        fun key(list: List<String>) = list
            .filterNot { ownNumber.isNotBlank() && PhoneNumbers.sameNumber(it, ownNumber) }
            .map { it.filter { ch -> ch.isDigit() }.removePrefix("1").ifBlank { it.lowercase() } }
            .toSet()
        val wanted = key(phones)
        return _conversations.value.firstOrNull { conv ->
            conv.participants.size > 1 && key(conv.participants.map { it.phoneNumber }) == wanted
        }
    }

    /** Updates a message's status when only its id is known (results arriving after a restart). */
    fun updateMessageStatusById(messageId: String, status: DeliveryStatus, info: String?) {
        val conversationId = _messagesByConversation.value.entries
            .firstOrNull { (_, msgs) -> msgs.any { it.id == messageId } }?.key ?: return
        updateMessageStatus(conversationId, messageId, status)
        if (info != null) _lastTransportEvent.value = info
    }

    fun sendQuickReplyToPhone(phone: String, text: String) {
        val conv = createOrOpenConversation(
            title = phone,
            phoneOrGroupPhones = phone,
            isGroup = false
        )
        sendMessage(conversationId = conv.id, body = text)
    }

    private fun setTypingIndicator(conversationId: String, isTyping: Boolean, senderName: String?) {
        _conversations.update { list ->
            list.map { conv ->
                if (conv.id == conversationId) {
                    conv.copy(isRecipientTyping = isTyping, typingSenderName = senderName)
                } else {
                    conv
                }
            }
        }
    }

    private fun updateMessageStatus(conversationId: String, messageId: String, status: DeliveryStatus) {
        var updatedMsg: RcsMessage? = null
        _messagesByConversation.update { map ->
            val current = map[conversationId].orEmpty()
            val updated = current.map { msg ->
                if (msg.id == messageId) {
                    val copy = msg.copy(deliveryStatus = status)
                    updatedMsg = copy
                    copy
                } else {
                    msg
                }
            }
            map + (conversationId to updated)
        }
        updatedMsg?.let { dbHelper?.saveMessage(it) }
        if (updatedMsg?.isOutgoing == true) {
            withSystemStore { syncStatusToSystemStore(it, conversationId, messageId) }
        }
    }

    private fun appendMessageInternal(message: RcsMessage) {
        _messagesByConversation.update { map ->
            val current = map[message.conversationId].orEmpty()
            map + (message.conversationId to (current + message))
        }
        dbHelper?.saveMessage(message)
    }

    private fun updateConversationPreview(
        conversationId: String,
        preview: String,
        timestampMs: Long,
        clearUnread: Boolean
    ) {
        var updatedConv: RcsConversation? = null
        _conversations.update { list ->
            sortConversations(
                list.map { conv ->
                    if (conv.id == conversationId) {
                        val copy = conv.copy(
                            lastMessagePreview = preview,
                            lastMessageTimestampMs = timestampMs,
                            unreadCount = if (clearUnread) 0 else conv.unreadCount + 1
                        )
                        updatedConv = copy
                        copy
                    } else {
                        conv
                    }
                }
            )
        }
        updatedConv?.let { dbHelper?.saveConversation(it) }
    }

    private fun sortConversations(list: List<RcsConversation>): List<RcsConversation> {
        return list.sortedWith(
            compareByDescending<RcsConversation> { it.isPinned }
                .thenByDescending { it.lastMessageTimestampMs }
        )
    }

    // ---- Android's shared SMS/MMS storage ----

    private fun findMessage(conversationId: String, messageId: String): RcsMessage? =
        _messagesByConversation.value[conversationId]?.firstOrNull { it.id == messageId }

    private fun conversationPhones(conv: RcsConversation): List<String> {
        val ownNumber = _networkConfig.value.localPhoneNumber
        return conv.participants.map { it.phoneNumber }
            .filterNot { ownNumber.isNotBlank() && PhoneNumbers.sameNumber(it, ownNumber) }
            .ifEmpty { listOf(conv.subtitleOrPhone) }
    }

    /** Runs [block] on the store's own thread, but only while we're allowed to write to it. */
    private fun withSystemStore(block: (SystemMessageStore) -> Unit) {
        val store = systemStore ?: return
        externalScope.launch(storeDispatcher) {
            if (store.isWritable()) runCatching { block(store) }
        }
    }

    private fun saveOutgoingToSystemStore(
        conversationId: String,
        messageId: String,
        insert: (SystemMessageStore, RcsMessage) -> String?
    ) {
        withSystemStore { store ->
            val msg = findMessage(conversationId, messageId) ?: return@withSystemStore
            if (msg.systemUri != null) return@withSystemStore // a retry or fallback of a saved message
            val uri = insert(store, msg) ?: return@withSystemStore
            setSystemUri(conversationId, messageId, uri)
            // The send result may already be in. Bring the stored copy up to date.
            syncStatusToSystemStore(store, conversationId, messageId)
        }
    }

    private fun setSystemUri(conversationId: String, messageId: String, uri: String) {
        var updatedMsg: RcsMessage? = null
        _messagesByConversation.update { map ->
            val current = map[conversationId].orEmpty()
            map + (conversationId to current.map { msg ->
                if (msg.id == messageId) msg.copy(systemUri = uri).also { updatedMsg = it } else msg
            })
        }
        updatedMsg?.let { dbHelper?.saveMessage(it) }
    }

    private fun syncStatusToSystemStore(store: SystemMessageStore, conversationId: String, messageId: String) {
        val msg = findMessage(conversationId, messageId) ?: return
        val uri = msg.systemUri ?: return
        store.updateBox(uri, boxFor(msg.deliveryStatus), delivered = msg.deliveryStatus == DeliveryStatus.DELIVERED)
    }

    private fun boxFor(status: DeliveryStatus): SystemMessageStore.Box = when (status) {
        DeliveryStatus.SENDING -> SystemMessageStore.Box.OUTBOX
        DeliveryStatus.FAILED -> SystemMessageStore.Box.FAILED
        else -> SystemMessageStore.Box.SENT
    }

    /**
     * One time, once we're the default SMS app: copy messages that only exist in this app into
     * the shared storage, then bring in everything already there (texts from Google Messages
     * or whatever app was used before) as conversations.
     */
    fun importSystemHistoryIfNeeded() {
        val store = systemStore ?: return
        if (!importRunning.compareAndSet(false, true)) return
        externalScope.launch(storeDispatcher) {
            try {
                if (!store.isWritable() || store.isImportDone()) return@launch
                exportAppOnlyMessages(store)
                val imported = mergeSystemHistory(store.loadHistory())
                store.markImportDone()
                if (imported > 0) _lastTransportEvent.value = "Imported $imported messages from your phone"
            } catch (t: Throwable) {
                _lastTransportEvent.value = "Couldn't import your existing messages: ${t.message}"
            } finally {
                importRunning.set(false)
            }
        }
    }

    private fun exportAppOnlyMessages(store: SystemMessageStore) {
        val subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        for (conv in _conversations.value) {
            val phones = conversationPhones(conv)
            for (msg in _messagesByConversation.value[conv.id].orEmpty()) {
                if (msg.systemUri != null || !wentOverCellular(msg)) continue
                val isMms = msg.protocol == TransportProtocol.MMS || msg.attachments.isNotEmpty() || phones.size > 1
                val box = if (msg.isOutgoing) boxFor(msg.deliveryStatus) else SystemMessageStore.Box.INBOX
                val uri = if (isMms) {
                    store.insertMms(
                        box, phones, if (msg.isOutgoing) null else msg.senderPhone,
                        if (msg.isOutgoing) phones else phones.filterNot { PhoneNumbers.sameNumber(it, msg.senderPhone) },
                        msg.body, msg.attachments, msg.timestampMs, read = true, subId = subId
                    )
                } else {
                    store.insertSms(
                        box, if (msg.isOutgoing) phones.first() else msg.senderPhone,
                        msg.body, msg.timestampMs, read = true, subId = subId
                    )
                }
                if (uri != null) setSystemUri(conv.id, msg.id, uri)
            }
        }
    }

    private fun wentOverCellular(msg: RcsMessage): Boolean = when {
        msg.protocol == TransportProtocol.SMS || msg.protocol == TransportProtocol.MMS -> true
        msg.isOutgoing && msg.deliveryStatus == DeliveryStatus.SMS_FALLBACK_SENT -> true
        else -> false
    }

    /** Adds history from the shared storage that we don't already have. Returns how many. */
    internal fun mergeSystemHistory(threads: List<SystemThread>): Int {
        val known = _messagesByConversation.value.values.flatten().mapNotNull { it.systemUri }.toHashSet()
        val ownNumber = _networkConfig.value.localPhoneNumber
        var total = 0

        for (thread in threads) {
            val fresh = thread.messages.filter { it.uri !in known }
            if (fresh.isEmpty()) continue
            val people = thread.addresses
                .filterNot { ownNumber.isNotBlank() && PhoneNumbers.sameNumber(it, ownNumber) }
                .ifEmpty { thread.addresses }
            val conv = if (people.size > 1) {
                findGroupConversation(people) ?: createOrOpenConversation(
                    title = "Group with ${people.size + 1}",
                    phoneOrGroupPhones = people.joinToString(","),
                    isGroup = true
                )
            } else {
                createOrOpenConversation(title = people.first(), phoneOrGroupPhones = people.first(), isGroup = false)
            }

            val newMessages = fresh.map { toRcsMessage(conv.id, it, people.first()) }
            _messagesByConversation.update { map ->
                map + (conv.id to (map[conv.id].orEmpty() + newMessages).sortedBy { it.timestampMs })
            }
            dbHelper?.saveAll(emptyList(), mapOf(conv.id to newMessages)) // one transaction, much faster

            val last = _messagesByConversation.value[conv.id].orEmpty().lastOrNull()
            val unread = fresh.count { it.box == SystemMessageStore.Box.INBOX && !it.read }
            var updatedConv: RcsConversation? = null
            _conversations.update { list ->
                sortConversations(list.map { c ->
                    if (c.id != conv.id || last == null) c
                    else c.copy(
                        lastMessagePreview = previewOf(last),
                        lastMessageTimestampMs = last.timestampMs,
                        unreadCount = c.unreadCount + unread
                    ).also { updatedConv = it }
                })
            }
            updatedConv?.let { dbHelper?.saveConversation(it) }
            total += newMessages.size
        }
        return total
    }

    private fun toRcsMessage(conversationId: String, m: SystemMessage, fallbackSender: String): RcsMessage {
        val incoming = m.box == SystemMessageStore.Box.INBOX
        val sender = m.sender.ifBlank { fallbackSender }
        return RcsMessage(
            conversationId = conversationId,
            senderId = if (incoming) "external-$sender" else "me",
            senderName = if (incoming) sender else "You",
            senderPhone = if (incoming) sender else _networkConfig.value.localPhoneNumber,
            body = m.body,
            timestampMs = m.dateMs,
            isOutgoing = !incoming,
            protocol = if (m.isMms) TransportProtocol.MMS else TransportProtocol.SMS,
            deliveryStatus = when (m.box) {
                SystemMessageStore.Box.INBOX -> DeliveryStatus.DELIVERED
                SystemMessageStore.Box.SENT -> if (m.delivered) DeliveryStatus.DELIVERED else DeliveryStatus.SMS_FALLBACK_SENT
                // Anything still queued from another app isn't going anywhere now
                SystemMessageStore.Box.OUTBOX, SystemMessageStore.Box.FAILED -> DeliveryStatus.FAILED
            },
            attachments = m.attachments,
            systemUri = m.uri
        )
    }

    private fun previewOf(msg: RcsMessage): String = when {
        msg.body.isNotBlank() -> msg.body
        msg.attachments.isNotEmpty() -> "📎 ${msg.attachments.first().title}"
        else -> "[MMS]"
    }

    companion object {
        /** How long to wait for the phone's MMS service before calling a send failed. */
        const val MMS_SEND_TIMEOUT_MS = 5 * 60 * 1000L
        const val MMS_TIMEOUT_TEXT =
            "MMS timed out. The phone never got through to the carrier. Check mobile data and the SIM's MMS settings (APN)."
    }
}
