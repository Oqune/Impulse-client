# Verify persisted message signatures on history reads

Date: 2026-10-13
Status: Approved for implementation by project owner
Scope: Android client controller, view model, a small signature-verification helper, and JVM regression tests. No Wire v3, cryptographic primitive, Room schema, or server change.

## Problem

Incoming live and synchronized messages are verified with the sender's cached ML-DSA key before insertion into Room. Restored history follows a different path: `ChatViewModel` calls `ChatController.decryptEntity`, which decapsulates/decrypts the stored relay ciphertext and extracts the envelope but does not verify its signature. `decryptHistory` has the same gap. This means the DB read boundary does not uphold the client policy that messages are shown only after successful ML-DSA verification.

The signed envelope remains inside `MessageEntity.ciphertext`; sender fingerprint and cached public keys are available per server. Therefore the same authenticated fields can be reconstructed on read without changing the stored schema or network protocol.

## Requirements

- Extract canonical-envelope construction and signature validation into a small reusable helper used by both live/sync ingestion and history restoration.
- Preserve backward compatibility for legacy signed envelopes without timestamp/nonce fields; use the exact same canonical forms currently used by `processVerifiedMessage`.
- For a restored row, derive the sender fingerprint from the encrypted blob, look up that server's cached DSA public key, decode the envelope signature, and verify before returning any message for display.
- If a key is missing, a signature is absent/malformed, or verification fails, fail closed and return no message. Keep the existing key-exchange/pending-message recovery path used by incoming sync; do not introduce a database migration or fabricate a key.
- Do not run the process-local replay cache or freshness/clock rejection for previously accepted rows. The persisted history is expected to contain messages that already passed these checks when received; applying a fresh nonce check would incorrectly hide them after restart.
- Derive `isOwn` from the signed sender fingerprint and local KEM fingerprint. Derive group/DM conversation identity from the signed `dm` field using the same rule as live ingestion. Do not trust mutable `is_own` or `conversation_id` DB columns as authenticity input; reject rows whose stored routing metadata conflicts with the signed envelope so a tampered row cannot appear in the wrong thread.
- Do not silently rewrite conflicting legacy rows. If a historically malformed or pre-feature row has routing metadata that disagrees with the signed envelope, keep it hidden and log the rejection; migration/backfill policy is a separate decision.
- Make `decryptEntity` suspendable as needed to fetch cached keys. Update the `ChatViewModel` flow using a suspend-compatible loop rather than attempting a suspend call inside collection `mapNotNull`.
- Add focused JVM tests for valid signature, missing/invalid signature, and signed-field alteration. Keep tests independent of Android Keystore by injecting the verifier into the helper.

## Non-goals

- No database schema/index migration or backfill marker.
- No change to message signatures, key exchange, Wire v3 packet bytes, replay semantics for newly received messages, or server behavior.
- No biometric, UI copy, or trust onboarding changes.
- No fallback that displays unverified cached plaintext when a key lookup fails.

## Acceptance criteria

- Live/sync and history paths use the same canonical signature verification helper.
- History loads only when the cached sender DSA key exists and validates the stored envelope.
- Altering content, sender, timestamp, nonce, or DM target causes verification failure because those fields are covered by the canonical form used for that envelope version.
- Group/DM and own/incoming metadata is derived from signed envelope/key identity; conflicting persisted routing flags fail closed.
- No protocol bytes, Room entities, schema version, or server code change.
- Root-owned test gate is run after implementation; this agent does not run builds unless asked.
