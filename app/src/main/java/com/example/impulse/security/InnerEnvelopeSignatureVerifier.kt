package com.example.impulse.security

import com.example.impulse.data.ReplayNonce
import com.example.impulse.transport.Protocol

/** Shared canonicalization and signature check for live and persisted envelopes. */
internal object InnerEnvelopeSignatureVerifier {
    suspend fun <T> verifyFreshAndPersist(
        envelope: Protocol.InnerEnvelope,
        publicKey: ByteArray,
        now: Long,
        freshnessWindowMs: Long,
        verifySignature: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean,
        persist: suspend () -> T
    ): T? {
        if (!verify(envelope, publicKey, verifySignature)) return null
        if (!ReplayNonce.isTimestampFresh(envelope.clientTs, now, freshnessWindowMs)) return null
        return persist()
    }

    fun verify(
        envelope: Protocol.InnerEnvelope,
        publicKey: ByteArray,
        verifySignature: (publicKey: ByteArray, message: ByteArray, signature: ByteArray) -> Boolean
    ): Boolean {
        val signature = runCatching { Protocol.base64Decode(envelope.signature) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val canonical = if (envelope.clientTs != 0L || envelope.nonce.isNotEmpty()) {
            Protocol.buildSignedInnerEnvelope(
                envelope.sender, "", envelope.content, envelope.clientTs, envelope.nonce, envelope.dm
            )
        } else {
            Protocol.buildInnerEnvelope(envelope.sender, "", envelope.content)
        }
        return runCatching { verifySignature(publicKey, canonical, signature) }.getOrDefault(false)
    }
}
