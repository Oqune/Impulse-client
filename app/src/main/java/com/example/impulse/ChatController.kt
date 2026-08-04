package com.example.impulse

import android.content.Context
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.PublicKeyRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.db.MessageEntity
import com.example.impulse.security.PqcCrypto
import com.example.impulse.security.SecureKeyManager
import com.example.impulse.security.TrustedCertManager
import com.example.impulse.transport.ConnectionState
import com.example.impulse.transport.Protocol
import com.example.impulse.transport.WebTransportClient
import com.example.impulse.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

class ChatController(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught coroutine exception", e)
        }
    )
    private val certManager = TrustedCertManager(context)
    private val repo = MessageRepository(context)
    private val outboxPrefs = context.getSharedPreferences("impulse_outbox", Context.MODE_PRIVATE)

    private val lock = Any()

    @Volatile private var client: WebTransportClient? = null
    @Volatile var currentServer: ServerConfig? = null
        private set
    @Volatile var clientName: String = ""
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

    @Volatile private var heartbeatJob: kotlinx.coroutines.Job? = null
    @Volatile private var reconnectJob: kotlinx.coroutines.Job? = null
    @Volatile private var authTimeoutJob: kotlinx.coroutines.Job? = null
    @Volatile private var autoReconnectEnabled = false
    @Volatile private var userDisconnect = false
    @Volatile private var reconnectAttempts = 0
    @Volatile private var authChallengeNonce = CompletableDeferred<ByteArray?>()
    @Volatile private var pendingSaltB64: String = ""

    private data class PendingMessage(
        val serverMsgId: Long,
        val senderFingerprint: String,
        val env: Protocol.InnerEnvelope,
        val ciphertext: ByteArray,
        val timestamp: Long,
        val isOwn: Boolean,
        val queuedAt: Long = System.currentTimeMillis()
    )

    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessage>>()
    private val pendingMessagesLock = Any()

    private data class OutboxEntry(
        val frame: ByteArray,
        val plaintext: String,
        val queuedAt: Long = System.currentTimeMillis(),
        var retries: Int = 0
    )
    private val outbox = mutableListOf<OutboxEntry>()
    private val outboxLock = Any()
    @Volatile private var flushOutboxRunning = false

    private val processedMsgIds = LinkedHashSet<Long>(512)
    private val dedupLock = Any()

    data class DecryptedMessage(
        val serverMsgId: Long,
        val sender: String,
        val senderFingerprint: String,
        val plaintext: String,
        val isOwn: Boolean,
        val timestamp: Long = 0L,
        val conversationId: String = "group"
    )

    fun addMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.remove(l) }

    private fun ensureKeyPair() {
        keyManager = SecureKeyManager
        PqcCrypto.ensureProvider()
        keyManager.ensureKeyPair(context)
        keyRepo = PublicKeyRepository(context)
    }

    fun connect(server: ServerConfig, name: String) {
        synchronized(lock) {
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
        }
        LogManager.i(TAG, "connect: server=${server.id} name='$name'")
        // Key generation (ML-KEM + ML-DSA) can throw on some devices (e.g.
        // BouncyCastle PQC provider unavailable). Catch it so the app shows an
        // error instead of crashing, and record the stack for diagnostics.
        try {
            ensureKeyPair()
        } catch (t: Throwable) {
            LogManager.e(TAG, "ensureKeyPair failed on connect", t)
            _state.value = ConnectionState.ERROR
            _lastError.value = "Ошибка генерации ключей: ${t.message}"
            com.example.impulse.util.CrashLog.writeCrash(
                com.example.impulse.util.CrashLog.buildCrashReport(
                    thread = Thread.currentThread(),
                    throwable = t,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    release = android.os.Build.VERSION.RELEASE,
                    manufacturer = android.os.Build.MANUFACTURER,
                    model = android.os.Build.MODEL,
                    timeMillis = System.currentTimeMillis(),
                    extra = "ChatController.connect ensureKeyPair",
                )
            )
            return
        }
        _lastError.value = null

        val hashes = certManager.getHashes(server.id)

        if (!certManager.isTrusted(server.id)) {
            _state.value = ConnectionState.ERROR
            _lastError.value = "Нет доверенного сертификата сервера. Отсканируйте QR-код с экрана сервера."
            LogManager.w(TAG, "No trusted cert for ${server.id}; QR scan required.")
            return
        }

        LogManager.i(TAG, "connect: server=${server.id} ip=${server.ipAddress} pinnedHashes=${hashes.size}")

        synchronized(lock) {
            client?.destroy()
            client = null
        }
        authChallengeNonce = CompletableDeferred()
        pendingSaltB64 = ""

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
                LogManager.i(TAG, "onReady FIRED — launching sendAuth")
                scope.launch {
                    val liveClient = synchronized(lock) { client }
                    LogManager.i(TAG, "sendAuth coroutine started, transport=${liveClient != null}")
                    sendAuth(liveClient)
                }
            },
        )
        clientHolder[0] = wtClient
        synchronized(lock) { client = wtClient }
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

    fun setAutoReconnect(enabled: Boolean) { autoReconnectEnabled = enabled }

    fun disconnect() {
        synchronized(lock) {
            userDisconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            // Best-effort notify the server before tearing down the connection.
            runBlocking {
                try {
                    withTimeout(1000) {
                        client?.send(Protocol.buildDisconnect())
                    }
                } catch (_: Exception) { }
            }
            client?.destroy()
            client = null
        }
        pendingSaltB64 = ""
        // Keep the outbox across disconnect/reconnect so queued messages are
        // NOT lost (Bug: "outbox cleared on disconnect drops messages"). The
        // flush loop re-runs on the next READY. Only `userDisconnect` from the
        // UI should clear it explicitly (see disconnectUser).
        synchronized(outboxLock) { flushOutboxRunning = false }
        synchronized(pendingMessagesLock) { pendingMessages.clear() }
        authChallengeNonce.completeExceptionally(IllegalStateException("disconnected"))
        authChallengeNonce = CompletableDeferred()
        _state.value = ConnectionState.DISCONNECTED
        _lastError.value = null
        synchronized(dedupLock) { processedMsgIds.clear() }
    }

    /** Release all resources. Call when this controller will never be used again. */
    fun destroy() {
        disconnect()
        scope.cancel()
    }

    suspend fun clearHistory() {
        val serverId = currentServer?.id ?: return
        LogManager.i(TAG, "clearHistory: clearing local messages for server=$serverId")
        repo.clearServer(serverId)
        synchronized(dedupLock) { processedMsgIds.clear() }
        if (_state.value == ConnectionState.READY) {
            val c = synchronized(lock) { client }
            val syncOk = withContext(Dispatchers.IO) { c?.send(Protocol.buildSync(0)) ?: false }
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
                // The transport handshake succeeded — the network is healthy,
                // so reset the exponential-backoff counter for the next failure.
                reconnectAttempts = 0
            }
            ConnectionState.ERROR -> {
                synchronized(lock) {
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                }
                _state.value = s
                cancelAuthTimeout()
                scheduleReconnect()
            }
            else -> { }
        }
    }

    private suspend fun sendAuth(transport: WebTransportClient? = client) {
        LogManager.i(TAG, "sendAuth: STARTED (transport=${transport != null}, server=${currentServer?.id})")
        if (userDisconnect) return
        val server = currentServer ?: run {
            LogManager.e(TAG, "sendAuth: no current server configured")
            _state.value = ConnectionState.ERROR
            return
        }
        val password = server.password
        LogManager.i(TAG, "sendAuth: password=${password.length} chars, waiting for challenge nonce...")
        val nonce = try {
            withTimeout(5000) { authChallengeNonce.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "sendAuth: challenge nonce not received after 5s: ${e.message}")
            if (!userDisconnect) {
                _state.value = ConnectionState.ERROR
                scheduleReconnect()
            }
            return
        }
        LogManager.i(TAG, "sendAuth: building auth frame (password=${password.length} chars, nonce=${nonce?.size} bytes)")
        val frame: ByteArray
        try {
            val saltB64 = pendingSaltB64; pendingSaltB64 = ""
            frame = Protocol.buildAuth(password, nonce, saltB64)
            LogManager.i(TAG, "sendAuth: auth frame built OK (${frame.size} bytes)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "sendAuth: buildAuth FAILED: ${e.message}", e)
            if (!userDisconnect) {
                _state.value = ConnectionState.ERROR
                scheduleReconnect()
            }
            return
        }
        LogManager.i(TAG, "sendAuth: sending frame via transport...")
        val ok = withContext(Dispatchers.IO) { transport?.send(frame) ?: false }
        LogManager.i(TAG, "sendAuth: transport.send() returned $ok (transport=${transport != null})")
        if (!ok) {
            LogManager.e(TAG, "sendAuth FAILED — transport.send() returned false (state=${_state.value})")
            if (!userDisconnect) {
                _state.value = ConnectionState.ERROR
                scheduleReconnect()
            }
            return
        }
        _state.value = ConnectionState.AUTHENTICATING
        LogManager.i(TAG, "AUTH SENT → waiting for server response (timeout=${AUTH_TIMEOUT_MS}ms)")
        startAuthTimeout()
    }

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
                scheduleReconnect()
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
        synchronized(lock) {
            reconnectJob?.cancel()
            if (!autoReconnectEnabled || userDisconnect) return
        }
        val server = currentServer ?: return
        val name = clientName
        if (server.id.isEmpty()) return
        val attempt = reconnectAttempts
        val delayMs = RECONNECT_BASE_DELAY_MS * (1 shl attempt.coerceAtMost(RECONNECT_MAX_BACKOFF_SHIFT))
        reconnectAttempts = attempt + 1
        synchronized(lock) {
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!autoReconnectEnabled || userDisconnect) return@launch
                if (_state.value == ConnectionState.ERROR || _state.value == ConnectionState.DISCONNECTED) {
                    LogManager.i(TAG, "auto-reconnecting to ${server.name} (attempt=${attempt + 1}, delay=${delayMs}ms)")
                    connect(server, name)
                }
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
        val ownPub = keyManager.getKemPublicKey()
        val peerKeys = allKemKeys.filter { !it.contentEquals(ownPub) }
        if (peerKeys.isEmpty()) {
            // With no peer keys cached there is no one to encrypt to. This is a
            // design limitation of per-recipient KEM wrapping (a 1:1 chat needs
            // at least one known peer), NOT a transport regression.
            LogManager.w(TAG, "No peer keys cached; cannot send.")
            _lastError.value = "Нет известных собеседников. Подключитесь вторым устройством, чтобы обмениваться сообщениями."
            return false
        }

        // Replay/ordering protection: sign the message together with a
        // client-generated timestamp and per-message nonce, so the signature
        // is bound to a unique token (Bug: "no replay/ordering protection").
        // Backward compatible: verifiers accept old 3-field envelopes too.
        val clientTs = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val innerCanonical = Protocol.buildSignedInnerEnvelope(clientName, "", plaintext, clientTs, nonce)
        val signature = keyManager.signDsa(innerCanonical)
        val signedEnvelope = Protocol.buildSignedInnerEnvelope(
            clientName,
            android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
            plaintext,
            clientTs,
            nonce
        )

        val recipients = mutableListOf<Triple<String, ByteArray, ByteArray>>()
        for (pubKey in peerKeys) {
            val (encKey, sharedSecret) = keyManager.encapsulateKem(pubKey)
            try {
                val ciphertext = PqcCrypto.aesEncrypt(sharedSecret, signedEnvelope)
                val recipientId = keyManager.fingerprintForBytes(pubKey)
                recipients.add(Triple(recipientId, encKey, ciphertext))
            } finally {
                sharedSecret.fill(0)
            }
        }

        val (ownEncKey, ownSharedSecret) = keyManager.encapsulateKem(ownPub)
        try {
            val ownCiphertext = PqcCrypto.aesEncrypt(ownSharedSecret, signedEnvelope)
            recipients.add(Triple(keyManager.getFingerprint(), ownEncKey, ownCiphertext))
        } finally {
            ownSharedSecret.fill(0)
        }

        val blob = buildPerRecipientBlob(recipients)
        val frame = Protocol.buildData(blob)
        val c = synchronized(lock) { client }
        val ok = withContext(Dispatchers.IO) { c?.send(frame) ?: false }

        if (ok) {
            val tempId = -(System.currentTimeMillis())
            val msg = DecryptedMessage(tempId, clientName, publicKeyHash, plaintext, true, System.currentTimeMillis())
            synchronized(listeners) { listeners.forEach { it(msg) } }
        } else {
            LogManager.w(TAG, "sendChat: transport send failed, queuing in outbox")
            synchronized(outboxLock) { outbox.add(OutboxEntry(frame, plaintext)) }
            persistOutbox()
            if (!flushOutboxRunning) {
                scope.launch { flushOutbox() }
            }
        }
        return ok
    }

    /**
     * Send a private message to ONE known recipient. The blob is still relayed
     * to everyone (the server broadcasts to all sessions), but only the target
     * recipient (and the sender, for local echo) holds a decipherable entry.
     */
    suspend fun sendDirectMessage(plaintext: String, recipientFingerprint: String): Boolean {
        if (_state.value != ConnectionState.READY) {
            LogManager.w(TAG, "sendDirect ignored: not READY (state=${_state.value})")
            return false
        }
        val serverId = currentServer?.id ?: return false
        val recipientPub = keyRepo.getKemPublicKey(serverId, recipientFingerprint)
        if (recipientPub == null) {
            LogManager.w(TAG, "sendDirect: no KEM key for $recipientFingerprint")
            _lastError.value = "Получатель не найден — обменяйтесь ключами."
            return false
        }

        val clientTs = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val innerCanonical = Protocol.buildSignedInnerEnvelope(clientName, "", plaintext, clientTs, nonce, recipientFingerprint)
        val signature = keyManager.signDsa(innerCanonical)
        val signedEnvelope = Protocol.buildSignedInnerEnvelope(
            clientName,
            android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
            plaintext,
            clientTs,
            nonce,
            recipientFingerprint
        )

        val recipients = mutableListOf<Triple<String, ByteArray, ByteArray>>()

        // Target recipient.
        val (encKey, sharedSecret) = keyManager.encapsulateKem(recipientPub)
        try {
            val ciphertext = PqcCrypto.aesEncrypt(sharedSecret, signedEnvelope)
            recipients.add(Triple(recipientFingerprint, encKey, ciphertext))
        } finally {
            sharedSecret.fill(0)
        }

        // Self entry so the sender sees their own DM locally.
        val ownPub = keyManager.getKemPublicKey()
        val (ownEncKey, ownSharedSecret) = keyManager.encapsulateKem(ownPub)
        try {
            val ownCiphertext = PqcCrypto.aesEncrypt(ownSharedSecret, signedEnvelope)
            recipients.add(Triple(keyManager.getFingerprint(), ownEncKey, ownCiphertext))
        } finally {
            ownSharedSecret.fill(0)
        }

        val blob = buildPerRecipientBlob(recipients)
        val frame = Protocol.buildData(blob)
        val c = synchronized(lock) { client }
        val ok = withContext(Dispatchers.IO) { c?.send(frame) ?: false }

        if (ok) {
            val tempId = -(System.currentTimeMillis())
            val msg = DecryptedMessage(tempId, clientName, publicKeyHash, plaintext, true, System.currentTimeMillis(), "dm:$recipientFingerprint")
            synchronized(listeners) { listeners.forEach { it(msg) } }
        } else {
            LogManager.w(TAG, "sendDirect: transport send failed, queuing in outbox")
            synchronized(outboxLock) { outbox.add(OutboxEntry(frame, plaintext)) }
            persistOutbox()
            if (!flushOutboxRunning) {
                scope.launch { flushOutbox() }
            }
        }
        return ok
    }

    /** Known peers for this server (fingerprint -> short label). */
    suspend fun knownPeers(serverId: String): List<Pair<String, String>> {
        // Build the repository on demand — `keyRepo` is only initialized after
        // connect(), and opening the chat list must not crash for a server that
        // has never connected (Bug: "UninitializedPropertyAccessException").
        val repo = com.example.impulse.data.PublicKeyRepository(context)
        return repo.getKnownPeers(serverId)
    }

    /** The local user's own fingerprint (used to label the Saved/Favorites chat). */
    fun ownFingerprint(): String =
        try {
            keyManager.getFingerprint()
        } catch (_: Exception) {
            ""
        }

    /**
     * Best-effort display name for a peer fingerprint: the most recent message's
     * sender name in that DM conversation, else a short fingerprint label.
     */
    suspend fun peerDisplayName(serverId: String, fingerprint: String): String {
        if (fingerprint.isEmpty()) return ""
        val conversation = "dm:$fingerprint"
        val latest = repo.loadForConversation(serverId, conversation).lastOrNull()
        return latest?.sender?.takeIf { it.isNotBlank() } ?: "…${fingerprint.take(6)}"
    }

    private suspend fun flushOutbox() {
        if (flushOutboxRunning) return
        flushOutboxRunning = true
        try {
            val entries: List<OutboxEntry>
            synchronized(outboxLock) {
                entries = outbox.toList()
            }
            if (entries.isEmpty()) return

            for (entry in entries) {
                if (_state.value == ConnectionState.DISCONNECTED) return
                if (entry.retries >= MAX_OUTBOX_RETRIES) {
                    LogManager.w(TAG, "Outbox: dropping message after ${MAX_OUTBOX_RETRIES} retries")
                    synchronized(outboxLock) { outbox.remove(entry) }
                    persistOutbox()
                    continue
                }
                val c = synchronized(lock) { client }
                val ok = withContext(Dispatchers.IO) { c?.send(entry.frame) ?: false }
                if (ok) {
                    synchronized(outboxLock) { outbox.remove(entry) }
                    persistOutbox()
                    val tempId = -(System.currentTimeMillis())
                    val msg = DecryptedMessage(tempId, clientName, publicKeyHash, entry.plaintext, true, System.currentTimeMillis())
                    synchronized(listeners) { listeners.forEach { it(msg) } }
                    LogManager.i(TAG, "Outbox: message sent successfully on retry ${entry.retries}")
                } else {
                    entry.retries++
                    LogManager.w(TAG, "Outbox: retry ${entry.retries} failed")
                    delay(1000L * entry.retries)
                }
            }
        } finally {
            flushOutboxRunning = false
        }
    }

    /**
     * Persist queued outbox entries to disk so they survive a process kill.
     * Frames are already-encrypted blobs (safe to store); plaintext is kept for
     * the optimistic local echo. Format: JSON array of [frameB64, plaintext].
     */
    private fun persistOutbox() {
        try {
            val entries: List<OutboxEntry>
            synchronized(outboxLock) { entries = outbox.toList() }
            val arr = org.json.JSONArray()
            for (e in entries) {
                val obj = org.json.JSONObject()
                obj.put("f", android.util.Base64.encodeToString(e.frame, android.util.Base64.NO_WRAP))
                obj.put("p", e.plaintext)
                obj.put("r", e.retries)
                obj.put("t", e.queuedAt)
                arr.put(obj)
            }
            outboxPrefs.edit().putString("outbox", arr.toString()).apply()
        } catch (e: Exception) {
            LogManager.w(TAG, "persistOutbox failed (non-fatal)", e)
        }
    }

    /** Load a previously-persisted outbox into memory (call once, at connect). */
    private fun restoreOutbox() {
        try {
            val raw = outboxPrefs.getString("outbox", null) ?: return
            val arr = org.json.JSONArray(raw)
            val restored = mutableListOf<OutboxEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val frame = android.util.Base64.decode(obj.getString("f"), android.util.Base64.DEFAULT)
                restored.add(OutboxEntry(
                    frame = frame,
                    plaintext = obj.optString("p", ""),
                    queuedAt = obj.optLong("t", System.currentTimeMillis()),
                    retries = obj.optInt("r", 0),
                ))
            }
            synchronized(outboxLock) {
                if (outbox.isEmpty()) outbox.addAll(restored)
            }
            outboxPrefs.edit().remove("outbox").apply()
            if (restored.isNotEmpty()) {
                LogManager.i(TAG, "restored ${restored.size} queued messages from disk")
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "restoreOutbox failed (non-fatal)", e)
        }
    }

    private fun buildPerRecipientBlob(recipients: List<Triple<String, ByteArray, ByteArray>>): ByteArray {
        val w = Protocol.Writer()
        val senderPubHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyManager.getKemPublicKey()).copyOf(32)
        w.bytes(senderPubHash)
        w.u32(recipients.size.toLong())
        for ((id, encKey, ciphertext) in recipients) {
            val idBytes = com.example.impulse.util.hexToBytes(id)
            w.bytes(idBytes)
            w.bytes(encKey)
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

        if (frame.serverMsgId > 0) {
            synchronized(dedupLock) {
                if (!processedMsgIds.add(frame.serverMsgId)) {
                    LogManager.d(TAG, "Dedup: skipping duplicate broadcast msg ${frame.serverMsgId}")
                    return
                }
                if (processedMsgIds.size > 2000) {
                    val iter = processedMsgIds.iterator()
                    repeat(500) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
            }
        }

        try {
            val payload = frame.payload
            val pr = Protocol.Reader(payload)

            val senderPubHash = pr.bytes()
            val senderFingerprint = com.example.impulse.util.bytesToHex(senderPubHash).take(32)

            val count = pr.u32().toInt()

            val ownFingerprint = keyManager.getFingerprint()

            // Collect every recipient entry addressed to us. A malicious/crafted
            // blob can place a decoy entry with our fingerprint first that fails
            // to decrypt; we must try ALL matching entries and accept the first
            // that decrypts (Bug: "decoy-entry DoS blocks legitimate messages").
            val ourEntries = mutableListOf<Pair<ByteArray, ByteArray>>()
            repeat(count) {
                val recipientId = pr.bytes()
                val recipientFp = com.example.impulse.util.bytesToHex(recipientId).take(32)
                val encKey = pr.bytes()
                val ciphertext = pr.bytes()
                if (recipientFp == ownFingerprint) {
                    ourEntries.add(encKey to ciphertext)
                }
            }

            var accepted = false
            for ((encKey, ciphertext) in ourEntries) {
                if (accepted) break
                var sharedSecret: ByteArray? = null
                var innerBytes: ByteArray? = null
                try {
                    sharedSecret = keyManager.decapsulateKem(encKey)
                    innerBytes = try {
                        PqcCrypto.aesDecrypt(sharedSecret, ciphertext)
                    } catch (_: Exception) {
                        null
                    }
                    if (innerBytes == null) continue
                    val env = Protocol.parseInnerEnvelope(innerBytes) ?: continue
                    accepted = true
                    innerBytes.fill(0)

                    val isOwn = env.sender == clientName
                    val realId = frame.serverMsgId
                    val ts = if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis()

                    scope.launch {
                        val dsaPub = keyRepo.getDsaPublicKey(serverId, senderFingerprint)
                        if (dsaPub == null) {
                            LogManager.w(TAG, "DSA key missing for $senderFingerprint, queuing pending message")
                            val pending = PendingMessage(
                                serverMsgId = realId,
                                senderFingerprint = senderFingerprint,
                                env = env,
                                ciphertext = payload,
                                timestamp = ts,
                                isOwn = isOwn
                            )
                            synchronized(pendingMessagesLock) {
                                pendingMessages.getOrPut(senderFingerprint) { mutableListOf() }.add(pending)
                            }
                            sendKeyExchangeRequest(senderFingerprint)
                            return@launch
                        }
                        processVerifiedMessage(serverId, realId, env, payload, ts, isOwn, senderFingerprint, dsaPub)
                    }
                } finally {
                    sharedSecret?.fill(0)
                }
            }

            if (!accepted && ourEntries.isEmpty()) {
                LogManager.d(TAG, "Per-Recipient message not for us, skipping")
            } else if (!accepted) {
                LogManager.w(TAG, "All our recipient entries failed to decrypt (possible decoy entries)")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Per-Recipient parse failed", e)
        }
    }

    private suspend fun processVerifiedMessage(
        serverId: String, realId: Long, env: Protocol.InnerEnvelope,
        payload: ByteArray, ts: Long, isOwn: Boolean,
        senderFingerprint: String, dsaPub: ByteArray
    ) {
        // Rebuild the canonical signed form. New envelopes sign sender+content
        // together with clientTs+nonce+dm; old envelopes (no ts/nonce) still
        // verify against the legacy canonical form for compatibility.
        val canonical = if (env.clientTs != 0L || env.nonce.isNotEmpty()) {
            Protocol.buildSignedInnerEnvelope(env.sender, "", env.content, env.clientTs, env.nonce, env.dm)
        } else {
            Protocol.buildInnerEnvelope(env.sender, "", env.content)
        }
        val sigBytes = runCatching {
            android.util.Base64.decode(env.signature, android.util.Base64.NO_WRAP)
        }.getOrNull()
        if (sigBytes == null || sigBytes.isEmpty()) {
            LogManager.w(TAG, "REJECT msg $realId from ${env.sender}: missing ML-DSA-65 signature")
            return
        }
        if (!keyManager.verifyDsa(dsaPub, canonical, sigBytes)) {
            LogManager.w(TAG, "ML-DSA-65 signature verification FAILED for msg $realId from ${env.sender}")
            return
        }

        // Symmetric conversation key: both sides address the thread by the OTHER
        // participant's fingerprint, so the sender and receiver share ONE thread:
        //  - received DM (isOwn=false): other = senderFingerprint (peer).
        //  - own echo (isOwn=true):     other = env.dm (the recipient we wrote to),
        //    NOT senderFingerprint (our own fp) — using senderFingerprint split a
        //    sent DM into a phantom "dm:<self>" thread that vanished from the
        //    conversation list after restart (Bug: "DM is one-way / thread lost").
        val conversationId = when {
            env.dm.isNotEmpty() && isOwn -> "dm:${env.dm}"
            env.dm.isNotEmpty() -> "dm:$senderFingerprint"
            else -> "group"
        }
        repo.upsert(
            MessageEntity(
                serverId = serverId,
                serverMsgId = realId,
                sender = env.sender,
                ciphertext = payload,
                iv = byteArrayOf(),
                timestamp = ts,
                isOwn = isOwn,
                conversationId = conversationId
            )
        )

        val msg = DecryptedMessage(realId, env.sender, senderFingerprint.take(8), env.content, isOwn, ts, conversationId)
        synchronized(listeners) { listeners.forEach { it(msg) } }
    }

    private fun sendKeyExchangeRequest(senderFingerprint: String) {
        scope.launch {
            try {
                val ownKemPub = keyManager.getKemPublicKey()
                val ownDsaPub = keyManager.getDsaPublicKey()
                val c = synchronized(lock) { client }
                val ok = withContext(Dispatchers.IO) { c?.send(Protocol.buildCombinedKeyExchange(ownKemPub, ownDsaPub)) ?: false }
                LogManager.i(TAG, "Combined key exchange request sent (ok=$ok) for pending msgs from $senderFingerprint")
            } catch (e: Exception) {
                LogManager.w(TAG, "Failed to send key exchange request", e)
            }
        }
    }

    private fun processPendingMessages(senderFingerprint: String) {
        val pendingList: List<PendingMessage>
        val now = System.currentTimeMillis()
        synchronized(pendingMessagesLock) {
            val all = pendingMessages.remove(senderFingerprint) ?: return
            val (fresh, expired) = all.partition { now - it.queuedAt < PENDING_MSG_TTL_MS }
            if (expired.isNotEmpty()) {
                LogManager.w(TAG, "Discarding ${expired.size} expired pending messages from $senderFingerprint")
            }
            if (fresh.isEmpty()) return
            pendingList = fresh
        }
        val serverId = currentServer?.id ?: return
        LogManager.i(TAG, "Processing ${pendingList.size} pending messages from $senderFingerprint")

        scope.launch {
            for (pending in pendingList) {
                val dsaPub = keyRepo.getDsaPublicKey(serverId, pending.senderFingerprint)
                if (dsaPub == null) {
                    LogManager.w(TAG, "DSA key still missing for ${pending.senderFingerprint}, re-queuing")
                    synchronized(pendingMessagesLock) {
                        pendingMessages.getOrPut(pending.senderFingerprint) { mutableListOf() }.add(pending)
                    }
                    continue
                }

                processVerifiedMessage(serverId, pending.serverMsgId, pending.env, pending.ciphertext, pending.timestamp, pending.isOwn, pending.senderFingerprint, dsaPub)
            }
        }
    }

    /**
     * Decrypts a per-recipient blob from a sync response or stored message.
     * Returns the parsed envelope plus the sender fingerprint, or null on
     * failure. The FULL envelope (ts/nonce/dm included) is returned so the
     * caller can re-verify the exact signed canonical form and address the
     * message to the right conversation (Bug: "sync dropped the dm flag and
     * re-stored DMs as group; sync verification failed without ts/nonce").
     */
    private fun decryptPerRecipientBlob(
        payload: ByteArray,
        serverId: String
    ): DecryptedBlob? {
        val pr = Protocol.Reader(payload)

        val senderPubHash = pr.bytes()
        val senderFingerprint = com.example.impulse.util.bytesToHex(senderPubHash).take(32)

        val count = pr.u32().toInt()
        val ownFingerprint = keyManager.getFingerprint()

        repeat(count) {
            val recipientId = pr.bytes()
            val recipientFp = com.example.impulse.util.bytesToHex(recipientId).take(32)
            val encKey = pr.bytes()
            val ciphertext = pr.bytes()

            if (recipientFp == ownFingerprint) {
                val sharedSecret = keyManager.decapsulateKem(encKey)
                try {
                    val innerBytes = PqcCrypto.aesDecrypt(sharedSecret, ciphertext)
                    val env = Protocol.parseInnerEnvelope(innerBytes)
                    if (env == null) {
                        LogManager.w(TAG, "decryptPerRecipientBlob: parseInnerEnvelope returned null for sender=$senderFingerprint")
                        return null
                    }
                    return DecryptedBlob(senderFingerprint, env)
                } catch (e: Exception) {
                    LogManager.w(TAG, "decryptPerRecipientBlob: decrypt failed for sender=$senderFingerprint: ${e.message}")
                    return null
                } finally {
                    sharedSecret.fill(0)
                }
            }
        }
        LogManager.d(TAG, "decryptPerRecipientBlob: no recipient match (own=$ownFingerprint, recipients=$count, sender=$senderFingerprint)")
        return null
    }

    private data class DecryptedBlob(
        val senderFingerprint: String,
        val env: Protocol.InnerEnvelope
    )

    private fun onSyncResponse(r: Protocol.Reader) {
        val resp = Protocol.parseSyncResponse(r)
        val serverId = currentServer?.id ?: return
        LogManager.i(TAG, "SyncResponse: ${resp.messages.size} messages")
        var maxId = 0L

        // Collect all messages that need processing, filtering out duplicates first
        val toProcess = mutableListOf<Protocol.SyncMessage>()
        for (m in resp.messages) {
            if (m.id > maxId) maxId = m.id
            if (m.id > 0) {
                synchronized(dedupLock) {
                    if (!processedMsgIds.add(m.id)) {
                        LogManager.d(TAG, "Dedup: skipping duplicate sync msg ${m.id}")
                        continue
                    }
                    if (processedMsgIds.size > 2000) {
                        val iter = processedMsgIds.iterator()
                        repeat(500) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                    }
                }
            }
            toProcess.add(m)
        }

        // Process all messages SEQUENTIALLY in a single coroutine to preserve order
        if (toProcess.isNotEmpty()) {
            scope.launch {
                for (m in toProcess) {
                    val result = runCatching { decryptPerRecipientBlob(m.payload, serverId) }.getOrNull()
                    if (result == null) {
                        LogManager.w(TAG, "SyncResponse decrypt failed for ${m.id}")
                        continue
                    }
                    val senderFingerprint = result.senderFingerprint
                    val env = result.env
                    val sender = env.sender
                    val isOwn = sender == clientName
                    LogManager.d(TAG, "onSyncResponse: msgId=${m.id} sender='$sender' isOwn=$isOwn dm=${env.dm.take(8)}")

                    val dsaPub = keyRepo.getDsaPublicKey(serverId, senderFingerprint)
                    if (dsaPub == null) {
                        LogManager.w(TAG, "DSA key missing for sync msg from $senderFingerprint, queuing")
                        val pending = PendingMessage(
                            serverMsgId = m.id,
                            senderFingerprint = senderFingerprint,
                            env = env,
                            ciphertext = m.payload,
                            timestamp = m.timestamp,
                            isOwn = isOwn
                        )
                        synchronized(pendingMessagesLock) {
                            pendingMessages.getOrPut(senderFingerprint) { mutableListOf() }.add(pending)
                        }
                        sendKeyExchangeRequest(senderFingerprint)
                        continue
                    }

                    processVerifiedMessage(serverId, m.id, env, m.payload, m.timestamp, isOwn, senderFingerprint, dsaPub)
                }
            }
        }

        if (resp.messages.size >= 500 && maxId > 0) {
            LogManager.i(TAG, "SyncResponse got 500 msgs (maxId=$maxId), fetching more...")
            scope.launch {
                delay(200)
                val c = synchronized(lock) { client }
                val ok = withContext(Dispatchers.IO) { c?.send(Protocol.buildSync(maxId)) ?: false }
                LogManager.i(TAG, "Follow-up sync request sent (ok=$ok, lastSeenId=$maxId)")
            }
        }
    }

    private fun onHeartbeat() {
        LogManager.d(TAG, "heartbeat received from server")
    }

    private fun startHeartbeat() {
        synchronized(lock) {
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                LogManager.i(TAG, "heartbeat started (interval=${HEARTBEAT_INTERVAL_MS}ms)")
                var heartbeatCount = 0
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!isActive) break
                    val c = synchronized(lock) { client } ?: break
                    val ok = withContext(Dispatchers.IO) { c.send(Protocol.buildHeartbeat()) }
                    LogManager.d(TAG, "heartbeat sent (ok=$ok)")

                    heartbeatCount++
                    if (heartbeatCount % RESYNC_EVERY_N_HEARTBEATS == 0) {
                        val lastId = repo.lastSeenId(currentServer?.id ?: "")
                        val c2 = synchronized(lock) { client }
                        val syncOk = withContext(Dispatchers.IO) { c2?.send(Protocol.buildSync(lastId)) ?: false }
                        LogManager.i(TAG, "Periodic re-sync sent (ok=$syncOk, lastSeenId=$lastId)")
                    }
                }
                LogManager.i(TAG, "heartbeat loop exited")
            }
        }
    }

    fun decryptEntity(entity: MessageEntity): DecryptedMessage? {
        val serverId = currentServer?.id ?: return null
        return try {
            val result = decryptPerRecipientBlob(entity.ciphertext, serverId) ?: return null
            DecryptedMessage(
                serverMsgId = entity.serverMsgId,
                sender = result.env.sender,
                senderFingerprint = result.senderFingerprint.take(8),
                plaintext = result.env.content,
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
                DecryptedMessage(
                    serverMsgId = e.serverMsgId,
                    sender = result.env.sender,
                    senderFingerprint = result.senderFingerprint.take(8),
                    plaintext = result.env.content,
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
            Protocol.OP_NEW_CERT_HASH -> "NewCertHash"
            Protocol.OP_AUTH_CHALLENGE -> "AuthChallenge"
            Protocol.OP_KEY_EXCHANGE_KEM_DSA -> "KeyExchangeKemDsa"
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
                Protocol.OP_KEY_EXCHANGE_KEM_DSA -> onCombinedKeyExchange(r)
                Protocol.OP_NEW_CERT_HASH -> onNewCertHash(r)
                Protocol.OP_AUTH_CHALLENGE -> onAuthChallenge(r)
                else -> LogManager.w(TAG, "UNKNOWN opcode 0x%02x (${raw.size} bytes)".format(opcode))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "PARSE FAILED opcode=0x%02x: ${e.message}".format(opcode), e)
        }
    }

    private fun onAuthChallenge(r: Protocol.Reader) {
        val frame = Protocol.parseAuthChallenge(r)
        pendingSaltB64 = frame.saltB64
        authChallengeNonce.complete(frame.nonce)
        LogManager.i(TAG, "AUTH CHALLENGE received (nonce=${frame.nonce.size} bytes, salt=${frame.saltB64.length} chars)")
    }

    private fun onNewCertHash(r: Protocol.Reader) {
        val frame = Protocol.parseNewCertHash(r)
        val serverId = currentServer?.id ?: return
        certManager.rotateHash(serverId, frame.hash)
        LogManager.i(TAG, "NEW CERT HASH received and stored as pending: ${frame.hash.take(16)}...")
    }

    private fun onAuthResult(r: Protocol.Reader) {
        val res = Protocol.parseAuthResult(r)
        if (!res.success) {
            LogManager.e(TAG, "AUTH REJECTED by server: ${res.errorMessage ?: "no reason"}")
            cancelAuthTimeout()
            if (!userDisconnect) {
                _state.value = ConnectionState.ERROR
                _lastError.value = "Аутентификация отклонена сервером: ${res.errorMessage ?: "нет причины"}"
            }
            return
        }
        cancelAuthTimeout()
        LogManager.i(TAG, "AUTH SUCCESS — publishing keys + requesting sync (clientName='$clientName')")
        _state.value = ConnectionState.AUTHENTICATING
        scope.launch {
            if (userDisconnect) return@launch
            // Cache our own key pair. Guarded: a storage failure here must NOT
            // block the KeyExchange/Sync that follows (Bug: "client stuck on
            // SYNC — KeyExchange never sent because cacheKey threw").
            try {
                val ownFp = keyManager.getFingerprint()
                keyRepo.cacheKey(
                    currentServer?.id ?: "",
                    ownFp,
                    keyManager.getKemPublicKey(),
                    keyManager.getDsaPublicKey()
                )
            } catch (t: Throwable) {
                LogManager.e(TAG, "cacheKey failed (non-fatal)", t)
                com.example.impulse.util.CrashLog.writeCrash(
                    com.example.impulse.util.CrashLog.buildCrashReport(
                        thread = Thread.currentThread(),
                        throwable = t,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        sdkInt = android.os.Build.VERSION.SDK_INT,
                        release = android.os.Build.VERSION.RELEASE,
                        manufacturer = android.os.Build.MANUFACTURER,
                        model = android.os.Build.MODEL,
                        timeMillis = System.currentTimeMillis(),
                        extra = "ChatController.onAuthResult cacheKey",
                    )
                )
            }

            val kemPub = keyManager.getKemPublicKey()
            val dsaPub = keyManager.getDsaPublicKey()
            // Send KeyExchange + Sync. Never throw out of here: a transient
            // failure must NOT strand the client on SYNC or spin reconnect.
            // Each send is retried a few times before we give up and drop the
            // connection to ERROR (which triggers reconnect).
            val c = synchronized(lock) { client }
            var keySent = false
            for (attempt in 1..3) {
                keySent = withContext(Dispatchers.IO) { c?.send(Protocol.buildCombinedKeyExchange(kemPub, dsaPub)) ?: false }
                if (keySent || userDisconnect) break
                LogManager.w(TAG, "KeyExchange send attempt $attempt failed, retrying")
                delay(300L * attempt)
            }
            LogManager.i(TAG, "KeyExchange combined KEM+DSA sent (ok=$keySent)")
            if (userDisconnect) return@launch
            if (!keySent) {
                LogManager.e(TAG, "KeyExchange could not be sent after 3 attempts — dropping to ERROR for reconnect")
                _state.value = ConnectionState.ERROR
                scheduleReconnect()
                return@launch
            }

            val lastSeen = runCatching { lastSeenId() }.getOrDefault(0L)
            var syncSent = false
            for (attempt in 1..3) {
                syncSent = withContext(Dispatchers.IO) { c?.send(Protocol.buildSync(lastSeen)) ?: false }
                if (syncSent || userDisconnect) break
                LogManager.w(TAG, "Sync send attempt $attempt failed, retrying")
                delay(300L * attempt)
            }
            LogManager.i(TAG, "Sync request sent (ok=$syncSent, lastSeenId=$lastSeen)")
            if (userDisconnect) return@launch
            if (!syncSent) {
                LogManager.e(TAG, "Sync could not be sent after 3 attempts — dropping to ERROR for reconnect")
                _state.value = ConnectionState.ERROR
                scheduleReconnect()
                return@launch
            }

            // Guard: if a disconnect raced in while we were authenticating, do
            // NOT flip back to READY with a null client (Bug: "state becomes
            // READY after user disconnect"). Leave the controller DISCONNECTED.
            if (userDisconnect || synchronized(lock) { client } == null) {
                LogManager.w(TAG, "AUTH SUCCESS but disconnect raced — staying DISCONNECTED")
                return@launch
            }

            _state.value = ConnectionState.AUTHENTICATED
            _state.value = ConnectionState.READY
            LogManager.i(TAG, "READY")

            // Restore any messages queued before a previous disconnect / kill.
            restoreOutbox()

            synchronized(outboxLock) {
                if (outbox.isNotEmpty() && !flushOutboxRunning) {
                    LogManager.i(TAG, "READY: outbox has ${outbox.size} queued messages, flushing")
                    scope.launch { flushOutbox() }
                }
            }

            val messages = repo.load(currentServer?.id ?: "")
            if (messages.isEmpty()) {
                _lastError.value = "Вы присоединились к чату. Сообщения до вашего подключения недоступны."
            }

            delay(3000)
            val c2 = synchronized(lock) { client }
            val reSyncOk = withContext(Dispatchers.IO) { c2?.send(Protocol.buildSync(lastSeenId())) ?: false }
            LogManager.i(TAG, "Delayed re-sync sent (ok=$reSyncOk, lastSeenId=${lastSeenId()})")
        }
        startHeartbeat()
    }

    private fun onCombinedKeyExchange(r: Protocol.Reader) {
        val frame = Protocol.parseCombinedKeyExchange(r)
        val serverId = currentServer?.id ?: return

        try {
            PqcCrypto.validateKemPublicKey(frame.kemPublicKey)
            PqcCrypto.validateDsaPublicKey(frame.dsaPublicKey)
            val kemFp = keyManager.fingerprintForBytes(frame.kemPublicKey)
            val dsaFp = keyManager.fingerprintForBytes(frame.dsaPublicKey)
            scope.launch {
                keyRepo.cacheKey(serverId, kemFp, frame.kemPublicKey, frame.dsaPublicKey)
                LogManager.i(TAG, "CombinedKeyExchange: kem_fp=$kemFp dsa_fp=$dsaFp cached atomically")
                processPendingMessages(kemFp)
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "CombinedKeyExchange validation failed: ${e.message}", e)
        }
    }

    private suspend fun lastSeenId(): Long = repo.lastSeenId(currentServer?.id ?: "")

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "ChatController"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val AUTH_TIMEOUT_MS = 15_000L
        private const val RECONNECT_BASE_DELAY_MS = 5_000L
        private const val RECONNECT_MAX_BACKOFF_SHIFT = 4 // caps at 5s * 2^4 = 80s
        private const val PENDING_MSG_TTL_MS = 120_000L
        private const val RESYNC_EVERY_N_HEARTBEATS = 2
        private const val MAX_OUTBOX_RETRIES = 3
    }
}
