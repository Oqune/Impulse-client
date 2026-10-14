# Persistent replay journal for signed messages

Date: 2026-10-14  
Status: Approved for implementation by project owner

## Problem

The client currently records accepted `(sender fingerprint, nonce)` pairs only in a 2,000-entry process-local LRU. A restart clears the replay defense, and eviction permits a previously accepted signed envelope to be accepted again. The in-memory marker is added before the Room message write, so process death or a database error can consume a nonce without preserving the message.

## Goals

- Persist replay markers across process restarts for the entire valid signed-message freshness window.
- Atomically claim a marker and persist the corresponding encrypted message in one Room transaction.
- Do not consume a marker for messages with missing/invalid signatures, invalid signed timestamps, duplicate nonces, or failed message persistence.
- Preserve existing messages when upgrading Room schema from version 5 to version 6.
- Keep marker storage metadata-only: store sender fingerprint, a fixed-size SHA-256 digest of nonce UTF-8 bytes, and expiry time; never store message plaintext or the raw signed nonce.
- Keep Wire v3 bytes, signature canonicalization, KDF, and server behavior unchanged.

## Replay-key scope and expiry

The uniqueness key is global `(senderFingerprint, nonceHash)`, without `serverId`. The signed canonical envelope has no server identifier, so adding a local server scope would allow the same valid signed envelope to be replayed through another server. Sender fingerprint is derived from the sender's KEM public-key hash and nonce is covered by the existing signature.

For nonzero signed timestamps that pass the existing ±96-hour freshness window, set expiry to `max(acceptedNow, signedClientTimestamp) + 96 hours`. This retains a marker for up to 192 hours after acceptance when the sender's clock is ahead, so it cannot expire while that signed timestamp remains acceptable. Envelopes with an empty nonce remain accepted with existing compatibility behavior and cannot be durably deduplicated. For a nonempty nonce with zero timestamp, use `acceptedNow + 96 hours`; report empty-nonce messages as an explicit remaining replay limitation.

Replace `abs(now - clientTs)` with overflow-safe range checks (`clientTs < now - window || clientTs > now + window`) so `Long.MIN_VALUE` cannot bypass freshness validation.

## Persistence and concurrency

- Add a Room `seen_nonces` table with composite primary key `(sender_fingerprint, nonce_hash)` and an index on `expires_at`.
- In one `@Transaction` DAO method: delete expired markers, reject an existing key, fail closed if 250,000 live markers already exist (never evict unexpired markers), insert the marker with `IGNORE`, then replace/upsert the encrypted message row. Rollback both writes on any SQL failure.
- Return explicit statuses for accepted, duplicate replay, and journal-capacity rejection. Capacity rejection must be logged and surface a generic user-visible error; do not silently drop the message or imply delivery.
- Keep the existing in-memory 2,000-entry LRU only as a fast duplicate accelerator. Check it only after signature and timestamp validation; update it only after the durable Room transaction commits successfully. The Room unique key remains authoritative for races and restarts.
- Factor signature verification and overflow-safe signed-timestamp freshness validation into the existing small envelope helper's verify-then-persist seam. Its persistence callback must not run on missing/invalid signature or stale timestamp; this seam is exercised with real ML-DSA keys so invalid signatures cannot consume a nonce.
- Purge expired markers during accepted-message transactions and alongside existing periodic/connect cleanup. Never delete active markers to make room.
- Do not backfill historical rows automatically. Verifying every old encrypted row may be expensive. A one-time replay of a message accepted before this migration can be displayed once after upgrade; after its transactional acceptance its marker persists. Document this non-retroactive boundary.
- Clearing message history must not clear the replay journal.

## Migration and data preservation

- Increment Room schema version from 5 to 6 and add an explicit additive 5→6 migration that only creates the new table/index; no message table rewrite or row deletion.
- Remove the existing destructive migration fallback. An unsupported schema must fail visibly rather than silently erase user history. Existing v1→v5 migration steps remain registered.
- Add an instrumentation migration test that creates a v5 database containing messages, opens it through the v5→v6 migration, confirms every message row survives, closes/reopens the v6 DB, and confirms replay uniqueness remains enforced.

## Validation

- Instrumented DAO tests: first `(sender, nonce)` commits both nonce and message; repeat with a different server/message ID is rejected; a different nonce is accepted; expired marker is removed and reusable; capacity rejects without evicting active markers; transaction rollback leaves neither marker nor message on injected message-write failure.
- A focused test using real signature verification demonstrates that an invalid signature never calls the durable acceptance path and therefore does not consume its nonce.
- Existing JVM and Android test/build gate is run by the coordinating agent; this agent does not run builds.

## Compatibility limits and non-goals

- Legacy envelopes with empty nonce have no replay identifier and remain outside durable replay protection.
- Markers created before this migration are not reconstructible without decrypting and verifying old history; no eager backfill is included.
- A successful transaction proves local acceptance only. Wire v3 has no end-to-end delivery acknowledgement, so this change does not guarantee remote delivery.
- No opcode, envelope field, signature, key, Room message row, or server change is made.
