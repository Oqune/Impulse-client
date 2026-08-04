package com.example.impulse.transport

/**
 * Binary wire protocol exchanged over the WebTransport bidirectional stream.
 *
 * Every frame starts with a single opcode byte. The server and client share the
 * same opcode table, so the server never sees any plaintext metadata: the
 * sender, message type, signatures and content are all encrypted inside the
 * [OP_DATA] payload (which itself is AES-256-GCM encrypted per-recipient).
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
 *  0x06 OP_HEARTBEAT    -> client_timestamp(u64)                (both directions)
* 0x07 OP_NEW_CERT_HASH<- 32 raw SHA-256 bytes, expiry(u64)
 * 0x08 OP_DISCONNECT     — either direction: no payload
 * 0x0B OP_AUTH_CHALLENGE  <- 16-byte random nonce             (server -> client)
 * 0x0C OP_KEY_EXCHANGE_KEM_DSA -> kem_key(bytes), dsa_key(bytes) (both directions)
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
    const val OP_DISCONNECT: Byte = 0x08
    const val OP_AUTH_CHALLENGE: Byte = 0x0B
    const val OP_KEY_EXCHANGE_KEM_DSA: Byte = 0x0C

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
        fun rawBytes(data: ByteArray) = buf.write(data)
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
            if (len < 0 || len > MAX_PAYLOAD_BYTES) {
                throw ProtocolException("bytes: length $len out of range (0..$MAX_PAYLOAD_BYTES)")
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
     * Auth: opcode + raw password bytes + HMAC-SHA-256 challenge response.
     *
     * If [challengeNonce] is provided (16 bytes from server's AuthChallenge),
     * the response is HMAC-SHA-256(key=Argon2id(password)_output, message=nonce)
     * — proving the client received the challenge and preventing replay attacks.
     *
     * Wire format:
     *   [0x01] [len(u32) raw_password_bytes] [len(u32) hmac_response] (if nonce provided)
     */
    fun buildAuth(password: String, challengeNonce: ByteArray? = null, argon2SaltB64: String = ""): ByteArray {
        val w = Writer()
        w.u8(OP_AUTH.toInt())
        w.bytes(password.toByteArray(Charsets.UTF_8))
        if (challengeNonce != null && challengeNonce.size == 16) {
            val key = try {
                argon2DeriveKey(password, argon2SaltB64)
            } catch (e: UnsatisfiedLinkError) {
                throw ProtocolException("Argon2 native library not available: ${e.message}")
            } catch (e: Exception) {
                throw ProtocolException("Argon2 key derivation failed: ${e.message}")
            }
            val response = hmacSha256(key, challengeNonce)
            w.rawBytes(response)
        }
        return w.toByteArray()
    }

    /**
     * Derive a 32-byte key from a password using Argon2id.
     * Parameters MUST match the server's Argon2::default() (m=19456, t=2, p=1).
     */
    internal fun argon2DeriveKey(password: String, saltB64: String = ""): ByteArray {
        val saltBytes = if (saltB64.isNotEmpty()) {
            android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
        } else ByteArray(0)
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
        )
            .withSalt(saltBytes)
            .withParallelism(1)
            .withMemoryAsKB(19456)
            .withIterations(2)
            .withVersion(0x13)
            .build()
        val generator = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        generator.init(params)
        val output = ByteArray(32)
        generator.generateBytes(password.toByteArray(Charsets.UTF_8), output, 0, output.size)
        return output
    }

    /**
     * HMAC-SHA-256: raw bytes key + raw bytes message → 32-byte MAC.
     */
    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretSpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretSpec)
        return mac.doFinal(message)
    }

    /**
     * SHA-256 of raw bytes, returned as a raw 32-byte array.
     */
    private fun sha256Bytes(input: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input)
    }

    /**
     * Lowercase hex SHA-256 of a UTF-8 string (no salt). Matches the server's
     * `sha256sum` of the password. Used for the Auth (0x01) frame.
     */
    fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return com.example.impulse.util.bytesToHex(digest)
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

    /** Disconnect: opcode only, no payload. */
    fun buildDisconnect(): ByteArray {
        val w = Writer()
        w.u8(OP_DISCONNECT.toInt())
        return w.toByteArray()
    }

    fun buildCombinedKeyExchange(kemPub: ByteArray, dsaPub: ByteArray): ByteArray {
        val inner = Writer()
        inner.bytes(kemPub)
        inner.bytes(dsaPub)
        val innerBytes = inner.toByteArray()
        val w = Writer()
        w.u8(OP_KEY_EXCHANGE_KEM_DSA.toInt())
        w.bytes(innerBytes)
        return w.toByteArray()
    }

    data class CombinedKeyExchangeFrame(val kemPublicKey: ByteArray, val dsaPublicKey: ByteArray)

    fun parseCombinedKeyExchange(r: Reader): CombinedKeyExchangeFrame {
        val blob = r.bytes()
        val inner = Reader(blob)
        val kem = inner.bytes()
        val dsa = inner.bytes()
        return CombinedKeyExchangeFrame(kem, dsa)
    }

    data class AuthResultFrame(val success: Boolean, val errorMessage: String?)

    /** Parses an AuthResult frame (opcode already consumed by caller). */
    fun parseAuthResult(r: Reader): AuthResultFrame {
        val ok = r.u8() != 0
        val msg = if (r.remaining() > 0) r.utf8() else null
        return AuthResultFrame(ok, msg)
    }

    data class AuthChallengeFrame(val nonce: ByteArray, val saltB64: String = "")

    /** Parses an AuthChallenge frame (opcode already consumed by caller).
     *  Wire format: 16 raw nonce bytes + optional length-prefixed B64 salt. */
    fun parseAuthChallenge(r: Reader): AuthChallengeFrame {
        if (r.remaining() < 16) throw ProtocolException("AuthChallenge: expected 16 bytes, got ${r.remaining()}")
        val nonce = r.readBytes(16)
        val saltB64 = if (r.remaining() > 0) {
            val saltBytes = r.bytes()
            String(saltBytes, Charsets.UTF_8)
        } else {
            ""
        }
        return AuthChallengeFrame(nonce, saltB64)
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
        val hash = com.example.impulse.util.bytesToHex(raw)
        val expiry = r.u64()
        return NewCertHashFrame(hash, expiry)
    }

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
     * Builds an inner envelope that additionally carries a client-generated
     * timestamp and nonce, both included in the signed canonical form. This
     * binds the ML-DSA-65 signature to a per-message uniqueness token so a
     * malicious relay cannot reorder or replay an old blob with new server
     * ids/timestamps (Bug: "no replay/ordering protection").
     *
     * [dm] is the recipient fingerprint for a private (1:1) message, or an
     * empty string for a group broadcast. It is part of the signed canonical
     * form, so a relay cannot relabel a group message as a DM.
     *
     * Backward compatible: old clients still send the 5-field form, and the
     * verifier accepts it (no ts/nonce/dm) — see [parseInnerEnvelope].
     */
    fun buildSignedInnerEnvelope(sender: String, signature: String, content: String, clientTs: Long, nonce: String, dm: String = ""): ByteArray {
        val json = """{"sender":${jsonStr(sender)},"signature":${jsonStr(signature)},"content":${jsonStr(content)},"ts":$clientTs,"n":${jsonStr(nonce)},"dm":${jsonStr(dm)}}"""
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
                    // Non-printable or non-ASCII characters: emit as-is (UTF-8).
                    if (c < ' ') sb.append("\\u%04x".format(c.code))
                    else sb.append(c) // preserve UTF-8 bytes
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
            OP_AUTH -> {
                // Auth: [0x01] [u32: pwd_len] [pwd_bytes] [32 raw bytes: HMAC-SHA-256]
                if (data.size - offset < 5) throw ProtocolException("frameLength: incomplete $opcode")
                val pwdLen = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                if (pwdLen < 0 || pwdLen > MAX_PAYLOAD_BYTES) throw ProtocolException("frameLength: $opcode pwd_len=$pwdLen out of range")
                1 + 4 + pwdLen + 32
            }
            OP_KEY_EXCHANGE_KEM_DSA -> {
                if (data.size - offset < 5) throw ProtocolException("frameLength: incomplete $opcode")
                val payloadLen = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES * 2) throw ProtocolException("frameLength: $opcode len=$payloadLen out of range")
                1 + 4 + payloadLen
            }
            OP_DATA -> {
                // Server→client Data: opcode(1) + id(u64=8) + timestamp(u64=8) + len(u32=4) + payload
                if (data.size - offset < 21) throw ProtocolException("frameLength: incomplete OP_DATA (need 21, have ${data.size - offset})")
                val payloadLen = ((data[offset + 17].toInt() and 0xFF)) or
                    ((data[offset + 18].toInt() and 0xFF) shl 8) or
                    ((data[offset + 19].toInt() and 0xFF) shl 16) or
                    ((data[offset + 20].toInt() and 0xFF) shl 24)
                if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES * 2) throw ProtocolException("frameLength: OP_DATA len=$payloadLen out of range")
                1 + 8 + 8 + 4 + payloadLen
            }
            OP_SYNC -> 1 + 8
            OP_HEARTBEAT -> 1 + 8
            OP_NEW_CERT_HASH -> 1 + 32 + 8
            OP_DISCONNECT -> 1
            OP_AUTH_CHALLENGE -> {
                // [0x0B] [16 nonce] [u32 salt_len] [salt_bytes]
                if (data.size - offset < 21) throw ProtocolException("frameLength: incomplete OP_AUTH_CHALLENGE (need 21, have ${data.size - offset})")
                val saltLen = ((data[offset + 17].toInt() and 0xFF)) or
                    ((data[offset + 18].toInt() and 0xFF) shl 8) or
                    ((data[offset + 19].toInt() and 0xFF) shl 16) or
                    ((data[offset + 20].toInt() and 0xFF) shl 24)
                if (saltLen < 0 || saltLen > 256) throw ProtocolException("frameLength: OP_AUTH_CHALLENGE salt_len=$saltLen out of range")
                1 + 16 + 4 + saltLen
            }
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
                    if (msgLen < 0 || msgLen > MAX_PAYLOAD_BYTES) throw ProtocolException("frameLength: OP_AUTH_RESULT msgLen=$msgLen out of range")
                    2 + 4 + msgLen
                }
            }
            OP_SYNC_RESPONSE -> {
                if (data.size - offset < 5) throw ProtocolException("frameLength: incomplete OP_SYNC_RESPONSE")
                val count = ((data[offset + 1].toInt() and 0xFF)) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                if (count < 0 || count > 10_000) throw ProtocolException("frameLength: OP_SYNC_RESPONSE count=$count out of range")
                var pos = offset + 5
                repeat(count) {
                    if (data.size - pos < 20) throw ProtocolException("frameLength: incomplete OP_SYNC_RESPONSE message")
                    val payloadLen = ((data[pos + 16].toInt() and 0xFF)) or
                        ((data[pos + 17].toInt() and 0xFF) shl 8) or
                        ((data[pos + 18].toInt() and 0xFF) shl 16) or
                        ((data[pos + 19].toInt() and 0xFF) shl 24)
                    if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES * 2) throw ProtocolException("frameLength: OP_SYNC_RESPONSE payloadLen=$payloadLen out of range")
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
        val content: String,
        /** Client-generated timestamp included in the signed form (0 if absent). */
        val clientTs: Long = 0L,
        /** Client-generated per-message nonce included in the signed form (empty if absent). */
        val nonce: String = "",
        /** Recipient fingerprint for a private message; empty = group broadcast. */
        val dm: String = ""
    )

    /**
     * Parses the inner envelope. Uses a tolerant, allocation-free scanner that
     * extracts the known string fields by name. Returns null if the envelope
     * cannot be parsed (callers drop the frame rather than crash).
     */
    fun parseInnerEnvelope(bytes: ByteArray): InnerEnvelope? {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            InnerEnvelope(
                sender = optJsonField(text, "sender") ?: "Unknown",
                signature = optJsonField(text, "signature") ?: "",
                content = optJsonField(text, "content") ?: "",
                clientTs = optJsonLong(text, "ts"),
                nonce = optJsonField(text, "n") ?: "",
                dm = optJsonField(text, "dm") ?: ""
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

    /** Extracts an optional numeric field (`"name":123`) or 0 if absent. */
    private fun optJsonLong(json: String, name: String): Long {
        val key = "\"$name\""
        val idx = json.indexOf(key)
        if (idx < 0) return 0L
        var p = idx + key.length
        while (p < json.length && (json[p] == ':' || json[p] == ' ' || json[p] == '\t')) p++
        if (p >= json.length) return 0L
        val start = p
        while (p < json.length && json[p].isDigit()) p++
        if (p == start) return 0L
        return json.substring(start, p).toLongOrNull() ?: 0L
    }
}
