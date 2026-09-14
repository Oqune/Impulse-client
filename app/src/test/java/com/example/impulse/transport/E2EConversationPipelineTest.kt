package com.example.impulse.transport

import com.example.impulse.security.PqcCrypto
import com.example.impulse.util.bytesToHex
import com.example.impulse.util.hexToBytes
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * End-to-End multi-peer integration test running on the JVM without requiring
 * real Android devices or an active network.
 *
 * It verifies the entire E2EE pipeline:
 *  1. Peer discovery and Combined Key Exchange (ML-KEM-768 + ML-DSA-65 with C1 attestation).
 *  2. Multi-recipient Group chat encryption, broadcast, signature verification, and local echo.
 *  3. Direct Message (DM) end-to-end encryption with cryptographic isolation (non-recipients cannot decrypt).
 *  4. Bidirectional DM symmetric conversation key mapping ("dm:<peer>" threading).
 *  5. Offline history synchronization via OP_SYNC (0x33) and OP_SYNC_RESPONSE (0x34).
 *  6. Replay attack rejection using message nonces.
 *  7. Ciphertext tampering detection (AES-GCM authentication tag failure).
 */
class E2EConversationPipelineTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpSecurityProvider() {
            PqcCrypto.ensureProvider()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test Harness: Virtual Peer & Virtual Relay
    // ────────────────────────────────────────────────────────────────────────

    data class DecryptedItem(
        val serverMsgId: Long,
        val senderName: String,
        val senderFingerprint: String,
        val content: String,
        val isOwn: Boolean,
        val timestamp: Long,
        val conversationId: String
    )

    data class PeerKey(
        val kemPublicKey: ByteArray,
        val dsaPublicKey: ByteArray
    )

    class VirtualPeer(var name: String) {
        private val kemKeyPair = PqcCrypto.generateKeyPair()
        private val dsaKeyPair = PqcCrypto.generateMlDsa65KeyPair()

        val kemPub: ByteArray = kemKeyPair.publicEncoded
        val kemPriv: ByteArray = kemKeyPair.privateEncoded
        val dsaPub: ByteArray = dsaKeyPair.publicEncoded
        val dsaPriv: ByteArray = dsaKeyPair.privateEncoded

        val fingerprint: String = bytesToHex(
            MessageDigest.getInstance("SHA-256").digest(kemPub)
        ).take(32)

        val knownPeers = mutableMapOf<String, PeerKey>()
        val seenNonces = mutableSetOf<String>()
        val receivedMessages = mutableListOf<DecryptedItem>()
        var lastSeenId: Long = 0L

        fun buildKeyExchangePacket(): ByteArray {
            val sig = PqcCrypto.signMlDsa65(dsaPriv, kemPub + dsaPub)
            return Protocol.buildCombinedKeyExchange(kemPub, dsaPub, sig)
        }

        fun onReceiveKeyExchange(packet: ByteArray) {
            val reader = Protocol.Reader(packet, 1)
            val frame = Protocol.parseCombinedKeyExchange(reader)
            val peerKemFp = bytesToHex(
                MessageDigest.getInstance("SHA-256").digest(frame.kemPublicKey)
            ).take(32)

            if (peerKemFp == fingerprint) return // ignore own key exchange

            val trusted = Protocol.evaluateKeyExchangeTrust(
                existingDsa = knownPeers[peerKemFp]?.dsaPublicKey,
                kemPublicKey = frame.kemPublicKey,
                dsaPublicKey = frame.dsaPublicKey,
                signature = frame.signature,
                verify = { pub, data, sig -> PqcCrypto.verifyMlDsa65(pub, data, sig) }
            )
            if (trusted) {
                knownPeers[peerKemFp] = PeerKey(frame.kemPublicKey, frame.dsaPublicKey)
            }
        }

        fun sendGroupMessage(content: String): ByteArray {
            return buildEncryptedDataPacket(content = content, targetDmFp = "")
        }

        fun sendDirectMessage(content: String, recipientFp: String): ByteArray {
            return buildEncryptedDataPacket(content = content, targetDmFp = recipientFp)
        }

        private fun buildEncryptedDataPacket(content: String, targetDmFp: String): ByteArray {
            val clientTs = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val canonical = Protocol.buildSignedInnerEnvelope(
                sender = name,
                signature = "",
                content = content,
                clientTs = clientTs,
                nonce = nonce,
                dm = targetDmFp
            )
            val dsaSig = PqcCrypto.signMlDsa65(dsaPriv, canonical)
            val signedEnvelope = Protocol.buildSignedInnerEnvelope(
                sender = name,
                signature = Protocol.base64Encode(dsaSig),
                content = content,
                clientTs = clientTs,
                nonce = nonce,
                dm = targetDmFp
            )

            val recipients = mutableListOf<Triple<String, ByteArray, ByteArray>>()

            val targetKeys = if (targetDmFp.isEmpty()) {
                // Group: encrypt for all known peers
                knownPeers.entries.map { it.key to it.value.kemPublicKey }
            } else {
                // DM: encrypt only for target
                val key = knownPeers[targetDmFp]?.kemPublicKey
                    ?: throw IllegalArgumentException("Unknown DM recipient: $targetDmFp")
                listOf(targetDmFp to key)
            }

            // Encrypt for peers
            for ((fp, pub) in targetKeys) {
                val (encKey, sharedSecret) = PqcCrypto.encapsulateKem(pub)
                val ct = PqcCrypto.aesEncrypt(sharedSecret, signedEnvelope)
                recipients.add(Triple(fp, encKey, ct))
            }

            // Encrypt for self (local echo / multi-device)
            val (ownEncKey, ownSecret) = PqcCrypto.encapsulateKem(kemPub)
            val ownCt = PqcCrypto.aesEncrypt(ownSecret, signedEnvelope)
            recipients.add(Triple(fingerprint, ownEncKey, ownCt))

            val blob = buildPerRecipientBlob(recipients)
            return Protocol.buildData(blob)
        }

        private fun buildPerRecipientBlob(recipients: List<Triple<String, ByteArray, ByteArray>>): ByteArray {
            val w = Protocol.Writer()
            val senderPubHash = MessageDigest.getInstance("SHA-256").digest(kemPub).copyOf(32)
            w.bytes(senderPubHash)
            w.u32(recipients.size.toLong())
            for ((idHex, encKey, ciphertext) in recipients) {
                w.bytes(hexToBytes(idHex))
                w.bytes(encKey)
                w.bytes(ciphertext)
            }
            return w.toByteArray()
        }

        /**
         * Process an incoming OP_DATA (0x32) server frame.
         * Returns true if successfully decrypted and verified, false if ignored/rejected.
         */
        fun onReceiveData(serverFrame: ByteArray): Boolean {
            val r = Protocol.Reader(serverFrame, 1)
            val dataFrame = Protocol.parseData(r)
            return processPayload(dataFrame.serverMsgId, dataFrame.timestamp, dataFrame.payload)
        }

        fun processPayload(serverMsgId: Long, ts: Long, payload: ByteArray): Boolean {
            val pr = Protocol.Reader(payload)
            val senderPubHash = pr.bytes()
            val senderFp = bytesToHex(senderPubHash).take(32)
            val count = pr.u32().toInt()

            var myEncKey: ByteArray? = null
            var myCiphertext: ByteArray? = null

            repeat(count) {
                val recipientId = pr.bytes()
                val recipientFp = bytesToHex(recipientId).take(32)
                val encKey = pr.bytes()
                val ct = pr.bytes()
                if (recipientFp == fingerprint && myEncKey == null) {
                    myEncKey = encKey
                    myCiphertext = ct
                }
            }

            val encKey = myEncKey ?: return false
            val ciphertext = myCiphertext ?: return false

            val sharedSecret = PqcCrypto.decapsulateKem(encKey, kemPriv)
            val innerBytes = runCatching { PqcCrypto.aesDecrypt(sharedSecret, ciphertext) }.getOrNull()
                ?: return false

            val env = Protocol.parseInnerEnvelope(innerBytes) ?: return false

            val isOwn = senderFp == fingerprint
            val dsaPub = if (isOwn) dsaPub else knownPeers[senderFp]?.dsaPublicKey
                ?: return false // missing sender DSA key

            val canonical = if (env.clientTs != 0L || env.nonce.isNotEmpty()) {
                Protocol.buildSignedInnerEnvelope(env.sender, "", env.content, env.clientTs, env.nonce, env.dm)
            } else {
                Protocol.buildInnerEnvelope(env.sender, "", env.content)
            }

            val sigBytes = runCatching { Protocol.base64Decode(env.signature) }.getOrNull() ?: return false
            if (!PqcCrypto.verifyMlDsa65(dsaPub, canonical, sigBytes)) {
                return false // signature mismatch!
            }

            if (env.nonce.isNotEmpty()) {
                val nonceKey = "$senderFp:${env.nonce}"
                if (!seenNonces.add(nonceKey)) {
                    return false // replay attack rejected!
                }
            }

            val conversationId = when {
                env.dm.isNotEmpty() && isOwn -> "dm:${env.dm}"
                env.dm.isNotEmpty() -> "dm:$senderFp"
                else -> "group"
            }

            if (serverMsgId > lastSeenId) {
                lastSeenId = serverMsgId
            }

            receivedMessages.add(
                DecryptedItem(
                    serverMsgId = serverMsgId,
                    senderName = env.sender,
                    senderFingerprint = senderFp,
                    content = env.content,
                    isOwn = isOwn,
                    timestamp = ts,
                    conversationId = conversationId
                )
            )
            return true
        }

        fun onReceiveSyncResponse(packet: ByteArray) {
            val r = Protocol.Reader(packet, 1)
            val resp = Protocol.parseSyncResponse(r)
            for (msg in resp.messages) {
                processPayload(msg.id, msg.timestamp, msg.payload)
            }
        }
    }

    class VirtualRelay {
        private var nextId = 1L
        private val messageLog = mutableListOf<Protocol.SyncMessage>()
        private val keyExchanges = mutableListOf<ByteArray>()
        private val clients = mutableListOf<VirtualPeer>()

        fun registerPeer(peer: VirtualPeer) {
            // 1. Replay existing peers' key exchanges to the new peer
            for (ke in keyExchanges) {
                peer.onReceiveKeyExchange(ke)
            }
            // 2. Publish new peer's key exchange
            val newKe = peer.buildKeyExchangePacket()
            keyExchanges.add(newKe)
            for (existing in clients) {
                existing.onReceiveKeyExchange(newKe)
            }
            clients.add(peer)
        }

        fun disconnectPeer(peer: VirtualPeer) {
            clients.remove(peer)
        }

        fun reconnectPeer(peer: VirtualPeer) {
            if (!clients.contains(peer)) {
                clients.add(peer)
            }
        }

        fun broadcast(sender: VirtualPeer, packet: ByteArray) {
            assertEquals(Protocol.OP_DATA, packet[0])
            val r = Protocol.Reader(packet, 1)
            val rawPayload = r.bytes()

            val msgId = nextId++
            val ts = System.currentTimeMillis()

            messageLog.add(Protocol.SyncMessage(msgId, ts, rawPayload))

            // Build server-to-client OP_DATA frame: [0x32][u64 id][u64 ts][payload]
            val w = Protocol.Writer()
            w.u8(Protocol.OP_DATA.toInt())
            w.u64(msgId)
            w.u64(ts)
            w.bytes(rawPayload)
            val serverFrame = w.toByteArray()

            for (client in clients) {
                client.onReceiveData(serverFrame)
            }
        }

        fun handleSync(lastSeenId: Long): ByteArray {
            val missed = messageLog.filter { it.id > lastSeenId }
            val w = Protocol.Writer()
            w.u8(Protocol.OP_SYNC_RESPONSE.toInt())
            w.u32(missed.size.toLong())
            for (m in missed) {
                w.u64(m.id)
                w.u64(m.timestamp)
                w.bytes(m.payload)
            }
            return w.toByteArray()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test Scenarios
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun test_peer_discovery_and_key_exchange() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")
        val charlie = VirtualPeer("Charlie")

        relay.registerPeer(alice)
        relay.registerPeer(bob)
        relay.registerPeer(charlie)

        // Everyone must have discovered each other with trusted keys
        assertEquals(2, alice.knownPeers.size)
        assertTrue(alice.knownPeers.containsKey(bob.fingerprint))
        assertTrue(alice.knownPeers.containsKey(charlie.fingerprint))

        assertEquals(2, bob.knownPeers.size)
        assertTrue(bob.knownPeers.containsKey(alice.fingerprint))
        assertTrue(bob.knownPeers.containsKey(charlie.fingerprint))

        assertEquals(2, charlie.knownPeers.size)
        assertTrue(charlie.knownPeers.containsKey(alice.fingerprint))
        assertTrue(charlie.knownPeers.containsKey(bob.fingerprint))
    }

    @Test
    fun test_group_chat_e2ee_delivery() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")
        val charlie = VirtualPeer("Charlie")

        relay.registerPeer(alice)
        relay.registerPeer(bob)
        relay.registerPeer(charlie)

        // Alice sends a message to the group
        val groupMessageText = "Hello everyone in post-quantum LAN!"
        val packet = alice.sendGroupMessage(groupMessageText)
        relay.broadcast(alice, packet)

        // Bob must receive and verify the message
        assertEquals(1, bob.receivedMessages.size)
        val bobMsg = bob.receivedMessages[0]
        assertEquals("Alice", bobMsg.senderName)
        assertEquals(alice.fingerprint, bobMsg.senderFingerprint)
        assertEquals(groupMessageText, bobMsg.content)
        assertEquals("group", bobMsg.conversationId)
        assertFalse(bobMsg.isOwn)

        // Charlie must also receive and verify the message
        assertEquals(1, charlie.receivedMessages.size)
        val charlieMsg = charlie.receivedMessages[0]
        assertEquals("Alice", charlieMsg.senderName)
        assertEquals(groupMessageText, charlieMsg.content)
        assertEquals("group", charlieMsg.conversationId)
        assertFalse(charlieMsg.isOwn)

        // Alice receives her own echo for conversation history and local confirmation
        assertEquals(1, alice.receivedMessages.size)
        val aliceMsg = alice.receivedMessages[0]
        assertEquals("Alice", aliceMsg.senderName)
        assertEquals(groupMessageText, aliceMsg.content)
        assertEquals("group", aliceMsg.conversationId)
        assertTrue(aliceMsg.isOwn)
    }

    @Test
    fun test_direct_message_isolation() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")
        val charlie = VirtualPeer("Charlie")

        relay.registerPeer(alice)
        relay.registerPeer(bob)
        relay.registerPeer(charlie)

        // Alice sends a private DM to Bob only
        val secretDmText = "Bob, this is a confidential 1:1 message."
        val packet = alice.sendDirectMessage(secretDmText, recipientFp = bob.fingerprint)
        relay.broadcast(alice, packet)

        // Bob MUST be able to decrypt the DM
        assertEquals(1, bob.receivedMessages.size)
        val bobMsg = bob.receivedMessages[0]
        assertEquals("Alice", bobMsg.senderName)
        assertEquals(secretDmText, bobMsg.content)
        assertEquals("dm:${alice.fingerprint}", bobMsg.conversationId)
        assertFalse(bobMsg.isOwn)

        // Charlie MUST NOT be able to decrypt or store the DM (zero-knowledge)
        assertEquals(0, charlie.receivedMessages.size)

        // Alice sees her own sent DM mapped to Bob's thread
        assertEquals(1, alice.receivedMessages.size)
        val aliceMsg = alice.receivedMessages[0]
        assertEquals("Alice", aliceMsg.senderName)
        assertEquals(secretDmText, aliceMsg.content)
        assertEquals("dm:${bob.fingerprint}", aliceMsg.conversationId)
        assertTrue(aliceMsg.isOwn)
    }

    @Test
    fun test_bidirectional_dm_thread_symmetry() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")

        relay.registerPeer(alice)
        relay.registerPeer(bob)

        // 1. Alice writes to Bob
        relay.broadcast(alice, alice.sendDirectMessage("Ping Bob", bob.fingerprint))

        // 2. Bob replies to Alice
        relay.broadcast(bob, bob.sendDirectMessage("Pong Alice", alice.fingerprint))

        // Alice's thread view
        assertEquals(2, alice.receivedMessages.size)
        assertEquals("dm:${bob.fingerprint}", alice.receivedMessages[0].conversationId)
        assertEquals("dm:${bob.fingerprint}", alice.receivedMessages[1].conversationId)

        // Bob's thread view
        assertEquals(2, bob.receivedMessages.size)
        assertEquals("dm:${alice.fingerprint}", bob.receivedMessages[0].conversationId)
        assertEquals("dm:${alice.fingerprint}", bob.receivedMessages[1].conversationId)
    }

    @Test
    fun test_history_sync_after_offline() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")
        val charlie = VirtualPeer("Charlie")

        // All 3 peers register and exchange public keys
        relay.registerPeer(alice)
        relay.registerPeer(bob)
        relay.registerPeer(charlie)

        // Charlie temporarily goes offline (disconnects from live broadcast)
        relay.disconnectPeer(charlie)

        // Alice and Bob exchange 3 group messages while Charlie is offline
        // (Since Charlie was known, Alice & Bob encrypted entries for Charlie too)
        relay.broadcast(alice, alice.sendGroupMessage("Msg 1"))
        relay.broadcast(bob, bob.sendGroupMessage("Msg 2"))
        relay.broadcast(alice, alice.sendGroupMessage("Msg 3"))

        // Also a private DM between Alice and Bob
        relay.broadcast(alice, alice.sendDirectMessage("Private between A and B", bob.fingerprint))

        // Charlie has received 0 live messages while offline
        assertEquals(0, charlie.receivedMessages.size)
        assertEquals(0L, charlie.lastSeenId)

        // Now Charlie reconnects and requests history synchronization from lastSeenId = 0
        relay.reconnectPeer(charlie)
        val syncResponse = relay.handleSync(lastSeenId = 0L)
        charlie.onReceiveSyncResponse(syncResponse)

        // Charlie must have caught up on all 3 group messages, but NOT the private DM between A and B
        assertEquals(3, charlie.receivedMessages.size)
        assertEquals("Msg 1", charlie.receivedMessages[0].content)
        assertEquals("Msg 2", charlie.receivedMessages[1].content)
        assertEquals("Msg 3", charlie.receivedMessages[2].content)
        assertEquals(3L, charlie.lastSeenId) // id 3 is the latest message decryptable by Charlie
    }

    @Test
    fun test_replay_protection() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")

        relay.registerPeer(alice)
        relay.registerPeer(bob)

        val packet = alice.sendGroupMessage("Non-repeatable message")
        relay.broadcast(alice, packet)
        assertEquals(1, bob.receivedMessages.size)

        // Attacker intercepts and replays the EXACT same frame to Bob
        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(999L) // attacker might try new server msg id
        w.u64(System.currentTimeMillis())
        w.bytes(Protocol.Reader(packet, 1).bytes())
        val replayedFrame = w.toByteArray()

        val accepted = bob.onReceiveData(replayedFrame)
        assertFalse("Replayed frame with identical nonce must be REJECTED", accepted)
        assertEquals("Bob's inbox must still contain exactly 1 message", 1, bob.receivedMessages.size)
    }

    @Test
    fun test_mitm_tampering_rejected() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")

        relay.registerPeer(alice)
        relay.registerPeer(bob)

        val packet = alice.sendGroupMessage("Authentic message")
        val r = Protocol.Reader(packet, 1)
        val payload = r.bytes()

        // Locate Bob's recipient ID in the payload and corrupt Bob's ciphertext/encKey
        val bobId = hexToBytes(bob.fingerprint)
        var bobOffset = -1
        for (i in 0 until payload.size - bobId.size) {
            var match = true
            for (j in bobId.indices) {
                if (payload[i + j] != bobId[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                bobOffset = i
                break
            }
        }
        assertTrue("Bob's recipient ID must be found in payload", bobOffset != -1)

        // Tamper 50 bytes after Bob's ID (inside Bob's encKey / ciphertext)
        val tamperPos = bobOffset + bobId.size + 10
        payload[tamperPos] = (payload[tamperPos].toInt() xor 0xFF).toByte()

        val w = Protocol.Writer()
        w.u8(Protocol.OP_DATA.toInt())
        w.u64(100L)
        w.u64(System.currentTimeMillis())
        w.bytes(payload)
        val tamperedFrame = w.toByteArray()

        val accepted = bob.onReceiveData(tamperedFrame)
        assertFalse("Tampered ciphertext must fail GCM decryption or signature check", accepted)
        assertEquals(0, bob.receivedMessages.size)
    }

    @Test
    fun testNicknameChangeAndPeerResolution() {
        val relay = VirtualRelay()
        val alice = VirtualPeer("Alice")
        val bob = VirtualPeer("Bob")
        val charlie = VirtualPeer("Charlie")

        relay.registerPeer(alice)
        relay.registerPeer(bob)
        relay.registerPeer(charlie)

        // 1. Alice changes her nickname to "AliceNew"
        alice.name = "AliceNew"
        val aliceFrame = alice.sendDirectMessage("Hello Charlie from AliceNew", charlie.fingerprint)
        relay.broadcast(alice, aliceFrame)

        // On Charlie's side:
        val charlieMsg = charlie.receivedMessages.last()
        assertEquals("Hello Charlie from AliceNew", charlieMsg.content)
        assertEquals("AliceNew", charlieMsg.senderName)
        assertEquals(alice.fingerprint, charlieMsg.senderFingerprint)
        assertFalse("Message from Alice to Charlie must not be marked isOwn on Charlie", charlieMsg.isOwn)
        assertEquals("dm:${alice.fingerprint}", charlieMsg.conversationId)

        // On Alice's side:
        val aliceEcho = alice.receivedMessages.last()
        assertTrue("Message sent by Alice must be marked isOwn on Alice", aliceEcho.isOwn)
        assertEquals("dm:${charlie.fingerprint}", aliceEcho.conversationId)

        // 2. Bob changes his nickname to "Charlie" (name collision / identical name)
        bob.name = "Charlie"
        val bobFrame = bob.sendDirectMessage("Impersonation test", charlie.fingerprint)
        relay.broadcast(bob, bobFrame)

        val impersonationMsg = charlie.receivedMessages.last()
        assertEquals("Impersonation test", impersonationMsg.content)
        assertEquals("Charlie", impersonationMsg.senderName)
        // MUST NOT be marked isOwn because fingerprint doesn't match Charlie!
        assertFalse("Even with identical senderName, isOwn must be false when fingerprint differs", impersonationMsg.isOwn)
        assertEquals(bob.fingerprint, impersonationMsg.senderFingerprint)
        assertEquals("dm:${bob.fingerprint}", impersonationMsg.conversationId)
    }
}
