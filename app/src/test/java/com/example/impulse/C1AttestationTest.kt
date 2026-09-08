package com.example.impulse

import com.example.impulse.security.PqcCrypto
import com.example.impulse.transport.Protocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side C1 attestation test (SPEC §1 / MITM defense).
 *
 * The server-side `c1` test is `#[ignore]`d on purpose: per SPEC the relay
 * (server) is opaque and MUST NEVER see plaintext, so it cannot vouch for a
 * peer's key. Only the *receiving client* can enforce C1 — by verifying that an
 * incoming combined key-exchange carries a valid ML-DSA-65 signature over
 * `(kemPub || dsaPub)` from the peer it already trusts.
 *
 * This test drives the EXACT trust formula used by
 * [ChatController.onCombinedKeyExchange] (factored into
 * [Protocol.evaluateKeyExchangeTrust]) against REAL [PqcCrypto] primitives, so
 * it proves the production code path — not a copy.
 *
 * It models the rogue-relay attack the Sprint 0 server test guards against: a
 * malicious relay tries to substitute the victim peer's KEM key (or forge the
 * whole key pair) to mount a MITM. Because the receiver already pins the peer's
 * DSA public key from a prior TOFU session, any such substitution fails
 * signature verification and is REJECTED.
 */
class C1AttestationTest {

    // Build the attestation exactly as ChatController.onAuthResult does:
    //   keyManager.signDsa(kemPub + dsaPub)  ==  signMlDsa65(dsaPriv, kemPub + dsaPub)
    private fun attest(kemPub: ByteArray, dsaPub: ByteArray, dsaPriv: ByteArray): ByteArray =
        PqcCrypto.signMlDsa65(dsaPriv, kemPub + dsaPub)

    // A real, trusted peer (DSA keypair + a freshly generated KEM keypair).
    private data class Peer(val kemPub: ByteArray, val dsaPub: ByteArray, val dsaPriv: ByteArray)

    private fun makePeer(): Peer {
        val dsa = PqcCrypto.generateMlDsa65KeyPair()
        val kem = PqcCrypto.generateKeyPair()
        return Peer(kem.publicEncoded, dsa.publicEncoded, dsa.privateEncoded)
    }

    // ── 1. Honest peer, known DSA key, valid attestation → TRUSTED ─────────────
    @Test
    fun knownPeer_withValidAttestation_isTrusted() {
        val peer = makePeer()
        // Receiver already pins this peer's DSA key from a prior session.
        val existingDsa = peer.dsaPub
        val sig = attest(peer.kemPub, peer.dsaPub, peer.dsaPriv)

        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = existingDsa,
            kemPublicKey = peer.kemPub,
            dsaPublicKey = peer.dsaPub,
            signature = sig,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertTrue("valid attestation from a known peer must be trusted", trusted)
    }

    // ── 2. Rogue relays a SUBSTITUTED KEM key, signed with the rogue's own ─────
    //      DSA key while spoofing the victim's DSA public key in the frame.
    //      The receiver pins the VICTIM's DSA key, so verify(victimDsaPub, ...) fails.
    @Test
    fun knownPeer_rogueSubstitutesKemKey_isRejected() {
        val victim = makePeer()
        val rogue = makePeer()
        // Rogue forwards the VICTIM's DSA public key (so the receiver finds a
        // pinned key → not TOFU) but swaps in the rogue's own KEM key, signing
        // it with the rogue's DSA private key.
        val rogueKemPub = rogue.kemPub
        val sig = attest(rogueKemPub, victim.dsaPub, rogue.dsaPriv)

        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = victim.dsaPub, // receiver pins the VICTIM's DSA key
            kemPublicKey = rogueKemPub,
            dsaPublicKey = victim.dsaPub, // spoofed to match the pinned key
            signature = sig,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertFalse("rogue KEM-key substitution under a pinned DSA key must be REJECTED", trusted)
    }

    // ── 3. Rogue forges the attestation (random bytes) for the known peer ──────
    @Test
    fun knownPeer_forgedAttestation_isRejected() {
        val victim = makePeer()
        val forged = ByteArray(64) { (it * 7).toByte() } // garbage, not a real sig
        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = victim.dsaPub,
            kemPublicKey = victim.kemPub,
            dsaPublicKey = victim.dsaPub,
            signature = forged,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertFalse("forged attestation for a known peer must be REJECTED", trusted)
    }

    // ── 4. Attestation must cover BOTH keys — signing only kemPub is not enough ─
    @Test
    fun knownPeer_attestationMissingDsaPub_isRejected() {
        val victim = makePeer()
        // Sign ONLY (kemPub) instead of (kemPub || dsaPub): the verify step binds
        // the signature to (kemPub || dsaPub), so this must fail.
        val wrongDataSig = PqcCrypto.signMlDsa65(victim.dsaPriv, victim.kemPub)
        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = victim.dsaPub,
            kemPublicKey = victim.kemPub,
            dsaPublicKey = victim.dsaPub,
            signature = wrongDataSig,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertFalse("attestation must bind BOTH keys; signing kemPub alone must be REJECTED", trusted)
    }

    // ── 5. Sender→receiver wire roundtrip: build via Protocol, verify via trust ─
    @Test
    fun buildThenEvaluate_roundtrip_honestPeer_trusted() {
        val peer = makePeer()
        val sig = attest(peer.kemPub, peer.dsaPub, peer.dsaPriv)
        // Sender serialises exactly as ChatController does.
        val frame = Protocol.buildCombinedKeyExchange(peer.kemPub, peer.dsaPub, sig)
        // Receiver parses exactly as ChatController.onCombinedKeyExchange does.
        val parsed = Protocol.parseCombinedKeyExchange(Protocol.Reader(frame, 1))

        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = peer.dsaPub,
            kemPublicKey = parsed.kemPublicKey,
            dsaPublicKey = parsed.dsaPublicKey,
            signature = parsed.signature,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertTrue("honest sender→receiver wire roundtrip must verify", trusted)
    }

    // ── 6. TOFU: a brand-new DSA key (never seen) is accepted, NOT rejected ────
    //      This is by design — first contact trusts on TOFU, and the UI must
    //      surface an out-of-band QR/pin confirmation (defense in depth). We
    //      assert the expected behaviour so a future regression that hard-rejects
    //      TOFU (breaking first-run pairing) is caught.
    @Test
    fun brandNewPeer_isAcceptedOnTofu() {
        val peer = makePeer()
        val sig = attest(peer.kemPub, peer.dsaPub, peer.dsaPriv)
        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = null, // first contact, nothing pinned yet
            kemPublicKey = peer.kemPub,
            dsaPublicKey = peer.dsaPub,
            signature = sig,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertTrue("first-contact (TOFU) peer must be accepted; out-of-band confirm is the UI's job", trusted)
    }

    // ── 7. Rogue replaces BOTH KEM and DSA keys and provides a valid signature ─
    //      under rogue DSA key. Receiver pins victim DSA key, so this must be REJECTED.
    @Test
    fun knownPeer_rogueReplacesBothKemAndDsaKeys_isRejected() {
        val victim = makePeer()
        val rogue = makePeer()
        // Rogue signs its own KEM key with its own DSA key
        val rogueSig = attest(rogue.kemPub, rogue.dsaPub, rogue.dsaPriv)
        val trusted = Protocol.evaluateKeyExchangeTrust(
            existingDsa = victim.dsaPub, // pinned victim key
            kemPublicKey = rogue.kemPub,
            dsaPublicKey = rogue.dsaPub, // rogue sends its own DSA key
            signature = rogueSig,
            verify = { dsaPub, data, s -> PqcCrypto.verifyMlDsa65(dsaPub, data, s) }
        )
        assertFalse("rogue replacing both KEM and DSA keys against pinned peer must be REJECTED", trusted)
    }
}
