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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _conversations = MutableStateFlow<List<RcsConversation>>(emptyList())
    val conversations: StateFlow<List<RcsConversation>> = _conversations.asStateFlow()

    private val _messagesByConversation = MutableStateFlow<Map<String, List<RcsMessage>>>(emptyMap())
    val messagesByConversation: StateFlow<Map<String, List<RcsMessage>>> = _messagesByConversation.asStateFlow()

    private val _imsStatus = MutableStateFlow(imsRcsManager.queryImsRegistrationStatus(rcsTransport))
    val imsStatus: StateFlow<ImsRegistrationStatus> = _imsStatus.asStateFlow()

    private val _networkConfig = MutableStateFlow(RcsClientConfig())
    val networkConfig: StateFlow<RcsClientConfig> = _networkConfig.asStateFlow()

    private val _lastTransportEvent = MutableStateFlow<String?>(null)
    val lastTransportEvent: StateFlow<String?> = _lastTransportEvent.asStateFlow()

    init {
        loadPersistedData()
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
    }

    fun updateNetworkConfig(newConfig: RcsClientConfig) {
        _networkConfig.value = newConfig
    }

    fun loadDeviceContacts() = imsRcsManager.loadDeviceContacts()

    fun probeHardwareUceForNumber(phone: String) = imsRcsManager.probeRealHardwareUce(phone)

    fun clearTransportEvent() {
        _lastTransportEvent.value = null
    }

    fun refreshImsAndRoleStatus() {
        _imsStatus.value = imsRcsManager.queryImsRegistrationStatus(rcsTransport)
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

        val effectiveProtocol = forceProtocol ?: imsRcsManager.selectTransportProtocol(
            isImsRegistered = rcsTransport.isRegistered,
            preferredProtocol = conv.preferredProtocol,
            recipientCapabilities = conv.capabilities,
            autoSmsFallback = conv.autoSmsFallback,
            hasMediaAttachments = attachments.isNotEmpty() || richCards.isNotEmpty()
        )

        val now = System.currentTimeMillis()
        val effectiveText = when {
            trimmedBody.isNotEmpty() -> trimmedBody
            attachments.isNotEmpty() -> attachments.first().title
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

    private fun dispatch(
        conv: RcsConversation,
        message: RcsMessage,
        effectiveText: String,
        protocol: TransportProtocol
    ) {
        val recipients = conv.participants.map { it.phoneNumber }.ifEmpty { listOf(conv.subtitleOrPhone) }

        fun fail(reason: String) {
            updateMessageStatus(conv.id, message.id, DeliveryStatus.FAILED)
            _lastTransportEvent.value = reason
        }

        when {
            recipients.size > 1 || conv.conversationType != ConversationType.ONE_TO_ONE ->
                fail("Group messages need MMS or RCS group chat, and neither is built yet")

            protocol == TransportProtocol.RCS_UP_2_7 -> {
                if (!rcsTransport.isRegistered) {
                    fail("RCS isn't connected, and SMS fallback is off for this chat")
                    return
                }
                externalScope.launch {
                    rcsTransport.sendChatMessage(PhoneNumbers.normalizeToE164(recipients.first()), message)
                        .onSuccess { updateMessageStatus(conv.id, message.id, DeliveryStatus.SENT) }
                        .onFailure { err ->
                            if (conv.autoSmsFallback && message.attachments.isEmpty() && message.richCards.isEmpty()) {
                                _lastTransportEvent.value = "RCS send failed (${err.message}), sending as SMS"
                                sendCellularSms(conv.id, message.id, recipients.first(), effectiveText)
                            } else {
                                fail("RCS send failed: ${err.message}")
                            }
                        }
                }
            }

            protocol == TransportProtocol.MMS ->
                fail("Attachments need MMS, which isn't built yet")

            else -> sendCellularSms(conv.id, message.id, recipients.first(), effectiveText)
        }
    }

    private fun sendCellularSms(
        conversationId: String,
        messageId: String,
        destinationPhone: String,
        text: String
    ) {
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
}
