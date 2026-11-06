package com.android.rcsclient.model

import java.util.UUID

enum class TransportProtocol(val displayName: String, val shortBadge: String) {
    RCS_UP_2_7("RCS (Universal Profile 2.7)", "RCS"),
    SMS("SMS (Text Fallback)", "SMS"),
    MMS("MMS (Multimedia Fallback)", "MMS")
}

enum class ConversationType(val label: String) {
    ONE_TO_ONE("1-on-1 Chat"),
    GROUP_RCS("RCS Group"),
    RBM_BUSINESS("Verified Business (RBM)")
}

enum class DeliveryStatus(val label: String) {
    SENDING("Sending…"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    DISPLAYED("Read"),
    SMS_FALLBACK_SENT("Sent as SMS/MMS"),
    FAILED("Failed")
}

enum class AttachmentType(val label: String) {
    HIGH_RES_IMAGE("HD Photo (FT-HTTP)"),
    HIGH_RES_VIDEO("HD Video (FT-HTTP)"),
    VOICE_NOTE("Audio Voice Note"),
    GEO_LOCATION("GSMA Geolocation Push"),
    DOCUMENT_FILE("Document (FT-HTTP)")
}

enum class SuggestionType {
    REPLY,
    OPEN_URL,
    DIAL_PHONE,
    VIEW_LOCATION,
    CALENDAR_EVENT,
    REQUEST_LOCATION
}

/**
 * What we know about a contact's RCS support. Everything defaults to false because this client
 * has no capability exchange (SIP OPTIONS / presence) yet, so we genuinely don't know.
 */
data class RcsContactCapabilities(
    val supportsSessionChat: Boolean = false,
    val supportsGroupChat: Boolean = false,
    val supportsFileTransferHttp: Boolean = false,
    val supportsGeoPush: Boolean = false,
    val supportsRbm: Boolean = false,
    val supportsMlsE2ee: Boolean = false,
    val isOnline: Boolean = false,
    val lastCapExchangeTimeMs: Long = 0L
)

/**
 * Kept only so older saved messages still deserialize. Nothing creates these any more,
 * because the old engine wasn't real MLS. Real RFC 9420 MLS comes with GSMA UP E2EE later.
 */
data class MlsEnvelope(
    val groupId: String,
    val epoch: Int,
    val cipherSuite: String = "MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519",
    val senderLeafIndex: Int = 0,
    val ciphertextBase64: String,
    val ivBase64: String,
    val authTagHex: String,
    val treeHashHex: String,
    val verified: Boolean = false
)

data class MediaAttachment(
    val id: String = UUID.randomUUID().toString(),
    val type: AttachmentType,
    val title: String,
    val subtitle: String = "",
    val mimeType: String = "application/octet-stream",
    val fileSizeBytes: Long = 0L,
    val imageResSeed: Int = 0,
    val uriString: String? = null,
    val durationSeconds: Int = 0,
    val waveformAmplitudes: List<Float> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val addressLabel: String? = null
)

data class SuggestedChip(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: SuggestionType = SuggestionType.REPLY,
    val actionPayload: String = ""
)

data class RichCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val badgeText: String = "",
    val headerIconType: String = "FLIGHT", // FLIGHT, PACKAGE, STORE, EVENT, SECURITY
    val accentColorHex: Long = 0xFF0B57D0,
    val priceOrStatusText: String = "",
    val suggestions: List<SuggestedChip> = emptyList()
)

data class MessageReaction(
    val emoji: String,
    val senderId: String,
    val senderName: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class Participant(
    val id: String,
    val displayName: String,
    val phoneNumber: String,
    val avatarColorHex: Long = 0xFF0B57D0,
    val isAdmin: Boolean = false,
    val capabilities: RcsContactCapabilities = RcsContactCapabilities()
)

data class RcsMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String, // "me" for outgoing
    val senderName: String,
    val senderPhone: String,
    val body: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val protocol: TransportProtocol = TransportProtocol.RCS_UP_2_7,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.DELIVERED,
    val replyToMessageId: String? = null,
    val replyToSenderName: String? = null,
    val replyToPreviewText: String? = null,
    val attachments: List<MediaAttachment> = emptyList(),
    val richCards: List<RichCard> = emptyList(),
    val suggestedChips: List<SuggestedChip> = emptyList(),
    val reactions: List<MessageReaction> = emptyList(),
    val mlsEnvelope: MlsEnvelope? = null
)

data class RcsConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subtitleOrPhone: String,
    val avatarColorHex: Long = 0xFF0B57D0,
    val conversationType: ConversationType = ConversationType.ONE_TO_ONE,
    val preferredProtocol: TransportProtocol = TransportProtocol.SMS,
    val autoSmsFallback: Boolean = true,
    val isVerifiedBusiness: Boolean = false,
    val businessVerifierName: String? = null,
    val businessCategory: String? = null,
    val participants: List<Participant> = emptyList(),
    val isE2eeEnabled: Boolean = false,
    val mlsGroupId: String = "mls-group-${UUID.randomUUID().toString().take(8)}",
    val mlsEpoch: Int = 1,
    val safetyNumber: String = "",
    val isRecipientTyping: Boolean = false,
    val typingSenderName: String? = null,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessagePreview: String = "",
    val lastMessageTimestampMs: Long = System.currentTimeMillis(),
    val capabilities: RcsContactCapabilities = RcsContactCapabilities(),
    val isRealRecipient: Boolean = false,
    val peerEndpointOverride: String = ""
)

/**
 * Real RCS / telephony state for the UI. `isImsRegistered` only turns true once a real RCS
 * stack has provisioned (ACS) and registered (SIP REGISTER). Until then it's false.
 */
data class ImsRegistrationStatus(
    val isImsRegistered: Boolean = false,
    val imsTransportType: String = "None (no RCS stack yet)",
    val carrierName: String = "Unknown carrier",
    val subscriptionId: Int = -1,
    val acsProvisioningState: String = "Not provisioned",
    val ucePresenceEnabled: Boolean = false,
    val mlsKeyPackagePublished: Boolean = false,
    val isHardwareImsBacked: Boolean = false,
    val defaultSmsAppGranted: Boolean = false
)
