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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.ByteArrayOutputStream
import java.net.URI
import com.ditchoom.buffer.Default
import java.util.concurrent.atomic.AtomicBoolean

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
        currentHost = uri.host
        if (uri.port > 0) currentPort = uri.port
        currentPath = uri.path.ifEmpty { "/" }
        startConnectTimeout()
        startSession()
    }

    fun disconnect() {
        intentionalClose.set(true)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        connectionJob?.cancel()
        connectionJob = null
        closeSessionAndStream()
        setState(ConnectionState.DISCONNECTED)
    }

    suspend fun send(frame: ByteArray): Boolean {
        if (intentionalClose.get()) return false
        val st = stream ?: run {
            LogManager.w(TAG, "send ignored: stream not open (state=${_state.value})")
            return false
        }
        if (_state.value != ConnectionState.CONNECTED &&
            _state.value != ConnectionState.AUTHENTICATING &&
            _state.value != ConnectionState.AUTHENTICATED
        ) {
            LogManager.w(TAG, "send ignored: not ready (state=${_state.value})")
            return false
        }
        return try {
            val buf = BufferFactory.Default.allocate(frame.size)
            try {
                for (b in frame) buf.writeByte(b)
                buf.resetForRead()
                st.write(buf)
            } finally {
                buf.freeIfNeeded()
            }
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "send failed", e)
            false
        }
    }

    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (intentionalClose.get()) return@launch
            if (_state.value == ConnectionState.CONNECTING) {
                LogManager.e(TAG, "connect timeout after ${CONNECT_TIMEOUT_MS}ms; marking ERROR")
                setState(ConnectionState.ERROR)
            }
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun startSession() {
        setState(ConnectionState.CONNECTING)
        LogManager.i(TAG, "connecting to $currentHost:$currentPort${currentPath} " +
            "(TOFU pinnedHashes=${serverCertHashes.size})")
        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                // W3C WebTransport TOFU: pin the leaf cert by SHA-256 hash of its
                // DER encoding. With the default HashOnly mode the hash match is
                // the sole trust check — exactly what a self-signed short-lived
                // server cert needs.
                val pinned = serverCertHashes.mapNotNull { hex -> certHashFromHex(hex) }
                if (pinned.isEmpty()) {
                    LogManager.e(TAG, "no valid pinned cert hashes; aborting connect")
                    cancelConnectTimeout()
                    setState(ConnectionState.ERROR)
                    return@launch
                }
                val quicOptions = QuicOptions(
                    alpnProtocols = listOf("h3"),
                    serverCertificateHashes = pinned,
                )
                withHttp3Connection(
                    hostname = currentHost,
                    port = currentPort,
                    quicOptions = quicOptions,
                    webTransport = WebTransportOptions(maxSessions = 1),
                ) {
                    val wtSession = connectWebTransport(
                        authority = currentHost,
                        path = currentPath,
                    )
                    val wtStream = wtSession.openBidiStream()
                    session = wtSession
                    stream = wtStream
                    cancelConnectTimeout()
                    LogManager.i(TAG, "session ready")
                    setState(ConnectionState.CONNECTED)
                    LogManager.i(TAG, "stream ready")

                    kotlinx.coroutines.yield()
                    onReady?.invoke()
                    runReadLoop(wtStream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebTransportException) {
                if (intentionalClose.get()) return@launch
                LogManager.e(TAG, "connect failed: ${e.message}", e)
                onSessionError?.invoke(0)
                cancelConnectTimeout()
                setState(ConnectionState.ERROR)
            } catch (e: Exception) {
                if (intentionalClose.get()) return@launch
                LogManager.e(TAG, "connect failed: ${e.message}", e)
                cancelConnectTimeout()
                setState(ConnectionState.ERROR)
            }
        }
    }

    private suspend fun runReadLoop(stream: WebTransportStream) {
        val acc = ByteArrayOutputStream()
        try {
            while (coroutineContext.isActive && !intentionalClose.get()) {
                when (val result = stream.read()) {
                    is ReadResult.Data -> {
                        val buf = result.buffer
                        val n = buf.remaining()
                        if (n > 0) {
                            val chunk = ByteArray(n)
                            for (i in 0 until n) {
                                chunk[i] = buf.readByte()
                            }
                            handleInboundChunk(chunk, acc)
                        }
                        buf.freeIfNeeded()
                    }
                    ReadResult.End -> {
                        LogManager.w(TAG, "read EOF (server closed connection)")
                        break
                    }
                    ReadResult.Reset -> {
                        LogManager.w(TAG, "read reset (peer aborted)")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!intentionalClose.get()) {
                LogManager.e(TAG, "read loop error: ${e.message}", e)
            }
        } finally {
            if (!intentionalClose.get()) {
                LogManager.w(TAG, "connection lost")
                setState(ConnectionState.ERROR)
            } else {
                setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun handleInboundChunk(chunk: ByteArray, acc: ByteArrayOutputStream) {
        acc.write(chunk)
        drainFrames(acc)
    }

    private val readAccumulator = ByteArrayOutputStream()

    private fun drainFrames(acc: ByteArrayOutputStream) {
        val bytes = acc.toByteArray()
        if (bytes.isEmpty()) return
        var pos = 0
        while (pos < bytes.size) {
            val frameLen = try {
                Protocol.frameLength(bytes, pos)
            } catch (_: Exception) {
                break
            }
            if (pos + frameLen > bytes.size) break
            val frame = bytes.copyOfRange(pos, pos + frameLen)
            pos += frameLen
            dispatch(frame)
        }
        if (pos > 0) {
            acc.reset()
            acc.write(bytes, pos, bytes.size - pos)
        }
    }

    private fun dispatch(raw: ByteArray) {
        if (raw.isEmpty()) return
        val opcode = raw[0]
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

    private fun closeSessionAndStream() {
        try {
            val st = stream
            val se = session
            if (st != null || se != null) {
                runBlocking {
                    st?.close()
                    se?.close()
                }
            }
        } catch (_: Exception) { }
        stream = null
        session = null
        readAccumulator.reset()
    }

    private fun setState(s: ConnectionState) {
        _state.value = s
        onState(s)
    }

    companion object {
        private const val TAG = "WebTransportClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L

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
