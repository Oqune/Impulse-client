# Android client hardening — 2026-10-13

## Changes made

This focused follow-up implements the owner-approved scope in [2026-10-13-auth-outbox-instrumentation-hardening.md](/D:/Data/Projects/ImpulseProject/client/docs/specs/2026-10-13-auth-outbox-instrumentation-hardening.md). It changes Android client files only. No commit, push, version change, or Gradle command was run by this agent; the coordinating agent will run test gates.

- AuthChallenge Argon2id tuples are now validated within `m=47104..65536 KiB`, `t=3..6`, `p=1..2`. The floor preserves OWASP and the server's current `47104/3/1` default. The ceiling limits hostile pre-auth KDF admission to 64 MiB, six passes, and two lanes; inputs above or below the supported range, malformed/duplicate/unknown/incomplete fields fail closed. Legacy challenges without the optional tuple still use server-compatible defaults. Validation also runs at the KDF entry point to prevent accidental internal bypass. Regression cases cover defaults, minimum/maximum bounds, malformed tuples, each over-limit field, actual BC derivation at the maximum tuple, and exact challenge salt/tuple use in auth proofs.
- Outbox flushing now sends one snapshot and stops on the first failed write. It retains and persists that encrypted frame for the next READY connection instead of dropping it after three attempts. Successful stream writes still remove the frame. The wire format and outgoing frame bytes are unchanged; persisted JSON continues to read older retry metadata by ignoring that optional field. The helper tests cover repeated failed passes, later success, and disconnected passes.
- Restored outbox preferences are retained until a send succeeds. Restoration merges by server ID and frame bytes, so repeated READY/reconnect restoration does not duplicate frames; writes update the preference for the entry's server even if the active server changes. `SharedPreferences.apply()` remains asynchronous, so an immediate process kill can still race the disk write; changing to synchronous commits or a transactional store is outside this narrow patch.
- Outbox flush ownership now uses an `AtomicBoolean` CAS; concurrent send and READY callbacks cannot both enter the send loop. Disconnect no longer releases a flag owned by an in-flight flush; its `finally` block is the only release.
- History reads now use the same canonical-envelope signature verifier as live/sync ingestion. The saved encrypted envelope supplies signed sender/content/time/nonce/DM data; the corresponding server-scoped cached DSA key is required. Missing keys/signatures and invalid signatures fail closed. The read path skips freshness/replay checks for previously accepted rows, derives own/group/DM identity from signed content, and rejects stored routing metadata that conflicts. Focused JVM tests use real ML-DSA keys for valid and legacy canonical forms, missing/malformed/invalid signatures, and alterations to each signed field. A malformed historic row with conflicting stored routing flags is hidden without a silent rewrite; any migration/backfill needs a separate policy.
- The in-memory Room instrumentation test now calls the in-memory DAO for TTL deletion; it asserts distinct negative/positive server message IDs remain separate under the actual unique index and tests replacement for the same server message ID. It no longer calls the production `MessageRepository` for test rows.
- Trusted certificate instrumentation fixtures now use valid 64-character SHA-256 hex values and clear their test records before/after each case.
- Biometric unlocked state now lives in an Activity-retained `ViewModel`, so it survives rotation but is neither saved for process restoration nor retained after a real background stop. A configured available biometric gate also delays relay connection until unlock. The lock screen only launches its prompt while the Activity is started. Device testing/enrollment remains pending with the coordinating agent; behavior is unverified on platforms without biometric or secure device credential enrollment.
- Signed-message replay defense now uses a Room `seen_nonces` journal keyed by sender fingerprint plus a 64-hex SHA-256 hash of the nonce. Marker insertion and encrypted message persistence share a transaction; markers retain the full ±96-hour acceptance window and the process cache updates only after commit. The journal fails closed at 250,000 live markers, purges expired entries, survives message-history clearing and process restart, and is intentionally global across servers because server identity is not signed. Room upgrades v5→v6 additively, and the destructive migration fallback is removed to prevent silent loss of existing history. Legacy empty-nonce envelopes remain outside replay protection, and existing history is not backfilled, so a pre-upgrade message can be accepted once after migration. Tests now cover migration/reopen, cross-server duplicate rejection, expiry, capacity, SQL rollback, and real ML-DSA invalid-signature non-consumption. The coordinating agent owns all build and device test gates.

## Validation status

Implementation is ready for the coordinating agent's Android test/build gate. Runtime and instrumentation results are pending; no code compilation is claimed here. The test environment observed during initial audit had one API 36 AVD and one running emulator. Initial source unconditionally rejected emulator builds in `WebTransportClient`, but the coordinating agent later identified x86_64 QUIC native libraries and owns the separate transport capability/spec change. That initial observation should not be treated as a remaining limitation after their change.

## Remaining audit issues outside this patch

- First-contact peer keys are accepted by self-signature TOFU without an identity comparison step; the trust UX needs to distinguish an unverified fingerprint from a verified human identity.
- Replay protection does not provide a remote delivery acknowledgement; Wire v3 has no end-to-end ACK. The nonce journal records local acceptance only. Empty-nonce legacy messages and the one-time pre-migration replay boundary remain explicit limits.
- Replay protection is still process-local and bounded; the history-read signature gap is addressed by this follow-up, but old rows without a retained sender key remain hidden by design.
- E2EE file/media transfer and voice notes remain roadmap items. An mDNS hostname can be parsed, but automatic network discovery is not implemented.

## Files changed

- `client/app/src/main/java/com/example/impulse/transport/Protocol.kt`
- `client/app/src/main/java/com/example/impulse/OutboxRetry.kt`
- `client/app/src/main/java/com/example/impulse/ChatController.kt`
- `client/app/src/main/java/com/example/impulse/MainActivity.kt`
- `client/app/src/main/java/com/example/impulse/ui/screens/BiometricLockScreen.kt`
- `client/app/src/main/java/com/example/impulse/ui/ChatViewModel.kt`
- `client/app/src/main/java/com/example/impulse/security/InnerEnvelopeSignatureVerifier.kt`
- `client/app/src/test/java/com/example/impulse/transport/ProtocolTest.kt`
- `client/app/src/test/java/com/example/impulse/OutboxRetryTest.kt`
- `client/app/src/test/java/com/example/impulse/security/InnerEnvelopeSignatureVerifierTest.kt`
- `client/app/src/test/java/com/example/impulse/data/ReplayNonceTest.kt`
- `client/app/src/androidTest/java/com/example/impulse/data/db/MessageDaoTest.kt`
- `client/app/src/main/java/com/example/impulse/data/ReplayNonce.kt`
- `client/app/src/main/java/com/example/impulse/data/db/SeenMessageNonceEntity.kt`
- `client/app/src/main/java/com/example/impulse/data/db/ReplayPersistenceResult.kt`
- `client/docs/specs/2026-10-14-persistent-replay-journal.md`
- `client/app/src/androidTest/java/com/example/impulse/security/TrustedCertManagerTest.kt`
- `client/docs/specs/2026-10-13-auth-outbox-instrumentation-hardening.md`
- `client/docs/specs/2026-10-13-history-signature-verification.md`
- `client/docs/specs/2026-10-14-outbox-flush-atomic-guard.md`
- `client/docs/specs/2026-10-14-biometric-background-relock.md`
- `client/docs/audit/2026-10-13-client-hardening.md`
