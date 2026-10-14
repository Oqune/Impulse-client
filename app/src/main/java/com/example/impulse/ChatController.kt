package com.example.impulse

import android.content.Context
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.PublicKeyRepository
import com.example.impulse.data.ReplayNonce
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.db.MessageEntity
import com.example.impulse.data.db.ReplayPersistenceResult
import com.example.impulse.security.PqcCrypto
import com.example.impulse.security.InnerEnvelopeSignatureVerifier
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
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class ChatController(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught coroutine exception", e)
        }
    )
    private val certManager = TrustedCertManager(context)
    private val repo = MessageRepository(context)
    private val outboxPrefs = context.getSharedPreferences("impulse_outbox", Context.MODE_PRIVATE)
    private val peerPrefs = context.getSharedPreferences("impulse_peer_names", Context.MODE_PRIVATE)

    private val _peerNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerNames: StateFlow<Map<String, String>> = _peerNames.asStateFlow()

    init {
        val initial = mutableMapOf<String, String>()
        for ((k, v) in peerPrefs.all) {
            if (v is String && v.isNotBlank()) {
                val fp = k.substringAfterLast("_", k)
                initial[fp] = v
            }
        }
        _peerNames.value = initial
    }

    private val lock = Any()

    @Volatile private var client: WebTransportClient? = null
    /** Incremented whenever a transport incarnation is replaced or invalidated. Guarded by [lock]. */
    private var connectionGeneration: Long = 0L
    @Volatile var currentServer: ServerConfig? = null
        private set
    @Volatile var clientName: String = ""
        private set

    fun updateClientName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty() && trimmed != clientName) {
            clientName = trimmed
            LogManager.i(TAG, "Updated clientName to '$trimmed'")
        }
    }

    private fun recordPeerName(serverId: String, fingerprint: String, name: String) {
        if (fingerprint.isBlank() || name.isBlank()) return
        peerPrefs.edit()
            .putString("${serverId}_$fingerprint", name)
            .putString(fingerprint, name)
            .apply()
        _peerNames.value = _peerNames.value + (fingerprint to name)
    }

    private lateinit var keyManager: SecureKeyManager
    private lateinit var keyRepo: PublicKeyRepository
    private val keyInitLock = Any()

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
    // Argon2id params from the server's AuthChallenge (SPEC N1, §4.3). The
    // client follows these instead of a frozen constant. OWASP floor defaults.
    @Volatile private var pendingArgonMemKB: Int = 47104
    @Volatile private var pendingArgonIter: Int = 3
    @Volatile private var pendingArgonPar: Int = 1

    private data class PendingMessage(
        val serverMsgId: Long,
        val senderFingerprint: String,
        val env: Protocol.InnerEnvelope,
        val ciphertext: ByteArray,
        val timestamp: Long,
        val isOwn: Boolean,
        val queuedAt: Long = System.currentTimeMillis()
    )

    private val processedMsgIds = LinkedHashSet<Long>(512)
    private val dedupLock = Any()
    private val pendingMessages = PendingMessageBuffer<PendingMessage>(
        maxItems = PendingMessageBuffer.MAX_ITEMS,
        maxItemsPerKey = PendingMessageBuffer.MAX_ITEMS_PER_KEY,
        maxBytes = PendingMessageBuffer.MAX_BYTES,
        ttlMillis = PendingMessageBuffer.TTL_MILLIS,
        keyOf = { it.senderFingerprint },
        queuedAtOf = { it.queuedAt },
        bytesOf = ::pendingMessageBytes,
        onExpired = { expired ->
            if (expired.serverMsgId > 0) synchronized(dedupLock) { processedMsgIds.remove(expired.serverMsgId) }
        }
    )

    private data class OutboxEntry(
        override val serverId: String,
        override val frame: ByteArray,
        override val queuedAt: Long = System.currentTimeMillis()
    ) : PersistedOutboxFrame
    private data class OutboxFlushTarget(
        val identity: ConnectionIdentity,
        val transport: WebTransportClient
    )
    private data class ActiveConnection(
        val identity: ConnectionIdentity,
        val transport: WebTransportClient
    )
    private val outbox = mutableListOf<OutboxEntry>()
    private val outboxLock = Any()
    private val flushOutboxRunning = AtomicBoolean(false)

    private fun captureReadyConnection(): ActiveConnection? = synchronized(lock) {
        if (_state.value != ConnectionState.READY) return@synchronized null
        val serverId = currentServer?.id ?: return@synchronized null
        val activeClient = client ?: return@synchronized null
        ActiveConnection(ConnectionIdentity(connectionGeneration, serverId), activeClient)
    }

    private fun isCurrentConnection(connection: ActiveConnection): Boolean =
        isCurrentConnection(connection.identity.generation, connection.identity.serverId, connection.transport)

    private fun queueOutboxFrame(serverId: String, frame: ByteArray) {
        synchronized(outboxLock) { outbox.add(OutboxEntry(serverId, frame)) }
        persistOutbox(serverId)
        if (!flushOutboxRunning.get()) scope.launch { flushOutbox() }
    }

    private fun emitLocalEchoIfCurrent(connection: ActiveConnection, message: DecryptedMessage): Boolean =
        synchronized(lock) {
            if (!isCurrentConnectionLocked(
                    connection.identity.generation,
                    connection.identity.serverId,
                    connection.transport
                )
            ) {
                false
            } else {
                synchronized(listeners) { listeners.forEach { it(message) } }
                true
            }
        }

    // C4 replay cache: tracks (senderFingerprint:nonce) to reject message replays
    private val seenNonces = LinkedHashMap<String, Long>(512, 0.75f, true)
    private val seenNoncesLock = Any()

    // Monotonic counter for optimistic local messages to avoid timestamp collisions
    private val tempIdCounter = AtomicLong(-1L)

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
        synchronized(keyInitLock) {
            if (::keyManager.isInitialized && ::keyRepo.isInitialized) return
            keyManager = SecureKeyManager
            PqcCrypto.ensureProvider()
            keyManager.ensureKeyPair(context)
            keyRepo = PublicKeyRepository(context)
        }
    }

    fun connect(server: ServerConfig, name: String) {
        val generation = synchronized(lock) {
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
            connectionGeneration++
            userDisconnect = false
            reconnectJob?.cancel()
            reconnectJob = null
            currentServer = server
            clientName = name
            _state.value = ConnectionState.CONNECTING
            connectionGeneration
        }
        LogManager.i(TAG, "connect: server=${server.id} name='$name'")
        // Key generation (ML-KEM + ML-DSA) can throw on some devices (e.g.
        // BouncyCastle PQC provider unavailable). Catch it so the app shows an
        // error instead of crashing, and record the stack for diagnostics.
        try {
            ensureKeyPair()
        } catch (t: Throwable) {
            LogManager.e(TAG, "ensureKeyPair failed on connect", t)
            val current = synchronized(lock) {
                if (connectionGeneration != generation || currentServer?.id != server.id) false else {
                    _state.value = ConnectionState.ERROR
                    _lastError.value = "Ошибка генерации ключей: ${t.message}"
                    true
                }
            }
            if (!current) return
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
        synchronized(lock) {
            if (connectionGeneration != generation || currentServer?.id != server.id) return
            _lastError.value = null
        }

        val hashes = certManager.getHashes(server.id)

        if (!certManager.isTrusted(server.id)) {
            synchronized(lock) {
                if (connectionGeneration != generation || currentServer?.id != server.id) return
                _state.value = ConnectionState.ERROR
                _lastError.value = "Нет доверенного сертификата сервера. Отсканируйте QR-код с экрана сервера."
            }
            LogManager.w(TAG, "No trusted cert for ${server.id}; QR scan required.")
            return
        }

        LogManager.i(TAG, "connect: server=${server.id} ip=${server.ipAddress} pinnedHashes=${hashes.size}")

        if (!isCurrentGeneration(generation, server.id)) return
        val clientHolder = arrayOfNulls<WebTransportClient>(1)
        val wtClient = WebTransportClient(
            context = context,
            serverCertHashes = hashes,
            onFrame = { raw ->
                val source = clientHolder[0]
                if (source != null && isCurrentConnection(generation, server.id, source)) {
                    handleIncoming(raw, generation, server, source)
                }
            },
            onState = { s ->
                val source = clientHolder[0]
                if (source != null) onTransportState(s, generation, server.id, source)
            },
            onCertHashPush = { h ->
                val source = clientHolder[0]
                if (source != null && isCurrentConnection(generation, server.id, source)) {
                    certManager.rotateHash(server.id, h)
                }
            },
            onSessionError = { code ->
                val source = clientHolder[0]
                if (source != null && isCurrentConnection(generation, server.id, source)) {
                    setLastErrorForConnection(
                        generation,
                        server.id,
                        source,
                        "Ошибка сессии WebTransport (code=$code). " +
                            "Возможно, сервер не поддерживает HTTP/3 (QUIC) или недоступен на ${server.ipAddress}:${server.port}."
                    )
                }
            },
            onReady = {
                val source = clientHolder[0]
                if (source != null && isCurrentConnection(generation, server.id, source)) {
                    LogManager.i(TAG, "onReady FIRED — launching sendAuth")
                    val challenge = authChallengeNonce
                    scope.launch {
                        if (!isCurrentConnection(generation, server.id, source)) return@launch
                        LogManager.i(TAG, "sendAuth coroutine started, transport=current")
                        sendAuth(source, server, generation, challenge)
                    }
                }
            },
        )
        clientHolder[0] = wtClient
        synchronized(lock) {
            if (connectionGeneration != generation || currentServer?.id != server.id) {
                wtClient.destroy()
                return
            }
            client?.destroy()
            authChallengeNonce.completeExceptionally(IllegalStateException("connection replaced"))
            authChallengeNonce = CompletableDeferred()
            pendingSaltB64 = ""
            client = wtClient
        }
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

    fun updateServer(server: ServerConfig) {
        synchronized(lock) {
            currentServer = server
        }
    }

    fun disconnect() {
        val clientToDestroy: WebTransportClient?
        synchronized(lock) {
            connectionGeneration++
            userDisconnect = true
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            clientToDestroy = client
            client = null
            pendingSaltB64 = ""
            authChallengeNonce.completeExceptionally(IllegalStateException("disconnected"))
            authChallengeNonce = CompletableDeferred()
            _state.value = ConnectionState.DISCONNECTED
            _lastError.value = null
        }
        if (clientToDestroy != null) {
            // Best-effort notify the server and destroy asynchronously without blocking caller thread
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                try {
                    withTimeout(500) {
                        clientToDestroy.send(Protocol.buildDisconnect())
                    }
                } catch (_: Exception) { }
                clientToDestroy.destroy()
            }
        }
        // Keep the outbox across disconnect/reconnect so queued messages are
        // NOT lost (Bug: "outbox cleared on disconnect drops messages"). The
        // flush loop re-runs on the next READY. Only `userDisconnect` from the
        // UI should clear it explicitly (see disconnectUser).
        pendingMessages.clear()
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

    private fun isCurrentGeneration(generation: Long, serverId: String): Boolean = synchronized(lock) {
        connectionGeneration == generation && currentServer?.id == serverId
    }

    private fun isCurrentConnection(generation: Long, serverId: String, transport: WebTransportClient): Boolean =
        synchronized(lock) { isCurrentConnectionLocked(generation, serverId, transport) }

    private fun isCurrentConnectionLocked(generation: Long, serverId: String, transport: WebTransportClient): Boolean =
        matchesConnectionIdentity(
            ConnectionIdentity(generation, serverId),
            ConnectionIdentity(connectionGeneration, currentServer?.id ?: ""),
            transport,
            client
        )

    private fun setStateForConnection(
        generation: Long,
        serverId: String,
        transport: WebTransportClient,
        state: ConnectionState
    ): Boolean = synchronized(lock) {
        if (!isCurrentConnectionLocked(generation, serverId, transport)) return@synchronized false
        _state.value = state
        true
    }

    private fun setLastErrorForConnection(
        generation: Long,
        serverId: String,
        transport: WebTransportClient,
        message: String
    ): Boolean = synchronized(lock) {
        if (!isCurrentConnectionLocked(generation, serverId, transport)) return@synchronized false
        _lastError.value = message
        true
    }

    private fun setLastErrorForConnection(connection: ActiveConnection, message: String): Boolean =
        setLastErrorForConnection(
            connection.identity.generation,
            connection.identity.serverId,
            connection.transport,
            message
        )

    private fun failCurrentConnection(generation: Long, serverId: String, transport: WebTransportClient) {
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, serverId, transport)) return
            _state.value = ConnectionState.ERROR
            scheduleReconnect()
        }
    }

    private fun onTransportState(
        s: ConnectionState,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, serverId, transport)) return
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
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    _state.value = s
                    cancelAuthTimeout()
                    scheduleReconnect()
                }
                else -> { }
            }
        }
    }

    private suspend fun sendAuth(
        transport: WebTransportClient,
        server: ServerConfig,
        generation: Long,
        challenge: CompletableDeferred<ByteArray?>
    ) {
        if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return
        LogManager.i(TAG, "sendAuth: STARTED (transport=current, server=${server.id})")
        val password = server.password
        LogManager.i(TAG, "sendAuth: password=${password.length} chars, waiting for challenge nonce...")
        val nonce = try {
            withTimeout(5000) { challenge.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "sendAuth: challenge nonce not received after 5s: ${e.message}")
            if (isCurrentConnection(generation, server.id, transport) && !userDisconnect) {
                failCurrentConnection(generation, server.id, transport)
            }
            return
        }
        if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return
        LogManager.i(TAG, "sendAuth: building auth frame (password=${password.length} chars, nonce=${nonce?.size} bytes)")
        val frame: ByteArray
        try {
            val saltB64 = pendingSaltB64; pendingSaltB64 = ""
            LogManager.i(TAG, "sendAuth: salt=${saltB64.length} chars, argon2(m=$pendingArgonMemKB,t=$pendingArgonIter,p=$pendingArgonPar)")
            frame = Protocol.buildAuth(password, nonce, saltB64, pendingArgonMemKB, pendingArgonIter, pendingArgonPar)
            LogManager.i(TAG, "sendAuth: auth frame built OK (${frame.size} bytes)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "sendAuth: buildAuth FAILED: ${e.message}", e)
            if (isCurrentConnection(generation, server.id, transport) && !userDisconnect) {
                failCurrentConnection(generation, server.id, transport)
            }
            return
        }
        if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return
        LogManager.i(TAG, "sendAuth: sending frame via transport...")
        val ok = withContext(Dispatchers.IO) { transport.send(frame) }
        LogManager.i(TAG, "sendAuth: transport.send() returned $ok (transport=current)")
        if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return
        if (!ok) {
            LogManager.e(TAG, "sendAuth FAILED — transport.send() returned false (state=${_state.value})")
            if (!userDisconnect) failCurrentConnection(generation, server.id, transport)
            return
        }
        if (!setStateForConnection(generation, server.id, transport, ConnectionState.AUTHENTICATING)) return
        LogManager.i(TAG, "AUTH SENT → waiting for server response (timeout=${AUTH_TIMEOUT_MS}ms)")
            startAuthTimeout(generation, server.id, transport)
    }

    private fun startAuthTimeout(generation: Long, serverId: String, transport: WebTransportClient) {
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, serverId, transport)) return
            cancelAuthTimeout()
            authTimeoutJob = scope.launch {
                delay(AUTH_TIMEOUT_MS)
                synchronized(lock) {
                    if (!isCurrentConnectionLocked(generation, serverId, transport)) return@launch
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
        val connection = captureReadyConnection()
        if (connection == null) {
            LogManager.w(TAG, "sendChat ignored: not READY (state=${_state.value})")
            return false
        }
        val serverId = connection.identity.serverId

        val allKemKeys = keyRepo.getAllKemPublicKeys(serverId)
        if (!isCurrentConnection(connection)) return false
        val ownPub = keyManager.getKemPublicKey()
        val peerKeys = allKemKeys.filter { !it.contentEquals(ownPub) }
        if (peerKeys.isEmpty()) {
            // With no peer keys cached there is no one to encrypt to. This is a
            // design limitation of per-recipient KEM wrapping (a 1:1 chat needs
            // at least one known peer), NOT a transport regression.
            LogManager.w(TAG, "No peer keys cached; cannot send.")
            setLastErrorForConnection(
                connection,
                "Нет известных собеседников. Подключитесь вторым устройством, чтобы обмениваться сообщениями."
            )
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
            Protocol.base64Encode(signature),
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
        if (!isCurrentConnection(connection)) {
            LogManager.i(TAG, "sendChat: connection changed during encryption; retaining frame for originating server")
            queueOutboxFrame(serverId, frame)
            return false
        }
        val ok = withContext(Dispatchers.IO) { connection.transport.send(frame) }
        if (!isCurrentConnection(connection)) {
            if (!ok) queueOutboxFrame(serverId, frame)
            return false
        }

        if (ok) {
            val tempId = tempIdCounter.getAndDecrement()
            val msg = DecryptedMessage(tempId, clientName, publicKeyHash, plaintext, true, System.currentTimeMillis())
            if (!emitLocalEchoIfCurrent(connection, msg)) return false
        } else {
            LogManager.w(TAG, "sendChat: transport send failed, queuing in outbox")
            queueOutboxFrame(serverId, frame)
        }
        return ok
    }

    /**
     * Send a private message to ONE known recipient. The blob is still relayed
     * to everyone (the server broadcasts to all sessions), but only the target
     * recipient (and the sender, for local echo) holds a decipherable entry.
     */
    suspend fun sendDirectMessage(plaintext: String, recipientFingerprint: String): Boolean {
        val connection = captureReadyConnection()
        if (connection == null) {
            LogManager.w(TAG, "sendDirect ignored: not READY (state=${_state.value})")
            return false
        }
        val serverId = connection.identity.serverId
        val recipientPub = keyRepo.getKemPublicKey(serverId, recipientFingerprint)
        if (!isCurrentConnection(connection)) return false
        if (recipientPub == null) {
            LogManager.w(TAG, "sendDirect: no KEM key for $recipientFingerprint")
            setLastErrorForConnection(connection, "Получатель не найден — обменяйтесь ключами.")
            return false
        }

        val clientTs = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val innerCanonical = Protocol.buildSignedInnerEnvelope(clientName, "", plaintext, clientTs, nonce, recipientFingerprint)
        val signature = keyManager.signDsa(innerCanonical)
        val signedEnvelope = Protocol.buildSignedInnerEnvelope(
            clientName,
            Protocol.base64Encode(signature),
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
        if (!isCurrentConnection(connection)) {
            LogManager.i(TAG, "sendDirect: connection changed during encryption; retaining frame for originating server")
            queueOutboxFrame(serverId, frame)
            return false
        }
        val ok = withContext(Dispatchers.IO) { connection.transport.send(frame) }
        if (!isCurrentConnection(connection)) {
            if (!ok) queueOutboxFrame(serverId, frame)
            return false
        }

        if (ok) {
            val tempId = tempIdCounter.getAndDecrement()
            val msg = DecryptedMessage(tempId, clientName, publicKeyHash, plaintext, true, System.currentTimeMillis(), "dm:$recipientFingerprint")
            if (!emitLocalEchoIfCurrent(connection, msg)) return false
        } else {
            LogManager.w(TAG, "sendDirect: transport send failed, queuing in outbox")
            queueOutboxFrame(serverId, frame)
        }
        return ok
    }

    /** Known peers for this server (fingerprint -> display name or short label). */
    suspend fun knownPeers(serverId: String): List<Pair<String, String>> {
        // Build the repository on demand — `keyRepo` is only initialized after
        // connect(), and opening the chat list must not crash for a server that
        // has never connected (Bug: "UninitializedPropertyAccessException").
        val repo = com.example.impulse.data.PublicKeyRepository(context)
        val peers = repo.getKnownPeers(serverId)
        return peers.map { (fp, _) ->
            val name = _peerNames.value[fp]
                ?: peerPrefs.getString("${serverId}_$fp", null)
                ?: peerPrefs.getString(fp, null)
                ?: "…${fp.take(6)}"
            fp to name
        }
    }

    /** The local user's own fingerprint (used to label the Saved/Favorites chat). */
    fun ownFingerprint(): String =
        try {
            keyManager.getFingerprint()
        } catch (_: Exception) {
            ""
        }

    /**
     * Best-effort display name for a peer fingerprint: cached verified name,
     * else the most recent incoming message's sender name in that DM, else a
     * short fingerprint label. NEVER returns own user's nickname.
     */
    suspend fun peerDisplayName(serverId: String, fingerprint: String): String {
        if (fingerprint.isEmpty()) return ""
        _peerNames.value[fingerprint]?.let { return it }
        val persisted = peerPrefs.getString("${serverId}_$fingerprint", null)
            ?: peerPrefs.getString(fingerprint, null)
        if (!persisted.isNullOrBlank()) {
            _peerNames.value = _peerNames.value + (fingerprint to persisted)
            return persisted
        }
        val conversation = "dm:$fingerprint"
        val latestPeerMsg = repo.loadForConversation(serverId, conversation)
            .lastOrNull { !it.isOwn && it.sender.isNotBlank() }
        if (latestPeerMsg != null) {
            recordPeerName(serverId, fingerprint, latestPeerMsg.sender)
            return latestPeerMsg.sender
        }
        return "…${fingerprint.take(6)}"
    }

    private suspend fun flushOutbox() {
        if (!flushOutboxRunning.compareAndSet(false, true)) return
        try {
            val target = synchronized(lock) {
                val serverId = currentServer?.id ?: return@synchronized null
                val activeClient = client ?: return@synchronized null
                OutboxFlushTarget(ConnectionIdentity(connectionGeneration, serverId), activeClient)
            } ?: return
            fun targetIsCurrent(): Boolean = synchronized(lock) {
                matchesConnectionIdentity(
                    target.identity,
                    ConnectionIdentity(connectionGeneration, currentServer?.id ?: ""),
                    target.transport,
                    client
                ) && _state.value == ConnectionState.READY
            }
            val entries: List<OutboxEntry>
            synchronized(outboxLock) {
                entries = outbox.filter { it.serverId == target.identity.serverId }
            }
            if (entries.isEmpty()) return

            flushOutboxBatch(
                entries = entries,
                canContinue = { targetIsCurrent() },
                send = { entry ->
                    if (!targetIsCurrent() || entry.serverId != target.identity.serverId) {
                        false
                    } else {
                        withContext(Dispatchers.IO) { target.transport.send(entry.frame) }
                    }
                },
                onDelivered = { entry ->
                    synchronized(outboxLock) { outbox.remove(entry) }
                    persistOutbox(entry.serverId)
                    LogManager.i(TAG, "Outbox: queued message sent successfully")
                },
                onFailure = { entry ->
                    LogManager.w(TAG, "Outbox: send failed; retaining frame for next READY connection")
                    persistOutbox(entry.serverId)
                    if (targetIsCurrent() && !userDisconnect) {
                        onTransportState(
                            ConnectionState.ERROR,
                            target.identity.generation,
                            target.identity.serverId,
                            target.transport
                        )
                    }
                },
            )
        } finally {
            flushOutboxRunning.set(false)
            val readyServerId = synchronized(lock) {
                currentServer?.id.takeIf { _state.value == ConnectionState.READY && client != null }
            }
            if (readyServerId != null) {
                val hasQueuedFrames = synchronized(outboxLock) { outbox.any { it.serverId == readyServerId } }
                if (hasQueuedFrames) scope.launch { flushOutbox() }
            }
        }
    }

    /**
     * Persist queued outbox entries to disk so they survive a process kill.
     * Frames are already-encrypted blobs (safe to store). Per SPEC C2 the
     * cleartext body is NOT persisted — only the encrypted frame is kept, so a
     * process kill / ADB / backup pull cannot reveal message content.
     * Format: JSON array of [serverId, frameB64, queuedAt].
     */
    private fun persistOutbox(serverId: String) {
        try {
            val entries: List<OutboxEntry>
            synchronized(outboxLock) { entries = outbox.filter { it.serverId == serverId } }
            val arr = org.json.JSONArray()
            for (e in entries) {
                val obj = org.json.JSONObject()
                obj.put("s", e.serverId)
                obj.put("f", android.util.Base64.encodeToString(e.frame, android.util.Base64.NO_WRAP))
                obj.put("t", e.queuedAt)
                arr.put(obj)
            }
            outboxPrefs.edit().putString("outbox_$serverId", arr.toString()).apply()
        } catch (e: Exception) {
            LogManager.w(TAG, "persistOutbox failed (non-fatal)", e)
        }
    }

    /** Load a previously-persisted outbox into memory (call once, at connect). */
    private fun restoreOutbox(serverId: String) {
        try {
            val raw = outboxPrefs.getString("outbox_$serverId", null) ?: return
            val arr = org.json.JSONArray(raw)
            val restored = mutableListOf<OutboxEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val frame = android.util.Base64.decode(obj.getString("f"), android.util.Base64.DEFAULT)
                val entryServerId = obj.optString("s", serverId)
                if (entryServerId != serverId || frame.isEmpty()) continue
                restored.add(OutboxEntry(
                    serverId = serverId,
                    frame = frame,
                    queuedAt = obj.optLong("t", System.currentTimeMillis()),
                ))
            }
            synchronized(outboxLock) {
                val merged = mergeRestoredOutbox(outbox, restored) { restoredServerId, frame, queuedAt ->
                    OutboxEntry(restoredServerId, frame, queuedAt)
                }
                outbox.clear()
                outbox.addAll(merged)
            }
            if (restored.isNotEmpty()) {
                LogManager.i(TAG, "restored ${restored.size} queued messages from disk for server $serverId")
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

    private suspend fun onData(
        r: Protocol.Reader,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        val frame = Protocol.parseData(r)
        if (!isCurrentConnection(generation, serverId, transport)) return

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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (innerBytes == null) continue
                    val env = Protocol.parseInnerEnvelope(innerBytes) ?: continue
                    accepted = true
                    innerBytes.fill(0)

                    val isOwn = senderFingerprint == ownFingerprint
                    val realId = frame.serverMsgId
                    val ts = if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis()

                    val dsaPub = keyRepo.getDsaPublicKey(serverId, senderFingerprint)
                    if (dsaPub == null) {
                        LogManager.w(TAG, "DSA key missing, message requires key exchange")
                        val pending = PendingMessage(
                            serverMsgId = realId,
                            senderFingerprint = senderFingerprint,
                            env = env,
                            ciphertext = payload,
                            timestamp = ts,
                            isOwn = isOwn
                        )
                        if (queuePendingMessage(pending)) {
                            sendKeyExchangeRequest(senderFingerprint, generation, serverId, transport)
                        }
                    } else {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "Per-Recipient parse failed", e)
        }
    }

    private suspend fun processVerifiedMessage(
        serverId: String, realId: Long, env: Protocol.InnerEnvelope,
        payload: ByteArray, ts: Long, isOwn: Boolean,
        senderFingerprint: String, dsaPub: ByteArray
    ) {
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
        val row = MessageEntity(
            serverId = serverId,
            serverMsgId = realId,
            sender = env.sender,
            ciphertext = payload,
            iv = byteArrayOf(),
            timestamp = ts,
            isOwn = isOwn,
            conversationId = conversationId
        )
        val acceptedAt = System.currentTimeMillis()
        val persisted = InnerEnvelopeSignatureVerifier.verifyFreshAndPersist(
            envelope = env,
            publicKey = dsaPub,
            now = acceptedAt,
            freshnessWindowMs = ReplayNonce.FRESHNESS_WINDOW_MS,
            verifySignature = { publicKey, message, signature ->
                keyManager.verifyDsa(publicKey, message, signature)
            }
        ) {
            if (env.nonce.isEmpty()) {
                repo.upsert(row)
                ReplayPersistenceResult.ACCEPTED
            } else {
                val nonceKey = "$senderFingerprint:${ReplayNonce.hash(env.nonce)}"
                val cachedDuplicate = synchronized(seenNoncesLock) {
                    val expiresAt = seenNonces[nonceKey]
                    if (expiresAt != null && expiresAt <= acceptedAt) {
                        seenNonces.remove(nonceKey)
                        false
                    } else expiresAt != null
                }
                if (cachedDuplicate) {
                    ReplayPersistenceResult.DUPLICATE
                } else {
                    val marker = ReplayNonce.marker(
                        senderFingerprint, env.nonce, env.clientTs, acceptedAt
                    )!!
                    repo.persistReplayProtected(row, marker, acceptedAt)
                }
            }
        }
        if (persisted == null) {
            LogManager.w(TAG, "REJECT msg $realId from ${env.sender}: invalid signature or signed timestamp")
            return
        }
        when (persisted) {
            ReplayPersistenceResult.DUPLICATE -> {
                LogManager.w(TAG, "REJECT msg $realId from ${env.sender}: replay detected")
                return
            }
            ReplayPersistenceResult.CAPACITY_REACHED -> {
                LogManager.w(TAG, "Replay journal capacity reached; rejecting msg $realId")
                _lastError.value = "Локальный журнал защиты от повторов заполнен; новые сообщения временно скрыты."
                return
            }
            ReplayPersistenceResult.ACCEPTED -> Unit
        }
        if (env.nonce.isNotEmpty()) {
            val nonceKey = "$senderFingerprint:${ReplayNonce.hash(env.nonce)}"
            synchronized(seenNoncesLock) {
                if (seenNonces.size >= 2000) seenNonces.remove(seenNonces.keys.firstOrNull())
                seenNonces[nonceKey] = ReplayNonce.marker(
                    senderFingerprint, env.nonce, env.clientTs, acceptedAt
                )!!.expiresAt
            }
        }

        if (!isOwn && env.sender.isNotBlank()) {
            recordPeerName(serverId, senderFingerprint, env.sender)
        }

        val msg = DecryptedMessage(realId, env.sender, senderFingerprint.take(8), env.content, isOwn, ts, conversationId)
        synchronized(listeners) { listeners.forEach { it(msg) } }
    }

    private fun sendKeyExchangeRequest(
        senderFingerprint: String,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        scope.launch {
            try {
                if (!isCurrentConnection(generation, serverId, transport)) return@launch
                val ownKemPub = keyManager.getKemPublicKey()
                val ownDsaPub = keyManager.getDsaPublicKey()
                val ownSig = keyManager.signDsa(ownKemPub + ownDsaPub) // C1 attestation
                if (!isCurrentConnection(generation, serverId, transport)) return@launch
                val ok = withContext(Dispatchers.IO) {
                    transport.send(Protocol.buildCombinedKeyExchange(ownKemPub, ownDsaPub, ownSig))
                }
                if (!isCurrentConnection(generation, serverId, transport)) return@launch
                LogManager.i(TAG, "Combined key exchange request sent (ok=$ok) for pending msgs from $senderFingerprint")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(TAG, "Failed to send key exchange request", e)
            }
        }
    }

    private suspend fun processPendingMessages(senderFingerprint: String, serverId: String) {
        val pendingList = pendingMessages.take(senderFingerprint)
        if (pendingList.isEmpty()) return
        LogManager.i(TAG, "Processing ${pendingList.size} bounded pending messages")
        for (pending in pendingList) {
            try {
                val dsaPub = keyRepo.getDsaPublicKey(serverId, pending.senderFingerprint)
                if (dsaPub == null) {
                    if (queuePendingMessage(pending)) {
                        LogManager.w(TAG, "Sender DSA key is still unavailable; retaining message within limits")
                    }
                    continue
                }
                processVerifiedMessage(
                    serverId, pending.serverMsgId, pending.env, pending.ciphertext, pending.timestamp,
                    pending.isOwn, pending.senderFingerprint, dsaPub
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(TAG, "Pending message processing failed", e)
            }
        }
    }

    private fun queuePendingMessage(pending: PendingMessage): Boolean {
        return when (pendingMessages.offer(pending)) {
            PendingMessageBuffer.OfferResult.ACCEPTED -> {
                if (pending.serverMsgId > 0) {
                    synchronized(dedupLock) {
                        processedMsgIds.add(pending.serverMsgId)
                        if (processedMsgIds.size > 2000) {
                            val iter = processedMsgIds.iterator()
                            repeat(500) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                        }
                    }
                }
                true
            }
            PendingMessageBuffer.OfferResult.EXPIRED -> {
                if (pending.serverMsgId > 0) synchronized(dedupLock) { processedMsgIds.remove(pending.serverMsgId) }
                false
            }
            PendingMessageBuffer.OfferResult.LIMIT_REACHED -> {
                if (pending.serverMsgId > 0) synchronized(dedupLock) { processedMsgIds.remove(pending.serverMsgId) }
                LogManager.w(TAG, "Pending-key queue limit reached; rejecting newest message")
                _lastError.value = "Очередь сообщений от неизвестных ключей заполнена; некоторые сообщения отклонены."
                false
            }
        }
    }

    private fun pendingMessageBytes(pending: PendingMessage): Long =
        pending.ciphertext.size.toLong() +
            pending.senderFingerprint.toByteArray(Charsets.UTF_8).size +
            pending.env.sender.toByteArray(Charsets.UTF_8).size +
            pending.env.content.toByteArray(Charsets.UTF_8).size +
            pending.env.nonce.toByteArray(Charsets.UTF_8).size +
            pending.env.dm.toByteArray(Charsets.UTF_8).size +
            pending.env.signature.toByteArray(Charsets.UTF_8).size +
            256L

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
                } catch (e: CancellationException) {
                    throw e
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

    private suspend fun onSyncResponse(
        r: Protocol.Reader,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        val resp = Protocol.parseSyncResponse(r)
        if (!isCurrentConnection(generation, serverId, transport)) return
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

        for (m in toProcess) {
            try {
                val result = decryptPerRecipientBlob(m.payload, serverId)
                if (result == null) {
                    LogManager.w(TAG, "SyncResponse decrypt failed for ${m.id}")
                    continue
                }
                val senderFingerprint = result.senderFingerprint
                val env = result.env
                val isOwn = senderFingerprint == ownFingerprint()
                LogManager.d(TAG, "onSyncResponse: msgId=${m.id} sender='${env.sender}' isOwn=$isOwn dm=${env.dm.take(8)}")

                val dsaPub = keyRepo.getDsaPublicKey(serverId, senderFingerprint)
                if (dsaPub == null) {
                    val pending = PendingMessage(
                        serverMsgId = m.id,
                        senderFingerprint = senderFingerprint,
                        env = env,
                        ciphertext = m.payload,
                        timestamp = m.timestamp,
                        isOwn = isOwn
                    )
                    if (queuePendingMessage(pending)) {
                        sendKeyExchangeRequest(senderFingerprint, generation, serverId, transport)
                    }
                    continue
                }

                processVerifiedMessage(serverId, m.id, env, m.payload, m.timestamp, isOwn, senderFingerprint, dsaPub)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(TAG, "SyncResponse message processing failed for ${m.id}", e)
            }
        }

        if (resp.messages.size >= 500 && maxId > 0) {
            LogManager.i(TAG, "SyncResponse got 500 msgs (maxId=$maxId), fetching more...")
            delay(200)
            if (!isCurrentConnection(generation, serverId, transport)) return
            val ok = withContext(Dispatchers.IO) { transport.send(Protocol.buildSync(maxId)) }
            if (!isCurrentConnection(generation, serverId, transport)) return
            LogManager.i(TAG, "Follow-up sync request sent (ok=$ok, lastSeenId=$maxId)")
        }
    }

    private fun onHeartbeat() {
        LogManager.d(TAG, "heartbeat received from server")
    }

    private fun startHeartbeat(generation: Long, serverId: String, transport: WebTransportClient) {
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, serverId, transport)) return
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                LogManager.i(TAG, "heartbeat started (interval=${HEARTBEAT_INTERVAL_MS}ms)")
                var heartbeatCount = 0
                while (isActive && isCurrentConnection(generation, serverId, transport)) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (!isActive || !isCurrentConnection(generation, serverId, transport)) break
                    val ok = withContext(Dispatchers.IO) { transport.send(Protocol.buildHeartbeat()) }
                    if (!isCurrentConnection(generation, serverId, transport)) break
                    LogManager.d(TAG, "heartbeat sent (ok=$ok)")

                    heartbeatCount++
                    if (heartbeatCount % RESYNC_EVERY_N_HEARTBEATS == 0) {
                        val lastId = repo.lastSeenId(serverId)
                        if (!isCurrentConnection(generation, serverId, transport)) break
                        val syncOk = withContext(Dispatchers.IO) { transport.send(Protocol.buildSync(lastId)) }
                        if (!isCurrentConnection(generation, serverId, transport)) break
                        LogManager.i(TAG, "Periodic re-sync sent (ok=$syncOk, lastSeenId=$lastId)")
                    }
                }
                LogManager.i(TAG, "heartbeat loop exited")
            }
        }
    }

    suspend fun decryptEntity(entity: MessageEntity): DecryptedMessage? = decryptAndVerifyStoredEntity(entity)

    private suspend fun decryptAndVerifyStoredEntity(entity: MessageEntity): DecryptedMessage? {
        return try {
            ensureKeyPair()
            val result = decryptPerRecipientBlob(entity.ciphertext, entity.serverId) ?: return null
            val dsaPub = keyRepo.getDsaPublicKey(entity.serverId, result.senderFingerprint)
            if (dsaPub == null) {
                LogManager.w(TAG, "history verification unavailable for ${entity.serverMsgId}: sender DSA key missing")
                return null
            }
            if (!InnerEnvelopeSignatureVerifier.verify(result.env, dsaPub) { publicKey, message, signature ->
                    keyManager.verifyDsa(publicKey, message, signature)
                }) {
                LogManager.w(TAG, "history signature verification failed for ${entity.serverMsgId}")
                return null
            }

            val isOwn = result.senderFingerprint == keyManager.getFingerprint()
            val conversationId = when {
                result.env.dm.isNotEmpty() && isOwn -> "dm:${result.env.dm}"
                result.env.dm.isNotEmpty() -> "dm:${result.senderFingerprint}"
                else -> "group"
            }
            if (entity.isOwn != isOwn || entity.conversationId != conversationId) {
                LogManager.w(TAG, "history routing metadata mismatch for ${entity.serverMsgId}; dropping persisted row")
                return null
            }
            DecryptedMessage(
                serverMsgId = entity.serverMsgId,
                sender = result.env.sender,
                senderFingerprint = result.senderFingerprint.take(8),
                plaintext = result.env.content,
                isOwn = isOwn,
                timestamp = entity.timestamp,
                conversationId = conversationId
            )
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            LogManager.w(TAG, "decryptEntity failed for ${entity.serverMsgId}", ex)
            null
        }
    }

    suspend fun decryptHistory(serverId: String): List<DecryptedMessage> {
        val entities = repo.load(serverId)
        return buildList {
            for (entity in entities) {
                if (entity.serverId != serverId) continue
                decryptAndVerifyStoredEntity(entity)?.let(::add)
            }
        }
    }

    // ------------------------------------------------------------------
    // Key exchange handling
    // ------------------------------------------------------------------

    private suspend fun handleIncoming(
        raw: ByteArray,
        generation: Long,
        server: ServerConfig,
        transport: WebTransportClient
    ) {
        if (!isCurrentConnection(generation, server.id, transport)) return
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
                Protocol.OP_AUTH_RESULT -> onAuthResult(r, generation, server, transport)
                Protocol.OP_SYNC_RESPONSE -> onSyncResponse(r, generation, server.id, transport)
                Protocol.OP_DATA -> onData(r, generation, server.id, transport)
                Protocol.OP_HEARTBEAT -> onHeartbeat()
                Protocol.OP_KEY_EXCHANGE_KEM_DSA -> onCombinedKeyExchange(r, generation, server.id, transport)
                Protocol.OP_NEW_CERT_HASH -> onNewCertHash(r, server.id)
                Protocol.OP_AUTH_CHALLENGE -> onAuthChallenge(r, generation, server.id, transport)
                else -> LogManager.w(TAG, "UNKNOWN opcode 0x%02x (${raw.size} bytes)".format(opcode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "PARSE FAILED opcode=0x%02x: ${e.message}".format(opcode), e)
        }
    }

    private fun onAuthChallenge(
        r: Protocol.Reader,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        val frame = Protocol.parseAuthChallenge(r)
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, serverId, transport)) return
            pendingSaltB64 = frame.saltB64
            pendingArgonMemKB = frame.argonMemKB
            pendingArgonIter = frame.argonIterations
            pendingArgonPar = frame.argonParallelism
            authChallengeNonce.complete(frame.nonce)
        }
        LogManager.i(TAG, "AUTH CHALLENGE received (nonce=${frame.nonce.size} bytes, salt=${frame.saltB64.length} chars, argon2 m=${frame.argonMemKB} t=${frame.argonIterations} p=${frame.argonParallelism})")
    }

    private fun onNewCertHash(r: Protocol.Reader, serverId: String) {
        val frame = Protocol.parseNewCertHash(r)
        certManager.rotateHash(serverId, frame.hash)
        LogManager.i(TAG, "NEW CERT HASH received and stored as pending: ${frame.hash.take(16)}...")
    }

    private fun onAuthResult(
        r: Protocol.Reader,
        generation: Long,
        server: ServerConfig,
        transport: WebTransportClient
    ) {
        if (!isCurrentConnection(generation, server.id, transport)) return
        val res = Protocol.parseAuthResult(r)
        if (!res.success) {
            LogManager.e(TAG, "AUTH REJECTED by server: ${res.errorMessage ?: "no reason"}")
            synchronized(lock) {
                if (isCurrentConnectionLocked(generation, server.id, transport) && !userDisconnect) {
                    cancelAuthTimeout()
                    _state.value = ConnectionState.ERROR
                    _lastError.value = "Аутентификация отклонена сервером: ${res.errorMessage ?: "нет причины"}"
                }
            }
            return
        }
        synchronized(lock) {
            if (!isCurrentConnectionLocked(generation, server.id, transport) || userDisconnect) return
            cancelAuthTimeout()
            _state.value = ConnectionState.AUTHENTICATING
        }
        LogManager.i(TAG, "AUTH SUCCESS — publishing keys + requesting sync (clientName='$clientName')")
        scope.launch {
            if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
            // Cache our own key pair. Storage failure must not block KeyExchange/Sync.
            try {
                val ownFp = keyManager.getFingerprint()
                keyRepo.cacheKey(
                    server.id,
                    ownFp,
                    keyManager.getKemPublicKey(),
                    keyManager.getDsaPublicKey()
                )
            } catch (t: CancellationException) {
                throw t
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
            if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch

            val kemPub = keyManager.getKemPublicKey()
            val dsaPub = keyManager.getDsaPublicKey()
            val dsaSig = keyManager.signDsa(kemPub + dsaPub) // C1 attestation
            // Send KeyExchange + Sync on the transport that authenticated this connection.
            var keySent = false
            for (attempt in 1..3) {
                if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
                keySent = withContext(Dispatchers.IO) {
                    transport.send(Protocol.buildCombinedKeyExchange(kemPub, dsaPub, dsaSig))
                }
                if (!isCurrentConnection(generation, server.id, transport)) return@launch
                if (keySent || userDisconnect) break
                LogManager.w(TAG, "KeyExchange send attempt $attempt failed, retrying")
                delay(300L * attempt)
            }
            LogManager.i(TAG, "KeyExchange combined KEM+DSA sent (ok=$keySent)")
            if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
            if (!keySent) {
                LogManager.e(TAG, "KeyExchange could not be sent after 3 attempts — dropping to ERROR for reconnect")
                failCurrentConnection(generation, server.id, transport)
                return@launch
            }

            val lastSeen = try {
                repo.lastSeenId(server.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
            var syncSent = false
            for (attempt in 1..3) {
                if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
                syncSent = withContext(Dispatchers.IO) { transport.send(Protocol.buildSync(lastSeen)) }
                if (!isCurrentConnection(generation, server.id, transport)) return@launch
                if (syncSent || userDisconnect) break
                LogManager.w(TAG, "Sync send attempt $attempt failed, retrying")
                delay(300L * attempt)
            }
            LogManager.i(TAG, "Sync request sent (ok=$syncSent, lastSeenId=$lastSeen)")
            if (!isCurrentConnection(generation, server.id, transport) || userDisconnect) return@launch
            if (!syncSent) {
                LogManager.e(TAG, "Sync could not be sent after 3 attempts — dropping to ERROR for reconnect")
                failCurrentConnection(generation, server.id, transport)
                return@launch
            }

            val becameReady = synchronized(lock) {
                if (!isCurrentConnectionLocked(generation, server.id, transport) || userDisconnect) {
                    false
                } else {
                    _state.value = ConnectionState.AUTHENTICATED
                    _state.value = ConnectionState.READY
                    true
                }
            }
            if (!becameReady) return@launch
            LogManager.i(TAG, "READY")
            startHeartbeat(generation, server.id, transport)

            restoreOutbox(server.id)
            synchronized(outboxLock) {
                if (outbox.any { it.serverId == server.id } && !flushOutboxRunning.get()) {
                    LogManager.i(TAG, "READY: outbox has queued messages for this server, flushing")
                    scope.launch { flushOutbox() }
                }
            }

            if (!isCurrentConnection(generation, server.id, transport)) return@launch
            val messages = repo.load(server.id)
            if (!isCurrentConnection(generation, server.id, transport)) return@launch
            if (messages.isEmpty()) {
                setLastErrorForConnection(
                    generation,
                    server.id,
                    transport,
                    "Вы присоединились к чату. Сообщения до вашего подключения недоступны."
                )
            }

            delay(3000)
            if (!isCurrentConnection(generation, server.id, transport)) return@launch
            val delayedLastSeen = repo.lastSeenId(server.id)
            if (!isCurrentConnection(generation, server.id, transport)) return@launch
            val reSyncOk = withContext(Dispatchers.IO) { transport.send(Protocol.buildSync(delayedLastSeen)) }
            if (!isCurrentConnection(generation, server.id, transport)) return@launch
            LogManager.i(TAG, "Delayed re-sync sent (ok=$reSyncOk, lastSeenId=$delayedLastSeen)")
        }
    }

    private suspend fun onCombinedKeyExchange(
        r: Protocol.Reader,
        generation: Long,
        serverId: String,
        transport: WebTransportClient
    ) {
        val frame = Protocol.parseCombinedKeyExchange(r)
        if (!isCurrentConnection(generation, serverId, transport)) return

        try {
            PqcCrypto.validateKemPublicKey(frame.kemPublicKey)
            PqcCrypto.validateDsaPublicKey(frame.dsaPublicKey)
            val kemFp = keyManager.fingerprintForBytes(frame.kemPublicKey)
            val dsaFp = keyManager.fingerprintForBytes(frame.dsaPublicKey)

            // C1 (MITM defense, SPEC §1): verify the ML-DSA-65 attestation over
            // (kemPub || dsaPub). The sender must prove ownership of the DSA
            // private key. We only trust a key if (a) it is brand-new TOFU, or
            // (b) it carries a valid signature from the ALREADY-KNOWN DSA key.
            // The read loop awaits this bounded trust/cache path so inbound frames
            // cannot enqueue unbounded key-exchange coroutines. The trust formula is
            // factored into Protocol.evaluateKeyExchangeTrust
            // so it is unit-testable on the JVM without an Android Context
            // (see C1AttestationTest). Behaviour is unchanged.
            val existingDsa = keyRepo.findDsaKeyByPub(serverId, frame.dsaPublicKey)
                ?: keyRepo.getDsaPublicKey(serverId, kemFp)
            if (!isCurrentConnection(generation, serverId, transport)) return
            val trusted = Protocol.evaluateKeyExchangeTrust(
                existingDsa = existingDsa,
                kemPublicKey = frame.kemPublicKey,
                dsaPublicKey = frame.dsaPublicKey,
                signature = frame.signature,
                verify = { dsaPub, data, sig -> keyManager.verifyDsa(dsaPub, data, sig) }
            )

            if (!trusted) {
                LogManager.w(TAG, "CombinedKeyExchange REJECTED: attestation failed for dsa_fp=$dsaFp (possible MITM)")
                return
            }

            keyRepo.cacheKey(serverId, kemFp, frame.kemPublicKey, frame.dsaPublicKey)
            if (!isCurrentConnection(generation, serverId, transport)) return
            val attestVerified = frame.signature != null && keyManager.verifyDsa(
                frame.dsaPublicKey, frame.kemPublicKey + frame.dsaPublicKey, frame.signature
            )
            LogManager.i(TAG, "CombinedKeyExchange: kem_fp=$kemFp dsa_fp=$dsaFp cached (attestation ${if (attestVerified) "verified" else "TOFU"})")
            processPendingMessages(kemFp, serverId)
        } catch (e: CancellationException) {
            throw e
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
        private const val RESYNC_EVERY_N_HEARTBEATS = 2
    }
}
