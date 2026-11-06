package com.android.rcsclient

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.rcsclient.telephony.DefaultSmsRoleHelper
import com.android.rcsclient.telephony.DeviceContactItem
import com.android.rcsclient.ui.RcsViewModel
import com.android.rcsclient.ui.components.ImsProvisioningDiagnosticsDialog
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.ui.components.MessageInfoDialog
import com.android.rcsclient.ui.components.NewRcsConversationDialog
import com.android.rcsclient.ui.screens.ChatDetailScreen
import com.android.rcsclient.ui.screens.ConversationListScreen
import com.android.rcsclient.ui.theme.RcsClientTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as RcsApplication
        val initialPhoneFromIntent = extractPhoneFromSmsIntent(intent)

        setContent {
            RcsClientTheme {
                val rcsViewModel: RcsViewModel = viewModel(
                    factory = RcsViewModel.Factory(app.repository)
                )

                LaunchedEffect(initialPhoneFromIntent) {
                    if (!initialPhoneFromIntent.isNullOrBlank()) {
                        val conv = app.repository.createOrOpenConversation(
                            title = initialPhoneFromIntent,
                            phoneOrGroupPhones = initialPhoneFromIntent,
                            isGroup = false
                        )
                        rcsViewModel.selectConversation(conv.id)
                    }
                }

                RcsMainAppContent(rcsViewModel = rcsViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? RcsApplication)?.repository?.refreshImsAndRoleStatus()
    }

    private fun extractPhoneFromSmsIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action == Intent.ACTION_SENDTO || action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW) {
            val schemePart = intent.data?.schemeSpecificPart?.trim()
            if (!schemePart.isNullOrBlank()) {
                return schemePart.substringBefore("?")
            }
        }
        return null
    }
}

@Composable
fun RcsMainAppContent(rcsViewModel: RcsViewModel) {
    val context = LocalContext.current
    val conversations by rcsViewModel.conversations.collectAsState()
    val messagesByConversation by rcsViewModel.messagesByConversation.collectAsState()
    val imsStatus by rcsViewModel.imsStatus.collectAsState()
    val networkConfig by rcsViewModel.repository.networkConfig.collectAsState()
    val lastTransportEvent by rcsViewModel.repository.lastTransportEvent.collectAsState()
    val selectedConversationId by rcsViewModel.selectedConversationId.collectAsState()
    val selectedFilter by rcsViewModel.selectedFilter.collectAsState()
    val searchQuery by rcsViewModel.searchQuery.collectAsState()
    val replyingToMessage by rcsViewModel.replyingToMessage.collectAsState()
    val inspectedMessage by rcsViewModel.inspectedMessageForMls.collectAsState()
    val toastMessage by rcsViewModel.actionToastMessage.collectAsState()

    var showImsDiagnosticsDialog by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var deviceContacts by remember { mutableStateOf<List<DeviceContactItem>>(emptyList()) }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        rcsViewModel.repository.refreshImsAndRoleStatus()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        rcsViewModel.repository.refreshImsAndRoleStatus()
        deviceContacts = rcsViewModel.repository.loadDeviceContacts()
        val grantedCount = results.values.count { it }
        rcsViewModel.showToast("Granted $grantedCount/${results.size} telephony & contacts permissions")
    }

    val requestRuntimePermissions: () -> Unit = {
        val perms = buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionsLauncher.launch(perms)
    }

    val requestDefaultSmsRole: () -> Unit = {
        val roleIntent = DefaultSmsRoleHelper.createRequestDefaultSmsRoleIntent(context)
        if (roleIntent != null) {
            runCatching { roleLauncher.launch(roleIntent) }.onFailure {
                rcsViewModel.showToast("RoleManager.ROLE_SMS requested")
            }
        } else {
            rcsViewModel.showToast("Default SMS role dialog unavailable on this device")
        }
    }

    LaunchedEffect(showNewChatDialog) {
        if (showNewChatDialog) {
            deviceContacts = rcsViewModel.repository.loadDeviceContacts()
        }
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            rcsViewModel.clearToast()
        }
    }

    LaunchedEffect(lastTransportEvent) {
        lastTransportEvent?.let { evt ->
            Toast.makeText(context, evt, Toast.LENGTH_LONG).show()
            rcsViewModel.repository.clearTransportEvent()
        }
    }

    val activeConversation = remember(conversations, selectedConversationId) {
        conversations.find { it.id == selectedConversationId }
    }

    BackHandler(enabled = activeConversation != null) {
        rcsViewModel.selectConversation(null)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeConversation == null) {
                ConversationListScreen(
                    conversations = conversations,
                    imsStatus = imsStatus,
                    selectedFilter = selectedFilter,
                    searchQuery = searchQuery,
                    onSelectFilter = rcsViewModel::setFilter,
                    onSearchQueryChange = rcsViewModel::setSearchQuery,
                    onConversationClick = { conv -> rcsViewModel.selectConversation(conv.id) },
                    onTogglePinConversation = { convId ->
                        rcsViewModel.repository.togglePinConversation(convId)
                        rcsViewModel.showToast("Updated conversation pin state")
                    },
                    onOpenImsDiagnostics = { showImsDiagnosticsDialog = true },
                    onRequestDefaultSmsRole = requestDefaultSmsRole,
                    onOpenNewChatDialog = { showNewChatDialog = true }
                )
            } else {
                val activeMessages = messagesByConversation[activeConversation.id].orEmpty()
                ChatDetailScreen(
                    conversation = activeConversation,
                    messages = activeMessages,
                    imsStatus = imsStatus,
                    replyingToMessage = replyingToMessage,
                    onBack = { rcsViewModel.selectConversation(null) },
                    onSendMessage = { text ->
                        rcsViewModel.sendTextMessage(activeConversation.id, text)
                    },
                    onSendHighResPhoto = { _ -> rcsViewModel.attachmentsNotSupportedYet() },
                    onSendVoiceNote = { rcsViewModel.attachmentsNotSupportedYet() },
                    onSendGeoLocation = { rcsViewModel.attachmentsNotSupportedYet() },
                    onSendDocument = { rcsViewModel.attachmentsNotSupportedYet() },
                    onSendRichCard = { rcsViewModel.attachmentsNotSupportedYet() },
                    onRotateMlsEpoch = { },
                    onSuggestedChipClick = { chip ->
                        rcsViewModel.handleSuggestedChipClick(activeConversation.id, chip)
                    },
                    onToggleReaction = { msgId, emoji ->
                        rcsViewModel.repository.toggleReaction(activeConversation.id, msgId, emoji)
                    },
                    onSetReplyingTo = rcsViewModel::setReplyingToMessage,
                    onInspectMessageMls = rcsViewModel::setInspectedMessageForMls,
                    onOpenMlsSecurityDialog = {
                        rcsViewModel.showToast("Not encrypted. SMS isn't end-to-end encrypted, and RCS isn't connected yet.")
                    },
                    onToggleProtocolMode = { nextProtocol ->
                        if (nextProtocol == TransportProtocol.RCS_UP_2_7 && !imsStatus.isImsRegistered) {
                            rcsViewModel.showToast("RCS isn't connected yet, so this chat stays on SMS")
                        } else {
                            rcsViewModel.repository.setConversationPreferredProtocol(activeConversation.id, nextProtocol)
                            rcsViewModel.showToast("Switched this chat to ${nextProtocol.shortBadge}")
                        }
                    },
                    onAttachmentActionClick = { info ->
                        rcsViewModel.showToast(info)
                    }
                )
            }

            if (showImsDiagnosticsDialog) {
                ImsProvisioningDiagnosticsDialog(
                    status = imsStatus,
                    networkConfig = networkConfig,
                    onRequestDefaultSmsRole = requestDefaultSmsRole,
                    onRequestRuntimePermissions = requestRuntimePermissions,
                    onSaveNetworkConfig = { updatedConfig ->
                        rcsViewModel.repository.updateNetworkConfig(updatedConfig)
                        rcsViewModel.showToast("Saved")
                    },
                    onDismiss = { showImsDiagnosticsDialog = false }
                )
            }

            if (inspectedMessage != null) {
                MessageInfoDialog(
                    message = inspectedMessage!!,
                    onDismiss = { rcsViewModel.setInspectedMessageForMls(null) }
                )
            }

            if (showNewChatDialog) {
                NewRcsConversationDialog(
                    deviceContacts = deviceContacts,
                    onRequestPermissions = requestRuntimePermissions,
                    onCreateConversation = { title, phone ->
                        val created = rcsViewModel.repository.createOrOpenConversation(
                            title = title,
                            phoneOrGroupPhones = phone,
                            isGroup = false
                        )
                        showNewChatDialog = false
                        rcsViewModel.selectConversation(created.id)
                    },
                    onDismiss = { showNewChatDialog = false }
                )
            }
        }
    }
}
