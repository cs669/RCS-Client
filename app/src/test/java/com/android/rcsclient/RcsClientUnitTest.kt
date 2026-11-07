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
import com.android.rcsclient.telephony.MmsBodyPart
import com.android.rcsclient.telephony.MmsPduCodec
import com.android.rcsclient.telephony.IncomingMms
import com.android.rcsclient.telephony.SystemMessage
import com.android.rcsclient.telephony.SystemMessageStore
import com.android.rcsclient.telephony.SystemMessageStore.Box
import com.android.rcsclient.telephony.SystemThread
import com.android.rcsclient.network.RcsClientConfig
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
    fun sendAttachment_withoutAndroidContext_failsInsteadOfHanging() {
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

    @Test
    fun mmsPduCodec_encodeAndParseSendReq_roundTripSucceeds() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val encoded = MmsPduCodec.encodeSendReq(
            transactionId = "tx-test-99",
            recipients = listOf("+16505550142", "+14155550188"),
            subject = "Photo MMS",
            parts = listOf(
                MmsBodyPart("text/plain", "body.txt", "Héllo via MMS 👋".toByteArray(Charsets.UTF_8)),
                MmsBodyPart("image/jpeg", "photo.jpg", fakeJpeg)
            )
        )

        val parsed = MmsPduCodec.parsePdu(encoded)
        assertNotNull(parsed)
        assertEquals(MmsPduCodec.MESSAGE_TYPE_SEND_REQ, parsed!!.messageType)
        assertEquals("tx-test-99", parsed.transactionId)
        assertEquals(listOf("+16505550142", "+14155550188"), parsed.toList)
        assertEquals("Photo MMS", parsed.subject)
        // SMIL layout part is generated first, then text, then the image
        assertEquals(3, parsed.parts.size)
        assertEquals("application/smil", parsed.parts[0].contentType)
        val smil = String(parsed.parts[0].data, Charsets.UTF_8)
        assertTrue(smil.contains("src=\"body.txt\"") && smil.contains("src=\"photo.jpg\""))
        assertEquals("text/plain", parsed.parts[1].contentType)
        assertEquals("utf-8", parsed.parts[1].charset!!.lowercase())
        assertEquals("Héllo via MMS 👋", parsed.parts[1].decodeText())
        assertEquals("image/jpeg", parsed.parts[2].contentType)
        assertEquals("photo.jpg", parsed.parts[2].filename)
        assertEquals(fakeJpeg.toList(), parsed.parts[2].data.toList())
    }

    @Test
    fun mmsPduCodec_sendReq_hasSpecRequiredHeaderOrderAndContentType() {
        val encoded = MmsPduCodec.encodeSendReq(
            transactionId = "T1",
            recipients = listOf("+16505550142"),
            parts = listOf(MmsBodyPart("text/plain", "t.txt", "hi".toByteArray()))
        ).map { it.toInt() and 0xFF }

        // Message-Type m-send-req, Transaction-Id "T1", MMS-Version 1.2, in that order
        assertEquals(listOf(0x8C, 0x80, 0x98, 'T'.code, '1'.code, 0x00, 0x8D, 0x92), encoded.take(8))
        // Content-Type: multipart/related, start=<smil>, type=application/smil
        val ctIndex = encoded.indexOf(0x84)
        val expectedCt = listOf(0xB3, 0x8A) + "<smil>".map { it.code } + listOf(0x00, 0x89) +
            "application/smil".map { it.code } + listOf(0x00)
        assertEquals(expectedCt.size, encoded[ctIndex + 1])
        assertEquals(expectedCt, encoded.subList(ctIndex + 2, ctIndex + 2 + expectedCt.size))
        // The text part declares charset=utf-8: value-length 3, text/plain, charset, utf-8
        val textCt = listOf(0x03, 0x83, 0x81, 0xEA)
        assertTrue(encoded.windowed(4).any { it == textCt })
    }

    private fun bytes(vararg items: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (item in items) when (item) {
            is Int -> out.write(item)
            is String -> { out.write(item.toByteArray(Charsets.UTF_8)); out.write(0) }
            is ByteArray -> out.write(item)
            else -> error("bad item")
        }
        return out.toByteArray()
    }

    @Test
    fun mmsPduCodec_parsesCarrierNotificationInd() {
        val from = "+15551234567/TYPE=PLMN"
        val pdu = bytes(
            0x8C, 0x82,                 // m-notification-ind
            0x98, "tx-abc",             // transaction id
            0x8D, 0x92,                 // version 1.2
            0x89, from.length + 2, 0x80, from, // From: address-present
            0x96, "Pics",               // subject
            0x8A, 0x80,                 // class personal
            0x8E, 0x02, 0x0B, 0xB8,     // size 3000
            0x88, 0x05, 0x81, 0x03, 0x03, 0xF4, 0x80, // expiry relative 259200s
            0x83, "http://mmsc.example.com/m/abc123"
        )
        val parsed = MmsPduCodec.parsePdu(pdu)!!
        assertEquals(MmsPduCodec.MESSAGE_TYPE_NOTIFICATION_IND, parsed.messageType)
        assertEquals("tx-abc", parsed.transactionId)
        assertEquals("+15551234567", parsed.from)
        assertEquals("Pics", parsed.subject)
        assertEquals(3000L, parsed.messageSize)
        assertEquals("http://mmsc.example.com/m/abc123", parsed.contentLocation)
    }

    @Test
    fun mmsPduCodec_parsesRetrieveConfWithCharsetCcAndExtraPartHeaders() {
        val latin1Text = "Café".toByteArray(Charsets.ISO_8859_1)
        // Part 1: text/plain; charset=iso-8859-1 (MIBenum 4), Content-Location, plus a
        // Content-Disposition header (0xAE) the old parser would have tripped over.
        val textHeaders = bytes(
            0x03, 0x83, 0x81, 0x84,
            0xAE, 0x03, 0x81, 0x8E, 0x01, // disposition: value-length 3 containing a stray 0x8E byte
            0x8E, "note.txt"
        )
        val imgHeaders = bytes(0x9E, 0xC0, 0x22, "<img1>", 0x8E, "IMG_0001.jpg")
        val img = byteArrayOf(1, 2, 3)
        val pdu = bytes(
            0x8C, 0x84,
            0x98, "tx-r1",
            0x8D, 0x92,
            0x85, 0x04, 0x65, 0x00, 0x00, 0x00, // date
            0x89, 0x0E, 0x80, "+15551234567",
            0x97, "+15550000001/TYPE=PLMN",
            0x82, "+15550000002/TYPE=PLMN",
            0x84, 0xA3,                           // multipart/mixed
            0x02,
            textHeaders.size, latin1Text.size, textHeaders, latin1Text,
            imgHeaders.size, img.size, imgHeaders, img
        )
        val parsed = MmsPduCodec.parsePdu(pdu)!!
        assertEquals(MmsPduCodec.MESSAGE_TYPE_RETRIEVE_CONF, parsed.messageType)
        assertEquals("+15551234567", parsed.from)
        assertEquals(listOf("+15550000001"), parsed.toList)
        assertEquals(listOf("+15550000002"), parsed.ccList)
        assertEquals(0x65000000L, parsed.dateSeconds)
        assertEquals(2, parsed.parts.size)
        assertEquals("note.txt", parsed.parts[0].filename)
        assertEquals("Café", parsed.parts[0].decodeText())
        assertEquals("image/jpeg", parsed.parts[1].contentType)
        assertEquals("IMG_0001.jpg", parsed.parts[1].filename)
        assertEquals(img.toList(), parsed.parts[1].data.toList())
    }

    @Test
    fun mmsPduCodec_encodesAcknowledgements() {
        assertEquals(
            listOf(0x8C, 0x85, 0x98) + "tx9".map { it.code } + listOf(0x00, 0x8D, 0x92, 0x91, 0x81),
            MmsPduCodec.encodeAcknowledgeInd("tx9").map { it.toInt() and 0xFF }
        )
        assertEquals(
            listOf(0x8C, 0x83, 0x98) + "tx9".map { it.code } + listOf(0x00, 0x8D, 0x92, 0x95, 0x81, 0x91, 0x81),
            MmsPduCodec.encodeNotifyRespInd("tx9", MmsPduCodec.STATUS_RETRIEVED).map { it.toInt() and 0xFF }
        )
    }

    private fun incoming(sender: String, vararg to: String) =
        IncomingMms(sender = sender, recipients = to.toList(), body = "hey", attachments = emptyList(), timestampMs = 5L)

    @Test
    fun groupMms_isThreadedByMembers_andExcludesOwnNumber() {
        val repository = newRepository()
        repository.updateNetworkConfig(RcsClientConfig(localPhoneNumber = "+16505550100"))

        // A writes to me and B, then B replies to me and A: same group thread
        repository.receiveIncomingMms(incoming("+16505550111", "+16505550100", "+16505550122"))
        repository.receiveIncomingMms(incoming("6505550122", "(650) 555-0100", "+1 650 555 0111"))

        val groups = repository.conversations.value.filter { it.participants.size > 1 }
        assertEquals(1, groups.size)
        val members = groups.first().participants.map { it.phoneNumber }.toSet()
        assertEquals(setOf("+16505550111", "+16505550122"), members)
        assertEquals(2, repository.messagesByConversation.value[groups.first().id]!!.size)
    }

    @Test
    fun oneToOneMms_landsInTheSameThreadAsSms() {
        val repository = newRepository()
        repository.updateNetworkConfig(RcsClientConfig(localPhoneNumber = "+16505550100"))
        repository.receiveIncomingSms("+16505550111", "sms first", 1L)
        repository.receiveIncomingMms(incoming("+16505550111", "+16505550100"))
        assertEquals(1, repository.conversations.value.size)
    }

    @Test
    fun oneToOneMms_withUnknownOwnNumber_isNotTreatedAsGroup() {
        val repository = newRepository()
        repository.receiveIncomingMms(incoming("+16505550111", "+16505550100"))
        assertEquals(1, repository.conversations.value.size)
        assertEquals(1, repository.conversations.value.first().participants.size)
    }

    @Test
    fun mmsSendResult_updatesMessageById() {
        val repository = newRepository()
        val conv = repository.createOrOpenConversation("Test", "+16505550142", isGroup = false)
        val sent = repository.sendMessage(conv.id, "hello")!!
        repository.updateMessageStatusById(sent.id, DeliveryStatus.SMS_FALLBACK_SENT, null)
        val stored = repository.messagesByConversation.value[conv.id]!!.first { it.id == sent.id }
        assertEquals(DeliveryStatus.SMS_FALLBACK_SENT, stored.deliveryStatus)
    }

    // ---- Android's shared SMS/MMS storage ----

    private class FakeSystemStore(var writable: Boolean = true) : SystemMessageStore {
        data class Insert(val kind: String, val box: Box, val thread: List<String>, val body: String, val read: Boolean)
        val inserts = mutableListOf<Insert>()
        val boxUpdates = mutableListOf<Pair<String, Box>>()
        val readThreads = mutableListOf<List<String>>()
        var history: List<SystemThread> = emptyList()
        var importDone = false
        private var nextId = 1

        override fun isWritable() = writable
        override fun insertSms(box: Box, address: String, body: String, dateMs: Long, read: Boolean, subId: Int): String {
            inserts += Insert("sms", box, listOf(address), body, read)
            return "content://sms/${nextId++}"
        }
        override fun insertMms(
            box: Box, threadAddresses: List<String>, sender: String?, recipients: List<String>, body: String,
            attachments: List<MediaAttachment>, dateMs: Long, read: Boolean, subId: Int
        ): String {
            inserts += Insert("mms", box, threadAddresses, body, read)
            return "content://mms/${nextId++}"
        }
        override fun updateBox(uri: String, box: Box, delivered: Boolean) { boxUpdates += uri to box }
        override fun markThreadRead(addresses: List<String>) { readThreads += addresses }
        override fun loadHistory(maxSms: Int, maxMms: Int) = history
        override fun isImportDone() = importDone
        override fun markImportDone() { importDone = true }
    }

    private fun repoWithStore(store: FakeSystemStore) = RcsRepository(
        dbHelper = null,
        imsRcsManager = imsManager,
        externalScope = CoroutineScope(Dispatchers.Unconfined + Job()),
        systemStore = store,
        storeDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun incomingSms_isSavedToSystemStoreAsUnread() {
        val store = FakeSystemStore().apply { importDone = true }
        val repository = repoWithStore(store)
        repository.receiveIncomingSms("+15551230000", "hi there", 1_000L)

        assertEquals(listOf(FakeSystemStore.Insert("sms", Box.INBOX, listOf("+15551230000"), "hi there", false)), store.inserts)
        val msg = repository.messagesByConversation.value.values.flatten().single()
        assertEquals("content://sms/1", msg.systemUri)
    }

    @Test
    fun outgoingSms_isSavedToOutbox_thenMovedWhenTheResultComesIn() {
        val store = FakeSystemStore().apply { importDone = true }
        val repository = repoWithStore(store)
        val conv = repository.createOrOpenConversation("Bob", "+15551230000", isGroup = false)
        // No Android context in tests, so the radio send fails right away
        val sent = repository.sendMessage(conv.id, "hello")!!

        assertEquals(Box.OUTBOX, store.inserts.single().box)
        val stored = repository.messagesByConversation.value[conv.id]!!.first { it.id == sent.id }
        assertEquals("content://sms/1", stored.systemUri)
        assertEquals(DeliveryStatus.FAILED, stored.deliveryStatus)
        assertEquals("content://sms/1" to Box.FAILED, store.boxUpdates.last())

        repository.updateMessageStatusById(sent.id, DeliveryStatus.SMS_FALLBACK_SENT, null)
        assertEquals("content://sms/1" to Box.SENT, store.boxUpdates.last())
    }

    @Test
    fun incomingGroupMms_isThreadedWithTheGroupNotOurNumber() {
        val store = FakeSystemStore().apply { importDone = true }
        val repository = repoWithStore(store)
        repository.updateNetworkConfig(RcsClientConfig(localPhoneNumber = "+15550000000"))
        repository.receiveIncomingMms(
            IncomingMms("+15551111111", listOf("+15550000000", "+15552222222"), "group pic", emptyList(), 5_000L)
        )
        val insert = store.inserts.single()
        assertEquals("mms", insert.kind)
        assertEquals(setOf("+15551111111", "+15552222222"), insert.thread.toSet())
    }

    @Test
    fun nothingIsWritten_whenNotTheDefaultSmsApp() {
        val store = FakeSystemStore(writable = false)
        val repository = repoWithStore(store)
        repository.receiveIncomingSms("+15551230000", "hi", 1_000L)
        assertTrue(store.inserts.isEmpty())
        assertFalse(store.importDone)
    }

    @Test
    fun import_bringsInHistory_once_andSkipsWhatWeAlreadyHave() {
        val store = FakeSystemStore(writable = false)
        val repository = repoWithStore(store)
        repository.receiveIncomingSms("+15551230000", "already here", 1_000L) // not saved: not default yet

        store.history = listOf(
            SystemThread(
                threadId = 7,
                addresses = listOf("+15551230000"),
                messages = listOf(
                    SystemMessage("content://sms/50", false, Box.SENT, "", "old outgoing", 500L, true, true, emptyList()),
                    SystemMessage("content://sms/51", false, Box.INBOX, "+15551230000", "old unread", 600L, false, false, emptyList())
                )
            ),
            SystemThread(
                threadId = 8,
                addresses = listOf("+15553330000", "+15554440000"),
                messages = listOf(
                    SystemMessage("content://mms/9", true, Box.INBOX, "+15553330000", "group hello", 700L, true, false, emptyList())
                )
            )
        )
        store.writable = true
        repository.refreshImsAndRoleStatus()

        // The app-only message was copied out first, then the two threads came in
        assertEquals(Box.INBOX, store.inserts.single().box)
        assertTrue(store.importDone)
        val convs = repository.conversations.value
        assertEquals(2, convs.size)
        val bob = convs.first { it.subtitleOrPhone == "+15551230000" }
        val msgs = repository.messagesByConversation.value[bob.id]!!
        assertEquals(listOf("old outgoing", "old unread", "already here"), msgs.map { it.body })
        assertEquals(DeliveryStatus.DELIVERED, msgs[0].deliveryStatus)
        assertTrue(msgs[0].isOutgoing)
        assertEquals("already here", bob.lastMessagePreview)
        assertEquals(2, bob.unreadCount) // the live one plus the unread imported one
        val group = convs.first { it.participants.size == 2 }
        assertEquals("group hello", repository.messagesByConversation.value[group.id]!!.single().body)

        // Running it again changes nothing
        repository.refreshImsAndRoleStatus()
        assertEquals(4, repository.messagesByConversation.value.values.sumOf { it.size })
    }

    @Test
    fun openingAConversation_marksItReadInSystemStore() {
        val store = FakeSystemStore().apply { importDone = true }
        val repository = repoWithStore(store)
        repository.receiveIncomingSms("+15551230000", "hi", 1_000L)
        val conv = repository.conversations.value.single()
        repository.markConversationRead(conv.id)
        assertEquals(listOf(listOf("+15551230000")), store.readThreads)
    }
}
