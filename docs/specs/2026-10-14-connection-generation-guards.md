# Connection-generation guards for stale callbacks and outbox sends

Date: 2026-10-14
Status: Implemented within the owner's authorized audit and remediation scope; root review and final gates required

## Problem

`ChatController` reuses mutable controller-wide server and transport fields across reconnects. A callback or authentication coroutine already in flight from a previous connection can therefore mutate the current connection state or send work through the newly assigned transport. The outbox snapshots frames for one server but currently looks up the global transport for each frame, allowing cross-server delivery if the selected server changes during a flush.

## Change

- Give each connect/disconnect a monotonically increasing connection generation and bind callbacks to the generation, server ID, and originating transport.
- Ignore stale state, frame, session-error, certificate-push, and ready callbacks.
- Carry the originating server, transport, and auth challenge through `sendAuth` and `onAuthResult` work. Recheck connection identity after suspension and before global state changes or network sends.
- Capture one connection identity and transport for an outbox batch. Stop the pass when that connection is no longer current; never send a queued frame for one server through another server's transport.
- Capture the ready connection before key lookup and encryption in `sendChat`/`sendDirectMessage`; send only through that transport and queue any failed frame under its captured server ID. If the connection changes before local echo, return failure without creating a placeholder.
- Preserve message bodies, outbox contents, connection APIs, and Wire v3 unchanged.
- Add a small JVM regression test for identity changes across server, generation, and transport.

## Validation

- Root runs the Gradle, instrumentation, and device gates after this patch.
- This agent does not run Gradle.

## Scope

Only controller connection lifecycle/routing and the identity helper/regression test. No protocol, database, cryptographic algorithm, UI, transport implementation, or release configuration changes.
