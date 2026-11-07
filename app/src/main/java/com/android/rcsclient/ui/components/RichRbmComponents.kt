package com.android.rcsclient.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.rcsclient.model.AttachmentType
import com.android.rcsclient.model.MediaAttachment
import com.android.rcsclient.model.RichCard
import com.android.rcsclient.model.SuggestedChip
import com.android.rcsclient.model.SuggestionType
import java.io.File
import kotlinx.coroutines.delay

/**
 * Renders either a standalone RBM Rich Card or a horizontally scrollable Rich Card Carousel.
 */
@Composable
fun RichCardOrCarouselView(
    cards: List<RichCard>,
    onChipClick: (SuggestedChip) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return

    if (cards.size == 1) {
        SingleRichCardItem(
            card = cards.first(),
            onChipClick = onChipClick,
            modifier = modifier.fillMaxWidth()
        )
    } else {
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(cards, key = { it.id }) { card ->
                SingleRichCardItem(
                    card = card,
                    onChipClick = onChipClick,
                    modifier = Modifier.width(276.dp)
                )
            }
        }
    }
}

@Composable
fun SingleRichCardItem(
    card: RichCard,
    onChipClick: (SuggestedChip) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(card.accentColorHex)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column {
            // Rich Card Media Header Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.72f)
                            )
                        )
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = card.badgeText.ifBlank { "RBM RICH CARD" },
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Icon(
                        imageVector = when (card.headerIconType) {
                            "FLIGHT" -> Icons.Default.FlightTakeoff
                            "PACKAGE" -> Icons.Default.LocalShipping
                            "STORE" -> Icons.Default.ShoppingBag
                            else -> Icons.Default.Star
                        },
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                if (card.priceOrStatusText.isNotBlank()) {
                    Text(
                        text = card.priceOrStatusText,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }
            }

            // Card Body
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                if (card.suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    card.suggestions.forEach { chip ->
                        FilledTonalButton(
                            onClick = { onChipClick(chip) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = iconForSuggestionType(chip.type),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = chip.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders media attachments inside a message bubble:
 * - High-Res Image / Video (FT-HTTP)
 * - Voice Note with interactive Play/Pause waveform
 * - GSMA Geolocation Push card
 * - Document / PDF File
 */
@Composable
fun MediaAttachmentView(
    attachment: MediaAttachment,
    isOutgoing: Boolean,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (attachment.type) {
        AttachmentType.HIGH_RES_IMAGE, AttachmentType.HIGH_RES_VIDEO -> {
            HighResPhotoAttachmentCard(attachment, onActionClick, modifier)
        }
        AttachmentType.VOICE_NOTE -> {
            VoiceNoteAttachmentCard(attachment, isOutgoing, modifier)
        }
        AttachmentType.GEO_LOCATION -> {
            GeoLocationAttachmentCard(attachment, onActionClick, modifier)
        }
        AttachmentType.DOCUMENT_FILE -> {
            DocumentAttachmentCard(attachment, isOutgoing, onActionClick, modifier)
        }
    }
}

@Composable
private fun HighResPhotoAttachmentCard(
    attachment: MediaAttachment,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val loadedBitmap = remember(attachment.uriString) {
        val uriStr = attachment.uriString
        if (!uriStr.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(uriStr)
                when (uri.scheme) {
                    "file" -> uri.path?.let { BitmapFactory.decodeFile(File(it).absolutePath) }
                    else -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        } else null
    }

    val palettes = listOf(
        listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
        listOf(Color(0xFF1A2980), Color(0xFF26D0CE)),
        listOf(Color(0xFFEB3349), Color(0xFFF45C43)),
        listOf(Color(0xFF134E5E), Color(0xFF71B280)),
        listOf(Color(0xFF4776E6), Color(0xFF8E54E9))
    )
    val chosenGradient = palettes[attachment.imageResSeed % palettes.size]

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onActionClick("Attachment: ${attachment.title}") },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp)
                .background(Brush.linearGradient(chosenGradient))
        ) {
            if (loadedBitmap != null) {
                Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = attachment.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Subtle horizon & sun illustration in Canvas when no bitmap file is present
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f),
                        radius = size.minDimension * 0.22f,
                        center = Offset(size.width * 0.78f, size.height * 0.32f)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.1f),
                        radius = size.minDimension * 0.35f,
                        center = Offset(size.width * 0.78f, size.height * 0.32f)
                    )
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (loadedBitmap != null) "MMS PHOTO" else "MEDIA ATTACHMENT",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = attachment.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = attachment.subtitle,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun VoiceNoteAttachmentCard(
    attachment: MediaAttachment,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(100)
            progress += 0.04f
            if (progress >= 1f) {
                progress = 0f
                isPlaying = false
            }
        }
    }

    val containerBg = if (isOutgoing) {
        Color.White.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentTint = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerBg,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isPlaying = !isPlaying },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause Voice Note" else "Play Voice Note",
                    tint = if (isOutgoing) MaterialTheme.colorScheme.primary else Color.White
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                val samples = attachment.waveformAmplitudes.ifEmpty {
                    listOf(0.3f, 0.7f, 0.5f, 0.9f, 0.4f, 0.8f, 0.6f, 0.3f, 0.75f, 0.5f)
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                ) {
                    val barCount = samples.size
                    val spacing = 4.dp.toPx()
                    val totalSpacing = spacing * (barCount - 1)
                    val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(3f)

                    samples.forEachIndexed { index, amp ->
                        val ratio = index.toFloat() / barCount.toFloat()
                        val isPlayed = ratio <= progress
                        val barHeight = (size.height * amp.coerceIn(0.2f, 1f))
                        val top = (size.height - barHeight) / 2f
                        drawRoundRect(
                            color = if (isPlayed) contentTint else contentTint.copy(alpha = 0.42f),
                            topLeft = Offset(index * (barWidth + spacing), top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = attachment.subtitle.ifBlank { "Voice Note • ${attachment.durationSeconds}s" },
                    color = contentTint.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun GeoLocationAttachmentCard(
    attachment: MediaAttachment,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                onActionClick("Opening GSMA Geolocation Push (${attachment.latitude}, ${attachment.longitude})")
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(Color(0xFFDFF2E6)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val step = 24.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(
                            color = Color(0xFFB7DEC6),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2f
                        )
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = Color(0xFFB7DEC6),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2f
                        )
                        y += step
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFB3261E),
                    shadowElevation = 4.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = attachment.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = attachment.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun DocumentAttachmentCard(
    attachment: MediaAttachment,
    isOutgoing: Boolean,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isOutgoing) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onActionClick("Opening document: ${attachment.title}") },
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.title,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = attachment.subtitle,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Horizontal Suggested Replies & Action Chips strip above the message composer.
 */
@Composable
fun SuggestedChipsBar(
    chips: List<SuggestedChip>,
    onChipClick: (SuggestedChip) -> Unit,
    modifier: Modifier = Modifier
) {
    if (chips.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            AssistChip(
                onClick = { onChipClick(chip) },
                label = {
                    Text(
                        text = chip.label,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = iconForSuggestionType(chip.type),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(50),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

private fun iconForSuggestionType(type: SuggestionType) = when (type) {
    SuggestionType.REPLY -> Icons.Default.Reply
    SuggestionType.OPEN_URL -> Icons.Default.OpenInBrowser
    SuggestionType.DIAL_PHONE -> Icons.Default.Call
    SuggestionType.VIEW_LOCATION -> Icons.Default.Map
    SuggestionType.CALENDAR_EVENT -> Icons.Default.CalendarMonth
    SuggestionType.REQUEST_LOCATION -> Icons.Default.MyLocation
}
