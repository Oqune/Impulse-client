package com.example.impulse.transport

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.WebTransport
import android.net.http.WebTransportBidirectionalStream
import android.net.http.WebTransportCallback
import android.net.http.WebTransportServerCertificateHashes
import android.net.http.WebTransportSession
import com.example.impulse.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebTransport client (Android `android.net.http.WebTransport`, API 33+).
 *
 * Responsibilities:
 *  - Establish a WebTransport session to the server over HTTPS/QUIC.
 *  - Pin the server certificate hash(es) via [WebTransportServerCertificateHashes]
 *    (TOFU hashes supplied by [com.example.impulse.security.TrustedCertManager]).
 *  - Open a single bidirectional stream used as the control + chat channel.
 *  - Reconnect with exponential backoff on network loss.
 *  - Expose connection state and an inbound binary-frame callback.
 *
 * The class is intentionally transport-only: encryption, key exchange and
 * persistence are handled by the layers above it (see [com.example.impulse.ChatController]).
 *
 * Framing: every binary frame is prefixed with a 4-byte big-endian length so
 * the read loop can delimit arbitrary binary payloads (the protocol no longer
 * uses newline-delimited JSON).
 */
class WebTransportClient(
    private val context: Context,
    private val certHashes: List<String>, // hex-encoded SHA-256 cert hashes
    private val onFrame: (ByteArray) -> Unit,
    private val onState: (ConnectionState) -> Unit,
    private val onCertHashPush: (String) -> Unit,
    /** DEV ONLY: when true, certificate pinning is skipped so a self-signed /
     *  mismatched server cert is accepted. NEVER enable in production builds. */
    private val skipCertPinning: Boolean = false
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught transport coroutine exception", e)
        }
    )
    private var engine: HttpEngine? = null
    private var session: WebTransportSession? = null
    private var stream: WebTransportBidirectionalStream? = null

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val intentionalClose = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var currentUrl: String = ""
    // Watchdog that fires if the session never becomes ready (e.g. server
    // unreachable, QUIC blocked, or cert-pin rejection with no callback).
    private var connectTimeoutJob: Job? = null

    fun connect(url: String) {
        intentionalClose.set(false)
        currentUrl = url
        startConnectTimeout()
        startSession()
    }

    fun disconnect() {
        intentionalClose.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        closeStreamAndSession()
        setState(ConnectionState.DISCONNECTED)
    }

    /**
     * Starts a watchdog: if [ConnectionState.CONNECTED] is not reached within
     * [CONNECT_TIMEOUT_MS], the connection is considered failed (server
     * unreachable, QUIC blocked, or cert-pin rejected without a callback) and we
     * transition to ERROR and schedule a reconnect. Cancelled on success/close.
     */
    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (intentionalClose.get()) return@launch
            if (_state.value == ConnectionState.CONNECTING) {
                LogManager.e(TAG, "connect timeout after ${CONNECT_TIMEOUT_MS}ms; marking ERROR")
                setState(ConnectionState.ERROR)
                scheduleReconnect()
            }
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    /** Sends a raw binary frame (length-prefixed) over the bidirectional stream. */
    fun send(frame: ByteArray): Boolean {
        // Never send before the transport is ready or after it has closed — this
        // guards against IllegalStateException ("write after close") and against
        // writing onto a stream that is CONNECTED but not yet open.
        if (intentionalClose.get()) return false
        val st = stream ?: run {
            LogManager.w(TAG, "send ignored: stream not open (state=${_state.value})")
            return false
        }
        if (_state.value != ConnectionState.CONNECTED &&
            _state.value != ConnectionState.AUTHENTICATED
        ) {
            LogManager.w(TAG, "send ignored: not ready (state=${_state.value})")
            return false
        }
        return try {
            // 4-byte big-endian length prefix + payload.
            val out = ByteBuffer.allocateDirect(4 + frame.size)
            out.putInt(frame.size)
            out.put(frame)
            out.flip()
            st.write(out)
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "send failed", e)
            false
        }
    }

    // ------------------------------------------------------------------

    private fun startSession() {
        setState(ConnectionState.CONNECTING)
        LogManager.i(TAG, "connecting to $currentUrl (pinning=${!skipCertPinning}, hashes=${certHashes.size})")
        scope.launch {
            try {
                ensureEngine()
                val builder = WebTransport.Builder(currentUrl)
                if (skipCertPinning) {
                    LogManager.w(TAG, "DEV MODE: certificate pinning DISABLED — any server cert accepted")
                } else if (certHashes.isNotEmpty()) {
                    val hashesBuilder = WebTransportServerCertificateHashes.Builder()
                    certHashes.forEach { hashesBuilder.addSha256Hash(hexToBytes(it)) }
                    builder.setServerCertificateHashes(hashesBuilder.build())
                }
                val wt = builder.build()
                wt.createSession(object : WebTransportCallback() {
                    override fun onSessionReady(session: WebTransportSession) {
                        cancelConnectTimeout()
                        LogManager.i(TAG, "session ready")
                        this@WebTransportClient.session = session
                        setState(ConnectionState.CONNECTED)
                        openStream(session)
                    }

                    override fun onSessionError(error: Int) {
                        LogManager.e(TAG, "session error: $error")
                        cancelConnectTimeout()
                        setState(ConnectionState.ERROR)
                        scheduleReconnect()
                    }

                    override fun onSessionClosed(info: Int) {
                        LogManager.w(TAG, "session closed: $info")
                        cancelConnectTimeout()
                        if (!intentionalClose.get()) {
                            setState(ConnectionState.ERROR)
                            scheduleReconnect()
                        }
                    }
                })
            } catch (e: Exception) {
                LogManager.e(TAG, "connect failed: ${e.message}", e)
                setState(ConnectionState.ERROR)
                scheduleReconnect()
            }
        }
    }

    private fun openStream(sess: WebTransportSession) {
        sess.createBidirectionalStream(object : WebTransportBidirectionalStream.Callback() {
            override fun onStreamReady(stream: WebTransportBidirectionalStream) {
                this@WebTransportClient.stream = stream
                // The transport is now CONNECTED. AUTHENTICATED is only set once the
                // upper layer (ChatController) has completed auth + key exchange and
                // derived the group secret, so we do NOT mark AUTHENTICATED here.
                LogManager.i(TAG, "stream ready")
                setState(ConnectionState.CONNECTED)
                scope.launch { readLoop(stream) }
            }

            override fun onStreamFailed(errorCode: Int) {
                LogManager.e(TAG, "stream failed: $errorCode")
                setState(ConnectionState.ERROR)
                scheduleReconnect()
            }
        })
    }

    private suspend fun readLoop(st: WebTransportBidirectionalStream) {
        // Accumulate bytes; parse 4-byte length prefix then the payload.
        val acc = java.io.ByteArrayOutputStream()
        try {
            while (scope.isActive) {
                val buf = ByteBuffer.allocateDirect(16 * 1024)
                val n = st.read(buf)
                if (n <= 0) break
                buf.flip()
                val chunk = ByteArray(n)
                buf.get(chunk)
                acc.write(chunk)
                // Drain as many complete frames as we have buffered.
                while (true) {
                    val bytes = acc.toByteArray()
                    if (bytes.size < 4) break
                    val len = ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                    if (bytes.size < 4 + len) break
                    val frame = bytes.copyOfRange(4, 4 + len)
                    acc.reset()
                    acc.write(bytes, 4 + len, bytes.size - (4 + len))
                    dispatch(frame)
                }
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "read loop ended: ${e.message}")
        }
    }

    private fun dispatch(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
        // Route cert-hash pushes to the TOFU manager before handing to caller.
        if (opcode == Protocol.OP_NEW_CERT_HASH) {
            try {
                val r = Protocol.Reader(raw, 1)
                val frame = Protocol.parseNewCertHash(r)
                LogManager.i(TAG, "cert hash push received (new=${LogManager.shortHash(frame.hash)})")
                onCertHashPush(frame.hash)
            } catch (e: Exception) {
                LogManager.w(TAG, "cert hash parse failed", e)
            }
        }
        onFrame(raw)
    }

    private fun scheduleReconnect() {
        if (intentionalClose.get()) return
        reconnectJob?.cancel()
        val delayMs = nextReconnectDelay()
        reconnectAttempts++
        LogManager.w(TAG, "scheduling reconnect #$reconnectAttempts in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!intentionalClose.get()) startSession()
        }
    }

    private fun nextReconnectDelay(): Long {
        val exp = (2_000L * Math.pow(2.0, reconnectAttempts.toDouble())).toLong()
        val capped = exp.coerceAtMost(60_000L)
        return (capped + (capped * 0.3 * Math.random())).toLong()
    }

    private fun ensureEngine() {
        if (engine == null) {
            engine = HttpEngine.Builder(context).build()
        }
    }

    private fun closeStreamAndSession() {
        try { stream?.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        stream = null
        session = null
    }

    private fun setState(s: ConnectionState) {
        _state.value = s
        onState(s)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val TAG = "WebTransportClient"
        /** Max time to wait for the WebTransport session to become ready. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
