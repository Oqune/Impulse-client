package com.example.impulse

import android.content.Context
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.PublicKeyRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.db.MessageEntity
import com.example.impulse.security.PqcCrypto
import com.example.impulse.security.SecureKeyManager
import com.example.impulse.security.SecureStorage
import com.example.impulse.security.TrustedCertManager
import com.example.impulse.transport.ConnectionState
import com.example.impulse.transport.Protocol
import com.example.impulse.transport.WebTransportClient
import com.example.impulse.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler

class ChatController(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught coroutine exception", e)
        }
    )
    private val secure = SecureStorage(context)
    private val certManager = TrustedCertManager(context)
    private val repo = MessageRepository(context)

    private var client: WebTransportClient? = null
    var currentServer: ServerConfig? = null
        private set
    var clientName: String = ""
        private set

    private lateinit var keyManager: SecureKeyManager
    private lateinit var keyRepo: PublicKeyRepository

    val publicKeyHash: String
        get() {
            if (!::keyManager.isInitialized) return ""
            return try {
                keyManager.fingerprintForBytes(keyManager.getKemPublicKey()).take(8)
            } catch (_: Exception) {
                ""
            }
        }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val listeners = mutableListOf<(DecryptedMessage) -> Unit>()

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    data class DecryptedMessage(
        val serverMsgId: Long,
        val sender: String,
        val plaintext: String,
        val isOwn: Boolean,
        val timestamp: Long = 0L
    )

    fun addMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.remove(l) }

    private fun ensureKeyPair() {
        keyManager = SecureKeyManager
        keyManager.ensureKeyPair(context)
        keyRepo = PublicKeyRepository(context)
    }

    fun connect(server: ServerConfig, name: String) {
        val cur = _state.value
        if (cur == ConnectionState.CONNECTING ||
            cur == ConnectionState.CONNECTED ||
            cur == ConnectionState.AUTHENTICATING ||
            cur == ConnectionState.AUTHENTICATED ||
            cur == ConnectionState.READY
        ) {
            LogManager.w(TAG, "connect ignored: already active (state=$cur)")
            return
        }
        userDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentServer = server
        clientName = name
        LogManager.i(TAG, "connect: server=${server.id} name='$name' state=$cur")
        ensureKeyPair()
        _lastError.value = null

        val hashes = certManager.getHashes(server.id)

        if (!certManager.isTrusted(server.id)) {
            _state.value = ConnectionState.ERROR
            _lastError.value = "Нет доверенного сертификата сервера. Отсканируйте QR-код с экрана сервера."
            LogManager.w(TAG, "No trusted cert for ${server.id}; QR scan required.")
            return
        }

        LogManager.i(TAG, "connect: server=${server.id} ip=${server.ipAddress} pinnedHashes=${hashes.size}")

        val clientHolder = arrayOfNulls<WebTransportClient>(1)
        val wtClient = WebTransportClient(
            context = context,
            serverCertHashes = hashes,
            onFrame = { raw -> handleIncoming(raw) },
            onState = { s -> onTransportState(s) },
            onCertHashPush = { h -> certManager.rotateHash(server.id, h) },
            onSessionError = { code -> _lastError.value = "Ошибка сессии WebTransport (code=$code). " +
                "Возможно, сервер не поддерживает HTTP/3 (QUIC) или недоступен на ${server.ipAddress}:${server.port}." },
            onReady = {
                scope.launch {
                    sendAuth(clientHolder[0])
                }
            },
        )
        clientHolder[0] = wtClient
        client = wtClient
        scope.launch {
            try {
                val purged = repo.purgeExpired()
                val tempCleaned = repo.clearTempMessages(server.id)
                LogManager.i(TAG, "purged $purged expired + $tempCleaned temp messages on connect")
            } catch (e: Exception) {
                LogManager.w(TAG, "cleanup failed (non-fatal)", e)
            }
        }
        LogManager.i(TAG, "connecting to ${server.name}")
        wtClient.connect(server.getWebTransportUrl())
    }

    private var autoReconnectEnabled = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var userDisconnect = false

    fun setAutoReconnect(enabled: Boolean) { autoReconnectEnabled = enabled }

    fun disconnect() {
        userDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        client?.disconnect()
        client = null
        _state.value = ConnectionState.DISCONNECTED
        _lastError.value = null
    }

    fun clearError() { _lastError.value = null }

    /** Clears all local messages for the current server and re-syncs from the server. */
    suspend fun clearHistory() {
        val serverId = currentServer?.id ?: return
        LogManager.i(TAG, "clearHistory: clearing local messages for server=$serverId")
        repo.clearServer(serverId)
        if (_state.value == ConnectionState.READY) {
            val syncOk = client?.send(Protocol.buildSync(0)) ?: false
            LogManager.i(TAG, "clearHistory: sync request sent (ok=$syncOk)")
        }
    }

    private fun onTransportState(s: ConnectionState) {
        LogManager.i(TAG, "transport state → $s")
        when (s) {
            ConnectionState.CONNECTING -> {
                _state.value = s
                _lastError.value = null
            }
            ConnectionState.CONNECTED -> {
                _state.value = s
            }
            ConnectionState.ERROR -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                _state.value = s
                cancelAuthTimeout()
                scheduleReconnect()
            }
            else -> { }
        }
    }

    private suspend fun sendAuth(transport: WebTransportClient? = client) {
        val server = currentServer ?: run {
            LogManager.e(TAG, "sendAuth: no current server configured")
            _state.value = ConnectionState.ERROR
            return
        }
        val password = server.password
        val frame = Protocol.buildAuth(password)
        val ok = transport?.send(frame) ?: false
        if (!ok) {
            LogManager.e(TAG, "sendAuth FAILED — transport.send() returned false (state=${_state.value})")
            return
        }
        _state.value = ConnectionState.AUTHENTICATING
        LogManager.i(TAG, "AUTH SENT → waiting for server response (timeout=${AUTH_TIMEOUT_MS}ms)")
        startAuthTimeout()
    }

    private var authTimeoutJob: kotlinx.coroutines.Job? = null
    private fun startAuthTimeout() {
        cancelAuthTimeout()
        authTimeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            val currentState = _state.value
            if (currentState == ConnectionState.CONNECTED ||
                currentState == ConnectionState.AUTHENTICATING
            ) {
                LogManager.e(TAG, "AUTH TIMEOUT after ${AUTH_TIMEOUT_MS}ms — state=$currentState → ERROR")
                _state.value = ConnectionState.ERROR
            } else {
                LogManager.d(TAG, "auth timeout fired but state=$currentState (already past auth), ignoring")
            }
        }
    }

    private fun cancelAuthTimeout() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        LogManager.d(TAG, "auth timeout cancelled")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (!autoReconnectEnabled || userDisconnect) return
        val server = currentServer ?: return
        val name = clientName
        if (server.id.isEmpty()) return
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!autoReconnectEnabled || userDisconnect) return@launch
            if (_state.value == ConnectionState.ERROR || _state.value == ConnectionState.DISCONNECTED) {
                LogManager.i(TAG, "auto-reconnecting to ${server.name}")
                connect(server, name)
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-Recipient KEM Wrapping — send
    // ------------------------------------------------------------------

    suspend fun sendChat(plaintext: String): Boolean {
        if (_state.value != ConnectionState.READY) {
            LogManager.w(TAG, "sendChat ignored: not READY (state=${_state.value})")
            return false
        }
        val serverId = currentServer?.id ?: return false

        val allKemKeys = keyRepo.getAllKemPublicKeys(serverId)
        if (allKemKeys.isEmpty()) {
            LogManager.w(TAG, "No peer keys cached; cannot send.")
            return false
        }

        // 1. Build and sign the inner envelope
        val innerJson = Protocol.buildInnerEnvelope(clientName, "", plaintext)
        val signature = keyManager.signDsa(innerJson)
        val signedEnvelope = Protocol.buildInnerEnvelope(
            clientName,
            android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
            plaintext
        )

        // 2. For each recipient, encapsulate and encrypt
        val recipients = mutableListOf<Triple<String, ByteArray, ByteArray>>()
        for (pubKey in allKemKeys) {
            val (encKey, sharedSecret) = keyManager.encapsulateKem(pubKey)
            val ciphertext = PqcCrypto.aesEncrypt(sharedSecret, signedEnvelope)
            val recipientId = keyManager.fingerprintForBytes(pubKey)
            recipients.add(Triple(recipientId, encKey, ciphertext))
        }

        // 3. Also add our own recipient entry (for self-decryption)
        val ownPub = keyManager.getKemPublicKey()
        val (ownEncKey, ownSharedSecret) = keyManager.encapsulateKem(ownPub)
        val ownCiphertext = PqcCrypto.aesEncrypt(ownSharedSecret, signedEnvelope)
        recipients.add(Triple(keyManager.getFingerprint(), ownEncKey, ownCiphertext))

        // 4. Build Per-Recipient blob
        val blob = buildPerRecipientBlob(recipients)

        // 5. Send via OP_DATA
        val frame = Protocol.buildData(blob)
        val ok = client?.send(frame) ?: false

        if (ok) {
            val tempId = -(System.currentTimeMillis())
            val msg = DecryptedMessage(tempId, clientName, plaintext, true, System.currentTimeMillis())
            synchronized(listeners) { listeners.forEach { it(msg) } }
        }
        return ok
    }

    private fun buildPerRecipientBlob(recipients: List<Triple<String, ByteArray, ByteArray>>): ByteArray {
        val w = Protocol.Writer()
        // sender_kem_pubhash: 32 bytes (SHA-256 of own KEM public key, first 32 bytes)
        val senderPubHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyManager.getKemPublicKey()).copyOf(32)
        w.bytes(senderPubHash)
        // count: u32
        w.u32(recipients.size.toLong())
        for ((id, encKey, ciphertext) in recipients) {
            // recipient_id: 32 bytes
            val idBytes = hexToBytes(id)
            w.bytes(idBytes)
            // encKey
            w.bytes(encKey)
            // ciphertext
            w.bytes(ciphertext)
        }
        return w.toByteArray()
    }

    // ------------------------------------------------------------------
    // Per-Recipient KEM Wrapping — receive
    // ------------------------------------------------------------------

    private fun onData(r: Protocol.Reader) {
        val frame = Protocol.parseData(r)
        val serverId = currentServer?.id ?: return

        try {
            val payload = frame.payload
            val pr = Protocol.Reader(payload)

            // sender_kem_pubhash: 32 bytes
            val senderPubHash = pr.readBytes(32)
            val senderFingerprint = senderPubHash.joinToString("") { "%02x".format(it) }

            // count: u32
            val count = pr.u32().toInt()

            // Find our recipient entry
            val ownFingerprint = keyManager.getFingerprint()
            var found = false

            repeat(count) {
                val recipientId = pr.readBytes(32)
                val recipientFp = recipientId.joinToString("") { "%02x".format(it) }
                val encKey = pr.bytes()
                val ciphertext = pr.bytes()

                if (recipientFp == ownFingerprint && !found) {
                    found = true
                    // Decapsulate and decrypt
                    val sharedSecret = keyManager.decapsulateKem(encKey)
                    val innerBytes = PqcCrypto.aesDecrypt(sharedSecret, ciphertext)
                    val env = Protocol.parseInnerEnvelope(innerBytes) ?: return

                    val isOwn = env.sender == clientName
                    val realId = frame.serverMsgId

                    scope.launch {
                        // Verify ML-DSA-65 signature
                        val dsaPub = keyRepo.getDsaPublicKey(serverId, senderFingerprint)
                        if (dsaPub != null) {
                            val canonical = Protocol.buildInnerEnvelope(env.sender, "", env.content)
                            val sigBytes = runCatching {
                                android.util.Base64.decode(env.signature, android.util.Base64.NO_WRAP)
                            }.getOrNull()
                            if (sigBytes != null && sigBytes.isNotEmpty()) {
                                if (!keyManager.verifyDsa(dsaPub, canonical, sigBytes)) {
                                    LogManager.w(TAG, "ML-DSA-65 signature verification failed")
                                    return@launch
                                }
                            }
                        }

                        repo.upsert(
                            MessageEntity(
                                serverId = serverId,
                                serverMsgId = realId,
                                sender = env.sender,
                                ciphertext = ciphertext,
                                iv = byteArrayOf(),
                                timestamp = if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis(),
                                isOwn = isOwn
                            )
                        )
                    }

                    val msg = DecryptedMessage(realId, env.sender, env.content, isOwn,
                        if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis())
                    synchronized(listeners) { listeners.forEach { it(msg) } }
                }
            }

            if (!found) {
                LogManager.d(TAG, "Per-Recipient message not for us, skipping")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Per-Recipient parse failed", e)
        }
    }

    /**
     * Decrypts a per-recipient blob from a sync response or stored message.
     * Returns (senderFingerprint, plaintext, isOwn, sender) or null on failure.
     */
    private fun decryptPerRecipientBlob(
        payload: ByteArray,
        serverId: String
    ): Triple<String, String, String>? {
        val pr = Protocol.Reader(payload)

        val senderPubHash = pr.readBytes(32)
        val senderFingerprint = senderPubHash.joinToString("") { "%02x".format(it) }

        val count = pr.u32().toInt()
        val ownFingerprint = keyManager.getFingerprint()

        repeat(count) {
            val recipientId = pr.readBytes(32)
            val recipientFp = recipientId.joinToString("") { "%02x".format(it) }
            val encKey = pr.bytes()
            val ciphertext = pr.bytes()

            if (recipientFp == ownFingerprint) {
                val sharedSecret = keyManager.decapsulateKem(encKey)
                val innerBytes = PqcCrypto.aesDecrypt(sharedSecret, ciphertext)
                val env = Protocol.parseInnerEnvelope(innerBytes) ?: return null
                return Triple(senderFingerprint, env.sender, env.content)
            }
        }
        return null
    }

    private fun onSyncResponse(r: Protocol.Reader) {
        val resp = Protocol.parseSyncResponse(r)
        val serverId = currentServer?.id ?: return
        LogManager.i(TAG, "SyncResponse: ${resp.messages.size} messages")
        for (m in resp.messages) {
            val result = runCatching { decryptPerRecipientBlob(m.payload, serverId) }.getOrNull()
            if (result == null) {
                LogManager.w(TAG, "SyncResponse decrypt failed for ${m.id}")
                continue
            }
            val (senderFingerprint, sender, content) = result
            val isOwn = sender == clientName
            LogManager.d(TAG, "onSyncResponse: msgId=${m.id} sender='$sender' isOwn=$isOwn")
            scope.launch {
                repo.upsert(
                    MessageEntity(
                        serverId = serverId,
                        serverMsgId = m.id,
                        sender = sender,
                        ciphertext = m.payload,
                        iv = byteArrayOf(),
                        timestamp = m.timestamp,
                        isOwn = isOwn
                    )
                )
            }
            val msg = DecryptedMessage(m.id, sender, content, isOwn, m.timestamp)
            synchronized(listeners) { listeners.forEach { it(msg) } }
        }
    }

    private fun onHeartbeat() {
        LogManager.d(TAG, "heartbeat received from server")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            LogManager.i(TAG, "heartbeat started (interval=${HEARTBEAT_INTERVAL_MS}ms)")
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive) break
                val c = client ?: break
                val ok = c.send(Protocol.buildHeartbeat())
                LogManager.d(TAG, "heartbeat sent (ok=$ok)")
            }
            LogManager.i(TAG, "heartbeat loop exited")
        }
    }

    fun decryptEntity(entity: MessageEntity): DecryptedMessage? {
        val serverId = currentServer?.id ?: return null
        return try {
            val result = decryptPerRecipientBlob(entity.ciphertext, serverId) ?: return null
            val (_, sender, content) = result
            DecryptedMessage(
                serverMsgId = entity.serverMsgId,
                sender = sender,
                plaintext = content,
                isOwn = entity.isOwn,
                timestamp = entity.timestamp
            )
        } catch (ex: Exception) {
            LogManager.w(TAG, "decryptEntity failed for ${entity.serverMsgId}", ex)
            null
        }
    }

    suspend fun decryptHistory(serverId: String): List<DecryptedMessage> {
        val entities = repo.load(serverId)
        return entities.mapNotNull { e ->
            try {
                val result = decryptPerRecipientBlob(e.ciphertext, serverId) ?: return@mapNotNull null
                val (_, sender, content) = result
                DecryptedMessage(
                    serverMsgId = e.serverMsgId,
                    sender = sender,
                    plaintext = content,
                    isOwn = e.isOwn,
                    timestamp = e.timestamp
                )
            } catch (ex: Exception) {
                LogManager.w(TAG, "history decrypt failed for ${e.serverMsgId}", ex)
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // Key exchange handling
    // ------------------------------------------------------------------

    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
        val opcodeName = when (opcode) {
            Protocol.OP_AUTH_RESULT -> "AuthResult"
            Protocol.OP_SYNC_RESPONSE -> "SyncResponse"
            Protocol.OP_DATA -> "Data"
            Protocol.OP_HEARTBEAT -> "Heartbeat"
            Protocol.OP_KEY_EXCHANGE -> "KeyExchange"
            Protocol.OP_KEY_EXCHANGE_KEM -> "KeyExchangeKem"
            Protocol.OP_KEY_EXCHANGE_DSA -> "KeyExchangeDsa"
            Protocol.OP_NEW_CERT_HASH -> "NewCertHash"
            else -> "0x%02x".format(opcode)
        }
        LogManager.d(TAG, "RX $opcodeName (${raw.size} bytes)")
        try {
            val r = Protocol.Reader(raw, 1)
            when (opcode) {
                Protocol.OP_AUTH_RESULT -> onAuthResult(r)
                Protocol.OP_SYNC_RESPONSE -> onSyncResponse(r)
                Protocol.OP_DATA -> onData(r)
                Protocol.OP_HEARTBEAT -> onHeartbeat()
                Protocol.OP_KEY_EXCHANGE -> onKeyExchange(r)
                Protocol.OP_KEY_EXCHANGE_KEM -> onKeyExchange(r, Protocol.OP_KEY_EXCHANGE_KEM)
                Protocol.OP_KEY_EXCHANGE_DSA -> onKeyExchange(r, Protocol.OP_KEY_EXCHANGE_DSA)
                Protocol.OP_NEW_CERT_HASH -> { }
                else -> LogManager.w(TAG, "UNKNOWN opcode 0x%02x (${raw.size} bytes)".format(opcode))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "PARSE FAILED opcode=0x%02x: ${e.message}".format(opcode), e)
        }
    }

    private fun onAuthResult(r: Protocol.Reader) {
        val res = Protocol.parseAuthResult(r)
        if (!res.success) {
            LogManager.e(TAG, "AUTH REJECTED by server: ${res.errorMessage ?: "no reason"}")
            cancelAuthTimeout()
            _state.value = ConnectionState.ERROR
            _lastError.value = "Аутентификация отклонена сервером: ${res.errorMessage ?: "нет причины"}"
            return
        }
        cancelAuthTimeout()
        LogManager.i(TAG, "AUTH SUCCESS — publishing keys + requesting sync (clientName='$clientName')")
        _state.value = ConnectionState.AUTHENTICATING
        scope.launch {
            // Cache own KEM public key for self-decryption
            val ownFp = keyManager.getFingerprint()
            keyRepo.cacheKey(
                currentServer?.id ?: "",
                ownFp,
                keyManager.getKemPublicKey(),
                keyManager.getDsaPublicKey()
            )

            // Send KEM and DSA keys with new opcodes
            val kemOk = keyManager.getKemPublicKey().let { pub ->
                client?.send(Protocol.buildKeyExchange(pub, Protocol.OP_KEY_EXCHANGE_KEM))
            } ?: false
            LogManager.i(TAG, "KeyExchange ML-KEM sent (ok=$kemOk)")
            val dsaOk = keyManager.getDsaPublicKey().let { pub ->
                client?.send(Protocol.buildKeyExchange(pub, Protocol.OP_KEY_EXCHANGE_DSA))
            } ?: false
            LogManager.i(TAG, "KeyExchange ML-DSA-65 sent (ok=$dsaOk)")

            val lastSeen = repo.lastSeenId(currentServer?.id ?: "")
            val syncOk = client?.send(Protocol.buildSync(lastSeen)) ?: false
            LogManager.i(TAG, "Sync request sent (ok=$syncOk, lastSeen=$lastSeen)")

            _state.value = ConnectionState.AUTHENTICATED
            _state.value = ConnectionState.READY
            LogManager.i(TAG, "READY")

            // Notify new participants
            val messages = repo.load(currentServer?.id ?: "")
            if (messages.isEmpty()) {
                _lastError.value = "Вы присоединились к чату. Сообщения до вашего подключения недоступны."
            }
        }
        startHeartbeat()
    }

    private fun onKeyExchange(r: Protocol.Reader) {
        val frame = Protocol.parseKeyExchange(r)
        val key = frame.publicKey
        val serverId = currentServer?.id ?: return

        try {
            val kf = java.security.KeyFactory.getInstance("Dilithium", "BCPQC")
            kf.generatePublic(java.security.spec.X509EncodedKeySpec(key))
            val fp = keyManager.fingerprintForBytes(key)
            scope.launch { keyRepo.cacheKey(serverId, fp, null, key) }
            LogManager.i(TAG, "KeyExchange ML-DSA-65 received, cached")
        } catch (_: Exception) {
            try {
                val kf = java.security.KeyFactory.getInstance("Kyber", "BCPQC")
                kf.generatePublic(java.security.spec.X509EncodedKeySpec(key))
                val fp = keyManager.fingerprintForBytes(key)
                scope.launch { keyRepo.cacheKey(serverId, fp, key, null) }
                LogManager.i(TAG, "KeyExchange ML-KEM received, cached")
            } catch (e: Exception) {
                LogManager.w(TAG, "KeyExchange validation failed", e)
            }
        }
    }

    private fun onKeyExchange(r: Protocol.Reader, opcode: Byte) {
        val frame = Protocol.parseKeyExchange(r)
        val key = frame.publicKey
        val serverId = currentServer?.id ?: return

        try {
            if (opcode == Protocol.OP_KEY_EXCHANGE_DSA) {
                val kf = java.security.KeyFactory.getInstance("Dilithium", "BCPQC")
                kf.generatePublic(java.security.spec.X509EncodedKeySpec(key))
                val fp = keyManager.fingerprintForBytes(key)
                scope.launch { keyRepo.cacheKey(serverId, fp, null, key) }
                LogManager.i(TAG, "KeyExchange ML-DSA-65 received, cached")
            } else {
                val kf = java.security.KeyFactory.getInstance("Kyber", "BCPQC")
                kf.generatePublic(java.security.spec.X509EncodedKeySpec(key))
                val fp = keyManager.fingerprintForBytes(key)
                scope.launch { keyRepo.cacheKey(serverId, fp, key, null) }
                LogManager.i(TAG, "KeyExchange ML-KEM received, cached")
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "KeyExchange validation failed", e)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        return bytes
    }

    companion object {
        private const val TAG = "ChatController"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val AUTH_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
