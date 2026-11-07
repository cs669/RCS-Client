package com.android.rcsclient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.rcsclient.data.RcsRepository
import com.android.rcsclient.model.ImsRegistrationStatus
import com.android.rcsclient.model.RcsConversation
import com.android.rcsclient.model.RcsMessage
import com.android.rcsclient.model.SuggestedChip
import com.android.rcsclient.model.SuggestionType
import com.android.rcsclient.model.TransportProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConversationFilter(val label: String) {
    ALL("All"),
    RCS_CHATS("RCS Chats"),
    BUSINESS_RBM("Verified RBM"),
    GROUPS("Groups"),
    SMS_MMS("SMS / MMS")
}

class RcsViewModel(val repository: RcsRepository) : ViewModel() {

    val conversations: StateFlow<List<RcsConversation>> = repository.conversations
    val messagesByConversation: StateFlow<Map<String, List<RcsMessage>>> = repository.messagesByConversation
    val imsStatus: StateFlow<ImsRegistrationStatus> = repository.imsStatus

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ConversationFilter.ALL)
    val selectedFilter: StateFlow<ConversationFilter> = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<RcsMessage?>(null)
    val replyingToMessage: StateFlow<RcsMessage?> = _replyingToMessage.asStateFlow()

    private val _inspectedMessageForMls = MutableStateFlow<RcsMessage?>(null)
    val inspectedMessageForMls: StateFlow<RcsMessage?> = _inspectedMessageForMls.asStateFlow()

    private val _actionToastMessage = MutableStateFlow<String?>(null)
    val actionToastMessage: StateFlow<String?> = _actionToastMessage.asStateFlow()

    fun selectConversation(conversationId: String?) {
        _selectedConversationId.value = conversationId
        _replyingToMessage.value = null
        if (conversationId != null) {
            repository.markConversationRead(conversationId)
        }
    }

    fun setFilter(filter: ConversationFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setReplyingToMessage(message: RcsMessage?) {
        _replyingToMessage.value = message
    }

    fun setInspectedMessageForMls(message: RcsMessage?) {
        _inspectedMessageForMls.value = message
    }

    fun clearToast() {
        _actionToastMessage.value = null
    }

    fun showToast(msg: String) {
        _actionToastMessage.value = msg
    }

    fun sendTextMessage(conversationId: String, text: String, forceProtocol: TransportProtocol? = null) {
        val replyTarget = _replyingToMessage.value
        repository.sendMessage(
            conversationId = conversationId,
            body = text,
            replyToMessage = replyTarget,
            forceProtocol = forceProtocol
        )
        _replyingToMessage.value = null
    }

    fun sendMediaAttachment(conversationId: String, uriString: String?, captionText: String = "") {
        if (uriString.isNullOrBlank()) {
            showToast("No attachment selected")
            return
        }
        val replyTarget = _replyingToMessage.value
        repository.sendMediaUriMessage(
            conversationId = conversationId,
            uriString = uriString,
            captionText = captionText,
            replyToMessage = replyTarget
        )
        _replyingToMessage.value = null
    }

    fun attachmentsNotSupportedYet() {
        showToast("Select a photo or file to send via MMS")
    }

    fun handleSuggestedChipClick(conversationId: String, chip: SuggestedChip) {
        when (chip.type) {
            SuggestionType.REPLY -> {
                val replyText = chip.actionPayload.ifBlank { chip.label }
                sendTextMessage(conversationId, replyText)
            }
            SuggestionType.REQUEST_LOCATION -> {
                attachmentsNotSupportedYet()
            }
            SuggestionType.CALENDAR_EVENT -> {
                showToast("📅 Added to Calendar: ${chip.actionPayload.ifBlank { chip.label }}")
                sendTextMessage(conversationId, "Added \"${chip.actionPayload.ifBlank { chip.label }}\" to my calendar 📅")
            }
            SuggestionType.OPEN_URL -> {
                showToast("🌐 Opening Verified RBM Webview: ${chip.actionPayload}")
            }
            SuggestionType.DIAL_PHONE -> {
                showToast("📞 Launching Dialer: ${chip.actionPayload}")
            }
            SuggestionType.VIEW_LOCATION -> {
                showToast("🗺️ Opening Maps: ${chip.actionPayload}")
            }
        }
    }

    class Factory(private val repository: RcsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RcsViewModel(repository) as T
        }
    }
}
