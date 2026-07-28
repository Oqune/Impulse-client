package com.example.impulse.transport

import android.content.Context
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.http3.WebTransportException
import com.ditchoom.socket.http3.WebTransportSession
import com.ditchoom.socket.http3.WebTransportOptions
import com.ditchoom.socket.http3.WebTransportStream
import com.ditchoom.socket.http3.withHttp3Connection
import com.ditchoom.socket.quic.CertificateHash
import com.ditchoom.socket.quic.QuicOptions
import com.example.impulse.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.net.URI
import com.ditchoom.buffer.Default
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import android.os.Build

class WebTransportClient(
    private val context: Context,
    private val serverCertHashes: List<String>,
    private val onFrame: (ByteArray) -> Unit,
    private val onState: (ConnectionState) -> Unit,
    private val onCertHashPush: (String) -> Unit,
    private val onSessionError: ((Int) -> Unit)? = null,
    private val onReady: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            LogManager.e(TAG, "uncaught transport coroutine exception", e)
        }
    )

    private val lock = Any()
    private var session: WebTransportSession? = null
    private var stream: WebTransportStream? = null
    private var connectionJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var currentHost = ""
    private var currentPort = 4433
    private var currentPath = "/"

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val intentionalClose = AtomicBoolean(false)

    fun connect(url: String) {
        intentionalClose.set(false)
        val uri = URI(url)
        synchronized(lock) {
            currentHost = uri.host
            if (uri.port > 0) currentPort = uri.port
            currentPath = uri.path.ifEmpty { "/" }
        }
        LogManager.i(TAG, "connect() url=$url host=$currentHost port=$currentPort path=$currentPath")
        startConnectTimeout()
        startSession()
    }

    fun disconnect() {
        LogManager.i(TAG, "disconnect() called — closing session/stream")
        intentionalClose.set(true)
        synchronized(lock) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            connectionJob?.cancel()
            connectionJob = null
        }
        scope.launch { closeSessionAndStream() }
        setState(ConnectionState.DISCONNECTED)
    }

    /** Release all resources including the coroutine scope. */
    fun destroy() {
        intentionalClose.set(true)
        val st = synchronized(lock) { stream }
        val se = synchronized(lock) { session }
        // close() is suspend — use runBlocking with timeout to avoid hanging
        runBlocking {
            try { withTimeout(1000) { st?.close() } } catch (_: Exception) { }
            try { withTimeout(1000) { se?.close() } } catch (_: Exception) { }
        }
        synchronized(lock) {
            session = null
            stream = null
        }
        scope.cancel()
    }

    suspend fun send(frame: ByteArray): Boolean {
        if (intentionalClose.get()) return false
        val st = synchronized(lock) { stream } ?: run {
            LogManager.w(TAG, "SEND BLOCKED: stream is null (state=${_state.value})")
            return false
        }
        if (_state.value != ConnectionState.CONNECTED &&
            _state.value != ConnectionState.AUTHENTICATING &&
            _state.value != ConnectionState.AUTHENTICATED &&
            _state.value != ConnectionState.READY
        ) {
            LogManager.w(TAG, "SEND BLOCKED: wrong state=${_state.value}")
            return false
        }
        return try {
            val buf = BufferFactory.Default.allocate(frame.size)
            try {
                for (b in frame) buf.writeByte(b)
                buf.resetForRead()
                LogManager.d(TAG, "SEND ${frame.size} bytes (opcode=0x%02x) to stream".format(frame[0].toInt()))
                st.write(buf)
                LogManager.d(TAG, "SEND OK")
            } finally {
                buf.freeIfNeeded()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e(TAG, "SEND FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private fun startConnectTimeout() {
        val job = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (intentionalClose.get()) return@launch
            if (_state.value == ConnectionState.CONNECTING) {
                LogManager.e(TAG, "CONNECT TIMEOUT after ${CONNECT_TIMEOUT_MS}ms — no response from server, state→ERROR")
                setState(ConnectionState.ERROR)
            }
        }
        synchronized(lock) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = job
        }
    }

    private fun cancelConnectTimeout() {
        synchronized(lock) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
        }
    }

    private fun startSession() {
        setState(ConnectionState.CONNECTING)
        val startTime = System.currentTimeMillis()

        if (isEmulator()) {
            LogManager.e(TAG, "ABORT: WebTransport requires a physical device (QUIC native libs unavailable on x86 emulators)")
            cancelConnectTimeout()
            setState(ConnectionState.ERROR)
            return
        }

        LogManager.i(TAG, "connecting to $currentHost:$currentPort${currentPath} " +
            "(pinnedHashes=${serverCertHashes.size})")
        val job = scope.launch {
            try {
                val pinned = serverCertHashes.mapNotNull { hex -> certHashFromHex(hex) }
                if (pinned.isEmpty()) {
                    LogManager.e(TAG, "ABORT: no valid pinned cert hashes")
                    cancelConnectTimeout()
                    setState(ConnectionState.ERROR)
                    return@launch
                }
                LogManager.i(TAG, "QUIC options: idleTimeout=120s keepAlive=15s handshakeTimeout=300s")
                val quicOptions = QuicOptions(
                    alpnProtocols = listOf("h3"),
                    serverCertificateHashes = pinned,
                    idleTimeout = 120.seconds,
                    keepAliveInterval = 15.seconds,
                )
                withHttp3Connection(
                    hostname = currentHost,
                    port = currentPort,
                    quicOptions = quicOptions,
                    timeout = 300.seconds,
                    webTransport = WebTransportOptions(maxSessions = 1),
                ) {
                    LogManager.i(TAG, "HTTP/3 connection established, opening WebTransport session...")
                    val wtSession = connectWebTransport(
                        authority = currentHost,
                        path = currentPath,
                    )
                    LogManager.i(TAG, "WebTransport session opened, opening bidirectional stream...")
                    val wtStream = wtSession.openBidiStream()
                    synchronized(lock) {
                        session = wtSession
                        stream = wtStream
                    }
                    cancelConnectTimeout()
                    val elapsed = System.currentTimeMillis() - startTime
                    LogManager.i(TAG, "SESSION READY (${elapsed}ms) — session + stream open")
                    setState(ConnectionState.CONNECTED)

                    kotlinx.coroutines.yield()
                    LogManager.i(TAG, "invoking onReady callback")
                    try {
                        onReady?.invoke()
                    } catch (e: Exception) {
                        LogManager.e(TAG, "onReady callback failed: ${e.message}", e)
                        closeSessionAndStream()
                        cancelConnectTimeout()
                        setState(ConnectionState.ERROR)
                        return@withHttp3Connection
                    }
                    LogManager.i(TAG, "onReady callback returned, entering read loop")
                    runReadLoop(wtStream)
                    LogManager.i(TAG, "read loop exited normally")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExceptionInInitializerError) {
                if (intentionalClose.get()) return@launch
                LogManager.e(TAG, "QUIC library init failed (missing native libs): ${e.message}", e)
                cancelConnectTimeout()
                setState(ConnectionState.ERROR)
            } catch (e: WebTransportException) {
                if (intentionalClose.get()) return@launch
                LogManager.e(TAG, "WebTransportException: ${e.message}", e)
                onSessionError?.invoke(0)
                cancelConnectTimeout()
                setState(ConnectionState.ERROR)
            } catch (e: Exception) {
                if (intentionalClose.get()) return@launch
                LogManager.e(TAG, "connect failed: ${e.javaClass.simpleName}: ${e.message}", e)
                cancelConnectTimeout()
                setState(ConnectionState.ERROR)
            }
        }
        synchronized(lock) {
            connectionJob?.cancel()
            connectionJob = job
        }
    }

    private suspend fun runReadLoop(stream: WebTransportStream) {
        val acc = ByteArrayOutputStream()
        var bytesRead = 0L
        var framesRead = 0L
        try {
            while (coroutineContext.isActive && !intentionalClose.get()) {
                when (val result = stream.read()) {
                    is ReadResult.Data -> {
                        val buf = result.buffer
                        val n = buf.remaining()
                        if (n > 0) {
                            bytesRead += n
                            val chunk = ByteArray(n)
                            for (i in 0 until n) chunk[i] = buf.readByte()
                            handleInboundChunk(chunk, acc)
                            framesRead++
                        }
                        buf.freeIfNeeded()
                    }
                    ReadResult.End -> {
                        LogManager.w(TAG, "READ EOF — server closed the stream (bytes=$bytesRead frames=$framesRead)")
                        break
                    }
                    ReadResult.Reset -> {
                        LogManager.w(TAG, "READ RESET — peer aborted the stream (bytes=$bytesRead frames=$framesRead)")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!intentionalClose.get()) {
                LogManager.e(TAG, "READ LOOP ERROR: ${e.javaClass.simpleName}: ${e.message} (bytes=$bytesRead frames=$framesRead)", e)
            }
        } finally {
            if (!intentionalClose.get()) {
                LogManager.w(TAG, "CONNECTION LOST — read loop terminated unexpectedly (bytes=$bytesRead frames=$framesRead)")
                setState(ConnectionState.ERROR)
            } else {
                LogManager.i(TAG, "read loop ended (intentional close, bytes=$bytesRead frames=$framesRead)")
                setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun handleInboundChunk(chunk: ByteArray, acc: ByteArrayOutputStream) {
        acc.write(chunk)
        drainFrames(acc)
    }

    private fun drainFrames(acc: ByteArrayOutputStream) {
        val bytes = acc.toByteArray()
        if (bytes.isEmpty()) return
        var pos = 0
        while (pos < bytes.size) {
            val frameLen = try {
                Protocol.frameLength(bytes, pos)
            } catch (e: Exception) {
                val b = bytes[pos].toInt() and 0xFF
                val knownOpcode = b in 0x01..0x0B
                if (knownOpcode) {
                    // Valid opcode but frame is incomplete (large frame in chunks).
                    // Break and wait for more data to arrive.
                    break
                }
                LogManager.w(TAG, "frameLength failed at pos=$pos, byte=0x%02x — skipping".format(b))
                pos++
                continue
            }
            if (pos + frameLen > bytes.size) break
            val frame = bytes.copyOfRange(pos, pos + frameLen)
            pos += frameLen
            try {
                dispatch(frame)
            } catch (e: Exception) {
                LogManager.w(TAG, "dispatch failed for frame: ${e.message}")
            }
        }
        if (pos > 0) {
            val remaining = bytes.size - pos
            System.arraycopy(bytes, pos, bytes, 0, remaining)
            acc.reset()
            acc.write(bytes, 0, remaining)
        }
    }

    private fun dispatch(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
        val opcodeName = when (opcode) {
            Protocol.OP_AUTH -> "Auth"
            Protocol.OP_AUTH_RESULT -> "AuthResult"
            Protocol.OP_SYNC -> "Sync"
            Protocol.OP_SYNC_RESPONSE -> "SyncResponse"
            Protocol.OP_DATA -> "Data"
            Protocol.OP_KEY_EXCHANGE -> "KeyExchange"
            Protocol.OP_HEARTBEAT -> "Heartbeat"
            Protocol.OP_NEW_CERT_HASH -> "NewCertHash"
            else -> "0x%02x".format(opcode)
        }
        LogManager.d(TAG, "RX frame: opcode=$opcodeName (${raw.size} bytes)")
        when (opcode) {
            Protocol.OP_NEW_CERT_HASH -> {
                try {
                    val r = Protocol.Reader(raw, 1)
                    val frame = Protocol.parseNewCertHash(r)
                    LogManager.i(TAG, "cert hash push received (new=${LogManager.shortHash(frame.hash)})")
                    onCertHashPush(frame.hash)
                } catch (e: Exception) {
                    LogManager.w(TAG, "cert hash parse failed", e)
                }
            }
            else -> onFrame(raw)
        }
    }

    private suspend fun closeSessionAndStream() {
        try {
            val st = synchronized(lock) { stream }
            val se = synchronized(lock) { session }
            try { st?.close() } catch (_: Exception) { }
            try { se?.close() } catch (_: Exception) { }
        } catch (_: Exception) { }
        synchronized(lock) {
            stream = null
            session = null
        }
    }

    private fun setState(s: ConnectionState) {
        _state.value = s
        onState(s)
    }

    companion object {
        private const val TAG = "WebTransportClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L

        private fun isEmulator(): Boolean {
            return Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for x86", ignoreCase = true) ||
                Build.FINGERPRINT.startsWith("generic", ignoreCase = true) ||
                Build.FINGERPRINT.startsWith("unknown", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.PRODUCT.contains("emulator", ignoreCase = true) ||
                Build.PRODUCT.contains("simulator", ignoreCase = true)
        }

        /** Convert a 64-char lowercase/uppercase hex SHA-256 fingerprint into a
         *  [CertificateHash] pinning the server leaf cert (DER encoding). */
        private fun certHashFromHex(hex: String): CertificateHash? {
            val clean = hex.trim().lowercase()
            if (!clean.matches(Regex("^[0-9a-f]{64}$"))) {
                LogManager.w(TAG, "ignoring malformed cert hash (len=${clean.length})")
                return null
            }
            val bytes = ByteArray(32) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return CertificateHash(BufferFactory.Default.wrap(bytes))
        }
    }
}
