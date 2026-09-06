# SPEC: Client Concurrency Resilience, Temp-ID Collision & Replay Hardening (C4)

- **Date:** 2026-09-06
- **Status:** APPROVED-FOR-IMPLEMENTATION
- **Scope:** `client/` (Kotlin Android)
- **Files Affected:**
  - `client/app/src/main/java/com/example/impulse/transport/WebTransportClient.kt`
  - `client/app/src/main/java/com/example/impulse/ChatController.kt`
  - `client/app/src/main/java/com/example/impulse/transport/Protocol.kt`
  - `client/app/src/test/java/com/example/impulse/ProtocolTest.kt` (or corresponding unit test)

---

## 1. Problem Statement & Objectives

1. **ANR Prevention (Main-Thread Safety):**
   `WebTransportClient.destroy()` and `ChatController.disconnect()` currently execute `runBlocking` with 1000ms timeouts inside locks. If invoked from the Android main thread (e.g. Activity lifecycle, UI back navigation, user logout), this risks triggering an ANR (Application Not Responding). We must transition teardown and disconnect signaling to asynchronous non-blocking routines on `Dispatchers.IO`.

2. **Temp-ID Collision Fix:**
   `ChatController.sendChat()` assigns optimistic local messages a temporary ID of `-(System.currentTimeMillis())`. Rapid sequential sends within the same millisecond or clock skew can cause duplicate primary key collisions in memory and DB outbox tracking. We replace this with a thread-safe monotonic counter (`AtomicLong(-1L)`).

3. **Wire Protocol Limit Alignment:**
   `Protocol.kt::frameLength` enforces `payloadLen > MAX_PAYLOAD_BYTES * 2` for `OP_SYNC_RESPONSE`, whereas the rest of the codebase and the Rust server strictly enforce `MAX_PAYLOAD_BYTES = 1_000_000`. We unify this limit to eliminate potential buffer desynchronization.

4. **Replay Cache Hardening (Audit Finding C4):**
   While incoming messages verify ML-DSA-65 signatures over canonical payloads including client nonces, the client previously relied solely on `serverMsgId` for deduplication. A malicious relay could replay an old signed message by assigning it a new `serverMsgId`. We introduce an in-memory bounded LRU cache of `(senderFingerprint, nonce)` pairs to reject replayed messages immediately.

---

## 2. Detailed Technical Design

### 2.1. Non-blocking Resource Teardown (`WebTransportClient.kt`)
- In `destroy()`:
  - Mark `intentionalClose.set(true)`.
  - Capture references to `stream` and `session`.
  - Launch socket close operations asynchronously in `CoroutineScope(Dispatchers.IO + NonCancellable).launch { ... }` with timeouts, rather than blocking the calling thread.
  - Clear references and cancel the main client scope.

### 2.2. Non-blocking Disconnect (`ChatController.kt`)
- In `disconnect()`:
  - Perform best-effort notification (`Protocol.buildDisconnect()`) on `Dispatchers.IO` via fire-and-forget coroutine.
  - Avoid `runBlocking` on the calling thread.

### 2.3. Monotonic Temp-ID Generator (`ChatController.kt`)
- Define `private val tempIdCounter = AtomicLong(-1L)`.
- In `sendChat()`: `val tempId = tempIdCounter.getAndDecrement()`.

### 2.4. Protocol Limit Unification (`Protocol.kt`)
- In `frameLength(OP_SYNC_RESPONSE)`:
  - Replace `MAX_PAYLOAD_BYTES * 2` with `MAX_PAYLOAD_BYTES`.

### 2.5. Replay Cache Implementation (`ChatController.kt`)
- Add a thread-safe LRU set `seenNonces: MutableMap<String, Long>` (bounded to 2000 entries) keyed by `"senderFp:nonce"`.
- When processing incoming signed envelopes with non-empty nonces:
  - If `"senderFp:nonce"` exists in `seenNonces`, reject as a duplicate replay attack.
  - Otherwise, record `"senderFp:nonce"` with current timestamp.

---

## 3. Verification Plan
- Unit tests: `./gradlew testDebugUnitTest` must pass completely.
- Verify that `ProtocolTest` passes with the aligned 1 MiB limit.
