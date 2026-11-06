package com.android.rcsclient

import com.android.rcsclient.data.RcsRepository
import com.android.rcsclient.model.DeliveryStatus
import com.android.rcsclient.model.MediaAttachment
import com.android.rcsclient.model.AttachmentType
import com.android.rcsclient.model.RcsMessage
import com.android.rcsclient.model.TransportProtocol
import com.android.rcsclient.network.PhoneNumbers
import com.android.rcsclient.network.RcsTransport
import com.android.rcsclient.telephony.CarrierImsRcsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcsClientUnitTest {

    private val imsManager = CarrierImsRcsManager(context = null)

    private fun newRepository(transport: RcsTransport? = null): RcsRepository =
        if (transport == null) {
            RcsRepository(dbHelper = null, imsRcsManager = imsManager)
        } else {
            RcsRepository(
                dbHelper = null,
                imsRcsManager = imsManager,
                rcsTransport = transport,
                externalScope = CoroutineScope(Dispatchers.Unconfined + Job())
            )
        }

    private class FakeRegisteredTransport : RcsTransport {
        val sent = mutableListOf<Pair<String, RcsMessage>>()
        override val isRegistered = true
        override val statusLabel = "Registered (test)"
        override suspend fun sendChatMessage(toE164: String, message: RcsMessage): Result<Unit> {
            sent += toE164 to message
            return Result.success(Unit)
        }
    }

    @Test
    fun freshRepository_hasNoFakeSeedConversations() {
        val repository = newRepository()
        assertTrue(repository.conversations.value.isEmpty())
    }

    @Test
    fun status_isNotRegistered_withoutARealRcsStack() {
        val repository = newRepository()
        assertFalse(repository.imsStatus.value.isImsRegistered)
    }

    @Test
    fun capabilities_areUnknown_withoutCapabilityExchange() {
        val caps = imsManager.resolveContactCapabilities("+16505550142")
        assertFalse(caps.supportsSessionChat)
        assertFalse(caps.supportsMlsE2ee)
    }

    @Test
    fun protocolSelection_fallsBackToSmsAndMms() {
        val rcsPeer = imsManager.resolveContactCapabilities("+16505550142").copy(supportsSessionChat = true)

        assertEquals(
            TransportProtocol.RCS_UP_2_7,
            imsManager.selectTransportProtocol(true, TransportProtocol.RCS_UP_2_7, rcsPeer, true, false)
        )
        assertEquals(
            TransportProtocol.SMS,
            imsManager.selectTransportProtocol(false, TransportProtocol.RCS_UP_2_7, rcsPeer, true, false)
        )
        assertEquals(
            TransportProtocol.MMS,
            imsManager.selectTransportProtocol(false, TransportProtocol.RCS_UP_2_7, rcsPeer, true, true)
        )
    }

    @Test
    fun sendText_withoutRcs_goesOutAsSms_andGetsNoSimulatedReply() {
        val repository = newRepository()
        val conv = repository.createOrOpenConversation("Test", "+16505550142", isGroup = false)

        val sent = repository.sendMessage(conv.id, "hello")
        assertNotNull(sent)
        assertEquals(TransportProtocol.SMS, sent!!.protocol)

        // No Android context in unit tests, so SmsManager isn't reachable and nothing else
        // should appear in the thread. The old build would have added a fake reply here.
        val messages = repository.messagesByConversation.value[conv.id].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.all { it.isOutgoing })
    }

    @Test
    fun sendAttachment_withoutMms_failsHonestly() {
        val repository = newRepository()
        val conv = repository.createOrOpenConversation("Test", "+16505550142", isGroup = false)
        val photo = MediaAttachment(type = AttachmentType.HIGH_RES_IMAGE, title = "photo.jpg")

        val sent = repository.sendMessage(conv.id, "", attachments = listOf(photo))
        val stored = repository.messagesByConversation.value[conv.id]!!.first { it.id == sent!!.id }
        assertEquals(DeliveryStatus.FAILED, stored.deliveryStatus)
    }

    @Test
    fun sendText_withRegisteredTransport_usesRcs() = runBlocking {
        val transport = FakeRegisteredTransport()
        val repository = newRepository(transport)
        val conv = repository.createOrOpenConversation("Test", "(650) 555-0142", isGroup = false)
        repository.setConversationPreferredProtocol(conv.id, TransportProtocol.RCS_UP_2_7)
        // Pretend capability exchange said yes. Real UCE comes with the RCS stack.
        val withCaps = repository.conversations.value.first { it.id == conv.id }
        assertEquals(TransportProtocol.RCS_UP_2_7, withCaps.preferredProtocol)

        val sent = repository.sendMessage(conv.id, "hi", forceProtocol = TransportProtocol.RCS_UP_2_7)
        assertNotNull(sent)
        assertEquals(1, transport.sent.size)
        assertEquals("+16505550142", transport.sent.first().first)
        val stored = repository.messagesByConversation.value[conv.id]!!.first { it.id == sent!!.id }
        assertEquals(DeliveryStatus.SENT, stored.deliveryStatus)
    }

    @Test
    fun sameNumber_matchesFormattingVariants_butNotShortCodes() {
        assertTrue(PhoneNumbers.sameNumber("+1 (650) 555-0142", "6505550142"))
        assertFalse(PhoneNumbers.sameNumber("+16505550142", "+16505550143"))
        assertTrue(PhoneNumbers.sameNumber("AMAZON", "amazon"))
        assertFalse(PhoneNumbers.sameNumber("12345", "+12345678"))
        assertEquals("12345", PhoneNumbers.normalizeToE164("12345"))
        assertEquals("+16505550142", PhoneNumbers.normalizeToE164("650-555-0142"))
    }

    @Test
    fun incomingSms_fromSameNumber_landsInOneConversation() {
        val repository = newRepository()
        repository.receiveIncomingSms("+16505550142", "one", 1L)
        repository.receiveIncomingSms("650-555-0142", "two", 2L)
        assertEquals(1, repository.conversations.value.size)
    }
}
