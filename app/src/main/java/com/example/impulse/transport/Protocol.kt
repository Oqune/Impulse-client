package com.example.impulse.transport

/**
 * Binary wire protocol exchanged over the WebTransport bidirectional stream.
 *
 * Every frame starts with a single opcode byte. The server and client share the
 * same opcode table, so the server never sees any plaintext metadata: the
 * sender, message type, signatures and content are all encrypted inside the
 * [OP_DATA] payload (which itself is AES-256-GCM encrypted with the group key).
 *
 * Field encoding (little-endian):
 *  - u8   : 1 byte
 *  - u32  : 4 bytes (length prefixes)
 *  - u64  : 8 bytes (ids / timestamps)
 *  - bytes: u32 length prefix followed by the raw bytes
 *
 * Opcodes:
 *  0x01 OP_AUTH         -> SHA-256(password) as lowercase hex (utf8)
 *  0x02 OP_AUTH_RESULT  <- success(u8) [error message bytes if !success]
 *  0x03 OP_SYNC         -> last_seen_id (u64)
 *  0x04 OP_SYNC_RESPONSE<- count(u32) { id(u64), timestamp(u64), len(u32), payload(bytes) }
 *  0x05 OP_DATA         -> len(u32), payload(bytes)            (both directions)
 *  0x06 OP_HEARTBEAT    -> (no body)                           (both directions)
 *  0x07 OP_NEW_CERT_HASH<- 32 raw SHA-256 bytes, expiry(u64)
 *  0x08 OP_KEY_EXCHANGE -> key_len(u32), public_key(bytes)     (both directions)
 */
object Protocol {

    /**
     * Thrown when an inbound binary frame is malformed (truncated, wrong length
     * prefix, or exceeds a sane size bound). Callers (the transport read loop
     * and [com.example.impulse.ChatController.handleIncoming]) catch this and
     * drop the offending frame instead of crashing the app.
     */
    class ProtocolException(message: String) : Exception(message)

    /**
     * Upper bound on any single binary payload (1 MiB). A length prefix larger
     * than this is treated as a malformed/attack frame and rejected before any
     * allocation, preventing an OutOfMemoryError from a hostile or corrupt
     * server/relay.
     */
    const val MAX_PAYLOAD_BYTES = 1 * 1024 * 1024

    // ---- Opcodes ----------------------------------------------------------
    const val OP_AUTH: Byte = 0x01
    const val OP_AUTH_RESULT: Byte = 0x02
    const val OP_SYNC: Byte = 0x03
    const val OP_SYNC_RESPONSE: Byte = 0x04
    const val OP_DATA: Byte = 0x05
    const val OP_HEARTBEAT: Byte = 0x06
    const val OP_NEW_CERT_HASH: Byte = 0x07
    const val OP_KEY_EXCHANGE: Byte = 0x08

    // ======================================================================
    // Binary writer / reader helpers
    // ======================================================================

    class Writer(initial: Int = 64) {
        private val buf = java.io.ByteArrayOutputStream(initial)
        fun u8(v: Int) = buf.write(v and 0xFF)
        fun u32(v: Long) {
            val x = v and 0xFFFFFFFFL
            buf.write((x ushr 0).toInt() and 0xFF)
            buf.write((x ushr 8).toInt() and 0xFF)
            buf.write((x ushr 16).toInt() and 0xFF)
            buf.write((x ushr 24).toInt() and 0xFF)
        }
        fun u64(v: Long) {
            var x = v
            for (i in 0..7) {
                buf.write((x and 0xFF).toInt())
                x = x ushr 8
            }
        }
        fun bytes(data: ByteArray) {
            u32(data.size.toLong())
            buf.write(data)
        }
        fun utf8(s: String) = bytes(s.toByteArray(Charsets.UTF_8))
        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    class Reader(private val data: ByteArray, private var pos: Int = 0) {
        fun remaining() = data.size - pos
        fun u8(): Int {
            require(pos < data.size) { "u8: out of bounds" }
            return data[pos++].toInt() and 0xFF
        }
        fun u32(): Long {
            val a = u8()
            val b = u8()
            val c = u8()
            val d = u8()
            return (a.toLong() shl 0) or (b.toLong() shl 8) or
                (c.toLong() shl 16) or (d.toLong() shl 24)
        }
        fun u64(): Long {
            var x = 0L
            for (i in 0..7) x = x or (u8().toLong() shl (8 * i))
            return x
        }
        fun bytes(): ByteArray {
            val len = u32().toInt()
            if (len > MAX_PAYLOAD_BYTES) {
                throw ProtocolException("bytes: length $len exceeds MAX_PAYLOAD_BYTES $MAX_PAYLOAD_BYTES")
            }
            if (pos + len > data.size) {
                throw ProtocolException("bytes: length $len exceeds remaining ${remaining()}")
            }
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun utf8(): String = String(bytes(), Charsets.UTF_8)
        fun readBytes(n: Int): ByteArray {
            if (pos + n > data.size) throw ProtocolException("readBytes: $n exceeds remaining ${remaining()}")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    // ======================================================================
    // Client -> Server frame builders
    // ======================================================================

    /**
     * Auth: opcode + SHA-256(password) as lowercase hex.
     *
     * The server stores/compares the SHA-256 of the password (the same value
     * produced by `printf 'pw' | sha256sum` on the server side), NOT the raw
     * password. Sending the raw password previously caused every auth to be
     * rejected ("Wrong password hash"). We hash client-side so the plaintext
     * password is never placed on the wire.
     */
    fun buildAuth(password: String): ByteArray {
        val w = Writer()
        w.u8(OP_AUTH.toInt())
        w.utf8(sha256Hex(password))
        return w.toByteArray()
    }

    /**
     * Lowercase hex SHA-256 of a UTF-8 string (no salt). Matches the server's
     * `sha256sum` of the password. Used for the Auth (0x01) frame.
     */
    fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Sync: opcode + last_seen_id (u64). */
    fun buildSync(lastSeenId: Long): ByteArray {
        val w = Writer()
        w.u8(OP_SYNC.toInt())
        w.u64(lastSeenId)
        return w.toByteArray()
    }

    /**
     * Data: opcode + len(u32) + payload(bytes).
     * [payload] is the already AES-256-GCM-encrypted blob (IV || ciphertext).
     */
    fun buildData(payload: ByteArray): ByteArray {
        val w = Writer()
        w.u8(OP_DATA.toInt())
        w.bytes(payload)
        return w.toByteArray()
    }

    /** Heartbeat: opcode + client_timestamp(u64). */
    fun buildHeartbeat(): ByteArray {
        val w = Writer()
        w.u8(OP_HEARTBEAT.toInt())
        w.u64(System.currentTimeMillis())
        return w.toByteArray()
    }

    /**
     * KeyExchange: opcode + key_len(u32) + public_key(bytes).
     * [publicKey] is the X509-encoded ML-KEM public key.
     */
    fun buildKeyExchange(publicKey: ByteArray): ByteArray {
        val w = Writer()
        w.u8(OP_KEY_EXCHANGE.toInt())
        w.bytes(publicKey)
        return w.toByteArray()
    }

    /** AuthResult: opcode + success(u8) [error_message bytes if !success]. */
    fun buildAuthResult(success: Boolean, errorMessage: String? = null): ByteArray {
        val w = Writer()
        w.u8(OP_AUTH_RESULT.toInt())
        w.u8(if (success) 1 else 0)
        if (!success && errorMessage != null) w.utf8(errorMessage)
        return w.toByteArray()
    }

    /** SyncResponse: opcode + count(u32) { id(u64), timestamp(u64), len(u32), payload(bytes) }. */
    fun buildSyncResponse(messages: List<SyncMessage>): ByteArray {
        val w = Writer()
        w.u8(OP_SYNC_RESPONSE.toInt())
        w.u32(messages.size.toLong())
        for (m in messages) {
            w.u64(m.id)
            w.u64(m.timestamp)
            w.bytes(m.payload)
        }
        return w.toByteArray()
    }

    // ======================================================================
    // Server -> Client frame parsers
    // ======================================================================

    data class AuthResultFrame(val success: Boolean, val errorMessage: String?)

    /** Parses an AuthResult frame (opcode already consumed by caller). */
    fun parseAuthResult(r: Reader): AuthResultFrame {
        val ok = r.u8() != 0
        val msg = if (r.remaining() > 0) r.utf8() else null
        return AuthResultFrame(ok, msg)
    }

    /**
     * Parses the Auth (0x01) frame body (opcode already consumed by caller) and
     * returns the SHA-256 hex the client sent. Used by tests/diagnostics to
     * verify the wire format; the plaintext password is never recoverable from
     * this value.
     */
    fun parseAuthPassword(r: Reader): String = r.utf8()

    data class SyncMessage(
        val id: Long,
        val timestamp: Long,
        val payload: ByteArray
    )

    data class SyncResponseFrame(
        val messages: List<SyncMessage>
    )

    /** Parses a SyncResponse frame (opcode already consumed by caller). */
    fun parseSyncResponse(r: Reader): SyncResponseFrame {
        val count = r.u32().toInt()
        // Guard against a hostile/garbled count that would allocate a huge list
        // or loop forever reading past the buffer end.
        if (count < 0 || count > 10_000) {
            throw ProtocolException("SyncResponse count out of range: $count")
        }
        val list = ArrayList<SyncMessage>(count)
        repeat(count) {
            // Each iteration must have at least 16 bytes (2×u64) + 4 bytes (len).
            if (r.remaining() < 20) {
                throw ProtocolException("SyncResponse truncated at message $it/$count")
            }
            val id = r.u64()
            val ts = r.u64()
            val payload = r.bytes()
            list.add(SyncMessage(id, ts, payload))
        }
        return SyncResponseFrame(list)
    }

    data class DataFrame(
        /** Server-assigned monotonic id (relay adds this; 0 if absent). */
        val serverMsgId: Long,
        /** Server timestamp (epoch millis) attached by the relay. */
        val timestamp: Long,
        /** The AES-256-GCM encrypted inner envelope (IV || ciphertext). */
        val payload: ByteArray
    )

    /**
     * Parses a Data frame (opcode already consumed by caller).
     *
     * Wire layout (server→client relay, per the binary spec):
     *   server_msg_id(u64) | timestamp(u64) | len(u32) | payload(bytes)
     *
     * The client MUST use [serverMsgId] for deduplication/storage so that the
     * optimistic local copy (negative temp id) is replaced by the authoritative
     * row once the server echoes the message.
     */
    fun parseData(r: Reader): DataFrame {
        val serverMsgId = r.u64()
        val timestamp = r.u64()
        val payload = r.bytes()
        return DataFrame(serverMsgId, timestamp, payload)
    }

    data class NewCertHashFrame(
        val hash: String,
        val expiry: Long
    )

    /** Parses a NewCertHash frame (opcode already consumed by caller).
     *  Wire format: [32 raw SHA-256 bytes] [u64 expiry]. */
    fun parseNewCertHash(r: Reader): NewCertHashFrame {
        if (r.remaining() < 40) throw ProtocolException("NewCertHash: expected at least 40 bytes, got ${r.remaining()}")
        val raw = r.readBytes(32)
        val hash = raw.joinToString("") { "%02x".format(it) }
        val expiry = r.u64()
        return NewCertHashFrame(hash, expiry)
    }

    data class KeyExchangeFrame(
        val publicKey: ByteArray
    )

    /** Parses a KeyExchange frame (opcode already consumed by caller). */
    fun parseKeyExchange(r: Reader): KeyExchangeFrame = KeyExchangeFrame(r.bytes())

    // ======================================================================
    // Inner encrypted payload (the JSON that lives inside OP_DATA)
    // ======================================================================

    /**
     * Builds the inner JSON envelope that is encrypted and placed inside the
     * [OP_DATA] payload. The server only ever sees the ciphertext, so all
     * metadata (sender, signature, content) is hidden from it.
     *
     * Implemented with a tiny hand-rolled JSON encoder (no [org.json] dependency)
     * so the same code path is exercised by JVM unit tests without Robolectric.
     */
    fun buildInnerEnvelope(sender: String, signature: String, content: String): ByteArray {
        val json = """{"sender":${jsonStr(sender)},"signature":${jsonStr(signature)},"content":${jsonStr(content)}}"""
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Minimal JSON string literal encoder: escapes the control characters,
     * double-quote and backslash required by RFC 8259. The three envelope
     * fields are all free-form text, so escaping is mandatory to keep the
     * envelope well-formed.
     */
    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                in ' '..'~' -> sb.append(c) // printable ASCII
                else -> {
                    // Emit \uXXXX for non-printable / non-ASCII to stay strict.
                    if (c < ' ') sb.append("\\u%04x".format(c.code))
                    else sb.append(c) // UTF-8 bytes are preserved as-is
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Returns the total byte length of the complete frame starting at [offset]
     *  in [data], or throws [ProtocolException] if incomplete or unknown opcode.
     *  Mirrors the server's try_read_packet logic. */
    fun frameLength(data: ByteArray, offset: Int = 0): Int {
        if (offset >= data.size) throw ProtocolException("frameLength: empty")
        val opcode = data[offset]
        return when (opcode) {
            OP_AUTH, OP_DATA, OP_KEY_EXCHANGE -> {
                if (data.size - offset < 5) throw ProtocolException("frameLength: incomplete $opcode")
                val payloadLen = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES * 2) throw ProtocolException("frameLength: $opcode len=$payloadLen out of range")
                1 + 4 + payloadLen
            }
            OP_SYNC -> 1 + 8
            OP_HEARTBEAT -> 1 + 8
            OP_NEW_CERT_HASH -> 1 + 32 + 8
            OP_AUTH_RESULT -> {
                if (data.size - offset < 2) throw ProtocolException("frameLength: incomplete OP_AUTH_RESULT")
                val success = data[offset + 1]
                // Server encodes: success=0x01 → 2 bytes total; fail=0x00 → has error message
                if (success != 0.toByte()) 2
                else {
                    if (data.size - offset < 6) throw ProtocolException("frameLength: incomplete OP_AUTH_RESULT")
                    val msgLen = ((data[offset + 2].toInt() and 0xFF)) or
                        ((data[offset + 3].toInt() and 0xFF) shl 8) or
                        ((data[offset + 4].toInt() and 0xFF) shl 16) or
                        ((data[offset + 5].toInt() and 0xFF) shl 24)
                    2 + 4 + msgLen
                }
            }
            OP_SYNC_RESPONSE -> {
                if (data.size - offset < 5) throw ProtocolException("frameLength: incomplete OP_SYNC_RESPONSE")
                val count = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                var pos = offset + 5
                repeat(count.toInt()) {
                    if (data.size - pos < 20) throw ProtocolException("frameLength: incomplete OP_SYNC_RESPONSE message")
                    val payloadLen = ((data[pos + 16].toInt() and 0xFF)) or
                        ((data[pos + 17].toInt() and 0xFF) shl 8) or
                        ((data[pos + 18].toInt() and 0xFF) shl 16) or
                        ((data[pos + 19].toInt() and 0xFF) shl 24)
                    pos += 20 + payloadLen
                }
                pos - offset
            }
            else -> throw ProtocolException("frameLength: unknown opcode $opcode")
        }
    }

    data class InnerEnvelope(
        val sender: String,
        val signature: String,
        val content: String
    )

    /**
     * Parses the inner envelope. Uses a tolerant, allocation-free scanner that
     * extracts the three known string fields by name. Returns null if the
     * envelope cannot be parsed (callers drop the frame rather than crash).
     */
    fun parseInnerEnvelope(bytes: ByteArray): InnerEnvelope? {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            InnerEnvelope(
                sender = optJsonField(text, "sender") ?: "Unknown",
                signature = optJsonField(text, "signature") ?: "",
                content = optJsonField(text, "content") ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the value of `"name":"..."` from a JSON object. Handles the
     * escape sequences produced by [jsonStr]. Returns null if the field is
     * absent or malformed.
     */
    private fun optJsonField(json: String, name: String): String? {
        val key = "\"$name\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        var p = idx + key.length
        while (p < json.length && (json[p] == ':' || json[p] == ' ' || json[p] == '\t')) p++
        if (p >= json.length || json[p] != '"') return null
        p++ // skip opening quote
        val sb = StringBuilder()
        while (p < json.length) {
            val c = json[p]
            if (c == '"') return sb.toString()
            if (c == '\\' && p + 1 < json.length) {
                p++
                when (json[p]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = json.substring(p + 1, minOf(p + 5, json.length))
                        p += hex.length
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                    }
                    else -> sb.append(json[p])
                }
            } else {
                sb.append(c)
            }
            p++
        }
        return null
    }
}
