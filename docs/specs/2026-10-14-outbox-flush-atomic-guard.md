# Atomic outbox flush ownership

Date: 2026-10-14  
Status: Approved by project owner

## Problem

`ChatController` checks a volatile Boolean before launching an outbox flush, then the coroutine checks and sets it in separate operations. Concurrent send-failure and READY callbacks can both observe `false`, launch workers, and transmit the same queued encrypted frame more than once.

## Scope and requirements

- Replace the read-then-write Boolean guard with `AtomicBoolean.compareAndSet(false, true)` at the start of the flush coroutine.
- Use atomic reads at existing scheduling sites as an optimization only; the CAS is the correctness boundary.
- Release ownership in `finally` with `set(false)` after the flush attempt exits.
- Do not reset the flag from disconnect while a worker may still be in `send`; only the worker's `finally` releases ownership.
- Preserve per-server queue behavior, persistence, retry-on-READY semantics, and Wire v3 bytes.
- No new test seam is required for this primitive; existing outbox retry tests continue to cover retained frames and successful removal. Concurrency must be reviewed against the actual CAS boundary.

## Non-goals

- No change to send acknowledgement semantics, retry policy, persistence format, crypto, or server protocol.
