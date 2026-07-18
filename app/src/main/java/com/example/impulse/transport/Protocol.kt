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
 *  - u16  : 2 bytes (length prefixes)
 *  - u32  : 4 bytes
 *  - u64  : 8 bytes (ids / timestamps)
 *  - bytes: u16 length prefix followed by the raw bytes
 *
 * Opcodes:
 *  0x01 OP_AUTH         -> password (utf8 bytes)
 *  0x02 OP_AUTH_RESULT  <- success(u8) [error message bytes if !success]
 *  0x03 OP_SYNC         -> last_seen_id (u64)
 *  0x04 OP_SYNC_RESPONSE<- count(u32) { id(u64), timestamp(u64), len(u16), payload(bytes) }
 *  0x05 OP_DATA         -> len(u16), payload(bytes)            (both directions)
 *  0x06 OP_HEARTBEAT    -> (no body)                           (both directions)
 *  0x07 OP_NEW_CERT_HASH<- hash_len(u16), hash(bytes), expiry(u64)
 *  0x08 OP_KEY_EXCHANGE -> key_len(u16), public_key(bytes)     (both directions)
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
        fun u16(v: Int) {
            buf.write((v ushr 0) and 0xFF)
            buf.write((v ushr 8) and 0xFF)
        }
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
            u16(data.size)
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
        fun u16(): Int {
            val lo = u8()
            val hi = u8()
            return (hi shl 8) or lo
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
            val len = u16()
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
    }

    // ======================================================================
    // Client -> Server frame builders
    // ======================================================================

    /** Auth: opcode + password (UTF-8). */
    fun buildAuth(password: String): ByteArray {
        val w = Writer()
        w.u8(OP_AUTH.toInt())
        w.utf8(password)
        return w.toByteArray()
    }

    /** Sync: opcode + last_seen_id (u64). */
    fun buildSync(lastSeenId: Long): ByteArray {
        val w = Writer()
        w.u8(OP_SYNC.toInt())
        w.u64(lastSeenId)
        return w.toByteArray()
    }

    /**
     * Data: opcode + len(u16) + payload(bytes).
     * [payload] is the already AES-256-GCM-encrypted blob (IV || ciphertext).
     */
    fun buildData(payload: ByteArray): ByteArray {
        val w = Writer()
        w.u8(OP_DATA.toInt())
        w.bytes(payload)
        return w.toByteArray()
    }

    /** Heartbeat: opcode only. */
    fun buildHeartbeat(): ByteArray = byteArrayOf(OP_HEARTBEAT)

    /**
     * KeyExchange: opcode + key_len(u16) + public_key(bytes).
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

    /** SyncResponse: opcode + count(u32) { id(u64), timestamp(u64), len(u16), payload(bytes) }. */
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
            // Each iteration must have at least 16 bytes (2×u64) + 2 bytes (len).
            if (r.remaining() < 18) {
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
     *   server_msg_id(u64) | timestamp(u64) | len(u16) | payload(bytes)
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

    /** Parses a NewCertHash frame (opcode already consumed by caller). */
    fun parseNewCertHash(r: Reader): NewCertHashFrame {
        val hash = r.utf8()
        val expiry = if (r.remaining() >= 8) r.u64() else 0L
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
