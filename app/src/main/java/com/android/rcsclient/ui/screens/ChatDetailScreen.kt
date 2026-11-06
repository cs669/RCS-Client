package com.android.rcsclient.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.rcsclient.model.ConversationType
import com.android.rcsclient.model.DeliveryStatus
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.RcsConversation
import com.android.rcsclient.model.RcsMessage
import com.android.rcsclient.model.SuggestedChip
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.ui.components.MediaAttachmentView
import com.android.rcsclient.ui.components.RichCardOrCarouselView
import com.android.rcsclient.ui.components.SuggestedChipsBar
import com.android.rcsclient.ui.theme.E2eeGreen
import com.android.rcsclient.ui.theme.SmsSlate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_REACTION_EMOJIS = listOf("❤️", "👍", "😂", "🔥", "🎉", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    conversation: RcsConversation,
    messages: List<RcsMessage>,
    imsStatus: ImsRegistrationStatus,
    replyingToMessage: RcsMessage?,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendHighResPhoto: (String?) -> Unit,
    onSendVoiceNote: () -> Unit,
    onSendGeoLocation: () -> Unit,
    onSendDocument: () -> Unit,
    onSendRichCard: () -> Unit,
    onRotateMlsEpoch: () -> Unit,
    onSuggestedChipClick: (SuggestedChip) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onSetReplyingTo: (RcsMessage?) -> Unit,
    onInspectMessageMls: (RcsMessage) -> Unit,
    onOpenMlsSecurityDialog: () -> Unit,
    onToggleProtocolMode: (TransportProtocol) -> Unit,
    onAttachmentActionClick: (String) -> Unit
) {
    var draftText by remember(conversation.id) { mutableStateOf("") }
    var showAttachmentTray by remember { mutableStateOf(false) }
    var activeReactionMessageId by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSendHighResPhoto(uri.toString())
        } else {
            onSendHighResPhoto(null)
        }
    }

    LaunchedEffect(messages.size, conversation.isRecipientTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isRcsActive = imsStatus.isImsRegistered &&
        conversation.capabilities.supportsSessionChat &&
        conversation.preferredProtocol == TransportProtocol.RCS_UP_2_7

    val latestSuggestedChips = remember(messages) {
        messages.lastOrNull { it.suggestedChips.isNotEmpty() }?.suggestedChips.orEmpty()
    }

    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }

                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(conversation.avatarColorHex)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = conversation.title.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Title & Status Subtitle
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenMlsSecurityDialog() }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = conversation.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (conversation.isVerifiedBusiness) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Verified,
                                        contentDescription = "Verified Business",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            val subtitle = when {
                                conversation.isRecipientTyping ->
                                    "${conversation.typingSenderName ?: "Peer"} is typing…"
                                conversation.isVerifiedBusiness ->
                                    conversation.businessVerifierName ?: "Verified RBM Agent"
                                isRcsActive && conversation.isE2eeEnabled ->
                                    "RCS UP 2.7 • MLS E2EE (Epoch #${conversation.mlsEpoch})"
                                isRcsActive ->
                                    "RCS UP 2.7 • Online"
                                else ->
                                    "SMS • ${conversation.subtitleOrPhone}"
                            }

                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (conversation.isRecipientTyping) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // MLS E2EE Inspector Button
                        IconButton(onClick = onOpenMlsSecurityDialog) {
                            Icon(
                                imageVector = if (isRcsActive && conversation.isE2eeEnabled) {
                                    Icons.Default.VerifiedUser
                                } else {
                                    Icons.Default.LockOpen
                                },
                                contentDescription = "Encryption info",
                                tint = if (isRcsActive && conversation.isE2eeEnabled) {
                                    E2eeGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        // Protocol Switcher (RCS <-> SMS Fallback)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isRcsActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                SmsSlate.copy(alpha = 0.16f)
                            },
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clickable {
                                    val next = if (conversation.preferredProtocol == TransportProtocol.RCS_UP_2_7) {
                                        TransportProtocol.SMS
                                    } else {
                                        TransportProtocol.RCS_UP_2_7
                                    }
                                    onToggleProtocolMode(next)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "Switch Transport Protocol",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isRcsActive) MaterialTheme.colorScheme.primary else SmsSlate
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isRcsActive) "RCS" else "SMS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isRcsActive) MaterialTheme.colorScheme.primary else SmsSlate
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Messages Timeline
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Session Security / RBM Trust Banner at top of chat
                item(key = "session-header") {
                    SessionSecurityBanner(
                        conversation = conversation,
                        isRcsActive = isRcsActive,
                        onClick = onOpenMlsSecurityDialog
                    )
                }

                items(messages, key = { it.id }) { message ->
                    MessageBubbleItem(
                        message = message,
                        isGroupChat = conversation.conversationType == ConversationType.GROUP_RCS,
                        showReactionPicker = activeReactionMessageId == message.id,
                        onToggleReactionPicker = {
                            activeReactionMessageId = if (activeReactionMessageId == message.id) null else message.id
                        },
                        onSelectReaction = { emoji ->
                            onToggleReaction(message.id, emoji)
                            activeReactionMessageId = null
                        },
                        onReplyClick = { onSetReplyingTo(message) },
                        onInspectMlsEnvelope = { onInspectMessageMls(message) },
                        onRichCardChipClick = onSuggestedChipClick,
                        onAttachmentActionClick = onAttachmentActionClick
                    )
                }

                if (conversation.isRecipientTyping) {
                    item(key = "typing-indicator") {
                        TypingIndicatorBubble(senderName = conversation.typingSenderName ?: conversation.title)
                    }
                }
            }

            // Suggested Replies & Action Chips Bar
            if (latestSuggestedChips.isNotEmpty()) {
                SuggestedChipsBar(
                    chips = latestSuggestedChips,
                    onChipClick = onSuggestedChipClick
                )
            }

            // Quoted Reply Preview Banner
            AnimatedVisibility(visible = replyingToMessage != null) {
                replyingToMessage?.let { target ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${target.senderName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = target.body.ifBlank {
                                        target.attachments.firstOrNull()?.title ?: "Rich Card"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onSetReplyingTo(null) },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel Reply",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Expandable Rich Media & RCS Action Drawer
            AnimatedVisibility(visible = showAttachmentTray) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "RCS Universal Profile 2.7 Attachments & Actions",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AttachmentDrawerTile(
                                icon = Icons.Default.Image,
                                label = "HD Photo",
                                sublabel = "FT-HTTP 4K",
                                tint = Color(0xFF0B57D0),
                                onClick = {
                                    showAttachmentTray = false
                                    runCatching {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }.onFailure {
                                        onSendHighResPhoto(null)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AttachmentDrawerTile(
                                icon = Icons.Default.Mic,
                                label = "Voice Note",
                                sublabel = "Opus E2EE",
                                tint = Color(0xFF006A60),
                                onClick = {
                                    showAttachmentTray = false
                                    onSendVoiceNote()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AttachmentDrawerTile(
                                icon = Icons.Default.LocationOn,
                                label = "Geo-Push",
                                sublabel = "GSMA XML",
                                tint = Color(0xFFB3261E),
                                onClick = {
                                    showAttachmentTray = false
                                    onSendGeoLocation()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AttachmentDrawerTile(
                                icon = Icons.Default.Description,
                                label = "Document",
                                sublabel = "PDF / File",
                                tint = Color(0xFF6750A4),
                                onClick = {
                                    showAttachmentTray = false
                                    onSendDocument()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AttachmentDrawerTile(
                                icon = Icons.Default.ViewCarousel,
                                label = "Rich Card",
                                sublabel = "RBM Card",
                                tint = Color(0xFF00639B),
                                onClick = {
                                    showAttachmentTray = false
                                    onSendRichCard()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AttachmentDrawerTile(
                                icon = Icons.Default.Key,
                                label = "Rotate Key",
                                sublabel = "MLS Epoch+1",
                                tint = E2eeGreen,
                                onClick = {
                                    showAttachmentTray = false
                                    onRotateMlsEpoch()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Bottom Message Composer Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachments need MMS or RCS file transfer. Neither exists yet, so the button
                    // explains that instead of opening a tray of placeholder attachments.
                    IconButton(onClick = { onSendHighResPhoto(null) }) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Add attachment",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        placeholder = {
                            Text(
                                text = when {
                                    isRcsActive && conversation.isE2eeEnabled -> "RCS message (MLS E2EE)…"
                                    isRcsActive -> "RCS message…"
                                    else -> "Text message"
                                },
                                fontSize = 14.sp
                            )
                        },
                        trailingIcon = {
                            if (!isRcsActive) {
                                val remaining = 160 - (draftText.length % 160)
                                val segments = (draftText.length / 160) + 1
                                Text(
                                    text = if (segments > 1) "$remaining/$segments" else "$remaining",
                                    fontSize = 11.sp,
                                    color = SmsSlate,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                            } else if (conversation.isE2eeEnabled) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "MLS Encrypted",
                                    tint = E2eeGreen,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(16.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = if (isRcsActive) MaterialTheme.colorScheme.primary else SmsSlate,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        maxLines = 4,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Send or Quick Voice Note Button
                    val canSend = draftText.isNotBlank()
                    IconButton(
                        onClick = {
                            if (canSend) {
                                val toSend = draftText
                                draftText = ""
                                onSendMessage(toSend)
                            } else {
                                onSendVoiceNote()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRcsActive) MaterialTheme.colorScheme.primary else SmsSlate
                            )
                    ) {
                        Icon(
                            imageVector = if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                            contentDescription = if (canSend) "Send Message" else "Send Voice Note",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSecurityBanner(
    conversation: RcsConversation,
    isRcsActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when {
                    conversation.isVerifiedBusiness -> Icons.Default.Verified
                    isRcsActive && conversation.isE2eeEnabled -> Icons.Default.Lock
                    else -> Icons.Default.Sms
                },
                contentDescription = null,
                tint = when {
                    conversation.isVerifiedBusiness -> MaterialTheme.colorScheme.primary
                    isRcsActive && conversation.isE2eeEnabled -> E2eeGreen
                    else -> SmsSlate
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    conversation.isVerifiedBusiness ->
                        "Verified RBM Business Agent (${conversation.businessVerifierName ?: "GSMA Trust"})"
                    isRcsActive && conversation.isE2eeEnabled ->
                        "RCS Chat with ${conversation.title} is end-to-end encrypted (MLS Epoch #${conversation.mlsEpoch}). Tap to verify."
                    isRcsActive ->
                        "RCS Universal Profile 2.7 session active"
                    else ->
                        "Texting over SMS. SMS isn't end-to-end encrypted."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubbleItem(
    message: RcsMessage,
    isGroupChat: Boolean,
    showReactionPicker: Boolean,
    onToggleReactionPicker: () -> Unit,
    onSelectReaction: (String) -> Unit,
    onReplyClick: () -> Unit,
    onInspectMlsEnvelope: () -> Unit,
    onRichCardChipClick: (SuggestedChip) -> Unit,
    onAttachmentActionClick: (String) -> Unit
) {
    val isOutgoing = message.isOutgoing
    val isRcs = message.protocol == TransportProtocol.RCS_UP_2_7

    val bubbleBgColor = when {
        isOutgoing && isRcs -> MaterialTheme.colorScheme.primary
        isOutgoing && !isRcs -> SmsSlate
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val bubbleTextColor = if (isOutgoing) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        // Group Sender Name Header
        if (isGroupChat && !isOutgoing) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
            )
        }

        // Rich Cards or Carousels (rendered full-width or card-width above/inside message)
        if (message.richCards.isNotEmpty()) {
            RichCardOrCarouselView(
                cards = message.richCards,
                onChipClick = onRichCardChipClick,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Main Bubble
        Surface(
            color = bubbleBgColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isOutgoing) 18.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 18.dp
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = { onToggleReactionPicker() },
                    onLongClick = { onInspectMlsEnvelope() }
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Quoted Reply Box
                if (message.replyToPreviewText != null) {
                    Surface(
                        color = if (isOutgoing) {
                            Color.Black.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = message.replyToSenderName ?: "Reply",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = bubbleTextColor
                            )
                            Text(
                                text = message.replyToPreviewText,
                                fontSize = 11.sp,
                                color = bubbleTextColor.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Media Attachments
                if (message.attachments.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = if (message.body.isNotBlank()) 8.dp else 0.dp)
                    ) {
                        message.attachments.forEach { att ->
                            MediaAttachmentView(
                                attachment = att,
                                isOutgoing = isOutgoing,
                                onActionClick = onAttachmentActionClick
                            )
                        }
                    }
                }

                // Message Body Text
                if (message.body.isNotBlank()) {
                    Text(
                        text = message.body,
                        color = bubbleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Footer: Timestamp + Protocol + MLS E2EE Lock + IMDN Receipt
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable { onInspectMlsEnvelope() }
                ) {
                    if (message.mlsEnvelope != null) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "MLS E2EE Verified",
                            tint = bubbleTextColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }

                    Text(
                        text = "${message.protocol.shortBadge} • ${formatBubbleTime(message.timestampMs)}",
                        fontSize = 10.sp,
                        color = bubbleTextColor.copy(alpha = 0.78f)
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        DeliveryReceiptIcon(
                            status = message.deliveryStatus,
                            tint = bubbleTextColor.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // Displayed Reactions Row
        if (message.reactions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val grouped = message.reactions.groupBy { it.emoji }
                grouped.forEach { (emoji, list) ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.clickable { onSelectReaction(emoji) }
                    ) {
                        Text(
                            text = if (list.size > 1) "$emoji ${list.size}" else emoji,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Pop-up Quick Reaction & Reply Bar (when message is tapped)
        AnimatedVisibility(visible = showReactionPicker) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_REACTION_EMOJIS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onSelectReaction(emoji) }
                                .padding(4.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onReplyClick()
                            onToggleReactionPicker()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply to message",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            onInspectMlsEnvelope()
                            onToggleReactionPicker()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Inspect MLS Wire Envelope",
                            tint = E2eeGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryReceiptIcon(status: DeliveryStatus, tint: Color) {
    when (status) {
        DeliveryStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = status.label,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
        }
        DeliveryStatus.SENT, DeliveryStatus.SMS_FALLBACK_SENT -> {
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = status.label,
                tint = tint,
                modifier = Modifier.size(13.dp)
            )
        }
        DeliveryStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = status.label,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
        }
        DeliveryStatus.DISPLAYED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "IMDN Read",
                    tint = Color(0xFF96F7B4),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Read",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint
                )
            }
        }
        DeliveryStatus.FAILED -> {
            Text(
                text = "!",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TypingIndicatorBubble(senderName: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "•••  $senderName is composing via MSRP…",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AttachmentDrawerTile(
    icon: ImageVector,
    label: String,
    sublabel: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = sublabel,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun formatBubbleTime(timestampMs: Long): String {
    return SimpleDateFormat("h:mm a", Locale.US).format(Date(timestampMs))
}
