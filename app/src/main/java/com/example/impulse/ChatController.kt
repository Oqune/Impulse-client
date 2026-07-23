package com.example.impulse

import android.content.Context
import com.example.impulse.data.MessageRepository
import com.example.impulse.data.ServerConfig
import com.example.impulse.data.db.MessageEntity
import com.example.impulse.security.PqcCrypto
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
import java.util.concurrent.ConcurrentHashMap

class ChatController private constructor(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught coroutine exception", e)
        }
    )
    private val secure = SecureStorage(context)
    private val certManager = TrustedCertManager(context)
    private val repo = MessageRepository(context)

    private var client: WebTransportClient? = null
    private var currentServer: ServerConfig? = null
    private var clientName: String = ""

    private var myPrivateKey: ByteArray? = null
    private var myPublicKey: ByteArray? = null

    private var myMlDsa65Private: ByteArray? = null
    private var myMlDsa65Public: ByteArray? = null

    private val peerPublicKeys = ConcurrentHashMap<String, ByteArray>()
    private val peerMlDsa65Keys = ConcurrentHashMap<String, ByteArray>()
    private var groupSecret: ByteArray? = null

    private fun fingerprint(key: ByteArray): String =
        android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)

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
        val priv = secure.getBytes(SecureStorage.KEY_PQ_PRIVATE)
        val pub = secure.getBytes(SecureStorage.KEY_PQ_PUBLIC)
        if (priv != null && pub != null) {
            myPrivateKey = priv
            myPublicKey = pub
        } else {
            val kp = PqcCrypto.generateKeyPair()
            myPrivateKey = kp.privateEncoded
            myPublicKey = kp.publicEncoded
            secure.putBytes(SecureStorage.KEY_PQ_PRIVATE, kp.privateEncoded)
            secure.putBytes(SecureStorage.KEY_PQ_PUBLIC, kp.publicEncoded)
        }
        val dsaPriv = secure.getBytes(SecureStorage.KEY_MLDSA65_PRIVATE)
        val dsaPub = secure.getBytes(SecureStorage.KEY_MLDSA65_PUBLIC)
        if (dsaPriv != null && dsaPub != null) {
            myMlDsa65Private = dsaPriv
            myMlDsa65Public = dsaPub
        } else {
            val dsa = PqcCrypto.generateMlDsa65KeyPair()
            myMlDsa65Private = dsa.privateEncoded
            myMlDsa65Public = dsa.publicEncoded
            secure.putBytes(SecureStorage.KEY_MLDSA65_PRIVATE, dsa.privateEncoded)
            secure.putBytes(SecureStorage.KEY_MLDSA65_PUBLIC, dsa.publicEncoded)
        }
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
        ensureKeyPair()
        _lastError.value = null

        // Reset crypto state from any previous session
        peerPublicKeys.clear()
        peerMlDsa65Keys.clear()
        groupSecret = null

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
                val removed = repo.purgeExpired()
                LogManager.i(TAG, "purged $removed expired messages on connect")
            } catch (e: Exception) {
                LogManager.w(TAG, "purgeExpired failed (non-fatal)", e)
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

    suspend fun sendChat(plaintext: String): Boolean {
        if (_state.value != ConnectionState.READY) {
            LogManager.w(TAG, "sendChat ignored: not READY (state=${_state.value})")
            return false
        }
        val secret = groupSecret ?: run {
            LogManager.w(TAG, "No group secret yet; cannot encrypt message.")
            return false
        }
        val innerJson = Protocol.buildInnerEnvelope(
            sender = clientName,
            signature = "",
            content = plaintext
        )
        val signature = myMlDsa65Private?.let { priv ->
            PqcCrypto.signMlDsa65(priv, innerJson)
        } ?: run {
            LogManager.e(TAG, "ML-DSA-65 key not available; cannot sign outgoing message")
            return false
        }
        val signedInner = Protocol.buildInnerEnvelope(
            sender = clientName,
            signature = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
            content = plaintext
        )
        val blob = PqcCrypto.aesEncrypt(secret, signedInner)
        val frame = Protocol.buildData(blob)
        val ok = client?.send(frame) ?: false
        if (ok) {
            val tempId = -System.currentTimeMillis()
            scope.launch {
                repo.upsert(
                    MessageEntity(
                        serverId = currentServer?.id ?: "",
                        serverMsgId = tempId,
                        sender = clientName,
                        ciphertext = blob,
                        iv = byteArrayOf(),
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        return ok
    }

    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
        val opcodeName = when (opcode) {
            Protocol.OP_AUTH_RESULT -> "AuthResult"
            Protocol.OP_SYNC_RESPONSE -> "SyncResponse"
            Protocol.OP_DATA -> "Data"
            Protocol.OP_HEARTBEAT -> "Heartbeat"
            Protocol.OP_KEY_EXCHANGE -> "KeyExchange"
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
        LogManager.i(TAG, "AUTH SUCCESS — publishing keys + requesting sync")
        _state.value = ConnectionState.AUTHENTICATING
        scope.launch {
            val kemOk = myPublicKey?.let { pub -> client?.send(Protocol.buildKeyExchange(pub)) } ?: false
            LogManager.i(TAG, "KeyExchange ML-KEM sent (ok=$kemOk)")
            val dsaOk = myMlDsa65Public?.let { pub -> client?.send(Protocol.buildKeyExchange(pub)) } ?: false
            LogManager.i(TAG, "KeyExchange ML-DSA-65 sent (ok=$dsaOk)")
            val lastSeen = repo.lastSeenId(currentServer?.id ?: "")
            val syncOk = client?.send(Protocol.buildSync(lastSeen)) ?: false
            LogManager.i(TAG, "Sync request sent (ok=$syncOk, lastSeen=$lastSeen)")

            // Single-peer: server excludes sender from key exchange relay, so we
            // never receive our own key back.  Derive the group secret from our
            // own ML-KEM key alone — this is correct for a single participant.
            // If other peers connect later, onKeyExchange will add their keys and
            // re-derive.
            if (groupSecret == null && myPublicKey != null) {
                val fp = fingerprint(myPublicKey!!)
                peerPublicKeys[fp] = myPublicKey!!
                LogManager.i(TAG, "single-peer: added own key to peerPublicKeys, deriving group secret")
                recomputeGroupSecret()
                if (groupSecret != null) {
                    _state.value = ConnectionState.AUTHENTICATED
                    _state.value = ConnectionState.READY
                    LogManager.i(TAG, "READY (single-peer group secret derived)")
                } else {
                    LogManager.e(TAG, "deriveGroupKey failed for single-peer")
                    _state.value = ConnectionState.ERROR
                    _lastError.value = "Не удалось вывести групповый ключ"
                }
            }
        }
        startHeartbeat()
    }

    private fun onKeyExchange(r: Protocol.Reader) {
        val frame = Protocol.parseKeyExchange(r)
        val key = frame.publicKey

        try {
            val kf = java.security.KeyFactory.getInstance("Dilithium", "BCPQC")
            kf.generatePublic(java.security.spec.X509EncodedKeySpec(key))
            val fp = fingerprint(key)
            peerMlDsa65Keys[fp] = key
            LogManager.i(TAG, "KeyExchange ML-DSA-65 received (${key.size} bytes, peers=${peerMlDsa65Keys.size})")
            return
        } catch (_: Exception) { }

        val fp = fingerprint(key)
        peerPublicKeys[fp] = key
        LogManager.i(TAG, "KeyExchange ML-KEM received (${key.size} bytes, peers=${peerPublicKeys.size})")
        recomputeGroupSecret()
        if (groupSecret != null) {
            LogManager.i(TAG, "GROUP SECRET ESTABLISHED — channel ready")
            _state.value = ConnectionState.AUTHENTICATED
            _state.value = ConnectionState.READY
            LogManager.i(TAG, "READY — secure chat enabled")
        }
    }

    private fun recomputeGroupSecret() {
        if (myPublicKey == null) return
        val allKeys = ArrayList<ByteArray>(peerPublicKeys.values)
        allKeys.add(myPublicKey!!)
        try {
            groupSecret = PqcCrypto.deriveGroupKey(allKeys)
            LogManager.i(TAG, "group secret derived from ${allKeys.size} keys")
        } catch (e: Exception) {
            LogManager.e(TAG, "deriveGroupKey failed", e)
        }
    }

    private fun onData(r: Protocol.Reader) {
        val frame = Protocol.parseData(r)
        val secret = groupSecret ?: run {
            LogManager.w(TAG, "Data received but no group secret yet; dropping.")
            return
        }
        val innerBytes = try {
            PqcCrypto.aesDecrypt(secret, frame.payload)
        } catch (e: Exception) {
            LogManager.e(TAG, "decrypt failed", e)
            return
        }
        val env = Protocol.parseInnerEnvelope(innerBytes) ?: run {
            LogManager.e(TAG, "inner envelope parse failed")
            return
        }
        val canonical = Protocol.buildInnerEnvelope(env.sender, "", env.content)
        val sigBytes = runCatching {
            android.util.Base64.decode(env.signature, android.util.Base64.NO_WRAP)
        }.getOrNull()
        if (sigBytes != null && sigBytes.isNotEmpty()) {
            val pub = peerMlDsa65Keys.values.firstOrNull { pub ->
                PqcCrypto.verifyMlDsa65(pub, canonical, sigBytes)
            }
            if (pub == null) {
                LogManager.w(TAG, "ML-DSA-65 signature verification failed for '${env.sender}'; dropping")
                return
            }
        }
        val isOwn = env.sender == clientName
        val realId = frame.serverMsgId
        scope.launch {
            repo.upsert(
                MessageEntity(
                    serverId = currentServer?.id ?: "",
                    serverMsgId = realId,
                    sender = env.sender,
                    ciphertext = frame.payload,
                    iv = byteArrayOf(),
                    timestamp = if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis()
                )
            )
        }
        val msg = DecryptedMessage(realId, env.sender, env.content, isOwn,
            if (frame.timestamp != 0L) frame.timestamp else System.currentTimeMillis())
        synchronized(listeners) { listeners.forEach { it(msg) } }
    }

    private fun onSyncResponse(r: Protocol.Reader) {
        val resp = Protocol.parseSyncResponse(r)
        val secret = groupSecret ?: run {
            LogManager.w(TAG, "SyncResponse but no group secret yet; cannot decrypt history.")
            return
        }
        LogManager.i(TAG, "SyncResponse: ${resp.messages.size} messages")
        for (m in resp.messages) {
            val innerBytes = try {
                PqcCrypto.aesDecrypt(secret, m.payload)
            } catch (e: Exception) {
                LogManager.e(TAG, "history decrypt failed for ${m.id}", e)
                continue
            }
            val env = Protocol.parseInnerEnvelope(innerBytes) ?: continue
            val isOwn = env.sender == clientName
            scope.launch {
                repo.upsert(
                    MessageEntity(
                        serverId = currentServer?.id ?: "",
                        serverMsgId = m.id,
                        sender = env.sender,
                        ciphertext = m.payload,
                        iv = byteArrayOf(),
                        timestamp = m.timestamp
                    )
                )
            }
            val msg = DecryptedMessage(m.id, env.sender, env.content, isOwn, m.timestamp)
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
                val ok = client?.send(Protocol.buildHeartbeat()) ?: false
                LogManager.d(TAG, "heartbeat sent (ok=$ok)")
            }
        }
    }

    fun decryptEntity(entity: MessageEntity): DecryptedMessage? {
        val secret = groupSecret ?: return null
        val env = try {
            Protocol.parseInnerEnvelope(PqcCrypto.aesDecrypt(secret, entity.ciphertext))
        } catch (ex: Exception) {
            LogManager.w(TAG, "decryptEntity failed for ${entity.serverMsgId}", ex)
            return null
        } ?: return null
        return DecryptedMessage(
            serverMsgId = entity.serverMsgId,
            sender = env.sender,
            plaintext = env.content,
            isOwn = env.sender == clientName,
            timestamp = entity.timestamp
        )
    }

    suspend fun decryptHistory(serverId: String): List<DecryptedMessage> {
        val secret = groupSecret ?: return emptyList()
        val entities = repo.load(serverId)
        return entities.mapNotNull { e ->
            val env = try {
                Protocol.parseInnerEnvelope(PqcCrypto.aesDecrypt(secret, e.ciphertext))
            } catch (ex: Exception) {
                LogManager.w(TAG, "history decrypt failed for ${e.serverMsgId}", ex)
                return@mapNotNull null
            } ?: return@mapNotNull null
            DecryptedMessage(
                serverMsgId = e.serverMsgId,
                sender = env.sender,
                plaintext = env.content,
                isOwn = env.sender == clientName,
                timestamp = e.timestamp
            )
        }
    }

    companion object {
        private const val TAG = "ChatController"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val AUTH_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 5_000L

        @Volatile
        private var INSTANCE: ChatController? = null

        fun getInstance(context: Context): ChatController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatController(context.applicationContext).also { INSTANCE = it }
            }
    }
}
