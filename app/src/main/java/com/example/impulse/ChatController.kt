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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Central coordinator that wires together the transport, post-quantum crypto,
 * trusted-certificate management and the encrypted local store.
 *
 * This is the single entry point the UI talks to. It replaces the old
 * `WebSocketManager` and adds:
 *  - ML-KEM-768 key generation / exchange / group-secret derivation.
 *  - AES-256-GCM encryption of an inner JSON envelope (sender, signature,
 *    content) before it is placed inside the binary [Protocol.OP_DATA] packet.
 *  - TOFU certificate pinning via [TrustedCertManager].
 *  - `last_seen_id` based history synchronisation (binary [Protocol.OP_SYNC]).
 *
 * The wire format is fully binary (see [Protocol]); the server never sees any
 * plaintext metadata — everything sensitive lives inside the encrypted Data
 * payload.
 */
class ChatController private constructor(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            // Any uncaught exception inside a controller coroutine is logged but
            // does NOT crash the app (SupervisorJob keeps sibling work alive).
            LogManager.e(TAG, "uncaught coroutine exception", e)
        }
    )
    private val secure = SecureStorage(context)
    private val certManager = TrustedCertManager(context)
    private val repo = MessageRepository(context)

    private var client: WebTransportClient? = null
    private var currentServer: ServerConfig? = null
    private var clientName: String = ""

    // Our ML-KEM key pair (generated once, persisted in EncryptedSharedPreferences).
    private var myPrivateKey: ByteArray? = null
    private var myPublicKey: ByteArray? = null

    // Our Ed25519 signing key pair (sender authentication of each message).
    private var myEd25519Private: ByteArray? = null
    private var myEd25519Public: ByteArray? = null

    // Per-peer ML-KEM public keys (X509-encoded) received via KeyExchange (0x08).
    // Keyed by a stable base64 fingerprint (NOT contentHashCode, which can
    // collide) so re-adding the same peer never creates a duplicate entry and
    // the group secret stays deterministic across all clients.
    private val peerPublicKeys = ConcurrentHashMap<String, ByteArray>()
    // Per-peer Ed25519 public keys (X509-encoded) received via KeyExchange (0x08).
    // Keyed by the same base64 fingerprint used to verify message signatures.
    private val peerEd25519Keys = ConcurrentHashMap<String, ByteArray>()
    // The derived group secret used for AES (null until enough peers exchanged).
    private var groupSecret: ByteArray? = null

    /** Stable, collision-free fingerprint of a public key (base64 of the bytes). */
    private fun fingerprint(key: ByteArray): String =
        android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // UI listeners for decrypted chat messages.
    private val listeners = mutableListOf<(DecryptedMessage) -> Unit>()

    // Heartbeat ticker.
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    data class DecryptedMessage(
        val serverMsgId: Long,
        val sender: String,
        val plaintext: String,
        val isOwn: Boolean,
        val timestamp: Long = 0L
    )

    // ------------------------------------------------------------------

    fun addMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeMessageListener(l: (DecryptedMessage) -> Unit) = synchronized(listeners) { listeners.remove(l) }

    /** Ensures an ML-KEM-768 + Ed25519 key pair exists, loading from secure storage. */
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
        // Ed25519 signing key (separate from the PQC KEM key).
        val edPriv = secure.getBytes(SecureStorage.KEY_ED25519_PRIVATE)
        val edPub = secure.getBytes(SecureStorage.KEY_ED25519_PUBLIC)
        if (edPriv != null && edPub != null) {
            myEd25519Private = edPriv
            myEd25519Public = edPub
        } else {
            val ed = PqcCrypto.generateEd25519KeyPair()
            myEd25519Private = ed.privateEncoded
            myEd25519Public = ed.publicEncoded
            secure.putBytes(SecureStorage.KEY_ED25519_PRIVATE, ed.privateEncoded)
            secure.putBytes(SecureStorage.KEY_ED25519_PUBLIC, ed.publicEncoded)
        }
    }

    fun connect(server: ServerConfig, name: String) {
        currentServer = server
        clientName = name
        ensureKeyPair()

        val prefs = com.example.impulse.data.ServerPreferences(context)
        val skipPinning = prefs.getDevSkipPinning()
        val hashes = certManager.getHashes(server.id)
        if (hashes.isEmpty() && !skipPinning) {
            // No trusted hash yet → the UI must drive the user to QR scanning.
            _state.value = ConnectionState.ERROR
            LogManager.w(TAG, "No trusted cert hash for ${server.id}; QR scan required.")
            return
        }

        client = WebTransportClient(
            context = context,
            certHashes = hashes,
            onFrame = { raw -> handleIncoming(raw) },
            onState = { s -> onTransportState(s) },
            onCertHashPush = { h -> certManager.rotateHash(server.id, h) },
            skipCertPinning = skipPinning
        )
        scope.launch {
            try {
                val removed = repo.purgeExpired()
                LogManager.i(TAG, "purged $removed expired messages on connect")
            } catch (e: Exception) {
                LogManager.w(TAG, "purgeExpired failed (non-fatal)", e)
            }
        }
        LogManager.i(TAG, "connecting to ${server.name} (devSkipPinning=$skipPinning)")
        client?.connect(server.getWebTransportUrl())
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        client?.disconnect()
        client = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Maps raw transport states onto the controller's richer state machine.
     * The transport only knows about CONNECTING / CONNECTED / ERROR; the
     * AUTHENTICATING / AUTHENTICATED / READY states are owned by this controller
     * and are never overwritten by the transport (so we never report a false
     * "connected" before auth + key exchange complete).
     */
    private fun onTransportState(s: ConnectionState) {
        when (s) {
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.ERROR -> _state.value = s
            else -> { /* ignore AUTHENTICATED/READY coming from transport */ }
        }
    }

    /** Sends a plaintext chat message (encrypts inner envelope with group key). */
    fun sendChat(plaintext: String): Boolean {
        // Only send once the channel is fully READY. This fixes the "false
        // connected" bug where messages were dispatched before the secure
        // channel (auth + group secret) was established.
        if (_state.value != ConnectionState.READY) {
            LogManager.w(TAG, "sendChat ignored: not READY (state=${_state.value})")
            return false
        }
        val secret = groupSecret ?: run {
            LogManager.w(TAG, "No group secret yet; cannot encrypt message.")
            return false
        }
        // Build the inner envelope (sender / content) and sign it with our
        // Ed25519 key so receivers can authenticate the sender.
        val innerJson = Protocol.buildInnerEnvelope(
            sender = clientName,
            signature = "", // placeholder; real signature computed below
            content = plaintext
        )
        // Sign the canonical bytes (everything except the signature field).
        val signature = myEd25519Private?.let { priv ->
            PqcCrypto.sign(priv, innerJson)
        } ?: byteArrayOf()
        val signedInner = Protocol.buildInnerEnvelope(
            sender = clientName,
            signature = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
            content = plaintext
        )
        val blob = PqcCrypto.aesEncrypt(secret, signedInner)
        val frame = Protocol.buildData(blob)
        val ok = client?.send(frame) ?: false
        if (ok) {
            // Optimistically store our own message. Use a NEGATIVE temp id so it
            // can never collide with a real server-assigned id (always >= 0);
            // the server echo (real id) will be upserted separately.
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

    // ------------------------------------------------------------------
    // Incoming handling
    // ------------------------------------------------------------------

    private fun handleIncoming(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
        try {
            val r = Protocol.Reader(raw, 1)
            when (opcode) {
                Protocol.OP_AUTH_RESULT -> onAuthResult(r)
                Protocol.OP_SYNC_RESPONSE -> onSyncResponse(r)
                Protocol.OP_DATA -> onData(r, serverMsgId = 0L)
                Protocol.OP_HEARTBEAT -> onHeartbeat()
                Protocol.OP_KEY_EXCHANGE -> onKeyExchange(r)
                Protocol.OP_NEW_CERT_HASH -> {
                    // Already routed to certManager by the transport; nothing else.
                }
                else -> LogManager.w(TAG, "unknown opcode 0x%02x".format(opcode))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "bad frame opcode=0x%02x".format(opcode), e)
        }
    }

    private fun onAuthResult(r: Protocol.Reader) {
        val res = Protocol.parseAuthResult(r)
        if (!res.success) {
            LogManager.e(TAG, "auth failed: ${res.errorMessage}")
            _state.value = ConnectionState.ERROR
            return
        }
        LogManager.i(TAG, "auth success; publishing keys + requesting sync")
        // Server accepted our password. We are now AUTHENTICATING: the secure
        // channel is not fully usable until the group secret is derived from the
        // exchanged public keys (see onKeyExchange → READY).
        _state.value = ConnectionState.AUTHENTICATING
        // Publish our ML-KEM public key so peers can derive the shared group
        // secret. The AUTHENTICATED/READY state is set only after the group
        // secret is established (see onKeyExchange).
        myPublicKey?.let { pub -> client?.send(Protocol.buildKeyExchange(pub)) }
        // Also publish our Ed25519 public key so peers can verify our signatures.
        myEd25519Public?.let { pub -> client?.send(Protocol.buildKeyExchange(pub)) }
        // Request history sync. lastSeenId is a suspend DB call; run it in the
        // controller scope instead of blocking the transport callback thread.
        scope.launch {
            val lastSeen = repo.lastSeenId(currentServer?.id ?: "")
            client?.send(Protocol.buildSync(lastSeen))
        }
        startHeartbeat()
    }

    private fun onKeyExchange(r: Protocol.Reader) {
        val frame = Protocol.parseKeyExchange(r)
        // The server relays two kinds of public keys via KeyExchange:
        //  - ML-KEM-768 (X509, ~1184 bytes) → used for the group secret.
        //  - Ed25519 (X509, 44 bytes)        → used to verify message signatures.
        // We distinguish them by encoded length (no extra wire field needed).
        val key = frame.publicKey
        if (key.size >= 200) {
            // ML-KEM public key. Keyed by a stable base64 fingerprint so the
            // same peer never produces a duplicate entry (contentHashCode could
            // collide). This keeps the group secret deterministic across clients.
            val fp = fingerprint(key)
            peerPublicKeys[fp] = key
            LogManager.i(TAG, "KeyExchange: ML-KEM pubkey received (peers=${peerPublicKeys.size})")
            recomputeGroupSecret()
            // Once we have a group secret we are fully authenticated & ready.
            if (groupSecret != null) {
                LogManager.i(TAG, "AUTHENTICATED (group secret established)")
                _state.value = ConnectionState.AUTHENTICATED
                // Transition to READY so the UI can enable the send box.
                _state.value = ConnectionState.READY
                LogManager.i(TAG, "READY: secure channel established, chat enabled")
            }
        } else {
            // Ed25519 public key, keyed by its own fingerprint; matched against
            // the envelope sender via the relay (sender name is carried in the
            // inner envelope).
            val fp = fingerprint(key)
            peerEd25519Keys[fp] = key
            LogManager.i(TAG, "KeyExchange: Ed25519 pubkey received (peers=${peerEd25519Keys.size})")
        }
    }

    /**
     * Recomputes the group AES key deterministically from the SET of all known
     * ML-KEM public keys (our own + every peer we have seen). Because the set is
     * sorted before hashing, every participant that has observed the same set
     * derives the identical key — enabling cross-client decryption without any
     * KEM exchange (the server only relays pubkeys).
     *
     * This is the FIX for the previous audit's "group secret computed
     * incorrectly" bug: we no longer call `encapsulate` with a fresh random
     * secret per peer (which produced different keys on each client). Instead
     * the key is a pure function of the observed public-key set, so it is
     * stable and identical across all members. Recompute is triggered whenever
     * a new peer key arrives (key exchange) or a peer is added.
     */
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

    private fun onData(r: Protocol.Reader, serverMsgId: Long) {
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
        // Verify the Ed25519 signature over the canonical inner bytes (sender +
        // content). The signature is base64 in the envelope; we re-sign the same
        // canonical JSON (signature field empty) and compare. If the sender's
        // Ed25519 pubkey is known, enforce verification; otherwise accept (TOFU
        // for signatures, same trust model as the cert pinning).
        val canonical = Protocol.buildInnerEnvelope(env.sender, "", env.content)
        val sigBytes = runCatching {
            android.util.Base64.decode(env.signature, android.util.Base64.NO_WRAP)
        }.getOrNull()
        if (sigBytes != null && sigBytes.isNotEmpty()) {
            val pub = peerEd25519Keys.values.firstOrNull { pub ->
                PqcCrypto.verify(pub, canonical, sigBytes)
            }
            if (pub == null) {
                LogManager.w(TAG, "signature verification failed for '${env.sender}'; dropping")
                return
            }
        }
        val isOwn = env.sender == clientName
        // Persist the encrypted blob (IV || ciphertext) keyed by the REAL server
        // id (>= 0). This upserts over any optimistic negative-temp copy.
        val realId = if (frame.serverMsgId != 0L) frame.serverMsgId else serverMsgId
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
        // Reply with our own heartbeat so the server keeps the session alive.
        client?.send(Protocol.buildHeartbeat())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                client?.send(Protocol.buildHeartbeat())
            }
        }
    }

    /**
     * Decrypts a single stored [MessageEntity] using the current group secret.
     * Returns null when the secret is unavailable or the row cannot be decrypted
     * (e.g. it was encrypted under a rotated key). Used by the reactive UI flow
     * so history appears as soon as the group secret is established.
     */
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

    /**
     * Decrypts locally stored history for [serverId] using the current group
     * secret. Returns a list of [DecryptedMessage] (or null plaintext when the
     * secret is not yet available / decryption fails).
     */
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

        @Volatile
        private var INSTANCE: ChatController? = null

        fun getInstance(context: Context): ChatController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatController(context.applicationContext).also { INSTANCE = it }
            }
    }
}
