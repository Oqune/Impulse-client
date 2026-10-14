# WebTransport connection and session lifetime

Date: 2026-10-14
Status: Implemented within the owner's authorized audit scope; final verification required

## Problem

The client uses `com.ditchoom:socket-http3:3.9.5`. Its `withHttp3Connection` delegates to `withQuicConnection`, whose timeout encloses both QUIC/HTTP3 establishment and the entire caller block. The caller block includes WebTransport setup and the long-running stream read loop. Therefore a finite 300-second timeout closes an otherwise active connection after five minutes, regardless of received heartbeat/data.

The client already has a 15-second connection-establishment deadline and a 90-second read-idle timeout, but the establishment timer only changes UI state; it does not cancel the coroutine waiting for the network operation.

## Requirements

- Remove the arbitrary absolute lifetime limit on an established WebTransport session.
- Make the existing finite 15-second establishment deadline cancel the active connection coroutine while the state is still `CONNECTING`.
- Keep the finite 90-second read-idle timeout, QUIC idle timeout and keepalive settings.
- Preserve structured cancellation so the library's `finally` closes the QUIC connection and propagates `CancellationException`.
- Keep Wire v3, pinning, frame handling, reconnect behavior, and session APIs unchanged.

## Implementation

- Pass `Duration.INFINITE` to `withHttp3Connection`; this disables its wrapper timeout for the lifetime-scoped callback and does not add a large guessed session duration.
- At the existing connect deadline, atomically recheck `CONNECTING` under the client's lock, cancel the current connection job, and transition the client to `ERROR`.
- Keep the connection deadline active until the WebTransport stream is ready; cancel it at the existing successful setup point.
- Update the transport log to report the actual 15-second connection deadline rather than claiming the wrapper provides a 300-second handshake timeout.

## Validation

- Source review verifies the pinned library's timeout wraps both `engine.connect` and `connection.block()`.
- Root runs the full Android build and live two-device relay soak. The soak must remain connected beyond five minutes with heartbeat/data, while a non-responsive endpoint still reaches `ERROR` at the finite connection deadline and a silent established stream still trips the finite read-idle timeout.
- The opt-in deadline device test measures actual setup failure at a non-responsive endpoint with no opened session. Read-idle protection is retained and reviewed by source; a forced 90-second silent established peer was not exercised.
- Initial Sync and live broadcast use separate relay writer channels and can interleave. Flood verification requires all 100 messages exactly once and chronological persisted history (the chat UI source), rather than an unsupported promise that listener callbacks arrive in global relay-ID order. Preserve the failed strict-callback run as evidence of that distinction.

## Limits

The client cannot impose a second precise handshake-only timeout through this library's scope API; the application-level connection deadline supplies it by cancelling the whole setup coroutine. DNS resolution performed by the platform may not be interruptible immediately, though cancellation is observed after that call returns. An established session remains bounded by explicit disconnect/destroy, parent coroutine cancellation, QUIC idle behavior, and the read-idle timeout.
