# Inbound processing backpressure and pending-key bounds

Date: 2026-10-14  
Status: Approved for implementation by project owner

## Problem

The WebTransport read loop invokes a non-suspending frame callback. Live message handling launches per-frame coroutines, and combined key exchange work also escapes into a coroutine; sync responses similarly launch a worker and return to reading. A busy authenticated relay can therefore outpace signature/KEM/database work and retain an unbounded number of tasks and decoded payloads. Messages waiting for an unknown sender key are accumulated without global, per-sender, byte, or short expiry limits.

## Goals

- Propagate receive work completion back to the WebTransport stream reader so the transport's existing flow control provides backpressure.
- Process live data and sync history sequentially in receive order without creating one coroutine per inbound message.
- Bound pending messages that cannot yet be verified because their sender key is unknown.
- Preserve Wire v3 bytes, cryptography, accepted-message behavior, sync cursor semantics, and outgoing ACK behavior.
- Propagate coroutine cancellation instead of converting it into an ordinary malformed-frame or message error.

## Receive callback and sequencing

- Change `WebTransportClient`'s `onFrame` callback to `suspend (ByteArray) -> Unit`; its existing suspend frame dispatcher invokes it directly from the read loop.
- Make `ChatController.handleIncoming`, `onData`, `onSyncResponse`, and `onCombinedKeyExchange` suspend, and await their existing crypto/key-cache/repository operations sequentially.
- Keep `onAuthResult` asynchronous because it sends the post-auth key exchange and sync request before publishing READY. `onAuthChallenge` remains immediate and completes the existing deferred without waiting on a follow-up frame.
- Do not wait for a network acknowledgement from any receive callback. Processing an inbound frame is local acceptance only.
- Preserve existing fail-closed parsing behavior: malformed frames are logged and dropped. CancellationException must be rethrown through controller handlers, per-frame dispatch, and sync processing. Other parse/verification failures retain their current handling.
- This serializes the reader while synchronous crypto/storage work runs. Validate this against the real relay/device scenario; do not claim high-rate 500-message processing without measurement.

## Unknown-key pending queue

- All pending insertions and requeues go through one admission helper under the existing lock.
- Before admission, discard expired entries. Pending TTL is 30 seconds.
- Enforce maximum 128 pending messages globally, 16 per sender fingerprint, and 8 MiB aggregate retained content. Account for the ciphertext plus UTF-8 envelope fields and a fixed per-entry overhead.
- On overflow, reject the newest pending item. Keep no raw ciphertext or plaintext in logs; emit a generic pressure warning/error through the existing controller status path. This is a local resource rejection and must not be described as delivery or acknowledged success.
- Remove byte accounting when entries are expired, dequeued, cleared on disconnect, or successfully moved for processing. If an item is requeued after a transient key lookup miss, it must pass the same limits and TTL rule.
- Existing valid frames with available keys are unaffected by pending queue limits.

## Validation

- JVM unit tests for pending admission at global/per-sender/message-byte limits, rejection without exceeding limits, stale-entry cleanup, dequeue/clear accounting, and requeue admission.
- Coordinating agent runs the existing opt-in `LiveRelayTest` with two devices and an additional authenticated group-message flood/ordering scenario. Assert delivery order and that the connection remains READY; retain the existing direct/offline replay checks.
- Coordinating agent runs normal Android build, unit, lint, and instrumentation gates. This agent does not run builds.

## Non-goals and compatibility limits

- No Wire opcode, frame structure, E2EE key, signature, database schema, migration, ACK, or delivery-cursor change.
- This does not add a global work queue or background message scheduler. A long sync batch can hold the reader until its current frame has been processed; the real relay test must assess practical latency.
- Pending messages can be discarded when resource caps or the 30-second TTL are reached; the UI surfaces a generic warning, and Wire v3 still has no end-to-end delivery acknowledgement.
